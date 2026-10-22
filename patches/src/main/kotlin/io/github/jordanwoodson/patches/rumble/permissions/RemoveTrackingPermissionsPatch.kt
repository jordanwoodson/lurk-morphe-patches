package io.github.jordanwoodson.patches.rumble.permissions

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.childElements

// Permissions only trackers use. Without AD_ID, Google Play services hands every SDK, and Rumble's own
// requests (ad_device_id, watch progress), the all-zero advertising ID on Android 13 and newer (API 33, where
// the permission was introduced); older Android versions don't check it. The rest feed install attribution
// and ad measurement. READ_PHONE_STATE is declared but never requested or used.
private val TRACKING_PERMISSIONS = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.AD_SERVICES_CONFIG",
    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
    "com.samsung.android.mapsagent.permission.READ_APP_INFO",
    "com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA",
    "android.permission.READ_PHONE_STATE",
)

// Package visibility AppsFlyer uses to read install referrers from other stores.
private const val APPSFLYER_REFERRER_ACTION = "com.appsflyer.referrer.INSTALL_PROVIDER"
private const val SAMSUNG_REFERRER_PACKAGE = "com.samsung.android.mapsagent"

@Suppress("unused")
val removeTrackingPermissionsPatch = resourcePatch(
    name = "Remove tracking permissions",
    description = "Removes the advertising ID, ad measurement, install referrer and phone state permissions. " +
        "On Android 13 and newer, every SDK and Rumble's own requests then get an all-zero advertising ID; on " +
        "Android 12 and older, Google Play services hands out the real one regardless, unless you delete it in " +
        "Android's settings.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)

    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement

            val permissions = manifest.childElements("uses-permission")
                .filter { it.getAttribute("android:name") in TRACKING_PERMISSIONS }
            val missing = TRACKING_PERMISSIONS - permissions.map { it.getAttribute("android:name") }.toSet()
            if (missing.isNotEmpty()) {
                throw PatchException("Rumble: tracking permissions not found: ${missing.joinToString()}. " +
                    "This patch targets 3.21.0; recheck the manifest before updating compatibility.")
            }
            permissions.forEach { manifest.removeChild(it) }

            manifest.childElements("queries").flatMap { it.childElements() }.forEach { query ->
                val referrer = when (query.tagName) {
                    "package" -> query.getAttribute("android:name") == SAMSUNG_REFERRER_PACKAGE
                    "intent" -> query.childElements("action")
                        .any { it.getAttribute("android:name") == APPSFLYER_REFERRER_ACTION }
                    else -> false
                }
                if (referrer) query.parentNode.removeChild(query)
            }
        }
    }
}
