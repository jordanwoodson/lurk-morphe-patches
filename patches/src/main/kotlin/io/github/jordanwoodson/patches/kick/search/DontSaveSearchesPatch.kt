package io.github.jordanwoodson.patches.kick.search

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "no_search_history"

@Suppress("unused")
val dontSaveSearchesPatch = bytecodePatch(
    name = "Don't save searches",
    description = "Stops Kick from saving what you search for on this device. Searches you saved earlier stay " +
        "until you remove them. On by default. Turn it off in Lurk settings > Privacy." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // The search-history store's addHistoryEntry prepends each query; it returns at its "no query" guard
        // instead. The store is saved together with the sign-in tokens, so it isn't cleared natively, and
        // removing a saved search still works.
        kickHermes.skipGuard(kickHermes.function("addHistoryEntry"), KEY)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Don't save searches",
                "Don't add new searches to the search history.",
                Section.PRIVACY,
                restart = true,
            ),
        )
    }
}
