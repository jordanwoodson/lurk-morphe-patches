package io.github.jordanwoodson.patches.rumble.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_CHAT
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.rumble.shared.staticRangeCalls
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.TextSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals

private const val KEY = "chat_keywords"

@Suppress("unused")
val chatKeywordsPatch = bytecodePatch(
    name = "Chat keywords",
    description = "Highlights live chat messages that mention you or contain your highlight words with a tinted " +
        "background, optionally with a vibration, and hides messages with blocked words or from blocked users. " +
        "Mentions are on by default; the word and user lists start empty. On by default. Turn it off in Lurk " +
        "settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, liveChatHookPatch, chatEntityPatch)

    execute {
        // Hiding happens in the live chat hook, before Rumble builds rows. Mentions need the signed-in user:
        // the mapper gets its ID, and the chat history's users list has its name.
        ChatEventMapperFingerprint.single("live chat event mapper").method.apply {
            // (this, time, event, baseUrl, currentUserId, creatorName)
            if (AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("Rumble: unexpected live chat event mapper")
            val register = parameterRegister(4)
            addInstructions(
                0,
                "invoke-static/range { v$register .. v$register }, $RUMBLE_CHAT->currentUserId(Ljava/lang/String;)V",
            )
        }

        // Highlighted rows get a tinted background around Rumble's own modifier.
        val row = LiveChatMessageViewFingerprint.single("live chat message row").method
        Locators.record("rumble.chat.message_row", row)
        val modifier = row.parameterTypes[0].toString()
        row.apply {
            requireLocals(1)
            addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p1 }, $RUMBLE_CHAT->highlight(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $modifier
                    move-object/from16 p0, v0
                """,
            )
        }

        // Modifier.background(color, shape) in its default-argument form, as the Rant row calls it.
        val background = RantMessageViewFingerprint.matchAll().flatMap { it.method.staticRangeCalls() }
            .map { it.second }.filter { method ->
                val parameters = method.parameterTypes.map { it.toString() }
                method.returnType == modifier && parameters.size == 5 && parameters[0] == modifier &&
                    parameters[1] == "J" && parameters[3] == "I" && parameters[4] == "Ljava/lang/Object;"
            }.map { it.toString() }.distinct().singleOrNull()
            ?: throw PatchException("Rumble: expected one background modifier in the Rant row")
        bridge(
            RUMBLE_CHAT,
            "background",
            """
                move-object v0, p0
                check-cast v0, $modifier
                move-wide v1, p1
                const/4 v3, 0x0
                const/4 v4, 0x2
                const/4 v5, 0x0
                invoke-static/range { v0 .. v5 }, $background
                move-result-object v0
                return-object v0
            """,
            locals = 6,
        )

        rumbleSettings.add(
            SwitchSetting(KEY, "Chat keywords",
                "Highlight and hide live chat messages by the words and users below. Whole words, any case.",
                Section.CHAT),
            SwitchSetting("chat_highlight_mentions", "Highlight mentions",
                "Tint messages that mention your username. Needs a signed-in account.", Section.CHAT, parent = KEY),
            TextSetting("chat_highlight_words", "Highlight words",
                "Tint messages containing any of these words or phrases, separated by commas.",
                Section.CHAT, parent = KEY),
            SwitchSetting("chat_highlight_vibrate", "Vibrate on highlights",
                "Vibrate when a highlighted message arrives in live chat.", Section.CHAT, default = false, parent = KEY),
            TextSetting("chat_blocked_words", "Blocked words",
                "Hide messages containing any of these words or phrases, separated by commas.",
                Section.CHAT, parent = KEY),
            TextSetting("chat_blocked_users", "Blocked users",
                "Hide messages from these usernames (as in rumble.com/user/name), separated by commas.",
                Section.CHAT, parent = KEY),
        )
    }
}
