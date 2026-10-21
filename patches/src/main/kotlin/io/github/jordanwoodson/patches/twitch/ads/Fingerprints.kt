package io.github.jordanwoodson.patches.twitch.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal const val ADS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchAds;"
internal const val AD_EDGE_API = "Ltv/twitch/android/shared/ad/edge/api/AdEdgeApi;"
internal const val IVS_MEDIA_PLAYER = "Lcom/amazonaws/ivs/player/MediaPlayer;"
internal const val RN_PLAYER_VIEW = "Lcom/twitchrn/player/TwitchIVSPlayerView;"
internal const val RN_PLAYBACK_SESSION = "Lcom/twitchrn/player/PlaybackSession;"
internal const val RN_PLAYBACK_DISPLAY = "Lcom/twitchrn/player/PlaybackDisplay;"
internal const val BASE_VIEW_DELEGATE = "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"
internal const val BRAND_SURVEY_ITEM = "Ltv/twitch/android/shared/viewer/pub/FeedItem\$BrandSurveyItem;"
internal const val RN_VIDEO_AD_PLAYER = "Ltv/twitch/android/feature/discovery/feed/rn/runtime/TwitchRNVideoAdPlayer;"
internal const val RN_VIDEO_AD_DELEGATE = "Lcom/twitchrn/player/TwitchVideoAdView\$Delegate;"

// RxJava's Single emitter is the only parameter; R8 renames its class, so match any object type.
internal object ClientAdEligibilityFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("L"),
    strings = listOf("bypass_ad_eligibility", "skip_preroll", "decline_all_ads"),
)

// The lazy creators of the Retrofit service every native ad request goes through: they set the ad
// server as the base URL and create the AdEdgeApi interface.
internal object AdEdgeApiCreatorFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;", parameters = emptyList(),
    filters = listOf(string("https://edge.ads.twitch.tv/"), checkCast(AD_EDGE_API)),
)

// React Native's network module: every JavaScript fetch, including the RN ad requests, starts here.
internal object RnNetworkRequestFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/react/modules/network/NetworkingModule;",
    name = "sendRequest", returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "D", "Lcom/facebook/react/bridge/ReadableArray;",
        "Lcom/facebook/react/bridge/ReadableMap;", "Ljava/lang/String;", "Z", "D", "Z"),
)

// React Native's VAST video ad player: parses the ad and builds a player once it's attached to its view.
internal object RnVideoAdParseFingerprint : Fingerprint(
    definingClass = RN_VIDEO_AD_PLAYER, name = "parseAndBuild", returnType = "V", parameters = emptyList(),
)

// The native feed's brand survey state, which inserts a survey where a feed ad didn't fill.
internal object BrandSurveyStateFingerprint : Fingerprint(
    name = "toString", returnType = "Ljava/lang/String;", parameters = emptyList(),
    strings = listOf("BrandSurveyState(brandSurveyItem="),
)

// The native (Core) player's HLS metadata handler, which turns EXT-X-DATERANGE ad cues into events.
internal object NativeAdCueFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Lorg/json/JSONObject;"),
    strings = listOf("twitch-stitched-ad", "twitch-stream-source", "pbyp-preflight"),
)

// The native stitched-ad overlay (countdown, Learn more, mute button) drawing its state.
internal object StitchedAdOverlayFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ltv/twitch/android/core/mvp/viewdelegate/ViewDelegateState;"),
    strings = listOf("install_now_url", "learn_more_url"),
)

// That overlay's "ad playing" state.
internal object StitchedAdPlayingFingerprint : Fingerprint(
    name = "toString", returnType = "Ljava/lang/String;", parameters = emptyList(),
    strings = listOf("AdPlaying(countdownText=", ", podSize=", ", isPlayStoreAd="),
)

internal object RnPlayerMetadataFingerprint : Fingerprint(
    definingClass = RN_PLAYBACK_SESSION, name = "handleMetadata", returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/nio/ByteBuffer;"),
)

internal object RnPlayerStreamUrlFingerprint : Fingerprint(
    definingClass = RN_PLAYBACK_SESSION, name = "setStreamUrl", returnType = "V", parameters = listOf("Ljava/lang/String;"),
)

internal object RnPlayerReleaseFingerprint : Fingerprint(
    definingClass = RN_PLAYBACK_SESSION, name = "release", returnType = "V", parameters = emptyList(),
)

// The native live playlist (usher) URL, built from the channel and its signed playback token.
internal object LivePlaylistUrlFingerprint : Fingerprint(
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Ljava/lang/String;", "Ltv/twitch/android/models/AccessTokenResponse;", "L", "Z"),
    strings = listOf("api/v2/channel/hls/", "api/channel/hls/"),
)

// React Native's player preloader, keyed by the stream URL the player view later asks for.
internal object RnPreloadFingerprint : Fingerprint(
    definingClass = "Lcom/twitchrn/player/PreloadHolder;", name = "preload", returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z"),
)

internal object ProxyTemplateFingerprint : Fingerprint(
    definingClass = ADS, name = "proxyTemplate", returnType = "Ljava/lang/String;", parameters = emptyList(),
)

internal const val BACKUP_STREAM = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchBackupStream;"
internal const val IVS_REQUEST = "Lcom/amazonaws/ivs/net/Request;"
internal const val IVS_BODY_CONSUMER = "Lcom/amazonaws/ivs/net/ByteBufferConsumer;"
internal const val IVS_BODY_PROVIDER = "Lcom/amazonaws/ivs/net/ByteBufferConsumer\$Provider;"

// IVS (every Twitch player) sends each request, playlists included, through its Java HTTP client. This is
// the OkHttp one, which IVS picks when OkHttp is in the app; it reads the URL from the request once.
internal object IvsHttpRequestFingerprint : Fingerprint(
    definingClass = "Lcom/amazonaws/ivs/net/OkHttpClient;", name = "execute", returnType = "V",
    parameters = listOf(IVS_REQUEST, "Lcom/amazonaws/ivs/net/ResponseCallback;"),
    filters = listOf(methodCall(definingClass = IVS_REQUEST, name = "getUrl")),
)

// The OkHttp client's response body consumer, which hands the body to the player in buffers.
internal object IvsBodyConsumerFingerprint : Fingerprint(
    definingClass = IVS_BODY_CONSUMER, name = "consume", returnType = "V",
    parameters = listOf("Lcom/amazonaws/ivs/net/ReadCallback;"),
)
