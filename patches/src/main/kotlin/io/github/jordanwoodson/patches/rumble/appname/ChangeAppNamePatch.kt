package io.github.jordanwoodson.patches.rumble.appname

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import org.w3c.dom.Element

@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Changes the name shown under Rumble's launcher icon and in Android's settings.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_RUMBLE)

    val appName by stringOption(
        key = "appName",
        default = "Rumble Lurk",
        values = mapOf("Rumble Lurk" to "Rumble Lurk", "Lurk" to "Lurk"),
        title = "App name",
        description = "The name to show.",
        required = true,
    ) { !it.isNullOrBlank() }

    execute {
        // The application's label is @string/app_name, which only the default strings.xml defines.
        document("res/values/strings.xml").use { document ->
            val nodes = document.getElementsByTagName("string")
            val appNameString = (0 until nodes.length).map { nodes.item(it) as Element }
                .singleOrNull { it.getAttribute("name") == "app_name" }
                ?: throw PatchException("Rumble: app_name string not found. " +
                    "This patch targets Rumble ${RUMBLE.version}; recheck the resources before updating compatibility.")
            // Android string resources treat quotes and backslashes, and a leading @ or ?, specially.
            appNameString.textContent = appName!!.trim()
                .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace(Regex("^([@?])"), "\\\\$1")
        }
    }
}
