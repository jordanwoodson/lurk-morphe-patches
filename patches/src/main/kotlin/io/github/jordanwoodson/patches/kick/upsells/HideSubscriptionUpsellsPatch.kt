package io.github.jordanwoodson.patches.kick.upsells

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "hide_subscription_upsells"

@Suppress("unused")
val hideSubscriptionUpsellsPatch = bytecodePatch(
    name = "Hide subscription upsells",
    description = "Hides channel Subscribe and Gift a Sub buttons and the leaderboard gift-subscription prompt. " +
        "Keeps the existing subscribed indicator. Starts off. Turn it on in Lurk settings > Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        hideSubscribeButton()
        kickHermes.returnNull("ChannelGiftSubButton", KEY, defaultEnabled = false)
        kickHermes.returnNull("LeaderboardSubsFooter", KEY, defaultEnabled = false)
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Hide subscription upsells",
                "Hide channel subscription buttons and the leaderboard gift prompt.",
                Section.INTERFACE,
                default = false,
                restart = true,
            ),
        )
    }
}

/**
 * `ChannelSubButton` ends with `if (x.subscription) <subscribed icon> else <Subscribe button>`: the read, a forward
 * JmpTrueLong on its result to the subscribed branch, then the unsubscribed path, whose first instruction (a
 * GetByIndex from the component's memo cache) becomes `return null`. All hooks have run by then, so React's hook
 * order is unchanged; the subscribed icon, subscription state and access stay as they are. Found by the property
 * and the register flow, not by registers, cache slots or offsets.
 */
private fun hideSubscribeButton() {
    val button = kickHermes.function("ChannelSubButton")
    val (_, run) = kickHermes.sequence(
        "unsubscribed branch", listOf(button),
        listOf(
            kickHermes.reads("subscription"),
            { it.opcode == HermesOp.JMP_TRUE_LONG },
            { it.opcode == HermesOp.GET_BY_INDEX },
        ),
    ) { (read, jump, _) -> jump.u8(5) == read.u8(1) && jump.target()!! > jump.offset }
    val first = run.last()
    val register = first.u8(1)
    if (register < button.numberRegisters + button.nonPointerRegisters) {
        throw PatchException("Kick: ChannelSubButton's unsubscribed path starts in a register that can't hold null")
    }
    kickHermes.replaceInstruction(button, KEY, first,
        byteArrayOf(HermesOp.LOAD_CONST_NULL.toByte(), register.toByte(), HermesOp.RET.toByte(), register.toByte()),
        defaultEnabled = false)
}
