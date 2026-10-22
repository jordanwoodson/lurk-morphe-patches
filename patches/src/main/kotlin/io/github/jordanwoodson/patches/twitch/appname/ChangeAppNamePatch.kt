package io.github.jordanwoodson.patches.twitch.appname

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.TWITCH
import io.github.jordanwoodson.patches.shared.apps.changeAppName

@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Changes the name shown under Twitch's launcher icon and in Android's settings.",
    default = false,
) {
    changeAppName(TWITCH)
}
