package io.github.jordanwoodson.patches.kick.emotes

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.kick.chat.kickChatPagePatch
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

/** The OkHttp API KickHttp calls. R8 keeps OkHttp's names in Kick but drops methods nothing uses. */
private val OKHTTP_API = mapOf(
    "Lokhttp3/Interceptor;" to listOf("intercept(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"),
    "Lokhttp3/Interceptor\$Chain;" to listOf("request()Lokhttp3/Request;", "proceed(Lokhttp3/Request;)Lokhttp3/Response;"),
    "Lokhttp3/OkHttpClient\$Builder;" to listOf("addInterceptor(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient\$Builder;"),
    "Lokhttp3/Request;" to listOf("url()Lokhttp3/HttpUrl;"),
    "Lokhttp3/HttpUrl;" to listOf("host()Ljava/lang/String;", "encodedPath()Ljava/lang/String;"),
    "Lokhttp3/Response;" to listOf("isSuccessful()Z", "peekBody(J)Lokhttp3/ResponseBody;"),
    "Lokhttp3/ResponseBody;" to listOf("string()Ljava/lang/String;"),
)

@Suppress("unused")
val thirdPartyEmotesPatch = bytecodePatch(
    name = "Third-party emotes",
    description = "Adds optional 7TV emotes to chat, the native picker and autocomplete, with zero-width layers " +
        "and live set updates. Sends the channel owner's Kick user ID to 7tv.io, subscribes to set IDs at " +
        "events.7tv.io and loads images from cdn.7tv.app. Starts off. Turn it on in Lurk settings > Chat.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickChatPagePatch, kickComposerPatch)

    execute {
        requireOkHttpApi()

        // Kick's chat page only knows the channel ID, and 7TV knows channels by their owner's user ID. Every HTTP
        // client Kick makes starts from this builder, so KickHttp sees the channel responses that carry both.
        ClientBuilderFingerprint.kickMatch("OkHttpClientProvider.createClientBuilder").method.apply {
            val ret = implementation!!.instructions.withIndex().singleOrNull { it.value.opcode == Opcode.RETURN_OBJECT }
                ?: throw PatchException("Kick: createClientBuilder no longer returns once")
            val builder = (ret.value as OneRegisterInstruction).registerA
            addInstructions(ret.index, """
                invoke-static/range { v$builder .. v$builder }, Lio/github/jordanwoodson/extension/shared/kick/KickHttp;->withInterceptor(Lokhttp3/OkHttpClient${'$'}Builder;)Lokhttp3/OkHttpClient${'$'}Builder;
                move-result-object v$builder
            """)
        }

        // The chat page script loads the sets from 7tv.io and turns the emote names in messages into images.
        kickSettings.add(
            SwitchSetting(
                "emotes_7tv",
                "7TV emotes",
                "Shows 7TV in chat, picker and autocomplete. Sends the channel owner's Kick user ID to 7tv.io, " +
                    "subscribes to live set changes at events.7tv.io and loads images from cdn.7tv.app.",
                Section.CHAT,
                default = false,
                restart = true,
            ),
        )
    }
}

context(context: BytecodePatchContext)
private fun requireOkHttpApi() {
    for ((type, methods) in OKHTTP_API) {
        val classDef = context.classDefByOrNull(type) ?: throw PatchException("Kick: OkHttp $type is missing or renamed")
        val present = classDef.methods.map { method ->
            method.name + method.parameterTypes.joinToString("", "(", ")") + method.returnType
        }.toSet()
        methods.filterNot { it in present }.forEach { throw PatchException("Kick: OkHttp $type lacks $it") }
    }
}
