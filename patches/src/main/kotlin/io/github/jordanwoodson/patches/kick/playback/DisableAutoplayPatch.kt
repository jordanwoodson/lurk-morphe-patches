package io.github.jordanwoodson.patches.kick.playback

import app.morphe.patcher.patch.rawResourcePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "disable_autoplay"

@Suppress("unused")
val disableAutoplayPatch = rawResourcePatch(
    name = "Disable autoplay",
    description = "Starts Kick's feed previews switched off. Existing preferences and Kick's own " +
        "autoplay switch keep working. Does not change intentionally opened streams." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)
    execute {
        kickHermes.previewDefault(KEY)
        kickSettings.add(SwitchSetting(KEY, "Disable autoplay",
            "Default feed previews to off. Kick's saved preference takes precedence. Restart required.",
            Section.PLAYBACK, restart = true))
    }
}
