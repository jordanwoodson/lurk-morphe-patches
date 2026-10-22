package io.github.jordanwoodson.patches.twitch.badges

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

// The spans of one native chat line (timestamp, badges, sender, header, message).
internal object SpanGroupFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("ChatMessageSpanGroup(timestampSpan=", ", badgeSpan=", ", senderSpan="),
)

// One badge of a chat line: (name, image URL).
internal object BadgeViewModelFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("MessageBadgeViewModel(name=", ", badgeUrl="),
)

// Builds a chat line's spans from its badges, sender and tokens. Used by live chat, VOD replay, clips and more.
internal object ChatLineSpanBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf(
        "L", "Landroid/content/Context;", "Ljava/util/List;", "L", "Ljava/lang/Integer;", "Ljava/util/List;",
        "Z", "Z", "Z", "Ljava/lang/String;", "Ljava/lang/String;", "L", "Z", "L", "Ljava/lang/Long;", "L", "L",
        "Ltv/twitch/android/core/mvp/viewdelegate/EventDispatcher;", "L", "Z", "I",
    ),
)
