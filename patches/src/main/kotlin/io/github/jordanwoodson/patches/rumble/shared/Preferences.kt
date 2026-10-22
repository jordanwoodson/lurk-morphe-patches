package io.github.jordanwoodson.patches.rumble.shared

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import io.github.jordanwoodson.patches.shared.compose.preferenceDefault
import io.github.jordanwoodson.patches.shared.compose.preferenceStoredValue

/**
 * Rumble's UserPreferenceManager reads each DataStore key in a flow like `prefs[key] ?: default`. Returns that
 * reader and the index of the instruction producing the default (see patches/shared/compose).
 */
internal fun BytecodePatchContext.preferenceDefault(
    preferences: MutableClass,
    key: String,
    unbox: String,
): Pair<MutableMethod, Int> = preferenceDefault(RUMBLE, preferences, key, unbox)

/** The reader of a DataStore key and the index of the check-cast of its stored value (see patches/shared/compose). */
internal fun BytecodePatchContext.preferenceStoredValue(
    preferences: MutableClass,
    key: String,
    unbox: String,
    boxed: String,
): Pair<MutableMethod, Int> = preferenceStoredValue(RUMBLE, preferences, key, unbox, boxed)
