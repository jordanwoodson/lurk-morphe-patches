package io.github.jordanwoodson.patches.twitch.navigation

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val INTERFACE = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchInterface;"

@Suppress("unused")
val defaultStartupTabPatch = bytecodePatch(
    name = "Default startup tab",
    description = "Adds a choice of the screen Twitch opens on instead of Home: Browse, Following or Activity. " +
        "Links and notifications still open what they point to.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val method = DestinationRouterFingerprint.twitchMatch("destination router").method
        // The destination enum is the third parameter. Only its Default value (a plain launch) is replaced,
        // by an entry of the same enum looked up by name, so links keep their own destination.
        val destination = method.parameterTypes[2].toString()
        method.addInstructions(0, """
            invoke-static/range { p3 .. p3 }, $INTERFACE->startDestination(Ljava/lang/Enum;)Ljava/lang/Enum;
            move-result-object p3
            check-cast p3, $destination
        """)
        twitchSettings.add(ChoiceSetting("default_tab", "Default startup tab",
            "The screen Twitch opens on. Following needs an account.", Section.INTERFACE, "0",
            listOf("Home" to "0", "Browse" to "1", "Following" to "2", "Activity" to "3")))
    }
}
