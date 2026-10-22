package io.github.jordanwoodson.patches.twitch.startup

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val LANDING = "Ltv/twitch/android/app/core/LandingActivity;"
private const val STARTUP = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchStartup;"

private object LandingCreateFingerprint : Fingerprint(
    definingClass = LANDING, name = "onCreate", returnType = "V", parameters = listOf("Landroid/os/Bundle;"),
)

// Parses the launch intent and routes to the main screen, a link's destination or the login screen.
// Twitch calls it once Branch's link lookup and the install referrer have both answered.
private object LandingRouteFingerprint : Fingerprint(
    definingClass = LANDING, returnType = "V", parameters = emptyList(), strings = listOf("parse_launcher_intent"),
)

@Suppress("unused")
val fixStuckStartScreenPatch = bytecodePatch(
    name = "Fix stuck start screen",
    description = "Opens Twitch when its start screen is still waiting after 5 seconds. Twitch waits for Branch's " +
        "link lookup before it routes, and when that never answers, it stays on a blank start screen until it's " +
        "closed, unpatched Twitch included. On by default; the switch is in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val route = LandingRouteFingerprint.twitchMatch("start screen routing").method
        // Route once per start screen: when Branch answers after the fallback, its route is skipped.
        route.requireLocals(1)
        route.addInstructionsWithLabels(0, """
            ${invokeStatic("$STARTUP->firstRoute(Landroid/app/Activity;)Z", route.parameterRegister(0))}
            move-result v0
            if-nez v0, :lurk_route
            return-void
        """, ExternalLabel("lurk_route", route.getInstruction(0)))
        val create = LandingCreateFingerprint.twitchMatch("start screen creation").method
        // onCreate has many locals, so `this` is above v15: copy it down for the two-register call.
        create.requireLocals(2)
        create.addInstructions(0, """
            move-object/from16 v0, p0
            const-string v1, "${route.name}"
            ${invokeStatic("$STARTUP->watchStart(Landroid/app/Activity;Ljava/lang/String;)V", 0, 1)}
        """)
        twitchSettings.add(SwitchSetting("fix_stuck_start", "Fix stuck start screen",
            "Open Twitch when its start screen is still waiting for Branch's link lookup after 5 seconds.",
            Section.INTERFACE))
    }
}
