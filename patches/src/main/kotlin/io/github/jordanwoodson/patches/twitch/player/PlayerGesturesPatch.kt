package io.github.jordanwoodson.patches.twitch.player

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.copyDrawablePaths
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch
import org.w3c.dom.Element

/**
 * Lurk's own rewind and forward icons: Twitch's icons with the digits taken out, made from the APK at
 * patch time. In each icon the arrow is the path that starts at the top edge; the digits sit lower.
 */
private val seekIconsPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    execute {
        numberless("ic_rewind_10", "lurk_seek_back")
        numberless("ic_forward_30", "lurk_seek_forward")
    }
}

private fun ResourcePatchContext.numberless(icon: String, name: String) = copyDrawablePaths(icon, name, "Twitch") { paths ->
    val arrows = paths.filter { path ->
        Regex("""^\s*[Mm]\s*[-\d.]+[\s,]+([-\d.]+)""").find(path.getAttribute("android:pathData"))
            ?.groupValues?.get(1)?.toFloatOrNull()?.let { it < 4f } == true
    }
    arrows.takeIf { paths.size >= 2 && arrows.size == 1 }
}

@Suppress("unused")
val playerGesturesPatch = bytecodePatch(
    name = "Player gestures",
    description = "Sets the skip interval of the video (VOD) rewind and forward buttons and the media " +
        "notification, and adds brightness (left) and volume (right) swipes on the video in landscape. " +
        "Twitch's -10/+30 seconds stay until you choose another interval in Lurk settings; swipes start off.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch, seekIconsPatch)
    execute {
        // The classic VOD overlay: -10 and +30 seconds per tap, summed and then seeked by Twitch.
        VodSeekTouchFingerprint.twitchMatch("VOD seek buttons").let { match ->
            listOf(2 to "seekForward", 0 to "seekBack").forEach { (filter, helper) ->
                val literal = match.instructionMatches[filter]
                val register = (literal.instruction as OneRegisterInstruction).registerA
                val box = match.method.getInstruction(literal.index + 1) as FiveRegisterInstruction
                if (box.registerCount != 1 || box.registerC != register) throw PatchException("Twitch: VOD seek changed")
                match.method.addInstructions(literal.index + 1, """
                    invoke-static/range { v$register .. v$register }, $PLAYER->$helper(I)I
                    move-result v$register
                """)
            }
        }
        // Their icons, drawn with "10" and "30", and talkback.
        VodSeekViewFingerprint.twitchMatch("VOD seek view").let { match ->
            val method = match.method
            listOf(2 to "bindSeekForward", 0 to "bindSeekBack").forEach { (filter, helper) ->
                val id = match.instructionMatches[filter].index
                val code = method.implementation!!.instructions
                val put = (id + 1..id + 5).firstOrNull { code[it].opcode == Opcode.IPUT_OBJECT }
                    ?: throw PatchException("Twitch: VOD seek button lookup changed")
                if (((code[put] as ReferenceInstruction).reference as FieldReference).type != "Landroid/widget/ImageView;") {
                    throw PatchException("Twitch: VOD seek buttons are no longer images")
                }
                val button = (code[put] as TwoRegisterInstruction).registerA
                method.requireNotBranchTarget(put + 1, "VOD seek button")
                method.addInstructions(put + 1,
                    "invoke-static/range { v$button .. v$button }, $PLAYER->$helper(Landroid/widget/ImageView;)V")
            }
        }

        // The vertical VOD theatre's Compose buttons: positive 10 and 30 seconds before each talkback.
        VerticalVodSeekFingerprint.twitchMatch("vertical VOD seek buttons").let { match ->
            val method = match.method
            listOf(match.instructionMatches[2].index to 30L, match.instructionMatches[0].index to 10L).forEach { (talkback, seconds) ->
                val code = method.implementation!!.instructions
                val constant = (talkback - 1 downTo maxOf(0, talkback - 4)).firstOrNull { index ->
                    code[index].opcode.let { it == Opcode.CONST_WIDE_16 || it == Opcode.CONST_WIDE_32 || it == Opcode.CONST_WIDE }
                } ?: throw PatchException("Twitch: vertical VOD seek changed")
                if ((code[constant] as WideLiteralInstruction).wideLiteral != seconds) {
                    throw PatchException("Twitch: vertical VOD seek is no longer $seconds seconds")
                }
                val register = (code[constant] as OneRegisterInstruction).registerA
                method.addInstructions(constant + 1, """
                    invoke-static/range { v$register .. v${register + 1} }, $PLAYER->seekSeconds(J)J
                    move-result-wide v$register
                """)
            }
        }

        // The media notification and session (background audio), in milliseconds.
        listOf(MediaSeekBackFingerprint to "seekBackMillis", MediaSeekForwardFingerprint to "seekForwardMillis")
            .forEach { (fingerprint, helper) ->
                val match = fingerprint.twitchMatch("media $helper")
                val literal = match.instructionMatches.first()
                val register = (literal.instruction as OneRegisterInstruction).registerA
                match.method.addInstructions(literal.index + 1, """
                    invoke-static/range { v$register .. v$register }, $PLAYER->$helper(I)I
                    move-result v$register
                """)
            }

        // Every icon that draws Twitch's numbers, in Compose, the notification and the media session.
        val back = setOf(resourceId(ResourceType.DRAWABLE, "ic_rewind_10"), resourceId(ResourceType.DRAWABLE, "skip_backwards_10"))
        val forward = setOf(resourceId(ResourceType.DRAWABLE, "ic_forward_30"), resourceId(ResourceType.DRAWABLE, "skip_forward_30"))
        var icons = 0
        SeekIconFingerprint.matchAll().forEach { match ->
            val method = match.method
            val code = method.implementation!!.instructions
            code.indices.reversed().forEach { index ->
                val instruction = code[index]
                val value = (instruction as? NarrowLiteralInstruction)?.wideLiteral
                if (instruction.opcode != Opcode.CONST || value == null || (value !in back && value !in forward)) return@forEach
                val register = (instruction as OneRegisterInstruction).registerA
                val helper = if (value in back) "seekBackIcon" else "seekForwardIcon"
                method.addInstructions(index + 1, """
                    invoke-static/range { v$register .. v$register }, $PLAYER->$helper(I)I
                    move-result v$register
                """)
                icons++
            }
        }
        if (icons != EXPECTED_ICON_USES) throw PatchException("Twitch: expected $EXPECTED_ICON_USES seek icon uses, found $icons")

        // Brightness and volume swipes on the theatre root shared by every player screen.
        listOf(TheatreInterceptFingerprint to "swipeIntercept", TheatreTouchFingerprint to "swipeTouch")
            .forEach { (fingerprint, hook) ->
                fingerprint.twitchMatch("theatre $hook").method.apply {
                    requireLocals(1)
                    addInstructionsWithLabels(0, """
                        ${invokeStatic("$PLAYER->$hook(Landroid/view/ViewGroup;Landroid/view/MotionEvent;)Z",
                            parameterRegister(0), parameterRegister(1))}
                        move-result v0
                        if-eqz v0, :stock
                        return v0
                    """, ExternalLabel("stock", getInstruction(0)))
                }
            }

        twitchSettings.add(
            ChoiceSetting("vod_skip_seconds", "VOD skip interval",
                "Rewind and forward on videos and in the media notification. Other intervals use icons " +
                    "without Twitch's 10 and 30.", Section.PLAYBACK, "0",
                listOf("Twitch's default (-10 s, +30 s)" to "0", "5 seconds" to "5", "10 seconds" to "10",
                    "15 seconds" to "15", "30 seconds" to "30")),
            SwitchSetting("swipe_controls", "Brightness and volume swipes",
                "In landscape, swipe up or down on the left of the video for brightness and on the right for " +
                    "volume. The middle keeps drag to minimize.", Section.PLAYBACK, default = false),
        )
    }
}

/** The two vertical VOD buttons, two media session actions and three in each of the two notification builders. */
private const val EXPECTED_ICON_USES = 10
