package io.github.jordanwoodson.patches.rumble.update

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "block_update_prompts"

@Suppress("unused")
val blockUpdatePromptsPatch = bytecodePatch(
    name = "Block update prompts",
    description = "Stops the \"update required\" screen and the \"new version available\" dialog that Rumble " +
        "turns on remotely, so a patched version keeps working when Rumble raises its minimum version. Update " +
        "by patching a newer version. On by default. Turn it off in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        ForceUpdateFingerprint.single("forced update check").method
            .also { Locators.record("rumble.block_update_prompts.force_check", it) }
            .addInstructionsIfEnabled(
            KEY,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        SuggestUpdateFingerprint.single("suggested update check").method
            .also { Locators.record("rumble.block_update_prompts.suggest_check", it) }
            .addInstructionsIfEnabled(
            KEY,
            """
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Block update prompts",
                "Rumble's \"update required\" screen and \"new version available\" dialog.",
                Section.INTERFACE,
                default = true,
                restart = true,
            ),
        )
    }
}
