package io.github.jordanwoodson.patches.twitch.icon

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.TWITCH
import io.github.jordanwoodson.patches.shared.apps.lurkIcon

@Suppress("unused")
val lurkIconPatch = resourcePatch(
    name = "Lurk icon",
    description = "Replaces Twitch's launcher icon with Lurk artwork, including Android's themed icon.",
) {
    lurkIcon(TWITCH)
}
