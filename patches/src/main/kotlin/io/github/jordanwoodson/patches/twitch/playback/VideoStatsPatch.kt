package io.github.jordanwoodson.patches.twitch.playback

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val STATS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchStats;"

@Suppress("unused")
val videoStatsPatch = bytecodePatch(
    name = "Show video stats",
    description = "Adds the video stats button Twitch shows only to its staff to the native player: latency to " +
        "the broadcaster, bitrate, buffer, dropped frames, codecs and server, and for live streams the stream's " +
        "uptime, from the stream age Twitch's playlist already carries. Everything shown is local to the player.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        VideoDebugPanelFingerprint.twitchMatch("video stats panel gate").method
            .addInstructionsIfEnabled("video_stats", "const/4 v0, 0x1\nreturn v0")
        hookUptime()
        twitchSettings.add(SwitchSetting("video_stats", "Show video stats",
            "A stats button in the player: latency, bitrate, buffer, dropped frames and a live stream's uptime.",
            Section.PLAYBACK))
    }
}

/**
 * Uptime: the master playlist parser hands the playlist to the extension, which keeps the stream's start by
 * serving ID; the panel's rows get an Uptime row right before Twitch turns them into list items.
 */
private fun BytecodePatchContext.hookUptime() {
    MasterPlaylistFingerprint.twitchMatch("master playlist parser").method.apply {
        requireNotBranchTarget(0, "master playlist parser")
        addInstructions(0, invokeStatic("$STATS->masterPlaylist(Ljava/lang/String;)V", parameterRegister(0)))
    }

    val rows = VideoStatsRowsFingerprint.twitchMatch("video stats rows")
    val init = rows.instructionMatches[1]
    val rowType = ((init.instruction as ReferenceInstruction).reference as MethodReference).definingClass
    val rowClass = classDefBy(rowType)
    if (rowClass.fields.count { it.type == "Ljava/lang/String;" } != 2 || rowClass.fields.count() != 2) {
        throw PatchException("Twitch: the video stats row is no longer a label and a value")
    }
    rows.method.apply {
        val code = implementation!!.instructions.toList()
        // The new row goes into the list with ArrayList.add; Twitch then iterates that list into list items.
        val row = (init.instruction as FiveRegisterInstruction).registerC
        val add = (init.index + 1 until code.size).firstOrNull { i ->
            val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
            code[i].opcode == Opcode.INVOKE_VIRTUAL && ref?.toString() == "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z" &&
                (code[i] as FiveRegisterInstruction).registerD == row
        } ?: throw PatchException("Twitch: the video stats rows aren't collected in a list")
        val list = (code[add] as FiveRegisterInstruction).registerC
        val iterate = (add + 1 until code.size).firstOrNull { i ->
            val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
            code[i].opcode == Opcode.INVOKE_VIRTUAL && ref?.toString() == "Ljava/util/ArrayList;->iterator()Ljava/util/Iterator;" &&
                (code[i] as FiveRegisterInstruction).registerC == list
        } ?: throw PatchException("Twitch: the video stats rows are no longer listed")
        requireNotBranchTarget(iterate, "video stats rows")
        addInstructions(iterate, invokeStatic("$STATS->statsRows(Ljava/util/List;)V", list))
    }
    bridge(STATS, "statsRow", """
        new-instance v0, $rowType
        invoke-direct { v0, p0, p1 }, $rowType-><init>(Ljava/lang/String;Ljava/lang/String;)V
        return-object v0
    """, locals = 1)
}
