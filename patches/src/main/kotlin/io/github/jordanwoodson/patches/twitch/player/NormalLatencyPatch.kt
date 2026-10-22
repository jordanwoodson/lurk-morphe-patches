package io.github.jordanwoodson.patches.twitch.player

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import io.github.jordanwoodson.patches.shared.ivs.IvsLowLatencyFingerprint
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val normalLatencyPatch = bytecodePatch(
    name = "Normal latency",
    description = "Adds a setting that turns off the player's low-latency mode for live streams: a few seconds " +
        "more delay to the broadcaster, for fewer rebuffers on a weak connection. Off until turned on in Lurk settings.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // Twitch's native players never set the mode, so IVS's default (low latency) applies. Set it just
        // before each load, once the player is known to exist.
        NativePlayerLoadFingerprint.twitchMatch("native player load").let { match ->
            val read = match.instructionMatches[1]
            val player = (read.instruction as TwoRegisterInstruction).registerA
            match.method.requireNotBranchTarget(read.index + 1, "native player load")
            match.method.addInstructions(read.index + 1,
                "invoke-static/range { v$player .. v$player }, $PLAYER->loadLatency(Ljava/lang/Object;)V")
        }
        // React Native and the portal player set it themselves (on by default).
        IvsLowLatencyFingerprint.twitchMatch("IVS low latency").method.apply {
            addInstructions(0, """
                ${invokeStatic("$PLAYER->lowLatency(Z)Z", parameterRegister(1))}
                move-result p1
            """)
        }
        twitchSettings.add(SwitchSetting("normal_latency", "Normal latency",
            "Turns off low-latency mode for live streams: a few seconds more delay, fewer rebuffers. Applies " +
                "to streams opened afterwards.", Section.PLAYBACK, default = false))
    }
}
