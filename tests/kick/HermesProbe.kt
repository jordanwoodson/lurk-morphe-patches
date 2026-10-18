package io.github.jordanwoodson.patches.kick.shared

import io.github.jordanwoodson.patches.kick.shared.HermesBundle.Constant
import java.io.File
import java.security.MessageDigest

/**
 * Runs against the supplied Kick bundle, not a reconstructed or synthetic bytecode file.
 * Usage: HermesProbe <stock index.android.bundle> <patched bundle out> <manifest out>
 */
fun main(args: Array<String>) {
    val original = File(args[0]).readBytes()
    val bundle = HermesBundle(original)
    check(bundle.function("KicksShopButton").offset == 0x8d20c0)
    check(bundle.function("preferencesSettingsSlice").body.size == 97)
    fun rejects(block: () -> Unit) = check(runCatching(block).isFailure)
    rejects { HermesBundle(original.copyOf().also { it[8] = 98 }) }
    rejects { HermesBundle(original.copyOf().also { it[500] = (it[500].toInt() xor 1).toByte() }) }
    rejects { bundle.function("missing") }
    rejects { bundle.function("get") }
    val disabled = HermesBundle(original)
    disabled.returnNull("KicksShopButton", "hide_buttons", defaultEnabled = false)
    check(disabled.preview().contentEquals(original))
    rejects { bundle.replace("KicksShopButton", "invalid", byteArrayOf(52), byteArrayOf(1, 2)) }
    for (name in listOf("KicksShopButton", "GetKicksButton", "ChannelSubButton", "ChannelGiftSubButton")) {
        bundle.returnNull(name, "hide_buttons")
    }
    rejects { bundle.returnNull("KicksShopButton", "overlap") }
    val preview = bundle.previewDefault("disable_previews")
    check(preview.original.size == 97 && preview.replacement.size == 97)

    // The decoder walks every function body exactly, including ones with switch tables.
    val decoded = bundle.instructions(bundle.function("handleBanUserEvent"))
    check(decoded.last().offset == 0x122 && decoded.last().bytes.contentEquals(byteArrayOf(0x76, 1)))
    check(decoded.count { it.target() != null } == 5)

    // Object literal keys come from the shape table, in the slot order PutOwnBySlotIdx fills.
    val chatConfig = bundle.functionUsing("web chat config", reads = listOf("searchParams", "getRequestBaseHeaders"),
        loads = listOf("https://chat.mobile.kick.com"))
    val literals = bundle.instructions(chatConfig).filter { it.opcode == 2 } // NewObjectWithBufferLong
    check(bundle.objectKeys(literals[0]).take(3) == listOf("chatroomId", "subscriberBadges", "userCanModerate"))
    check(bundle.objectKeys(literals[0]).size == 11)
    check(bundle.objectKeys(literals[1]) == listOf("id", "slug", "username"))
    check(bundle.objectKeys(literals[2]) == listOf("uri"))
    rejects { bundle.objectKeys(bundle.instructions(chatConfig).first()) }

    // returnAtEntry covers prologues returnNull rejects, and returns the requested constant.
    bundle.returnAtEntry("AppUpdateHandler", "block_update_prompts")
    bundle.returnAtEntry("shouldShowAppRating", "hide_app_rating", Constant.FALSE)
    rejects { bundle.returnNull("shouldShowAppRating", "invalid") }
    bundle.returnAtEntry("handleClearChatEvent", "chat_ignore_clears", Constant.UNDEFINED)
    val clear = bundle.function("handleClearChatEvent")
    val general = (clear.numberRegisters + clear.nonPointerRegisters).toByte()
    check(general > 0 && bundle.edits.last().replacement.contentEquals(
        byteArrayOf(0x93.toByte(), general, 0x76, general, 0, 0, 0, 0)))
    rejects { bundle.returnAtEntry("handleSubscriptionEvent", "invalid") } // JmpTrue isn't a prologue.

    // One GetById becomes `true`; a function reading the property twice, or not at all, is rejected.
    val deleted = bundle.functionUsing("delete message", reads = listOf("userCanModerate"), loads = listOf("user_deleted"))
    bundle.trueProperty(deleted, "userCanModerate", "chat_show_deleted")
    check(bundle.edits.last().original.contentEquals(hex("45 03 03 06")
        + byteArrayOf(bundle.stringId("userCanModerate").toByte(), (bundle.stringId("userCanModerate") ushr 8).toByte())))
    check(bundle.edits.last().replacement.contentEquals(hex("95 03 95 03 95 03")))
    bundle.trueProperty(bundle.function("handleBanUserEvent"), "userCanModerate", "chat_show_deleted")
    rejects { bundle.trueProperty(bundle.function("handleBanUserEvent"), "Date", "invalid") } // TryGetById, twice.
    rejects { bundle.trueProperty(bundle.function("handleClearChatEvent"), "userCanModerate", "invalid") }
    rejects { bundle.functionUsing("missing", reads = listOf("userCanModerate"), loads = listOf("clear-")) }

    // Both guard shapes; a function without one, or with another JmpTrue first, is rejected.
    bundle.skipGuard(bundle.function("handleSubscriptionEvent"), "hide_sub_messages", defaultEnabled = false)
    check(bundle.edits.last().replacement.contentEquals(hex("10 07 07")))
    bundle.skipGuard(bundle.function("addHistoryEntry"), "no_search_history")
    check(bundle.edits.last().original.contentEquals(hex("b1 05 02")) &&
        bundle.edits.last().replacement.contentEquals(hex("10 02 02")))
    rejects { bundle.skipGuard(bundle.function("shouldShowAppRating"), "invalid") }
    val hostEnd = bundle.functionUsing("host countdown end", reads = listOf("currentPath"),
        loads = listOf("/stream", "/clip", "/vod"))
    rejects { bundle.skipGuard(hostEnd, "invalid") } // Its first JmpTrue isn't a return guard.
    bundle.returnAtEntry(hostEnd, "host_end", Constant.UNDEFINED, defaultEnabled = false)
    // This is decoder/helper coverage only, not a Stay on channel execution test.
    // verify_hosting.py executes the APK manifest's real countdown edit, retained React caches,
    // actual JSX handlers/countdown callbacks and stock/off/mutated bytecode controls.
    check(bundle.instructions(bundle.function("JoinHostDialogContent")).count {
        (it.opcode == 1 || it.opcode == 2) && bundle.objectKeys(it) == listOf("duration", "startOnMount", "onEnd")
    } == 1)

    // Literal edits: exact original bytes, unique replacement windows, choices with their own bytes.
    val skip = 201721
    check(bundle.literal(skip, 13).contentEquals(hex("73 0a000000 0a000000 0a000000")))
    rejects { bundle.dataEdit(skip, "invalid", hex("73 0b000000"), hex("73 0c000000")) }
    rejects { bundle.dataEdit(-1, "invalid", hex("00"), hex("01")) }
    val choice = bundle.dataChoice(skip, "vod_skip_seconds", hex("73 0a000000 0a000000 0a000000"),
        mapOf("5" to hex("73 05000000 05000000 05000000"), "30" to hex("73 1e000000 1e000000 1e000000")))
    check(choice.embedded.contentEquals(choice.original))
    rejects { bundle.dataChoice(skip, "vod_skip_seconds", hex("73"), mapOf("x" to hex("74"))) }
    rejects { bundle.dataEdit(142621, "vod_skip_seconds", hex("a8 02"), hex("a4 02")) } // Mixed kinds on one key.
    bundle.dataChoice(142621, "amoled_black", hex("a8 02"), mapOf("1" to hex("a4 02"), "2" to hex("a4 02")))
    bundle.dataChoice(142619, "amoled_black", hex("ba 02"), mapOf("2" to hex("ad 02")))
    rejects { bundle.dataChoice(142620, "amoled_black", hex("02 a8"), mapOf("1" to hex("02 a4"))) }
    val shipped = HermesBundle(original)
    shipped.dataChoice(skip, "vod_skip_seconds", hex("73 0a000000 0a000000 0a000000"),
        mapOf("5" to hex("73 05000000 05000000 05000000")), defaultValue = "5")
    check(shipped.replacements().single().let { (from, to) -> from != to && from.length == to.length })

    val patched = bundle.preview()
    check(patched.size == original.size)
    check(MessageDigest.getInstance("SHA-1").digest(patched.copyOfRange(0, patched.size - 20))
        .contentEquals(patched.copyOfRange(patched.size - 20, patched.size)))
    val restored = patched.copyOf()
    for (edit in bundle.edits) edit.original.copyInto(restored, edit.offset)
    MessageDigest.getInstance("SHA-1").digest(restored.copyOfRange(0, restored.size - 20))
        .copyInto(restored, restored.size - 20)
    check(restored.contentEquals(original))
    // Functions with edits, plus one window for the adjacent colour edits and one for the skip interval.
    check(bundle.replacements().size == bundle.edits.filter { it.functionId >= 0 }.map { it.functionId }.toSet().size + 2)
    for ((from, to) in bundle.replacements()) {
        val pattern = hex(from)
        check((0..original.size - pattern.size).count { start ->
            original[start] == pattern[0] && pattern.indices.all { original[start + it] == pattern[it] }
        } == 1)
        check(from.length == to.length)
    }
    val manifest = bundle.manifest()
    check(manifest.contains("\"format\":2") && manifest.contains("\"embeddedSha256\""))
    check(manifest.contains("\"values\":{\"1\":\"a402\",\"2\":\"a402\"},\"default\":\"0\""))
    File(args[1]).writeBytes(patched)
    File(args[2]).writeText(manifest)
    println("PASS: actual v99 headers, fail-closed checks, ${bundle.edits.size} edits (entry returns, property " +
        "reads, guards, literal choices), footer, unique windows and exact disabled restoration")
}
