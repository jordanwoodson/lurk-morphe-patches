package io.github.jordanwoodson.patches.twitch.chat

import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.HermesBundle
import io.github.jordanwoodson.patches.twitch.shared.hex
import io.github.jordanwoodson.patches.twitch.shared.installDelayedPredicate
import io.github.jordanwoodson.patches.twitch.shared.twitchHermes
import io.github.jordanwoodson.patches.twitch.shared.twitchHermesPatch
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val SHOW_DELETED = "chat_show_deleted"
private const val IGNORE_CLEARS = "chat_ignore_clears"
private const val SPANNED = "Landroid/text/SpannedString;"
private const val BUILDER = "Landroid/text/SpannableStringBuilder;"

@Suppress("unused")
val showDeletedMessagesPatch = bytecodePatch(
    name = "Show deleted messages",
    description = "Keeps the text of chat messages that moderators delete, or remove with a timeout or ban, " +
        "struck through and dimmed instead of Twitch's \"message deleted\" placeholder. A second switch keeps " +
        "the messages when a moderator clears the whole chat.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchRNChatPatch, twitchSettingsPatch, twitchHermesPatch)
    execute {
        keepMessagesDeletedBeforeShown()
        keepMessagesDeletedAfterShown()
        ignoreChatClears()
        twitchHermes.ignoreClearChatEvents()
        val predicate = object {}.javaClass.classLoader.getResourceAsStream("twitch/delayed.hbc")?.use { it.readBytes() }
            ?: throw PatchException("Twitch: missing compiled delayed predicate; run the pinned compiler task")
        twitchHermes.installDelayedPredicate(predicate)
        twitchSettings.add(
            SwitchSetting(SHOW_DELETED, "Show deleted messages",
                "Keep deleted messages, struck through, instead of \"message deleted\".", Section.CHAT, restart = true),
            SwitchSetting(IGNORE_CLEARS, "Ignore chat clears",
                "Keep the messages when a moderator clears the chat. Timeouts and bans still apply.",
                Section.CHAT, restart = true),
        )
    }
}

/**
 * A message that is already deleted when its line is built (deleted while chat was closed, or sent during a
 * timeout's grace period) takes a branch that builds the placeholder. Keep its text instead.
 */
private fun BytecodePatchContext.keepMessagesDeletedBeforeShown() {
    val method = DeletedMessageSpansFingerprint.twitchMatch("deleted chat line spans").method
    val instructions = method.instructionList()
    val placeholderText = resourceId(ResourceType.STRING, "chat_message_deleted")
    val text = instructions.indices.single { (instructions[it] as? WideLiteralInstruction)?.wideLiteral == placeholderText }
    // The branch: if-eqz <isDeleted parameter>, :shown; new-instance SpannableStringBuilder (the placeholder).
    val start = (text downTo 1).firstOrNull {
        instructions[it].opcode == Opcode.NEW_INSTANCE && instructions[it].typeRef == BUILDER
    } ?: throw PatchException("Twitch: no deleted message placeholder")
    val test = instructions[start - 1]
    val parameters = method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum() +
        if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    if (test.opcode != Opcode.IF_EQZ ||
        (test as OneRegisterInstruction).registerA < method.implementation!!.registerCount - parameters) {
        throw PatchException("Twitch: the deleted message placeholder isn't guarded by its isDeleted parameter")
    }
    val shown = method.branchTarget(start - 1)
    // The message's spans: the SpannedString the placeholder's click span keeps so moderators can reveal it.
    val reveal = (text until instructions.size).firstOrNull {
        instructions[it].opcode == Opcode.INVOKE_DIRECT && instructions[it].methodRef?.let { init ->
            init.name == "<init>" && init.parameterTypes.size == 4 && init.parameterTypes[1].toString() == SPANNED
        } == true
    } ?: throw PatchException("Twitch: no deleted message reveal span")
    val body = (instructions[reveal] as FiveRegisterInstruction).registerE
    val scratch = (instructions[start] as OneRegisterInstruction).registerA
    if (body > 15 || scratch > 15 || body == scratch) throw PatchException("Twitch: unexpected placeholder registers")
    (start until reveal).forEach {
        val write = instructions[it] as? OneRegisterInstruction
        if (write != null && write.registerA == body && !instructions[it].opcode.name.startsWith("INVOKE") &&
            !instructions[it].opcode.name.startsWith("IF_")) {
            throw PatchException("Twitch: the deleted message text is overwritten before the placeholder")
        }
    }
    method.requireNotBranchTarget(start, "deleted message placeholder")
    // Both paths meet at :shown with the message spans in the body register, as the normal path leaves them.
    // The scratch register is the placeholder builder, which nothing after :shown reads.
    method.addInstructionsWithLabels(start, """
        invoke-static { v$body }, $CHAT->deletedBody($SPANNED)$SPANNED
        move-result-object v$scratch
        if-eqz v$scratch, :lurk_placeholder
        move-object v$body, v$scratch
        goto :lurk_shown
    """, ExternalLabel("lurk_placeholder", instructions[start]), ExternalLabel("lurk_shown", shown))
}

/** A line that is already shown is rebuilt with the placeholder after its name. Strike its message instead. */
private fun BytecodePatchContext.keepMessagesDeletedAfterShown() {
    val method = DeletedMessageLineFingerprint.twitchMatch("deleted chat line").method
    val instructions = method.instructionList()
    // new SpannableStringBuilder(line, start, bodyStart): the line up to the message, kept in front of it.
    val copy = instructions.indices.singleOrNull {
        instructions[it].opcode == Opcode.INVOKE_DIRECT && instructions[it].methodRef?.let { init ->
            init.definingClass == BUILDER && init.name == "<init>" &&
                init.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/CharSequence;", "I", "I")
        } == true
    } ?: throw PatchException("Twitch: no deleted line prefix")
    val call = instructions[copy] as FiveRegisterInstruction
    val line = call.registerD
    val bodyStart = call.registerF
    // The line is the SpannedString parameter, still unchanged; the next instruction overwrites its register,
    // so it is free to hold the result.
    val firstParameter = method.implementation!!.registerCount - method.parameterTypes.size
    val next = instructions[copy + 1]
    if (line != firstParameter + 1 || next.opcode != Opcode.CONST_STRING ||
        (next as OneRegisterInstruction).registerA != line || line > 15 || bodyStart > 15) {
        throw PatchException("Twitch: unexpected deleted line registers")
    }
    method.requireNotBranchTarget(copy + 1, "deleted line placeholder")
    // The span the line marks its sender's name with: the class the message start is found from.
    instructions.subList(0, copy).lastOrNull { it.opcode == Opcode.CONST_CLASS }?.typeRef
        ?.let { Locators.record("twitch.show_deleted.name_span", it) }
    method.addInstructionsWithLabels(copy + 1, """
        invoke-static { v$line, v$bodyStart }, $CHAT->deletedLine(${SPANNED}I)Landroid/text/Spanned;
        move-result-object v$line
        if-eqz v$line, :lurk_off
        return-object v$line
    """, ExternalLabel("lurk_off", next))
}

/**
 * CLEARCHAT without a target user is a moderator clearing the chat. Return the parser's "nothing to do"
 * result for it, as for lines without a channel. Timeouts and bans (with a target user) are unchanged.
 */
private fun BytecodePatchContext.ignoreChatClears() {
    val method = IrcEventParserFingerprint.twitchMatch("IRC event parser").method
    val instructions = method.instructionList()
    val ignored = instructions.firstOrNull { it.opcode == Opcode.SGET_OBJECT }?.fieldRef
        ?.takeIf { it.type == it.definingClass && classDefBy(it.type).superclass == method.returnType }
        ?: throw PatchException("Twitch: the IRC parser has no unhandled result")
    val targetUser = instructions.indices.single { instructions[it].stringRef == "target-user-id" }
    // The user-clear event (target present) and the whole-chat clear (a singleton) are both chat events.
    val userClear = (targetUser until instructions.size).firstOrNull { instructions[it].opcode == Opcode.NEW_INSTANCE }
        ?.let { instructions[it].typeRef } ?: throw PatchException("Twitch: no user clear event")
    val clear = (targetUser until instructions.size).firstOrNull {
        instructions[it].opcode == Opcode.SGET_OBJECT && instructions[it].fieldRef?.let { field -> field.type == field.definingClass } == true
    } ?: throw PatchException("Twitch: no chat clear event")
    val event = instructions[clear].fieldRef!!.type
    if (classDefBy(event).superclass != classDefBy(userClear).superclass) throw PatchException("Twitch: unexpected chat clear event")
    val eventRegister = (instructions[clear] as OneRegisterInstruction).registerA
    Locators.record("twitch.ignore_chat_clears.ignored_result", ignored.toString())
    // Right after it, a goto to code that sets a register before reading anything: that register is free.
    val jump = clear + 1
    if (instructions[jump].opcode != Opcode.GOTO) throw PatchException("Twitch: unexpected chat clear flow")
    val landing = method.branchTarget(jump)
    if (!landing.isConstant()) throw PatchException("Twitch: no free register after the chat clear")
    val scratch = (landing as OneRegisterInstruction).registerA
    if (scratch == eventRegister || scratch > 255) throw PatchException("Twitch: no free register after the chat clear")
    method.requireNotBranchTarget(jump, "chat clear jump")
    method.addInstructionsWithLabels(jump, """
        invoke-static {}, $CHAT->ignoreChatClears()Z
        move-result v$scratch
        if-eqz v$scratch, :lurk_keep
        sget-object v$scratch, $ignored
        return-object v$scratch
    """, ExternalLabel("lurk_keep", instructions[jump]))
}

/**
 * React Native theatre: the chat store's 'clearchat' subscriber calls onClearChatEvent. Its closure returns
 * undefined instead, so the RN chat ignores clears too.
 */
private fun HermesBundle.ignoreClearChatEvents() {
    val name = stringId("onClearChatEvent")
    if (name !in 0..65535) throw PatchException("Twitch: onClearChatEvent needs a long string index")
    // LoadFromEnvironment r2, r0, 0; GetById r1, r2, 0, 'onClearChatEvent'; LoadParam r0, 1; Call2; Ret.
    val call = hex("3b 02 00 00 45 01 02 00") + byteArrayOf(name.toByte(), (name ushr 8).toByte()) +
        hex("89 00 01 6e 00 01 02 00 76 00")
    val handler = matchingFunction(call)
    if (!handler.body.contentEquals(hex("34 00 00") + call)) throw PatchException("Twitch: unexpected clearchat handler")
    // wireEvents subscribes it: LoadConstStringLongIndex r3, 'clearchat'; CreateClosure r2, r1, <handler>.
    val event = stringId("clearchat")
    val subscribe = byteArrayOf(0x91.toByte(), 3) + ByteArray(4) { (event ushr (8 * it)).toByte() } +
        byteArrayOf(0x84.toByte(), 2, 1, handler.id.toByte(), (handler.id ushr 8).toByte())
    val wire = function("wireEvents").body
    if ((0..wire.size - subscribe.size).count { at -> subscribe.indices.all { wire[at + it] == subscribe[it] } } != 1) {
        throw PatchException("Twitch: the clearchat handler isn't wireEvents' subscriber")
    }
    val register = handler.numberRegisters + handler.nonPointerRegisters
    if (register !in 0..255 || register >= handler.frameSize) throw PatchException("Twitch: no general register")
    // LoadConstUndefined; Ret. Unreachable (0) pads the rest of the body.
    val body = ByteArray(handler.body.size)
    byteArrayOf(0x93.toByte(), register.toByte(), 0x76, register.toByte()).copyInto(body)
    replace(handler, IGNORE_CLEARS, handler.body, body)
}
