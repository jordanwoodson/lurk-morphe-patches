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
    val reader = mutableTwin(readerClass, readerMethod)

    val code = reader.implementation!!.instructions.toList()
    val unboxIndex = code.indexOfFirst { it is ReferenceInstruction && it.reference.toString() == unbox }
    val nullCheck = (unboxIndex downTo 0).first { code[it].opcode == Opcode.IF_EQZ }
    return reader to (code[nullCheck] as BuilderOffsetInstruction).target.location.index
}
