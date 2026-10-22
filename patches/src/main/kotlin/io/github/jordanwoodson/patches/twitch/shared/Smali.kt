package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.smali.toInstructions
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import io.github.jordanwoodson.patches.shared.settings.isEnabled
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions as morpheAddInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels as morpheAddInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction as morpheReplaceInstruction
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled as sharedAddInstructionsIfEnabled

/*
 * Checked versions of Morphe's smali insertions. The Twitch patches import these instead of Morphe's.
 *
 * Morphe's inline smali compiler drops any line it rejects and only prints the reason to stderr: the
 * patch applies, and the line is missing from the patched dex. The usual cause is a register above v15
 * in a four-bit form (`invoke-* { ... }`, `iget`/`iput`, `instance-of`, `move-object`, `const/4`, `if-eq`),
 * which a parameter register becomes once the app's method has enough locals. Each function here
 * compiles the smali, counts the instructions that came out, and fails the patch unless every
 * instruction line made it.
 */

/** The lines of [smali] that compile to an instruction: not blank, a comment, a label or a directive. */
private fun instructionLines(smali: String) = smali.lineSequence()
    .map { it.trim() }
    .count { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(":") && !it.startsWith(".") }

/** Compiles [smali] for this method; fails unless every instruction line compiled. */
internal fun MutableMethod.compileChecked(smali: String): List<BuilderInstruction> {
    val compiled = smali.toInstructions(this)
    val expected = instructionLines(smali)
    if (compiled.size != expected) {
        throw PatchException(
            "Lurk: ${expected - compiled.size} of $expected injected instructions in $definingClass->$name did not " +
                "compile, most likely a register above v15 in a four-bit form (use /range, /from16 or " +
                "invokeStatic()):\n${smali.trimIndent()}",
        )
    }
    return compiled
}

internal fun MutableMethod.addInstructions(index: Int, smali: String) =
    morpheAddInstructions(index, compileChecked(smali))

internal fun MutableMethod.addInstructions(index: Int, instructions: List<BuilderInstruction>) =
    morpheAddInstructions(index, instructions)

internal fun MutableMethod.addInstruction(index: Int, smali: String) =
    morpheAddInstructions(index, compileChecked(smali).also {
        if (it.size != 1) throw PatchException("Lurk: addInstruction takes one instruction")
    })

internal fun MutableMethod.replaceInstruction(index: Int, smali: String) {
    val compiled = compileChecked(smali)
    if (compiled.size != 1) throw PatchException("Lurk: replaceInstruction takes one instruction")
    morpheReplaceInstruction(index, compiled.single())
}

internal fun MutableMethod.replaceInstruction(index: Int, instruction: BuilderInstruction) =
    morpheReplaceInstruction(index, instruction)

/**
 * Morphe's [morpheAddInstructionsWithLabels], after checking [smali] the way Morphe compiles it: with a
 * `nop` standing in for each label the smali jumps to but doesn't define.
 */
internal fun MutableMethod.addInstructionsWithLabels(index: Int, smali: String, vararg labels: ExternalLabel) {
    compileChecked(smali + externalLabels(smali).joinToString("") { "\n:$it\nnop" })
    morpheAddInstructionsWithLabels(index, smali, *labels)
}

/** Labels [smali] refers to without defining them. */
private fun externalLabels(smali: String): Set<String> {
    val lines = smali.lineSequence().map { it.substringBefore('#').trim() }.toList()
    val defined = lines.filter { it.startsWith(":") }.map { it.removePrefix(":") }.toSet()
    val used = lines.filterNot { it.startsWith(":") }.flatMap { line ->
        // A label stands after a space, comma or brace; `->field:Type` doesn't.
        Regex("(?:^|[\\s,{]):([A-Za-z_][A-Za-z0-9_]*)").findAll(line.substringBefore('"')).map { it.groupValues[1] }.toList()
    }
    return used.filterNot { it in defined }.toSet()
}

/** The shared settings helper, after checking the smali it inserts. */
internal fun MutableMethod.addInstructionsIfEnabled(key: String, smali: String, locals: Int = 1) {
    compileChecked(isEnabled(key, "v0") + "if-eqz v0, :lurk_off\n" + smali + "\n:lurk_off\nnop")
    sharedAddInstructionsIfEnabled(key, smali, locals)
}
