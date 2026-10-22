package io.github.jordanwoodson.patches.twitch.account

import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
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
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val KEY = "watch_without_login"

@Suppress("unused")
val watchWithoutSigningInPatch = bytecodePatch(
    name = "Watch without signing in",
    description = "Opens Twitch without an account instead of stopping at the Log In / Sign Up screen, using " +
        "Twitch's own signed-out mode. Streams, videos and chat can be watched; following, chatting and other " +
        "account actions still ask you to sign in. Signed-in use is unchanged.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val experiment = LoggedOutExperimentFingerprint.twitchMatch("logged-out experiment").classDef
        // The getter every "main UI or login screen" decision calls: false while signed in, otherwise
        // whether the experiment assigned this device a variant. Unassigned (the default) means login.
        val method = experiment.methods.singleOrNull { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@singleOrNull false
            method.returnType == "Z" && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                instructions.size > 3 && instructions[0].opcode == Opcode.IGET_OBJECT &&
                instructions[1].opcode == Opcode.INVOKE_VIRTUAL && instructions[3].opcode == Opcode.IF_EQZ &&
                instructions.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                    ref.definingClass == "Ljava/util/Map;" && ref.name == "containsKey" } == true }
        } ?: throw PatchException("Twitch: no unique logged-out experiment getter")
        method.requireLocals(1)
        val account = method.getInstruction<ReferenceInstruction>(0).reference as FieldReference
        val isSignedIn = method.getInstruction<ReferenceInstruction>(1).reference as MethodReference
        if (isSignedIn.returnType != "Z" || isSignedIn.parameterTypes.isNotEmpty()) {
            throw PatchException("Twitch: logged-out experiment no longer checks the account first")
        }
        // Signed out with the switch on: treat the device as in the experiment, which is Twitch's
        // signed-out browsing mode. Signed in, or switched off: the original getter.
        method.addInstructionsWithLabels(0, """
            ${isEnabled(KEY, "v0")}
            if-eqz v0, :lurk_off
            iget-object v0, p0, $account
            invoke-virtual { v0 }, $isSignedIn
            move-result v0
            if-nez v0, :lurk_off
            const/4 v0, 0x1
            return v0
        """, ExternalLabel("lurk_off", method.getInstruction(0)))

        twitchSettings.add(SwitchSetting(KEY, "Watch without signing in",
            "Skip the Log In / Sign Up screen while signed out. Account actions still ask you to sign in.",
            Section.PRIVACY, restart = true))
    }
}
