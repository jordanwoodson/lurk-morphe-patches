package io.github.jordanwoodson.extension.shared;

import android.content.res.AssetManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Loads verified extra HBC assets through the same bridgeless instance, after its main bundle. */
public final class CompiledBundle {
    private static final Map<String, WeakReference<Object>> INSTANCES = new HashMap<>();
    private CompiledBundle() { }

    public static synchronized boolean load(Object instance, AssetManager assets, String name, int family) {
        try {
            WeakReference<Object> previous = INSTANCES.get(name);
            if (previous != null && previous.get() == instance) return false;
            byte[] bytes = read(assets, name + ".hbc", 1024 * 1024);
            JSONObject metadata = new JSONObject(new String(read(assets, name + ".json", 8192), StandardCharsets.UTF_8));
            if (metadata.getInt("format") != 1 || metadata.getInt("version") != family || bytes.length < 32 ||
                    bytes[0] != (byte) 0xc6 || bytes[1] != 0x1f || bytes[2] != (byte) 0xbc || bytes[3] != 3 ||
                    bytes[4] != (byte) 0xc1 || bytes[5] != 3 || bytes[6] != 0x19 || bytes[7] != 0x1f ||
                    (bytes[8] & 255) != family || bytes[9] != 0 || bytes[10] != 0 || bytes[11] != 0 ||
                    !hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(metadata.getString("bytecodeSha256"))) {
                throw new IllegalArgumentException("Compiled bundle contract mismatch");
            }
            Class<?> type = instance.getClass();
            type.getMethod("access$loadJSBundleFromAssets", type, AssetManager.class, String.class)
                    .invoke(null, instance, assets, "assets://" + name + ".hbc");
            INSTANCES.put(name, new WeakReference<>(instance));
            Logger.d("Compiled Hermes: queued " + name);
            return true;
        } catch (Exception e) {
            Logger.w("Compiled Hermes: load failed " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static synchronized void release(Object instance) {
        INSTANCES.entrySet().removeIf(entry -> entry.getValue().get() == null || entry.getValue().get() == instance);
    }

    /** Data only. NativeArray and ReactInstance stay in the target app, never bundled in the extension. */
    public static synchronized void call(String bundle, String module, String method, String json) {
        WeakReference<Object> reference = INSTANCES.get(bundle);
        Object instance = reference == null ? null : reference.get();
        if (instance == null) return;
        try {
            ClassLoader loader = instance.getClass().getClassLoader();
            Class<?> arrayType = Class.forName("com.facebook.react.bridge.WritableNativeArray", true, loader);
            Object arguments = arrayType.getConstructor().newInstance();
            arrayType.getMethod("pushString", String.class).invoke(arguments, json);
            instance.getClass().getMethod("callFunctionOnModule", String.class, String.class,
                    Class.forName("com.facebook.react.bridge.NativeArray", true, loader)).invoke(instance, module, method, arguments);
        } catch (Exception e) {
            Logger.w("Compiled Hermes: delivery failed " + e.getClass().getSimpleName());
        }
    }

    public static byte[] read(AssetManager assets, String name, int limit) throws Exception {
        try (InputStream input = assets.open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (n > limit - output.size()) throw new IllegalArgumentException("Oversized compiled asset");
                output.write(buffer, 0, n);
            }
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
