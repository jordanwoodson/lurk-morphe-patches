package io.github.jordanwoodson.patches.rumble.consent

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "block_cookie_notice"

@Suppress("unused")
val blockCookieNoticePatch = bytecodePatch(
    name = "Block cookie notice",
    description = "Stops Rumble's Ketch consent SDK from loading, so the cookie notice never appears and no " +
        "consent is recorded, and ignores consent recorded earlier. Rumble then keeps the analytics and ad " +
        "tracking it gates on consent switched off. " +
        "After restarting, Airship does not start; this may affect push notifications. " +
        "Settings > Data Consent no longer opens.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Rumble calls load() on every resume. Where no privacy law applies, Ketch records consent to
        // every purpose without showing anything, which starts AppsFlyer and Firebase Analytics.
        // showPreferences() (Settings > Data Consent) records the same defaults as soon as it opens.
        // Returning false is what both do when they cannot create their WebView: the SDK never contacts
        // Ketch, the advertising ID Rumble hands it is never sent, and no consent is stored.
        listOf(KetchLoadFingerprint, KetchShowPreferencesFingerprint).forEach { fingerprint ->
            val match = fingerprint.matchAllOrNull()?.singleOrNull()
                ?: throw PatchException("Rumble: expected exactly one match for " +
                    "${fingerprint.javaClass.simpleName}. This patch targets 3.21.0; " +
                    "recheck the Ketch SDK before updating compatibility.")

            match.method.addInstructionsIfEnabled(
                KEY,
                """
                    const/4 v0, 0x0
                    return v0
                """,
            )
        }

        // Consent recorded while the switch was off (or before this patch was included) is cached and
        // would still start the trackers on every launch. Report no cached decision while it is on.
        val cached = CachedConsentHasDecisionFingerprint.matchAllOrNull()?.singleOrNull()
            ?: throw PatchException("Rumble: expected exactly one cached consent decision getter. " +
                "This patch targets 3.21.0; recheck the consent cache before updating compatibility.")
        cached.method.addInstructionsIfEnabled(
            KEY,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Block cookie notice",
                "After restarting: no Ketch consent notice and no consent recorded. May affect push notifications.",
                Section.PRIVACY,
                restart = true,
            ),
        )
    }
}
