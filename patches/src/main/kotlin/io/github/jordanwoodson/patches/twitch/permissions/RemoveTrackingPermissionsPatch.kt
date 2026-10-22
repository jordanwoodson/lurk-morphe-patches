package io.github.jordanwoodson.patches.twitch.permissions

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.TWITCH
import io.github.jordanwoodson.patches.shared.apps.removeTrackingPermissions

@Suppress("unused")
val removeTrackingPermissionsPatch = resourcePatch(
    name = "Remove tracking permissions",
    description = "Removes advertising ID and Google Play install-referrer permissions. " +
        "Other identifiers and analytics require separate controls.",
) {
    removeTrackingPermissions(TWITCH)
}
