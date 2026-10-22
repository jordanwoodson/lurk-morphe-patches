package io.github.jordanwoodson.patches.twitch.playback

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val videoStatsPatch = bytecodePatch(
    name = "Show video stats",
    description = "Adds the video stats button Twitch shows only to its staff to the native player: latency to " +
        "the broadcaster, bitrate, buffer, dropped frames, codecs and server. Everything shown is local to the player.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        VideoDebugPanelFingerprint.twitchMatch("video stats panel gate").method
            .addInstructionsIfEnabled("video_stats", "const/4 v0, 0x1\nreturn v0")
        twitchSettings.add(SwitchSetting("video_stats", "Show video stats",
            "A stats button in the player: latency, bitrate, buffer and dropped frames.", Section.PLAYBACK))
    }
}
