package io.github.jordanwoodson.patches.twitch.appname

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import org.w3c.dom.Element

@Suppress("unused")
val changeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Changes the name shown under Twitch's launcher icon and in Android's settings.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TWITCH)

    val appName by stringOption(
        key = "appName",
        default = "Twitch Lurk",
        values = mapOf("Twitch Lurk" to "Twitch Lurk", "Lurk" to "Lurk"),
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
                ?: throw PatchException("Twitch: app_name string not found. " +
                    "This patch targets 31.7.0; recheck the resources before updating compatibility.")
            // Android string resources treat quotes and backslashes, and a leading @ or ?, specially.
            appNameString.textContent = appName!!.trim()
                .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace(Regex("^([@?])"), "\\\\$1")
        }
    }
}
