package io.github.jordanwoodson.patches.kick.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.ivs.IvsPauseFingerprint
import io.github.jordanwoodson.patches.shared.ivs.IvsPlayFingerprint
import io.github.jordanwoodson.patches.shared.ivs.IvsReleaseFingerprint
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section

@Suppress("unused")
val sleepTimerPatch = bytecodePatch(
    name = "Sleep timer",
    description = "Pauses playback a chosen time after it last started or resumed, including background audio. " +
        "Starts off. Choose a time in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        // Every start or resume reaches IVS play(); pause() and release() end it.
        IvsPlayFingerprint.kickMatch("IVS play").method
            .addInstructions(0, "invoke-static/range { p0 .. p0 }, $KICK_PLAYER->onPlay(Ljava/lang/Object;)V")
        listOf(IvsPauseFingerprint to "IVS pause", IvsReleaseFingerprint to "IVS release").forEach { (fingerprint, what) ->
            fingerprint.kickMatch(what).method
                .addInstructions(0, "invoke-static/range { p0 .. p0 }, $KICK_PLAYER->onStop(Ljava/lang/Object;)V")
        }

        // The timer pauses through the player view React Native plays through, the way the player's own
        // controls and closing picture-in-picture do, so the audio service and the controls follow.
        PlayerViewPlayFingerprint.kickMatch("player view play").method
            .addInstructions(0, "invoke-static/range { p0 .. p0 }, $KICK_PLAYER->onViewPlay(Landroid/view/View;)V")
        val pause = PlayerViewPauseFingerprint.kickMatch("player view pause").method
        bridge(KICK_PLAYER, "pauseView", """
            check-cast p0, ${pause.definingClass}
            invoke-virtual { p0 }, ${pause.definingClass}->pause()V
            return-void
        """)

        // The view queues its IVS pause on a player executor. Carry only sleep dispatch's expiry to
        // that Runnable, then check it at execution; a newer play can overtake the queued command.
        val executor = PlayerOperationExecutorFingerprint.kickMatch("player operation executor").method
        val enqueue = executor.implementation!!.instructions.mapIndexedNotNull { index, instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (instruction.opcode == Opcode.INVOKE_INTERFACE &&
                reference?.definingClass == "Ljava/util/concurrent/ExecutorService;" &&
                reference.name == "execute" && reference.returnType == "V" &&
                reference.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Runnable;")) {
                val registers = instruction as? FiveRegisterInstruction
                    ?: throw PatchException("Kick: player executor invocation no longer uses two registers")
                if (registers.registerCount != 2 || registers.registerD > 255) {
                    throw PatchException("Kick: player executor registers changed")
                }
                index to registers.registerD
            } else null
        }.singleOrNull() ?: throw PatchException("Kick: expected exactly one player executor enqueue")
        val (enqueueIndex, operationRegister) = enqueue
        executor.addInstructions(enqueueIndex, """
            invoke-static/range { v$operationRegister .. v$operationRegister }, Lio/github/jordanwoodson/extension/shared/SleepTimer;->guardPauseOperation(Ljava/lang/Runnable;)Ljava/lang/Runnable;
            move-result-object v$operationRegister
        """)

        kickSettings.add(
            ChoiceSetting(
                "sleep_timer",
                "Sleep timer",
                "Pauses playback this long after it last started or resumed, including background audio.",
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
