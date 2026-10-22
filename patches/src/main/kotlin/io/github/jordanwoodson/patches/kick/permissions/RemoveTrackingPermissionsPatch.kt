package io.github.jordanwoodson.patches.kick.permissions

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.KICK
import io.github.jordanwoodson.patches.shared.apps.removeTrackingPermissions

@Suppress("unused")
val removeTrackingPermissionsPatch = resourcePatch(
    name = "Remove tracking permissions",
    description = "Removes advertising ID, ad attribution and Google Play install-referrer permissions. " +
        "Other identifiers and analytics require separate controls.",
) {
    removeTrackingPermissions(KICK)
}
