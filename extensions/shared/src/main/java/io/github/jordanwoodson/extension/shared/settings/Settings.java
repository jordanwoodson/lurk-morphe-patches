package io.github.jordanwoodson.extension.shared.settings;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import io.github.jordanwoodson.extension.shared.Logger;

/**
 * Runtime switches for the patches in a patched app. A patch that can be switched off asks
 * {@link #isEnabled(String)} before it changes anything. The switches are on the Lurk settings screen
 * ({@link SettingsActivity}); each keeps its patch-time default until it is changed there.
 */
@SuppressWarnings("unused")
public final class Settings {
    private static final String PREFERENCES = "lurk_settings";
    private static final String SHORTCUT_ID = "lurk_settings";

    private static volatile Context context;
    private static volatile Registry registry;
    private static Object openAction;

    private static final AtomicLong CHANGES = new AtomicLong();
    /** SharedPreferences keeps its listeners weakly, so this one lives here. */
    private static final SharedPreferences.OnSharedPreferenceChangeListener WATCHER = (preferences, key) -> {
        CHANGES.incrementAndGet();
        Logger.refresh();
    };
    private static volatile SharedPreferences watched;
    private static volatile SharedPreferences unwatchable;

    private Settings() {
    }

    /** Injection point: the start of the app's Application.onCreate(). */
    public static void init(Context appContext) {
        context = appContext;
        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(new ResumedActivityTracker());
        }
        // Watch for changes from here on, so Debug logging and cached lists follow the screen.
        changes();
        new Thread(Settings::publishShortcut, "lurk-shortcut").start();
    }

    /**
     * Injection point. Whether the switch {@code key} is on. A key no included patch registered counts as
     * off, and so does every key when the registry can't be read: Lurk fails closed, so a broken registry
     * turns every patch off instead of turning default-off ones on.
     */
    public static boolean isEnabled(String key) {
        Registry.Entry entry = registry().get(key);
        if (entry == null) return false;
        boolean fallback = Boolean.parseBoolean(entry.defaultValue);
        SharedPreferences preferences = preferences();
        if (preferences == null) return fallback;
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    /**
     * Whether a patch registered {@code key}, so it was included. {@link #isEnabled(String)} already counts an
     * unregistered key as off; a choice or text reads as its stub value ("", or 0 as a number) without one.
     */
    public static boolean isRegistered(String key) {
        return registry().get(key) != null;
    }

    /** The application context, or null before the app has one. */
    public static Context appContext() {
        return context();
    }

    /** Whether the settings can be read yet (the app has a context), without looking for one. */
    public static boolean hasContext() {
        return context != null;
    }

    /**
     * A number that changes whenever a Lurk setting changes in this process, so code that turns settings into
     * something costly (patterns, lists) can keep the result until then. -1 when changes can't be watched
     * (no context yet, or preferences that take no listeners): read the settings every time.
     */
    public static long changes() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return -1;
        if (watched != preferences) {
            synchronized (CHANGES) {
                if (watched != preferences) {
                    if (unwatchable == preferences) return -1;
                    try {
                        preferences.registerOnSharedPreferenceChangeListener(WATCHER);
                    } catch (RuntimeException e) {
                        unwatchable = preferences;
                        return -1;
                    }
                    CHANGES.incrementAndGet();
                    watched = preferences;
                }
            }
        }
        return CHANGES.get();
    }

    /** Injection point. The value picked for the choice {@code key} as a number, or 0 if it isn't one. */
    public static int getInt(String key) {
        try {
            return Integer.parseInt(getString(key));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The text typed for the text setting {@code key}, or its patch-time default (usually empty). Never null. */
    public static String getText(String key) {
        String text = getString(key);
        return text == null ? "" : text;
    }

    /**
     * Injection point. A {@code kotlin.jvm.functions.Function0} that opens the settings screen, for use as
     * a Compose onClick. Made through a proxy, since the extension doesn't compile against Kotlin.
     */
    public static synchronized Object openAction() {
        if (openAction == null) {
            try {
                Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0", false,
                        Settings.class.getClassLoader());
                openAction = Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                        (proxy, method, args) -> {
                            switch (method.getName()) {
                                case "invoke":
                                    Activity activity = ResumedActivityTracker.current();
                                    open(activity != null ? activity : context());
                                    return null;
                                case "equals":
                                    return proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                default:
                                    return "Lurk settings";
                            }
                        });
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Lurk: no Kotlin Function0 in this app", e);
            }
        }
        return openAction;
    }

    /** The value of the choice or text {@code key}: as changed on the screen, its patch-time default, or "". */
    static String getString(String key) {
        Registry.Entry entry = registry().get(key);
        if (entry == null) return "";
        String fallback = entry.defaultValue;
        SharedPreferences preferences = preferences();
        if (preferences == null) return fallback;
        try {
            return preferences.getString(key, fallback);
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    static void putBoolean(String key, boolean value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putBoolean(key, value).apply();
        Logger.refresh();
    }

    static void putString(String key, String value) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(key, value).apply();
        Logger.refresh();
    }

    /** Forgets every change made on the screen, so each setting is back to its patch-time default. */
    static void reset() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().apply();
        Logger.refresh();
    }

    static Registry registry() {
        Registry current = registry;
        if (current == null) {
            synchronized (Settings.class) {
                current = registry;
                if (current == null) registry = current = Registry.parse(registryJson());
            }
        }
        return current;
    }

    static void open(Context from) {
        if (from == null) return;
        Intent intent = new Intent(from, SettingsActivity.class);
        if (!(from instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        from.startActivity(intent);
    }

    /** Starts the app again from its launcher activity, for settings that are read once at startup. */
    static void restart(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null || launch.getComponent() == null) return;
        // Changes are saved with apply(), which writes in the background; exit() doesn't wait for it.
        // A commit is queued behind those writes and returns once they're on disk.
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().commit();
        activity.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        Runtime.getRuntime().exit(0);
    }

    private static SharedPreferences preferences() {
        Context current = context();
        return current == null ? null : current.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static Context context() {
        Context current = context;
        if (current == null) {
            // Before Application.onCreate(), for instance in a content provider.
            try {
                current = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                context = current;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Logger.d("settings: no application context yet");
            }
        }
        return current;
    }

    /** A launcher shortcut (long-press the app icon) to the settings screen. */
    private static void publishShortcut() {
        Context current = context;
        if (current == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
        try {
            ShortcutManager manager = current.getSystemService(ShortcutManager.class);
            if (manager == null) return;
            for (ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
                if (SHORTCUT_ID.equals(shortcut.getId())) return;
            }
            Registry registry = registry();
            Intent intent = new Intent(Intent.ACTION_VIEW).setClassName(current, SettingsActivity.class.getName());
            ShortcutInfo shortcut = new ShortcutInfo.Builder(current, SHORTCUT_ID)
                    .setShortLabel(registry.name)
                    .setLongLabel(registry.name + " settings")
                    .setIcon(Icon.createWithResource(current, current.getApplicationInfo().icon))
                    .setIntent(intent)
                    .build();
            manager.addDynamicShortcuts(Collections.singletonList(shortcut));
        } catch (RuntimeException e) {
            Logger.w("settings: no launcher shortcut: " + e);
        }
    }

    /** Replaced at patch time with the settings the included patches registered, as JSON. */
    private static String registryJson() {
        return null;
    }
}
