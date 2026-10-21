package io.github.jordanwoodson.patches.kick.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.SHARED_EXTENSION
import io.github.jordanwoodson.patches.shared.settings.SETTINGS_CLASS
import io.github.jordanwoodson.patches.shared.settings.SettingsScreen
import org.w3c.dom.Element

internal val kickSettings = SettingsScreen("Kick")
internal const val KICK_BUNDLE = "Lio/github/jordanwoodson/extension/shared/kick/KickBundle;"

context(context: BytecodePatchContext)
internal fun Fingerprint.kickMatch(what: String): Match = matchAllOrNull()?.singleOrNull()
    ?: throw PatchException("Kick 40.32.3: expected exactly one $what")

internal val kickSettingsPatch = bytecodePatch(
    description = "Adds Lurk settings to Kick's launcher shortcut.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    extendWith(SHARED_EXTENSION)
    dependsOn(resourcePatch {
        execute {
            document("AndroidManifest.xml").use { doc ->
                val app = doc.getElementsByTagName("application").item(0) as Element
                app.appendChild(doc.createElement("activity").apply {
                    setAttribute("android:name", "io.github.jordanwoodson.extension.shared.settings.SettingsActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:label", "Lurk")
                    setAttribute("android:taskAffinity", "")
                    setAttribute("android:theme", "@android:style/Theme.DeviceDefault.Light.NoActionBar")
                })
            }
        }
    })
    execute {
        kickSettings.clear()
        ApplicationOnCreateFingerprint.kickMatch("MainApplication.onCreate").method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, $SETTINGS_CLASS->init(Landroid/content/Context;)V
                invoke-static/range { p0 .. p0 }, $KICK_BUNDLE->init(Landroid/content/Context;)V
            """,
        )
    }
    finalize {
        RegistryFingerprint.kickMatch("settings registry").method.apply {
            if (implementation!!.instructions.first().opcode != Opcode.CONST_4 ||
                implementation!!.registerCount != 1) throw PatchException("Kick: unexpected registry stub")
            replaceInstruction(0, BuilderInstruction21c(Opcode.CONST_STRING, 0,
                ImmutableStringReference(kickSettings.json())))
        }
    }
}

/** Only Hermes consumers need this hook. Native-only patch combinations never read a manifest. */
internal val kickBundleLoaderPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)
    execute {
        val match = BundleLoaderFingerprint.kickMatch("bridgeless asset bundle loader")
        if ("Lcom/facebook/react/bridge/JSBundleLoaderDelegate;" !in match.classDef.interfaces) {
            throw PatchException("Kick: unexpected bundle loader interface")
        }
        val method = match.method
        val instructions = method.implementation!!.instructions.toList()
        val index = instructions.indices.singleOrNull { index ->
            val instruction = instructions[index]
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            instruction.opcode == Opcode.INVOKE_STATIC &&
                ref?.name == "access\$loadJSBundleFromAssets" &&
                ref.definingClass == "Lcom/facebook/react/runtime/ReactInstance;" &&
                ref.parameterTypes.map { it.toString() } == listOf(
                    "Lcom/facebook/react/runtime/ReactInstance;", "Landroid/content/res/AssetManager;", "Ljava/lang/String;")
        } ?: throw PatchException("Kick: no unique native asset load")
        val call = instructions[index] as FiveRegisterInstruction
        if (method.implementation!!.registerCount != 4 || call.registerC != 0 ||
            call.registerD != 1 || call.registerE != 2 || instructions[index + 1].opcode != Opcode.RETURN_VOID) {
            throw PatchException("Kick: loader register lifetime changed")
        }
        method.addInstructionsWithLabels(index, """
            invoke-static { p1, p2 }, $KICK_BUNDLE->prepare(Landroid/content/res/AssetManager;Ljava/lang/String;)Ljava/lang/String;
            move-result-object p3
            if-eqz p3, :original
            invoke-static { p0, p3, p2 }, Lcom/facebook/react/runtime/ReactInstance;->access${'$'}loadJSBundleFromFile(Lcom/facebook/react/runtime/ReactInstance;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """, ExternalLabel("original", method.getInstruction(index)))
    }
}
