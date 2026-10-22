package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchBuilder

/**
 * `<queries>` entries that only let a tracking SDK see other apps, which Remove tracking permissions takes
 * out with the permissions: `<package>` entries naming one of [packages] and `<intent>` entries with one of
 * [actions].
 */
internal class TrackingQueries(
    val packages: Set<String> = emptySet(),
    val actions: Set<String> = emptySet(),
)

/**
 * The body of an app's "Remove tracking permissions" patch: removes the app's [LurkApp.trackingPermissions]
 * from the manifest, failing if any of them is missing, and its [LurkApp.trackingQueries].
 */
internal fun ResourcePatchBuilder.removeTrackingPermissions(app: LurkApp) {
    compatibleWith(app.compatibility)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement

            val permissions = manifest.childElements("uses-permission")
                .filter { it.getAttribute("android:name") in app.trackingPermissions }
            val missing = app.trackingPermissions - permissions.map { it.getAttribute("android:name") }.toSet()
            if (missing.isNotEmpty()) {
                throw PatchException("${app.name}: tracking permissions not found: ${missing.joinToString()}. " +
                    "This patch targets ${app.name} ${app.version}; recheck the manifest before updating compatibility.")
            }
            permissions.forEach { manifest.removeChild(it) }

            manifest.childElements("queries").flatMap { it.childElements() }.forEach { query ->
                val tracking = when (query.tagName) {
                    "package" -> query.getAttribute("android:name") in app.trackingQueries.packages
                    "intent" -> query.childElements("action")
                        .any { it.getAttribute("android:name") in app.trackingQueries.actions }
                    else -> false
                }
                if (tracking) query.parentNode.removeChild(query)
            }
        }
    }
}
