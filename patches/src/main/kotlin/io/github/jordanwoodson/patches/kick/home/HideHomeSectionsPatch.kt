package io.github.jordanwoodson.patches.kick.home

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val hideHomeSectionsPatch = bytecodePatch(
    name = "Hide Home sections",
    description = "Hides the featured carousel, recommended streams and categories rows on Home, each with its own " +
        "switch. Starts off. Turn them on in Lurk settings > Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // Home renders these three rows first, then the sections Kick's server configures. Each row loads its
        // own data and shows its own placeholder, so rendering nothing removes it and its loading state.
        val rows = listOf(
            Triple("FeaturedStreamsRow", "hide_home_featured", "Hide featured streams" to "The featured carousel at the top of Home."),
            Triple("RecommendedStreamsRow", "hide_home_recommended", "Hide recommended streams" to "The recommended streams row (signed in)."),
            Triple("CategoriesRow", "hide_home_categories", "Hide categories" to "The top or recommended categories row."),
        )
        rows.forEach { (component, key, _) -> kickHermes.returnNull(component, key, defaultEnabled = false) }
        kickSettings.add(*rows.map { (_, key, text) ->
            SwitchSetting(key, text.first, text.second, Section.INTERFACE, default = false, restart = true)
        }.toTypedArray())
    }
}
