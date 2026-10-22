package io.github.jordanwoodson.patches.twitch.chat

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.twitch.shared.requireKept
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val STYLE = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchChatStyle;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val KEY = "chat_appearance"

@Suppress("unused")
val chatAppearancePatch = bytecodePatch(
    name = "Chat appearance",
    description = "Adds chat look settings: text size, alternating row backgrounds, plain /me messages, and the " +
        "width and background opacity of the landscape chat panel. Each keeps Twitch's look until changed in Lurk " +
        "settings. Native and React Native live chat and history, plus native VOD chat replay.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchRNChatPatch, twitchSettingsPatch)
    execute {
        styleLines()
        numberLiveMessages()
        hookChatRows("$STYLE->bindRow(Landroid/view/View;Landroid/widget/TextView;)V")
        sizeLandscapeChat()
        sizeTheatreChat()
        twitchSettings.add(
            SwitchSetting(KEY, "Chat appearance",
                "Use the chat look chosen below. Off, or with every choice at Twitch's default, chat looks as in Twitch.",
                Section.CHAT),
            ChoiceSetting("chat_text_size", "Chat text size", "Live chat, chat history and VOD chat replay.",
                Section.CHAT, "0", listOf("Twitch's default" to "0", "85%" to "85", "115%" to "115", "130%" to "130"),
                parent = KEY),
            SwitchSetting("chat_alternate_rows", "Alternating row backgrounds", "Shade every other chat message.",
                Section.CHAT, default = false, parent = KEY),
            ChoiceSetting("chat_me_style", "/me messages", "How messages sent with /me look.",
                Section.CHAT, "0", listOf("Twitch's default (italic)" to "0", "Plain" to "1"), parent = KEY),
            ChoiceSetting("chat_landscape_width", "Landscape chat width",
                "Share of the screen width taken by the chat panel in landscape. Applies when the player is next laid out.",
                Section.CHAT, "0",
                listOf("Twitch's default (30%, 20% on tablets)" to "0", "20%" to "20", "25%" to "25", "35%" to "35",
                    "40%" to "40"),
                parent = KEY),
            ChoiceSetting("chat_landscape_opacity", "Landscape chat background",
                "Opacity of the landscape chat panel's background, over the video or the player's black background.",
                Section.CHAT, "0",
                listOf("Twitch's default (85% dark, 95% light)" to "0", "75%" to "75", "50%" to "50", "25%" to "25"),
                parent = KEY),
        )
    }
}

/**
 * The line builder takes the message style. Swap Twitch's /me style (italic) for the normal message style when
 * /me messages are plain, and note the message ID and where the line is shown; at the end, mark every other
 * line's sender span for the alternating backgrounds.
 */
private fun BytecodePatchContext.styleLines() {
    val method = chatLineBuilder()
    val styleType = method.parameterTypes[11].toString()
    // The builder's own default style (flag 0x400): Twitch's normal message, a singleton of a subclass.
    val normal = method.instructionList().firstNotNullOfOrNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.SGET_OBJECT }?.fieldRef
            ?.takeIf { it.type == it.definingClass && classDefByOrNull(it.type)?.superclass == styleType }
    } ?: throw PatchException("Twitch: the chat line builder has no default message style")
    if (classDefBy(normal.type).let { !AccessFlags.FINAL.isSet(it.accessFlags) }) {
        throw PatchException("Twitch: unexpected chat message style")
    }
    method.addInstructions(0, """
        move-object/from16 v0, p11
        sget-object v1, $normal
        move-object/from16 v2, p9
        move-object/from16 v3, p13
        invoke-static { v0, v1, v2, v3 }, $STYLE->beginLine(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        check-cast v0, $styleType
        move-object/from16 p11, v0
    """)
    val (init, start) = method.spanGroupConstructor()
    val sender = start + 3
    method.addInstructions(init, """
        invoke-static/range { v$sender .. v${sender + 2} }, $STYLE->markLine($CHAR_SEQUENCE$CHAR_SEQUENCE$CHAR_SEQUENCE)$CHAR_SEQUENCE
        move-result-object v$sender
    """)
}

/**
 * Live chat and chat history make each line's list item when the message arrives, in order. Number the
 * messages there (with their sender and tokens, so hidden ones are skipped), so alternating backgrounds stay
 * with their message when old lines leave the list.
 */
private fun BytecodePatchContext.numberLiveMessages() {
    val line = liveChatLine()
    val method = ChatLineItemFingerprint.twitchMatch("chat line item").method
    if (method.parameterTypes[0].toString() != line.type || AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("Twitch: the chat line item isn't made from a live chat message")
    }
    method.requireLocals(3)
    method.addInstructions(0, """
        move-object/from16 v2, p1
        iget-object v0, v2, ${line.id}
        iget-object v1, v2, ${line.sender}
        iget-object v2, v2, ${line.tokens}
        invoke-static { v0, v1, v2 }, $STYLE->onMessage(Ljava/lang/String;Ljava/lang/Object;Ljava/util/List;)V
    """)
}

/** Right after the landscape chat panel gets Twitch's width, adjust its width and background. */
private fun BytecodePatchContext.sizeLandscapeChat() {
    val match = LandscapeChatWidthFingerprint.twitchMatch("landscape chat width")
    match.method.apply {
        val call = match.instructionMatches.last().index
        val panel = (instructionList()[call] as FiveRegisterInstruction).registerD
        if (panel > 15) throw PatchException("Twitch: landscape chat panel register out of range")
        requireNotBranchTarget(call + 1, "landscape chat width")
        addInstructions(call + 1, "invoke-static { v$panel }, $STYLE->landscapeChat(Landroid/view/View;)V")
    }
}

/**
 * The native theatre lays its landscape chat column out from a guideline at 70% of the width (80% on tablets).
 * Right after Twitch sets that percentage, set the one the extension returns, which also adjusts the column's
 * background. Twitch's 100% (no chat column) jumps past this.
 */
private fun BytecodePatchContext.sizeTheatreChat() {
    TheatreLayoutFingerprint.twitchMatch("theatre layout").method.apply {
        val instructions = instructionList()
        val phone = instructions.indexOfFirst {
            it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == 0.7f.toRawBits()
        }
        if (phone < 0) throw PatchException("Twitch: no theatre chat guideline percentage")
        val percent = (instructions[phone] as OneRegisterInstruction).registerA
        // Right after the constant: set(percent, guideline ID) on the constraint set.
        val call = phone + 1
        val setter = instructions[call].methodRef
        if (instructions[call].opcode != Opcode.INVOKE_VIRTUAL || setter?.returnType != "V" ||
            setter.parameterTypes.map { it.toString() } != listOf("F", "I") ||
            (instructions[call] as FiveRegisterInstruction).registerD != percent) {
            throw PatchException("Twitch: the theatre chat guideline percentage isn't set right away")
        }
        val set = instructions[call] as FiveRegisterInstruction
        // The constraint set was cloned from the theatre's ConstraintLayout, which the same register still holds.
        val layoutType = "Landroidx/constraintlayout/widget/ConstraintLayout;"
        val clone = (call downTo 0).firstOrNull { i ->
            val instruction = instructions[i]
            instruction.opcode == Opcode.INVOKE_VIRTUAL && (instruction as FiveRegisterInstruction).registerC == set.registerC &&
                instruction.methodRef?.let { ref ->
                    ref.definingClass == setter.definingClass && ref.parameterTypes.map { it.toString() } == listOf(layoutType)
                } == true
        } ?: throw PatchException("Twitch: the theatre constraint set isn't cloned from its layout")
        val layout = (instructions[clone] as FiveRegisterInstruction).registerD
        requireKept(clone, call + 1, layout, "theatre layout")
        if (listOf(layout, set.registerC, set.registerD, set.registerE).any { it > 15 }) {
            throw PatchException("Twitch: theatre layout registers out of range")
        }
        // Inserted after the call: the branch for 100% (no chat column) targets the next original instruction.
        addInstructions(call + 1, """
            invoke-static { v$layout, v$percent }, $STYLE->landscapeChatGuide(Landroid/view/View;F)F
            move-result v$percent
            invoke-virtual { v${set.registerC}, v$percent, v${set.registerE} }, $setter
        """)
    }
}
