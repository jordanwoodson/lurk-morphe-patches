package io.github.jordanwoodson.patches.kick.rating

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesBundle.Constant
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "hide_app_rating"

@Suppress("unused")
val hideRatingPromptPatch = bytecodePatch(
    name = "Hide rating prompt",
    description = "Stops Kick from asking you to rate the app. On by default. Turn it off in Lurk settings > Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // Every automatic prompt (after a won prediction, a number of follows, a redeemed reward) goes through
        // showAppRatingDialog, which asks this store check first: never shown, or more than 120 days ago.
        kickHermes.returnAtEntry("shouldShowAppRating", KEY, Constant.FALSE)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Hide rating prompt",
                "Never ask to rate Kick.",
                Section.INTERFACE,
                restart = true,
            ),
        )
    }
}
