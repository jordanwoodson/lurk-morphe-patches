package io.github.jordanwoodson.extension.shared;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Sleep timer, shared by the apps: pauses playback the chosen number of minutes (the {@code sleep_timer}
 * choice) after it last started or resumed. The app reports its players starting and stopping and supplies
 * the pause action.
 */
public final class SleepTimer {
    public static final String KEY = "sleep_timer";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Players that were started and not paused or released since. */
    private static final Map<Object, Long> STARTED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Advanced by every play callback, including a resume of the same player. Guarded by STARTED. */
    private static long generation;
    private static Runnable scheduledSleep;
    private static volatile Consumer<Expiry> pauseAction;
    /** Scope for app pause commands enqueued by the initial action; always cleared after dispatch. */
    private static final ThreadLocal<Expiry> DISPATCHING = new ThreadLocal<>();

    private SleepTimer() {
    }

    /** The app's way to pause everything that plays. Called on the main thread when the timer runs out. */
    public static void setPauseAction(Runnable action) {
        pauseAction = action == null ? null : expiry -> action.run();
    }

    /** An app with a delayed fallback retains this expiry's eligible players and playback generations. */
    public static void setExpiryAction(Consumer<Expiry> action) {
        pauseAction = action;
    }

    /**
     * Injection point at an app's player executor enqueue. Stock operations pass through unchanged;
     * commands enqueued by a sleep action revalidate that expiry when they actually execute.
     */
    public static Runnable guardPauseOperation(Runnable operation) {
        Expiry expiry = DISPATCHING.get();
        return expiry == null ? operation : () -> expiry.runIfCurrent(operation);
    }

    /** A player started or resumed: (re)arms the timer from now. May run on any thread. */
    public static void onPlay(Object player) {
        if (player == null) return;
        synchronized (STARTED) {
            long playing = ++generation;
            STARTED.put(player, playing);
            MAIN.post(() -> arm(playing));
        }
    }

    /** A player paused, stopped or was released. */
    public static void onStop(Object player) {
        if (player != null) STARTED.remove(player);
    }

    /** The players started and not stopped since, for pause actions that pause them directly. */
    public static List<Object> started() {
        synchronized (STARTED) {
            return new ArrayList<>(STARTED.keySet());
        }
    }

    private static void arm(long playing) {
        synchronized (STARTED) {
            // A play on another thread can overtake this queued arm callback.
            if (playing != generation) return;
            if (scheduledSleep != null) MAIN.removeCallbacks(scheduledSleep);
            int minutes = Settings.getInt(KEY);
            scheduledSleep = () -> sleep(playing);
            if (minutes > 0) MAIN.postDelayed(scheduledSleep, minutes * 60_000L);
        }
    }

    private static void sleep(long playing) {
        if (Settings.getInt(KEY) <= 0) return;
        synchronized (STARTED) {
            if (playing != generation) return;
            if (STARTED.isEmpty()) {
                Logger.d("sleep timer: nothing is playing");
                return;
            }
            Consumer<Expiry> action = pauseAction;
            if (action == null) {
                Logger.d("sleep timer: no pause action");
                return;
            }
            Expiry expiry = new Expiry(playing, new WeakHashMap<>(STARTED));
            // Initial pause paths can affect every app player. Serialize the actual dispatch with
            // onPlay, just as the per-player fallback does. IVS's injected onStop reenters this monitor.
            DISPATCHING.set(expiry);
            try {
                action.accept(expiry);
            } finally {
                DISPATCHING.remove();
            }
        }
        toast("Sleep timer: playback paused");
    }

    /**
     * One expiry's players and their play generations. A fallback may pause a player missed by the app,
     * but never one started afterwards, resumed on the same instance, stopped or released. Other players
     * starting do not exempt an eligible old player that the app's pause path genuinely missed.
     */
    public static final class Expiry {
        private final long generation;
        private final Map<Object, Long> candidates;
        private boolean consumed;

        private Expiry(long generation, Map<Object, Long> candidates) {
            this.generation = generation;
            this.candidates = candidates;
        }

        private void runIfCurrent(Runnable operation) {
            synchronized (STARTED) {
                if (generation != SleepTimer.generation || Settings.getInt(KEY) <= 0) return;
                operation.run();
            }
        }

        public void pauseLeftovers(String platform) {
            synchronized (STARTED) {
                if (consumed) return;
                consumed = true;
                if (Settings.getInt(KEY) <= 0) return;
                for (Map.Entry<Object, Long> entry : candidates.entrySet()) {
                    Object player = entry.getKey();
                    if (!entry.getValue().equals(STARTED.get(player))) continue;
                    // Keep the generation check and pause together: onPlay may run on any thread.
                    try {
                        player.getClass().getMethod("pause").invoke(player);
                        Logger.d("sleep timer: paused a player " + platform + " missed");
                    } catch (Throwable t) {
                        Logger.d("sleep timer: " + t);
                    }
                }
            }
        }
    }

    private static void toast(String text) {
        MAIN.post(() -> {
            try {
                Toast.makeText(Settings.appContext(), text, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Logger.d("toast: " + e);
            }
        });
    }
}
