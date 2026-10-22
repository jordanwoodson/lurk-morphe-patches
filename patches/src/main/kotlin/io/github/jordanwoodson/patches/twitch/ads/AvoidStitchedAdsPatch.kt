package io.github.jordanwoodson.patches.twitch.ads

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val KEY = "avoid_stitched_ads"

@Suppress("unused")
val avoidStitchedAdsPatch = bytecodePatch(
    name = "Avoid stitched ads",
    description = "While Twitch's server stitches an ad break into a live stream, plays the stream instead from " +
        "a picture-by-picture session, the kind Twitch itself shows during breaks, which gets no ads: at 360p " +
        "at most, with sound, then back to the stream's own session and quality when the break is over. The " +
        "player doesn't reload. The playback token for that session is requested without your account. If a " +
        "break runs past 4 minutes or the stream's own playlist stops answering, the stream comes back on a " +
        "new session instead; when your own token has under a minute left, that session's token is also " +
        "requested without your account, and the rest of the stream plays as a signed-out viewer's (how " +
        "that affects subscriber ad exemption, minutes watched and Drops is untested). When no " +
        "picture-by-picture session can be had, the break plays, and Mute stitched ads still mutes and covers it. Native and React " +
        "Native players; streams started while it's off are left alone, and turning it off during a break " +
        "lets that break finish on picture-by-picture first. Does nothing for streams loaded through Playlist proxy.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The URL of each request IVS sends: a media playlist whose stream moved to a new session is
        // fetched from that session's playlist instead.
        IvsHttpRequestFingerprint.twitchMatch("IVS HTTP client request").method.apply {
            val instructions = implementation!!.instructions.toList()
            val calls = instructions.withIndex().filter { (_, instruction) ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass == IVS_REQUEST &&
                    reference.name == "getUrl"
            }
            val call = calls.singleOrNull()?.index ?: throw PatchException("Twitch: IVS request URL read changed")
            val move = instructions.getOrNull(call + 1)
            if (move?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("Twitch: IVS request URL unused")
            val url = (move as OneRegisterInstruction).registerA
            val request = implementation!!.registerCount - 2 // p1
            if (url > 15 || request > 15) throw PatchException("Twitch: IVS request registers above v15")
            addInstructions(call + 2, """
                invoke-static { v$request, v$url }, $BACKUP_STREAM->requestUrl(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$url
            """)
        }

        // Each response body, before IVS reads it: a Twitch playlist is read (and changed if needed) by the
        // extension, which then hands it on itself; anything else is consumed as before.
        IvsBodyConsumerFingerprint.twitchMatch("IVS response body consumer").method.apply {
            val consumer = classDefBy(IVS_BODY_CONSUMER)
            if (consumer.fields.none { it.name == "request" && it.type == IVS_REQUEST } ||
                consumer.fields.none { it.name == "provider" && it.type == IVS_BODY_PROVIDER }) {
                throw PatchException("Twitch: IVS body consumer fields changed")
            }
            requireLocals(1)
            if (implementation!!.registerCount > 16) throw PatchException("Twitch: IVS body consumer registers above v15")
            addInstructionsWithLabels(0, """
                iget-object v0, p0, $IVS_BODY_CONSUMER->request:$IVS_REQUEST
                invoke-static { p0, v0, p1 }, $BACKUP_STREAM->consume(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :lurk_original
                return-void
            """, ExternalLabel("lurk_original", getInstruction(0)))
        }

        twitchSettings.add(SwitchSetting(KEY, "Avoid stitched ads",
            "During a stitched ad break, play the stream at up to 360p from a picture-by-picture session, which " +
                "has no ads. Applies to streams started after it's turned on; turned off, a break in progress " +
                "finishes first. Not for streams loaded through Playlist proxy.",
            Section.PLAYBACK))
    }
}
