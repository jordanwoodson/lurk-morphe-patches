package io.github.jordanwoodson.patches.rumble.privacy

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.childElements
import org.w3c.dom.Element

/**
 * The SDKs' own manifest switches, as each documents them. The "deactivated" flags of Firebase Analytics and
 * Performance are permanent: a later setAnalyticsCollectionEnabled(true) or setPerformanceCollectionEnabled(true),
 * which Rumble calls once consent is granted, is ignored. The rest are defaults that consent can still change.
 */
private val META_DATA = linkedMapOf(
    // Firebase Analytics: off for good, and no advertising ID if anything turns it on anyway.
    "firebase_analytics_collection_deactivated" to "true",
    "google_analytics_adid_collection_enabled" to "false",
    // Firebase Performance: off for good.
    "firebase_performance_collection_deactivated" to "true",
    // Crashlytics: off until Rumble enables it at run time (Rumble already ships this).
    "firebase_crashlytics_collection_enabled" to "false",
    // Facebook SDK: no automatic full initialization, app events, advertising ID or SDK monitoring.
    "com.facebook.sdk.AutoInitEnabled" to "false",
    "com.facebook.sdk.AutoLogAppEventsEnabled" to "false",
    "com.facebook.sdk.AdvertiserIDCollectionEnabled" to "false",
    "com.facebook.sdk.MonitorEnabled" to "false",
)

@Suppress("unused")
val disableTrackingSdksPatch = resourcePatch(
    name = "Disable tracking SDKs",
    description = "Switches Firebase Analytics and Firebase Performance off for good, so recorded consent can't " +
        "turn them on, and stops the Facebook SDK from initializing itself fully, logging app events or reading " +
        "the advertising ID by default. Crashlytics and AppsFlyer have no such switch and still follow consent; " +
        "Block cookie notice keeps them off. Changes the APK when patching, so it has no switch in Lurk settings.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)

    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("Rumble: the manifest has no application element")
            val existing = application.childElements("meta-data").associateBy { it.getAttribute("android:name") }
            META_DATA.forEach { (name, value) ->
                val element = existing[name] ?: document.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                element.removeAttribute("android:resource")
                element.setAttribute("android:value", value)
            }
        }
    }
}
