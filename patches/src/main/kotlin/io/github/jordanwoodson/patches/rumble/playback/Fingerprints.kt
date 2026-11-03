package io.github.jordanwoodson.patches.rumble.playback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// Rumble's UserPreferenceManager. Its constructor creates the DataStore keys by name.
internal object UserPreferencesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("autoplayOnKey", "playbackInFeedKey", "videoQualityKey"),
)

// Builds the player for Home's featured video: (video, screen, playEvenIfFeedPlaybackIsOff, ...). Its class
// gets the "Playback in feeds" check injected, like the builder for ordinary feed cards, which takes more
// parameters.
private object FeaturedVideoPlayerClassFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("playbackInFeedsEnabledUseCase"),
)

internal object FeaturedVideoPlayerFingerprint : Fingerprint(
    classFingerprint = FeaturedVideoPlayerClassFingerprint,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Ljava/lang/String;", "Z", "Lkotlin/jvm/functions/Function2;", "L"),
)

// Picks the source (one URL per quality) a video starts with:
// (sources, preferred height, preferred bitrate, default height, isLive, lowQuality, liveUsesAuto).
internal object VideoSourceSelectionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Ljava/util/List;", "Ljava/lang/Integer;", "Ljava/lang/Integer;", "I", "Z", "Z", "Z"),
    filters = listOf(string("sourceList")),
)

// DefineCurrentWatchPositionUseCase(video, resume, continuation): where a video starts. It reads the position
// kept on the phone, then uses the server's position signed in and 0 signed out.
private object WatchPositionClassFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("getLastPositionUseCase", "sessionManager"),
)

internal object WatchPositionFingerprint : Fingerprint(
    classFingerprint = WatchPositionClassFingerprint,
    returnType = "Ljava/lang/Object;",
    parameters = listOf("L", "Z", "L"),
)
