package io.github.jordanwoodson.patches.twitch.chat

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.hex
import io.github.jordanwoodson.patches.twitch.shared.twitchHermes
import io.github.jordanwoodson.patches.twitch.shared.twitchHermesPatch
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val KEY = "chat_anonymous"
private const val LOGIN_RESTRICTION =
    "Ltv/twitch/android/shared/messageinput/pub/chatrestrictions/ChatRestrictionsDisplayType\$LOGIN;"

@Suppress("unused")
val anonymousChatPatch = bytecodePatch(
    name = "Anonymous chat",
    description = "Adds a switch that reads chat as an anonymous guest, the way Twitch does when signed out, " +
        "while the rest of the app stays signed in, so you aren't listed among a channel's chatters. Sending " +
        "chat messages is disabled while it's on. Off by default; applies after a restart.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch, twitchHermesPatch)
    execute {
        anonymousIrcLogin()
        readOnlyChatInput()
        blockChatMessageSend()
        // React Native theatre: chatAuthOptions(token, login) returns {} (a guest login) when the token is
        // missing, else {token, login}. Testing the token with JmpTrue instead of JmpFalse returns {} for a
        // signed-in user. Its chat client already refuses to send without a token.
        twitchHermes.replace("chatAuthOptions", KEY, hex("89 03 01 b2 09 03"), hex("89 03 01 b0 09 03"),
            defaultEnabled = false)
        twitchSettings.add(SwitchSetting(KEY, "Anonymous chat",
            "Read chat as a guest so you aren't listed as a chatter. Sending messages is disabled while this is on.",
            Section.CHAT, default = false, restart = true))
    }
}

/** The IRC login sends PASS listen and a justinfan nick instead of the account's token and name. */
private fun BytecodePatchContext.anonymousIrcLogin() {
    val describe = IrcCredentialsFingerprint.twitchMatch("IRC credentials").method
    val credentials = describe.definingClass
    // toString concatenates ("UserCredentials(nickname=", nick, ", oauthToken=", token, ")"): name the two
    // fields by the labels in front of them.
    val parts = describe.instructionList()
    val concatAt = parts.indices.singleOrNull {
        parts[it].opcode == Opcode.INVOKE_STATIC && parts[it].methodRef?.parameterTypes?.size == 5
    } ?: throw PatchException("Twitch: unexpected IRC credentials description")
    val concat = parts[concatAt] as FiveRegisterInstruction
    fun written(register: Int) = parts.subList(0, concatAt).lastOrNull {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.IGET_OBJECT) &&
            (it as OneRegisterInstruction).registerA == register
    }
    val arguments = listOf(concat.registerC, concat.registerD, concat.registerE, concat.registerF, concat.registerG)
        .map { written(it) }
    if (arguments[0]?.stringRef != "UserCredentials(nickname=" || arguments[2]?.stringRef != ", oauthToken=") {
        throw PatchException("Twitch: IRC credentials fields changed")
    }
    val nick = arguments[1]?.fieldRef ?: throw PatchException("Twitch: no IRC nickname field")
    val token = arguments[3]?.fieldRef ?: throw PatchException("Twitch: no IRC token field")
    if (nick.definingClass != credentials || token.definingClass != credentials || nick.type != "Ljava/lang/String;" ||
        token.type != "Ljava/lang/String;") throw PatchException("Twitch: unexpected IRC credentials fields")

    // Only the login reads the credentials outside their own class, so the account's token and name go
    // nowhere else. Fail rather than leave a second reader unhooked.
    val readers = mutableListOf<String>()
    classDefForEach { type ->
        if (type.type == credentials) return@classDefForEach
        type.methods.forEach { method ->
            method.implementation?.instructions?.forEach {
                val field = it.fieldRef
                if (it.opcode == Opcode.IGET_OBJECT && field != null && (field.matches(nick) || field.matches(token))) {
                    readers += "${type.type}->${method.name}"
                }
            }
        }
    }
    val login = IrcLoginFingerprint.twitchMatch("IRC login").method
    if (readers.size != 2 || readers.any { it != "${login.definingClass}->${login.name}" }) {
        throw PatchException("Twitch: IRC credentials are read outside the login: $readers")
    }

    // Transform each value as it's read. The token is null-checked next ("PASS listen" when null); the nick
    // goes into "NICK ". Both registers are written by the read, so nothing else is overwritten.
    fun hook(field: FieldReference, method: String) {
        val instructions = login.instructionList()
        val read = instructions.indices.single { instructions[it].opcode == Opcode.IGET_OBJECT &&
            instructions[it].fieldRef?.matches(field) == true }
        val register = (instructions[read] as TwoRegisterInstruction).registerA
        if (register > 255) throw PatchException("Twitch: IRC login register out of range")
        login.requireNotBranchTarget(read + 1, "IRC login read")
        login.addInstructions(read + 1, """
            invoke-static/range { v$register .. v$register }, $CHAT->$method(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$register
        """)
    }
    val tokenRead = login.instructionList().let { list ->
        list.indexOfFirst { it.opcode == Opcode.IGET_OBJECT && it.fieldRef?.matches(token) == true }
    }
    if (login.instructionList()[tokenRead + 1].opcode != Opcode.IF_NEZ) {
        throw PatchException("Twitch: the IRC token is no longer null-checked before PASS")
    }
    hook(token, "ircToken")
    hook(nick, "ircNick")
}

private fun FieldReference.matches(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

/** While anonymous, the chat input shows Twitch's own log-in restriction, which also blocks sending. */
private fun BytecodePatchContext.readOnlyChatInput() {
    val method = ChatRestrictionFingerprint.twitchMatch("chat input restriction").method
    method.requireLocals(1)
    method.addInstructionsWithLabels(0, """
        invoke-static {}, $CHAT->readOnly()Z
        move-result v0
        if-eqz v0, :lurk_off
        sget-object v0, $LOGIN_RESTRICTION->INSTANCE:$LOGIN_RESTRICTION
        return-object v0
    """, ExternalLabel("lurk_off", method.getInstruction(0)))

    // Its "Login to Chat" label says why the input is read-only instead.
    val label = ChatRestrictionLabelFingerprint.twitchMatch("chat restriction label").method
    val instructions = label.instructionList()
    val loginText = resourceId(ResourceType.STRING, "chat_input_label_login")
    val text = instructions.indices.single { (instructions[it] as? WideLiteralInstruction)?.wideLiteral == loginText }
    val result = text + 2
    if (instructions[text + 1].methodRef?.name != "getString" || instructions[result].opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("Twitch: unexpected chat restriction label")
    }
    val register = (instructions[result] as OneRegisterInstruction).registerA
    label.requireNotBranchTarget(result + 1, "chat restriction label")
    label.addInstructions(result + 1, """
        invoke-static/range { v$register .. v$register }, $CHAT->loginLabel(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v$register
    """)
}

/**
 * A message that gets past the input anyway (a reply, a reward's message) fails with Twitch's own send-error
 * result instead of reaching the SendChatMessage mutation, which would post it under the account.
 */
private fun BytecodePatchContext.blockChatMessageSend() {
    val mutation = SendChatMessageMutationFingerprint.twitchMatch("SendChatMessage mutation").method.definingClass
    val method = sendChatMessageFingerprint(mutation).twitchMatch("chat message send").method
    val instructions = method.instructionList()
    // The send's own failure result: new-instance R; R(Throwable); return-object R.
    val failure = instructions.indices.mapNotNull { index ->
        val type = instructions[index].typeRef.takeIf { instructions[index].opcode == Opcode.NEW_INSTANCE }
        val init = instructions.getOrNull(index + 1)?.methodRef
        type.takeIf {
            init != null && init.definingClass == type && init.name == "<init>" &&
                init.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Throwable;") &&
                instructions.getOrNull(index + 2)?.opcode == Opcode.RETURN_OBJECT
        }
    }.singleOrNull() ?: throw PatchException("Twitch: no unique chat send failure result")
    method.requireLocals(2)
    method.addInstructionsWithLabels(0, """
        invoke-static {}, $CHAT->sendBlocked()Ljava/lang/Throwable;
        move-result-object v0
        if-eqz v0, :lurk_off
        new-instance v1, $failure
        invoke-direct {v1, v0}, $failure-><init>(Ljava/lang/Throwable;)V
        return-object v1
    """, ExternalLabel("lurk_off", method.getInstruction(0)))
}
