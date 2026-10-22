package io.github.jordanwoodson.patches.twitch.player

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

/** Smali descriptor of the extension's player class. */
internal const val PLAYER = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayer;"

/** The base class of Twitch's native player presenters (live, VOD, clips, vertical). */
context(context: BytecodePatchContext)
internal fun playerPresenterClass(): ClassDef =
    PlayerPresenterFingerprint.twitchMatch("player presenter base class").originalClassDef

/**
 * Fails if a branch jumps to [index]. Code inserted at [index] runs before the instruction there, but a
 * branch to that instruction would skip it.
 */
internal fun MutableMethod.requireNoBranchTo(index: Int, what: String) {
    if (implementation!!.instructions[index].location.labels.isNotEmpty()) {
        throw PatchException("Twitch: $what is a branch target")
    }
}

/** Fails if an instruction before [end] writes [register], so it still holds the parameter there. */
internal fun MutableMethod.requireUnwritten(register: Int, end: Int, what: String) {
    implementation!!.instructions.take(end).forEach { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA
        val wide = instruction.opcode.setsWideRegister()
        if (instruction.opcode.setsRegister() && written != null &&
            (written == register || (wide && written + 1 == register))) {
            throw PatchException("Twitch: $what is overwritten")
        }
    }
}

/**
 * Gives an empty extension stub of [PLAYER] a body that calls into obfuscated app code, so the
 * extension never names R8 types.
 */
context(context: BytecodePatchContext)
internal fun bridge(name: String, smali: String) {
    val stub = context.mutableClassDefBy(PLAYER).methods.singleOrNull { it.name == name }
        ?: throw PatchException("Lurk: no $name stub")
    val code = stub.implementation!!.instructions
    if (code.size != 1 || code[0].opcode != Opcode.RETURN_VOID) throw PatchException("Lurk: $name stub is not empty")
    stub.addInstructions(0, smali)
}
