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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Show video stats: a panel over Kick's player with the numbers its IVS player keeps, refreshed by Kick's own
 * once-a-second player observer. The panel is a drawable in the player view's overlay, so it never takes a touch,
 * and it goes away at the next refresh after the switch is turned off. With the switch off nothing is read.
 */
@SuppressWarnings("unused")
public final class KickStats {
    static final String KEY = "video_stats";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The panel on each player view. Main thread only. */
    private static final Map<View, Panel> PANELS = new WeakHashMap<>();
    /** Whether a panel may be showing, so the switched-off path only reads this field. */
    private static volatile boolean showing;
    private static volatile Ivs ivs;

    private KickStats() {
    }

    /**
     * Injection point: the start of KickIvsPlayerV2View.handlePlayerStatistics(Player), which Kick's observer runs
     * about once a second, on its player thread, while the view is attached and has a player.
     */
    public static void onStatistics(View view, Object player) {
        if (view == null || player == null) return;
        if (!enabled()) {
            if (showing) MAIN.post(() -> remove(view));
            return;
        }
        String[] lines;
        try {
            lines = lines(player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.d("video stats: " + e);
            return;
        }
        showing = true;
        MAIN.post(() -> show(view, lines));
    }

    /** The panel's text for [player]: one line per number the player has. */
    static String[] lines(Object player) throws ReflectiveOperationException {
        Ivs api = ivs;
        ClassLoader loader = player.getClass().getClassLoader();
        if (api == null || api.loader != loader) {
            api = new Ivs(loader);
            ivs = api;
        }
        long latency = (Long) api.latency.invoke(player);
        long average = (Long) api.average.invoke(player);
        long estimate = (Long) api.estimate.invoke(player);
        long buffer = (Long) api.buffered.invoke(player) - (Long) api.position.invoke(player);
        Object statistics = api.statistics.invoke(player);
        Object quality = api.quality.invoke(player);

        List<String> lines = new ArrayList<>();
        // Recorded videos have no live edge: IVS reports no latency for them.
        if (latency > 0) lines.add("Latency  " + seconds(latency));
        int bitrate = statistics == null ? 0 : (Integer) api.videoBitrate.invoke(statistics);
        long shown = bitrate > 0 ? bitrate : average;
        if (shown > 0 || estimate > 0) {
            lines.add("Bitrate  " + (shown > 0 ? rate(shown) : "-") + (estimate > 0 ? ", network " + rate(estimate) : ""));
        }
        lines.add("Buffer   " + seconds(Math.max(0, buffer)));
        if (statistics != null) {
            int dropped = (Integer) api.dropped.invoke(statistics);
            int decoded = (Integer) api.decoded.invoke(statistics);
            int fps = (Integer) api.frameRate.invoke(statistics);
            lines.add("Dropped  " + dropped + " of " + decoded + " frames" + (fps > 0 ? ", " + fps + " fps" : ""));
        }
        if (quality != null) {
            String name = (String) api.name.invoke(quality);
            int width = (Integer) api.width.invoke(quality);
            int height = (Integer) api.height.invoke(quality);
            lines.add("Quality  " + (name == null || name.isEmpty() ? "-" : name)
                    + (width > 0 && height > 0 ? ", " + width + "x" + height : ""));
            String codecs = (String) api.codecs.invoke(quality);
            if (codecs != null && !codecs.isEmpty()) lines.add("Codecs   " + codecs.replace(",", ", "));
        }
        return lines.toArray(new String[0]);
    }

    private static String seconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.1f s", milliseconds / 1000.0);
    }

    private static String rate(long bitsPerSecond) {
        return bitsPerSecond >= 1_000_000
                ? String.format(Locale.ROOT, "%.2f Mbps", bitsPerSecond / 1_000_000.0)
                : String.format(Locale.ROOT, "%d kbps", Math.round(bitsPerSecond / 1000.0));
    }

    private static void show(View view, String[] lines) {
        if (!enabled()) {
            remove(view);
            return;
        }
        Panel panel = PANELS.get(view);
        if (panel == null) {
            panel = new Panel(view);
            PANELS.put(view, panel);
            view.getOverlay().add(panel);
            Logger.d("video stats: shown on the player");
        }
        panel.show(lines, view.getWidth(), view.getHeight());
    }

    private static void remove(View view) {
        Panel panel = PANELS.remove(view);
        if (panel != null) {
            view.getOverlay().remove(panel);
            Logger.d("video stats: removed from the player");
        }
        if (PANELS.isEmpty()) showing = false;
    }

    /** The lines on [view]'s panel, or null without one. For the probes. */
    static String[] shown(View view) {
        Panel panel = PANELS.get(view);
        return panel == null ? null : panel.lines.clone();
    }

    private static boolean enabled() {
        return Settings.isRegistered(KEY) && Settings.isEnabled(KEY);
    }

    /** The IVS player API, by the public names of Amazon's SDK (its native code needs them), looked up once. */
    private static final class Ivs {
        final ClassLoader loader;
        final Method latency, average, estimate, buffered, position, statistics, quality;
        final Method videoBitrate, frameRate, dropped, decoded;
        final Method name, codecs, width, height;

        Ivs(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> player = Class.forName("com.amazonaws.ivs.player.Player", false, loader);
            latency = player.getMethod("getLiveLatency");
            average = player.getMethod("getAverageBitrate");
            estimate = player.getMethod("getBandwidthEstimate");
            buffered = player.getMethod("getBufferedPosition");
            position = player.getMethod("getPosition");
            statistics = player.getMethod("getStatistics");
            quality = player.getMethod("getQuality");
            Class<?> stats = Class.forName("com.amazonaws.ivs.player.Statistics", false, loader);
            videoBitrate = stats.getMethod("getVideoBitRate");
            frameRate = stats.getMethod("getFrameRate");
            dropped = stats.getMethod("getDroppedFrames");
            decoded = stats.getMethod("getDecodedFrames");
            Class<?> level = Class.forName("com.amazonaws.ivs.player.Quality", false, loader);
            name = level.getMethod("getName");
            codecs = level.getMethod("getCodecs");
            width = level.getMethod("getWidth");
            height = level.getMethod("getHeight");
        }
    }

    /** White monospace lines on a translucent black box in the player's top left corner. */
    private static final class Panel extends Drawable {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float margin;
        private final float padding;
        private final float radius;
        String[] lines = new String[0];

        Panel(View view) {
            DisplayMetrics metrics = view.getResources().getDisplayMetrics();
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, metrics));
            box.setColor(0xB3000000);
            margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, metrics);
            padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, metrics);
            radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, metrics);
        }

        void show(String[] lines, int width, int height) {
            this.lines = lines;
            setBounds(0, 0, width, height);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            String[] current = lines;
            if (current.length == 0) return;
            Paint.FontMetrics font = text.getFontMetrics();
            float line = font.descent - font.ascent;
            float widest = 0;
            for (String value : current) widest = Math.max(widest, text.measureText(value));
            Rect bounds = getBounds();
            float left = bounds.left + margin;
            float top = bounds.top + margin;
            canvas.drawRoundRect(left, top, left + widest + 2 * padding, top + line * current.length + 2 * padding,
                    radius, radius, box);
            float baseline = top + padding - font.ascent;
            for (String value : current) {
                canvas.drawText(value, left + padding, baseline, text);
                baseline += line;
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
