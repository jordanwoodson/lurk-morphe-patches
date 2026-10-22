package io.github.jordanwoodson.patches.kick.counts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "exact_counts"

// Kick's own Expo module keeps its names: its JavaScript calls requireNativeModule("KickIntlFormatter").
internal object FormatNumberFingerprint : Fingerprint(
    definingClass = "Lexpo/modules/kickintlformatter/KickIntlFormatterModule;", name = "formatNumber",
    returnType = "Ljava/lang/String;", parameters = listOf("D", "Ljava/lang/String;", "Ljava/lang/Boolean;"),
)

@Suppress("unused")
val exactCountsPatch = bytecodePatch(
    name = "Show exact counts",
    description = "Shows viewer and follower counts in full, such as 306,112 followers instead of 306.1K. Starts " +
        "off. Turn it on in Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        // Every count Kick abbreviates goes through its native formatter, which writes a compact number unless its
        // third argument is false; Kick's JavaScript leaves it unset. The switch makes it false.
        FormatNumberFingerprint.kickMatch("KickIntlFormatter.formatNumber").method.apply {
            Locators.record("kick.exact_counts.format_number", this)
            val compact = parameterRegister(3) // this, number (two registers), locale, compact
            addInstructions(0, """
                invoke-static/range { v$compact .. v$compact }, Lio/github/jordanwoodson/extension/shared/kick/KickInterface;->compactNumbers(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                move-result-object v$compact
            """)
        }
        kickSettings.add(
            SwitchSetting(KEY, "Show exact counts", "Viewer and follower counts in full instead of rounded.",
                Section.INTERFACE, default = false),
        )
    }
}
