package io.github.jordanwoodson.patches.kick.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KICK_STATS = "Lio/github/jordanwoodson/extension/shared/kick/KickStats;"

@Suppress("unused")
val videoStatsPatch = bytecodePatch(
    name = "Show video stats",
    description = "Shows a small panel in the corner of the player with what Kick's video player reports, " +
        "refreshed every second: latency to the broadcaster, bitrate and the network estimate, buffer, dropped " +
        "frames, quality and codecs. The numbers come from the player on your phone; nothing is sent anywhere, " +
        "and the panel never takes a tap. Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        // Kick's player view runs an observer every second while it is attached and has a player, on its player
        // thread, and reads the IVS player's bitrate, duration and latency here for React Native's (unused)
        // statistics event. KickStats reads the rest of the player's numbers there and draws them in the view's
        // overlay.
        PlayerStatisticsFingerprint.kickMatch("player view statistics").method.apply {
            Locators.record("kick.video_stats.player_statistics", this)
            addInstructions(
                0,
                invokeStatic(
                    "$KICK_STATS->onStatistics(Landroid/view/View;Ljava/lang/Object;)V",
                    parameterRegister(0), parameterRegister(1),
                ),
            )
        }
        kickSettings.add(
            SwitchSetting(
                "video_stats",
                "Show video stats",
                "A panel over the player with latency, bitrate, buffer, dropped frames, quality and codecs, " +
                    "refreshed every second. Applies at once.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
