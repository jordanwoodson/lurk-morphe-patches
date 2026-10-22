package io.github.jordanwoodson.patches.shared.compose

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Format
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import io.github.jordanwoodson.patches.shared.parameterRegister

private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** Fails if an instruction from [from] up to (not including) [to] writes [register]. */
internal fun MutableMethod.requireUnwritten(app: AppCode, register: Int, from: Int, to: Int, what: String) {
    implementation!!.instructions.subList(from, to).forEach { instruction ->
        if (instruction.writes(register)) throw app.error("$what is overwritten")
    }
}

/**
 * The register the instruction at [index] writes without reading it. Nothing else can be using it just before
 * that instruction, so code inserted there may use it.
 */
internal fun MutableMethod.overwrittenNext(app: AppCode, index: Int): Int {
    val instruction = implementation!!.instructions[index]
    val written = (instruction as? OneRegisterInstruction)?.registerA
    if (written == null || !instruction.opcode.setsRegister() || instruction.reads(written)) {
        throw app.error("${instruction.opcode} in $definingClass->$name doesn't free a register")
    }
    return written
}

/**
 * Fails if a branch jumps to [index]. Code inserted at [index] runs before the instruction there, but a branch
 * to that instruction would skip it.
 */
internal fun MutableMethod.requireNoBranchTo(app: AppCode, index: Int, what: String) {
    if (implementation!!.instructions[index].location.labels.isNotEmpty()) throw app.error("$what is a branch target")
}

/**
 * The type of the constructor parameter Kotlin checks against [name] ("saveQueryUseCase"), found through the
 * register its checkNotNullParameter call passes.
 */
internal fun MutableMethod.constructorParameterType(app: AppCode, name: String): String {
    val code = implementation!!.instructions.toList()
    val nameIndex = code.indexOfFirst { it.constString() == name }
    if (nameIndex < 0) throw app.error("$definingClass doesn't check $name")
    val nameRegister = (code[nameIndex] as OneRegisterInstruction).registerA
    val check = code.drop(nameIndex + 1).firstOrNull {
        it is FiveRegisterInstruction && it.registerCount == 2 && it.registerD == nameRegister &&
            (it as ReferenceInstruction).reference.toString()
                .endsWith("->checkNotNullParameter(Ljava/lang/Object;Ljava/lang/String;)V")
    } as? FiveRegisterInstruction ?: throw app.error("no null check of $name in $definingClass")
    // The checked register, or the parameter it was copied from.
    var register = check.registerC
    val checkIndex = code.indexOf(check as Instruction)
    val write = (checkIndex - 1 downTo 0).firstOrNull { code[it].writes(register) }
    if (write != null) {
        val copy = code[write]
        if (copy.opcode !in MOVES) throw app.error("$name in $definingClass isn't a parameter")
        register = (copy as TwoRegisterInstruction).registerB
    }
    val index = (1..parameterTypes.size).firstOrNull { parameterRegister(it) == register }
        ?: throw app.error("$name in $definingClass isn't a parameter")
    // Index 0 is `this`.
    return parameterTypes[index - 1].toString()
}

/**
 * Follows [register], as it is just before the instruction at [index], back through object moves to the
 * instruction that produced it, and returns that instruction's index and the register it wrote.
 */
internal fun MutableMethod.sourceOf(register: Int, index: Int): Pair<Int, Int> {
    val code = implementation!!.instructions
    var current = register
    var at = index
    while (true) {
        val wanted = current
        at = (at - 1 downTo 0).first { code[it].opcode.setsRegister() && (code[it] as? OneRegisterInstruction)?.registerA == wanted }
        if (code[at].opcode in MOVES) current = (code[at] as TwoRegisterInstruction).registerB else return at to current
    }
}

internal fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/** Whether the instruction reads [register], for the instructions [overwrittenNext] accepts. */
private fun Instruction.reads(register: Int): Boolean = when {
    // check-cast and the 2addr arithmetic read and write vA.
    opcode == Opcode.CHECK_CAST || opcode.name.endsWith("_2ADDR") -> true
    opcode.format == Format.Format35c || opcode.format == Format.Format3rc -> true
    this is ThreeRegisterInstruction -> registerB == register || registerC == register ||
        (opcode.setsWideRegister() && (registerB + 1 == register || registerC + 1 == register))
    this is TwoRegisterInstruction -> registerB == register || (opcode.setsWideRegister() && registerB + 1 == register)
    else -> false
}
