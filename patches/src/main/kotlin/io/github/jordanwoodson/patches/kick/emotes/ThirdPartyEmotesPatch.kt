package io.github.jordanwoodson.patches.kick.emotes

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.chat.kickChatPagePatch
import io.github.jordanwoodson.patches.kick.chat.kickHttpPatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

@Suppress("unused")
val thirdPartyEmotesPatch = bytecodePatch(
    name = "Third-party emotes",
    description = "Adds optional 7TV emotes to chat, the native picker and autocomplete, with zero-width layers " +
        "and live set updates. Sends the channel owner's Kick user ID to 7tv.io, asks again every 5 minutes while " +
        "the chat is open, keeps a websocket to events.7tv.io open for the channel's emote sets and loads images " +
        "from cdn.7tv.app, so 7TV can see which channels you watch and for how long. Starts off. Turn it on in " +
        "Lurk settings > Chat." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    // Kick's chat page only knows the channel ID, and 7TV knows channels by their owner's user ID: KickHttp reads both
    // from the channel responses the app already receives (kickHttpPatch).
    dependsOn(kickChatPagePatch, kickComposerPatch, kickHttpPatch)

    execute {
        // The chat page script loads the sets from 7tv.io and turns the emote names in messages into images.
        kickSettings.add(
            SwitchSetting(
                "emotes_7tv",
                "7TV emotes",
                "Shows 7TV in chat, picker and autocomplete. Sends the channel owner's Kick user ID to 7tv.io every " +
                    "5 minutes and keeps a connection to events.7tv.io while chat is open, so 7TV can see which " +
                    "channels you watch and for how long.",
                Section.CHAT,
                default = false,
                restart = true,
            ),
        )
    }
}
