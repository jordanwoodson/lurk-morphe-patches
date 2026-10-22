package io.github.jordanwoodson.extension.shared.twitch;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.Network;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Preference fallbacks only: Twitch continues to read and write the user's own choices. */
@SuppressWarnings("unused")
public final class TwitchPlayback {
    /** Lurk's Source choice: the HLS group ID of Twitch's source rendition, which is never a name. */
    private static final String SOURCE = "chunked";
    private static final String SOURCE_SUFFIX = " (source)";

    private TwitchPlayback() {}

    public static String autoplayDefault(String original) {
        return Settings.isEnabled("disable_autoplay") ? "never" : original;
    }

    /** Keep the native recommendation's manual Play card, without the countdown controls. */
    public static boolean showManualRecommendation(View container) {
        if (!Settings.isEnabled("autoplay_next_video_off")) return false;
        hideCountdown(container);
        container.setVisibility(View.VISIBLE);
        return true;
    }

    private static void hideCountdown(View view) {
        if (view.getId() != View.NO_ID) {
            try {
                String name = view.getResources().getResourceEntryName(view.getId());
                if (name.equals("autoplay_progress") || name.equals("autoplay_time_remaining")
                        || name.equals("cancel_autoplay")) view.setVisibility(View.GONE);
            } catch (RuntimeException ignored) {
                // Unnamed wrapper views still have children worth inspecting.
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideCountdown(group.getChildAt(i));
        }
    }

    public static String qualityDefault(String original) {
        String quality = selectedQuality();
        return quality == null ? original : quality;
    }

    public static String qualityPreferenceDefault(String original, String suffix) {
        String quality = selectedQuality();
        if (quality == null) return original;
        return "_clip".equals(suffix) ? clipQuality(quality) : quality;
    }

    /** Clips use ClipQuality's strings, without frame rates; Twitch picks the closest one a clip has. */
    private static String clipQuality(String quality) {
        if (SOURCE.equals(quality)) return "source";
        if (quality.equals("160p")) return "240";
        int p = quality.indexOf('p');
        return p > 0 ? quality.substring(0, p) : quality;
    }

    /**
     * Called only for absent AsyncStorage rows, before the existing result/callback is completed. React
     * Native needs a rendition's exact name, and the source rendition's name changes from stream to
     * stream, so Source leaves the row absent.
     */
    public static String storageQualityDefault(String key) {
        if (!"preferred-video-quality".equals(key)) return null;
        String quality = selectedQuality();
        return quality == null || SOURCE.equals(quality) ? null : "\"" + quality + "\"";
    }

    /**
     * Injection point: the start of the native player's select-by-name, which matches names exactly.
     * Twitch names the source rendition like "1080p60 (source)", so turn Lurk's Source choice, or a named
     * choice that is the source, into that full name. Anything else, including requests made before the
     * qualities have loaded (Twitch retries those), goes through unchanged.
     */
    public static String playerQuality(String requested, Object player) {
        if (requested == null || player == null || selectedQuality() == null) return requested;
        try {
            ClassLoader loader = player.getClass().getClassLoader();
            Method qualities = Class.forName("com.amazonaws.ivs.player.Player", false, loader).getMethod("getQualities");
            Method name = Class.forName("com.amazonaws.ivs.player.Quality", false, loader).getMethod("getName");
            String source = null;
            for (Object quality : (Collection<?>) qualities.invoke(player)) {
                String candidate = quality == null ? null : (String) name.invoke(quality);
                if (candidate == null) continue;
                if (candidate.equalsIgnoreCase(requested)) return requested;
                if (candidate.toLowerCase(Locale.ROOT).endsWith(SOURCE_SUFFIX)) source = candidate;
            }
            if (source == null) return requested;
            String sourceName = source.substring(0, source.length() - SOURCE_SUFFIX.length());
            return SOURCE.equals(requested) || sourceName.equalsIgnoreCase(requested) ? source : requested;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return requested;
        }
    }

    /**
     * The chosen default rendition, or null for Twitch's own. On a metered network (mobile data, or Wi-Fi
     * marked as metered) the "On mobile data" choice replaces it when that one is lower; Twitch's own default
     * (Auto) counts as the highest.
     */
    private static String selectedQuality() {
        int quality = Settings.getInt("video_quality");
        int metered = Settings.getInt("video_quality_metered");
        boolean mobile = name(metered) != null && rank(metered) < rank(quality) && Network.isMetered(Settings.appContext());
        if (mobile != meteredLogged) {
            meteredLogged = mobile;
            Logger.d(mobile ? "quality: metered network, " + name(metered) + " instead of " + name(quality)
                    : "quality: the default applies");
        }
        return name(mobile ? metered : quality);
    }

    /** Whether the last quality asked for was the mobile one, so the log says when that changes. */
    private static volatile boolean meteredLogged;

    /** How high a quality choice is: Source and Twitch's own default (Auto, or anything unknown) are highest. */
    private static int rank(int quality) {
        switch (quality) {
            case 108060: return 6;
            case 1080: return 5;
            case 72060: return 4;
            case 720: return 3;
            case 480: return 2;
            case 360: return 1;
            case 160: return 0;
            default: return 7;
        }
    }

    private static String name(int quality) {
        switch (quality) {
            case 1: return SOURCE;
            case 108060: return "1080p60";
            case 1080: return "1080p";
            case 72060: return "720p60";
            case 720: return "720p";
            case 480: return "480p";
            case 360: return "360p";
            case 160: return "160p";
            default: return null;
        }
    }

    /**
     * Twitch's "Continue Playing Audio in the Background" value when none is saved ({@code never} in
     * Twitch). A choice made in Twitch's settings is saved, so it always wins.
     */
    public static String backgroundAudioDefault(String original) {
        switch (Settings.getInt("background_audio")) {
            case 1: return "always";
            case 2: return "headphones_speaker";
            default: return original;
        }
    }

    /**
     * Whether a live channel may open in the React Native theatre: Twitch's own answer ({@code live}),
     * or false while the native player is chosen.
     */
    public static boolean routeLiveToReactNative(boolean live) {
        return live && !Settings.isEnabled("native_player");
    }
}
