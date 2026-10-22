package io.github.jordanwoodson.patches.twitch.notifications

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

// SHA-1 of Twitch's original signing certificate (SHA-256 1338f9b0..., pinned in Constants), in the
// uppercase hex Firebase sends.
private const val ORIGINAL_CERT_SHA1 = "8C68C13822723A2B1FA844BED340031BEB1F9463"

@Suppress("unused")
val fixNotificationsPatch = bytecodePatch(
    name = "Fix notifications",
    description = "Lets the patched app register for push notifications. Firebase only accepts Twitch's " +
        "original package name and signing certificate, and refuses the re-signed app, so its registration " +
        "requests send the original ones.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
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
                val register = (instructions[call] as FiveRegisterInstruction).registerE
                method.addInstructions(call, "const-string v$register, \"$value\"")
            }
    }
}
