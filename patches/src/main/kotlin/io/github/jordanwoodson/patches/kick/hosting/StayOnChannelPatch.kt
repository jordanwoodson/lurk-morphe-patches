package io.github.jordanwoodson.patches.kick.hosting

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "stay_on_host"

@Suppress("unused")
val stayOnChannelPatch = bytecodePatch(
    name = "Stay on channel when it hosts",
    description = "When the channel you're watching hosts another one, Kick's host dialog waits for you instead of " +
        "switching when its countdown ends: Watch now still switches, Cancel stays. Starts off. Turn it on in Lurk " +
        "settings > Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        countdownAfterWatchNow()
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Stay on channel when it hosts",
                "The host dialog waits instead of counting down. Watch now still switches.",
                Section.INTERFACE,
                default = false,
                restart = true,
            ),
        )
    }
}

/**
 * `JoinHostDialogContent` is mounted with the stream screen and calls `useCountdown({duration, startOnMount: true,
 * onEnd})`. When it reaches zero, onEnd switches to the hosted channel on a stream, clip or VOD screen. Watch now
 * doesn't switch by itself: it sets the dialog's `pressed` state (which hides it) and shows a "taking you to ... in
 * N seconds" toast, and the countdown's end does the rest.
 *
 * The countdown now starts only once Watch now is pressed: `startOnMount` is `pressed` instead of true, so the
 * dialog waits, countdown paused, for Watch now, Cancel or close. The literal's startOnMount slot is a constant,
 * so its store takes the place of the memo cache write that follows the onEnd store. Without it the options
 * object is rebuilt on every render, which useCountdown, reading only its fields, doesn't notice.
 */
private fun countdownAfterWatchNow() {
    val dialog = kickHermes.function("JoinHostDialogContent")
    val code = kickHermes.instructions(dialog)
    fun fail(what: String): Nothing = throw PatchException("Kick: host dialog $what changed")

    // const [pressed, setPressed] = useState(false): the first IteratorNext after the useState read, copied once.
    val useState = code.singleOrNull { it.propertyId() == kickHermes.stringId("useState") } ?: fail("useState read")
    val next = code.firstOrNull { it.offset > useState.offset && it.opcode == HermesOp.ITERATOR_NEXT } ?: fail("state destructuring")
    val copy = code.firstOrNull { it.offset > next.offset && it.opcode == HermesOp.MOV && it.u8(2) == next.u8(1) }
        ?: fail("state destructuring")
    val pressed = copy.u8(1)

    val options = code.filter { it.opcode == HermesOp.NEW_OBJECT_WITH_BUFFER || it.opcode == HermesOp.NEW_OBJECT_WITH_BUFFER_LONG }
        .singleOrNull { kickHermes.objectKeys(it) == listOf("duration", "startOnMount", "onEnd") }
        ?: fail("countdown options")
    // PutOwnBySlotIdx rObject, rValue, slot
    fun store(slot: Int) = code.singleOrNull { it.opcode == HermesOp.PUT_OWN_BY_SLOT_IDX && it.u8(1) == options.u8(1) && it.u8(3) == slot }
        ?: fail("countdown options slot $slot")
    val duration = store(0)
    val onEnd = store(2)
    val at = code.indexOf(onEnd)
    // LoadConstUInt8 r0, index; PutByValStrict rCache, r0, rOnEnd
    val (cacheIndex, cacheWrite) = (code.getOrNull(at + 1) ?: fail("memo")) to (code.getOrNull(at + 2) ?: fail("memo"))
    if (code.getOrNull(at - 1) != duration || cacheIndex.opcode != HermesOp.LOAD_CONST_UINT8 || cacheIndex.u8(1) != 0 ||
        cacheWrite.opcode != HermesOp.PUT_BY_VAL_STRICT || cacheWrite.u8(2) != 0 || cacheWrite.u8(3) != onEnd.u8(2)) {
        fail("countdown options memo")
    }
    // Nothing writes the state register before the edit. Writes name their register first; jumps start with an offset.
    if (code.any { it.offset in (copy.offset + 1) until cacheIndex.offset && it.targets().isEmpty() &&
            it.bytes.size > 1 && it.u8(1) == pressed }) {
        fail("state register")
    }
    val startOnMount = byteArrayOf(HermesOp.PUT_OWN_BY_SLOT_IDX.toByte(), options.bytes[1], pressed.toByte(), 1)
    val nothing = byteArrayOf(HermesOp.MOV.toByte(), 0, 0) // Mov r0, r0; r0 is reloaded next
    val kept = duration.bytes + onEnd.bytes
    kickHermes.replace(dialog, KEY, kept + cacheIndex.bytes + cacheWrite.bytes, kept + startOnMount + nothing,
        defaultEnabled = false)
}
