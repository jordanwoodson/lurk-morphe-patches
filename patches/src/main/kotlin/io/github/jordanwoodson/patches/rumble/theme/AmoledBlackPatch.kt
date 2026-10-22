package io.github.jordanwoodson.patches.rumble.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_INTERFACE
import io.github.jordanwoodson.patches.rumble.shared.requireNoBranchTo
import io.github.jordanwoodson.patches.rumble.shared.requireUnwritten
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "amoled_black"
private const val NEAR_BLACK_KEY = "amoled_near_black"

@Suppress("unused")
val amoledBlackPatch = bytecodePatch(
    name = "AMOLED black",
    description = "Turns the dark grey of Rumble's raised surfaces (the bottom bar, search fields, cards, chips and " +
        "tags) pure black for OLED screens, optionally near-black so their edges still show. Rumble's dark theme " +
        "already draws pages, sheets and dialogs black; dividers and buttons keep their grey. Applies after a " +
        "restart. Starts off. Turn it on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // Rumble builds its surface grey in two places, each once per process: the theme's color set (the dark
        // set's surface; the light set uses another field) and the palette of named colors (surfaces that stay
        // dark in both themes, and scrims). The grey's ARGB value passes through the extension on its way into
        // Color(Long), so the switch needs no re-patch, only a restart.
        listOf(ThemeColorsFingerprint to "colors", PaletteFingerprint to "palette").forEach { (fingerprint, what) ->
            val match = fingerprint.single("$what building the surface grey")
            val method = match.method
            Locators.record("rumble.amoled_black.$what", method)
            val literal = match.instructionMatches[0]
            val call = match.instructionMatches[1]
            val register = (literal.instruction as OneRegisterInstruction).registerA
            val argument = when (val invoke = call.instruction) {
                is RegisterRangeInstruction -> invoke.startRegister.takeIf { invoke.registerCount == 2 }
                is FiveRegisterInstruction -> invoke.registerC.takeIf { invoke.registerCount == 2 }
                else -> null
            }
            if (argument != register) {
                throw PatchException("Rumble: the $what no longer turns the surface grey into a color directly")
            }
            method.requireUnwritten(register, literal.index + 1, call.index, "the $what's surface grey")
            method.requireUnwritten(register + 1, literal.index + 1, call.index, "the $what's surface grey")
            method.requireNoBranchTo(literal.index + 1, "the $what's surface grey")
            method.addInstructions(
                literal.index + 1,
                """
                    ${invokeStatic("$RUMBLE_INTERFACE->amoledSurface(J)J", register, register + 1)}
                    move-result-wide v$register
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "AMOLED black",
                "Black instead of dark grey for the bottom bar, search fields, cards, chips and tags. Pages, sheets " +
                    "and dialogs are already black; dividers and buttons keep their grey.",
                Section.INTERFACE,
                default = false,
                restart = true,
            ),
            SwitchSetting(
                NEAR_BLACK_KEY,
                "Near-black surfaces",
                "A black one step lighter (#0B0B0C), so the bottom bar, search fields and cards still stand out " +
                    "from the page.",
                Section.INTERFACE,
                default = false,
                parent = KEY,
                restart = true,
            ),
        )
    }
}
