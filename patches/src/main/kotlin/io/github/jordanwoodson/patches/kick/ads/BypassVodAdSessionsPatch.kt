package io.github.jordanwoodson.patches.kick.ads

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "bypass_vod_ad_sessions"

@Suppress("unused")
val bypassVodAdSessionsPatch = bytecodePatch(
    name = "Bypass VOD ad sessions",
    description = "Experimental: uses the original VOD URL instead of requesting a separate ad-session manifest. " +
        "Does not remove ads stitched into live streams. Enable in Lurk settings after patching.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // The asynchronous wrapper must finish with an object. Both the initial-VOD loader and
        // live-rewind loader fall back to their original VOD URL when manifestUrl is absent.
        // Replace APIRequest.send(request) with {}, keeping the generator state transitions and
        // Promise completion. This does not touch postPlayerTelemetry, which acquires playback URLs.
        // Its compiler-generated wrapper has the same name, so exactly one of the functions with that
        // name must build the request: api = APIRequest; send = api.send; request = {method: 'GET', url,
        // parse: true}; send.call(api, request). Only the Call2 changes: NewObject and a Mov of the same
        // length leave {} in the call's result register.
        val (function, run) = kickHermes.sequence(
            "VOD session request", kickHermes.functionsNamed("?anon_0_getVodSession"),
            listOf(
                kickHermes.reads("APIRequest"),
                kickHermes.reads("send"),
                kickHermes.creates("method", "url", "parse"),
                { it.opcode == HermesOp.PUT_OWN_BY_SLOT_IDX },
                { it.opcode == HermesOp.CALL2 },
            ),
        ) { (api, send, request, put, call) ->
            send.u8(2) == api.u8(1) && put.u8(1) == request.u8(1) && call.u8(2) == send.u8(1) &&
                call.u8(3) == api.u8(1) && call.u8(4) == request.u8(1)
        }
        val call = run.last()
        val result = call.bytes[1]
        kickHermes.replaceInstruction(function, KEY, call,
            byteArrayOf(HermesOp.NEW_OBJECT.toByte(), result, HermesOp.MOV.toByte(), result, result),
            defaultEnabled = false)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Bypass VOD ad sessions (experimental)",
                "Use the original VOD URL. Turn off if VOD or live rewind fails. Live ads are unaffected.",
                Section.PRIVACY,
                default = false,
                restart = true,
            ),
        )
    }
}
