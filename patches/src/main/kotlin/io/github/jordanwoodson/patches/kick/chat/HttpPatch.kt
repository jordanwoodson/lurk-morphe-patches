package io.github.jordanwoodson.patches.kick.chat

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.invokeStatic

/** Smali descriptor of the extension's OkHttp interceptor. */
private const val KICK_HTTP = "Lio/github/jordanwoodson/extension/shared/kick/KickHttp;"

/** The OkHttp API KickHttp calls. R8 keeps OkHttp's names in Kick but drops methods nothing uses. */
private val OKHTTP_API = mapOf(
    "Lokhttp3/Interceptor;" to listOf("intercept(Lokhttp3/Interceptor\$Chain;)Lokhttp3/Response;"),
    "Lokhttp3/Interceptor\$Chain;" to listOf("request()Lokhttp3/Request;", "proceed(Lokhttp3/Request;)Lokhttp3/Response;"),
    "Lokhttp3/OkHttpClient\$Builder;" to listOf("addInterceptor(Lokhttp3/Interceptor;)Lokhttp3/OkHttpClient\$Builder;"),
    "Lokhttp3/Request;" to listOf("url()Lokhttp3/HttpUrl;", "method()Ljava/lang/String;"),
    "Lokhttp3/HttpUrl;" to listOf("host()Ljava/lang/String;", "encodedPath()Ljava/lang/String;"),
    "Lokhttp3/Response;" to listOf("isSuccessful()Z", "peekBody(J)Lokhttp3/ResponseBody;"),
    "Lokhttp3/ResponseBody;" to listOf("string()Ljava/lang/String;"),
)

/**
 * Every HTTP client React Native makes for Kick starts from OkHttpClientProvider.createClientBuilder(), so an
 * interceptor added there sees the app's own API requests. KickHttp adds one to the clients built while 7TV emotes
 * (it reads channel responses) or Anonymous chat (it refuses chat sends) is on; both patches depend on this one.
 */
internal val kickHttpPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        requireOkHttpApi()
        ClientBuilderFingerprint.kickMatch("OkHttpClientProvider.createClientBuilder").method.apply {
            val ret = implementation!!.instructions.withIndex().singleOrNull { it.value.opcode == Opcode.RETURN_OBJECT }
                ?: throw PatchException("Kick: createClientBuilder no longer returns once")
            val builder = (ret.value as OneRegisterInstruction).registerA
            addInstructions(ret.index, """
                ${invokeStatic("$KICK_HTTP->withInterceptor(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient\$Builder;", builder)}
                move-result-object v$builder
            """)
        }
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
