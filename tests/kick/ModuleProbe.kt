package io.github.jordanwoodson.patches.kick.shared
import java.io.File
fun main(args: Array<String>) {
    val bundle = HermesBundle(File(args[0]).readBytes())
    check(bundle.metroModule(listOf("chatRoomStore", "useChatroomStore")) == 3232)
    check(bundle.metroModule(listOf("ImageCacheV2")) == 2716)
    check(bundle.metroModule(listOf("useSessionStore", "AppSessionTiming")) == 2826)
    check(bundle.metroModule(listOf("getInternalInstanceHandleFromPublicInstance", "getNativeTagFromPublicInstance"), direct = true) == 121)
    check(bundle.metroModule(listOf("Commands", "__INTERNAL_VIEW_CONFIG", "AndroidTextInput")) == 58)
    check(runCatching { bundle.metroModule(listOf("missing-export")) }.isFailure)
    println("PASS: unique pinned Metro export and registration contracts")
}
