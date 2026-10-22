package io.github.jordanwoodson.extension.shared.kick;

import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Kick's native interface helpers. Each returns Kick's own value while its setting is off. */
@SuppressWarnings("unused")
public final class KickInterface {
    private static final String EXACT_COUNTS = "exact_counts";

    private KickInterface() {
    }

    /**
     * Injection point: the start of Kick's KickIntlFormatterModule.formatNumber(number, locale, compact), which every
     * viewer and follower count goes through. Kick's JavaScript leaves compact unset, which the module treats as
     * compact ("306.1K"); with Show exact counts on it is false, so the number is written in full ("306,112").
     */
    public static Boolean compactNumbers(Boolean compact) {
        return Settings.isRegistered(EXACT_COUNTS) && Settings.isEnabled(EXACT_COUNTS) ? Boolean.FALSE : compact;
    }
}
