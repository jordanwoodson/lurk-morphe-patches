package io.github.jordanwoodson.patches.rumble.upsells

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.videolists.videoListsPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val hidePremiumOnlyPatch = bytecodePatch(
    name = "Hide premium-only videos",
    description = "Leaves videos only Rumble Premium members can watch out of Home, related videos, search and " +
        "categories, unless your account has access to them. Other lists, such as channel pages and My Feed, may " +
        "still show them. Starts off. Turn it on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, videoListsPatch)

    execute {
        // The shared video list hook (RumbleInterface.videos) leaves out videos whose availability is "premium"
        // and whose has_access_to_premium is false while the switch is on.
        rumbleSettings.add(
            SwitchSetting(
                "hide_premium_only",
                "Hide premium-only videos",
                "Videos only Premium members can watch, in Home, related videos, search and categories.",
                Section.INTERFACE,
                default = false,
            ),
        )
    }
}
