package io.github.jordanwoodson.patches.rumble.chat

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_CHAT
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.rumble.shared.toStringGetter
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.compose.toStringField

/**
 * Gives the extension the fields of a live chat message entity it needs (text, sender, time), through
 * stubs filled with the entity's getters. Shared by the chat patches, so each stub is filled once.
 */
internal val chatEntityPatch = bytecodePatch(
    description = "Lets the chat patches read live chat messages.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        val entity = ChatMessageEntityFingerprint.single("live chat message entity").classDef
        Locators.record("rumble.chat.entity", entity.type)
        listOf(
            Triple("messageText", ", message=", "entity_message"),
            Triple("userName", ", userName=", "entity_user_name"),
            Triple("timeReceived", ", timeReceived=", "entity_time_received"),
        ).forEach { (stub, label, locator) ->
            Locators.record("rumble.chat.$locator", entity.toStringField(RUMBLE, label).toString())
            bridge(
                RUMBLE_CHAT,
                stub,
                """
                    check-cast p0, ${entity.type}
                    invoke-virtual { p0 }, ${entity.toStringGetter(label)}
                    move-result-object p0
                    return-object p0
                """,
            )
        }
    }
}
