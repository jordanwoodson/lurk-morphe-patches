package io.github.jordanwoodson.patches.kick.appname

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.KICK
import io.github.jordanwoodson.patches.shared.apps.changeAppName

@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Changes Kick's name in the launcher and Android settings.",
    default = false,
) {
    // Kick's option has always been free text, without the two suggestions Twitch and Rumble offer.
    changeAppName(KICK, suggestNames = false)
}
