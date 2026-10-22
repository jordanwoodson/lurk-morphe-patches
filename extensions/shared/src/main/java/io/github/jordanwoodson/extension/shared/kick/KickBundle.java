package io.github.jordanwoodson.extension.shared.kick;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Selects verified, equal-length Hermes edits at process startup. No bytecode relocation or eval. */
public final class KickBundle {
    private static Context context;
    private static String prepared;
    private static boolean embedded;

    private KickBundle() { }

    public static void init(Context application) {
        context = application.getApplicationContext();
    }

    /** Called only at React Native's embedded asset loader. A reload uses the same settings snapshot. */
    public static synchronized String prepare(AssetManager assets, String assetUrl) {
        if (!"assets://index.android.bundle".equals(assetUrl)) return null;
        if (prepared != null || embedded) return prepared;
        long started = SystemClock.elapsedRealtime();
        try {
            if (context == null) throw new IllegalStateException("No application context");
            JSONObject manifest = new JSONObject(new String(read(assets, "lurk-hermes.json", 1024 * 1024),
                    StandardCharsets.UTF_8));
            if (manifest.getInt("format") != 2 || manifest.getInt("version") != 99) {
                throw new IllegalArgumentException("Unknown bundle manifest");
            }
            JSONArray edits = manifest.getJSONArray("edits");
            // Each process gets a single snapshot: "true" or "false" for a switch, the number for a choice.
            Map<String, String> snapshot = new HashMap<>();
            boolean defaults = true;
            for (int i = 0; i < edits.length(); i++) {
                JSONObject edit = edits.getJSONObject(i);
                String key = edit.getString("key");
                if (!snapshot.containsKey(key)) {
                    snapshot.put(key, edit.has("values") ? String.valueOf(Settings.getInt(key))
                            : String.valueOf(Settings.isEnabled(key)));
                }
                if (!snapshot.get(key).equals(String.valueOf(edit.get("default")))) defaults = false;
            }
            File output = new File(context.getCodeCacheDir(), "lurk-kick.hbc");
            File sidecar = new File(context.getCodeCacheDir(), "lurk-kick.json");
            if (defaults) {
                // The embedded asset already has the patch-time defaults: don't copy 12 MB every start.
                embedded = true;
                sidecar.delete();
                output.delete();
                Logger.d("Kick Hermes: every setting is at its default, using the embedded bundle");
                return null;
            }
            String embeddedHash = manifest.getString("embeddedSha256");
            // The private copy written for the same embedded bundle and the same settings is reused, after checking
            // that it is still the file that was written. Otherwise it is built again.
            String key = new JSONObject().put("format", 1).put("embeddedSha256", embeddedHash)
                    .put("snapshot", new JSONObject(new TreeMap<>(snapshot))).toString();
            String reused = reuse(output, sidecar, key);
            if (reused != null) {
                prepared = reused;
                Logger.d("Kick Hermes: reusing the bundle prepared for these settings ("
                        + (SystemClock.elapsedRealtime() - started) + " ms)");
                return prepared;
            }
            byte[] bundle = read(assets, "index.android.bundle", 32 * 1024 * 1024);
            if (!hex(MessageDigest.getInstance("SHA-256").digest(bundle)).equals(embeddedHash)) {
                throw new IllegalArgumentException("Embedded bundle hash mismatch");
            }
            int previousEnd = 0;
            for (int i = 0; i < edits.length(); i++) {
                JSONObject edit = edits.getJSONObject(i);
                int offset = edit.getInt("offset");
                byte[] original = unhex(edit.getString("original"));
                if (original.length == 0 || offset < previousEnd || offset > bundle.length - 20 - original.length) {
                    throw new IllegalArgumentException("Invalid bundle edit range");
                }
                byte[] expected = bytesFor(edit, String.valueOf(edit.get("default")), original);
                if (!Arrays.equals(expected, Arrays.copyOfRange(bundle, offset, offset + expected.length))) {
                    throw new IllegalArgumentException("Bundle edit mismatch");
                }
                byte[] selected = bytesFor(edit, snapshot.get(edit.getString("key")), original);
                System.arraycopy(selected, 0, bundle, offset, selected.length);
                previousEnd = offset + original.length;
            }
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(bundle, 0, bundle.length - 20);
            System.arraycopy(sha1.digest(), 0, bundle, bundle.length - 20, 20);
            // The sidecar goes first so a stale one never describes a new bundle, then the bundle, then the sidecar
            // naming its hash. Atomic replacement tolerates interrupted starts.
            sidecar.delete();
            write(output, bundle);
            write(sidecar, new JSONObject().put("key", key).put("length", bundle.length)
                    .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(bundle))).toString()
                    .getBytes(StandardCharsets.UTF_8));
            prepared = output.getAbsolutePath();
            Logger.d("Kick Hermes: selected " + edits.length() + " edits across " + snapshot.size() + " settings ("
                    + (SystemClock.elapsedRealtime() - started) + " ms)");
            return prepared;
        } catch (Exception e) {
            // The APK still has the validated patch-time defaults. Do not load a stale private copy.
            Log.w(Logger.TAG, "Kick Hermes: Lurk settings that change Kick's JavaScript can't be applied, so their "
                    + "patch-time defaults are in effect: " + e);
            return null;
        }
    }

    /**
     * The bytes an edit writes for a setting value: a switch's replacement while "true", a choice's entry
     * for the value, or else the original. Every candidate must have the original's length.
     */
    private static byte[] bytesFor(JSONObject edit, String value, byte[] original) throws Exception {
        JSONObject values = edit.optJSONObject("values");
        String selected = values == null ? ("true".equals(value) ? edit.getString("replacement") : null)
                : values.optString(value, null);
        byte[] bytes = selected == null ? original : unhex(selected);
        if (bytes.length != original.length) throw new IllegalArgumentException("Invalid bundle edit length");
        return bytes;
    }

    /**
     * The prepared copy's path when its sidecar was written for [key] (the embedded bundle's hash and the settings
     * snapshot) and the file still has the length and SHA-256 the sidecar records; otherwise null.
     */
    private static String reuse(File output, File sidecar, String key) {
        try {
            if (!output.isFile() || !sidecar.isFile() || sidecar.length() > 64 * 1024) return null;
            JSONObject stored = new JSONObject(new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8));
            if (!key.equals(stored.optString("key")) || output.length() != stored.optLong("length", -1)) return null;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(output)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = input.read(buffer)) != -1; ) sha256.update(buffer, 0, count);
            }
            return hex(sha256.digest()).equals(stored.optString("sha256")) ? output.getAbsolutePath() : null;
        } catch (Exception e) {
            Logger.d("Kick Hermes: not reusing the prepared bundle: " + e);
            return null;
        }
    }

    /** Replaces [file] with [bytes] atomically, so an interrupted start leaves the old file or the new one. */
    private static void write(File file, byte[] bytes) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(bytes);
            atomic.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) atomic.failWrite(stream);
            throw e;
        }
    }

    private static byte[] read(AssetManager assets, String path, int limit) throws Exception {
        try (InputStream input = assets.open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > limit - count) throw new IllegalArgumentException("Asset exceeds size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static byte[] unhex(String value) {
        if (value.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
