import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Version-pinned integration test for the chat patches: the real Twitch 31.7.0 classes with the emitted
 * hooks, run on ART with a fake settings store. Each switch is checked on and off; "off" must reach the
 * original code (its result, or the exception its empty dependencies cause).
 * The short class names and resource ID below are Twitch 31.7.0's R8 output.
 */
public final class ChatArtProbe {
    private static final Map<String, Object> settings = new HashMap<>();
    private static final String CHAT = "io.github.jordanwoodson.extension.shared.twitch.TwitchChat";
    private static Object unsafe;

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
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        Field field = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings").getDeclaredField("context");
        field.setAccessible(true);
        field.set(null, context);
        Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        unsafe = theUnsafe.get(null);
    }

    private static Object allocate(String type) throws Exception {
        return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Class.forName(type));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, Class<?> type) throws Exception {
        Object found = null;
        for (Field field : target.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) continue;
            check(found == null, "Several " + type + " fields");
            field.setAccessible(true);
            found = field.get(target);
        }
        return found;
    }

    /** Anonymous chat is snapshotted once per process; start a new "process" for each case. */
    private static void anonymous(boolean enabled) throws Exception {
        settings.put("chat_anonymous", enabled);
        for (String name : new String[]{"anonymous", "guestNick"}) {
            Field field = Class.forName(CHAT).getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, null);
        }
    }

    private static Throwable failure(Method method, Object target, Object... arguments) throws Exception {
        try {
            method.invoke(target, arguments);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    private static void anonymousChat() throws Exception {
        Class<?> chat = Class.forName(CHAT);
        Method token = chat.getMethod("ircToken", String.class);
        Method nick = chat.getMethod("ircNick", String.class);
        anonymous(false);
        check("secret".equals(token.invoke(null, "secret")) && "viewer".equals(nick.invoke(null, "viewer")),
            "Off must keep the account's IRC login");
        anonymous(true);
        String guest = (String) nick.invoke(null, "viewer");
        check(token.invoke(null, "secret") == null, "On must send PASS listen");
        check(guest.matches("justinfan[0-9]{9}") && guest.equals(nick.invoke(null, "other")), "Unstable guest nick " + guest);
        settings.put("chat_anonymous", false);
        check(token.invoke(null, "secret") == null, "The login must not change before a restart");

        // The input restriction: Twitch's LOGIN while on; the original lookup (which needs the account) while off.
        String restrictions = "tv.twitch.android.shared.messageinput";
        Class<?> state = Class.forName(restrictions + ".impl.chatrestrictions.ChatRestrictionsState");
        Object mapper = allocate("yn6");
        Method restriction = null;
        for (Method method : mapper.getClass().getMethods()) {
            if (method.getParameterTypes().length == 3 && method.getParameterTypes()[0] == state) restriction = method;
        }
        Object login = Class.forName(restrictions + ".pub.chatrestrictions.ChatRestrictionsDisplayType$LOGIN").getField("INSTANCE").get(null);
        Object restrictionsState = allocate(state.getName());
        for (boolean send : new boolean[]{false, true}) {
            anonymous(true);
            check(restriction.invoke(mapper, restrictionsState, null, send) == login, "On must show the log-in restriction");
            anonymous(false);
            check(failure(restriction, mapper, restrictionsState, null, send) instanceof NullPointerException,
                "Off must run the original restriction lookup");
        }

        // The SendChatMessage mutation: Twitch's own failure result while on.
        Object sender = allocate("wey");
        Method send = null;
        for (Method method : sender.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("t") && types.length == 5 && types[0] == String.class && types[3] == String.class) send = method;
        }
        anonymous(true);
        Object result = send.invoke(sender, "123", "hello", null, null, null);
        check(result != null && result.getClass().getName().equals("a9z") &&
            result.toString().startsWith("Error(error=java.lang.IllegalStateException: Lurk"), "On must not send: " + result);
        anonymous(false);
        check(failure(send, sender, "123", "hello", null, null, null) != null, "Off must reach the original GraphQL send");
        anonymous(false);
    }

    private static void chatClears() throws Exception {
        Class<?> logger = Class.forName("cko");
        Object log = Proxy.newProxyInstance(logger.getClassLoader(), new Class<?>[]{logger}, (proxy, method, values) -> null);
        Class<?> parserType = Class.forName("foa");
        Object parser = parserType.getConstructor(logger, Class.forName("q1e"), Class.forName("qgz")).newInstance(log, null, null);
        Method line = parserType.getMethod("N", String.class);
        Method parse = null;
        for (Method method : parserType.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("L") && types.length == 5 && types[4] == boolean.class) parse = method;
        }
        Object user = Class.forName("e4d").getMethod("l").invoke(null);
        Object properties = allocate("mq5");
        Object ignored = Class.forName("vjm").getField("b").get(null);
        Class<?> event = Class.forName("f65");
        for (boolean enabled : new boolean[]{false, true, false}) {
            settings.put("chat_ignore_clears", enabled);
            Object clear = parse.invoke(parser, line.invoke(parser, ":tmi.twitch.tv CLEARCHAT #somechannel"), user, properties, null, false);
            if (enabled) check(clear == ignored, "On must ignore a chat clear, got " + clear);
            else check(clear.getClass().getName().equals("sjm") && get(clear, event).getClass().getName().equals("w55"),
                "Off must clear the chat, got " + clear);
            Object timeout = parse.invoke(parser, line.invoke(parser,
                "@ban-duration=600;room-id=1;target-user-id=42 :tmi.twitch.tv CLEARCHAT #somechannel :someone"), user, properties, null, false);
            check(get(timeout, event).getClass().getName().equals("e65"), "Timeouts must always apply, got " + timeout);
            Object delete = parse.invoke(parser, line.invoke(parser,
                "@login=someone;room-id=;target-msg-id=abc-123 :tmi.twitch.tv CLEARMSG #somechannel :hello"), user, properties, null, false);
            check(get(delete, event).getClass().getName().equals("u55"), "Deletions must always arrive, got " + delete);
        }
    }

    private static void deletedMessages() throws Exception {
        Class<?> chat = Class.forName(CHAT);
        Class<?> struck = Class.forName(CHAT + "$DeletedSpan");
        // Shown line: name span (Twitch's own) on "viewer", then ": " and the message.
        SpannableStringBuilder text = new SpannableStringBuilder("12:00 viewer: hello there");
        text.setSpan(allocate("je7"), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        SpannedString line = new SpannedString(text);
        Resources system = Resources.getSystem();
        Resources resources = new Resources(system.getAssets(), new DisplayMetrics(), new Configuration()) {
            @Override public String getString(int id) {
                return id == 0x7f15032a ? "message deleted" : super.getString(id);
            }
        };
        Context context = new ContextWrapper(null) {
            @Override public Resources getResources() { return resources; }
        };
        Class<?> dispatcher = Class.forName("tv.twitch.android.core.mvp.viewdelegate.EventDispatcher");
        Method render = Class.forName("r8m").getMethod("u", String.class, SpannedString.class, Context.class, dispatcher, boolean.class);
        for (boolean enabled : new boolean[]{false, true, false}) {
            settings.put("chat_show_deleted", enabled);
            Spanned shown = (Spanned) render.invoke(null, "abc-123", line, context, null, false);
            if (enabled) {
                Object[] spans = shown.getSpans(0, shown.length(), struck);
                check(shown.toString().equals("12:00 viewer: hello there") && spans.length == 1 &&
                    shown.getSpanStart(spans[0]) == 14 && shown.getSpanEnd(spans[0]) == shown.length(),
                    "On must strike the message only: " + shown);
            } else {
                check(shown.toString().equals("12:00 viewer: <message deleted>"), "Off must show Twitch's placeholder: " + shown);
            }
        }
        // Deleted before shown: the extension's replacement for the placeholder body.
        Method body = chat.getMethod("deletedBody", SpannedString.class);
        settings.put("chat_show_deleted", false);
        check(body.invoke(null, new SpannedString("hello")) == null, "Off must keep the placeholder");
        settings.put("chat_show_deleted", true);
        SpannedString kept = (SpannedString) body.invoke(null, new SpannedString("hello"));
        check(kept.toString().equals("hello") && kept.getSpans(0, 5, struck).length == 1, "On must keep the struck text");
    }

    private static void timestamps() throws Exception {
        Method shown = Class.forName(CHAT).getMethod("timestamps", Object.class, boolean.class);
        Class<?> source = Class.forName("vz90");
        Class<?> config = Class.forName("cc6");
        Object chat = config.getConstructors()[0].newInstance(true, source.getField("Chat").get(null), null);
        Object whisper = config.getConstructors()[0].newInstance(true, source.getField("Whisper").get(null), null);
        for (boolean enabled : new boolean[]{false, true}) {
            settings.put("chat_timestamps", enabled);
            check((boolean) shown.invoke(null, chat, false) == enabled, "Live chat time must follow the switch");
            check(!(boolean) shown.invoke(null, whisper, false), "Whispers must stay unchanged");
            check((boolean) shown.invoke(null, chat, true), "History keeps its time");
        }
        Method pattern = Class.forName(CHAT).getMethod("timestampPattern", String.class);
        settings.put("chat_timestamp_format", "0");
        check("H:mm".equals(pattern.invoke(null, "H:mm")), "24-hour must keep Twitch's pattern");
        settings.put("chat_timestamp_format", "1");
        check("h:mm".equals(pattern.invoke(null, "H:mm")), "12-hour pattern");
    }

    private static void autoClaim() throws Exception {
        Class<?> modelType = Class.forName("tv.twitch.android.models.communitypoints.CommunityPointsModel");
        Class<?> claimType = Class.forName("ld");
        Class<?> channel = Class.forName("tv.twitch.android.models.channel.ChannelInfo");
        Class<?> presenterType = Class.forName("gd9");
        Class<?> action = Class.forName("og9");
        List<String> claims = new ArrayList<>();
        Object recorder = Proxy.newProxyInstance(action.getClassLoader(), new Class<?>[]{action}, (proxy, method, values) -> {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == String.class &&
                types[1].getName().equals("tv.twitch.android.shared.one.chat.pub.ChatModeMetadata")) {
                claims.add(values[0] + "/" + values[1]);
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        Object presenter = allocate(presenterType.getName());
        set(presenter, "m", recorder);
        Object update = Class.forName("sc9").getConstructor(presenterType, int.class).newInstance(presenter, 0);
        Method invoke = update.getClass().getMethod("invoke", Object.class);
        // ChannelInfo is an interface; the claim only stores it.
        Object info = Proxy.newProxyInstance(channel.getClassLoader(), new Class<?>[]{channel}, (proxy, method, values) -> null);
        java.lang.reflect.Constructor<?> claimConstructor = claimType.getConstructor(String.class, int.class, int.class, List.class, channel);
        Method setClaim = modelType.getMethod("setClaim", claimType);
        java.util.function.Function<String, Object> model = id -> {
            try {
                Object value = modelType.getConstructor().newInstance();
                if (id != null) setClaim.invoke(value, claimConstructor.newInstance(id, 50, 50, Collections.emptyList(), info));
                return value;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        // The update then reaches the presenter's state machine, absent here: proof it carried on as before.
        settings.put("chat_auto_claim", false);
        check(failure(invoke, update, model.apply("bonus-1")) instanceof NullPointerException && claims.isEmpty(), "Off must not claim");
        settings.put("chat_auto_claim", true);
        check(failure(invoke, update, model.apply(null)) instanceof NullPointerException && claims.isEmpty(), "No bonus, no claim");
        failure(invoke, update, model.apply("bonus-1"));
        failure(invoke, update, model.apply("bonus-1"));
        check(claims.equals(Collections.singletonList("bonus-1/null")), "Expected one claim, got " + claims);
        failure(invoke, update, model.apply("bonus-2"));
        check(claims.size() == 2 && claims.get(1).equals("bonus-2/null"), "A new bonus must be claimed: " + claims);
    }

    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes, int offset, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < length; i++) text.append(String.format("%02x", bytes[offset + i] & 255));
        return text.toString();
    }

    /** The React Native edits: listed in the manifest, and the embedded bundle holds each switch's default. */
    private static void hermes(String apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk)) {
            JSONArray edits = new JSONObject(new String(read(zip, "assets/lurk-hermes.json"), "UTF-8")).getJSONArray("edits");
            byte[] bundle = read(zip, "assets/index.android.bundle");
            int anonymous = 0, clears = 0;
            for (int i = 0; i < edits.length(); i++) {
                JSONObject edit = edits.getJSONObject(i);
                String original = edit.getString("original"), replacement = edit.getString("replacement");
                int offset = edit.getInt("offset");
                String embedded = hex(bundle, offset, original.length() / 2);
                check(embedded.equals(edit.getBoolean("default") ? replacement : original), "Embedded bytes differ at " + offset);
                if (edit.getString("key").equals("chat_anonymous")) {
                    anonymous++;
                    check(!edit.getBoolean("default") && original.equals("890301b20903") && replacement.equals("890301b00903"),
                        "Unexpected chatAuthOptions edit");
                } else if (edit.getString("key").equals("chat_ignore_clears")) {
                    clears++;
                    check(edit.getBoolean("default") && original.startsWith("3400003b0200004501020") &&
                        replacement.matches("93(..)76\\1(00)+"), "Unexpected clearchat handler edit");
                }
            }
            check(anonymous == 1 && clears == 1, "Expected one edit per RN chat switch");
        }
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
        section("anonymous chat: IRC login, read-only input, blocked send", ChatArtProbe::anonymousChat);
        section("ignore chat clears (timeouts and deletions still apply)", ChatArtProbe::chatClears);
        section("deleted messages", ChatArtProbe::deletedMessages);
        section("timestamps", ChatArtProbe::timestamps);
        section("auto-claim channel points", ChatArtProbe::autoClaim);
        section("React Native edits", () -> hermes(args[0]));
        if (failures > 0) {
            System.out.println("FAILED: " + failures + " section(s)");
            System.exit(1);
        }
        System.out.println("PASS: real Twitch chat classes with each switch on and off");
    }
}
