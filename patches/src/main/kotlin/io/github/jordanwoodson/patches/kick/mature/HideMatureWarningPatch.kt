package io.github.jordanwoodson.patches.kick.mature

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesBundle.Constant
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "hide_mature_warning"

@Suppress("unused")
val hideMatureWarningPatch = bytecodePatch(
    name = "Hide mature-content warning",
    description = "Skips Kick's \"18+ Mature Content Warning\" on mature streams when you're signed out, as if you " +
        "had tapped \"I am 18+\". Signed in, Kick goes by your account's own age and this changes nothing; age " +
        "checks Kick runs where the law requires them aren't touched. Starts off. Turn it on in Lurk settings > " +
        "Interface." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // useIsOver18() returns the signed-in account's is_over_18, or else the preferences store's isOver18, which
        // the warning's "I am 18+" sets. Only that preference's selector, `state => state.isOver18`, returns true.
        val selector = kickHermes.functionUsing("preferences isOver18 selector", reads = listOf("isOver18"))
        val code = kickHermes.instructions(selector)
        if (code.map { it.opcode } != listOf(0x89, HermesOp.GET_BY_ID, HermesOp.RET) ||
            code[1].u8(1) != code[2].u8(1)) {
            throw PatchException("Kick: the isOver18 selector is no longer `state => state.isOver18`")
        }
        kickHermes.returnAtEntry(selector, KEY, Constant.TRUE, defaultEnabled = false)
        kickSettings.add(
            SwitchSetting(KEY, "Hide mature-content warning",
                "Skip the 18+ warning on mature streams when signed out. Signed in, your account's age applies.",
                Section.INTERFACE, default = false, restart = true),
        )
    }
}
