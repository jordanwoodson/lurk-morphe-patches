package io.github.jordanwoodson.patches.twitch.playback

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val backgroundAudioPatch = bytecodePatch(
    name = "Background audio",
    description = "Keeps streams and videos playing as audio when you leave the app, using Twitch's own " +
        "\"Continue Playing Audio in the Background\" with its audio-only stream and media notification. Changes " +
        "only the default, Never in Twitch: a choice made in Twitch's settings still wins.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val method = BackgroundAudioSettingFingerprint.twitchMatch("background audio setting").method
        val instructions = method.implementation!!.instructions.toList()
        // "never" is both the preference lookup's default and the null fallback, so one rewrite covers both.
        val never = instructions.indices.singleOrNull { index ->
            instructions[index].opcode == Opcode.CONST_STRING &&
                ((instructions[index] as ReferenceInstruction).reference as StringReference).string == "never"
        } ?: throw PatchException("Twitch: background audio default changed")
        val register = (instructions[never] as OneRegisterInstruction).registerA
        method.addInstructions(never + 1, """
            invoke-static/range { v$register .. v$register }, $PLAYBACK->backgroundAudioDefault(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$register
        """)
        twitchSettings.add(ChoiceSetting("background_audio", "Background audio",
            "Default for Twitch's \"Continue Playing Audio in the Background\" until you pick one in Twitch's settings.",
            Section.PLAYBACK, "1",
            listOf("Twitch's default (never)" to "0", "Always" to "1", "With headphones or a speaker" to "2")))
    }
}
