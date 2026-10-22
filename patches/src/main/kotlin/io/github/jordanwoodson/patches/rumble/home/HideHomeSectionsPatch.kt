package io.github.jordanwoodson.patches.rumble.home

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_INTERFACE
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.TextSetting

private const val CATEGORIES = "home_hide_categories"
private const val CREATORS = "home_hide_creators"
private const val PLAYLISTS = "home_hide_playlists"
private const val TITLES = "home_hide_titles"

// HomeSectionDto.kind: what each Home section is (videos, shorts, categories, creators, playlists, ad_block,
// hide). Its mapper parses it into HomeSectionKind; a "hide" section is neither loaded nor drawn.
private val HomeSectionKindGetterFingerprint =
    dtoGetterFingerprint("Lcom/rumble/network/dto/home/HomeSectionDto;", "kind", "Ljava/lang/String;")

@Suppress("unused")
val hideHomeSectionsPatch = bytecodePatch(
    name = "Hide Home sections",
    description = "Hides Home's categories, creators and playlists rows, each with its own switch, and any " +
        "section whose title you list in Lurk settings, such as Live or Rumble Originals. Starts off. Turn them " +
        "on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        HomeSectionKindGetterFingerprint.single("Home section kind getter").method.apply {
            Locators.record("rumble.hide_home_sections.kind_getter", this)
            val code = implementation!!.instructions
            if (code.size != 2 || code[0].opcode != Opcode.IGET_OBJECT || code[1].opcode != Opcode.RETURN_OBJECT) {
                throw PatchException("Rumble: unexpected Home section kind getter")
            }
            val kind = getInstruction<OneRegisterInstruction>(0).registerA
            val self = parameterRegister(0)
            addInstructions(
                1,
                """
                    invoke-static { v$self, v$kind }, $RUMBLE_INTERFACE->homeSectionKind(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$kind
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(CATEGORIES, "Hide category rows", "Home's rows of categories, such as Top Live Categories.",
                Section.INTERFACE, default = false),
            SwitchSetting(CREATORS, "Hide creator rows", "Home's rows of recommended channels.",
                Section.INTERFACE, default = false),
            SwitchSetting(PLAYLISTS, "Hide playlist rows", "Home's rows of playlists.",
                Section.INTERFACE, default = false),
            TextSetting(TITLES, "Hide Home sections by title",
                "Section titles to hide, separated by commas, such as Live, Editor Picks. Any case.",
                Section.INTERFACE),
        )
    }
}
