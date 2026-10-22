package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.stringOption
import org.w3c.dom.Element

/**
 * The body of an app's "Change app name" patch: replaces the app_name string, which labels the launcher icon
 * and the app in Android's settings, with the `appName` option ("<App> Lurk" by default). [suggestNames]
 * offers "<App> Lurk" and "Lurk" to pick from in Manager; without it the option is free text only.
 */
internal fun ResourcePatchBuilder.changeAppName(app: LurkApp, suggestNames: Boolean = true) {
    compatibleWith(app.compatibility)

    val default = "${app.name} Lurk"
    val appName by stringOption(
        key = "appName",
        default = default,
        values = if (suggestNames) mapOf(default to default, "Lurk" to "Lurk") else null,
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
                ?: throw PatchException("${app.name}: app_name string not found. This patch targets " +
                    "${app.name} ${app.version}; recheck the resources before updating compatibility.")
            // Android string resources treat quotes and backslashes, and a leading @ or ?, specially.
            appNameString.textContent = appName!!.trim()
                .replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace(Regex("^([@?])"), "\\\\$1")
        }
    }
}
