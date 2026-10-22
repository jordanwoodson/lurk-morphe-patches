package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How Lurk icon gives an app its launcher icon. */
internal sealed interface LauncherIcon {
    fun ResourcePatchContext.install(app: LurkApp)
}

/** The class loader of this bundle, which holds the artwork under patches/src/main/resources/. */
private val bundleResources: ClassLoader get() = LauncherIcon::class.java.classLoader

/**
 * Lurk's figure from Rumble's artwork, recolored to [color] on Lurk's dark background, as a new adaptive icon
 * (also the themed icon) that the manifest then points to. Only for apps that require Android 8 or newer, so
 * every supported device understands adaptive vector icons.
 */
internal class RecoloredFigure(private val color: String) : LauncherIcon {
    override fun ResourcePatchContext.install(app: LurkApp) {
        val foreground = bundleResources.getResourceAsStream(FIGURE)
            ?.bufferedReader()?.use { it.readText() }
            ?.takeIf { FIGURE_COLOR in it }
            ?: throw PatchException("Bundled Lurk artwork missing, or its logo is no longer $FIGURE_COLOR")
        get("res/drawable/lurk_launcher_foreground.xml").apply {
            parentFile.mkdirs()
            writeText(foreground.replace(FIGURE_COLOR, color))
        }
        get("res/drawable/lurk_launcher_background.xml").writeText("""
            <shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
                <solid android:color="#181D24" />
            </shape>
        """.trimIndent())
        get("res/mipmap-anydpi-v26/lurk_launcher.xml").apply {
            parentFile.mkdirs()
            writeText("""
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="@drawable/lurk_launcher_background" />
                    <foreground android:drawable="@drawable/lurk_launcher_foreground" />
                    <monochrome android:drawable="@drawable/lurk_launcher_foreground" />
                </adaptive-icon>
            """.trimIndent())
        }
        document("AndroidManifest.xml").use { doc ->
            val application = doc.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:icon", "@mipmap/lurk_launcher")
            application.setAttribute("android:roundIcon", "@mipmap/lurk_launcher")
        }
    }

    private companion object {
        /** The figure's vector in Rumble's artwork, and the logo color it is drawn in there. */
        const val FIGURE = "rumble/icon/drawable/ic_launcher_foreground.xml"
        const val FIGURE_COLOR = "#89DA44"
    }
}

/**
 * Artwork made for the app by `bin/app-icon <app> <artwork.png>` under patches/src/main/resources/[directory]/.
 * Each of [files] (paths under res/) replaces the file the app ships under the same name, so resource IDs and
 * the manifest's icon references stay untouched.
 */
internal class LauncherArtwork(private val directory: String, private val files: List<String>) : LauncherIcon {
    override fun ResourcePatchContext.install(app: LurkApp) {
        val targets = files.associateWith { get("res/$it") }
        val missing = targets.filterValues { !it.isFile }.keys
        if (missing.isNotEmpty()) {
            throw PatchException("${app.name}: launcher icon files not found: ${missing.joinToString()}. " +
                "This patch targets ${app.name} ${app.version}; recheck res/ before updating compatibility.")
        }

        targets.forEach { (path, target) ->
            val icon = bundleResources.getResourceAsStream("$directory/$path")
                ?: throw PatchException("Bundled icon file missing: $directory/$path")
            icon.use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}

/** The body of an app's "Lurk icon" patch: installs the app's [LurkApp.icon]. */
internal fun ResourcePatchBuilder.lurkIcon(app: LurkApp) {
    compatibleWith(app.compatibility)

    execute {
        with(app.icon) { install(app) }
    }
}
