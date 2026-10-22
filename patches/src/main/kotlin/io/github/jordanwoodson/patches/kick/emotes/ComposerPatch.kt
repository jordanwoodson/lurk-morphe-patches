package io.github.jordanwoodson.patches.kick.emotes

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.kick.chat.kickChatBridgePatch
import io.github.jordanwoodson.patches.kick.settings.BundleLoaderFingerprint
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.parameterRegisters
import io.github.jordanwoodson.patches.shared.requireLowRegisters

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
        // bin/test-kick-modules compares a fresh discovery on the stock bundle with these.
        mapOf("store" to store, "images" to images, "session" to session, "host" to host, "input" to input)
            .forEach { (name, id) -> Locators.record("kick.third_party_emotes.module_$name", "$id") }
        get("assets/lurk-kick-chat-contract.json").writeText(
            """{"store":$store,"images":$images,"session":$session,"host":$host,"input":$input,"files":"https://files.kick.com/","enabled":true}"""
        )
    }
}

/** Both native main-bundle branches enqueue the same compiled bootstrap, without re-entering the delegate. */
internal val kickComposerPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(composerResources, kickChatBridgePatch)
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
            method.addInstructions(index + 1, invokeStatic(
                "$COMPOSER->afterMain(Ljava/lang/Object;Landroid/content/res/AssetManager;Ljava/lang/String;)V",
                *method.parameterRegisters().take(3).toIntArray()))
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
                // p0 and p2 aren't adjacent, so no range form: refuse a version where either is above v15.
                requireLowRegisters("composer file delegate", parameterRegister(0), parameterRegister(2))
                addInstructions(call.index + 1, "invoke-static { p0, p2 }, $COMPOSER->afterFile(Ljava/lang/Object;Ljava/lang/String;)V")
            }
        Fingerprint(definingClass = "Lcom/facebook/react/runtime/ReactInstance;", name = "destroy", returnType = "V", parameters = emptyList())
            .kickMatch("ReactInstance.destroy").method.addInstructions(0,
                "invoke-static/range { p0 .. p0 }, Lio/github/jordanwoodson/extension/shared/CompiledBundle;->release(Ljava/lang/Object;)V")
    }
}
