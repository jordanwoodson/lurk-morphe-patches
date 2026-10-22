package io.github.jordanwoodson.patches.kick.clone

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.KICK
import io.github.jordanwoodson.patches.shared.apps.cloneApp

@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone app",
    description = "Installs Kick under another package name alongside the original, with separate sign-in " +
        "and settings. Sharing sign-in with Kick's broadcaster app may not work after re-signing.",
    default = false,
) {
    cloneApp(KICK)
}
