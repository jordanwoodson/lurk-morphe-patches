package io.github.jordanwoodson.extension.shared;

import android.util.Log;

import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Logging for patched apps, under the tag {@code lurk}: read it with {@code adb logcat -s lurk}.
 *
 * <p>{@link #d} is for following a hook (call it from injected smali:
 * {@code invoke-static {v0}, Lio/github/jordanwoodson/extension/shared/Logger;->d(Ljava/lang/String;)V}).
 * It only writes while Lurk settings &gt; Advanced &gt; Debug logging is on, since its lines can name the
 * channels and videos someone opens. {@link #i}, {@link #w} and {@link #blocked} always write: keep them
 * for facts a bug report needs (a patch giving up, a fallback taken, a crash guard). Never log tokens,
 * cookies, signed URLs, account names or ids.
 */
@SuppressWarnings("unused")
public final class Logger {
    public static final String TAG = "lurk";
    /** The switch that turns {@link #d} on, registered by each app's settings patch. */
    public static final String DEBUG_KEY = "debug_logging";

    /** Debug logging as last read, or null until it is read (again). */
    private static volatile Boolean debug;
    /** Set while this thread reads the switch, so the settings code logging on the way can't recurse. */
    private static final ThreadLocal<Boolean> READING = new ThreadLocal<>();

    private Logger() {
    }

    /** Detail for following a hook. Only written while Debug logging is on. */
    public static void d(String message) {
        if (debug()) Log.d(TAG, message);
    }

    /** Always written: something worth having in a bug report. */
    public static void i(String message) {
        Log.i(TAG, message);
    }

    /** Always written: something failed and Lurk carried on without it. */
    public static void w(String message) {
        Log.w(TAG, message);
    }

    /** Always written: something failed and Lurk carried on without it. */
    public static void w(String message, Throwable failure) {
        Log.w(TAG, message, failure);
    }

    /** Records that a patch stopped something, e.g. a tracker call or an analytics request. Always written. */
    public static void blocked(String what) {
        Log.i(TAG, "blocked: " + what);
    }

    /** Reads the Debug logging switch again on the next {@link #d}. Settings calls this when a setting changes. */
    public static void refresh() {
        debug = null;
    }

    /** Whether {@link #d} writes. */
    public static boolean debug() {
        Boolean known = debug;
        if (known != null) return known;
        if (READING.get() != null) return false;
        READING.set(Boolean.TRUE);
        try {
            boolean on = Settings.isEnabled(DEBUG_KEY);
            // Before the app has a context only the default can be read: don't keep it.
            if (Settings.hasContext()) debug = on;
            return on;
        } catch (RuntimeException e) {
            return false;
        } finally {
            READING.remove();
        }
    }
}
