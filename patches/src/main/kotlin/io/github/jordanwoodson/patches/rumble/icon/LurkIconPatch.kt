package io.github.jordanwoodson.patches.rumble.icon

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.RUMBLE
import io.github.jordanwoodson.patches.shared.apps.lurkIcon

@Suppress("unused")
val lurkIconPatch = resourcePatch(
    name = "Lurk icon",
    description = "Replaces Rumble's launcher icon with the Lurk icon, including the themed icon on Android 13+.",
) {
    lurkIcon(RUMBLE)
}
