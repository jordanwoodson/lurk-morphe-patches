import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes the injected loader on Android. All-off must reconstruct the independent stock SHA-256. */
public final class BundleProbe {
    private static final String STOCK = "dd5e4d45bffa1a7befbf943985eb3f0d0d1896d303d28abf0a5748b1d5f124dc";
    private static final Map<String, Boolean> switches = new HashMap<>();
    private static final Map<String, String> choices = new HashMap<>();

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String digest(String algorithm, byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance(algorithm).digest(bytes)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }

    private static byte[] unhex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return result;
    }

    private static byte[] read(AssetManager assets, String name) throws Exception {
        try (java.io.InputStream input = assets.open(name)) { return input.readAllBytes(); }
    }

    private static void verifyAnalyticsJobs(Class<?> settings) throws Exception {
        Method registryJson = settings.getDeclaredMethod("registryJson");
        registryJson.setAccessible(true);
        JSONArray entries = new JSONObject((String) registryJson.invoke(null)).getJSONArray("settings");
        boolean selected = false;
        for (int i = 0; i < entries.length(); i++) {
            if ("disable_analytics".equals(entries.getJSONObject(i).getString("key"))) selected = true;
        }
        if (!selected) return; // Hermes-only combinations do not patch these jobs.

        // Isolate from the completed Hermes snapshot tests. Constructors and the original job
        // bodies must not run: either could initialize SDK work in this app_process test.
        switches.put("disable_analytics", true);
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Object unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            Class<?> parameters = Class.forName("android.app.job.JobParameters");
            for (String name : new String[]{"SessionPayloadJobService", "InsiderAmplificationService"}) {
                Class<?> type = Class.forName("com.useinsider.insider." + name);
                Object service = allocate.invoke(unsafe, type);
                Object result = type.getMethod("onStartJob", parameters).invoke(service, new Object[]{null});
                check(Boolean.FALSE.equals(result), name + " did not complete synchronously");
            }
            System.out.println("PASS: two actual Insider job guards complete synchronously without SDK dispatch");
            verifyPromises(unsafe, allocate);
        } finally {
            switches.remove("disable_analytics");
            switches.remove("block_fingerprinting");
        }
    }

    /**
     * The Firebase event bridge, Datadog's React initialization and the PAL nonce bridge settle their promise at
     * once, so JavaScript awaiting them carries on; FingerprintJS rejects as a network error only while its own
     * switch is on. The real methods run on instances made without constructors.
     */
    @SuppressWarnings("deprecation")
    private static void verifyPromises(Object unsafe, Method allocate) throws Exception {
        // The PAL bridge makes a main-thread Handler when its class loads; app_process has no main Looper yet.
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        List<String> calls = new ArrayList<>();
        Class<?> reactPromise = Class.forName("com.facebook.react.bridge.Promise");
        Class<?> expoPromise = Class.forName("expo.modules.kotlin.Promise");
        Class<?> readableMap = Class.forName("com.facebook.react.bridge.ReadableMap");
        Object promise = Proxy.newProxyInstance(reactPromise.getClassLoader(), new Class<?>[]{reactPromise},
            (proxy, method, values) -> { calls.add(method.getName() + Arrays.toString(values)); return null; });
        Object expo = Proxy.newProxyInstance(expoPromise.getClassLoader(), new Class<?>[]{expoPromise},
            (proxy, method, values) -> { calls.add(method.getName() + Arrays.toString(values)); return null; });

        Class<?> firebase = Class.forName("io.invertase.firebase.analytics.NativeRNFBTurboAnalytics");
        firebase.getMethod("logEvent", String.class, readableMap, reactPromise)
            .invoke(allocate.invoke(unsafe, firebase), "screen_view", null, promise);
        check(calls.equals(List.of("resolve[null]")), "Firebase logEvent: " + calls);
        calls.clear();
        Class<?> datadog = Class.forName("com.datadog.reactnative.DdSdkImplementation");
        datadog.getMethod("initialize", readableMap, reactPromise).invoke(allocate.invoke(unsafe, datadog), null, promise);
        check(calls.equals(List.of("resolve[null]")), "Datadog initialize: " + calls);
        calls.clear();
        Class<?> pal = Class.forName("com.kick.mobile.kickivsplayerv2.PalSDKModuleBridge");
        pal.getMethod("generateNonce", Context.class, Class.forName("com.kick.mobile.kickivsplayerv2.PalNonceRequestConfig"),
            expoPromise).invoke(allocate.invoke(unsafe, pal), null, null, expo);
        check(calls.size() == 1 && calls.get(0).startsWith("resolve[{") && calls.get(0).contains("sdkAvailable=false"),
            "PAL nonce: " + calls);
        calls.clear();

        Class<?> fpjs = Class.forName("com.fingerprintjs.reactnative.RNFingerprintjsProModule");
        Object module = allocate.invoke(unsafe, fpjs);
        for (String name : new String[]{"getVisitorDataWithTimeout", "getVisitorIdWithTimeout"}) {
            Method method = fpjs.getMethod(name, readableMap, String.class, Integer.class, reactPromise);
            switches.put("block_fingerprinting", false);
            method.invoke(module, null, null, null, promise);
            check(calls.isEmpty(), name + " ran Lurk's rejection with the switch off: " + calls);
            switches.put("block_fingerprinting", true);
            method.invoke(module, null, null, null, promise);
            check(calls.equals(List.of("reject[Error: , NetworkError]")), name + ": " + calls);
            calls.clear();
        }
        System.out.println("PASS: Firebase, Datadog and PAL stubs resolve their promise; FingerprintJS rejects only "
            + "with its switch on");
    }

    /**
     * Process starts with settings off their defaults reuse the private copy: the same settings load it without
     * rewriting it, while a changed setting, a changed copy or a sidecar written for another embedded bundle build it
     * again, and the patch-time defaults delete both files.
     */
    private static void verifyReuse(Method prepare, Field prepared, Field defaults, AssetManager assets, byte[] embedded,
                                    File directory, JSONArray edits) throws Exception {
        File output = new File(directory, "lurk-kick.hbc");
        File sidecar = new File(directory, "lurk-kick.json");
        List<String> keys = new ArrayList<>(switches.keySet());
        check(keys.size() >= 2, "Reuse needs two switches");
        switches.put(keys.get(0), true);
        byte[] first = load(prepare, prepared, defaults, assets, embedded);
        check(output.isFile() && sidecar.isFile(), "No private copy and sidecar for non-default settings");
        check(output.setLastModified(1_000_000_000_000L), "Cannot date the private copy");
        byte[] again = load(prepare, prepared, defaults, assets, embedded);
        check(Arrays.equals(again, first) && output.lastModified() == 1_000_000_000_000L,
            "The same settings rebuilt the private copy");

        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(output, "rw")) {
            file.seek(1000);
            int value = file.read();
            file.seek(1000);
            file.write(value ^ 1);
        }
        output.setLastModified(1_000_000_000_000L);
        byte[] repaired = load(prepare, prepared, defaults, assets, embedded);
        check(Arrays.equals(repaired, first) && output.lastModified() != 1_000_000_000_000L,
            "A changed private copy was reused");

        JSONObject stored = new JSONObject(new String(Files.readAllBytes(sidecar.toPath()), "UTF-8"));
        JSONObject key = new JSONObject(stored.getString("key"));
        key.put("embeddedSha256", "0000000000000000000000000000000000000000000000000000000000000000");
        Files.write(sidecar.toPath(), stored.put("key", key.toString()).toString().getBytes("UTF-8"));
        output.setLastModified(1_000_000_000_000L);
        load(prepare, prepared, defaults, assets, embedded);
        check(output.lastModified() != 1_000_000_000_000L, "A sidecar for another embedded bundle was trusted");

        switches.put(keys.get(1), true);
        output.setLastModified(1_000_000_000_000L);
        byte[] changed = load(prepare, prepared, defaults, assets, embedded);
        check(!Arrays.equals(changed, first) && output.lastModified() != 1_000_000_000_000L,
            "A changed setting reused the old private copy");

        // The patch-time defaults use the embedded asset and remove both files.
        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.getJSONObject(i);
            if (edit.has("values")) choices.put(edit.getString("key"), edit.getString("default"));
            else switches.put(edit.getString("key"), edit.getBoolean("default"));
        }
        check(load(prepare, prepared, defaults, assets, embedded) == embedded, "Defaults did not use the embedded asset");
        check(!output.exists() && !sidecar.exists(), "Defaults left the private copy or its sidecar");
        System.out.println("PASS: private copy reused for the same settings and rebuilt for changed settings, a changed "
            + "copy or another embedded bundle; defaults remove it");
    }

    /** A fresh process's load: the private copy's bytes, or the embedded asset's when it uses that. */
    private static byte[] load(Method prepare, Field prepared, Field defaults, AssetManager assets, byte[] embedded)
            throws Exception {
        prepared.set(null, null);
        defaults.set(null, false);
        String output = (String) prepare.invoke(null, assets, "assets://index.android.bundle");
        return output == null ? embedded : Files.readAllBytes(new File(output).toPath());
    }

    public static void main(String[] args) throws Exception {
        File directory = new File(args[1]);
        directory.mkdirs();
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, args[0]);
        byte[] embedded = read(assets, "index.android.bundle");
        JSONObject manifest = new JSONObject(new String(read(assets, "lurk-hermes.json"), "UTF-8"));
        check(STOCK.equals(manifest.getString("sourceSha256")), "Unexpected source hash");
        JSONArray edits = manifest.getJSONArray("edits");
        check(edits.length() > 0, "No selected Hermes patches");
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("getBoolean")) {
                    return switches.getOrDefault((String) values[0], (Boolean) values[1]);
                }
                if (method.getName().equals("getString")) {
                    return choices.getOrDefault((String) values[0], (String) values[1]);
                }
                throw new UnsupportedOperationException(method.getName());
            });
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCodeCacheDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        Class<?> settings = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");
        Field settingsContext = settings.getDeclaredField("context");
        settingsContext.setAccessible(true);
        settingsContext.set(null, context);
        Class<?> loader = Class.forName("io.github.jordanwoodson.extension.shared.kick.KickBundle");
        loader.getMethod("init", Context.class).invoke(null, context);
        Method prepare = loader.getMethod("prepare", AssetManager.class, String.class);
        Field prepared = loader.getDeclaredField("prepared");
        prepared.setAccessible(true);
        Field defaults = loader.getDeclaredField("embedded");
        defaults.setAccessible(true);
        check(prepare.invoke(null, assets, "assets://unrelated.hbc") == null, "Unrelated bundle intercepted");

        // Patch defaults load the embedded asset as is, without a private copy, and a second load in the
        // process keeps that snapshot.
        File stale = new File(directory, "lurk-kick.hbc");
        Files.write(stale.toPath(), new byte[]{1});
        check(prepare.invoke(null, assets, "assets://index.android.bundle") == null, "Defaults made a private copy");
        check(!stale.exists(), "Defaults left a stale private copy");
        Map<String, JSONObject> choiceValues = new HashMap<>();
        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.getJSONObject(i);
            if (edit.has("values")) {
                choices.put(edit.getString("key"), "0");
                choiceValues.put(edit.getString("key"), edit.getJSONObject("values"));
            } else {
                switches.put(edit.getString("key"), false);
            }
        }
        check(prepare.invoke(null, assets, "assets://index.android.bundle") == null, "Settings changed without restart");

        // Simulate process restart; every switch off and every choice at 0 must reconstruct the
        // independently hashed stock bundle.
        byte[] restored = load(prepare, prepared, defaults, assets, embedded);
        check(digest("SHA-256", restored).equals(STOCK), "All-off did not restore the stock bundle");
        for (String key : switches.keySet()) {
            switches.put(key, true);
            byte[] selected = load(prepare, prepared, defaults, assets, embedded);
            check(!Arrays.equals(selected, restored), "Switch had no effect: " + key);
            check(selected.length == restored.length, "Bundle layout changed");
            byte[] content = Arrays.copyOf(selected, selected.length - 20);
            check(Arrays.equals(MessageDigest.getInstance("SHA-1").digest(content),
                Arrays.copyOfRange(selected, content.length, selected.length)), "Invalid Hermes footer: " + key);
            switches.put(key, false);
        }
        // Each choice value writes exactly its bytes at its edits; an unknown value keeps the original.
        int values = 0;
        for (String key : choiceValues.keySet()) {
            JSONObject options = choiceValues.get(key);
            List<String> tried = new ArrayList<>();
            for (Iterator<String> names = options.keys(); names.hasNext(); ) tried.add(names.next());
            tried.add("-7");
            for (String value : tried) {
                choices.put(key, value);
                byte[] selected = load(prepare, prepared, defaults, assets, embedded);
                byte[] expected = restored.clone();
                for (int i = 0; i < edits.length(); i++) {
                    JSONObject edit = edits.getJSONObject(i);
                    if (!key.equals(edit.getString("key"))) continue;
                    String bytes = edit.getJSONObject("values").optString(value, edit.getString("original"));
                    byte[] write = unhex(bytes);
                    System.arraycopy(write, 0, expected, edit.getInt("offset"), write.length);
                }
                byte[] content = Arrays.copyOf(expected, expected.length - 20);
                System.arraycopy(MessageDigest.getInstance("SHA-1").digest(content), 0, expected, content.length, 20);
                check(Arrays.equals(selected, expected), "Choice bytes differ: " + key + "=" + value);
                values++;
            }
            choices.put(key, "0");
        }
        System.out.println("PASS: loader defaults, process snapshot, all-off stock restoration, " + switches.size() +
            " independent switches, " + choiceValues.size() + " choices with " + values + " exact values");
        verifyReuse(prepare, prepared, defaults, assets, embedded, directory, edits);
        verifyAnalyticsJobs(settings);
    }
}
