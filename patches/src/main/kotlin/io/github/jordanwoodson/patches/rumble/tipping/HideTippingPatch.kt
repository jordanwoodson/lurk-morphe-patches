package io.github.jordanwoodson.patches.rumble.tipping

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "hide_tipping"

@Suppress("unused")
val hideTippingPatch = bytecodePatch(
    name = "Hide tipping and Rants",
    description = "Hides the Tip buttons on channels, videos and Shorts, Rants (paid live chat messages) and " +
        "paying with Rumble Wallet. Rants other viewers send still show. Starts off. Turn it on in Lurk " +
        "settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, actionButtonsPatch)

    execute {
        // Tip leaves the buttons the channel and video headers may show through the shared action buttons hook
        // (RumbleInterface.actionButtons) while this switch is on.

        val returnNull = """
            const/4 v0, 0x0
            return-object v0
        """
        CreatorTipsInfoFingerprint.single("Creator.tipsInfo getter").method.addInstructionsIfEnabled(KEY, returnNull)
        VideoSourceWalletFingerprint.single("VideoSource.wallet getter").method.addInstructionsIfEnabled(KEY, returnNull)

        RantsEnabledFingerprint.single("LiveChatConfigRant.enable getter").method.addInstructionsIfEnabled(
            KEY,
            """
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """,
        )
        RantsWalletFingerprint.single("LiveChatConfigRant.walletEnabled getter").method.addInstructionsIfEnabled(
            KEY,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Hide tipping and Rants",
                "Tip buttons, Rants and paying with Rumble Wallet.",
                Section.INTERFACE,
                default = false,
            ),
        )
    }
}
