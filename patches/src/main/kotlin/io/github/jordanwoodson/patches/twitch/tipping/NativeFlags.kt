package io.github.jordanwoodson.patches.twitch.tipping

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.matchKey
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

internal const val INTERFACE_CLASS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchInterface;"

/** Identify only a UI-state flag, from its Kotlin diagnostic label, then filter its constructor write. */
internal fun BytecodePatchContext.filterBitsFlag(fingerprint: Fingerprint, marker: String) =
    filterUiFlag(fingerprint, marker, "$INTERFACE_CLASS->bitsVisible(Z)Z")

/**
 * Passes the boolean field that a state's toString prints after [marker] through [filter], a static
 * `(Z)Z` method, right before the constructor stores it. The state must have exactly one such store.
 */
internal fun BytecodePatchContext.filterUiFlag(fingerprint: Fingerprint, marker: String, filter: String) {
    val state = fingerprint.twitchMatch("UI state $marker").method
    val field = printedBooleanField(state, marker)
    if (field.definingClass != state.definingClass) throw PatchException("Twitch: UI flag belongs to another class")
    // twitch.ui_flag.<state>.<flag>, for example twitch.ui_flag.chat_toolbar_state.is_bits_button_visible.
    val flag = (Regex("(\\w+)=$").find(marker)?.groupValues?.get(1) ?: marker)
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()
    Locators.record(fingerprint.matchKey("UI state $marker").replace("twitch.match.", "twitch.ui_flag.") + ".$flag",
        field.toString())
    val constructors = mutableClassDefBy(state.definingClass).methods.filter { it.name == "<init>" }
    var writes = 0
    constructors.forEach { method ->
        val points = method.implementation!!.instructions.withIndex().filter {
            it.value.opcode == Opcode.IPUT_BOOLEAN &&
                (it.value as ReferenceInstruction).reference.toString() == field.toString()
        }
        points.reversed().forEach { (index, instruction) ->
            val register = (instruction as TwoRegisterInstruction).registerA
            // Transform the value immediately before storage. No extra register or parameter shift,
            // and no object/constructor return is skipped.
            if (register > 255) throw PatchException("Twitch: UI flag register exceeds move-result range")
            method.addInstructions(index, """
                invoke-static/range { v$register .. v$register }, $filter
                move-result v$register
            """)
            writes++
        }
    }
    if (writes != 1) throw PatchException("Twitch: expected one constructor write for $marker, found $writes")
}

/**
 * The boolean field a Kotlin data-class toString prints right after [marker]. R8 merges the
 * StringBuilder calls into helpers such as `(String, Z, String, Z, String)`, and may load a label
 * before the fields of the same call, so the value is taken from the argument that follows the label
 * (or, for a trailing label, the first boolean argument of the next call on the same builder).
 */
internal fun printedBooleanField(state: Method, marker: String): FieldReference {
    val instructions = state.implementation!!.instructions.toList()
    val label = instructions.indices.singleOrNull { index ->
        ((instructions[index] as? ReferenceInstruction)?.reference as? StringReference)?.string == marker
    } ?: throw PatchException("Twitch: no unique $marker label")
    val labelRegister = (instructions[label] as OneRegisterInstruction).registerA
    val call = (label + 1 until instructions.size).firstOrNull { labelRegister in arguments(instructions[it]) }
        ?: throw PatchException("Twitch: $marker is never printed")
    val (callArguments, types) = typedArguments(instructions[call])
    val position = callArguments.indexOf(labelRegister)
    val (valueCall, valueRegister) = if (position + 1 < types.size) {
        if (types[position + 1] != "Z") throw PatchException("Twitch: $marker is not followed by a boolean")
        call to callArguments[position + 1]
    } else {
        // A trailing label: the builder is the receiver or first argument, or the call's result.
        val next = instructions.getOrNull(call + 1)
        val builder = if (next?.opcode == Opcode.MOVE_RESULT_OBJECT) (next as OneRegisterInstruction).registerA
            else arguments(instructions[call]).first()
        val following = (call + 1 until instructions.size).firstOrNull { arguments(instructions[it]).firstOrNull() == builder }
            ?: throw PatchException("Twitch: nothing printed after $marker")
        val (followingArguments, followingTypes) = typedArguments(instructions[following])
        val first = followingTypes.indexOfFirst { it == "Z" }
        if (first < 0) throw PatchException("Twitch: $marker is not followed by a boolean")
        following to followingArguments[first]
    }
    val read = (valueCall - 1 downTo label + 1).firstOrNull { index ->
        val instruction = instructions[index]
        instruction !is FiveRegisterInstruction && instruction !is RegisterRangeInstruction &&
            ((instruction as? OneRegisterInstruction)?.registerA == valueRegister)
    }?.let { instructions[it] }
    if (read == null) {
        // Plain StringBuilder chains load the field after the label (`append(label)` then `append(field)`).
        return instructions.drop(label + 1).firstOrNull { it.opcode == Opcode.IGET_BOOLEAN }
            ?.let { (it as ReferenceInstruction).reference as FieldReference }
            ?: throw PatchException("Twitch: no boolean field after $marker")
    }
    if (read.opcode != Opcode.IGET_BOOLEAN) throw PatchException("Twitch: value printed after $marker is not a field")
    return (read as ReferenceInstruction).reference as FieldReference
}

private fun arguments(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    else -> emptyList()
}

/** Argument registers paired with parameter types, without the receiver. Only single-width types occur here. */
private fun typedArguments(instruction: Instruction): Pair<List<Int>, List<String>> {
    val reference = (instruction as ReferenceInstruction).reference as? MethodReference
        ?: throw PatchException("Twitch: unexpected toString instruction")
    val registers = arguments(instruction)
    val static = instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE
    val types = reference.parameterTypes.map { it.toString() }
    if (types.any { it == "J" || it == "D" }) throw PatchException("Twitch: unexpected wide toString argument")
    val values = if (static) registers else registers.drop(1)
    if (values.size != types.size) throw PatchException("Twitch: unexpected toString call shape")
    return values to types
}
