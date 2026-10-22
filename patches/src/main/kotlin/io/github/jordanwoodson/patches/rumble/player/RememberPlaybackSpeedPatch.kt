package io.github.jordanwoodson.patches.rumble.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PLAYBACK
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.enumWith
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals

private const val KEY = "remember_speed"
private const val DEFAULT_KEY = "default_speed"

/**
 * Rumble keeps the picked speed per scope (video page, other) in an in-memory store, which the player service
 * resets to 1x. This hidden patch passes the store through RumblePlayback: picks (onSpeedPicked), resets
 * (onSpeedReset) and reads (rememberedSpeed, which returns Rumble's own value as null). Shared by Remember
 * playback speed and Default playback speed.
 */
internal val speedStorePatch = bytecodePatch(
    description = "Lets the Rumble patches choose the playback speed.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        val speed = enumWith("SPEED_0_25", "NORMAL", "SPEED_2")
        val normal = speed.enumConstant("NORMAL")
        val setMatch = SpeedStoreSetFingerprint.single("playback speed store")
        val set = setMatch.method
        if (set.parameterTypes[1].toString() != speed.type) throw PatchException("Rumble: unexpected speed store")
        val scope = set.parameterTypes[0].toString()
        val get = setMatch.classDef.methods.singleOrNull {
            it.parameterTypes.map(CharSequence::toString) == listOf(scope) && it.returnType == speed.type
        } ?: throw PatchException("Rumble: no speed store getter")
        // The reset: the store's one ()V method, which sets every scope back to 1x.
        val reset = setMatch.classDef.methods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType == "V" && it.name != "<init>" && it.name != "<clinit>"
        } ?: throw PatchException("Rumble: no speed store reset")
        Locators.record("rumble.remember_speed.store_set", set)
        Locators.record("rumble.remember_speed.store_get", get)

        set.addInstruction(0, "invoke-static/range { p2 .. p2 }, $RUMBLE_PLAYBACK->onSpeedPicked(Ljava/lang/Enum;)V")
        reset.addInstruction(0, "invoke-static { }, $RUMBLE_PLAYBACK->onSpeedReset()V")
        get.apply {
            requireLocals(1)
            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $normal
                    invoke-static { v0 }, $RUMBLE_PLAYBACK->rememberedSpeed(Ljava/lang/Enum;)Ljava/lang/Enum;
                    move-result-object v0
                    if-eqz v0, :original
                    check-cast v0, ${speed.type}
                    return-object v0
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }
    }
}

@Suppress("unused")
val rememberPlaybackSpeedPatch = bytecodePatch(
    name = "Remember playback speed",
    description = "Keeps the playback speed you last picked for every video, including after Rumble restarts, " +
        "instead of going back to 1x. Live streams without rewind still play at 1x. Starts off. Turn it on in " +
        "Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, speedStorePatch)

    execute {
        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Remember playback speed",
                "Keep the speed you last picked for every video, also after a restart. Live streams without " +
                    "rewind stay at 1x.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}

@Suppress("unused")
val defaultPlaybackSpeedPatch = bytecodePatch(
    name = "Default playback speed",
    description = "Starts videos at a chosen playback speed instead of 1x, until you pick another speed in the " +
        "player. Live streams without rewind still play at 1x. Starts off (1x). Choose a speed in Lurk " +
        "settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, speedStorePatch)

    execute {
        // Values are the speed in hundredths; RumblePlayback maps them to Rumble's speed constants by name.
        rumbleSettings.add(
            ChoiceSetting(
                DEFAULT_KEY,
                "Default playback speed",
                "The speed videos start at until you pick one in the player. Remember playback speed, when on, " +
                    "keeps your pick instead.",
                Section.PLAYBACK,
                default = "0",
                choices = listOf(
                    "1x (Rumble's)" to "0", "0.5x" to "50", "0.75x" to "75", "0.9x" to "90", "1.25x" to "125",
                    "1.5x" to "150", "1.75x" to "175", "2x" to "200",
                ),
            ),
        )
    }
}
