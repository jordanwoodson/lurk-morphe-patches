package io.github.jordanwoodson.patches.twitch.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
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
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch
import io.github.jordanwoodson.patches.shared.SHARED_EXTENSION
import io.github.jordanwoodson.patches.shared.settings.SETTINGS_CLASS
import io.github.jordanwoodson.patches.shared.settings.SettingsScreen
import io.github.jordanwoodson.patches.shared.settings.debugLoggingSetting
import org.w3c.dom.Element

internal val twitchSettings = SettingsScreen("Twitch")
internal const val TWITCH_BUNDLE = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchBundle;"

internal val twitchSettingsPatch = bytecodePatch(
    description = "Adds Lurk settings to Twitch's launcher shortcut.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
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
        twitchSettings.clear()
        twitchSettings.add(debugLoggingSetting)
        ApplicationOnCreateFingerprint.twitchMatch("TwitchApplication.onCreate").method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, Lio/github/jordanwoodson/extension/shared/twitch/TwitchPrivacy;->initSettingsOnce(Landroid/content/Context;)V
                invoke-static/range { p0 .. p0 }, $TWITCH_BUNDLE->init(Landroid/content/Context;)V
            """,
        )
    }
    finalize {
        RegistryFingerprint.twitchMatch("settings registry").method.apply {
            if (implementation!!.instructions.first().opcode != Opcode.CONST_4 ||
                implementation!!.registerCount != 1) throw PatchException("Twitch: unexpected registry stub")
            replaceInstruction(0, BuilderInstruction21c(Opcode.CONST_STRING, 0,
                ImmutableStringReference(twitchSettings.json())))
        }
    }
}

/** Only Hermes consumers need this hook. Native-only patch combinations never read a manifest. */
internal val twitchBundleLoaderPatch = bytecodePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val match = BundleLoaderFingerprint.twitchMatch("bridgeless asset bundle loader")
        if ("Lcom/facebook/react/bridge/JSBundleLoaderDelegate;" !in match.classDef.interfaces) {
            throw PatchException("Twitch: unexpected bundle loader interface")
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
        } ?: throw PatchException("Twitch: no unique native asset load")
        val call = instructions[index] as FiveRegisterInstruction
        if (method.implementation!!.registerCount != 4 || call.registerC != 0 ||
            call.registerD != 1 || call.registerE != 2 || instructions[index + 1].opcode != Opcode.RETURN_VOID) {
            throw PatchException("Twitch: loader register lifetime changed")
        }
        method.addInstructionsWithLabels(index, """
            invoke-static { p1, p2 }, $TWITCH_BUNDLE->prepare(Landroid/content/res/AssetManager;Ljava/lang/String;)Ljava/lang/String;
            move-result-object p3
            if-eqz p3, :original
            invoke-static { p0, p3, p2 }, Lcom/facebook/react/runtime/ReactInstance;->access${'$'}loadJSBundleFromFile(Lcom/facebook/react/runtime/ReactInstance;Ljava/lang/String;Ljava/lang/String;)V
            return-void
        """, ExternalLabel("original", method.getInstruction(index)))
    }
}
