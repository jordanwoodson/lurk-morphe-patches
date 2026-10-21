import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes actual emitted Twitch methods on ART, with uninitialized owners to detect fallthrough.
 * Compile against compile-only kx50/akg stubs; omit both stubs from the probe DEX.
 * R8 names are Twitch 31.7.0's: Spade dispatcher jn0 and callback kx50, client ads ms/os, Crashlytics arbiter apc.
 * CLASSPATH=probe.jar:patched.apk app_process / PrivacyAdsProbe
 */
public final class PrivacyAdsProbe {
    private static final Map<String, Boolean> switches = new HashMap<>();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object instance = unsafe.getMethod("getUnsafe").invoke(null);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(instance, type);
    }

    private static void originalMustFail(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            method.invoke(receiver, arguments);
            throw new AssertionError("Disabled analytics did not reach original " + method.getName());
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof NullPointerException, "Unexpected original SDK result: " + expected.getCause());
        }
    }

    private static void settings() throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("getBoolean")) {
                    return switches.getOrDefault((String) values[0], (Boolean) values[1]);
                }
                throw new UnsupportedOperationException(method.getName());
            });
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        Field field = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings").getDeclaredField("context");
        field.setAccessible(true);
        field.set(null, context);
    }

    public static void main(String[] args) throws Exception {
        settings();
        switches.put("disable_analytics", true);
        Class<?> dispatcher = Class.forName("jn0");
        Object owner = allocate(dispatcher);
        Method send = dispatcher.getMethod("d", String.class, LinkedHashMap.class, kx50.class, boolean.class);
        // R8 removed kx50's empty constructor, so bypass our compile-only constructor as well.
        SpadeCallback callback = (SpadeCallback) allocate(SpadeCallback.class);
        send.invoke(owner, "test_event", new LinkedHashMap<>(), callback, false);
        check(callback.successes == 1 && callback.failures == 0, "Spade must complete success exactly once");
        check(callback.value == null, "Spade empty success response changed");
        send.invoke(owner, "test_event", new LinkedHashMap<>(), null, true);
        check(callback.successes == 1, "Null callback must return without dispatching");
        switches.put("disable_analytics", false);
        try {
            send.invoke(owner, "test_event", new LinkedHashMap<>(), callback, false);
            throw new AssertionError("Disabled patch did not reach original dispatcher");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof NullPointerException, "Original owner must be uninitialized");
        }
        check(callback.successes == 1 && callback.failures == 0, "Disabled gate unexpectedly completed callback");

        switches.put("block_client_ads", true);
        Class<?> observerType = Class.forName("io.reactivex.v");
        final Object[] result = new Object[1];
        final int[] calls = new int[2];
        Object observer = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[]{observerType},
            (proxy, method, values) -> {
                if (method.getName().equals("onSuccess")) { calls[0]++; result[0] = values[0]; }
                else if (method.getName().equals("b")) { calls[1]++; throw new AssertionError(values[0]); }
                return null;
            });
        Class<?> emitterType = Class.forName("io.reactivex.internal.operators.single.c");
        Object emitter = emitterType.getConstructor(observerType).newInstance(observer);
        Class<?> merged = Class.forName("ms");
        Object request = merged.getConstructor(int.class, Object.class, Object.class)
            .newInstance(0, allocate(Class.forName("os")), null);
        merged.getMethod("h", emitterType).invoke(request, emitter);
        check(calls[0] == 1 && calls[1] == 0, "Client-ad eligibility must complete once without error");
        check(result[0].getClass().getName().equals("tv.twitch.android.util.Optional"), "Wrong eligibility result type");
        Field optionalValue = result[0].getClass().getDeclaredField("a");
        optionalValue.setAccessible(true);
        check(Boolean.FALSE.equals(optionalValue.get(result[0])), "Eligibility must be explicitly false, not absent");
        check(Boolean.TRUE.equals(emitterType.getMethod("isDisposed").invoke(emitter)), "Single emitter must finish");
        switches.put("block_client_ads", false);
        try {
            Object nextEmitter = emitterType.getConstructor(observerType).newInstance(observer);
            merged.getMethod("h", emitterType).invoke(request, nextEmitter);
            throw new AssertionError("Disabled ad patch did not reach the original eligibility code");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof NullPointerException, "Original ad dependencies must remain uninitialized");
        }
        check(calls[0] == 1 && calls[1] == 0, "Disabled client-ad gate unexpectedly emitted a result");

        switches.put("disable_analytics", true);
        Class<?> arbiterType = Class.forName("apc");
        Object arbiter = allocate(arbiterType);
        Method collectionPermission = arbiterType.getMethod("b");
        check(Boolean.FALSE.equals(collectionPermission.invoke(arbiter)), "Crashlytics automatic upload permission must be false");
        Class<?> crashlyticsType = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
        Object crashlytics = allocate(crashlyticsType);
        Method unsent = crashlyticsType.getMethod("sendUnsentReports");
        Method log = crashlyticsType.getMethod("log", String.class);
        Method exception = crashlyticsType.getMethod("recordException", Throwable.class);
        Method user = crashlyticsType.getMethod("setUserId", String.class);
        unsent.invoke(crashlytics);
        log.invoke(crashlytics, "privacy probe");
        exception.invoke(crashlytics, new Throwable("privacy probe"));
        user.invoke(crashlytics, "privacy-probe");
        switches.put("disable_analytics", false);
        originalMustFail(collectionPermission, arbiter);
        originalMustFail(unsent, crashlytics);
        originalMustFail(log, crashlytics, "privacy probe");
        originalMustFail(exception, crashlytics, new Throwable("privacy probe"));
        originalMustFail(user, crashlytics, "privacy-probe");
        System.out.println("PASS: actual ART Spade callback once/null/off; client-ad completed Optional.FALSE; Crashlytics arbiter and four dispatch gates/on-off controls");
    }
}

final class SpadeCallback extends kx50 {
    int successes;
    int failures;
    Object value;

    @Override public void K(Object response) { successes++; value = response; }
    @Override public void J(akg error) { failures++; }
}
