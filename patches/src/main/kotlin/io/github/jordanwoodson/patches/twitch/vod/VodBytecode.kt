package io.github.jordanwoodson.patches.twitch.vod

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode

/** Smali descriptor of the extension's VOD class. */
internal const val VOD = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchVod;"

/**
 * Gives an empty extension stub of [VOD] a body that calls into obfuscated app code, so the extension
 * never names R8 types. Mirrors the player extension's bridge helper.
 */
context(context: BytecodePatchContext)
internal fun vodBridge(name: String, smali: String) {
    val stub = context.mutableClassDefBy(VOD).methods.singleOrNull { it.name == name }
        ?: throw PatchException("Lurk: no $name stub")
    val code = stub.implementation!!.instructions
    if (code.size != 1 || code[0].opcode != Opcode.RETURN_VOID) throw PatchException("Lurk: $name stub is not empty")
    stub.addInstructions(0, smali)
}
