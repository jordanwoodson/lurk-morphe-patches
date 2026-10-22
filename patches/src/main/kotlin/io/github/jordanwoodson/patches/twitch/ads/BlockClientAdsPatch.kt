package io.github.jordanwoodson.patches.twitch.ads

import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.isEnabled
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.invoke
import io.github.jordanwoodson.patches.twitch.shared.freeRegister
import io.github.jordanwoodson.patches.twitch.shared.localCount
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val blockClientAdsPatch = bytecodePatch(
    name = "Block client ads",
    description = "Declines ads handled by Twitch's native client ad eligibility check using its existing " +
        "not eligible result. Does not remove ads stitched into streams by the server or guarantee ad-free playback.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val method = ClientAdEligibilityFingerprint.twitchMatch("client ad eligibility override").method
        val instructions = method.implementation!!.instructions.toList()
        // R8 merged unrelated Single emitters (authentication, images) into this class. Insert only
        // at the ad case's first SharedPreferences read, reached by its original switch target.
        val start = instructions.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type == "Landroid/content/SharedPreferences;"
        }.singleOrNull()?.index ?: throw PatchException("Twitch: no unique ad preferences read")
        // The ad request that holds those preferences.
        ((instructions[start] as ReferenceInstruction).reference as FieldReference).definingClass
            .let { Locators.record("twitch.block_client_ads.request", it) }
        if (start < 2 || instructions[start - 1].opcode != Opcode.CHECK_CAST || instructions[start - 2].opcode != Opcode.CHECK_CAST) {
            throw PatchException("Twitch: ad eligibility synthetic branch changed")
        }
        // The emitter's onSuccess, as the other cases call it (R8 renames both class and method).
        val emitter = method.parameterTypes.single().toString()
        val onSuccess = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.definingClass == emitter && it.returnType == "V" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;") }
            .distinct().singleOrNull() ?: throw PatchException("Twitch: ad eligibility emitter changed")
        method.requireNotBranchTarget(start, "ad eligibility preferences read")
        // With the switch off the original code continues, so the switch is read into a register that is
        // dead here. The decline branch returns, so its two other registers only need to be low locals.
        val flag = method.freeRegister(start, emptyList(), "client ad eligibility switch")
        val (value, emitterCopy) = (0 until minOf(method.localCount(), 16)).filter { it != flag }.take(2)
            .takeIf { it.size == 2 } ?: throw PatchException("Twitch: ad eligibility has too few low locals")
        method.addInstructionsWithLabels(start, """
            ${isEnabled("block_client_ads", "v$flag")}
            if-eqz v$flag, :lurk_ads_off
            sget-object v$flag, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            new-instance v$value, Ltv/twitch/android/util/Optional;
            invoke-direct { v$value, v$flag }, Ltv/twitch/android/util/Optional;-><init>(Ljava/lang/Object;)V
            move-object/from16 v$emitterCopy, p1
            ${invoke("invoke-virtual", onSuccess.toString(), emitterCopy, value)}
            return-void
        """, ExternalLabel("lurk_ads_off", method.getInstruction(start)))
        twitchSettings.add(SwitchSetting("block_client_ads", "Block client ads",
            "Decline native client ads. Server-stitched stream ads may still play.", Section.PRIVACY))
    }
}
