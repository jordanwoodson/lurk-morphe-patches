package io.github.jordanwoodson.patches.twitch.emotes

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import io.github.jordanwoodson.patches.shared.parameterRegisters
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/** Fails if a branch, switch or try block jumps to [index]: code inserted there would be skipped. */
internal fun MutableMethod.requireNotBranchTarget(index: Int, what: String) {
    if (implementation!!.instructions[index].location.labels.isNotEmpty()) {
        throw PatchException("Twitch: $what is a branch target")
    }
}

/**
 * Whether [register] is dead at [index]: following straight-line code from there, the first instruction
 * that uses it only writes it. Reaching a branch, return or throw first counts as unproven.
 */
internal fun MutableMethod.isFreeRegister(index: Int, register: Int): Boolean {
    val instructions = implementation!!.instructions
    for (i in index until instructions.size) {
        val instruction = instructions[i]
        val used = instruction.registers()
        if (register in used || instruction.isWide() && register - 1 in used) {
            return instruction.opcode.setsRegister() && !instruction.isWide() &&
                "2ADDR" !in instruction.opcode.name &&
                (instruction as OneRegisterInstruction).registerA == register &&
                used.count { it == register } == 1
        }
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) return false
    }
    return false
}

internal fun MutableMethod.requireFreeRegister(index: Int, register: Int, what: String) {
    if (!isFreeRegister(index, register)) throw PatchException("Twitch: can't prove $what register v$register is free")
}

/** A local register below v16 that is dead at [index] and not in [exclude]. */
internal fun MutableMethod.freeRegister(index: Int, exclude: Collection<Int>, what: String): Int {
    // The first parameter register (or this) is the number of locals.
    val locals = parameterRegisters().firstOrNull() ?: implementation!!.registerCount
    return (0 until minOf(locals, 16)).firstOrNull { it !in exclude && isFreeRegister(index, it) }
        ?: throw PatchException("Twitch: no free register for $what")
}

/**
 * Fails unless [register], set at [from], still holds that value at [to]: nothing in between writes it,
 * and nothing outside that range jumps into it.
 */
internal fun MutableMethod.requireKept(from: Int, to: Int, register: Int, what: String) {
    val instructions = implementation!!.instructions
    for (i in from + 1 until to) {
        val instruction = instructions[i]
        val written = instruction.opcode.setsRegister() && (instruction as OneRegisterInstruction).registerA.let {
            it == register || instruction.isWide() && it + 1 == register
        }
        if (written) throw PatchException("Twitch: $what register v$register is overwritten")
    }
    instructions.forEachIndexed { i, instruction ->
        if (i in from..to) return@forEachIndexed
        val target = (instruction as? BuilderOffsetInstruction)?.target?.location?.index ?: return@forEachIndexed
        if (target in from + 1..to) throw PatchException("Twitch: $what is reachable without v$register")
    }
    implementation!!.tryBlocks.forEach { block ->
        block.exceptionHandlers.forEach { handler ->
            if (handler.handler.location.index in from + 1..to) {
                throw PatchException("Twitch: $what is reachable from an exception handler")
            }
        }
    }
}

private fun Instruction.isWide() = opcode.name.let { "WIDE" in it || "LONG" in it || "DOUBLE" in it }

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}
