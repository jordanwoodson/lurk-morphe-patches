package io.github.jordanwoodson.patches.twitch.links

import app.morphe.patcher.Fingerprint

internal object NativeLinkShareFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;", "Ltv/twitch/android/shared/twitch/share/sheet/pub/TwitchShareableContent;",
        "Z", "Ltv/twitch/android/models/sharing/ShareLocation;"),
    strings = listOf("initiate", "android.intent.extra.TEXT", "MOBILE_SHARE_CONTENT_ID"),
)

internal object NativeVideoShareFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;", "Ltv/twitch/android/shared/twitch/share/sheet/pub/TwitchShareableContent;",
        "Ltv/twitch/android/models/sharing/ShareLocation;", "Lkotlin/jvm/functions/Function1;", "L"),
    strings = listOf("android.intent.extra.TEXT", "video/mp4", "MOBILE_SHARE_CONTENT_ID"),
)

internal object ClipVideoShareFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/Object;"), returnType = "Ljava/lang/Object;",
    strings = listOf("android.intent.extra.TEXT", "clip_video", "video/mp4"),
)

internal object GenericShareFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/CharSequence;", "Ljava/lang/String;"),
    returnType = "V", strings = listOf("android.intent.action.SEND", "android.intent.extra.TEXT", "text/plain"),
)

internal object ReactVideoShareFingerprint : Fingerprint(
    name = "videoShareIntent",
    parameters = listOf("Landroid/net/Uri;", "Ljava/lang/String;"), returnType = "Landroid/content/Intent;",
    strings = listOf("android.intent.extra.TEXT", "video/mp4"),
)

internal object ReactShareFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/react/modules/share/ShareModule;",
    name = "share",
    returnType = "V", strings = listOf("android.intent.extra.TEXT", "Failed to open share dialog"),
)

internal object CopyShareLinkFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/String;", "Ltv/twitch/android/shared/twitch/share/sheet/pub/TwitchShareableContent;"),
    returnType = "V", strings = listOf("copy_link"),
)

internal object NativeClipboardFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/CharSequence;"), returnType = "V", strings = listOf("clipboard"),
)

internal object ReactClipboardFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/react/modules/clipboard/ClipboardModule;",
    name = "setString",
    parameters = listOf("Ljava/lang/String;"), returnType = "V",
)
