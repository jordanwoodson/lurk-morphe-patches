package io.github.jordanwoodson.extension.shared.rumble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Privacy settings for Rumble that need code at run time. With a setting off, each hook returns Rumble's value. */
@SuppressWarnings("unused")
public final class RumblePrivacy {
    static final String BLOCK_COOKIE_NOTICE = "block_cookie_notice";

    /** Airship's PrivacyManager.FEATURE_ANALYTICS (1 << 4), part of Airship's public API. */
    private static final int AIRSHIP_ANALYTICS = 16;

    private RumblePrivacy() {
    }

    // ---------------------------------------------------------------------------------------------
    // Block cookie notice

    /** Injection point: Block cookie notice is starting Airship, Rumble's push service, with essential_services only. */
    public static void startingAirship() {
        Logger.d("consent: starting Airship with essential_services only");
    }

    /**
     * Injection point: the Airship features Rumble enables for the essential_services purpose (push, contacts,
     * tags, in-app messages and Airship's own analytics). While Block cookie notice is on, analytics is left out.
     * The array keeps its element type, so it can go back to Airship.
     */
    public static Object[] airshipFeatures(Object[] features) {
        if (features == null || features.length == 0 || !Settings.isRegistered(BLOCK_COOKIE_NOTICE)
                || !Settings.isEnabled(BLOCK_COOKIE_NOTICE)) {
            return features;
        }
        try {
            List<Object> kept = new ArrayList<>(features.length);
            for (Object feature : features) {
                if (feature == null || featureValue(feature) != AIRSHIP_ANALYTICS) kept.add(feature);
            }
            if (kept.size() == features.length) return features;
            Object[] result = (Object[]) Array.newInstance(features.getClass().getComponentType(), kept.size());
            Logger.d("consent: Airship analytics left off");
            return kept.toArray(result);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.d("consent: Airship features unchanged: " + e);
            return features;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Disable analytics: Keep watch progress

    /**
     * Injection point: an event upload while Disable analytics and Keep watch progress are on. Returns only the
     * events of {@code type} (Rumble's watch progress event), the same list when that is all of them, or null
     * when there are none, so nothing is sent.
     */
    public static List<?> watchProgressOnly(List<?> events, Class<?> type) {
        if (events == null || type == null || events.isEmpty()) return null;
        List<Object> kept = null;
        for (int i = 0; i < events.size(); i++) {
            Object event = events.get(i);
            boolean keep = type.isInstance(event);
            if (!keep && kept == null) {
                kept = new ArrayList<>(events.size());
                for (int j = 0; j < i; j++) kept.add(events.get(j));
            } else if (keep && kept != null) {
                kept.add(event);
            }
        }
        if (kept == null) return events;
        return kept.isEmpty() ? null : kept;
    }

    // ---------------------------------------------------------------------------------------------
    // Clean share links

    /**
     * Injection point: a link Rumble is about to share. A rumble.com link (or one of its subdomains) loses
     * everything from the first "?"; any other text is returned as it is.
     */
    public static String cleanShareLink(String link) {
        if (link == null) return null;
        int query = link.indexOf('?');
        if (query < 0) return link;
        String host = host(link);
        if (host == null) return link;
        host = host.toLowerCase(Locale.ROOT);
        if (!host.equals("rumble.com") && !host.endsWith(".rumble.com")) return link;
        return link.substring(0, query);
    }

    /** The host of an absolute http(s) link, or null. Doesn't need android.net, so host tests can run it. */
    static String host(String link) {
        int scheme = link.indexOf("://");
        if (scheme <= 0) return null;
        String rest = link.substring(scheme + 3);
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#'}) {
            int index = rest.indexOf(stop);
            if (index >= 0 && index < end) end = index;
        }
        String authority = rest.substring(0, end);
        int user = authority.lastIndexOf('@');
        if (user >= 0) authority = authority.substring(user + 1);
        if (authority.startsWith("[")) return null;
        int port = authority.indexOf(':');
        if (port >= 0) authority = authority.substring(0, port);
        return authority.isEmpty() ? null : authority;
    }

    /** An Airship feature's raw value: its one int field (R8 renames the field, not what it holds). */
    private static int featureValue(Object feature) throws ReflectiveOperationException {
        Field value = null;
        for (Field field : feature.getClass().getDeclaredFields()) {
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
            if (value != null) throw new NoSuchFieldException("several int fields in " + feature.getClass());
            value = field;
        }
        if (value == null) throw new NoSuchFieldException("no int field in " + feature.getClass());
        value.setAccessible(true);
        return value.getInt(feature);
    }
}
