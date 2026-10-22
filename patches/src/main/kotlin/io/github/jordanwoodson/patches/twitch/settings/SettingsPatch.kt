package io.github.jordanwoodson.patches.twitch.settings

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.apps.TWITCH
import io.github.jordanwoodson.patches.shared.apps.lurkSettings
import io.github.jordanwoodson.patches.shared.apps.reactNativeBundleLoader

internal val twitchSettings = TWITCH.settings
internal const val TWITCH_BUNDLE = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchBundle;"

internal val twitchSettingsPatch = bytecodePatch(
    description = "Adds Lurk settings to Twitch's launcher shortcut.",
) {
    lurkSettings(TWITCH)
}

/** Only Hermes consumers need this hook. Native-only patch combinations never read a manifest. */
internal val twitchBundleLoaderPatch = bytecodePatch {
    reactNativeBundleLoader(TWITCH, twitchSettingsPatch)
}
