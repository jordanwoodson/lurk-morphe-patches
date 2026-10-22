import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Helpers for the probes. Under app_process the app has no Context, so Lurk settings read their
 * patch-time defaults from the registry; {@link #setting} rewrites a default there to test the other value.
 */
final class ProbeSupport {
    private static final String SETTINGS = "io.github.jordanwoodson.extension.shared.settings.Settings";
    private static final String REGISTRY = "io.github.jordanwoodson.extension.shared.settings.Registry";

    private ProbeSupport() {
    }

    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
    }

    static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Object call(String className, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = Class.forName(className).getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static JSONObject current;

    /** The registry as this process sees it: what the patches wrote, with the probe's changes. */
    static JSONObject registry() throws Exception {
        if (current == null) {
            Method json = Class.forName(SETTINGS).getDeclaredMethod("registryJson");
            json.setAccessible(true);
            current = new JSONObject((String) json.invoke(null));
        }
        return new JSONObject(current.toString());
    }

    private static void install(JSONObject root) throws Exception {
        current = root;
        Method parse = Class.forName(REGISTRY).getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        Field registry = Class.forName(SETTINGS).getDeclaredField("registry");
        registry.setAccessible(true);
        registry.set(null, parse.invoke(null, root.toString()));
    }

    /** Whether the patches registered {@code key}. */
    static boolean registered(String key) throws Exception {
        JSONArray settings = registry().getJSONArray("settings");
        for (int i = 0; i < settings.length(); i++) {
            if (key.equals(settings.getJSONObject(i).getString("key"))) return true;
        }
        return false;
    }

    /** Makes {@code value} the value of setting {@code key} for this process. */
    static void setting(String key, String value) throws Exception {
        JSONObject root = registry();
        JSONArray settings = root.getJSONArray("settings");
        boolean found = false;
        for (int i = 0; i < settings.length(); i++) {
            JSONObject entry = settings.getJSONObject(i);
            if (key.equals(entry.getString("key"))) {
                entry.put("default", value);
                found = true;
            }
        }
        check(found, "setting " + key + " is not registered");
        install(root);
    }

    /** Leaves {@code key} out of the registry for this process, as if its patch weren't included. */
    static void unregister(String key) throws Exception {
        JSONObject root = registry();
        JSONArray settings = root.getJSONArray("settings");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < settings.length(); i++) {
            if (!key.equals(settings.getJSONObject(i).getString("key"))) kept.put(settings.getJSONObject(i));
        }
        root.put("settings", kept);
        install(root);
    }

    // ---------------------------------------------------------------------------------------------
    // Locators: the obfuscated names the patches matched, which they record in assets/lurk/locators.json of
    // the patched APK. A probe asks for a name by key with the name in the APK it was written against as
    // the fallback, used while the asset is absent (older builds, the stock APK).

    private static JSONObject locators;

    /** The APK on the probe's classpath. */
    static String apk() {
        for (String entry : System.getProperty("java.class.path", "").split(":")) {
            if (entry.endsWith(".apk")) return entry;
        }
        throw new IllegalStateException("no APK on the classpath");
    }

    /** The recorded value of {@code key} (a type descriptor or method reference), or {@code fallback}. */
    static String locate(String key, String fallback) {
        if (locators == null) {
            locators = new JSONObject();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk())) {
                java.util.zip.ZipEntry entry = zip.getEntry("assets/lurk/locators.json");
                if (entry != null) {
                    byte[] bytes = new byte[(int) entry.getSize()];
                    try (java.io.DataInputStream in = new java.io.DataInputStream(zip.getInputStream(entry))) {
                        in.readFully(bytes);
                    }
                    locators = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                throw new IllegalStateException("can't read assets/lurk/locators.json", e);
            }
        }
        return locators.optString(key, fallback);
    }

    /** The class recorded as {@code key} ("Lpkg/A;"), or the fallback descriptor. */
    static Class<?> type(String key, String fallback) throws Exception {
        return typeOf(locate(key, fallback));
    }

    /** The method recorded as {@code key} ("Lpkg/A;->name(Lpkg/B;I)V"), or the fallback reference. */
    static Method method(String key, String fallback) throws Exception {
        String reference = locate(key, fallback);
        int arrow = reference.indexOf("->");
        int open = reference.indexOf('(', arrow);
        int close = reference.indexOf(')', open);
        Class<?> owner = typeOf(reference.substring(0, arrow));
        String name = reference.substring(arrow + 2, open);
        java.util.List<Class<?>> parameters = new java.util.ArrayList<>();
        String descriptors = reference.substring(open + 1, close);
        for (int i = 0; i < descriptors.length(); ) {
            int start = i;
            while (descriptors.charAt(i) == '[') i++;
            i = descriptors.charAt(i) == 'L' ? descriptors.indexOf(';', i) + 1 : i + 1;
            parameters.add(typeOf(descriptors.substring(start, i)));
        }
        Method m = owner.getDeclaredMethod(name, parameters.toArray(new Class<?>[0]));
        m.setAccessible(true);
        return m;
    }

    /** The field name in the field reference recorded as {@code key} ("Lpkg/A;->name:Ltype;"). */
    static String fieldName(String key, String fallback) {
        String reference = locate(key, fallback);
        return reference.substring(reference.indexOf("->") + 2, reference.indexOf(':'));
    }

    /** The value of the one static field of {@code type} whose type is {@code fieldType} (a Kotlin object's INSTANCE). */
    static Object staticOfType(Class<?> type, Class<?> fieldType) throws Exception {
        Object found = null;
        for (Field f : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == fieldType) {
                if (found != null) throw new AssertionError("several " + fieldType.getName() + " fields in " + type);
                f.setAccessible(true);
                found = f.get(null);
            }
        }
        if (found == null) throw new AssertionError("no " + fieldType.getName() + " field in " + type);
        return found;
    }

    static Class<?> typeOf(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "C": return char.class;
            case "S": return short.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            case "V": return void.class;
            default:
                String name = descriptor.startsWith("[") ? descriptor.replace('/', '.')
                        : descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                return Class.forName(name);
        }
    }

    /** The one declared method of {@code type} with these parameter and return types, whatever R8 named it. */
    static Method bySignature(Class<?> type, Class<?> returns, Class<?>... parameters) {
        Method found = null;
        for (Method m : type.getDeclaredMethods()) {
            if (m.getReturnType() == returns && java.util.Arrays.equals(m.getParameterTypes(), parameters)) {
                if (found != null) throw new AssertionError("several " + returns.getSimpleName() + " methods in " + type);
                found = m;
            }
        }
        if (found == null) throw new AssertionError("no such method in " + type);
        found.setAccessible(true);
        return found;
    }

    /** The constant's name of an enum value (Rumble's enums may override toString). */
    static String name(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    /** Invokes {@code m}; an exception the method throws comes back as the result instead of propagating. */
    static Object outcome(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause();
        }
    }

    /** Arguments for a method that are all null, 0 or false: enough to reach a hook at its start. */
    static Object[] defaults(Method m) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == boolean.class) args[i] = false;
            else if (t == int.class) args[i] = 0;
            else if (t == long.class) args[i] = 0L;
            else if (t == float.class) args[i] = 0f;
            else if (t == double.class) args[i] = 0d;
            else if (t.isPrimitive()) throw new AssertionError("unexpected primitive " + t);
        }
        return args;
    }

    /** Runs a probe body, printing its failure and exiting 1. */
    static void run(String name, ThrowingRunnable body) {
        try {
            body.run();
            System.out.println("PASS: " + name);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
