package io.github.jordanwoodson.patches.rumble.consent

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// The Ketch Android SDK's two entry points that open its consent WebView, Ketch.load() and
// Ketch.showPreferences(), are both (Boolean, Boolean, Int, Int): Boolean. R8 renames the class,
// but each method logs its own line when an experience is already showing.

internal object KetchLoadFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Z", "Z", "I", "I"),
    strings = listOf("Not loading as an experience is already being shown"),
)

internal object KetchShowPreferencesFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("Z", "Z", "I", "I"),
    strings = listOf("Not showing preferences as an experience is already being shown"),
)

// Rumble keeps its own copy of the last decision (consent_cache.json) and applies it at startup without
// Ketch. The cached model's toString names it; hasDecision is its only no-argument boolean getter.
internal object CachedConsentHasDecisionFingerprint : Fingerprint(
    classFingerprint = Fingerprint(name = "toString", strings = listOf("CachedConsent(hasDecision=")),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN, MatchFirst()),
        opcode(Opcode.RETURN, MatchAfterImmediately()),
    ),
)

// Rumble's consent service hands each decision to every tracker (AppsFlyer, Firebase, Facebook, Airship).
// Its constructor checks its parameters by name.
internal object ConsentServiceFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("trackers", "sendDebugLogEventUseCase", "getAdvertisingIdUseCase"),
)
