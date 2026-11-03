package io.github.jordanwoodson.patches.twitch.layout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral

internal const val LANDING_ACTIVITY = "Ltv/twitch/android/feature/viewer/landing/ViewerLandingActivity;"

/** The resource switches Twitch's tablet UI reads: values-sw600dp sets the first two, values-w1024dp the third. */
internal val TABLET_BOOLS = listOf("isTablet", "is_tablet", "following_drawer_use_sidebar")

// Every method reading one of those switches with Resources.getBoolean: the device form factor (Phone or
// Tablet) behind Twitch's tablet layouts, the navigation rail and Following sidebar, and the cast dialogs.
internal object TabletBoolFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(*TABLET_BOOLS.map { resourceLiteral(ResourceType.BOOL, it) }.toTypedArray()),
        methodCall(definingClass = "Landroid/content/res/Resources;", name = "getBoolean", returnType = "Z"),
    ),
)

// The main screen inflating its layout (viewer_landing): the bottom navigation bar, navigation rail and the
// fragment container above the bar.
internal object LandingInflateFingerprint : Fingerprint(
    definingClass = LANDING_ACTIVITY,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "viewer_landing"),
        methodCall(definingClass = "Landroid/view/LayoutInflater;", name = "inflate"),
    ),
)

// The main screen's window insets listener: it pads the layout for the status bar and sides, and gives the
// bottom navigation bar's group a bottom margin for the gesture bar. It's the only listener holding the activity.
internal object LandingInsetsFingerprint : Fingerprint(
    name = "onApplyWindowInsets",
    filters = listOf(
        methodCall(definingClass = "Landroid/view/View;", name = "getPaddingBottom"),
        methodCall(definingClass = "Landroid/view/View;", name = "setPadding"),
        methodCall(definingClass = "Landroid/view/View;", name = "setLayoutParams"),
    ),
    custom = { method, classDef ->
        method.parameterTypes.firstOrNull()?.toString() == "Landroid/view/View;" &&
            classDef.fields.any { it.type == LANDING_ACTIVITY }
    },
)

// The player configuration each player presenter makes: the content format (HLS for live channels and
// videos, MP4 for clips and feed videos) and whether ExoPlayer is forced. Its toString keeps the names.
internal object PlayerConfigurationFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("PlayerConfiguration(crashReporterUtil=", ", forceExoPlayer=", ", contentFormat="),
)
