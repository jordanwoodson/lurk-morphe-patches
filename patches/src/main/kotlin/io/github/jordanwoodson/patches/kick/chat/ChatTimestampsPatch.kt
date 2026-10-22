package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val chatTimestampsPatch = bytecodePatch(
    name = "Chat timestamps",
    description = "Shows the time next to each chat message, in 24-hour (13:05) or 12-hour (1:05) format as on " +
        "Twitch. Kick's own chat settings can show times for signed-in accounts; this one also works signed out " +
        "and, while on, wins over Kick's. Kick's chat page draws the times, so a change to that page can stop it. " +
        "Starts off. Turn it on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch)

    execute {
        // Every line already has its time; the chat page shows it through --timestamps-display, which KickChat's page
        // script holds at inline, and writes it in the page's locale, which the script rewrites to the clock chosen
        // here (chat-page.js).
        kickSettings.add(
            SwitchSetting("chat_timestamps", "Chat timestamps",
                "Show the time next to each chat message. Applies to chats opened afterwards.", Section.CHAT,
                default = false),
            // Twitch's key, values and default. Read only while the switch is on: Kick's own times keep its format.
            ChoiceSetting("chat_timestamp_format", "Chat timestamps clock", "", Section.CHAT, "0",
                listOf("24-hour (13:05)" to "0", "12-hour (1:05)" to "1"), parent = "chat_timestamps"),
        )
    }
}
