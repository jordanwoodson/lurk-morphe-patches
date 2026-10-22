package io.github.jordanwoodson.patches.rumble.shared

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import io.github.jordanwoodson.patches.shared.compose.preferenceDefault

/**
 * Rumble's UserPreferenceManager reads each DataStore key in a flow like `prefs[key] ?: default`. Returns that
 * reader and the index of the instruction producing the default (see patches/shared/compose).
 */
internal fun BytecodePatchContext.preferenceDefault(
    preferences: MutableClass,
    key: String,
    unbox: String,
): Pair<MutableMethod, Int> = preferenceDefault(RUMBLE, preferences, key, unbox)
