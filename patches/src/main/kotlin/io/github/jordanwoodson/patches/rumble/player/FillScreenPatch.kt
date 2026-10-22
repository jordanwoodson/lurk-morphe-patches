package io.github.jordanwoodson.patches.rumble.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PLAYER
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "fill_screen"

@Suppress("unused")
val fillScreenPatch = bytecodePatch(
    name = "Fill screen",
    description = "Zooms videos to fill the screen in landscape, cropping the edges instead of showing black " +
        "bars, on phones taller than 16:9. Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Every phone player is a Media3 PlayerView with the FIT resize mode, kept in its AspectRatioFrameLayout.
        // Rumble sets it once, so the mode is changed where the layout reads it while measuring: the check
        // runs again when the screen rotates or enters picture-in-picture.
        val match = AspectRatioMeasureFingerprint.single("video frame measure")
        val getter = match.classDef.methods.singleOrNull { it.name == "getResizeMode" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Rumble: no getResizeMode in the video frame")
        val field = getter.implementation!!.instructions.firstOrNull { it.opcode == Opcode.IGET }
            ?.let { (it as ReferenceInstruction).reference.toString() }
            ?: throw PatchException("Rumble: unexpected getResizeMode")
        match.method.apply {
            val code = implementation!!.instructions.toList()
            val reads = code.indices.filter {
                code[it].opcode == Opcode.IGET && (code[it] as ReferenceInstruction).reference.toString() == field
            }
            val read = reads.singleOrNull() ?: throw PatchException("Rumble: expected one resize mode read")
            val mode = (code[read] as TwoRegisterInstruction).registerA
            val self = parameterRegister(0)
            if (mode > 15 || self > 15) throw PatchException("Rumble: unexpected registers in the video frame")
            addInstructions(
                read + 1,
                """
                    invoke-static { v$self, v$mode }, $RUMBLE_PLAYER->resizeMode(Landroid/view/View;I)I
                    move-result v$mode
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Fill screen",
                "Zoom videos to fill the screen in landscape, cropping the edges. Not in picture-in-picture.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
