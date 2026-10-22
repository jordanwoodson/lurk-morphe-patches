package io.github.jordanwoodson.patches.shared.compose

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.shared.settings.isEnabled

/**
 * Makes a Compose list loop skip the items whose route is [route] while the switch [key] is on. The loop is
 * Kotlin's `for ((index, item) in list.withIndex())` shape that Compose emits for a row of tabs: an iterator,
 * a running index incremented with add-int/lit8, and the item cast to its type, which has a getter returning
 * [routeType] (the route's superclass). Other code often refers to items by position, so the item stays in the
 * list and keeps its index; the loop only skips drawing it.
 */
internal fun BytecodePatchContext.skipLoopItems(app: AppCode, loop: MutableMethod, route: String, routeType: String, key: String) {
    loop.apply {
        val instructions = implementation!!.instructions.toList()
        fun calls(index: Int, method: String) = instructions[index].let {
            it is ReferenceInstruction && it.reference.toString() == method
        }
        val next = instructions.indices.single { calls(it, "Ljava/util/Iterator;->next()Ljava/lang/Object;") }
        val head = (next downTo 0).first { calls(it, "Ljava/util/Iterator;->hasNext()Z") }
        // next() goes to a register that is dead once copied into the item variable.
        val scratch = (instructions[next + 1] as OneRegisterInstruction).registerA
        val increment = (next until instructions.size).first { instructions[it].opcode == Opcode.ADD_INT_LIT8 }
        val (nextIndex, index) = (instructions[increment] as TwoRegisterInstruction).let { it.registerA to it.registerB }
        val itemCast = (next until instructions.size).first { instructions[it].opcode == Opcode.CHECK_CAST }
        val item = (instructions[itemCast] as OneRegisterInstruction).registerA
        val itemType = ((instructions[itemCast] as ReferenceInstruction).reference as TypeReference).type
        val routeGetter = classDefBy(itemType).methods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType == routeType
        } ?: throw app.error("no route getter in the loop's item $itemType")
        requireLowRegisters("${app.name}: hiding a loop item", scratch, item)

        addInstructionsWithLabels(
            itemCast + 1,
            """
                ${isEnabled(key, "v$scratch")}
                if-eqz v$scratch, :keep
                invoke-virtual { v$item }, $itemType->${routeGetter.name}()$routeType
                move-result-object v$scratch
                instance-of v$scratch, v$scratch, $route
                if-eqz v$scratch, :keep
                move/from16 v$index, v$nextIndex
                goto/16 :loop
            """,
            ExternalLabel("keep", getInstruction(itemCast + 1)),
            ExternalLabel("loop", getInstruction(head)),
        )
    }
}
