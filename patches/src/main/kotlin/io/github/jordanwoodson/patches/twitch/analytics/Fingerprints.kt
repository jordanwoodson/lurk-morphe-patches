package io.github.jordanwoodson.patches.twitch.analytics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object BranchEventFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ljava/lang/String;"), strings = listOf("branch_event_name"),
)

internal object SpadeEventFingerprint : Fingerprint(
    classFingerprint = BranchEventFingerprint,
    returnType = "V",
    strings = listOf("branch_campaign", "mweb_device_id", "x_untrusted_minute-watched_spade"),
)

internal object SpadeBatchFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Ljava/util/ArrayList;"),
    strings = listOf("graphQL", "both", "[", "]"),
)

internal object UserDataConsentFingerprint : Fingerprint(
    name = "toString", returnType = "Ljava/lang/String;", parameters = emptyList(),
    strings = listOf("UserDataConsent(consentOptions="),
)

internal object VendorConsentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    filters = listOf(methodCall(
        definingClass = "Ltv/twitch/android/models/privacy/VendorConsentSetting;", name = "getConsentStatus",
    )),
    custom = { method, _ -> method.parameterTypes.size == 2 &&
        method.parameterTypes.all { it.toString().startsWith("L") } &&
        method.implementation?.instructions?.any {
            val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
            reference?.definingClass == "Ltv/twitch/android/models/privacy/VendorConsentSetting;" &&
                reference.name == "getConsentStatus" && reference.returnType == method.returnType
        } == true },
)

internal object SentryInitializationFingerprint : Fingerprint(
    definingClass = "Lio/sentry/android/core/SentryAndroid;", name = "init", returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Lio/sentry/ILogger;", "Lio/sentry/Sentry\$OptionsConfiguration;"),
)

internal object CrashlyticsArbiterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR), returnType = "V",
    strings = listOf("com.google.firebase.crashlytics", "firebase_crashlytics_collection_enabled"),
)

internal object BranchInitializationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR), returnType = "V", parameters = listOf("Landroid/content/Context;"),
    strings = listOf("bnc_tracking_state", "bnc_retry_count"),
)

internal object BranchTrackingSetterFingerprint : Fingerprint(
    returnType = "V", parameters = listOf("Landroid/content/Context;", "Z"),
    strings = listOf("disableTracking context: ", "Tracking disabled. Clearing all pending requests"),
)

// Nielsen's server-to-server measurement: each ping is a GET to imrworldwide.com with the advertising ID.
internal object NielsenPingFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(methodCall(
        definingClass = "Ltv/twitch/android/shared/nielsen/NielsenS2SApi\$NielsenS2SService;", name = "firePing")),
)

// Sprig (UserLeap) in-app surveys and event tracking. The public Sprig facade delegates to this SDK object;
// without an environment configured, its tracking and surveys have nowhere to send to.
internal object SprigConfigureFingerprint : Fingerprint(
    definingClass = "Lcom/userleap/UserLeap;", name = "configure", returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
)
