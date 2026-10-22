package io.github.jordanwoodson.extension.shared.twitch;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Twitch's start screen routes only after Branch's link lookup answers. When it never does, the start
 * screen stays up until the app is closed. Route anyway after a few seconds, once.
 */
@SuppressWarnings("unused")
public final class TwitchStartup {
    static final String KEY = "fix_stuck_start";
    private static final long FALLBACK_MS = 5000;
    private static final Set<Activity> routed = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private TwitchStartup() {
    }

    /**
     * Injection point: the start screen's routing. True the first time for this start screen, and every
     * time while the switch is off, as unpatched.
     */
    public static boolean firstRoute(Activity activity) {
        if (!Settings.isEnabled(KEY)) return true;
        return routed.add(activity);
    }

    /** Injection point: the start screen's onCreate. {@code route} is its routing method's name. */
    public static void watchStart(Activity activity, String route) {
        if (!Settings.isEnabled(KEY)) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || routed.contains(activity)) return;
            Logger.d("startup: no answer from Branch after " + FALLBACK_MS + " ms, routing anyway");
            try {
                Method method = activity.getClass().getDeclaredMethod(route);
                method.setAccessible(true);
                method.invoke(activity);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Logger.d("startup: fallback route failed: " + e);
            }
        }, FALLBACK_MS);
    }
}
