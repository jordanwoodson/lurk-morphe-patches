package io.github.jordanwoodson.patches.twitch.clone

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.TWITCH
import io.github.jordanwoodson.patches.shared.apps.cloneApp

@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone app",
    description = "Installs Twitch under another package name alongside the original, with separate sign-in " +
        "and settings. Amazon sign-in and verified app links may not work after re-signing.",
    default = false,
) {
    cloneApp(TWITCH)
}
