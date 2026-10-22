package io.github.jordanwoodson.patches.twitch.mature

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.HermesBundle
import io.github.jordanwoodson.patches.twitch.shared.twitchHermes
import io.github.jordanwoodson.patches.twitch.shared.twitchHermesPatch
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val KEY = "hide_mature_warning"

@Suppress("unused")
val hideMatureWarningPatch = bytecodePatch(
    name = "Hide mature content warning",
    description = "Skips the content warning on streams, videos and clips with mature content labels, as if " +
        "you had chosen Start Watching. Age, region, guardian and account restrictions still apply. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch, twitchHermesPatch)
    execute {
        // Native: every label counts as acknowledged, which is what Twitch's own debug override does.
        // Nothing is saved, so switching off brings the warning back for labels not yet accepted.
        MatureLabelAcknowledgedFingerprint.twitchMatch("content label acknowledgement").method
            .addInstructionsIfEnabled(KEY, """
                const/4 v0, 0x1
                return v0
            """)
        twitchHermes.hideMatureGate()
        twitchSettings.add(SwitchSetting(KEY, "Hide mature content warning",
            "Skip the content-label warning on streams, videos and clips. Age and region restrictions still apply.",
            Section.INTERFACE, default = false, restart = true))
    }
}

/**
 * RN theatre: deriveContentGate returns 'mature' only for sign-posted content with no age, region,
 * guardian or account gate. It returns null (no gate) instead; the other gates are unchanged.
 */
internal fun HermesBundle.hideMatureGate() {
    val id = stringId("mature")
    val before = byteArrayOf(0x91.toByte(), 1, id.toByte(), (id ushr 8).toByte(), (id ushr 16).toByte(), (id ushr 24).toByte())
    replace("deriveContentGate", KEY, before, byteArrayOf(0x94.toByte(), 1, 0x94.toByte(), 1, 0x94.toByte(), 1),
        defaultEnabled = false)
}
