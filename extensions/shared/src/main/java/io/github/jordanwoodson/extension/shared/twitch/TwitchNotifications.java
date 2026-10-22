package io.github.jordanwoodson.extension.shared.twitch;

import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * "Fix notifications": Firebase Installations only registers Twitch's original package name and signing
 * certificate, which a re-signed app doesn't have, so push registration fails. While the switch is on,
 * the registration request sends the original values instead of the patched app's.
 */
@SuppressWarnings("unused")
public final class TwitchNotifications {
    static final String KEY = "fix_notifications";

    private TwitchNotifications() {
    }

    /** Injection point: a Firebase Installations header value, {@code original} while the switch is on. */
    public static String firebaseHeader(String stock, String original) {
        return Settings.isEnabled(KEY) ? original : stock;
    }
}
