package io.github.jordanwoodson.patches.twitch.player

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val VIEW_DELEGATE = "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"

@Suppress("unused")
val refreshStreamPatch = bytecodePatch(
    name = "Refresh stream",
    description = "Long-press the player's settings gear, or the middle of the video, to reload a stuck stream " +
        "through Twitch's own Retry: a new access token and playlist for live streams, the same position for " +
        "videos. Covers Twitch's native players.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The error screen's Retry calls one presenter method with (0, true). Call exactly that one.
        val retry = playerRetry()
        bridge("retryPlayer", """
            check-cast p0, ${retry.definingClass}
            invoke-interface { p0, p1, p2 }, $retry
        """)

        // Each presenter's player view, to know which player a long-press belongs to.
        hookPlayerViewAttach()

        // The settings gear of the classic overlays, live and VOD/clips: long-press refreshes.
        listOf(LivePlayerOverlayFingerprint to "live", VodPlayerOverlayFingerprint to "VOD").forEach { (fingerprint, what) ->
            val match = fingerprint.twitchMatch("$what player overlay")
            val method = match.method
            val code = method.implementation!!.instructions
            val id = match.instructionMatches[1]
            val idRegister = (id.instruction as OneRegisterInstruction).registerA
            // The view is looked up by the settings_button ID, its result moved into a register and cast
            // to ImageView; insert right after the cast.
            val lookup = code[id.index + 1] as? FiveRegisterInstruction
            val cast = (id.index + 2..id.index + 5).firstOrNull { index ->
                code[index].opcode == Opcode.CHECK_CAST &&
                    ((code[index] as ReferenceInstruction).reference as TypeReference).type == "Landroid/widget/ImageView;"
            }
            val arguments = lookup?.let {
                listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
            }.orEmpty()
            if (idRegister !in arguments || code[id.index + 2].opcode != Opcode.MOVE_RESULT_OBJECT || cast == null) {
                throw PatchException("Twitch: the $what player's settings button lookup changed")
            }
            val gear = (code[cast] as OneRegisterInstruction).registerA
            method.requireNotBranchTarget(cast + 1, "the $what player's settings button")
            method.addInstructions(cast + 1,
                "invoke-static/range { v$gear .. v$gear }, $PLAYER->bindRefresh(Landroid/view/View;)V")
        }

        // Long-pressing the native player view: Twitch emits it and ignores it.
        PlayerLongPressFingerprint.twitchMatch("player long press").method.apply {
            requireNotBranchTarget(0, "the player's long press")
            addInstructions(0, invokeStatic("$PLAYER->videoLongPress(Landroid/view/MotionEvent;)V", parameterRegister(1)))
        }

        twitchSettings.add(
            SwitchSetting("refresh_stream", "Refresh stream",
                "Long-press the player's settings gear to reload a stuck stream with a new access token, as " +
                    "Twitch's Retry does. Applies to players opened after it's turned on.", Section.PLAYBACK),
            SwitchSetting("refresh_stream_video", "Also by long-pressing the video",
                "Long-press the middle of the video to refresh too. Twitch doesn't use this gesture.",
                Section.PLAYBACK, parent = "refresh_stream"),
        )
    }
}
