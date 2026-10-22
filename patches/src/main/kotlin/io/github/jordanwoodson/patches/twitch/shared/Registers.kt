package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.BuilderSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.parameterRegisters
import io.github.jordanwoodson.patches.shared.settings.requireLocals

/*
 * Register bookkeeping for code inserted into Twitch's methods: which registers a hook may write without
 * changing what the original code computes, and how to reach parameters from instruction forms that only
 * take v0..v15.
 */

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

/** Fails if a branch, switch or try block jumps to [index]: code inserted there would be skipped. */
internal fun MutableMethod.requireNotBranchTarget(index: Int, what: String) {
    if (implementation!!.instructions[index].location.labels.isNotEmpty()) {
        throw PatchException("Twitch: $what is a branch target")
    }
}

/**
 * Whether [register] is dead at [index]: on every path from there (branches, switch cases and the
 * exception handlers of instructions that can throw), the first instruction that uses it only writes it,
 * or the path returns or throws without reading it. Wide uses and read-modify-write forms count as reads.
 */
internal fun MutableMethod.isFreeRegister(index: Int, register: Int): Boolean =
    everyPath(index) { instruction ->
        val used = instruction.registers()
        when {
            register !in used && !(instruction.isWide() && register - 1 in used) -> null
            else -> instruction.writesOnly(register, used)
        }
    }

/**
 * Whether no path from the method's start can [dereference] parameter [register] while it still holds the
 * argument: every path writes it first, or returns or throws without.
 */
internal fun MutableMethod.neverDereferenced(register: Int, dereference: (Instruction) -> Boolean): Boolean =
    everyPath(0) { instruction ->
        when {
            dereference(instruction) -> false
            instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA.let {
                it == register || instruction.isWide() && it == register - 1
            } -> true
            else -> null
        }
    }

/**
 * Walks every path from [index] (branches, switch cases and the exception handlers of instructions that
 * can throw) until [decide] settles it: true ends the path as fine, false fails the walk, null goes on.
 * A path that returns or throws ends fine; one that runs off the code fails.
 */
private fun MutableMethod.everyPath(index: Int, decide: (Instruction) -> Boolean?): Boolean {
    val instructions = implementation!!.instructions
    val visited = HashSet<Int>()
    val pending = ArrayDeque(listOf(index))
    while (pending.isNotEmpty()) {
        var i = pending.removeLast()
        path@ while (visited.add(i)) {
            val instruction = instructions.getOrNull(i) ?: return false
            when (decide(instruction)) {
                true -> break@path
                false -> return false
                null -> Unit
            }
            if (instruction.opcode.canThrow()) pending += handlers(i)
            val opcode = instruction.opcode
            when {
                opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH -> {
                    val payload = (instruction as BuilderOffsetInstruction).target.location.instruction as? BuilderSwitchPayload
                        ?: return false
                    payload.switchElements.forEach { pending += it.target.location.index }
                    i++
                }
                instruction is BuilderOffsetInstruction -> {
                    pending += instruction.target.location.index
                    if (opcode == Opcode.GOTO || opcode == Opcode.GOTO_16 || opcode == Opcode.GOTO_32) break@path
                    i++
                }
                !opcode.canContinue() -> break@path
                else -> i++
            }
        }
    }
    return true
}

/** Whether [this] writes [register] without reading it: a single-register write, not wide or in place. */
private fun Instruction.writesOnly(register: Int, used: List<Int>) =
    opcode.setsRegister() && !isWide() && "2ADDR" !in opcode.name && opcode != Opcode.CHECK_CAST &&
        (this as OneRegisterInstruction).registerA == register && used.count { it == register } == 1

/** The first instructions of the exception handlers covering the instruction at [index]. */
private fun MutableMethod.handlers(index: Int): List<Int> {
    val address = implementation!!.instructions[index].location.codeAddress
    return implementation!!.tryBlocks
        .filter { address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }
        .flatMap { block -> block.exceptionHandlers.map { it.handler.location.index } }
}

internal fun MutableMethod.requireFreeRegister(index: Int, register: Int, what: String) {
    if (!isFreeRegister(index, register)) throw PatchException("Twitch: can't prove $what register v$register is free")
}

/** The number of local registers: the first parameter register, or every register without parameters. */
internal fun MutableMethod.localCount(): Int = parameterRegisters().firstOrNull() ?: implementation!!.registerCount

/** A local register below v16 that is dead at [index] and not in [exclude]. */
internal fun MutableMethod.freeRegister(index: Int, exclude: Collection<Int>, what: String): Int =
    (0 until minOf(localCount(), 16)).firstOrNull { it !in exclude && isFreeRegister(index, it) }
        ?: throw PatchException("Twitch: no free register for $what")

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
