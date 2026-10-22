import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * The obfuscated names the patches matched in a patched APK, from its assets/lurk/locators.json (keys like
 * twitch.match.chat_restriction, values like Lyn6; or Lyn6;->b(...)Lz;). A probe passes the name it was
 * written against as the fallback, used while an APK has no such file or no such key.
 */
final class ProbeLocators {
    private static JSONObject locators = new JSONObject();
    private static boolean loaded;

    private ProbeLocators() { }

    /** Reads the APK's locators; an APK without them leaves every lookup on its fallback. */
    static void load(String apk) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry("assets/lurk/locators.json");
            if (entry == null) return;
            try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
                locators = new JSONObject(output.toString("UTF-8"));
                loaded = true;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable locators in " + apk, e);
        }
    }

    /** Whether the APK carried locators, for a probe's report. */
    static boolean loaded() {
        return loaded;
    }

    /** The class of [key] (a type, or a method's defining class) as a Java name, else [fallback]. */
    static String type(String key, String fallback) {
        String value = locators.optString(key, null);
        if (value == null) return fallback;
        String descriptor = value.contains("->") ? value.substring(0, value.indexOf("->")) : value;
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /** The method name of the method locator [key], else [fallback]. */
    static String method(String key, String fallback) {
        String value = locators.optString(key, null);
        if (value == null || !value.contains("->")) return fallback;
        return value.substring(value.indexOf("->") + 2, value.indexOf('('));
    }

    /** The Java name of the type of the field locator [key] (Lx;->name:Ltype;), else [fallback]. */
    static String fieldType(String key, String fallback) {
        String value = locators.optString(key, null);
        if (value == null || !value.contains("->") || !value.contains(":")) return fallback;
        String descriptor = value.substring(value.indexOf(':') + 1);
        return descriptor.startsWith("L") ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.') : fallback;
    }

    /** The field name of the field locator [key] (Lx;->name:Ltype;), else [fallback]. */
    static String field(String key, String fallback) {
        String value = locators.optString(key, null);
        if (value == null || !value.contains("->") || !value.contains(":")) return fallback;
        return value.substring(value.indexOf("->") + 2, value.indexOf(':'));
    }
}
