import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Version-pinned integration test: real Twitch enum/constructors and production helper on ART. */
public final class InterfaceArtProbe {
    private static final Map<String, Boolean> switches = new HashMap<>();
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void settings() throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("getBoolean")) return switches.getOrDefault((String) values[0], (Boolean) values[1]);
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
    private static List<String> names(List<?> values) {
        List<String> names = new ArrayList<>();
        for (Object value : values) names.add(((Enum<?>) value).name());
        return names;
    }
    private static boolean flag(Object state, String field) throws Exception {
        Field member = state.getClass().getDeclaredField(field);
        member.setAccessible(true);
        return member.getBoolean(state);
    }
    private static Object state(String name, boolean stockFlag) throws Exception {
        Constructor<?> constructor = Class.forName(name).getConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] arguments = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            if (types[index] == boolean.class) arguments[index] = stockFlag;
            else if (types[index].isEnum()) arguments[index] = types[index].getEnumConstants()[0];
            else if (types[index].getName().equals("rc9")) arguments[index] = Class.forName("qc9").getField("a").get(null);
        }
        return constructor.newInstance(arguments);
    }
    public static void main(String[] args) throws Exception {
        boolean stock = args.length > 0 && args[0].equals("stock");
        if (!stock) settings();
        // R8 names are Twitch 31.7.0's: profile tabs vpv, chat toolbar state px70, one-chat state zas.
        Class<?> tabType = Class.forName("vpv");
        Object entries = tabType.getMethod("getEntries").invoke(null);
        List<String> original = Arrays.asList("HOME", "ABOUT", "CLIPS", "VIDEOS", "SCHEDULE", "CHAT");
        check(names((List<?>) entries).equals(original), "Unexpected stock profile tab list");
        if (!stock) {
            Method filter = Class.forName("io.github.jordanwoodson.extension.shared.twitch.TwitchInterface").getMethod("clipTabs", Object.class);
            Method contextualFilter = filter.getDeclaringClass().getMethod("clipTabs", Object.class, Bundle.class);
            Class<?> marker = Class.forName("kotlin.enums.EnumEntries");
            for (boolean enabled : new boolean[]{false, true, false}) {
                switches.put("hide_clips", enabled);
                Object output = filter.invoke(null, entries);
                check(marker.isInstance(output), "Result lost Twitch's EnumEntries runtime ABI");
                check(names((List<?>) output).equals(enabled ? Arrays.asList("HOME", "ABOUT", "VIDEOS", "SCHEDULE", "CHAT") : original), "Incorrect filtered tab order");
                check(names((List<?>) entries).equals(original), "Global enum entries changed");
                if (!enabled) check(output == entries, "Disabled filter must preserve identity");
                else {
                    List<?> tabs = (List<?>) output;
                    check(tabs.indexOf(tabType.getField("VIDEOS").get(null)) == 2, "Pager index does not match visible tabs");
                    check(tabs.toArray().length == 5, "EnumEntries array bridge failed");
                    try { tabs.clear(); throw new AssertionError("Filtered entries are mutable"); }
                    catch (UnsupportedOperationException expected) { }
                }
                for (String key : new String[]{"clipId", "contentType", "profileScope", "profileScopeLower"}) {
                    Bundle explicit = new Bundle();
                    explicit.putString(key.equals("profileScopeLower") ? "profileScope" : key, key.equals("clipId") ? "shared-clip" : key.equals("contentType") ? "clips_content" : key.equals("profileScopeLower") ? "clips" : "CLIPS");
                    check(contextualFilter.invoke(null, entries, explicit) == entries, "Explicit clip profile must preserve full enum: " + key);
                }
            }
        }
        for (boolean enabled : new boolean[]{false, true, false}) {
            switches.put("hide_tipping", enabled);
            for (boolean initial : new boolean[]{false, true}) {
                boolean expected = initial && (stock || !enabled);
                Object toolbar = state("px70", initial);
                check(flag(toolbar, "k") == expected && flag(toolbar, "m") == expected, "Native chat Bits button/tray flags differ");
                check(flag(toolbar, "n") == initial && flag(toolbar, "p") == initial, "Typed cheer/shared chat state changed");
                Object oneChat = state("zas", initial);
                check(flag(oneChat, "e") == expected && flag(oneChat, "k") == expected, "Native OneChat Bits/one-tap flags differ");
                check(flag(oneChat, "a") == initial && flag(oneChat, "b") == initial && flag(oneChat, "p") == initial, "Ordinary chat visibility changed");
            }
        }
        System.out.println(stock ? "PASS: original ART enum and chat flag negative controls" : "PASS: real ART EnumEntries ABI/order/immutability/off identity and native Bits constructors on/off without chat changes");
    }
}
