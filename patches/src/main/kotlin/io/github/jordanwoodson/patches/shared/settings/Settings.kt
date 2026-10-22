package io.github.jordanwoodson.patches.shared.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.jordanwoodson.patches.shared.Locators
import java.net.URLDecoder
import java.util.jar.JarFile

/** Smali descriptor of extensions/shared Settings, which injected code asks whether a switch is on. */
internal const val SETTINGS_CLASS = "Lio/github/jordanwoodson/extension/shared/settings/Settings;"

/** The groups on the settings screen, in screen order. */
internal enum class Section(val title: String) {
    PRIVACY("Privacy"),
    INTERFACE("Interface"),
    PLAYBACK("Playback"),
    CHAT("Chat"),
    PREMIUM("Premium"),
    /** Lurk's own settings: Debug logging, then the screen's tools (export, import, report, reset). */
    ADVANCED("Advanced"),
}

/**
 * An entry on the Lurk settings screen. Its value starts at [default] (from the patch's options) and
 * stays until it's changed on the screen. [parent] is the switch it only matters under; [restart]
 * marks values the app reads once, at startup.
 */
internal sealed class Setting(
    val key: String,
    val title: String,
    val summary: String,
    val section: Section,
    val default: String,
    val parent: String?,
    val restart: Boolean,
)

/**
 * A switch. Injected code reads it with [isEnabled]. [keepWhenPaused] marks lockout protection that "Pause
 * Lurk" must leave alone (see [KEEP_WHEN_PAUSED] for the switches marked here).
 */
internal class SwitchSetting(
    key: String,
    title: String,
    summary: String,
    section: Section,
    default: Boolean = true,
    parent: String? = null,
    restart: Boolean = false,
    val keepWhenPaused: Boolean = false,
) : Setting(key, title, summary, section, default.toString(), parent, restart)

/**
 * Switches "Pause Lurk" leaves as they are, besides any registered with keepWhenPaused: Block update prompts
 * (without it a forced update can lock someone out of the app, and updating removes Lurk), Twitch's Watch
 * without signing in (without it a signed-out Twitch stops at the sign-in screen, so the pause would lock
 * the person out too) and Lurk's own Debug logging, which helps while finding out whether Lurk is the
 * cause of a problem.
 */
private val KEEP_WHEN_PAUSED = setOf("block_update_prompts", "watch_without_login", "debug_logging")

/** One of [choices] (label to value). Injected code reads the value as a number with [intValue]. */
internal class ChoiceSetting(
    key: String,
    title: String,
    summary: String,
    section: Section,
    default: String,
    val choices: List<Pair<String, String>>,
    restart: Boolean = false,
    parent: String? = null,
) : Setting(key, title, summary, section, default, parent, restart) {
    init {
        require(choices.any { it.second == default }) { "$key: default $default is not a choice" }
    }
}

/** Free text, such as a comma-separated list, typed into a dialog. The extension reads it with Settings.getText. */
internal class TextSetting(
    key: String,
    title: String,
    summary: String,
    section: Section,
    default: String = "",
    parent: String? = null,
    restart: Boolean = false,
) : Setting(key, title, summary, section, default, parent, restart)

/**
 * Lurk's own switch for extensions/shared Logger.d, which each app's settings patch registers first. Off by
 * default: the lines it turns on can name the channels and videos someone opens.
 */
internal val debugLoggingSetting
    get() = SwitchSetting(
        "debug_logging",
        "Debug logging",
        "Detailed lines in the system log for bug reports (adb logcat -s lurk). They can name the channels " +
            "and videos you open.",
        Section.ADVANCED,
        default = false,
    )

/**
 * The settings an app's patches register, written into the extension as JSON when the app's settings
 * patch finalizes. Patches add theirs at the end of execute, so a patch that is left out or fails
 * shows nothing.
 */
internal class SettingsScreen(private val app: String) {
    private val settings = mutableListOf<Setting>()

    /** Starts a patching run. Patch objects outlive one run in Morphe Manager. */
    fun clear() = settings.clear()

    /** Registers [added]. A key can be registered once: two patches sharing a key would share one switch. */
    fun add(vararg added: Setting) {
        for (setting in added) {
            require(settings.none { it.key == setting.key }) { "Lurk setting ${setting.key} is registered twice" }
            settings += setting
        }
    }

    /**
     * By section, then title, each setting followed by the settings under it. A setting's parent must be a
     * registered top-level setting in the same section, or the screen couldn't show the setting under it.
     * Under a switch, a setting only applies (and can only be changed) while the switch is on; under a
     * choice it is grouped with it.
     */
    fun json(): String {
        for (setting in settings) {
            val parent = setting.parent ?: continue
            val above = settings.singleOrNull { it.key == parent }
            require(above != null && above.parent == null && above.section == setting.section) {
                "Lurk setting ${setting.key}: its parent $parent is not a registered top-level setting in ${setting.section.title}"
            }
        }
        val ordered = settings.filter { it.parent == null }
            .sortedWith(compareBy({ it.section.ordinal }, { it.title }))
            .flatMap { setting -> listOf(setting) + settings.filter { it.parent == setting.key } }
        val entries = ordered.joinToString(",") { setting ->
            val fields = mutableListOf(
                "key" to setting.key.json(),
                "type" to when (setting) {
                    is ChoiceSetting -> "choice"
                    is TextSetting -> "text"
                    is SwitchSetting -> "switch"
                }.json(),
                "title" to setting.title.json(),
                "summary" to setting.summary.json(),
                "section" to setting.section.title.json(),
                "default" to setting.default.json(),
                "parent" to (setting.parent?.json() ?: "null"),
                "restart" to setting.restart.toString(),
            )
            if (setting is SwitchSetting && (setting.keepWhenPaused || setting.key in KEEP_WHEN_PAUSED)) {
                fields += "keep" to "true"
            }
            if (setting is ChoiceSetting) {
                fields += "choices" to setting.choices.joinToString(",", "[", "]") { (label, value) ->
                    "[${label.json()},${value.json()}]"
                }
            }
            fields.joinToString(",", "{", "}") { (name, value) -> "${name.json()}:$value" }
        }
        return "{\"name\":\"Lurk\",\"version\":${bundleVersion().json()},\"app\":${app.json()},\"settings\":[$entries]}"
    }
}

private fun String.json() = buildString {
    append('"')
    for (char in this@json) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

/** The Version from this bundle's jar manifest, or "" when it can't be read. */
private fun bundleVersion(): String = runCatching {
    val type = SettingsScreen::class.java
    val url = type.classLoader!!.getResource(type.name.replace('.', '/') + ".class")!!.toString()
    if (!url.startsWith("jar:file:")) return ""
    val jar = URLDecoder.decode(url.removePrefix("jar:file:").substringBefore('!'), "UTF-8")
    JarFile(jar).use { it.manifest.mainAttributes.getValue("Version") }
}.getOrNull().orEmpty()

/**
 * Writes what the patches matched for [app] (see [Locators]) to `assets/lurk/locators.json` in the patched
 * APK, then forgets it for the next run. Called from each app's settings patch when it finalizes, after every
 * other patch.
 */
internal fun ResourcePatchContext.writeLocators(app: String) {
    get(LOCATORS_ASSET).apply { parentFile.mkdirs() }.writeText(Locators.json(app))
    Locators.clear()
}

/** Where [writeLocators] puts the matched names; probes open it from the APK's assets. */
internal const val LOCATORS_ASSET = "assets/lurk/locators.json"

/** Smali that leaves in [register] (any v or p register) whether the switch [key] is on. */
internal fun isEnabled(key: String, register: String) = """
    const-string $register, "$key"
    invoke-static/range { $register .. $register }, $SETTINGS_CLASS->isEnabled(Ljava/lang/String;)Z
    move-result $register
"""

/** Smali that leaves in [register] the value of the choice [key] as a number, 0 if it isn't one. */
internal fun intValue(key: String, register: String) = """
    const-string $register, "$key"
    invoke-static/range { $register .. $register }, $SETTINGS_CLASS->getInt(Ljava/lang/String;)I
    move-result $register
"""

/**
 * Runs [smali] at the start of the method while the switch [key] is on; while it is off the method
 * runs as it was. [smali] can jump to `:lurk_off` to carry on with the original code, and can use
 * the first [locals] local registers (v0 up), which hold nothing yet at that point.
 */
internal fun MutableMethod.addInstructionsIfEnabled(key: String, smali: String, locals: Int = 1) {
    requireLocals(locals)
    addInstructionsWithLabels(
        0,
        isEnabled(key, "v0") + "if-eqz v0, :lurk_off\n" + smali,
        ExternalLabel("lurk_off", getInstruction(0)),
    )
}

/** Fails unless the method has [count] local registers, so v0 up to v(count - 1) can't be a parameter. */
internal fun MutableMethod.requireLocals(count: Int) {
    val parameters = parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum() +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val locals = implementation!!.registerCount - parameters
    if (locals < count) {
        throw PatchException("$definingClass->$name has $locals local registers, the patch needs $count")
    }
}
