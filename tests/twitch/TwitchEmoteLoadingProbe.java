import io.github.jordanwoodson.extension.shared.settings.Settings;
import io.github.jordanwoodson.extension.shared.twitch.TwitchEmotes;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * How emote lists load when a service fails (issue 100), with the production loader, controlled HTTPS
 * answers per URL and a clock the probe moves. No traffic to the services.
 */
public final class TwitchEmoteLoadingProbe {
    static final String ROOT = "io.github.jordanwoodson.extension.shared.twitch.";
    static final String FFZ_ROOM = "https://api.frankerfacez.com/v1/room/id/";
    static final String FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global";
    static final String SEVENTV_USER = "{\"emote_set\":{\"emotes\":[{\"id\":\"abc123\",\"name\":\"Channel7\",\"data\":{\"animated\":false}}]}}";
    static final String SEVENTV_GLOBAL = "{\"emotes\":[{\"id\":\"glob7\",\"name\":\"Global7\",\"data\":{\"animated\":false}}]}";
    static final String BTTV_USER = "{\"channelEmotes\":[{\"id\":\"bttv1\",\"code\":\"ChannelB\"}],\"sharedEmotes\":[]}";
    static final String BTTV_GLOBAL = "[{\"id\":\"bttvg\",\"code\":\"GlobalB\"}]";

    /** An answer: an HTTP status and body, or an exception, or blocking until released (a hanging service). */
    interface Answer { Object answer(String url) throws IOException; }
    static final Map<String, Answer> ANSWERS = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    static int calls(String url) { AtomicInteger n = CALLS.get(url); return n == null ? 0 : n.get(); }
    static void answer(String url, int status, String body) { ANSWERS.put(url, u -> new Object[]{status, body}); }

    static void install() {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                String key = url.toString();
                CALLS.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                return new HttpURLConnection(url) {
                    Object[] result;
                    public void connect() {} public void disconnect() {} public boolean usingProxy() { return false; }
                    Object[] result() throws IOException {
                        if (result == null) {
                            Answer answer = ANSWERS.get(key);
                            if (answer == null) throw new AssertionError("unexpected request " + key);
                            result = (Object[]) answer.answer(key);
                        }
                        return result;
                    }
                    public int getResponseCode() throws IOException { return (Integer) result()[0]; }
                    public InputStream getInputStream() throws IOException {
                        return new ByteArrayInputStream(((String) result()[1]).getBytes(StandardCharsets.UTF_8));
                    }
                };
            }
        } : null);
    }

    static Object field(String type, String name) throws Exception {
        Field f = Class.forName(ROOT + type).getDeclaredField(name); f.setAccessible(true); return f.get(null);
    }
    static void setField(String type, String name, Object value) throws Exception {
        Field f = Class.forName(ROOT + type).getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = TwitchEmotes.class.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(null, args); } catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    @SuppressWarnings("unchecked") static Map<String, Object> sets() throws Exception { return (Map<String, Object>) field("TwitchEmotes", "SETS"); }
    static Object entry(String key) throws Exception { return sets().get(key); }
    static Object entryField(Object entry, String name) throws Exception {
        Field f = entry.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(entry);
    }
    static List<String> ids(String key) throws Exception {
        Object entry = entry(key);
        List<String> out = new ArrayList<>();
        if (entry == null) return out;
        Object emotes = entryField(entry, "emotes");
        for (int i = 0; i < Array.getLength(emotes); i++) {
            Object emote = Array.get(emotes, i);
            Field id = emote.getClass().getDeclaredField("id"); id.setAccessible(true);
            out.add((String) id.get(emote));
        }
        return out;
    }
    static Map<?, ?> resolved(String channel) throws Exception {
        int mask = 0;
        String[] keys = {"emotes_7tv", "emotes_bttv", "emotes_ffz"};
        for (int i = 0; i < keys.length; i++) if (Settings.isEnabled(keys[i])) mask |= 1 << i;
        Object resolved = call("resolve", new Class<?>[]{String.class, int.class}, channel, mask);
        Field byCode = resolved.getClass().getDeclaredField("byCode"); byCode.setAccessible(true);
        return (Map<?, ?>) byCode.get(resolved);
    }
    /** Waits until no list is loading (all three lanes idle). */
    static void settle() throws Exception {
        Set<?> loading = (Set<?>) field("TwitchEmotes", "LOADING");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!loading.isEmpty()) {
            if (System.nanoTime() > end) throw new AssertionError("still loading: " + loading);
            Thread.sleep(10);
        }
    }
    static void waitFor(BooleanSupplier condition, String why) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out: " + why);
            Thread.sleep(10);
        }
    }
    static boolean has(String key) {
        try { return entry(key) != null; } catch (Exception e) { throw new AssertionError(e); }
    }
    static void advance(long millis) { android.os.SystemClock.offset += millis; }
    static String url(String id, String variant) { return TwitchEmotes.url("https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/" + variant + "/dark/2.0"); }

    public static void main(String[] args) throws Exception {
        install();
        switch (args[0]) {
            case "independent": {
                // One service hanging (FrankerFaceZ's origin, until the read timeout) must not hold up the others'
                // channel lists, nor may the shared pool being busy (badge lists, images).
                Settings.values.put("emotes_7tv", "true");
                Settings.values.put("emotes_bttv", "true");
                Settings.values.put("emotes_ffz", "true");
                CountDownLatch hang = new CountDownLatch(1);
                Answer hanging = u -> {
                    try { hang.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new SocketTimeoutException("timeout");
                };
                ANSWERS.put(FFZ_ROOM + "123", hanging);
                ANSWERS.put(FFZ_GLOBAL, hanging);
                ANSWERS.put(FFZ_ROOM + "456", hanging);
                answer("https://7tv.io/v3/users/twitch/123", 200, SEVENTV_USER);
                answer("https://7tv.io/v3/users/twitch/456", 200, SEVENTV_USER);
                answer("https://7tv.io/v3/emote-sets/global", 200, SEVENTV_GLOBAL);
                answer("https://api.betterttv.net/3/cached/users/twitch/123", 200, BTTV_USER);
                answer("https://api.betterttv.net/3/cached/users/twitch/456", 200, BTTV_USER);
                answer("https://api.betterttv.net/3/cached/emotes/global", 200, BTTV_GLOBAL);
                ThreadPoolExecutor shared = (ThreadPoolExecutor) field("TwitchThirdParty", "POOL");
                CountDownLatch busy = new CountDownLatch(shared.getMaximumPoolSize());
                for (int i = 0; i < shared.getMaximumPoolSize(); i++) shared.execute(() -> {
                    busy.countDown();
                    try { hang.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                check(busy.await(2, TimeUnit.SECONDS), "shared pool busy");
                TwitchEmotes.onChatChannel("123");
                // A second channel right after: its lists don't wait for the first one's hanging request either.
                TwitchEmotes.onChatChannel("456");
                waitFor(() -> has("7:123") && has("b:123") && has("7:global") && has("b:global") && has("7:456") && has("b:456"),
                    "7TV and BetterTTV lists while FrankerFaceZ hangs");
                Map<?, ?> codes = resolved("123");
                check(codes.containsKey("Channel7") && codes.containsKey("ChannelB") && codes.containsKey("Global7")
                    && codes.containsKey("GlobalB"), "channel and global emotes of the services that answered: " + codes.keySet());
                check(!has("f:123"), "FrankerFaceZ still loading");
                hang.countDown();
                settle();
                check((Integer) entryField(entry("f:123"), "failures") == 1, "FrankerFaceZ's list failed on its own");
                check(ids("7:123").equals(List.of("lurk7s_abc123")), "7TV untouched by FrankerFaceZ's failure");
                break;
            }
            case "retry": {
                // A channel list that fails on joining is tried again within seconds, not after a minute, and a list
                // that had emotes keeps them while it fails.
                Settings.values.put("emotes_7tv", "true");
                answer("https://7tv.io/v3/emote-sets/global", 200, SEVENTV_GLOBAL);
                AtomicInteger attempt = new AtomicInteger();
                ANSWERS.put("https://7tv.io/v3/users/twitch/123", u -> attempt.incrementAndGet() == 1
                    ? new Object[]{503, ""} : new Object[]{200, SEVENTV_USER});
                TwitchEmotes.onChatChannel("123");
                settle();
                check(resolved("123").keySet().equals(Set.of("Global7")), "only the global emotes after the failure");
                check((Integer) entryField(entry("7:123"), "failures") == 1, "one failure counted");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 1, "not asked again at once");
                advance(4_000);
                resolved("123");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 1, "not before the first retry time");
                advance(1_001);
                resolved("123");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 2, "asked again after five seconds");
                check(resolved("123").containsKey("Channel7"), "channel emotes after the retry");
                check((Integer) entryField(entry("7:123"), "failures") == 0, "an answer clears the failures");

                // Failing again later keeps the emotes it had; the retry times grow.
                advance(10 * 60_000L + 1);
                ANSWERS.put("https://7tv.io/v3/users/twitch/123", u -> { throw new SocketTimeoutException("timeout"); });
                resolved("123");
                settle();
                check(resolved("123").containsKey("Channel7"), "a list that fails keeps its emotes");
                advance(5_001);
                resolved("123");
                settle();
                check((Integer) entryField(entry("7:123"), "failures") == 2 && calls("https://7tv.io/v3/users/twitch/123") == 4, "second failure");
                advance(6_000);
                resolved("123");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 4, "the second retry waits fifteen seconds");
                // Joining the channel again tries a failed list at once.
                TwitchEmotes.onChatChannel("123");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 5, "joining the channel tries again");
                TwitchEmotes.onChatChannel("123");
                settle();
                check(calls("https://7tv.io/v3/users/twitch/123") == 5, "but not right after a failure");
                check((Integer) entryField(entry("7:123"), "failures") == 3, "third failure");
                break;
            }
            case "cancelled": {
                // A request given up because its React Native rows went away (the worker's owner check failing as
                // the request opens) is not a failure: nothing is stored, and the next caller loads the list at once
                // instead of a minute later.
                Settings.values.put("emotes_7tv", "true");
                answer("https://7tv.io/v3/users/twitch/123", 200, SEVENTV_USER);
                answer("https://7tv.io/v3/emote-sets/global", 200, SEVENTV_GLOBAL);
                @SuppressWarnings("unchecked") ThreadLocal<BooleanSupplier> owner =
                    (ThreadLocal<BooleanSupplier>) field("TwitchThirdParty", "ACTIVE_REQUEST");
                owner.set(() -> false);
                call("load", new Class<?>[]{int.class, String.class, String.class}, 0, "123", "7:123");
                owner.remove();
                check(!has("7:123"), "a cancelled load stores nothing");
                check(calls("https://7tv.io/v3/users/twitch/123") == 0, "and makes no request");
                resolved("123");
                settle();
                check(ids("7:123").equals(List.of("lurk7s_abc123")), "the next caller loads it at once");
                break;
            }
            default: throw new AssertionError(args[0]);
        }
        System.out.println("PASS emote loading " + args[0]);
    }
}
