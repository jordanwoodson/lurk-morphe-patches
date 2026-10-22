package io.github.jordanwoodson.patches.kick.analytics

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.shared.settings.isEnabled
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import org.w3c.dom.Element

private const val KEY = "disable_analytics"
private const val PRIVACY = "Lio/github/jordanwoodson/extension/shared/kick/KickPrivacy;"

/**
 * Firebase's content provider starts Analytics before MainApplication.onCreate turns collection off,
 * so a fresh install would collect from the manifest default until then. The persisted value that
 * onCreate sets every start wins over this default, so the switch still turns collection back on.
 */
private val firebaseStartsDisabledPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val nodes = doc.getElementsByTagName("meta-data")
            val enabled = (0 until nodes.length).map { nodes.item(it) as Element }
                .singleOrNull { it.getAttribute("android:name") == "firebase_analytics_collection_enabled" }
                ?: throw PatchException("Kick: Firebase Analytics collection default missing")
            enabled.setAttribute("android:value", "false")
        }
    }
}

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Disables Firebase Analytics collection, Insider and Datadog initialization/events, " +
        "Mux/Datazoom player analytics and PAL nonce requests. Playback API requests remain necessary " +
        "to obtain streams. Restart Kick after changing this setting. This does not remove video ads.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch, firebaseStartsDisabledPatch)

    execute {
        // Resolve the original React Native completion contract, without dispatching an event task. The method has
        // one local, so the promise stays in its parameter register: refuse a version where that is above v15.
        FirebaseEventFingerprint.kickMatch("Firebase event bridge").method.apply {
            requireLowRegisters("Firebase event bridge", parameterRegister(3))
            addInstructionsIfEnabled(KEY, """
                const/4 v0, 0x0
                invoke-interface { p3, v0 }, $REACT_PROMISE->resolve(Ljava/lang/Object;)V
                return-void
            """)
        }
        // No scratch registers required: only transform the existing boolean argument.
        FirebaseCollectionFingerprint.kickMatch("Firebase collection setter").method.addInstructions(0, """
            invoke-static/range { p1 .. p1 }, $PRIVACY->allowAnalyticsCollection(Z)Z
            move-result p1
        """)

        listOf(
            InsiderInitFingerprint to "Insider initialization",
            InsiderEventFingerprint to "Insider event build",
            DatadogNativeFingerprint to "Datadog native initialization",
            MuxInitializationFingerprint to "Mux player initialization",
            DatazoomApplyFingerprint to "Datazoom player configuration",
        ).forEach { (fingerprint, description) ->
            fingerprint.kickMatch(description).method.addInstructionsIfEnabled(KEY, "return-void")
        }
        // Previously scheduled jobs can upload stored data without initializing the RN SDK.
        // Returning false reports synchronous completion: no worker was started, so jobFinished
        // is unnecessary and onStopJob will not be called for this execution. Both originals
        // have two locals; the helper checks that v0 is not a parameter before adding the guard.
        listOf(
            InsiderSessionJobFingerprint to "Insider session upload job",
            InsiderAmplificationJobFingerprint to "Insider amplification job",
        ).forEach { (fingerprint, description) ->
            fingerprint.kickMatch(description).method.addInstructionsIfEnabled(KEY, """
                const/4 v0, 0x0
                return v0
            """)
        }
        // The promise is copied next to the value, so the call never depends on how many locals Datadog adds.
        DatadogBridgeFingerprint.kickMatch("Datadog React initialization").method.addInstructionsIfEnabled(KEY, """
            move-object/from16 v0, p2
            const/4 v1, 0x0
            invoke-interface { v0, v1 }, $REACT_PROMISE->resolve(Ljava/lang/Object;)V
            return-void
        """, locals = 2)
        // Hermes already handles this unavailable result. An unresolved Promise blocks its playback payload.
        PalNonceFingerprint.kickMatch("PAL nonce bridge").method.apply {
            requireLowRegisters("PAL nonce bridge", parameterRegister(3))
            addInstructionsIfEnabled(KEY, """
                invoke-static { }, $PRIVACY->unavailablePalNonce()Ljava/util/Map;
                move-result-object v0
                invoke-interface { p3, v0 }, $EXPO_PROMISE->resolve(Ljava/lang/Object;)V
                return-void
            """)
        }

        val startup = AnalyticsStartupFingerprint.kickMatch("analytics startup").method
        startup.requireLocals(2)
        val initialization = startup.implementation!!.instructions.take(2).map {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()
        }
        if (initialization != listOf(
                "Lio/github/jordanwoodson/extension/shared/settings/Settings;->init(Landroid/content/Context;)V",
                "Lio/github/jordanwoodson/extension/shared/kick/KickBundle;->init(Landroid/content/Context;)V",
            )) throw PatchException("Kick: settings must initialize before analytics collection")
        // Also restores Firebase's enabled default after switching the patch off and restarting.
        startup.addInstructions(2, """
            invoke-static/range { p0 .. p0 }, $FIREBASE->getInstance(Landroid/content/Context;)Lcom/google/firebase/analytics/FirebaseAnalytics;
            move-result-object v1
            ${isEnabled(KEY, "v0")}
            xor-int/lit8 v0, v0, 0x1
            invoke-virtual { v1, v0 }, $FIREBASE->setAnalyticsCollectionEnabled(Z)V
        """)

        kickSettings.add(SwitchSetting(KEY, "Disable analytics",
            "Disables Firebase, Insider, Datadog, Mux/Datazoom and PAL tracking. Playback requests still run.",
            Section.PRIVACY, restart = true))
    }
}
