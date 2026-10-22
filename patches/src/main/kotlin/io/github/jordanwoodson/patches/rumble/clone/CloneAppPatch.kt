package io.github.jordanwoodson.patches.rumble.clone

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.RUMBLE
import io.github.jordanwoodson.patches.shared.apps.cloneApp

@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone app",
    description = "Installs Rumble under another package name, so it sits alongside the original app with its own " +
        "sign-in and settings. Use \"Change app name\" to tell the two apart in the launcher.",
    default = false,
) {
    cloneApp(
        RUMBLE,
        packageNameDescription = "The package name for the clone. Each install needs its own.",
        packageNameValues = mapOf("Rumble (Lurk)" to "${RUMBLE.packageName}.lurk"),
    )
}
