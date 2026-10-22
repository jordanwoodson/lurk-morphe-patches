package io.github.jordanwoodson.extension.shared.kick;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Mute stitched live ads (experimental). While Kick's player reports an ad break, the player is muted and the video
 * covered with black and a small "Ad break"; both are restored when the break's time is up, the break ends or another
 * stream loads. The ad itself still plays: it is part of the stream. Three signals start a break, none of which a
 * signed-out session received (docs/kick.md): an IVS AdCue (Player.Listener.onCue), the IVS player's own ad break
 * events (onAdBreakStarted and onAdBreakEnded, added to Kick's listener) and the ads of the MediaTailor session that
 * Kick forwards to its analytics. With the switch off nothing starts; a break already running still ends, so Lurk never
 * leaves a player muted or covered.
 */
@SuppressWarnings("unused")
public final class KickAds {
    static final String KEY = "mute_live_ads";
    /** The length of an ad when its signal doesn't give one. */
    static final long DEFAULT_MS = 30_000;
    /** The longest a break is kept without a new signal, as on Twitch. */
    static final long MAX_MS = 240_000;
    /** Extra time after an ad's length: its signal can come a little before the ad plays. */
    static final long SLACK_MS = 2_000;
    /** How long the cover waits after a MediaTailor ad ends, for the next ad of the same break. */
    static final long POD_GAP_MS = 1_500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Breaks by Kick player view. Main thread only. */
    private static final Map<View, Break> BREAKS = new WeakHashMap<>();
    /** The mute state Kick last asked each player view for: what the end of a break restores. */
    private static final Map<View, Boolean> REQUESTED = Collections.synchronizedMap(new WeakHashMap<>());
    /** While Lurk itself calls a view's setMuted, so muteRequest lets the call through. Main thread only. */
    private static boolean applying;

    private KickAds() {
    }

    private static final class Break {
        final WeakReference<View> view;
        final Drawable foreground;
        final Runnable timeout;

        Break(View view) {
            this.view = new WeakReference<>(view);
            foreground = view.getForeground();
            timeout = () -> {
                View current = this.view.get();
                if (current != null && BREAKS.get(current) == this) end(current, "its time is up");
            };
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Injection points

    /**
     * Injection point: the start of Kick's IVS listener onCue(Cue), on the main thread, with the listener's player
     * view. Kick ignores every cue. Only an IVS AdCue starts a break; other cues are logged (Debug logging) so that a
     * real one can be recognised.
     */
    public static void onCue(View view, Object cue) {
        if (view == null || cue == null || !enabled()) return;
        if (!isAdCue(cue)) {
            Logger.d("ads: " + cue.getClass().getSimpleName() + " cue, not an ad");
            return;
        }
        long length = adCueMillis(cue);
        post(() -> start(view, length, "IVS ad cue"));
    }

    /** Injection point: Player.Listener.onAdBreakStarted(AdBreak), which Lurk adds to Kick's listener. */
    public static void onAdBreakStarted(View view, Object adBreak) {
        if (view == null || !enabled()) return;
        long length = adBreakMillis(adBreak);
        post(() -> start(view, length, "IVS ad break"));
    }

    /** Injection point: Player.Listener.onAdBreakEnded(AdBreak), which Lurk adds to Kick's listener. */
    public static void onAdBreakEnded(View view, Object adBreak) {
        if (view != null) post(() -> end(view, "the IVS ad break ended"));
    }

    /**
     * Injection point: the start of the player view's MediaTailor ad event callback (the constructor's lambda that
     * hands each forwarded event to Mux), with the view and Kick's MuxAdEventData. Only a MediaTailor session sends
     * these, and Kick only makes one when its playback response has a tracking URL and Datazoom is running.
     */
    public static void onMediaTailorEvent(View view, Object event) {
        if (view == null || event == null) return;
        String name = adEventName(event);
        if ("adStart".equals(name)) {
            if (!enabled()) return;
            Double seconds = adEventSeconds(event);
            long length = seconds != null && seconds > 0 && seconds < MAX_MS / 1000.0
                    ? Math.round(seconds * 1000) : DEFAULT_MS;
            post(() -> start(view, length, "MediaTailor ad"));
        } else if ("adEnd".equals(name)) {
            post(() -> endSoon(view));
        }
    }

    /**
     * Injection point: the start of the player view's setStreamUrl, loadSource and destroy. Another stream, or none,
     * ends the break.
     */
    public static void onLoad(View view) {
        if (view != null) post(() -> end(view, "another stream loads"));
    }

    /**
     * Injection point: the start of the player view's setMuted(boolean), which React Native calls with the mute Kick
     * wants. Remembered, so the end of a break restores it; during a break the player stays muted.
     */
    public static boolean muteRequest(View view, boolean muted) {
        if (view == null || applying) return muted;
        REQUESTED.put(view, muted);
        if (Looper.myLooper() == Looper.getMainLooper() && !muted && BREAKS.containsKey(view)) {
            Logger.d("ads: kept the player muted until the break is over");
            return true;
        }
        return muted;
    }

    // ---------------------------------------------------------------------------------------------
    // Breaks, on the main thread

    private static void start(View view, long length, String source) {
        if (!enabled()) return;
        Break current = BREAKS.get(view);
        if (current == null) {
            current = new Break(view);
            BREAKS.put(view, current);
            view.setForeground(new AdCover(view));
            apply(view, true);
            Logger.i("ads: " + source + ": muted and covered the player");
        } else {
            Logger.d("ads: " + source + " during the break");
        }
        MAIN.removeCallbacks(current.timeout);
        MAIN.postDelayed(current.timeout, Math.min(length, MAX_MS) + SLACK_MS);
    }

    private static void endSoon(View view) {
        Break current = BREAKS.get(view);
        if (current == null) return;
        MAIN.removeCallbacks(current.timeout);
        MAIN.postDelayed(current.timeout, POD_GAP_MS);
    }

    private static void end(View view, String why) {
        Break ended = BREAKS.remove(view);
        if (ended == null) return;
        MAIN.removeCallbacks(ended.timeout);
        if (view.getForeground() instanceof AdCover) view.setForeground(ended.foreground);
        apply(view, Boolean.TRUE.equals(REQUESTED.get(view)));
        Logger.i("ads: the break is over (" + why + "): sound and video back");
    }

    /** The player view's own setMuted, so its player thread and React Native's state stay in step. */
    private static void apply(View view, boolean muted) {
        applying = true;
        try {
            setMuted(view, muted);
        } catch (RuntimeException e) {
            Logger.w("ads: couldn't " + (muted ? "mute" : "unmute") + " the player", e);
        } finally {
            applying = false;
        }
    }

    private static void post(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else MAIN.post(action);
    }

    // ---------------------------------------------------------------------------------------------
    // Ad signals, by the public names of Amazon's IVS SDK

    private static boolean isAdCue(Object cue) {
        try {
            return Class.forName("com.amazonaws.ivs.player.AdCue", false, cue.getClass().getClassLoader()).isInstance(cue);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * The rest of the break an AdCue announces: its ad's duration, for each ad of the pod from this one on. IVS's cue
     * times are in milliseconds; a duration under a second is taken to be in seconds. Without a duration, the cue's
     * own end, else 30 seconds.
     */
    static long adCueMillis(Object cue) {
        try {
            Class<?> type = cue.getClass();
            long duration = type.getField("duration").getLong(cue);
            long start = type.getField("startTime").getLong(cue);
            long end = type.getField("endTime").getLong(cue);
            int count = type.getField("podCount").getInt(cue);
            int position = type.getField("podPosition").getInt(cue);
            long each = duration >= 1000 ? duration : duration > 0 ? duration * 1000 : end > start ? end - start : DEFAULT_MS;
            return each * Math.max(1, count - position);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return DEFAULT_MS;
        }
    }

    /** An IVS AdBreak's duration (milliseconds, or seconds when under one), else the longest break. */
    static long adBreakMillis(Object adBreak) {
        try {
            long duration = (Long) adBreak.getClass().getMethod("getDuration").invoke(adBreak);
            return duration >= 1000 ? duration : duration > 0 ? duration * 1000 : MAX_MS;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return MAX_MS;
        }
    }

    private static boolean enabled() {
        return Settings.isRegistered(KEY) && Settings.isEnabled(KEY);
    }

    // ---------------------------------------------------------------------------------------------
    // Bridges, filled in at patch time.

    /** ((KickIvsPlayerV2View) view).setMuted(muted). */
    private static void setMuted(View view, boolean muted) {
    }

    /** ((MuxAdEventData) event).getEventName(). */
    private static String adEventName(Object event) {
        return null;
    }

    /** ((MuxAdEventData) event).getDurationSeconds(). */
    private static Double adEventSeconds(Object event) {
        return null;
    }

    /** Opaque black with a small "Ad break" in the middle. */
    private static final class AdCover extends Drawable {
        private final Paint fill = new Paint();
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        AdCover(View view) {
            fill.setColor(Color.BLACK);
            label.setColor(0x99FFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                    view.getResources().getDisplayMetrics()));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRect(bounds, fill);
            float baseline = bounds.exactCenterY() - (label.descent() + label.ascent()) / 2;
            canvas.drawText("Ad break", bounds.exactCenterX(), baseline, label);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
