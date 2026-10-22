package io.github.jordanwoodson.extension.shared.twitch;

import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Show video stats: a stream's uptime in the native player's stats panel.
 *
 * <p>Twitch's master playlist carries {@code #EXT-X-TWITCH-INFO} with the session's {@code SERVING-ID} and the
 * stream's age when the playlist was served ({@code STREAM-TIME}, seconds). Twitch parses the line, shows the
 * serving ID in the panel and drops the stream time. The parse hook keeps the start time per serving ID; the
 * panel's rows find their serving ID and get an Uptime row. Nothing is requested; VODs and clips have no
 * stream time and get no row.
 */
@SuppressWarnings("unused")
public final class TwitchStats {
    private static final String KEY = "video_stats";
    private static final String INFO = "#EXT-X-TWITCH-INFO:";
    private static final String SERVING_ID_LABEL = "Serving ID";
    static final String UPTIME_LABEL = "Uptime";
    private static final int MAX_STREAMS = 16;

    /** Serving ID -> the stream's start on the elapsedRealtime clock. */
    private static final Map<String, Long> STARTS = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_STREAMS;
        }
    };

    private TwitchStats() {
    }

    /** Injection point: Twitch parses a master playlist. Keeps its stream's start, by serving ID. */
    public static void masterPlaylist(String playlist) {
        if (playlist == null || !Settings.isEnabled(KEY)) return;
        try {
            int at = playlist.indexOf(INFO);
            if (at < 0) return;
            int end = playlist.indexOf('\n', at);
            String line = playlist.substring(at + INFO.length(), end < 0 ? playlist.length() : end).trim();
            String servingId = attribute(line, "SERVING-ID");
            String streamTime = attribute(line, "STREAM-TIME");
            if (servingId == null || servingId.isEmpty() || streamTime == null) return;
            double seconds = Double.parseDouble(streamTime);
            if (!(seconds >= 0) || seconds > 30 * 24 * 3600) return;
            long start = SystemClock.elapsedRealtime() - Math.round(seconds * 1000);
            synchronized (STARTS) {
                STARTS.put(servingId, start);
            }
            Logger.d("stats: stream started " + Math.round(seconds) + " s before " + servingId);
        } catch (RuntimeException e) {
            Logger.d("stats: unreadable stream info: " + e);
        }
    }

    /** An attribute of an EXT-X-TWITCH-INFO line ({@code NAME="value",...} or {@code NAME=value,...}). */
    static String attribute(String line, String name) {
        int at = 0;
        while (at < line.length()) {
            int equals = line.indexOf('=', at);
            if (equals < 0) return null;
            String key = line.substring(at, equals).trim();
            int valueStart = equals + 1;
            int valueEnd;
            String value;
            if (valueStart < line.length() && line.charAt(valueStart) == '"') {
                valueEnd = line.indexOf('"', valueStart + 1);
                if (valueEnd < 0) return null;
                value = line.substring(valueStart + 1, valueEnd);
                valueEnd++;
            } else {
                valueEnd = line.indexOf(',', valueStart);
                if (valueEnd < 0) valueEnd = line.length();
                value = line.substring(valueStart, valueEnd);
            }
            if (key.equals(name)) return value;
            int comma = line.indexOf(',', valueEnd);
            if (comma < 0) return null;
            at = comma + 1;
        }
        return null;
    }

    /**
     * Injection point: the stats panel's rows (label and value objects), right before they are shown. Adds an
     * Uptime row when the panel's serving ID belongs to a live stream whose start is known.
     */
    public static void statsRows(List rows) {
        if (rows == null || !Settings.isEnabled(KEY)) return;
        try {
            String servingId = null;
            for (Object row : rows) {
                String[] texts = texts(row);
                if (texts == null) continue;
                if (SERVING_ID_LABEL.equals(texts[0])) servingId = texts[1];
                if (UPTIME_LABEL.equals(texts[0])) return;
            }
            if (servingId == null) return;
            Long start;
            synchronized (STARTS) {
                start = STARTS.get(servingId);
            }
            if (start == null) return;
            Object row = statsRow(UPTIME_LABEL, uptime(SystemClock.elapsedRealtime() - start));
            if (row != null) rows.add(row);
        } catch (RuntimeException e) {
            Logger.d("stats: uptime row failed: " + e);
        }
    }

    /** H:MM:SS, the way Twitch's own player shows a stream's uptime. */
    static String uptime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    /** A row's two texts, label first: the label is the one of its two string fields Twitch names. */
    private static String[] texts(Object row) {
        if (row == null) return null;
        String first = null;
        String second = null;
        for (Field field : row.getClass().getDeclaredFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                String value = (String) field.get(row);
                if (first == null) first = value;
                else second = value;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return first == null || second == null ? null : new String[]{first, second};
    }

    /** Replaced at patch time: one of the stats panel's rows, as Twitch makes them. */
    private static Object statsRow(String label, String value) {
        return null;
    }
}
