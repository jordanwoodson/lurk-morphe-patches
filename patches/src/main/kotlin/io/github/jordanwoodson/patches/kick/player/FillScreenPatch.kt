package io.github.jordanwoodson.patches.kick.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "fill_screen"
private const val RESIZE_MODE = "Lcom/amazonaws/ivs/player/ResizeMode;"

@Suppress("unused")
val fillScreenPatch = bytecodePatch(
    name = "Fill screen",
    description = "Zooms streams and videos to fill the screen in landscape, cropping their top and bottom instead " +
        "of showing black bars at the sides, on phones taller than 16:9. Vertical videos and the player next to " +
        "the chat keep their bars. Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        // Kick's player view scales its SurfaceView by the IVS resize mode, which stays FIT: React Native never
        // passes one. The method checks the mode against FIT right before comparing the video's aspect ratio with
        // the view's, so the hook gets both ratios there and may hand back FILL. The view runs it on every layout,
        // so rotating or entering picture-in-picture checks again.
        SurfaceTransformFingerprint.kickMatch("player view surface transform").method.apply {
            Locators.record("kick.fill_screen.surface_transform", this)
            val code = implementation!!.instructions.toList()
            val fit = "$RESIZE_MODE->FIT:$RESIZE_MODE"
            val read = code.indices.singleOrNull { i ->
                code[i].opcode == Opcode.IGET_OBJECT &&
                    ((code[i] as ReferenceInstruction).reference as? FieldReference)?.type == RESIZE_MODE &&
                    code.getOrNull(i + 1)?.opcode == Opcode.SGET_OBJECT &&
                    (code[i + 1] as ReferenceInstruction).reference.toString() == fit
            } ?: throw PatchException("Kick: expected one FIT check in the player view's surface transform")
            val mode = (code[read] as TwoRegisterInstruction).registerA
            val constant = (code[read + 1] as OneRegisterInstruction).registerA
            val branch = code[read + 2] as? TwoRegisterInstruction
            val compare = code.getOrNull(read + 3) as? ThreeRegisterInstruction
            if (branch == null || branch.opcode !in setOf(Opcode.IF_EQ, Opcode.IF_NE) ||
                setOf(branch.registerA, branch.registerB) != setOf(mode, constant) ||
                compare == null || compare.opcode != Opcode.CMPL_FLOAT
            ) {
                throw PatchException("Kick: the surface transform no longer compares FIT, then the aspect ratios")
            }
            // cmpl-float compares the video's width / height with the view's.
            addInstructions(
                read + 1,
                """
                    ${invokeStatic(
                        "$KICK_PLAYER->resizeMode(Landroid/view/View;Ljava/lang/Object;FF)Ljava/lang/Object;",
                        parameterRegister(0), mode, compare.registerB, compare.registerC,
                    )}
                    move-result-object v$mode
                    check-cast v$mode, $RESIZE_MODE
                """,
            )
        }
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Fill screen",
                "Zoom streams to fill the screen in landscape instead of showing bars at the sides, cropping their " +
                    "top and bottom. Not for vertical videos, next to the chat or in picture-in-picture.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
