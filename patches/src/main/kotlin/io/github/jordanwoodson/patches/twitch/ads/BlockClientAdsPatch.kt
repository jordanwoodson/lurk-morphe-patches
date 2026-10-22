package io.github.jordanwoodson.patches.twitch.ads

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
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.invoke
import io.github.jordanwoodson.patches.shared.requireLowRegisters
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
        method.requireLocals(3)
        val instructions = method.implementation!!.instructions.toList()
        // R8 merged unrelated Single emitters (authentication, images) into this class. Insert only
        // at the ad case's first SharedPreferences read, reached by its original switch target.
        val start = instructions.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.type == "Landroid/content/SharedPreferences;"
        }.singleOrNull()?.index ?: throw PatchException("Twitch: no unique ad preferences read")
        if (start < 2 || instructions[start - 1].opcode != Opcode.CHECK_CAST || instructions[start - 2].opcode != Opcode.CHECK_CAST) {
            throw PatchException("Twitch: ad eligibility synthetic branch changed")
        }
        // The emitter's onSuccess, as the other cases call it (R8 renames both class and method).
        val emitter = method.parameterTypes.single().toString()
        val onSuccess = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.definingClass == emitter && it.returnType == "V" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;") }
            .distinct().singleOrNull() ?: throw PatchException("Twitch: ad eligibility emitter changed")
        method.requireLowRegisters("client ad eligibility emitter", method.parameterRegister(1))
        method.addInstructionsWithLabels(start, """
            ${isEnabled("block_client_ads", "v0")}
            if-eqz v0, :lurk_ads_off
            sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            new-instance v2, Ltv/twitch/android/util/Optional;
            invoke-direct { v2, v0 }, Ltv/twitch/android/util/Optional;-><init>(Ljava/lang/Object;)V
            ${invoke("invoke-virtual", onSuccess.toString(), method.parameterRegister(1), 2)}
            return-void
        """, ExternalLabel("lurk_ads_off", method.getInstruction(start)))
        twitchSettings.add(SwitchSetting("block_client_ads", "Block client ads",
            "Decline native client ads. Server-stitched stream ads may still play.", Section.PRIVACY))
    }
}
