package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

// The key Twitch's Anonymous chat uses, with the same default.
private const val KEY = "chat_anonymous"

@Suppress("unused")
val anonymousChatPatch = bytecodePatch(
    name = "Anonymous chat",
    description = "Adds a switch that opens Kick's chat as a guest, the way Kick opens it when you're signed out, " +
        "while the rest of the app stays signed in: the chat page gets neither your sign-in token nor your account's " +
        "name and ID, so its own connection to Kick's chat isn't yours. Sending chat messages is disabled while " +
        "it's on, and messages that mention you aren't highlighted. Kick can still tell that you are watching: the " +
        "player's playback request (postPlayerTelemetry) and the app's own live connection still identify you. " +
        "Checked signed out only; whether Kick then leaves you off a channel's chatter list needs an account to " +
        "see. The switch applies after a restart. Starts off. Turn it on in Lurk settings > Chat." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    // The chat page config is built in Kick's JavaScript; the sends are refused in the app's HTTP client.
    dependsOn(kickHermesPatch, kickHttpPatch)

    execute {
        guestChatConfig()
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Anonymous chat",
                "Read chat as a guest: the chat page gets no sign-in token or account. Sending messages is " +
                    "disabled while this is on.",
                Section.CHAT,
                default = false,
                restart = true,
            ),
        )
    }
}

/**
 * The chat page's config (HTMLChatWrapper's memo) is a 12-key object literal that the app JSON-encodes into the page's
 * address. Kick fills its `token` slot with the secure store's sign-in token, which the page sends as a Bearer token
 * when it authenticates its own realtime connection, and builds `selfUser` ({id, slug, username}) only when there is
 * an account:
 *
 *     PutOwnBySlotIdx  config, rToken, <token>
 *     LoadFromEnvironment  rUser, env, <account>
 *     LoadConstUndefined  rSelf
 *     JmpFalse  end, rUser            ; no account: selfUser stays undefined
 *     ... rSelf = {id, slug, username} ...
 *  end:
 *     PutOwnBySlotIdx  config, rSelf, <selfUser>
 *
 * With the switch on, the token store and the account load become loads of undefined (into rSelf, which the next
 * instruction but one sets to undefined anyway, and into rUser), so the token slot keeps the literal's null and the
 * branch leaves selfUser undefined: the config a signed-out app builds. `headers` (the app's fixed User-Agent and
 * platform headers from getRequestBaseHeaders, nothing about the account) is left alone.
 */
private fun guestChatConfig() {
    val config = kickHermes.functionUsing(
        "web chat config",
        reads = listOf("searchParams", "getRequestBaseHeaders"),
        loads = listOf("https://chat.mobile.kick.com"),
    )
    val code = kickHermes.instructions(config)
    fun fail(what: String): Nothing = throw PatchException("Kick: the chat page config's $what changed")
    val literal = code.filter { it.opcode == HermesOp.NEW_OBJECT_WITH_BUFFER || it.opcode == HermesOp.NEW_OBJECT_WITH_BUFFER_LONG }
        .singleOrNull { kickHermes.objectKeys(it).containsAll(listOf("token", "selfUser", "headers")) }
        ?: fail("object")
    val keys = kickHermes.objectKeys(literal)
    val tokenSlot = keys.indexOf("token")
    val selfSlot = keys.indexOf("selfUser")
    val configRegister = literal.u8(1)
    val (_, run) = kickHermes.sequence(
        "chat config token store and account branch", listOf(config),
        listOf(
            { it.opcode == HermesOp.PUT_OWN_BY_SLOT_IDX && it.u8(1) == configRegister && it.u8(3) == tokenSlot },
            { it.opcode == HermesOp.LOAD_FROM_ENVIRONMENT },
            { it.opcode == HermesOp.LOAD_CONST_UNDEFINED },
            { it.opcode == HermesOp.JMP_FALSE },
        ),
    ) { (_, user, self, jump) -> jump.u8(2) == user.u8(1) && self.u8(1) != user.u8(1) && self.u8(1) != configRegister }
    val (store, user, self, jump) = run
    val end = code.singleOrNull { it.offset == jump.target() } ?: fail("account branch")
    if (end.opcode != HermesOp.PUT_OWN_BY_SLOT_IDX || end.u8(1) != configRegister || end.u8(2) != self.u8(1) ||
        end.u8(3) != selfSlot
    ) {
        fail("selfUser store")
    }
    if (store.bytes.size != 4 || user.bytes.size != 4) fail("instruction sizes")
    val undefined = HermesOp.LOAD_CONST_UNDEFINED.toByte()
    val rSelf = self.bytes[1]
    val rUser = user.bytes[1]
    kickHermes.replace(
        config, KEY, store.bytes + user.bytes,
        byteArrayOf(undefined, rSelf, undefined, rSelf, undefined, rUser, undefined, rUser),
        defaultEnabled = false,
    )
}
