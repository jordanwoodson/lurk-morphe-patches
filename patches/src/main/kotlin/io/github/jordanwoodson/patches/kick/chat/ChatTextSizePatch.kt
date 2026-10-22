package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val chatTextSizePatch = bytecodePatch(
    name = "Chat text size",
    description = "Sets the chat text size, from 12 to 24 pixels, and can pack chat messages closer together " +
        "(compact chat). Kick's own chat settings have a text size for signed-in accounts; this one also works signed " +
        "out and, while set, wins over Kick's. Kick's chat page draws the text, so a change to that page can stop it. " +
        "Starts at Kick's size with compact chat off. Choose another in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch)

    execute {
        // The chat page sizes its text from --chatroom-font-size and spaces lines by --message-gap; KickChat's page
        // script holds them at the chosen values (chat-page.js).
        kickSettings.add(
            ChoiceSetting("chat_text_size", "Chat text size",
                "The size of chat text. Applies to chats opened afterwards.", Section.CHAT, "0",
                listOf("Kick's setting" to "0") + listOf(12, 14, 18, 20, 24).map { "$it px" to "$it" }),
            SwitchSetting("chat_compact", "Compact chat",
                "Less space between chat messages. Applies to chats opened afterwards.", Section.CHAT, default = false),
        )
    }
}
