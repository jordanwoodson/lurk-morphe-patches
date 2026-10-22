package io.github.jordanwoodson.patches.twitch.chat

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val CHAT = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchChat;"

internal val Instruction.methodRef get() = (this as? ReferenceInstruction)?.reference as? MethodReference
internal val Instruction.fieldRef get() = (this as? ReferenceInstruction)?.reference as? FieldReference
internal val Instruction.typeRef get() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
internal val Instruction.stringRef get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

internal fun MutableMethod.instructionList(): List<BuilderInstruction> = implementation!!.instructions.toList()

/** The instruction a branch at [index] jumps to. */
internal fun MutableMethod.branchTarget(index: Int): BuilderInstruction {
    val branch = implementation!!.instructions[index] as? BuilderOffsetInstruction
        ?: throw PatchException("Twitch: no branch at $name:$index")
    return implementation!!.instructions[branch.target.location.index]
}

internal fun Instruction.isConstant() = opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
