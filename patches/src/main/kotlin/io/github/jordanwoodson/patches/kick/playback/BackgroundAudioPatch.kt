package io.github.jordanwoodson.patches.kick.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.player.ActivityResumeFingerprint
import io.github.jordanwoodson.patches.kick.player.KICK_PLAYER
import io.github.jordanwoodson.patches.kick.player.ShouldRunAudioServiceFingerprint
import io.github.jordanwoodson.patches.kick.player.UpdatePipParamsFingerprint
import io.github.jordanwoodson.patches.kick.player.UserLeaveHintFingerprint
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals

@Suppress("unused")
val backgroundAudioPatch = bytecodePatch(
    name = "Background audio",
    description = "Keeps live streams and videos playing as audio when you leave Kick, using Kick's own audio " +
        "notification. A second switch turns off the automatic picture-in-picture window. On by default, as on " +
        "Twitch. Turn it off in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        // Kick already plays audio with the screen locked and in picture-in-picture, through its media-playback
        // foreground service. Leaving without picture-in-picture starts nothing, so the audio stops once the
        // app is frozen. Start the service while Kick is still in front, so Android allows it.
        UserLeaveHintFingerprint.kickMatch("MainActivity.onUserLeaveHint").method.apply {
            val superCall = implementation!!.instructions.singleOrNull { it.opcode == Opcode.INVOKE_SUPER }
                ?: throw PatchException("Kick: onUserLeaveHint no longer calls its superclass once")
            val reference = (superCall as ReferenceInstruction).reference as MethodReference
            requireLocals(1)
            addInstructionsWithLabels(0, """
                invoke-static/range { p0 .. p0 }, $KICK_PLAYER->onUserLeaveHint(Landroid/app/Activity;)Z
                move-result v0
                if-eqz v0, :kick_pip
                invoke-super/range { p0 .. p0 }, $reference
                return-void
            """, ExternalLabel("kick_pip", getInstruction(0)))
        }

        // On Android 12 and later Kick also lets the system enter picture-in-picture on its own when you leave
        // (auto-enter in the window's picture-in-picture parameters). Kick's own picture-in-picture button enters
        // it directly and keeps working.
        UpdatePipParamsFingerprint.kickMatch("player view updatePipParams").method.addInstructions(0, """
            invoke-static/range { p1 .. p1 }, $KICK_PLAYER->autoEnterPip(Z)Z
            move-result p1
        """)

        // Kick stops the service whenever its rule (locked screen or picture-in-picture) no longer holds: on each
        // player state change, on unlock and on resume. Keep it while Kick was left playing.
        ShouldRunAudioServiceFingerprint.kickMatch("shouldRunAudioService").method.apply {
            val isPlaying = getInstruction(0)
            val field = (isPlaying as? ReferenceInstruction)?.reference as? FieldReference
            if (isPlaying.opcode != Opcode.SGET_BOOLEAN || field?.name != "isPlaying") {
                throw PatchException("Kick: shouldRunAudioService no longer starts with isPlaying")
            }
            requireLocals(1)
            addInstructionsWithLabels(0, """
                invoke-static { }, $KICK_PLAYER->keepAudio()Z
                move-result v0
                if-eqz v0, :kick_rule
                sget-boolean v0, $field
                return v0
            """, ExternalLabel("kick_rule", getInstruction(0)))
        }

        val resume = ActivityResumeFingerprint.kickMatch("KickIvsPlayerV2Manager.onActivityResume").method
        resume.addInstructions(0, "invoke-static { }, $KICK_PLAYER->onResume()V")

        // The extension reaches the player module through bridges, so it never names Kick's classes.
        val companion = resume.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }.singleOrNull { it.type.endsWith("/AudioService\$Companion;") }
            ?: throw PatchException("Kick: onActivityResume no longer stops AudioService")
        bridge(KICK_PLAYER, "startAudioService", """
            sget-object v0, $companion
            invoke-virtual { v0, p0 }, ${companion.type}->start(Landroid/content/Context;)V
            return-void
        """, locals = 1)
        bridge(KICK_PLAYER, "isPlaying", """
            sget-object v0, ${resume.definingClass}->INSTANCE:${resume.definingClass}
            invoke-virtual { v0 }, ${resume.definingClass}->isPlaying()Z
            move-result v0
            return v0
        """, locals = 1)

        kickSettings.add(
            SwitchSetting(
                "background_audio",
                "Background audio",
                "Keep playing as audio when you leave Kick.",
                Section.PLAYBACK,
            ),
            SwitchSetting(
                "no_auto_pip",
                "No automatic picture-in-picture",
                "Don't open the floating video when you leave Kick while watching.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
