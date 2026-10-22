package io.github.jordanwoodson.patches.rumble.player

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PLAYER
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.compose.calls
import io.github.jordanwoodson.patches.shared.compose.mutableTwin
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "keep_screen_on_paused"

@Suppress("unused")
val keepScreenOnPausedPatch = bytecodePatch(
    name = "Keep screen on while paused",
    description = "Keeps the screen on while a video is paused in the player, as it is during playback. When " +
        "the video ends or you leave the player, the screen can turn off again. Starts off. Turn it on in Lurk " +
        "settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        val helper = KeepScreenOnFingerprint.single("keep screen on helper").method
        val helperCall = "${helper.definingClass}->${helper.name}(Landroid/content/Context;Z)V"
        val paused = classDefByStrings("Paused(isBuffering=", StringComparisonType.EQUALS).singleOrNull()
            ?: throw PatchException("Rumble: expected one Paused player state")
        Locators.record("rumble.keep_screen_on_paused.paused_state", paused.type)

        // The player's state effect turns the screen on unless it is paused or finished (outside an ad).
        val lambdas = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            classDef.methods.filter { method ->
                method.calls(helperCall) &&
                    method.implementation!!.instructions.any {
                        it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as TypeReference).type == paused.type
                    }
            }.forEach { lambdas += classDef to it }
        }
        val (lambdaClass, lambdaMethod) = lambdas.singleOrNull()
            ?: throw PatchException("Rumble: expected one player state effect, found ${lambdas.size}")
        if (AccessFlags.STATIC.isSet(lambdaMethod.accessFlags)) throw PatchException("Rumble: unexpected state effect")
        val method = mutableTwin(lambdaClass, lambdaMethod)
        val code = method.implementation!!.instructions.toList()

        // The state is read as reader(this.field) right before the Paused check.
        val check = code.indexOfFirst {
            it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as TypeReference).type == paused.type
        }
        val reader = code[check - 2]
        val field = code[check - 3]
        if (code[check - 1].opcode != Opcode.MOVE_RESULT_OBJECT || reader.opcode != Opcode.INVOKE_STATIC ||
            field.opcode != Opcode.IGET_OBJECT || (field as TwoRegisterInstruction).registerB != method.parameterRegister(0)
        ) {
            throw PatchException("Rumble: unexpected player state read")
        }
        // The state class and its reader are package-private, so the effect reads the state itself and passes
        // it on. After the helper call the method only returns Unit, so any other low register is free there.
        val call = code.indices.single {
            code[it] is ReferenceInstruction && (code[it] as ReferenceInstruction).reference.toString() == helperCall
        }
        val context = (code[call] as FiveRegisterInstruction).registerC
        val keep = (code[call] as FiveRegisterInstruction).registerD
        val unit = code.getOrNull(call + 1)
        val returned = code.getOrNull(call + 2)
        if (unit?.opcode != Opcode.SGET_OBJECT || returned?.opcode != Opcode.RETURN_OBJECT) {
            throw PatchException("Rumble: unexpected code after the keep screen on call")
        }
        val scratch = (0..15).first { it != context && it != keep }
        val self = method.parameterRegister(0)
        if (keep > 15 || self > 15) throw PatchException("Rumble: unexpected registers in the state effect")
        method.addInstructions(
            call,
            """
                iget-object v$scratch, v$self, ${(field as ReferenceInstruction).reference as FieldReference}
                invoke-static { v$scratch }, ${(reader as ReferenceInstruction).reference}
                move-result-object v$scratch
                invoke-static { v$keep, v$scratch }, $RUMBLE_PLAYER->keepScreenOn(ZLjava/lang/Object;)Z
                move-result v$keep
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Keep screen on while paused",
                "The screen stays on while a video is paused in the player.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
