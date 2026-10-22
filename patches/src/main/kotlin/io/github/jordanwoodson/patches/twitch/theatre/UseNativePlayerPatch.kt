package io.github.jordanwoodson.patches.twitch.theatre

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val PACKAGE = "Ltv/twitch/android/feature/discovery/feed/rn/theatre/"

// Decides whether a live channel opens in the React Native "Ultralight" theatre (Route) or the native one
// (any Refuse value). Both callers only compare the result with Route.
private object TheatreRouteDecisionFingerprint : Fingerprint(
    definingClass = "${PACKAGE}RNTheatreRouteDecisionKt;",
    name = "rnTheatreRouteDecision",
    returnType = "${PACKAGE}RNTheatreRouteDecision;",
)

@Suppress("unused")
val useNativePlayerPatch = bytecodePatch(
    name = "Use native player",
    description = "Always opens live channels in Twitch's native player, never the React Native \"Ultralight\" player " +
        "a server experiment can switch some users to. Most Lurk playback, chat and ad patches hook the native player.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The method has no spare registers. Its first parameter says whether the channel is live; while
        // the switch is on it reads as false, which Twitch answers with RefuseNotLiveChannel.
        TheatreRouteDecisionFingerprint.twitchMatch("React Native theatre route decision").method.apply {
            addInstructions(0, """
                ${invokeStatic("Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayback;->routeLiveToReactNative(Z)Z",
                    parameterRegister(0))}
                move-result p0
            """)
        }
        twitchSettings.add(SwitchSetting("native_player", "Use native player",
            "Open live channels in the native player, which most Lurk patches act on.", Section.PLAYBACK))
    }
}
