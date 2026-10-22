package io.github.jordanwoodson.patches.twitch.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val THEME = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchTheme;"

@Suppress("unused")
val amoledBlackPatch = bytecodePatch(
    name = "AMOLED black",
    description = "Turns the dark theme's greys black on screens drawn with Android views: the window and " +
        "navigation bar, Home, Browse and channel pages, the theatre and chat, whose backgrounds are repainted " +
        "after each layout. Screens Twitch draws with Compose (parts of settings, the emote picker and some sheets) " +
        "and pop-up sheets keep their dark grey. Only in Twitch's dark mode. Applies after a restart. " +
        "Starts off. Turn it on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The Application's onCreate registers the activity callbacks, right after the Lurk calls the settings patch
        // puts first (settings, then the React Native bundle), so settings stay initialized first. The switch is
        // read at the first activity.
        Fingerprint(
            definingClass = "Ltv/twitch/android/app/consumer/TwitchApplication;",
            name = "onCreate", returnType = "V", parameters = emptyList(),
        ).twitchMatch("application start").method.apply {
            val code = implementation!!.instructions.toList()
            var at = 0
            while (at < code.size && ((code[at] as? ReferenceInstruction)?.reference as? MethodReference)
                    ?.definingClass?.startsWith("Lio/github/jordanwoodson/extension/") == true) at++
            if (at == 0) throw PatchException("Twitch: Lurk's settings no longer start the application")
            requireNotBranchTarget(at, "application start")
            addInstructions(at, invokeStatic("$THEME->init(Landroid/app/Application;)V", parameterRegister(0)))
        }
        twitchSettings.add(SwitchSetting("amoled_black", "AMOLED black",
            "Black instead of dark grey in the dark theme, on screens drawn with Android views. Compose screens " +
                "and pop-up sheets stay dark grey.",
            Section.INTERFACE, default = false, restart = true))
    }
}
