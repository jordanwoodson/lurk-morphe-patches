package io.github.jordanwoodson.patches.rumble.tipping

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val hideJoinShopPatch = bytecodePatch(
    name = "Hide Join and Shop buttons",
    description = "Hides the Join (paid channel membership) and Shop (a creator's store) buttons next to Follow on " +
        "channels and videos, each with its own switch. Starts off. Turn them on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, actionButtonsPatch)

    execute {
        // Join and Shop leave the action button list through the shared hook (RumbleInterface.actionButtons).
        rumbleSettings.add(
            SwitchSetting(
                "hide_join_button",
                "Hide Join buttons",
                "The Join (channel membership) button on channels and videos.",
                Section.INTERFACE,
                default = false,
            ),
            SwitchSetting(
                "hide_shop_button",
                "Hide Shop buttons",
                "The Shop button that opens a creator's store, on channels and videos.",
                Section.INTERFACE,
                default = false,
            ),
        )
    }
}
