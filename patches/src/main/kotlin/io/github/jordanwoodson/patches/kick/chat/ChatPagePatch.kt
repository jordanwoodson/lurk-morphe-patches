package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import com.android.tools.smali.dexlib2.Opcode
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK

/** The script KickChat evaluates in Kick's chat page, from the patches' resources into the app's assets. */
private val chatPageScriptPatch = rawResourcePatch {
    compatibleWith(COMPATIBILITY_KICK)
    execute {
        val script = object {}.javaClass.classLoader.getResourceAsStream("kick/chat/chat-page.js")?.use { it.readBytes() }
            ?: throw PatchException("Bundled Kick chat page script missing")
        get("assets/lurk-chat.js").writeBytes(script)
    }
}

/**
 * Kick draws chat in a WebView showing its chat page (chat.mobile.kick.com), which has its own realtime
 * connection and draws every chat line itself. When a page starts and when it finishes loading, KickChat
 * evaluates Lurk's chat page script there with the switches that are on. Patches whose switches change chat
 * lines depend on this one and register the switches it reads.
 */
internal val kickChatPagePatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch, chatPageScriptPatch)

    execute {
        mapOf("onPageStarted" to PageStartedFingerprint, "onPageFinished" to PageFinishedFingerprint)
            .forEach { (name, fingerprint) ->
                fingerprint.kickMatch("RNCWebViewClient.$name").method.apply {
                    // Right after the super call, before onPageStarted reuses p2.
                    val call = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
                    if (call < 0) throw PatchException("Kick: RNCWebViewClient.$name no longer calls super")
                    addInstructions(call + 1, "invoke-static/range { p1 .. p2 }, " +
                        "Lio/github/jordanwoodson/extension/shared/kick/KickChat;->${if (name == "onPageStarted") "onPageStarted" else "onPage"}(Landroid/webkit/WebView;Ljava/lang/String;)V")
                }
            }
    }
}
