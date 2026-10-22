package io.github.jordanwoodson.patches.rumble.permissions

import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.shared.apps.RUMBLE
import io.github.jordanwoodson.patches.shared.apps.removeTrackingPermissions

@Suppress("unused")
val removeTrackingPermissionsPatch = resourcePatch(
    name = "Remove tracking permissions",
    description = "Removes the advertising ID, ad measurement, install referrer and phone state permissions. " +
        "On Android 13 and newer, every SDK and Rumble's own requests then get an all-zero advertising ID; on " +
        "Android 12 and older, Google Play services hands out the real one regardless, unless you delete it in " +
        "Android's settings.",
) {
    removeTrackingPermissions(RUMBLE)
}
