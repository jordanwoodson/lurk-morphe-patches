package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.Constants.TWITCH_VERSION

/**
 * The single method this fingerprint matches, or a PatchException naming [what]. The match is recorded as a
 * locator (see [matchKey]), so test probes can find the obfuscated method in the patched APK instead of
 * pinning its R8 name.
 */
context(context: BytecodePatchContext)
internal fun Fingerprint.twitchMatch(what: String): Match {
    val match = matchAllOrNull()?.singleOrNull()
        ?: throw PatchException("Twitch $TWITCH_VERSION: expected exactly one $what")
    Locators.record(matchKey(what), match.originalMethod)
    return match
}

/**
 * `twitch.match.<name>`: a named fingerprint object's name without "Fingerprint" (ChatLineItemFingerprint
 * gives `chat_line_item`), or for a fingerprint made in place, its [what].
 */
internal fun Fingerprint.matchKey(what: String): String {
    val name = javaClass.simpleName.takeIf { javaClass != Fingerprint::class.java && it.isNotEmpty() }
        ?.removeSuffix("Fingerprint")?.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
    return "twitch.match." + (name ?: what).lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
