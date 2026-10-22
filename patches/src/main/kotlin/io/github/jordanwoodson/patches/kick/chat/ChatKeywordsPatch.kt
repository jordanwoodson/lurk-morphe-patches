package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.TextSetting

// The keys ChatFilter reads, shared with Twitch and Rumble.
private const val KEY = "chat_keywords"

@Suppress("unused")
val chatKeywordsPatch = bytecodePatch(
    name = "Chat keywords",
    description = "Highlights chat messages that mention you or contain your highlight words with a tinted " +
        "background, optionally with a vibration, and hides messages with blocked words or from blocked users. " +
        "Matching happens on your phone; nothing is sent anywhere. Mentions need a signed-in account; the word and " +
        "user lists start empty. A hidden message can show for a moment before it disappears. Starts off. Turn it " +
        "on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatBridgePatch)

    execute {
        // Kick's chat page draws every line. KickChat's page script hands each new line (sender, kick.com handle,
        // text) to the app through react-native-webview's bridge; KickChat matches it with the shared ChatFilter and
        // answers with the lines to tint or hide (kickChatBridgePatch, chat-page.js).
        kickSettings.add(
            SwitchSetting(KEY, "Chat keywords",
                "Highlight and hide chat messages by the words and users below. Whole words, any case. Applies to " +
                    "chats opened afterwards.",
                Section.CHAT, default = false),
            SwitchSetting("chat_highlight_mentions", "Highlight mentions",
                "Tint messages that mention your username. Needs a signed-in account.", Section.CHAT, parent = KEY),
            TextSetting("chat_highlight_words", "Highlight words",
                "Tint messages containing any of these words or phrases, separated by commas.",
                Section.CHAT, parent = KEY),
            SwitchSetting("chat_highlight_vibrate", "Vibrate on highlights",
                "Vibrate when a highlighted message arrives, at most every three seconds.", Section.CHAT,
                default = false, parent = KEY),
            TextSetting("chat_blocked_words", "Blocked words",
                "Hide messages containing any of these words or phrases, separated by commas.",
                Section.CHAT, parent = KEY),
            TextSetting("chat_blocked_users", "Blocked users",
                "Hide messages from these users (the name shown in chat or the kick.com/name handle), separated by " +
                    "commas.",
                Section.CHAT, parent = KEY),
        )
    }
}
