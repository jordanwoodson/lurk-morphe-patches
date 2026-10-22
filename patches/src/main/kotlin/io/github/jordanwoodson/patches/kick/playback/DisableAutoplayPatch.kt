package io.github.jordanwoodson.patches.kick.playback

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesInstruction
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.hex
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "disable_autoplay"

@Suppress("unused")
val disableAutoplayPatch = rawResourcePatch(
    name = "Disable autoplay",
    description = "Starts Kick's feed previews switched off. Existing preferences and Kick's own " +
        "autoplay switch keep working. Does not change intentionally opened streams." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)
    execute {
        previewDefault()
        kickSettings.add(SwitchSetting(KEY, "Disable autoplay",
            "Default feed previews to off. Kick's saved preference takes precedence. Restart required.",
            Section.PLAYBACK, restart = true))
    }
}

/**
 * `preferencesSettingsSlice` builds the preferences store's initial state, `showStreamPreviews: true` among its twelve
 * defaults. The edit keeps every default and closure and changes only showStreamPreviews to false. The state object
 * and the picture-in-picture position object both use NewObjectWithBufferLong; the same serialized values also exist
 * in the first 64 KiB of the value buffer, which the short NewObjectWithBuffer reaches, and their shorter encodings
 * free six bytes for `LoadConstFalse; PutOwnBySlotIdx`. The function has no branches or exception table, and its body
 * size and offsets are pinned: any change in it fails the patch.
 */
private fun previewDefault() {
    fun fail(what: String): Nothing = throw PatchException("Kick: preferences slice $what")
    val function = kickHermes.function("preferencesSettingsSlice")
    val body = function.body
    if (body.size != 97 || function.flags and 0x18 != 0) fail("changed size or metadata")
    val instructions = kickHermes.instructions(function)
    if (instructions.any { it.targets().isNotEmpty() }) fail("has branches")
    val main = instructions.singleOrNull { it.offset == 0x11 } ?: fail("state object moved")
    if (main.opcode != HermesOp.NEW_OBJECT_WITH_BUFFER_LONG || main.u8(1) != 0 ||
        kickHermes.objectKeys(main).size != 12 || kickHermes.objectKeys(main)[2] != "showStreamPreviews") {
        fail("state object changed")
    }
    val pip = instructions.singleOrNull { it.offset == 0x1b } ?: fail("PiP state object moved")
    if (pip.opcode != HermesOp.NEW_OBJECT_WITH_BUFFER_LONG || pip.u8(1) != 2 ||
        kickHermes.objectKeys(pip) != listOf("top", "left", "scale")) {
        fail("PiP state object changed")
    }
    if (!body.copyOfRange(0x25, 0x29).contentEquals(hex("52 00 02 05"))) fail("PiP state isn't slot 5")
    // The short form reaches the first 64 KiB of values: point it at the same serialized run there.
    val window = kickHermes.literal(0, minOf(kickHermes.literalSize, 65536))
    fun shortObject(instruction: HermesInstruction): ByteArray {
        val shape = instruction.u32(2)
        if (shape > 0xffff) fail("state shape needs the long form")
        val start = instruction.u32(6)
        val values = kickHermes.literal(start, kickHermes.valuesEnd(start, kickHermes.shapeSize(shape)) - start)
        val short = (0..window.size - values.size).firstOrNull { offset ->
            values.indices.all { window[offset + it] == values[it] }
        } ?: fail("has no short copy of the literal values at $start")
        return byteArrayOf(HermesOp.NEW_OBJECT_WITH_BUFFER.toByte(), instruction.bytes[1], shape.toByte(),
            (shape ushr 8).toByte(), short.toByte(), (short ushr 8).toByte())
    }
    // Main state object, showStreamPreviews (slot 2) = false, PiP position object; then two pad bytes after Ret.
    val replacement = body.copyOfRange(0, 0x11) + shortObject(main) +
        byteArrayOf(HermesOp.LOAD_CONST_FALSE.toByte(), 2, HermesOp.PUT_OWN_BY_SLOT_IDX.toByte(), 0, 2, 2) +
        shortObject(pip) + body.copyOfRange(0x25, body.size) + byteArrayOf(0, 0)
    kickHermes.replaceBody(function.name, KEY, replacement)
}
