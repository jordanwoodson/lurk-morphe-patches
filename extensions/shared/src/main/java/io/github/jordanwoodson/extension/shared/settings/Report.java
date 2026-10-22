package io.github.jordanwoodson.extension.shared.settings;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The GitHub bug report form (.github/ISSUE_TEMPLATE/bug_report.yml) with what Lurk knows filled in: the
 * app, the app and Lurk versions, the Android version and device, and every switch's state. Text settings
 * (word and user lists) are named, never quoted. Nothing is sent until the person submits the form.
 */
final class Report {
    static final String FORM = "https://github.com/jordanwoodson/lurk-morphe-patches/issues/new";
    /** Browsers and GitHub take long URLs, but past this only changed settings are listed. */
    static final int MAX_URL = 7000;

    private Report() {
    }

    /**
     * The form's URL. {@code values} is key to value as text ({@link Settings#values()}), {@code device} is
     * like "Android 16 (API 36), Google Pixel 8".
     */
    static String url(Registry registry, Map<String, String> values, boolean paused, String appVersion,
                      String device) {
        String versions = registry.app + " " + appVersion + ", Lurk v" + registry.version
                + ", Manager or Desktop version: ";
        String full = url(registry.app, versions, device, settings(registry, values, paused, false));
        if (full.length() <= MAX_URL) return full;
        return url(registry.app, versions, device, settings(registry, values, paused, true));
    }

    private static String url(String app, String versions, String device, String patches) {
        return FORM + "?template=bug_report.yml"
                + "&app=" + encode(app)
                + "&versions=" + encode(versions)
                + "&device=" + encode(device)
                + "&patches=" + encode(patches);
    }

    /** The "Patches selected" field: the Lurk settings, all of them or only those changed from the defaults. */
    static String settings(Registry registry, Map<String, String> values, boolean paused, boolean changedOnly) {
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        List<String> choices = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Registry.Entry entry : registry.entries) {
            String value = values.get(entry.key);
            if (value == null || (changedOnly && value.equals(entry.defaultValue))) continue;
            if (entry.isSwitch()) {
                (Boolean.parseBoolean(value) ? on : off).add(entry.key);
            } else if (Registry.CHOICE.equals(entry.type)) {
                choices.add(entry.key + "=" + value);
            } else if (!value.isEmpty()) {
                texts.add(entry.key);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Patches selected: (the defaults, or list them)\n\n");
        out.append(changedOnly ? "Lurk settings changed from the patch defaults:\n" : "Lurk settings:\n");
        out.append("- On: ").append(join(on)).append('\n');
        out.append("- Off: ").append(join(off)).append('\n');
        if (!choices.isEmpty() || !changedOnly) out.append("- Choices: ").append(join(choices)).append('\n');
        if (!texts.isEmpty()) out.append("- Lists filled in: ").append(join(texts)).append('\n');
        out.append("- Pause Lurk: ").append(paused ? "on" : "off");
        if (!registry.readable) out.append("\n- The settings could not be read: every patch is off");
        return out.toString();
    }

    private static String join(List<String> items) {
        return items.isEmpty() ? "none" : String.join(", ", items);
    }

    static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
