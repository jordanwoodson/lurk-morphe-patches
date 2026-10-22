package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.patch.Compatibility
import io.github.jordanwoodson.patches.shared.compose.AppCode

/**
 * One app of the bundle, as the patches every app has see it. Each app's folder declares those patches
 * (name, description, whether selected by default) and builds their body from here with the app's
 * description in Apps.kt. What differs between the apps is a parameter of this class or of the builder,
 * never a check on which app it is.
 */
internal class LurkApp(
    /** The app's folder under patches/: `twitch`. */
    val slug: String,
    /** The app's `COMPATIBILITY_<APP>` from its shared/Constants.kt. */
    val compatibility: Compatibility,
    /** The `<uses-permission>` entries Remove tracking permissions takes out. Each must be in the manifest. */
    val trackingPermissions: Set<String>,
    /** The `<queries>` entries Remove tracking permissions takes out with them. */
    val trackingQueries: TrackingQueries = TrackingQueries(),
) {
    /** The launcher name as Constants.kt gives it ("Twitch"), for messages and the default app name. */
    val name: String = compatibility.name ?: throw IllegalArgumentException("$slug: the compatibility has no name")

    /** The original app's package name. */
    val packageName: String = compatibility.packageName
        ?: throw IllegalArgumentException("$slug: the compatibility has no package name")

    /** The newest version the patches target, for messages. Only shared/Constants.kt spells it. */
    val version: String get() = AppCode(compatibility).version
}
