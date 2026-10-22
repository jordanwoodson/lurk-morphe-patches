package io.github.jordanwoodson.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.emotes.EmoteSpanFingerprint
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val COMPACT = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchChatCompact;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val SPANNABLE = "Landroid/text/SpannableString;"

@Suppress("unused")
val compactChatPatch = bytecodePatch(
    name = "Compact chat",
    description = "Adds switches that leave chatters' badges out of native chat lines (Twitch's and third-party " +
        "ones) and show emotes as their text, so no emote images load. Native live chat, chat history and VOD " +
        "chat replay; React Native chat is unchanged. Starts off. Turn it on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The badge span, right before the line's spans are put together (after any third-party badges).
        val method = chatLineBuilder()
        val (init, start) = method.spanGroupConstructor()
        val badges = start + 2
        method.addInstructions(init, """
            invoke-static/range { v$badges .. v$badges }, $COMPACT->badges($CHAR_SEQUENCE)$CHAR_SEQUENCE
            move-result-object v$badges
        """)

        // An emote's span: its code as plain text instead of the image (and its click span).
        EmoteSpanFingerprint.twitchMatch("emote span builder").method.apply {
            val code = implementation!!.instructions.toList()
            val token = parameterTypes[3].toString()
            val text = code.indices.firstNotNullOfOrNull { i ->
                val init = code[i]
                val ref = (init as? ReferenceInstruction)?.reference as? MethodReference
                if (init.opcode != Opcode.INVOKE_DIRECT || ref?.toString() != "$SPANNABLE-><init>($CHAR_SEQUENCE)V") return@firstNotNullOfOrNull null
                val read = code.getOrNull(i - 1)
                val field = (read as? ReferenceInstruction)?.reference as? FieldReference
                field.takeIf {
                    read?.opcode == Opcode.IGET_OBJECT && it?.definingClass == token && it.type == "Ljava/lang/String;" &&
                        (read as TwoRegisterInstruction).registerA == (init as FiveRegisterInstruction).registerD
                }
            } ?: throw PatchException("Twitch: the emote span no longer shows the emote's code")
            addInstructionsIfEnabled("chat_text_emotes", """
                move-object/from16 v0, p4
                iget-object v0, v0, $text
                new-instance v1, $SPANNABLE
                invoke-direct { v1, v0 }, $SPANNABLE-><init>($CHAR_SEQUENCE)V
                return-object v1
            """, locals = 2)
        }

        twitchSettings.add(
            SwitchSetting("chat_hide_badges", "Hide badges",
                "Leaves chatters' badges out of native chat lines, Twitch's and third-party ones.",
                Section.CHAT, default = false),
            SwitchSetting("chat_text_emotes", "Emotes as text",
                "Shows emotes in native chat as their text, without loading their images.",
                Section.CHAT, default = false),
        )
    }
}

@Suppress("unused")
val firstTimeChattersPatch = bytecodePatch(
    name = "Highlight first-time chatters",
    description = "Tints a chatter's first message in a channel in native live chat, using the first-msg tag " +
        "Twitch's chat server puts on it. Chat history, VOD replay and React Native chat carry no such tag. " +
        "Starts off. Turn it on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // Every IRC line native chat receives is split into its tags, prefix, command and so on.
        val components = Fingerprint(
            name = "toString", returnType = "Ljava/lang/String;", parameters = emptyList(),
            strings = listOf("IrcEventComponents(messageTags=", ", command="),
        ).twitchMatch("IRC event components").classDef
        mutableClassDefBy(components.type).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == "Ljava/util/Map;"
        }?.apply {
            // A static call with a parameter only: allowed before the superclass constructor.
            addInstructions(0, invokeStatic("$COMPACT->ircTags(Ljava/util/Map;)V", parameterRegister(1)))
        } ?: throw PatchException("Twitch: the IRC event components no longer start with the tags")

        // The line builder notes the line's message ID at its start and marks its sender span at its end.
        val method = chatLineBuilder()
        method.addInstructions(0, """
            move-object/from16 v0, p9
            invoke-static { v0 }, $COMPACT->beginLine(Ljava/lang/String;)V
        """)
        val (init, start) = method.spanGroupConstructor()
        val sender = start + 3
        method.addInstructions(init, """
            invoke-static/range { v$sender .. v$sender }, $COMPACT->markLine($CHAR_SEQUENCE)$CHAR_SEQUENCE
            move-result-object v$sender
        """)
        hookChatRows("$COMPACT->bindRow(Landroid/view/View;Landroid/widget/TextView;)V")

        twitchSettings.add(SwitchSetting("chat_first_chatters", "Highlight first-time chatters",
            "Tints a chatter's first message in the channel green in native live chat, as Twitch's chat server " +
                "marks it.", Section.CHAT, default = false))
    }
}
