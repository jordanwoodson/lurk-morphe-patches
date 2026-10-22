package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegisters

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

/**
 * The chat page's messages to the app, through react-native-webview's bridge: KickChat takes the ones Lurk's page
 * script sends (7TV catalogs, Chat keywords batches) and leaves every other one to Kick. A WebView's destruction ends
 * its page's authority. Third-party emotes and Chat keywords depend on this.
 */
internal val kickChatBridgePatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch)

    execute {
        WebViewDestroyFingerprint.kickMatch("RNCWebView.destroy").method.addInstructions(0,
            "invoke-static/range { p0 .. p0 }, $KICK_CHAT->onPageDisposed(Landroid/webkit/WebView;)V")
        WebViewMessageFingerprint.kickMatch("RNCWebView.onMessage").method.apply {
            if (implementation!!.registerCount < 4) throw PatchException("Kick: RNCWebView.onMessage has no scratch register")
            val onMessage = invokeStatic(
                "$KICK_CHAT->onMessage(Landroid/webkit/WebView;Ljava/lang/String;Ljava/lang/String;)Z",
                *parameterRegisters().toIntArray())
            addInstructionsWithLabels(0, """
                $onMessage
                move-result v0
                if-eqz v0, :stock
                return-void
            """, ExternalLabel("stock", getInstruction(0)))
        }
    }
}
