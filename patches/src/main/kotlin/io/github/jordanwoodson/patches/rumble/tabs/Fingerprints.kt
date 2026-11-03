package io.github.jordanwoodson.patches.rumble.tabs

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

private object StartupTabClassFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("rumble_prefs", "defaultTabIndexKey"),
)

// The rumble_prefs helper's getter of the tab Rumble opens on (defaultTabIndexKey: 0 Home, 3 My Feed).
internal object StartupTabFingerprint : Fingerprint(
    classFingerprint = StartupTabClassFingerprint,
    returnType = "I",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = "Landroid/content/SharedPreferences;", name = "getInt"),
    ),
)
