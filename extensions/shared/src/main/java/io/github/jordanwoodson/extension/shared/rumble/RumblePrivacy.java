package io.github.jordanwoodson.extension.shared.rumble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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
