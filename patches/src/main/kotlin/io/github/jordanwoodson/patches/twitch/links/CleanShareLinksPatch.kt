package io.github.jordanwoodson.patches.twitch.links

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val LINKS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchLinks;"
private const val EXTRA = "Landroid/content/Intent;->putExtra(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"
private const val CLIP = "Landroid/content/ClipData;->newPlainText(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"

@Suppress("unused")
val cleanShareLinksPatch = bytecodePatch(
    name = "Clean share links",
    description = "Removes known Twitch attribution and campaign parameters from shared links and copied share text. " +
        "Keeps timestamps, clip IDs, unknown parameters and fragments. Covers native and React Native sharing.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        listOf(NativeLinkShareFingerprint to 2, NativeVideoShareFingerprint to 2, ClipVideoShareFingerprint to 1,
            GenericShareFingerprint to 1, ReactVideoShareFingerprint to 1, ReactShareFingerprint to 2).forEach { (fp, count) ->
            val method = fp.twitchMatch("share intent with $count string extras").method
            val calls = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference.toString() == EXTRA
            }
            if (calls.size != count) throw PatchException("Twitch: expected $count share extras, found ${calls.size}")
            calls.forEach { (index, instruction) ->
                val call = instruction as? FiveRegisterInstruction
                    ?: throw PatchException("Twitch: unsupported share registers")
                if (call.registerCount != 3) throw PatchException("Twitch: unexpected share arguments")
                method.replaceInstruction(index, "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                    "$LINKS->putShareExtra(Landroid/content/Intent;Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;")
            }
        }
        listOf(CopyShareLinkFingerprint, NativeClipboardFingerprint, ReactClipboardFingerprint).forEach { fp ->
            val method = fp.twitchMatch("copy-link clipboard boundary").method
            val call = method.implementation!!.instructions.withIndex().single { (_, instruction) ->
                (instruction as? ReferenceInstruction)?.reference.toString() == CLIP
            }
            val args = call.value as? FiveRegisterInstruction
                ?: throw PatchException("Twitch: unsupported clipboard registers")
            if (args.registerCount != 2) throw PatchException("Twitch: unexpected clipboard arguments")
            val text = args.registerD
            method.addInstructions(call.index, """
                invoke-static/range { v$text .. v$text }, $LINKS->cleanClipboardText(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object v$text
            """)
        }
        twitchSettings.add(SwitchSetting("clean_share_links", "Clean share links",
            "Removes known tracking parameters from Twitch links you share or copy; keeps functional parameters.",
            Section.PRIVACY))
    }
}
