package io.github.jordanwoodson.patches.twitch.notifications

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.freeRegister
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

// SHA-1 of Twitch's original signing certificate (SHA-256 1338f9b0..., pinned in Constants), in the
// uppercase hex Firebase sends.
private const val ORIGINAL_CERT_SHA1 = "8C68C13822723A2B1FA844BED340031BEB1F9463"

private const val KEY = "fix_notifications"
private const val NOTIFICATIONS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchNotifications;"

@Suppress("unused")
val fixNotificationsPatch = bytecodePatch(
    name = "Fix notifications",
    description = "Lets the patched app register for push notifications. Firebase only accepts Twitch's " +
        "original package name and signing certificate, and refuses the re-signed app, so its registration " +
        "requests send the original ones, which are what unpatched Twitch sends. Registration was checked; " +
        "delivering a notification needs an account and wasn't. On by default; the switch is in Lurk " +
        "settings > Interface and applies after a restart.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val method = FirebaseInstallationsRequestFingerprint.twitchMatch("Firebase Installations request").method
        // Replace the value register of the two header calls, right before each call, so every path
        // that computes the value (including its failure fallbacks) reaches the replacement.
        listOf("X-Android-Package" to COMPATIBILITY_TWITCH.packageName!!, "X-Android-Cert" to ORIGINAL_CERT_SHA1)
            .forEach { (header, value) ->
                val instructions = method.implementation!!.instructions.toList()
                val name = instructions.indexOfFirst {
                    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == header
                }
                val call = (name + 1 until instructions.size).firstOrNull { index ->
                    val ref = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
                    ref?.name == "addRequestProperty"
                } ?: throw PatchException("Twitch: Firebase Installations no longer sends $header")
                val arguments = instructions[call] as FiveRegisterInstruction
                val stock = arguments.registerE
                method.requireNotBranchTarget(call, "Firebase $header")
                // The original value goes into a register that is dead at the call; the switch picks one.
                val original = method.freeRegister(call, listOf(arguments.registerC, arguments.registerD, stock),
                    "Firebase $header")
                method.addInstructions(call, """
                    const-string v$original, "$value"
                    ${invokeStatic("$NOTIFICATIONS->firebaseHeader(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", stock, original)}
                    move-result-object v$stock
                """)
            }
        twitchSettings.add(SwitchSetting(KEY, "Fix notifications",
            "Register for push notifications with Twitch's original package name and signing certificate, which " +
                "Firebase requires.", Section.INTERFACE, restart = true))
    }
}
