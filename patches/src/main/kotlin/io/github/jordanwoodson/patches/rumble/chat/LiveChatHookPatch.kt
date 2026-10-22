package io.github.jordanwoodson.patches.rumble.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_CHAT
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.rumble.shared.single

/**
 * Passes every live chat message list through the extension before Rumble builds chat rows from it: the
 * live messages of each chat event and the history sent on joining. Hide chat clutter and Chat keywords
 * filter there; with none of their switches on, the extension returns the list unchanged.
 */
internal val liveChatHookPatch = bytecodePatch(
    description = "Lets the chat patches filter live chat messages.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        listOf(
            Triple(LiveChatMessagesFingerprint, "live chat messages getter", "messages_getter"),
            Triple(LiveChatHistoryFingerprint, "live chat history getter", "history_getter"),
        ).forEach { (fingerprint, what, locator) ->
            fingerprint.single(what).method.apply {
                Locators.record("rumble.chat.$locator", this)
                val code = implementation!!.instructions
                if (code.size != 2 || code[0].opcode != Opcode.IGET_OBJECT || code[1].opcode != Opcode.RETURN_OBJECT) {
                    throw PatchException("Rumble: unexpected $what")
                }
                val list = getInstruction<OneRegisterInstruction>(0).registerA
                val self = parameterRegister(0)
                addInstructions(
                    1,
                    """
                        invoke-static { v$list, v$self }, $RUMBLE_CHAT->messages(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                        move-result-object v$list
                    """,
                )
            }
        }
    }
}
