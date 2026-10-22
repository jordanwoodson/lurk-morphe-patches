package io.github.jordanwoodson.patches.shared

import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The obfuscated class and method names the patches matched, collected while patching and written into
 * the patched APK as `assets/lurk/locators.json` by each app's settings patch. Test probes read that file
 * instead of pinning R8 output by hand, so a new app version doesn't mean rewriting every probe.
 *
 * Keys are `<app>.<patch>.<what>`, for example `twitch.refresh_stream.player_view_attach`. Values are
 * smali type descriptors (`Lcom/example/A;`) or full method references
 * (`Lcom/example/A;->b(Ljava/lang/String;)V`).
 */
internal object Locators {
    private val entries = sortedMapOf<String, String>()

    fun record(key: String, value: String) {
        require(key.matches(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+"))) { "locator key $key: use <app>.<patch>.<what>" }
        val previous = entries.put(key, value)
        require(previous == null || previous == value) { "locator $key recorded twice with different values" }
    }

    fun record(key: String, method: MethodReference) =
        record(key, "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")

    /** Everything recorded for [app] (keys starting with `<app>.`), as a JSON object. */
    fun json(app: String): String = entries.filterKeys { it.startsWith("$app.") }
        .entries.joinToString(",", "{", "}") { (key, value) -> "${key.jsonString()}:${value.jsonString()}" }

    fun clear() = entries.clear()
}

private fun String.jsonString() = buildString {
    append('"')
    for (char in this@jsonString) {
        when {
            char == '"' -> append("\\\"")
            char == '\\' -> append("\\\\")
            char < ' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}
