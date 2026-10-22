package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val chatTimestampsPatch = bytecodePatch(
    name = "Chat timestamps",
    description = "Shows the time next to each chat message. Kick's own chat settings have the same option for " +
        "signed-in accounts; this one also works signed out and, while on, wins over Kick's. Kick's chat page draws " +
        "the times, so a change to that page can stop it. Starts off. Turn it on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch)

    execute {
        // Every line already has its time; the chat page shows it through --timestamps-display, which KickChat's page
        // script holds at inline (chat-page.js).
        kickSettings.add(
            SwitchSetting("chat_timestamps", "Chat timestamps",
                "Show the time next to each chat message. Applies to chats opened afterwards.", Section.CHAT,
                default = false),
        )
    }
}
