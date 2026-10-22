package io.github.jordanwoodson.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import io.github.jordanwoodson.patches.twitch.settings.BundleLoaderFingerprint
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.metroModule
import io.github.jordanwoodson.patches.twitch.shared.twitchHermes
import io.github.jordanwoodson.patches.twitch.shared.twitchHermesPatch
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val RN_CHAT = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchRNChat;"
private val rnChatResources = rawResourcePatch {
    dependsOn(twitchHermesPatch)
    execute {
        val loader = object {}.javaClass.classLoader
        for (suffix in listOf("hbc", "json")) {
            val bytes = loader.getResourceAsStream("twitch/chat.$suffix")?.use { it.readBytes() }
                ?: throw PatchException("Twitch: missing compiled chat.$suffix; run the pinned compiler task")
            get("assets/lurk-twitch-chat.$suffix").writeBytes(bytes)
        }
        val signatures = linkedMapOf(
            "react" to listOf("createElement", "useContext", "memo"),
            "native" to listOf("NativeModules", "View", "Text", "StyleSheet"),
            "row" to listOf("ChatMessageRow"), "body" to listOf("MessageBody"),
            "time" to listOf("TimestampPart", "formatTimestamp"),
            "store" to listOf("useChatStore", "useChatSelector"),
            "metrics" to listOf("ChatTextMetricsContext", "ChatFontScaleContext"),
            "badges" to listOf("badgeKey", "buildBadgeRegistry"),
            "stage" to listOf("useClassicStagePresentation"),
            "column" to listOf("useColumnTrackWidth", "COLUMN_TRANSITION_MS"),
            "overlayHost" to listOf("OneChatOverlayHost"),
            "theatreStore" to listOf("useTheatreChatStore"),
            "classicColumn" to listOf("ClassicChatColumn"),
            "chatArea" to listOf("ChatArea"),
        )
        get("assets/lurk-twitch-chat-contract.json").writeText(signatures.entries.joinToString(",", "{", "}") {
            "\"${it.key}\":${twitchHermes.metroModule(it.value, direct = it.key != "native")}" })
    }
}

/** Main loads enqueue the compiled callable module before any surface; no eval/sourceURL replacement. */
internal val twitchRNChatPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(rnChatResources)
    execute {
        val match = BundleLoaderFingerprint.twitchMatch("RN chat asset delegate")
        val method = match.method
        val calls = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_STATIC && ref?.definingClass == "Lcom/facebook/react/runtime/ReactInstance;" &&
                ref.name in listOf("access\$loadJSBundleFromAssets", "access\$loadJSBundleFromFile")
        }
        if (calls.size != 2) throw PatchException("Twitch RN chat: expected default/prepared main loads")
        calls.asReversed().forEach { (index, instruction) ->
            val call = instruction as FiveRegisterInstruction
            if (call.registerC != 0 || call.registerE != 2) throw PatchException("Twitch RN chat: loader registers changed")
            method.addInstructions(index + 1, "invoke-static { p0, p1, p2 }, $RN_CHAT->afterMain(Ljava/lang/Object;Landroid/content/res/AssetManager;Ljava/lang/String;)V")
        }
        Fingerprint(definingClass = match.classDef.type, name = "loadScriptFromFile", returnType = "V",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"))
            .twitchMatch("RN chat file delegate").method.apply {
                val call = implementation!!.instructions.withIndex().singleOrNull { (_, instruction) ->
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    instruction.opcode == Opcode.INVOKE_STATIC && ref?.name == "access\$loadJSBundleFromFile"
                } ?: throw PatchException("Twitch RN chat: no unique file load")
                val registers = call.value as FiveRegisterInstruction
                if (registers.registerC != 0 || registers.registerE != 2) throw PatchException("Twitch RN chat: file registers changed")
                addInstructions(call.index + 1, "invoke-static { p0, p2 }, $RN_CHAT->afterFile(Ljava/lang/Object;Ljava/lang/String;)V")
            }
        Fingerprint(definingClass = "Lcom/facebook/react/runtime/ReactInstance;", name = "destroy", returnType = "V", parameters = emptyList())
            .twitchMatch("ReactInstance.destroy").method.addInstructions(0,
                "invoke-static/range { p0 .. p0 }, Lio/github/jordanwoodson/extension/shared/CompiledBundle;->release(Ljava/lang/Object;)V")
        val bridge = Fingerprint(
            definingClass = "Ltv/twitch/android/feature/discovery/feed/rn/bridge/TwitchRNFeedSettingsModule;",
            name = "getDisableAutoplay", returnType = "Z", parameters = emptyList(),
        ).twitchMatch("RN sync feed-settings bridge")
        val annotation = bridge.method.annotations.singleOrNull { it.type == "Lcom/facebook/react/bridge/ReactMethod;" }
            ?: throw PatchException("Twitch RN chat: missing ReactMethod precedent")
        if (annotation.elements.singleOrNull { it.name == "isBlockingSynchronousMethod" }
                ?.value.let { it as? BooleanEncodedValue }?.value != true) {
            throw PatchException("Twitch RN chat: feed-settings method is not synchronous")
        }
        Fingerprint(definingClass = bridge.classDef.type, name = "invalidate", returnType = "V", parameters = emptyList())
            .twitchMatch("RN chat module disposal").method.addInstructions(0,
                "invoke-static/range { p0 .. p0 }, $RN_CHAT->dispose(Ljava/lang/Object;)V")
        val target = mutableClassDefBy(bridge.classDef.type)
        if (target.methods.any { it.name == "getLurkChatData" }) throw PatchException("Twitch RN chat: bridge name collision")
        target.methods.add(ImmutableMethod(target.type, "getLurkChatData",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)), "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, setOf(annotation), emptySet(), MutableMethodImplementation(3))
            .toMutable().apply { addInstructions(0, """
                invoke-static { p0, p1 }, $RN_CHAT->exchange(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """) })
    }
}
