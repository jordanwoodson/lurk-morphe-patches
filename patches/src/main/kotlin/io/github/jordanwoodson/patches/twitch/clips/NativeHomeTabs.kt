package io.github.jordanwoodson.patches.twitch.clips

import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.twitch.tipping.INTERFACE_CLASS

private const val PAGE = "Ltv/twitch/android/models/feed/DiscoveryFeedPage;"
private const val FILTER = "$INTERFACE_CLASS->homeTabs(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"

/**
 * Native Home (the default, and the signed-out Home) takes its top tabs from one provider interface:
 * [Following, Live, Clips] signed in, [Live, Clips] signed out. Its three callers (the pager adapter,
 * the Home presenter, and the presenter's Turbo-tab rebuild) all get the same filtered list, so tab
 * labels, pages and indexes agree. A Home opened on Clips (a shared clip link) keeps its Clips tab.
 */
internal fun BytecodePatchContext.hideNativeHomeClipsTab() {
    val matches = HomeTabsFingerprint.matchAllOrNull().orEmpty()
    val providers = matches.map { it.classDef }
    val interfaces = providers.flatMap { it.interfaces }.groupingBy { it }.eachCount().filterValues { it == providers.size }.keys
    val provider = interfaces.singleOrNull()
        ?: throw PatchException("Twitch: Home tab providers (${providers.size}) share no single interface")
    if (providers.size != 2) throw PatchException("Twitch: expected signed-in and signed-out Home tab lists")
    // Both lists implement the interface's one List(boolean) method, so the matched methods carry its name.
    val name = matches.map { it.method.name }.distinct().singleOrNull()
        ?: throw PatchException("Twitch: the Home tab lists implement different methods")
    classDefBy(provider).methods.singleOrNull { method ->
        method.name == name && method.returnType == "Ljava/util/List;" && method.parameterTypes.map { it.toString() } == listOf("Z")
    } ?: throw PatchException("Twitch: the Home tab interface does not declare the matched List(boolean) method")
    val call = "$provider->$name(Z)Ljava/util/List;"
    Locators.record("twitch.hide_clips.home_tabs", call)
    val sites = mutableListOf<Pair<String, MethodReference>>()
    classDefForEach { type ->
        type.methods.forEach { method ->
            if (method.implementation?.instructions?.any { it.opcode == Opcode.INVOKE_INTERFACE &&
                    (it as ReferenceInstruction).reference.toString() == call } == true) {
                sites += type.type to method
            }
        }
    }
    if (sites.size != 3) throw PatchException("Twitch: expected three Home tab list users, found ${sites.size}")
    sites.forEach { (type, reference) ->
        val method = mutableClassDefBy(type).methods.single { it.name == reference.name &&
            it.parameterTypes.map { p -> p.toString() } == reference.parameterTypes.map { p -> p.toString() } }
        filterHomeTabs(method, call)
    }
}

private fun filterHomeTabs(method: MutableMethod, call: String) {
    val instructions = method.implementation!!.instructions.toList()
    val index = instructions.indices.single { instructions[it].opcode == Opcode.INVOKE_INTERFACE &&
        (instructions[it] as ReferenceInstruction).reference.toString() == call }
    val result = (instructions.getOrNull(index + 1) as? OneRegisterInstruction)
        ?.takeIf { instructions[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA
        ?: throw PatchException("Twitch: Home tab list result is not kept")
    val store = instructions[index + 2]
    if (store.opcode != Opcode.IPUT_OBJECT || (store as TwoRegisterInstruction).registerA != result ||
        ((store as ReferenceInstruction).reference as FieldReference).type != "Ljava/util/List;") {
        throw PatchException("Twitch: Home tab list is not stored in a field")
    }
    val parameters = method.parameterTypes.map { it.toString() }
    val static = AccessFlags.STATIC.isSet(method.accessFlags)
    val parameterRegisters = parameters.sumOf { if (it == "J" || it == "D") 2 else 1 } + (if (static) 0 else 1)
    val firstParameter = method.implementation!!.registerCount - parameterRegisters
    fun parameterRegister(position: Int) = firstParameter + (if (static) 0 else 1) +
        parameters.take(position).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val page = parameters.indexOf(PAGE)
    val fragment = instructions.firstOrNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "requireArguments"
    }?.let { (it as FiveRegisterInstruction).registerC }
    val smali = when {
        // The Home presenter: the page Home was opened on is a parameter, usually copied to a local first.
        page >= 0 -> {
            val parameter = parameterRegister(page)
            val copy = (0 until index).lastOrNull { at -> instructions[at].opcode == Opcode.MOVE_OBJECT_FROM16 &&
                (instructions[at] as TwoRegisterInstruction).registerB == parameter }
            val context = when {
                copy != null && !writtenBefore(instructions, index, (instructions[copy] as OneRegisterInstruction).registerA, copy) ->
                    (instructions[copy] as OneRegisterInstruction).registerA
                !writtenBefore(instructions, index, parameter, -1) -> parameter
                else -> throw PatchException("Twitch: Home page parameter is overwritten")
            }
            "invoke-static { v$result, v$context }, $FILTER"
        }
        // The pager adapter: its fragment, whose arguments hold the same page.
        method.name == "<init>" && fragment != null && fragment >= firstParameter -> {
            if (writtenBefore(instructions, index, fragment, -1)) throw PatchException("Twitch: Home fragment is overwritten")
            "invoke-static { v$result, v$fragment }, $FILTER"
        }
        // The Turbo rebuild: keep Clips only if the presenter's current list has it. Its boolean
        // argument register is dead after the call and holds the current list.
        else -> {
            val scratch = (instructions[index] as FiveRegisterInstruction).registerD
            if (!deadAfter(instructions, index + 3, scratch)) throw PatchException("Twitch: no free register for the Home tab rebuild " +
                "in ${method.definingClass}->${method.name}: v$scratch " + instructions.drop(index).take(6).joinToString { it.opcode.name })
            val owner = store.registerB
            """
                iget-object v$scratch, v$owner, ${(store as ReferenceInstruction).reference}
                invoke-static { v$result, v$scratch }, $FILTER
            """
        }
    }
    if (Regex("v(\\d+)").findAll(smali).any { it.groupValues[1].toInt() > 15 }) {
        throw PatchException("Twitch: Home tab filter registers exceed the four-bit call form")
    }
    method.addInstructions(index + 2, "$smali\nmove-result-object v$result")
}

private fun writes(instruction: Instruction, register: Int): Boolean {
    if (instruction is FiveRegisterInstruction || instruction is RegisterRangeInstruction) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return (instruction.opcode.setsRegister() && target == register) ||
        (instruction.opcode.setsWideRegister() && target + 1 == register)
}

/** Whether [register] is written between [from] (exclusive) and [to] (exclusive). */
private fun writtenBefore(instructions: List<Instruction>, to: Int, register: Int, from: Int) =
    (from + 1 until to).any { writes(instructions[it], register) }

private fun reads(instruction: Instruction, register: Int): Boolean = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount).contains(register)
    is RegisterRangeInstruction -> register in instruction.startRegister until instruction.startRegister + instruction.registerCount
    is TwoRegisterInstruction -> instruction.registerB == register ||
        (!instruction.opcode.setsRegister() && instruction.registerA == register)
    is OneRegisterInstruction -> !instruction.opcode.setsRegister() && instruction.registerA == register
    else -> false
}

/** Straight-line liveness: [register] is overwritten or the method returns before anything reads it. */
private fun deadAfter(instructions: List<Instruction>, start: Int, register: Int): Boolean {
    val offsets = mutableListOf<Int>()
    var position = 0
    instructions.forEach { offsets += position; position += it.codeUnits }
    var index = start
    repeat(200) {
        val instruction = instructions.getOrNull(index) ?: return false
        if (reads(instruction, register)) return false
        if (writes(instruction, register)) return true
        when {
            instruction.opcode.name.startsWith("return") -> return true
            instruction.opcode.name.startsWith("goto") ->
                index = offsets.indexOf(offsets[index] + (instruction as OffsetInstruction).codeOffset)
            instruction is OffsetInstruction || instruction.opcode.name.startsWith("throw") -> return false
            else -> index++
        }
    }
    return false
}
