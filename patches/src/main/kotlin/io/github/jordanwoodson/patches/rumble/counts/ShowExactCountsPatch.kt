package io.github.jordanwoodson.patches.rumble.counts

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_INTERFACE
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "exact_counts"

@Suppress("unused")
val showExactCountsPatch = bytecodePatch(
    name = "Show exact counts",
    description = "Shows view, like, comment, follower and \"watching now\" counts in full (12,345 instead of " +
        "12K). Starts off. Turn it on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        CountFormatterFingerprint.single("count formatter").method
            .also { Locators.record("rumble.exact_counts.formatter", it) }
            .addInstructionsIfEnabled(
            KEY,
            """
                invoke-static/range { p0 .. p1 }, $RUMBLE_INTERFACE->exactCount(J)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Show exact counts",
                "Views, likes, comments and followers in full, such as 12,345 instead of 12K.",
                Section.INTERFACE,
                default = false,
            ),
        )
    }
}
