package io.github.jordanwoodson.patches.rumble.tabs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.intValue
import io.github.jordanwoodson.patches.shared.settings.requireLocals

private const val KEY = "startup_tab"

@Suppress("unused")
val defaultStartupTabPatch = bytecodePatch(
    name = "Default startup tab",
    description = "Opens Rumble on Home or My Feed every time, instead of the one you last chose. Starts off. " +
        "Choose a tab in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Rumble remembers Home (0) or My Feed (3) and opens on it. The bottom bar keeps its tab positions when
        // Hide Shorts or Hide Create tab hides a tab, so the indexes stay valid.
        StartupTabFingerprint.single("startup tab preference").method.apply {
            Locators.record("rumble.startup_tab.getter", this)
            requireLocals(1)
            addInstructionsWithLabels(
                0,
                """
                    ${intValue(KEY, "v0")}
                    if-ltz v0, :original
                    return v0
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }

        rumbleSettings.add(
            ChoiceSetting(
                KEY,
                "Default startup tab",
                "The tab Rumble opens on.",
                Section.INTERFACE,
                default = "-1",
                choices = listOf("Rumble's choice (the last one)" to "-1", "Home" to "0", "My Feed" to "3"),
                restart = true,
            ),
        )
    }
}
