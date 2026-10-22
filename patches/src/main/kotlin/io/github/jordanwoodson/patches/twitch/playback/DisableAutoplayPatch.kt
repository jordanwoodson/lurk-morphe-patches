package io.github.jordanwoodson.patches.twitch.playback

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import io.github.jordanwoodson.patches.twitch.shared.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.invoke
import io.github.jordanwoodson.patches.twitch.shared.freeRegister
import io.github.jordanwoodson.patches.twitch.shared.requireFreeRegister
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.isEnabled
import io.github.jordanwoodson.patches.twitch.shared.hex
import io.github.jordanwoodson.patches.twitch.shared.twitchHermes
import io.github.jordanwoodson.patches.twitch.shared.twitchHermesPatch

internal const val PLAYBACK = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayback;"
private const val NEXT = "autoplay_next_video_off"

@Suppress("unused")
val disableAutoplayPatch = bytecodePatch(
    name = "Disable autoplay",
    description = "Stops the next recommended video and React Native feed clip from playing automatically, " +
        "and defaults feed previews to Never. Manual Play and saved feed preview choices keep working.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch, twitchHermesPatch)
    val feedPreviews by booleanOption(key = "feedPreviews", default = true, title = "Feed previews",
        description = "Default feed previews to Never; existing Twitch preferences still take priority.")
    val nextVideo by booleanOption(key = "nextVideo", default = true, title = "Next video",
        description = "Stop automatic recommendations and feed clip advancement; manual playback still works.")
    execute {
        listOf(AutoplayPreferenceFingerprint, AutoplayObserverFingerprint).forEach { fingerprint ->
            val method = fingerprint.twitchMatch("autoplay preference fallback").method
            val code = method.implementation!!.instructions.toList()
            val key = code.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { ref ->
                (ref as? StringReference)?.string == "autoplay_enabled"
            } }
            val fallback = code.withIndex().drop(key + 1).firstOrNull { (_, instruction) ->
                instruction.opcode == Opcode.CONST_STRING &&
                    ((instruction as ReferenceInstruction).reference as StringReference).string == ""
            } ?: throw PatchException("Twitch: missing autoplay fallback")
            val register = (fallback.value as OneRegisterInstruction).registerA
            method.addInstructions(fallback.index + 1, """
                invoke-static/range { v$register .. v$register }, $PLAYBACK->autoplayDefault(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$register
            """)
        }

        val recommendation = RecommendationAutoplayFingerprint.twitchMatch("automatic recommendation dispatch").method
        val eventCode = recommendation.implementation!!.instructions.toList()
        val event = eventCode.withIndex().single { (_, instruction) ->
            (instruction as? ReferenceInstruction)?.reference.let { (it as? StringReference)?.string == "player_rec_autoplay" }
        }
        // The event name's register is written by the original instruction there, so it is free before it.
        val scratch = (event.value as OneRegisterInstruction).registerA
        recommendation.requireFreeRegister(event.index, scratch, "automatic recommendation event")
        recommendation.requireNotBranchTarget(event.index, "automatic recommendation event")
        // The lambda returns Kotlin's Unit, which it loads into a register once at its start. Return the
        // same singleton, loaded here, rather than trusting that register to still hold it.
        val unit = (eventCode.first { it.opcode == Opcode.RETURN_OBJECT } as OneRegisterInstruction).registerA
        val load = eventCode.firstOrNull { it.opcode.setsRegister() && (it as OneRegisterInstruction).registerA == unit }
        val unitField = ((load as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { load.opcode == Opcode.SGET_OBJECT && it.type == it.definingClass }
            ?: throw PatchException("Twitch: automatic recommendation dispatch no longer returns a singleton")
        // The manual Play event has its own branch. Gate only the automatic event and its analytics.
        recommendation.addInstructionsWithLabels(event.index, """
            ${isEnabled(NEXT, "v$scratch")}
            if-eqz v$scratch, :play
            sget-object v$scratch, $unitField
            return-object v$scratch
        """, ExternalLabel("play", recommendation.getInstruction(event.index)))

        val countdown = RecommendationCountdownFingerprint.twitchMatch("recommendation countdown view").method
        val viewCode = countdown.implementation!!.instructions.toList()
        val progress = viewCode.withIndex().single { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as ReferenceInstruction).reference as? FieldReference)?.type == "Landroid/widget/ProgressBar;"
        }
        val progressField = (progress.value as ReferenceInstruction).reference
        val progressRegister = (progress.value as TwoRegisterInstruction).registerA
        val container = viewCode.single { instruction -> instruction.opcode == Opcode.IGET_OBJECT &&
            ((instruction as ReferenceInstruction).reference as? FieldReference)?.type == "Landroid/widget/LinearLayout;"
        } as TwoRegisterInstruction
        // RxJava's Disposable and the view delegate's setVisibility helper are R8-renamed; reuse the
        // references this method already calls.
        val calls = viewCode.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.distinct()
        val dispose = calls.singleOrNull { it.name == "dispose" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?: throw PatchException("Twitch: recommendation countdown no longer disposes its timer")
        val setVisibility = calls.singleOrNull { it.definingClass == "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("I") && it.returnType == "V" }
            ?: throw PatchException("Twitch: recommendation countdown visibility changed")
        val timer = viewCode.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .filter { it.definingClass == countdown.definingClass && it.type == dispose.definingClass }
            .distinct().single()
        // The check's result goes into a register that is dead here, so the stock path (check false)
        // continues with every register as it was. The hide branch returns, so it may use any low register.
        val result = countdown.freeRegister(progress.index, listOf(container.registerA), "recommendation countdown")
        val zero = container.registerA
        // The title and creator are already bound here. Preserve this branch target by replacing
        // its first instruction; jumping here must not bypass the inserted countdown check.
        countdown.replaceInstruction(progress.index,
            invokeStatic("$PLAYBACK->showManualRecommendation(Landroid/view/View;)Z", container.registerA))
        // Restore the original field load first, then bind the off branch to that real
        // instruction. A local label assembled in an inserted snippet can retain a snippet-
        // relative location and resolve to an unrelated existing instruction in this method.
        countdown.addInstructions(progress.index + 1,
            "iget-object v$progressRegister, p0, $progressField")
        countdown.addInstructionsWithLabels(progress.index + 1, """
            move-result v$result
            if-eqz v$result, :lurk_countdown_stock
            move-object/from16 v$result, p0
            iget-object v$result, v$result, $timer
            ${invoke("invoke-interface", dispose.toString(), result)}
            move-object/from16 v$result, p0
            const/4 v$zero, 0x0
            ${invoke("invoke-virtual", setVisibility.toString(), result, zero)}
            return-void
        """, ExternalLabel("lurk_countdown_stock", countdown.getInstruction(progress.index + 1)))

        // RN clip auto-advance has no saved native preference in this Android version: the
        // controller falls back to enabled=true when its unsupported native getter throws.
        // Only the automatic advance hook consumes this enabled argument; manual scroll/open
        // callbacks are separate. Preserve all instruction boundaries and branch offsets.
        twitchHermes.replace("useClipAutoAdvance", NEXT, hex("44 0c 07 01 ${twitchHermes.id8("enabled")}"),
            hex("96 0c 10 0c 0c"), defaultEnabled = nextVideo == true)
        twitchSettings.add(SwitchSetting("disable_autoplay", "No feed autoplay",
            "Defaults to Never until you choose another option in Twitch's autoplay setting.",
            Section.PLAYBACK, default = feedPreviews == true, restart = true),
            SwitchSetting(NEXT, "No autoplay of the next video",
                "Stops recommendation countdowns and automatic feed clip advancement. Manual Play still works.",
                Section.PLAYBACK, default = nextVideo == true, restart = true))
    }
}
