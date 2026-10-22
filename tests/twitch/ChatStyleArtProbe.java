import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Version-pinned integration test for Chat keywords and Chat appearance: Twitch 31.7.0's real chat line builder
 * (with the emitted hooks) and live message models, run on ART with a fake settings store and a fake signed-in
 * account. Each setting is checked on and off; off must give Twitch's own line.
 * The short class and field names below are Twitch 31.7.0's R8 output.
 */
public final class ChatStyleArtProbe {
    private static final Map<String, Object> settings = new HashMap<>();
    private static final String KEYWORDS = "io.github.jordanwoodson.extension.shared.twitch.TwitchChatKeywords";
    private static final String STYLE = "io.github.jordanwoodson.extension.shared.twitch.TwitchChatStyle";
    private static Object unsafe;
    private static Context context;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void install() throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                Object value = settings.getOrDefault((String) values[0], values[1]);
                if (method.getName().equals("getBoolean") || method.getName().equals("getString")) return value;
                throw new UnsupportedOperationException(method.getName());
            });
        Resources system = Resources.getSystem();
        Resources resources = new Resources(system.getAssets(), new DisplayMetrics(), new Configuration());
        context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
            @Override public Resources getResources() { return resources; }
        };
        for (String type : new String[]{"io.github.jordanwoodson.extension.shared.settings.Settings", KEYWORDS}) {
            Field field = Class.forName(type).getDeclaredField(type.endsWith("Settings") ? "context" : "application");
            field.setAccessible(true);
            field.set(null, context);
        }
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = theUnsafe.get(null);
    }

    private static Object allocate(String type) throws Exception {
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Class.forName(type));
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object staticField(String type, String name) throws Exception {
        Field field = Class.forName(type).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** A config (click configuration) for channel chat or a whisper thread. */
    private static Object config(String source) throws Exception {
        return Class.forName("cc6").getConstructors()[0].newInstance(true, Class.forName("vz90").getField(source).get(null), null);
    }

    private static Object sender(String login) throws Exception {
        Class<?> tuid = Class.forName("tv.twitch.android.models.Tuid");
        return Class.forName("dc6").getConstructor(tuid, String.class, String.class)
            .newInstance(null, login, Character.toUpperCase(login.charAt(0)) + login.substring(1));
    }

    private static List<Object> tokens(String text) throws Exception {
        return Collections.singletonList(Class.forName("dyp").getConstructor(String.class).newInstance(text));
    }

    /** Twitch's line builder (kb6.b) for one message; returns its span group (lb6). */
    private static Object line(String login, String text, String id, boolean action, String source) throws Exception {
        Method build = null;
        for (Method method : Class.forName("kb6").getDeclaredMethods()) {
            if (method.getName().equals("b") && method.getParameterTypes().length == 21) build = method;
        }
        check(build != null, "No line builder");
        Object style = staticField(action ? "r340" : "s340", "c");
        // Overlay mode (second to last): no reply header or readable-colour lookups.
        Object builder = allocate("kb6");
        Field colors = builder.getClass().getDeclaredField("e");
        colors.setAccessible(true);
        Object readable = allocate("x5r");
        Field contrast = readable.getClass().getDeclaredField("a");
        contrast.setAccessible(true);
        contrast.set(readable, allocate("uow"));
        colors.set(builder, readable);
        // A near-white sender colour: readable on the overlay's black, so no colour is computed.
        return build.invoke(null, builder, context, Collections.emptyList(), sender(login), 0xFFFEFEFE, tokens(text),
            false, false, action, id, null, style, false, config(source), null, null, null, null, null, true, 0);
    }

    private static Spanned joined(Object spans) throws Exception {
        return (Spanned) spans.getClass().getMethod("a").invoke(spans);
    }

    private static int verdict(Object spans) throws Exception {
        Method verdict = Class.forName(KEYWORDS).getDeclaredMethod("verdict", CharSequence.class);
        verdict.setAccessible(true);
        return (int) verdict.invoke(null, joined(spans));
    }

    private static boolean italic(Object spans) throws Exception {
        Spanned message = (Spanned) field(spans, "e");
        for (StyleSpan span : message.getSpans(0, message.length(), StyleSpan.class)) {
            if (span.getStyle() == Typeface.ITALIC) return true;
        }
        return false;
    }

    private static void reset() {
        settings.clear();
        settings.put("name", "lurker");
        settings.put("DisplayName", "Lurker");
    }

    private static void keywords() throws Exception {
        reset();
        check(verdict(line("viewer", "hello there", "k1", false, "Chat")) == 0, "Defaults must leave a plain line alone");
        check(verdict(line("viewer", "hi @Lurker how are you", "k2", false, "Chat")) == 1, "A mention must be highlighted");
        check(verdict(line("viewer", "lurker2 is someone else", "k3", false, "Chat")) == 0, "Only whole words mention");
        check(verdict(line("lurker", "hi @lurker", "k4", false, "Chat")) == 0, "Your own messages aren't mentions");
        settings.put("chat_highlight_mentions", false);
        check(verdict(line("viewer", "hi @Lurker", "k5", false, "Chat")) == 0, "Mentions off");
        settings.put("chat_highlight_words", "kekw, good game");
        check(verdict(line("viewer", "that was a GOOD game KEKW", "k6", false, "Chat")) == 1, "Highlight words, any case");
        check(verdict(line("viewer", "kekwait", "k7", false, "Chat")) == 0, "Highlight words are whole words");
        settings.put("chat_blocked_words", "spam");
        check(verdict(line("viewer", "buy SPAM now KEKW", "k8", true, "Chat")) == 2, "Blocked word must hide, over a highlight");
        settings.put("chat_blocked_users", "@Viewer, other");
        check(verdict(line("viewer", "hello there", "k9", false, "Other")) == 2, "Blocked user must hide (VOD replay too)");
        check(verdict(line("viewer", "hello there", "k10", false, "Whisper")) == 0, "Whispers stay unchanged");
        settings.put("chat_keywords", false);
        check(verdict(line("viewer", "buy spam now", "k11", false, "Chat")) == 0, "Off must leave every line alone");

        Method live = Class.forName(KEYWORDS).getMethod("onLiveMessage", String.class, Object.class, List.class);
        settings.put("chat_keywords", true);
        check((boolean) live.invoke(null, "l1", sender("viewer"), tokens("hello")), "Blocked user must be dropped live");
        check((boolean) live.invoke(null, "l2", sender("someone"), tokens("so much spam")), "Blocked word must be dropped live");
        check(!(boolean) live.invoke(null, "l3", sender("someone"), tokens("hello")), "Others must reach the chat list");
        settings.put("chat_keywords", false);
        check(!(boolean) live.invoke(null, "l4", sender("viewer"), tokens("spam")), "Off must drop nothing");
    }

    private static void appearance() throws Exception {
        reset();
        settings.put("chat_keywords", false);
        check(italic(line("viewer", "waves", "m1", true, "Chat")), "Twitch's /me is italic");
        settings.put("chat_me_style", "1");
        Object plain = line("viewer", "waves", "m2", true, "Chat");
        check(!italic(plain) && joined(plain).toString().contains("Viewer waves"), "Plain /me: upright, still 'name text'");
        check(!italic(line("viewer", "hello", "m3", false, "Chat")), "Normal messages stay upright");
        settings.put("chat_appearance", false);
        check(italic(line("viewer", "waves", "m4", true, "Chat")), "Off must keep Twitch's /me");

        Method arrived = Class.forName(STYLE).getMethod("onMessage", String.class, Object.class, List.class);
        Object odd = staticField(STYLE, "ODD_ROW");
        reset();
        settings.put("chat_blocked_words", "spam");
        settings.put("chat_alternate_rows", true);
        String[] ids = {"r1", "r2", "r3", "r4"};
        for (String id : ids) {
            arrived.invoke(null, id, sender("viewer"), tokens("hi"));
            // A message Chat keywords hides takes no place.
            arrived.invoke(null, id + "-blocked", sender("viewer"), tokens("spam"));
        }
        // Built in another order (as rows are bound), each line keeps the parity of its arrival.
        boolean[] marked = new boolean[ids.length];
        for (int i : new int[]{3, 1, 0, 2}) marked[i] = joined(line("viewer", "hi", ids[i], false, "Chat")).getSpanStart(odd) >= 0;
        check(marked[0] != marked[1] && marked[1] != marked[2] && marked[2] != marked[3], "Rows must alternate by arrival");
        // VOD chat replay lines have no ID and are built once each, in order.
        boolean[] replay = new boolean[4];
        for (int i = 0; i < replay.length; i++) {
            replay[i] = joined(line("viewer", "hi", null, false, "Other")).getSpanStart(odd) >= 0;
            check(joined(line("viewer", "spam", null, false, "Other")).getSpanStart(odd) < 0, "Hidden replay rows take no place");
        }
        check(replay[0] != replay[1] && replay[1] != replay[2] && replay[2] != replay[3], "Replay rows must alternate");
        check(joined(line("viewer", "hi", null, false, "Whisper")).getSpanStart(odd) < 0, "Lines without an ID elsewhere stay unmarked");
        settings.put("chat_alternate_rows", false);
        check(joined(line("viewer", "hi", "r2", false, "Chat")).getSpanStart(odd) < 0
            && joined(line("viewer", "hi", "r1", false, "Chat")).getSpanStart(odd) < 0, "Off must mark no row");

        Method guide = Class.forName(STYLE).getMethod("landscapeChatGuide", android.view.View.class, float.class);
        reset();
        check((float) guide.invoke(null, null, 0.7f) == 0.7f, "Default landscape width is Twitch's");
        settings.put("chat_landscape_width", "40");
        check(Math.abs((float) guide.invoke(null, null, 0.7f) - 0.6f) < 1e-6, "40% chat column starts at 60%");
        settings.put("chat_appearance", false);
        check((float) guide.invoke(null, null, 0.8f) == 0.8f, "Off keeps Twitch's width");
    }

    private interface Section {
        void run() throws Exception;
    }

    private static int failures;

    private static void section(String name, Section section) {
        try {
            section.run();
            System.out.println("ok   " + name);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            System.out.println("FAIL " + name + ": " + cause);
            failures++;
        }
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        install();
        section("chat keywords: mentions, highlight and blocked words, blocked users, live drops", ChatStyleArtProbe::keywords);
        section("chat appearance: /me style, alternating rows, landscape width", ChatStyleArtProbe::appearance);
        if (failures > 0) {
            System.out.println("FAILED: " + failures + " section(s)");
            System.exit(1);
        }
        System.out.println("PASS: real Twitch chat line builder with each setting on and off");
    }
}
