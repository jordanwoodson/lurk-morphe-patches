package io.github.jordanwoodson.patches.twitch.player

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH

@Suppress("unused")
val forceLandscapePatch = bytecodePatch(
    name = "Force landscape",
    description = "Turns the screen to landscape when a live stream or video opens in Twitch's native theatre, " +
        "and back to the orientation the app had when the theatre closes. Twitch's rotate button still " +
        "switches to portrait. Home's feed previews don't rotate. Starts off. Turn it on in Lurk settings > " +
        "Playback.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // Each presenter's player view: the theatre's player shows there.
        hookPlayerViewAttach()
        twitchSettings.add(SwitchSetting("force_landscape", "Force landscape",
            "Turn to landscape when a live stream or video opens in the theatre, and back when it closes. " +
                "Applies to players opened after it's turned on.",
            Section.PLAYBACK, default = false))
    }
}
