package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import com.android.tools.smali.dexlib2.Opcode
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val SHOW_DELETED = "chat_show_deleted"
private const val IGNORE_CLEARS = "chat_ignore_clears"
private const val WEBVIEW_COMPAT = "Landroidx/webkit/WebViewCompat;"
private const val WEBVIEW_FEATURE = "Landroidx/webkit/WebViewFeature;"
private const val ADD_DOCUMENT_START = "addDocumentStartJavaScript(Landroid/webkit/WebView;Ljava/lang/String;Ljava/util/Set;)" +
    "Landroidx/webkit/ScriptHandler;"

/** The moderator view script KickChat registers on each WebView, from the patches' resources into the app's assets. */
private val moderatorViewScriptPatch = rawResourcePatch {
    compatibleWith(COMPATIBILITY_KICK)
    execute {
        val script = object {}.javaClass.classLoader.getResourceAsStream("kick/chat/moderator-view.js")?.use { it.readBytes() }
            ?: throw PatchException("Bundled Kick moderator view script missing")
        get("assets/lurk-chat-moderator.js").writeBytes(script)
    }
}

@Suppress("unused")
val showDeletedMessagesPatch = bytecodePatch(
    name = "Show deleted messages",
    description = "Keeps chat messages that moderators delete, or remove with a timeout or ban, struck through " +
        "with a (Deleted), (Timed out) or (Banned) label, the way Kick's chat shows them to moderators, along with " +
        "its ban, timeout and unban lines. Lurk turns this view on inside the chat page on your phone; the chat " +
        "address the app sends to Kick stays as Kick builds it, so Kick's server sees no change. Large KICKs gifts " +
        "in chat also get the moderators' menu, whose Delete does nothing unless you moderate the channel. Needs an " +
        "Android System WebView that can run a script before the page's own (any current one). A second switch " +
        "keeps the chat when a moderator clears it. Both on by default, as on Twitch. Turn them off in Lurk " +
        "settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch, moderatorViewScriptPatch)

    execute {
        moderatorViewAtDocumentStart()

        kickSettings.add(
            SwitchSetting(
                SHOW_DELETED,
                "Show deleted messages",
                "Keep deleted, timed-out and banned messages, struck through, as moderators see them. Only the chat " +
                    "page on your phone changes; Kick's server sees nothing different. Applies to chats opened afterwards.",
                Section.CHAT,
            ),
            // The chat page empties its message list on a clear; KickChat's script keeps it.
            SwitchSetting(
                IGNORE_CLEARS,
                "Ignore chat clears",
                "Keep the messages when a moderator clears the chat. Timeouts and bans still apply. Applies to " +
                    "chats opened afterwards.",
                Section.CHAT,
            ),
        )
    }
}

/**
 * Kick's chat list is a web page (chat.mobile.kick.com) in a WebView. The app passes the page a JSON `config` in its
 * address, including the viewer's `userCanModerate`, and the page shows removed messages struck through and
 * labelled, plus ban, timeout and unban lines, when that is true. The address reaches Kick's server, so it isn't
 * changed. The page reads its config from react-native-webview's `ReactNativeWebView.injectedObjectJson()` in
 * preference to the address, and the moderator view script supplies that function with userCanModerate true. It has
 * to run before the page's own scripts, so KickChat registers it on every RNCWebView as an androidx.webkit
 * document-start script limited to the chat page's origin, when the view is constructed and before it loads anything.
 */
context(context: BytecodePatchContext)
private fun moderatorViewAtDocumentStart() {
    fun requireMethod(type: String, signature: String) {
        val classDef = context.classDefByOrNull(type) ?: throw PatchException("Kick: androidx.webkit $type is missing or renamed")
        if (classDef.methods.none { it.name + it.parameterTypes.joinToString("", "(", ")") + it.returnType == signature }) {
            throw PatchException("Kick: androidx.webkit $type lacks $signature")
        }
    }
    requireMethod(WEBVIEW_FEATURE, "isFeatureSupported(Ljava/lang/String;)Z")
    requireMethod(WEBVIEW_COMPAT, ADD_DOCUMENT_START)
    context.bridge(KICK_CHAT, "documentStartSupported", """
        const-string v0, "DOCUMENT_START_SCRIPT"
        invoke-static { v0 }, $WEBVIEW_FEATURE->isFeatureSupported(Ljava/lang/String;)Z
        move-result v0
        return v0
    """, locals = 1)
    context.bridge(KICK_CHAT, "addDocumentStartScript", """
        invoke-static { p0, p1, p2 }, $WEBVIEW_COMPAT->$ADD_DOCUMENT_START
        return-void
    """)

    WebViewConstructorFingerprint.kickMatch("RNCWebView constructor").method.apply {
        val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        val last = returns.singleOrNull()?.index ?: throw PatchException("Kick: RNCWebView's constructor no longer returns once")
        addInstructions(last, "invoke-static/range { p0 .. p0 }, $KICK_CHAT->onWebViewCreated(Landroid/webkit/WebView;)V")
    }
}
