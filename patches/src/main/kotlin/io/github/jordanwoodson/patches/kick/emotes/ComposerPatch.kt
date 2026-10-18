package io.github.jordanwoodson.patches.kick.emotes

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.settings.BundleLoaderFingerprint
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch

private const val COMPOSER = "Lio/github/jordanwoodson/extension/shared/kick/KickComposer;"

private val composerResources = rawResourcePatch {
    dependsOn(kickHermesPatch)
    execute {
        val loader = object {}.javaClass.classLoader
        for ((resource, asset) in listOf("kick/chat.hbc" to "lurk-kick-chat.hbc", "kick/chat.json" to "lurk-kick-chat.json")) {
            val bytes = loader.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: throw PatchException("Missing compiled Kick composer $resource; run the pinned compiler task")
            get("assets/$asset").writeBytes(bytes)
        }
        val store = kickHermes.metroModule(listOf("chatRoomStore", "useChatroomStore"))
        val images = kickHermes.metroModule(listOf("ImageCacheV2"))
        val session = kickHermes.metroModule(listOf("useSessionStore", "AppSessionTiming"))
        val host = kickHermes.metroModule(listOf("getInternalInstanceHandleFromPublicInstance", "getNativeTagFromPublicInstance"), direct = true)
        val input = kickHermes.metroModule(listOf("Commands", "__INTERNAL_VIEW_CONFIG", "AndroidTextInput"))
        get("assets/lurk-kick-chat-contract.json").writeText(
            """{"store":$store,"images":$images,"session":$session,"host":$host,"input":$input,"files":"https://files.kick.com/","enabled":true}"""
        )
    }
}

/** Both native main-bundle branches enqueue the same compiled bootstrap, without re-entering the delegate. */
internal val kickComposerPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(composerResources)
    execute {
        val match = BundleLoaderFingerprint.kickMatch("composer asset delegate")
        val method = match.method
        val calls = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_STATIC && ref?.definingClass == "Lcom/facebook/react/runtime/ReactInstance;" &&
                ref.name in listOf("access\$loadJSBundleFromAssets", "access\$loadJSBundleFromFile")
        }
        if (calls.size != 2) throw PatchException("Kick composer: expected default/prepared main loads")
        calls.asReversed().forEach { (index, instruction) ->
            val call = instruction as FiveRegisterInstruction
            if (call.registerC != 0 || call.registerE != 2) throw PatchException("Kick composer: loader registers changed")
            method.addInstructions(index + 1, "invoke-static { p0, p1, p2 }, $COMPOSER->afterMain(Ljava/lang/Object;Landroid/content/res/AssetManager;Ljava/lang/String;)V")
        }
        Fingerprint(definingClass = match.classDef.type, name = "loadScriptFromFile", returnType = "V",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"))
            .kickMatch("composer file delegate").method.apply {
                val call = implementation!!.instructions.withIndex().singleOrNull { (_, instruction) ->
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    instruction.opcode == Opcode.INVOKE_STATIC && ref?.name == "access\$loadJSBundleFromFile"
                } ?: throw PatchException("Kick composer: no unique file load")
                val registers = call.value as FiveRegisterInstruction
                if (registers.registerC != 0 || registers.registerE != 2) throw PatchException("Kick composer: file registers changed")
                addInstructions(call.index + 1, "invoke-static { p0, p2 }, $COMPOSER->afterFile(Ljava/lang/Object;Ljava/lang/String;)V")
            }
        Fingerprint(definingClass = "Lcom/facebook/react/runtime/ReactInstance;", name = "destroy", returnType = "V", parameters = emptyList())
            .kickMatch("ReactInstance.destroy").method.addInstructions(0,
                "invoke-static/range { p0 .. p0 }, Lio/github/jordanwoodson/extension/shared/CompiledBundle;->release(Ljava/lang/Object;)V")
        Fingerprint(definingClass = "Lcom/reactnativecommunity/webview/RNCWebView;", name = "destroy", returnType = "V", parameters = emptyList())
            .kickMatch("WebView catalog disposal").method.addInstructions(0,
                "invoke-static { p0 }, Lio/github/jordanwoodson/extension/shared/kick/KickChat;->onPageDisposed(Landroid/webkit/WebView;)V")
        Fingerprint(definingClass = "Lcom/reactnativecommunity/webview/RNCWebView;", name = "onMessage", returnType = "V",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"))
            .kickMatch("WebView catalog message boundary").method.apply {
                if (implementation!!.registerCount < 4) throw PatchException("Kick composer: no message scratch register")
                addInstructionsWithLabels(0, """
                    invoke-static { p0, p1, p2 }, Lio/github/jordanwoodson/extension/shared/kick/KickChat;->onMessage(Landroid/webkit/WebView;Ljava/lang/String;Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :stock
                    return-void
                """, ExternalLabel("stock", getInstruction(0)))
            }
    }
}
