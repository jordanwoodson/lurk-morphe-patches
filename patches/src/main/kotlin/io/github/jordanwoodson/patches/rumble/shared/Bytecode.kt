package io.github.jordanwoodson.patches.rumble.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.compose.AppCode
import io.github.jordanwoodson.patches.shared.compose.ComposableFingerprint
import io.github.jordanwoodson.patches.shared.compose.enumConstant
import io.github.jordanwoodson.patches.shared.compose.enumWith
import io.github.jordanwoodson.patches.shared.compose.kotlinObject
import io.github.jordanwoodson.patches.shared.compose.single
import io.github.jordanwoodson.patches.shared.compose.singleMethod
import io.github.jordanwoodson.patches.shared.compose.unitReference

// Rumble's side of patches/shared/compose: the app-agnostic lookups with Rumble's name and target version in
// their error messages. See that package for how each finds code without obfuscated names.

/** Rumble, for the shared lookups' error messages. */
internal val RUMBLE = AppCode(COMPATIBILITY_RUMBLE)

/** The one match of this fingerprint, or a PatchException naming [what]. */
context(context: BytecodePatchContext)
internal fun Fingerprint.single(what: String): Match = single(RUMBLE, what)

/** A Rumble composable, found by its Compose trace label ("com.rumble.battles.….Name (File.kt:line)"). */
internal fun composableFingerprint(qualifiedName: String) = ComposableFingerprint(qualifiedName)

internal fun Method.unitReference(): String = unitReference(RUMBLE)

internal fun BytecodePatchContext.kotlinObject(name: String, among: Collection<String>? = null): String =
    kotlinObject(RUMBLE, name, among)

internal fun ClassDef.enumConstant(name: String): String = enumConstant(RUMBLE, name)

internal fun BytecodePatchContext.enumWith(vararg names: String): ClassDef = enumWith(RUMBLE, *names)

internal fun MutableClass.singleMethod(what: String, predicate: (MutableMethod) -> Boolean): MutableMethod =
    singleMethod(RUMBLE, what, predicate)
