package io.github.jordanwoodson.patches.kick.shared

import java.io.File
import java.util.zip.ZipFile

/**
 * Metro module discovery on the stock bundle. Usage: ModuleProbe <stock index.android.bundle> [patched.apk]
 * The IDs come from the patched APK's assets/lurk/locators.json (kick.third_party_emotes.module_*) when it has them,
 * otherwise from the IDs below, which Kick's bundle had when the probe was written.
 */
fun main(args: Array<String>) {
    val bundle = HermesBundle(File(args[0]).readBytes())
    val pinned = mapOf("store" to 3232, "images" to 2716, "session" to 2826, "host" to 121, "input" to 58)
    val recorded = args.getOrNull(1)?.let { apk ->
        ZipFile(apk).use { zip ->
            zip.getEntry("assets/lurk/locators.json")?.let { entry -> zip.getInputStream(entry).readBytes().decodeToString() }
        }
    }?.let { json ->
        Regex("\"kick\\.third_party_emotes\\.module_(\\w+)\":\"(\\d+)\"").findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }.orEmpty()
    val expected = pinned + recorded
    println(if (recorded.isEmpty()) "Locators: none in the APK, using the pinned IDs" else "Locators: ${recorded.size} module IDs from the APK")
    check(bundle.metroModule(listOf("chatRoomStore", "useChatroomStore")) == expected.getValue("store"))
    check(bundle.metroModule(listOf("ImageCacheV2")) == expected.getValue("images"))
    check(bundle.metroModule(listOf("useSessionStore", "AppSessionTiming")) == expected.getValue("session"))
    check(bundle.metroModule(listOf("getInternalInstanceHandleFromPublicInstance", "getNativeTagFromPublicInstance"),
        direct = true) == expected.getValue("host"))
    check(bundle.metroModule(listOf("Commands", "__INTERNAL_VIEW_CONFIG", "AndroidTextInput")) == expected.getValue("input"))
    check(runCatching { bundle.metroModule(listOf("missing-export")) }.isFailure)
    println("PASS: unique pinned Metro export and registration contracts")
}
