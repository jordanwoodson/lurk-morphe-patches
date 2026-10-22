package io.github.jordanwoodson.patches.shared.compose

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * A Jetpack DataStore preference read as `prefs[key] ?: default` in a preference manager class: returns the
 * reader and the index of the instruction producing the default, found through the key's name. The way
 * there: constructor (key string -> field), the synthetic accessor for that field, the inner class method that
 * calls the accessor and unboxes the stored value with [unbox], and the branch taken when nothing is stored.
 */
internal fun BytecodePatchContext.preferenceDefault(
    app: AppCode,
    preferences: MutableClass,
    key: String,
    unbox: String,
): Pair<MutableMethod, Int> {
    val reader = preferenceReader(app, preferences, key, unbox)
    val code = reader.implementation!!.instructions.toList()
    val unboxIndex = code.indexOfFirst { it is ReferenceInstruction && it.reference.toString() == unbox }
    val nullCheck = (unboxIndex downTo 0).first { code[it].opcode == Opcode.IF_EQZ }
    return reader to (code[nullCheck] as BuilderOffsetInstruction).target.location.index
}

/**
 * Where a DataStore preference's stored value is read, `prefs[key] as T?`: the reader (see [preferenceDefault])
 * and the index of the check-cast of the stored value to [boxed], so code inserted after it can replace a null
 * (nothing stored) with another value. The stored value is in that instruction's register.
 */
internal fun BytecodePatchContext.preferenceStoredValue(
    app: AppCode,
    preferences: MutableClass,
    key: String,
    unbox: String,
    boxed: String,
): Pair<MutableMethod, Int> {
    val reader = preferenceReader(app, preferences, key, unbox)
    val code = reader.implementation!!.instructions.toList()
    val casts = code.indices.filter {
        code[it].opcode == Opcode.CHECK_CAST && (code[it] as ReferenceInstruction).reference.toString() == boxed
    }
    val cast = casts.singleOrNull() ?: throw app.error("expected one read of the $key preference, found ${casts.size}")
    return reader to cast
}

/** The inner class method of [preferences] that reads [key]'s DataStore value and unboxes it with [unbox]. */
private fun BytecodePatchContext.preferenceReader(
    app: AppCode,
    preferences: MutableClass,
    key: String,
    unbox: String,
): MutableMethod {
    val constructor = preferences.methods.single { it.name == "<init>" && key in it.constStrings() }
    val constructorCode = constructor.implementation!!.instructions.toList()
    val keyIndex = constructorCode.indexOfFirst { it.constString() == key }
    val field = constructorCode.drop(keyIndex).first { it.opcode == Opcode.IPUT_OBJECT }
        .let { (it as ReferenceInstruction).reference as FieldReference }

    val accessor = preferences.methods.singleOrNull { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference == field
            } == true
    } ?: throw app.error("no accessor for the $key preference")

    val innerPrefix = preferences.type.removeSuffix(";") + "$"
    val readers = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (!classDef.type.startsWith(innerPrefix)) return@classDefForEach
        classDef.methods.filter { it.calls(accessor.reference()) && it.calls(unbox) }.forEach { readers += classDef to it }
    }
    val (readerClass, readerMethod) = readers.singleOrNull()
        ?: throw app.error("expected one reader for the $key preference, found ${readers.size}")
    return mutableTwin(readerClass, readerMethod)
}
