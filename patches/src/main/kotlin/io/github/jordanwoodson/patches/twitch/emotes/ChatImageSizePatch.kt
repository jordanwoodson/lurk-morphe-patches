package io.github.jordanwoodson.patches.twitch.emotes

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.freeRegister
import io.github.jordanwoodson.patches.twitch.shared.requireFreeRegister
import io.github.jordanwoodson.patches.twitch.shared.requireKept
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

/**
 * Chat images: Twitch fits each emote and badge image in a square, so wide third-party emotes and pronoun
 * badges would shrink. Before the image loads, an image Lurk knows to be wide gets its width and is sized by
 * height instead. Everything else keeps Twitch's sizing.
 */
internal val chatImageSizePatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val match = ChatImageLoaderFingerprint.twitchMatch("chat image loader")
        match.method.apply {
            val instructions = implementation!!.instructions
            val call = match.instructionMatches.first().index
            val result = instructions[call + 1]
            if (result.opcode != Opcode.MOVE_RESULT) throw PatchException("Twitch: height constraint not kept")
            val constrained = (result as OneRegisterInstruction).registerA
            val store = call + 2
            if (instructions[store].opcode != Opcode.IPUT_BOOLEAN ||
                (instructions[store] as TwoRegisterInstruction).registerA != constrained) {
                throw PatchException("Twitch: height constraint isn't stored right away")
            }
            // The placeholder drawable: the register the loop casts to the placeholder class, whose String
            // field (the image URL) it later reads.
            val urlRead = (store until instructions.size).firstOrNull { i ->
                instructions[i].opcode == Opcode.IGET_OBJECT &&
                    ((instructions[i] as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/String;"
            } ?: throw PatchException("Twitch: chat image loader doesn't read a URL")
            val urlField = (instructions[urlRead] as ReferenceInstruction).reference as FieldReference
            val placeholder = (instructions[urlRead] as TwoRegisterInstruction).registerB
            val cast = (call downTo 0).firstOrNull { i ->
                instructions[i].opcode == Opcode.CHECK_CAST &&
                    ((instructions[i] as ReferenceInstruction).reference as TypeReference).type == urlField.definingClass
            } ?: throw PatchException("Twitch: chat image loader doesn't cast its placeholder")
            if ((instructions[cast] as OneRegisterInstruction).registerA != placeholder) {
                throw PatchException("Twitch: placeholder register changed")
            }
            requireKept(cast, store, placeholder, "chat image placeholder")
            requireNotBranchTarget(store, "height constraint store")
            val scratch = freeRegister(store, listOf(constrained, placeholder), "chat image loader")
            if (constrained > 15 || placeholder > 15) throw PatchException("Twitch: chat image registers out of range")
            addInstructions(store, """
                iget-object v$scratch, v$placeholder, $urlField
                invoke-static { v$constrained, v$placeholder, v$scratch }, Lio/github/jordanwoodson/extension/shared/twitch/TwitchEmotes;->sizeEmote(ZLandroid/graphics/drawable/Drawable;Ljava/lang/String;)Z
                move-result v$constrained
            """)
        }
    }
}
