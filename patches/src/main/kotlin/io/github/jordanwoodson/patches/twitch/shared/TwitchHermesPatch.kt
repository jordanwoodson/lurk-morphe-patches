package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hermes.HERMES_BUNDLE_PATH
import app.morphe.patches.all.misc.hermes.hermesPatch
import io.github.jordanwoodson.patches.twitch.settings.twitchBundleLoaderPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH

/** Reset for each patching run; only selected dependent patches register edits. */
internal lateinit var twitchHermes: HermesBundle
    private set

internal val twitchHermesPatch = rawResourcePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchBundleLoaderPatch)
    execute {
        twitchHermes = HermesBundle(get(HERMES_BUNDLE_PATH, true).readBytes())
    }
    finalize {
        val expected = twitchHermes.previewEqualLength()
        // The official helper performs the verified equal-length replacements and refreshes SHA-1.
        hermesPatch { twitchHermes.replacements() }.execute(this)
        if (!get(HERMES_BUNDLE_PATH, true).readBytes().contentEquals(expected)) {
            throw PatchException("Twitch Hermes: patched output differs from the verified edit plan")
        }
        // Only the compiled delayed predicate needs appended storage. All established edits retain
        // the official equal-length path and are checked before the bounded append is written.
        if (twitchHermes.delayedAppend != null) get(HERMES_BUNDLE_PATH, true).writeBytes(twitchHermes.preview())
        get("assets/lurk-hermes.json").writeText(twitchHermes.manifest())
    }
}
