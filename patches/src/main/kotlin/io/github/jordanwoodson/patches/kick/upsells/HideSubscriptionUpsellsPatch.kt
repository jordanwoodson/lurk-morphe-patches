package io.github.jordanwoodson.patches.kick.upsells

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.hex
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "hide_subscription_upsells"

@Suppress("unused")
val hideSubscriptionUpsellsPatch = bytecodePatch(
    name = "Hide subscription upsells",
    description = "Hides channel Subscribe and Gift a Sub buttons and the leaderboard gift-subscription prompt. " +
        "Keeps the existing subscribed indicator.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // The branch to the existing subscribed icon stays intact. Only the unsubscribed path
        // returns null, after all hooks have run; subscription state and access are unchanged.
        // `if (x.subscription) <subscribed icon>`: the read, its JmpTrueLong, then the unsubscribed path's
        // first instruction (GetByIndex), which becomes `return null`.
        val subscribed = kickHermes.getById(4, 9, 22, "subscription") + hex("b2 a9 00 00 00 04")
        kickHermes.replace(
            "ChannelSubButton",
            KEY,
            subscribed + hex("5e 04 06 0c"),
            subscribed + hex("94 04 76 04"),
        )
        kickHermes.returnNull("ChannelGiftSubButton", KEY)
        kickHermes.returnNull("LeaderboardSubsFooter", KEY)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Hide subscription upsells",
                "Hide channel subscription buttons and the leaderboard gift prompt.",
                Section.INTERFACE,
                restart = true,
            ),
        )
    }
}
