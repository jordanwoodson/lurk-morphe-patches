package io.github.jordanwoodson.patches.rumble.tabs

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.shared.compose.calls
import io.github.jordanwoodson.patches.shared.compose.constStrings

// The rumble_prefs helper's getter of the tab Rumble opens on (defaultTabIndexKey: 0 Home, 3 My Feed).
internal object StartupTabFingerprint : Fingerprint(
    returnType = "I",
    parameters = emptyList(),
    custom = { method, classDef ->
        method.calls("Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I") &&
            classDef.methods.any { constructor ->
                constructor.name == "<init>" &&
                    constructor.constStrings().containsAll(listOf("rumble_prefs", "defaultTabIndexKey"))
            }
    },
)
