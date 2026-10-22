package io.github.jordanwoodson.patches.kick.updates

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "block_update_prompts"

@Suppress("unused")
val blockUpdatePromptsPatch = bytecodePatch(
    name = "Block update prompts",
    description = "Stops Kick from asking for or forcing an app update. A patched Kick can't be updated from " +
        "Google Play, so update by patching a newer version. On by default. Turn it off in Lurk settings > Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // A leaf of the root layout: it runs the remote minimum-version check and the Play Store lookup, then
        // shows the forced-update screen or the update sheet. Rendering nothing skips all of it. Expo's own
        // over-the-air updates are already off whenever Kick's JavaScript is patched.
        kickHermes.returnNull("AppUpdateHandler", KEY)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Block update prompts",
                "Never show Kick's update screens. Update by patching a newer version.",
                Section.INTERFACE,
                restart = true,
            ),
        )
    }
}
