package io.github.jordanwoodson.patches.kick.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hermes.HERMES_BUNDLE_PATH
import app.morphe.patches.all.misc.hermes.hermesPatch
import io.github.jordanwoodson.patches.kick.settings.kickBundleLoaderPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.updates.embeddedBundlePatch

/**
 * Ends the description of every patch that edits Kick's JavaScript, which all go through [kickHermesPatch] and so
 * through the embedded-bundle patch: Manager users should know what including one costs.
 */
internal const val OTA_UPDATES_OFF = " Like every patch that changes Kick's JavaScript, it turns off Kick's " +
    "over-the-air JavaScript updates, so Kick's code fixes arrive only when you patch a newer version."

/** Reset for each patching run; only selected dependent patches register edits. */
internal lateinit var kickHermes: HermesBundle
    private set

internal val kickHermesPatch = rawResourcePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickBundleLoaderPatch, embeddedBundlePatch)
    execute {
        kickHermes = HermesBundle(get(HERMES_BUNDLE_PATH, true).readBytes())
    }
    finalize {
        val expected = kickHermes.preview()
        // The official helper performs the verified equal-length replacements and refreshes SHA-1.
        hermesPatch { kickHermes.replacements() }.execute(this)
        if (!get(HERMES_BUNDLE_PATH, true).readBytes().contentEquals(expected)) {
            throw PatchException("Kick Hermes: patched output differs from the verified edit plan")
        }
        get("assets/lurk-hermes.json").writeText(kickHermes.manifest())
    }
}
