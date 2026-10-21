package io.github.jordanwoodson.patches.kick.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.player.MainActivityCreateFingerprint
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section

private const val KEY = "amoled_black"

@Suppress("unused")
val amoledBlackPatch = bytecodePatch(
    name = "AMOLED black",
    description = "Makes Kick's dark background pure black for OLED screens, optionally with near-black cards and " +
        "sheets. Starts off. Choose it in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // Kick is dark only. Hermes stores strings overlapping each other ('#141517' shares bytes with '1',
        // '17' and others), so the colours aren't edited in place: the object's string IDs are swapped for
        // existing ones. 'highest' (raised elements and borders) stays in every mode. Colors.surface.default
        // is the object literal with keys highest, base, lowest, ... whose values start with a short-string
        // tag and these three colours; no other literal may read the two edited IDs.
        fun id(colour: String): ByteArray = kickHermes.stringId(colour).let {
            if (it > 0xffff) throw PatchException("Kick: $colour needs a long string ID")
            byteArrayOf(it.toByte(), (it ushr 8).toByte())
        }
        val expected = byteArrayOf(0x58) + id("#1C1E20") + id("#141517") + id("#090A0B")
        val surface = kickHermes.literals.filter {
            it.keys?.take(3) == listOf("highest", "base", "lowest") && it.end - it.start >= expected.size &&
                kickHermes.literal(it.start, expected.size).contentEquals(expected)
        }.singleOrNull() ?: throw PatchException("Kick: Colors.surface.default changed")
        // 1: black background. 2: also near-black cards and sheets, one step above black so they still stand out.
        kickHermes.dataChoice(surface.start + 5, KEY, id("#090A0B"), mapOf("1" to id("#000000"), "2" to id("#000000")),
            readers = listOf(surface))
        kickHermes.dataChoice(surface.start + 3, KEY, id("#141517"), mapOf("2" to id("#0B0B0C")), readers = listOf(surface))

        // The window behind React Native, seen during transitions and at the edges, follows the same choice.
        MainActivityCreateFingerprint.kickMatch("MainActivity.onCreate").method.apply {
            val superCreate = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_SUPER && ((it as ReferenceInstruction).reference as MethodReference).name == "onCreate"
            }
            if (superCreate < 0) throw PatchException("Kick: MainActivity.onCreate no longer calls its superclass")
            addInstructions(superCreate + 1, "invoke-static/range { p0 .. p0 }, " +
                "Lio/github/jordanwoodson/extension/shared/kick/KickTheme;->applyWindow(Landroid/app/Activity;)V")
        }

        kickSettings.add(
            ChoiceSetting(
                KEY,
                "AMOLED black",
                "A pure black background, optionally with near-black cards and sheets.",
                Section.INTERFACE,
                "0",
                listOf("Off" to "0", "Black background" to "1", "Black background and surfaces" to "2"),
                restart = true,
            ),
        )
    }
}
