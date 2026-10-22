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
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PLAYBACK
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.enumWith
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals

private const val KEY = "remember_speed"

@Suppress("unused")
val rememberPlaybackSpeedPatch = bytecodePatch(
    name = "Remember playback speed",
    description = "Keeps the playback speed you last picked for every video, including after Rumble restarts, " +
        "instead of going back to 1x. Live streams without rewind still play at 1x. Starts off. Turn it on in " +
        "Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Rumble keeps the picked speed per scope in memory, and the player service resets it to 1x.
        val speed = enumWith("SPEED_0_25", "NORMAL", "SPEED_2")
        val normal = speed.enumConstant("NORMAL")
        val setMatch = SpeedStoreSetFingerprint.single("playback speed store")
        val set = setMatch.method
        if (set.parameterTypes[1].toString() != speed.type) throw PatchException("Rumble: unexpected speed store")
        val scope = set.parameterTypes[0].toString()
        val get = setMatch.classDef.methods.singleOrNull {
            it.parameterTypes.map(CharSequence::toString) == listOf(scope) && it.returnType == speed.type
        } ?: throw PatchException("Rumble: no speed store getter")
        Locators.record("rumble.remember_speed.store_set", set)
        Locators.record("rumble.remember_speed.store_get", get)

        set.addInstruction(0, "invoke-static/range { p2 .. p2 }, $RUMBLE_PLAYBACK->onSpeedPicked(Ljava/lang/Enum;)V")
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
