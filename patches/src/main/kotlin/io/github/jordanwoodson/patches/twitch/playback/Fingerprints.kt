package io.github.jordanwoodson.patches.twitch.playback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

internal object AutoplayPreferenceFingerprint : Fingerprint(
    parameters = emptyList(), returnType = "L", strings = listOf("autoplay_enabled", ""),
)

// R8 shares this Function2 class with other lambdas. Only its autoplay preference case is changed.
internal object AutoplayObserverFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    returnType = "Ljava/lang/Object;", strings = listOf("autoplay_enabled", ""),
)

internal object QualityPreferencesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V", strings = listOf("should_video_auto_quality_downgrade", "last_used_video_quality", "auto"),
)

// The native player's select-by-name (live, VOD and vertical video): Twitch matches the requested name
// against each IVS Quality's name. A pending request is retried through this method once qualities load.
internal object PlayerQualityByNameFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ljava/lang/String;", "Z"), strings = listOf("auto"),
    filters = listOf(
        methodCall(definingClass = "Lcom/amazonaws/ivs/player/Player;", name = "getQualities"),
        methodCall(definingClass = "Lcom/amazonaws/ivs/player/Quality;", name = "getName"),
    ),
)

// Stable React Native library boundary. Exactly one pushNull produces an absent-key result row.
internal object MissingAsyncStorageValueFingerprint : Fingerprint(
    returnType = "V", strings = listOf("catalystLocalStorage", "key", "value"),
    custom = { method, classDef ->
        classDef.type.startsWith("Lcom/reactnativecommunity/asyncstorage/AsyncStorageModule$") &&
            method.name == "doInBackgroundGuarded"
    },
)

internal object RecommendationAutoplayFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/Object;"), returnType = "Ljava/lang/Object;",
    strings = listOf("player_rec_autoplay", "player_rec_select"),
)

internal object RecommendationCountdownFingerprint : Fingerprint(
    parameters = listOf("L"), returnType = "V",
    strings = listOf("progress", "Recommended model: ", " cannot be bound"),
)

// The app setting "Continue Playing Audio in the Background", which every player reads when the app is
// left. Twitch's fallback when none is saved is "never".
internal object BackgroundAudioSettingFingerprint : Fingerprint(
    returnType = "L", parameters = emptyList(),
    strings = listOf("background_audio_setting", "never", "headphones_speaker", "always"),
)

// Whether the native player's video stats panel is available: staff accounts with a debug preference.
internal object VideoDebugPanelFingerprint : Fingerprint(
    returnType = "Z", parameters = emptyList(), strings = listOf("showVideoDebugPanel"),
    filters = listOf(methodCall(definingClass = "Landroid/content/SharedPreferences;", name = "getBoolean")),
)

// Twitch's master playlist parser: reads EXT-X-TWITCH-INFO, whose STREAM-TIME (the stream's age) it drops.
internal object MasterPlaylistFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L", parameters = listOf("Ljava/lang/String;"),
    strings = listOf("#EXT-X-TWITCH-INFO:", "STREAM-TIME", "SERVING-ID"),
)

// The stats panel's rows: each entry of the panel's state becomes a row from its label and value.
internal object VideoStatsRowsFingerprint : Fingerprint(
    name = "invoke", returnType = "Ljava/lang/Object;", parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(name = "getLabel", parameters = emptyList(), returnType = "Ljava/lang/String;"),
        methodCall(name = "<init>", parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            location = MatchAfterWithin(2)),
    ),
)
