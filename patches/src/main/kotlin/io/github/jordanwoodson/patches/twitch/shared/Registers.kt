package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.requireLocals

/**
 * Parameters of a method, as registers that fit any instruction form, for code inserted at the method's
 * start. Put [prologue] first: it copies each parameter above v15 into a local.
 */
internal class LowParameters(val prologue: String, private val registers: Map<Int, Int>) {
    /** The register that holds parameter [index] once [prologue] has run. */
    operator fun get(index: Int): Int = registers[index] ?: throw PatchException("Lurk: parameter $index was not asked for")
}

/**
 * [LowParameters] for parameters [indexes] (0 is this in an instance method). At the start of a method no
 * local holds a value yet, so a parameter above v15 is copied into the locals from [firstLocal] up. Fails
 * when the method has too few locals for the copies, which can't happen while every parameter is low.
 */
internal fun MutableMethod.lowParameters(vararg indexes: Int, firstLocal: Int): LowParameters {
    val static = AccessFlags.STATIC.isSet(accessFlags)
    var next = firstLocal
    val copies = mutableListOf<String>()
    val registers = indexes.associateWith { index ->
        val register = parameterRegister(index)
        if (register <= 15) return@associateWith register
        val type = if (!static && index == 0) definingClass else parameterTypes[index - if (static) 0 else 1].toString()
        val wide = type == "J" || type == "D"
        val target = next
        next += if (wide) 2 else 1
        copies += when {
            wide -> "move-wide/from16"
            type.startsWith("L") || type.startsWith("[") -> "move-object/from16"
            else -> "move/from16"
        } + " v$target, v$register"
        target
    }
    if (next > firstLocal) {
        requireLocals(next)
        if (next - 1 > 15) throw PatchException("Lurk: $definingClass->$name has no low locals for its parameters")
    }
    return LowParameters(copies.joinToString("\n"), registers)
}
