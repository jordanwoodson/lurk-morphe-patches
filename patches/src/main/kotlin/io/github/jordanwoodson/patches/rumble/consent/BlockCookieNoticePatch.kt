package io.github.jordanwoodson.patches.rumble.consent

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PRIVACY
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.enumWith
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.shared.settings.requireLocals

private const val KEY = "block_cookie_notice"
private const val AIRSHIP = "Lcom/urbanairship/UAirship;"

@Suppress("unused")
val blockCookieNoticePatch = bytecodePatch(
    name = "Block cookie notice",
    description = "Stops Rumble's Ketch consent SDK from loading, so the cookie notice never appears and no " +
        "consent is recorded, and ignores consent recorded earlier. AppsFlyer, Firebase Analytics, Crashlytics, " +
        "Firebase Performance and the Facebook SDK's app events then stay off, as before anyone consents. " +
        "Push notifications keep working: Airship, Rumble's push service, still starts after a restart, with " +
        "only the essential purpose Rumble asks for and its own analytics off. Settings > Data Consent no " +
        "longer opens.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Rumble calls load() on every resume. Where no privacy law applies, Ketch records consent to
        // every purpose without showing anything, which starts AppsFlyer and Firebase Analytics.
        // showPreferences() (Settings > Data Consent) records the same defaults as soon as it opens.
        // Returning false is what both do when they cannot create their WebView: the SDK never contacts
        // Ketch, the advertising ID Rumble hands it is never sent, and no consent is stored.
        listOf(
            KetchLoadFingerprint to "Ketch load()",
            KetchShowPreferencesFingerprint to "Ketch showPreferences()",
        ).forEach { (fingerprint, what) ->
            fingerprint.single(what).apply {
                Locators.record("rumble.block_cookie_notice.ketch", classDef.type)
            }.method.addInstructionsIfEnabled(
                KEY,
                """
                    const/4 v0, 0x0
                    return v0
                """,
            )
        }

        // Consent recorded while the switch was off (or before this patch was included) is cached and
        // would still start the trackers on every launch. Report no cached decision while it is on.
        CachedConsentHasDecisionFingerprint.single("cached consent decision getter").apply {
            Locators.record("rumble.block_cookie_notice.cached_consent", classDef.type)
        }.method.addInstructionsIfEnabled(
            KEY,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        startAirshipOnly()

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Block cookie notice",
                "After restarting: no Ketch consent notice and no consent recorded. Push notifications still " +
                    "work, without Airship's analytics.",
                Section.PRIVACY,
                restart = true,
            ),
        )
    }
}

/**
 * Without a decision, Rumble starts none of its trackers, including Airship, its push service: the tracker
 * start loop runs only once consent exists. Granting essential_services through Rumble's own path would also
 * switch on Crashlytics and Firebase Performance (Rumble files them under the same purpose) and start
 * AppsFlyer, whose start doesn't depend on any purpose. So while the switch is on, the start loop starts only
 * the Airship tracker, with essential_services, which is what Airship's push feature needs.
 */
private fun BytecodePatchContext.startAirshipOnly() {
    val service = ConsentServiceFingerprint.single("consent service").classDef
    fun MethodReference.parameters() = parameterTypes.map { it.toString() }

    // The start loop: for (tracker in trackers) tracker.start(grantedPurposes, gdprApplies), after setting
    // its "started" flag. The update path (no flag) calls the tracker interface's other (Set, Boolean) method.
    val starts = service.methods.filter { method ->
        val code = method.implementation?.instructions ?: return@filter false
        method.parameterTypes.isEmpty() && method.returnType == "V" &&
            code.any { it.opcode == Opcode.IPUT_BOOLEAN } &&
            code.any {
                it.opcode == Opcode.INVOKE_INTERFACE &&
                    ((it as ReferenceInstruction).reference as MethodReference).parameters() == listOf("Ljava/util/Set;", "Z")
            }
    }
    val start = starts.singleOrNull()
        ?: throw PatchException("Rumble: expected one tracker start loop in the consent service, found ${starts.size}")
    val code = start.implementation!!.instructions.toList()
    val trackerStart = code.firstNotNullOf { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@firstNotNullOf null
        ((instruction as ReferenceInstruction).reference as MethodReference).takeIf {
            it.parameters() == listOf("Ljava/util/Set;", "Z")
        }
    }
    val started = code.firstNotNullOf { instruction ->
        if (instruction.opcode != Opcode.IPUT_BOOLEAN) null else (instruction as ReferenceInstruction).reference as FieldReference
    }
    val trackers = code.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@firstNotNullOfOrNull null
        ((instruction as ReferenceInstruction).reference as FieldReference).takeIf { it.type == "Ljava/util/Set;" }
    } ?: throw PatchException("Rumble: no tracker set in the consent service's start loop")

    // The Airship tracker: the tracker that takes off Airship.
    val tracker = trackerStart.definingClass
    val airshipTrackers = mutableListOf<String>()
    classDefForEach { classDef ->
        if (tracker !in classDef.interfaces) return@classDefForEach
        val usesAirship = classDef.methods.any { method ->
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == AIRSHIP
            } == true
        }
        if (usesAirship) airshipTrackers += classDef.type
    }
    val airship = airshipTrackers.singleOrNull()
        ?: throw PatchException("Rumble: expected one Airship consent tracker, found ${airshipTrackers.size}")
    val essential = enumWith("essential_services", "analytics", "targeted_advertising").enumConstant("essential_services")
    Locators.record("rumble.block_cookie_notice.consent_service", service.type)
    Locators.record("rumble.block_cookie_notice.start_loop", start)
    Locators.record("rumble.block_cookie_notice.tracker", tracker)
    Locators.record("rumble.block_cookie_notice.airship_tracker", airship)

    start.apply {
        requireLocals(4)
        addInstructionsIfEnabled(
            KEY,
            """
                iget-boolean v0, p0, $started
                if-nez v0, :done
                const/4 v0, 0x1
                iput-boolean v0, p0, $started
                iget-object v0, p0, $trackers
                invoke-interface { v0 }, Ljava/util/Set;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :next
                invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :done
                invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                instance-of v2, v1, $airship
                if-eqz v2, :next
                invoke-static { }, $RUMBLE_PRIVACY->startingAirship()V
                check-cast v1, $tracker
                sget-object v2, $essential
                invoke-static { v2 }, Ljava/util/Collections;->singleton(Ljava/lang/Object;)Ljava/util/Set;
                move-result-object v2
                const/4 v3, 0x1
                invoke-interface { v1, v2, v3 }, $trackerStart
                goto :next
                :done
                return-void
            """,
            locals = 4,
        )
    }

    // The Airship tracker enables Airship features for essential_services: push, contacts, tags and
    // attributes, in-app messages and Airship's own analytics. The extension leaves analytics out while the
    // switch is on.
    var featureCalls = 0
    mutableClassDefBy(airship).methods.forEach { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@forEach
        instructions.indices.filter { index ->
            val reference = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
            instructions[index].opcode == Opcode.INVOKE_VIRTUAL && reference != null &&
                reference.definingClass.startsWith("Lcom/urbanairship/") && reference.parameterTypes.size == 1 &&
                reference.parameterTypes[0].startsWith("[Lcom/urbanairship/")
        }.reversed().forEach { index ->
            featureCalls++
            val call = instructions[index] as FiveRegisterInstruction
            val features = call.registerD
            val type = ((call as ReferenceInstruction).reference as MethodReference).parameterTypes[0].toString()
            Locators.record("rumble.block_cookie_notice.airship_feature", type.removePrefix("["))
            method.addInstructions(
                index,
                """
                    ${invokeStatic("$RUMBLE_PRIVACY->airshipFeatures([Ljava/lang/Object;)[Ljava/lang/Object;", features)}
                    move-result-object v$features
                    check-cast v$features, $type
                """,
            )
        }
    }
    if (featureCalls != 1) {
        throw PatchException("Rumble: expected the Airship tracker to set Airship's features once, found $featureCalls")
    }
}
