package io.github.jordanwoodson.patches.twitch.consent

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val blockCookieNoticePatch = bytecodePatch(
    name = "Block cookie notice",
    description = "Hides Twitch's automatic cookie consent notice without accepting it or changing saved " +
        "vendor consent. Manual consent settings stay available. Use Disable analytics to block tracking.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        val options = ConsentOptionsFingerprint.twitchMatch("cookie notice presentation options").classDef
        val constructor = options.methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.size == 6 &&
                it.parameterTypes.map { type -> type.toString() }.let { types ->
                    types[0] == "Z" && types[1].startsWith("L") && types.drop(2) == List(4) { "Z" }
                }
        } ?: throw PatchException("Twitch: cookie notice options constructor changed")
        Locators.record("twitch.block_cookie_notice.options_constructor", constructor)
        // p3 is shouldShowNotification; the next boolean enables manual settings. Do not suppress
        // the consent query, mark consent accepted, alter vendor status or skip age restrictions.
        constructor.addInstructions(0, """
            invoke-static/range { p3 .. p3 }, Lio/github/jordanwoodson/extension/shared/twitch/TwitchPrivacy;->showCookieNotice(Z)Z
            move-result p3
        """)
        twitchSettings.add(SwitchSetting("block_cookie_notice", "Block cookie notice",
            "Hide the automatic notice without accepting consent. Manual consent settings remain available.", Section.PRIVACY))
    }
}
