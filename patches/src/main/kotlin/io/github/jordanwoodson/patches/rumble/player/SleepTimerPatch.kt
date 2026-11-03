package io.github.jordanwoodson.patches.rumble.player

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import io.github.jordanwoodson.patches.rumble.refresh.QualitySwitchFingerprint
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PLAYBACK
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section

private const val EXO_PLAYER = "Landroidx/media3/exoplayer/ExoPlayer;"
private const val PAUSE = "lurkPause"

@Suppress("unused")
val sleepTimerPatch = bytecodePatch(
    name = "Sleep timer",
    description = "Pauses playback a chosen time after it last started or resumed, including background play " +
        "and picture-in-picture. Starts off. Choose a time in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // The player service's Player.Listener: onPlaybackStateChanged(int) checks IDLE (1) and ENDED (4),
        // and onIsPlayingChanged(boolean) is the other method taking one boolean.
        val stateMethodMatches = Fingerprint(
            parameters = listOf("I"),
            returnType = "V",
            filters = listOf(literal(1), literal(4)),
        ).matchAll().filter { it.classDef.type.startsWith(PLAYER_SERVICE.removeSuffix(";") + "$") }
        val listeners = stateMethodMatches
            .map { it.classDef }
            .distinctBy { it.type }
            .filter { classDef ->
                classDef.methods.count {
                    it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "V"
                } == 1
            }
        val listener = listeners.singleOrNull()
            ?: throw PatchException("Rumble: expected one player service listener, found ${listeners.size}")
        mutableClassDefBy(listener).methods.single {
            it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "V"
        }.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $RUMBLE_PLAYBACK->onPlayingChanged(Ljava/lang/Object;Z)V",
        )

        addListenerPause(listener)

        // The same choices as Twitch's Sleep timer.
        rumbleSettings.add(
            ChoiceSetting(
                "sleep_timer",
                "Sleep timer",
                "Pauses playback this long after it last started or resumed, including background play.",
                Section.PLAYBACK,
                "0",
                listOf(
                    "Off" to "0", "15 minutes" to "15", "30 minutes" to "30", "45 minutes" to "45",
                    "1 hour" to "60", "1.5 hours" to "90", "2 hours" to "120",
                ),
            ),
        )
    }
}

/**
 * Lets the extension pause Rumble's player through the listener SleepTimer was handed: a public static
 * lurkPause(listener) on the listener class reads its service, the service's player, and calls the pause that
 * Rumble's media session uses (its player's override of Media3's Player.pause()). The listener's service field
 * is package-private, so the extension can't read it itself.
 */
private fun BytecodePatchContext.addListenerPause(listener: ClassDef) {
    // Media3's Player interface (which ExoPlayer extends) and its pause(): BasePlayer's final ()V method that
    // is setPlayWhenReady(false).
    val player = classDefBy(EXO_PLAYER).interfaces.singleOrNull()
        ?: throw PatchException("Rumble: ExoPlayer doesn't extend exactly one interface")
    val pauses = Fingerprint(
        interfaces = listOf(player),
        parameters = emptyList(),
        returnType = "V",
        filters = listOf(
            literal(0),
            methodCall(definingClass = player, parameters = listOf("Z")),
            opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
        ),
    ).matchAll().map { it.method.name }.toSet()
    val pause = pauses.singleOrNull() ?: throw PatchException("Rumble: expected one Media3 pause(), found $pauses")

    // Rumble's player (RumblePlayer) and the media session's player, an inner class of the service overriding
    // pause() with a call to Rumble's own pause.
    val rumblePlayer = QualitySwitchFingerprint.single("player quality switch").classDef.type
    val rumblePauses = Fingerprint(
        name = pause,
        parameters = emptyList(),
        returnType = "V",
        filters = listOf(
            methodCall(
                definingClass = rumblePlayer,
                parameters = emptyList(),
                returnType = "V",
                opcode = Opcode.INVOKE_VIRTUAL,
            ),
        ),
    ).matchAll()
        .filter { it.classDef.type.startsWith(PLAYER_SERVICE.removeSuffix(";") + "$") }
        .mapNotNull { match ->
            match.method.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
                val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstNotNullOfOrNull null
                if (instruction.opcode == Opcode.INVOKE_VIRTUAL && call.definingClass == rumblePlayer &&
                    call.parameterTypes.isEmpty() && call.returnType == "V"
                ) {
                    call.name
                } else null
            }
        }.toSet()
    val rumblePause = rumblePauses.singleOrNull()
        ?: throw PatchException("Rumble: expected the media session's pause to call one player method, found $rumblePauses")
    Locators.record("rumble.sleep_timer.listener", listener.type)
    Locators.record("rumble.sleep_timer.player_pause", "$rumblePlayer->$rumblePause()V")

    val serviceField = listener.fields.singleOrNull { it.type == PLAYER_SERVICE && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: throw PatchException("Rumble: the player service listener has no service field")
    val accessor = classDefBy(PLAYER_SERVICE).methods.singleOrNull {
        AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(PLAYER_SERVICE) && it.returnType == rumblePlayer
    } ?: throw PatchException("Rumble: no public accessor for the player service's player")

    mutableClassDefBy(listener).methods.add(
        ImmutableMethod(
            listener.type,
            PAUSE,
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    instance-of v0, p0, ${listener.type}
                    if-eqz v0, :none
                    check-cast p0, ${listener.type}
                    iget-object v0, p0, ${listener.type}->${serviceField.name}:$PLAYER_SERVICE
                    if-eqz v0, :none
                    invoke-static { v0 }, $PLAYER_SERVICE->${accessor.name}($PLAYER_SERVICE)$rumblePlayer
                    move-result-object v0
                    if-eqz v0, :none
                    invoke-virtual { v0 }, $rumblePlayer->$rumblePause()V
                    const/4 v0, 0x1
                    return v0
                    :none
                    const/4 v0, 0x0
                    return v0
                """,
            )
        },
    )
    bridge(
        RUMBLE_PLAYBACK,
        "pausePlayer",
        """
            invoke-static { p0 }, ${listener.type}->$PAUSE(Ljava/lang/Object;)Z
            move-result p0
            return p0
        """,
    )
}
