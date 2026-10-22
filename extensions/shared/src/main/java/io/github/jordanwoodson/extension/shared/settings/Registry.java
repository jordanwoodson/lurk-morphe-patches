package io.github.jordanwoodson.extension.shared.settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.jordanwoodson.extension.shared.Logger;

/**
 * The settings the included patches registered at patch time, in screen order. The patches write them
 * as JSON into {@link Settings#registryJson()}.
 */
final class Registry {
    static final String SWITCH = "switch";
    static final String CHOICE = "choice";
    static final String TEXT = "text";

    /** The bundle name, "Lurk". */
    final String name;
    /** The bundle version, or an empty string when the patches couldn't tell. */
    final String version;
    /** The patched app's name, such as "Rumble". */
    final String app;
    final List<Entry> entries;
    /**
     * False when the patches' JSON was missing, couldn't be parsed or listed nothing. The registry is then
     * empty, so every switch reads as off.
     */
    final boolean readable;
    private final Map<String, Entry> byKey = new HashMap<>();

    static final class Entry {
        final String key;
        final String type;
        final String title;
        final String summary;
        final String section;
        /** The switch this one only matters under, or null. */
        final String parent;
        /** "true"/"false" for a switch, a choice's value or a text otherwise. */
        final String defaultValue;
        /** Whether the app has to restart before a change applies. */
        final boolean restart;
        /** A switch that Pause Lurk leaves as it is (lockout protection, Debug logging). */
        final boolean keepWhenPaused;
        final String[] choiceLabels;
        final String[] choiceValues;

        Entry(JSONObject json) throws JSONException {
            key = json.getString("key");
            type = json.getString("type");
            title = json.getString("title");
            summary = json.optString("summary");
            section = json.getString("section");
            parent = json.isNull("parent") ? null : json.optString("parent", null);
            defaultValue = json.getString("default");
            restart = json.optBoolean("restart");
            keepWhenPaused = json.optBoolean("keep");
            JSONArray choices = json.optJSONArray("choices");
            int count = choices == null ? 0 : choices.length();
            choiceLabels = new String[count];
            choiceValues = new String[count];
            for (int i = 0; i < count; i++) {
                JSONArray choice = choices.getJSONArray(i);
                choiceLabels[i] = choice.getString(0);
                choiceValues[i] = choice.getString(1);
            }
        }

        String choiceLabel(String value) {
            for (int i = 0; i < choiceValues.length; i++) {
                if (choiceValues[i].equals(value)) return choiceLabels[i];
            }
            return value;
        }

        boolean hasChoice(String value) {
            for (String choice : choiceValues) {
                if (choice.equals(value)) return true;
            }
            return false;
        }

        boolean isSwitch() {
            return SWITCH.equals(type);
        }
    }

    private Registry(String name, String version, String app, List<Entry> entries, boolean readable) {
        this.name = name;
        this.version = version;
        this.app = app;
        this.entries = Collections.unmodifiableList(entries);
        this.readable = readable;
        for (Entry entry : entries) byKey.put(entry.key, entry);
    }

    /**
     * The registry in {@code json}. All or nothing: when any part of it can't be read the result is empty and
     * not {@link #readable}, so no switch is on.
     */
    static Registry parse(String json) {
        if (json != null) {
            try {
                JSONObject root = new JSONObject(json);
                JSONArray settings = root.getJSONArray("settings");
                List<Entry> entries = new ArrayList<>(settings.length());
                for (int i = 0; i < settings.length(); i++) entries.add(new Entry(settings.getJSONObject(i)));
                return new Registry(root.optString("name", "Lurk"), root.optString("version"),
                        root.optString("app", "the app"), entries, !entries.isEmpty());
            } catch (JSONException e) {
                Logger.w("settings: unreadable registry, every patch is off: " + e.getClass().getSimpleName());
            }
        } else {
            Logger.w("settings: no registry, every patch is off");
        }
        return new Registry("Lurk", "", "the app", new ArrayList<>(), false);
    }

    Entry get(String key) {
        return byKey.get(key);
    }
}
