package io.github.jordanwoodson.patches.twitch.playback

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.lowParameters
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section

private val QUALITIES = linkedMapOf("Twitch's default" to "0", "Source" to "1", "1080p60" to "108060",
    "1080p" to "1080", "720p60" to "72060", "720p" to "720", "480p" to "480", "360p" to "360", "160p" to "160")

@Suppress("unused")
val defaultVideoQualityPatch = bytecodePatch(
    name = "Default video quality",
    description = "Starts playback at the selected rendition when available, with Source as the default. " +
        "Saved player choices, including Auto, take priority. Unavailable renditions use Twitch's fallback. " +
        "A lower rendition for metered networks (mobile data, or Wi-Fi marked as metered) can be chosen in Lurk " +
        "settings > Playback > On mobile data; it's the same as on Wi-Fi until then.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    val quality by stringOption(key = "quality", default = "1", values = QUALITIES,
        title = "Default rendition", description = "Rendition to use before a player quality is saved.", required = true) {
        it in QUALITIES.values
    }
    execute {
        val match = QualityPreferencesFingerprint.twitchMatch("video quality preferences")
        val constructor = match.method
        val code = constructor.implementation!!.instructions.toList()
        val auto = code.withIndex().single { (_, instruction) ->
            (instruction as? ReferenceInstruction)?.reference.let { (it as? StringReference)?.string == "auto" }
        }
        val register = (auto.value as OneRegisterInstruction).registerA
        // This is the fallback for restoring video after audio-only mode, independent of each
        // content type's saved quality. Do not replace a persisted last-used value.
        constructor.addInstructions(auto.index + 1, """
            invoke-static/range { v$register .. v$register }, $PLAYBACK->qualityDefault(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$register
        """)
        val reader = match.classDef.methods.single { method ->
            method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                method.returnType == "Ljava/lang/String;" && method.implementation?.instructions?.any {
                    (it as? ReferenceInstruction)?.reference.let { ref -> (ref as? StringReference)?.string == "video_quality" }
                } == true
        }
        // Change only the fallback argument. Saved _live/_vod/_clip values, including Auto and
        // audio_only, remain under Twitch's control.
        val p = reader.lowParameters(2, 1, firstLocal = 0)
        reader.addInstructions(0, """
            ${p.prologue}
            ${invokeStatic("$PLAYBACK->qualityPreferenceDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", p[2], p[1])}
            move-result-object p2
        """)

        // Twitch's source rendition is named like "1080p60 (source)", so neither Source nor a named
        // choice that is the source would match by name. Resolve the request to the full name first.
        PlayerQualityByNameFingerprint.twitchMatch("player quality selection by name").method.apply {
            val player = implementation!!.instructions.first()
            val field = ((player as? ReferenceInstruction)?.reference as? FieldReference)
            if (player.opcode != Opcode.IGET_OBJECT || field?.type != "Lcom/amazonaws/ivs/player/MediaPlayer;") {
                throw PatchException("Twitch: unexpected player quality selection")
            }
            // After the first instruction, with no register proven free: fail rather than lose the call.
            val ivs = (player as OneRegisterInstruction).registerA
            requireLowRegisters("player quality selection", parameterRegister(1), ivs)
            addInstructions(1, """
                ${invokeStatic("$PLAYBACK->playerQuality(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", parameterRegister(1), ivs)}
                move-result-object p1
            """)
        }

        val missing = MissingAsyncStorageValueFingerprint.twitchMatch("missing React Native storage value").method
        val missingCode = missing.implementation!!.instructions.toList()
        val pushNull = missingCode.withIndex().single { (_, instruction) ->
            (instruction as? ReferenceInstruction)?.reference.toString() == "Lcom/facebook/react/bridge/WritableArray;->pushNull()V"
        }
        val before = missingCode[pushNull.index - 1] as? FiveRegisterInstruction
            ?: throw PatchException("Twitch: missing key before AsyncStorage null")
        val beforeRef = (missingCode[pushNull.index - 1] as? ReferenceInstruction)?.reference as? MethodReference
        val array = (pushNull.value as FiveRegisterInstruction).registerC
        if (beforeRef?.toString() != "Lcom/facebook/react/bridge/WritableArray;->pushString(Ljava/lang/String;)V" ||
            before.registerC != array || before.registerCount != 2 || before.registerD == array) {
            throw PatchException("Twitch: unexpected missing AsyncStorage row")
        }
        val key = before.registerD
        missing.replaceInstruction(pushNull.index, "invoke-static { v$key }, $PLAYBACK->storageQualityDefault(Ljava/lang/String;)Ljava/lang/String;")
        // pushString accepts null, just as the existing cursor-result branch does. The key
        // register is dead after the row is appended; no extra registers or callback changes.
        missing.addInstructions(pushNull.index + 1, """
            move-result-object v$key
            invoke-interface { v$array, v$key }, Lcom/facebook/react/bridge/WritableArray;->pushString(Ljava/lang/String;)V
        """)
        twitchSettings.add(
            ChoiceSetting("video_quality", "Default video quality",
                "Used until a player quality is saved. Missing renditions use Twitch's fallback.", Section.PLAYBACK,
                default = quality!!, choices = QUALITIES.toList(), restart = true),
            // Source is never lower than a default, so it isn't offered; Twitch's default (Auto) counts as highest.
            ChoiceSetting("video_quality_metered", "On mobile data",
                "Used instead of the default on metered networks (mobile data, or Wi-Fi marked as metered) when it " +
                    "is lower. Like the default, only until a player quality is saved.", Section.PLAYBACK,
                default = "0", choices = listOf("Same as on Wi-Fi" to "0") + QUALITIES.toList().drop(2),
                restart = true, parent = "video_quality"),
        )
    }
}
