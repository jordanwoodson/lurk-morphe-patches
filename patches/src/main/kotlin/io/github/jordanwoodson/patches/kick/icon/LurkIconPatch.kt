package io.github.jordanwoodson.patches.kick.icon

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.KICK
import io.github.jordanwoodson.patches.shared.apps.lurkIcon

@Suppress("unused")
val lurkIconPatch = resourcePatch(
    name = "Lurk icon",
    description = "Replaces Kick's launcher icon with Lurk artwork, including Android's themed icon.",
) {
    lurkIcon(KICK)
}
