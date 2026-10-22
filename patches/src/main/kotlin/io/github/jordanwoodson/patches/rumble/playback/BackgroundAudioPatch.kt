package io.github.jordanwoodson.patches.rumble.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.enumWith
import io.github.jordanwoodson.patches.rumble.shared.preferenceStoredValue
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.invoke
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.isEnabled

private const val KEY = "background_audio"

@Suppress("unused")
val backgroundAudioPatch = bytecodePatch(
    name = "Background audio by default",
    description = "Makes Settings > Playback > Background Play default to Sound only: leaving the app keeps the " +
        "sound playing instead of opening a picture-in-picture window. A choice made in Rumble's settings still " +
        "wins. Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // UserPreferenceManager reads backgroundPlayKey as an Int: 1 picture-in-picture, 2 sound only, 3 off.
        // With nothing stored it picks picture-in-picture where the device supports it, else sound only. While
        // the switch is on, nothing stored reads as sound only's value, so Rumble's own mapping does the rest.
        val preferences = UserPreferencesFingerprint.single("user preference manager").classDef
        val (reader, cast) = preferenceStoredValue(
            preferences,
            "backgroundPlayKey",
            "Ljava/lang/Integer;->intValue()I",
            "Ljava/lang/Integer;",
        )
        val modes = enumWith("PICTURE_IN_PICTURE", "SOUND", "OFF")
        val sound = modes.enumConstant("SOUND")
        // The mode's value getter, as the reader calls it to compare with the stored Int.
        val value = reader.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            reference?.takeIf {
                instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == modes.type &&
                    it.parameterTypes.isEmpty() && it.returnType == "I"
            }
        } ?: throw RUMBLE.error("no Background Play value getter in the preference reader")
        val stored = reader.getInstruction<OneRegisterInstruction>(cast).registerA

        // Only the stored value's register is used: it is null on the path that replaces it.
        reader.addInstructionsWithLabels(
            cast + 1,
            """
                if-nez v$stored, :keep
                ${isEnabled(KEY, "v$stored")}
                if-eqz v$stored, :none
                sget-object v$stored, $sound
                ${invoke("invoke-virtual", value.toString(), stored)}
                move-result v$stored
                ${invokeStatic("Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", stored)}
                move-result-object v$stored
                goto :keep
                :none
                const/16 v$stored, 0x0
            """,
            ExternalLabel("keep", reader.getInstruction(cast + 1)),
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Background audio by default",
                "Leaving the app keeps the sound playing, instead of picture-in-picture, until you choose " +
                    "otherwise in Settings > Playback > Background Play.",
                Section.PLAYBACK,
                default = false,
                restart = true,
            ),
        )
    }
}
