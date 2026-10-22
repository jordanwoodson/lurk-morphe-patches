package io.github.jordanwoodson.extension.shared.settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lurk settings as a JSON file, for Export settings and Import settings:
 *
 * <pre>{"lurk": 1, "app": "Twitch", "package": "tv.twitch.android.app", "version": "1.12.0",
 *  "settings": {"block_client_ads": true, "video_quality": "720", "chat_blocked_words": "spoiler"}}</pre>
 *
 * Every registered setting is written with its current value (a switch as true/false, a choice or text as a
 * string). Importing applies the keys this build registered with values they accept and lists the rest.
 */
final class Backup {
    static final int FORMAT = 1;

    private Backup() {
    }

    /** The file for {@code values} (key to value as text, from {@link Settings#values()}). */
    static String export(Registry registry, Map<String, String> values, String packageName) throws JSONException {
        JSONObject settings = new JSONObject();
        for (Registry.Entry entry : registry.entries) {
            String value = values.get(entry.key);
            if (value == null) continue;
            if (entry.isSwitch()) settings.put(entry.key, Boolean.parseBoolean(value));
            else settings.put(entry.key, value);
        }
        return new JSONObject()
                .put("lurk", FORMAT)
                .put("app", registry.app)
                .put("package", packageName)
                .put("version", registry.version)
                .put("settings", settings)
                .toString(2);
    }

    /** What importing a file would do. */
    static final class Plan {
        /** The app the file was exported from, or "". */
        final String app;
        /** Key to new value as text, in screen order. */
        final Map<String, String> changes = new LinkedHashMap<>();
        /** One line per change: "Title: old → new". */
        final List<String> lines = new ArrayList<>();
        /** Settings in the file that already have its value. */
        int unchanged;
        /** Settings in the file this build doesn't have, or with a value it can't take. */
        final List<String> skipped = new ArrayList<>();

        Plan(String app) {
            this.app = app;
        }
    }

    /**
     * What importing {@code text} would change, against {@code current} (key to value as text). Throws when
     * the text isn't a Lurk settings file.
     */
    static Plan plan(String text, Registry registry, Map<String, String> current) throws JSONException {
        JSONObject root = new JSONObject(text);
        if (root.optInt("lurk", -1) != FORMAT || root.optJSONObject("settings") == null) {
            throw new JSONException("Not a Lurk settings file");
        }
        JSONObject settings = root.getJSONObject("settings");
        Plan plan = new Plan(root.optString("app", ""));
        for (Registry.Entry entry : registry.entries) {
            if (!settings.has(entry.key)) continue;
            String value = accept(entry, settings.opt(entry.key));
            if (value == null) {
                plan.skipped.add(entry.title);
                continue;
            }
            String old = current.get(entry.key);
            if (value.equals(old)) {
                plan.unchanged++;
                continue;
            }
            plan.changes.put(entry.key, value);
            plan.lines.add(entry.title + ": " + describe(entry, old) + " → " + describe(entry, value));
        }
        for (Iterator<String> keys = settings.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (registry.get(key) == null) plan.skipped.add(key);
        }
        return plan;
    }

    /** The value as the entry stores it, or null when the entry can't take it. */
    static String accept(Registry.Entry entry, Object raw) {
        if (raw == null || raw == JSONObject.NULL) return null;
        if (entry.isSwitch()) {
            if (raw instanceof Boolean) return raw.toString();
            String text = raw.toString();
            return "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text) ? text.toLowerCase(Locale.ROOT) : null;
        }
        if (Registry.CHOICE.equals(entry.type)) {
            String value = raw instanceof Number && ((Number) raw).doubleValue() == ((Number) raw).longValue()
                    ? String.valueOf(((Number) raw).longValue()) : raw.toString();
            return entry.hasChoice(value) ? value : null;
        }
        return raw instanceof String ? ((String) raw).trim() : null;
    }

    /** A value for people: on/off, a choice's label, or the text. */
    static String describe(Registry.Entry entry, String value) {
        if (value == null) return "?";
        if (entry.isSwitch()) return Boolean.parseBoolean(value) ? "on" : "off";
        if (Registry.CHOICE.equals(entry.type)) return entry.choiceLabel(value);
        if (value.isEmpty()) return "not set";
        String line = value.replace('\n', ' ');
        return "“" + (line.length() > 40 ? line.substring(0, 39) + "…" : line) + "”";
    }
}
