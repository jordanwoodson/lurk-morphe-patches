package io.github.jordanwoodson.patches.shared.compose

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STRING = "Ljava/lang/String;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/** The static methods this method calls with invoke-static/range, with their instruction indexes. */
internal fun Method.staticRangeCalls(): List<Pair<Int, MethodReference>> =
    implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
        index to ((instruction as ReferenceInstruction).reference as MethodReference)
    }

/**
 * Compose's Modifier.clickable in its default-argument form, (modifier, enabled, onClickLabel, role,
 * interactionSource, onClick, mask, marker), as this method calls it. [modifier] is the Modifier type.
 * With mask 0xf only onClick is given.
 */
internal fun Method.clickableCall(app: AppCode, modifier: String): String =
    staticRangeCalls().map { it.second }.filter { method ->
        val parameters = method.parameterTypes.map { it.toString() }
        method.returnType == modifier && parameters.size == 8 && parameters[0] == modifier &&
            parameters[1] == "Z" && parameters[2] == STRING && parameters[5] == FUNCTION0 &&
            parameters[6] == "I" && parameters[7] == "Ljava/lang/Object;"
    }.map { it.toString() }.distinct().singleOrNull()
        ?: throw app.error("expected one clickable modifier in $definingClass->$name")
