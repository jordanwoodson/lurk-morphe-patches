import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes "Avoid stitched ads" on ART with the patched APK's classes: the real IVS body consumer
 * (ByteBufferConsumer.consume, with Lurk's hook) and the extension behind it.
 * 1. The playlist model, offsets and URLs on generated Twitch-style playlists.
 * 2. Against Twitch, signed out: a live channel's real usher master and media playlist pass through the
 *    hooked consumer unchanged; the same media playlist with an ad break appended makes the extension
 *    open a new playlist session (a new usher request with the same token), and the player gets that
 *    session's playlist without ads, numbered to continue its own; its next request is redirected there.
 * 3. With the switch off, the consumer runs as it was.
 * CLASSPATH=probe.jar:patched.apk app_process / AdBackupProbe
 */
public final class AdBackupProbe {
    private static final String EXT = "io.github.jordanwoodson.extension.shared.twitch.TwitchBackupStream";
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";
    private static final Map<String, Boolean> switches = new HashMap<>();

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void settings() throws Exception {
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("getBoolean")) return switches.getOrDefault((String) values[0], (Boolean) values[1]);
                if (method.getName().equals("getString")) return values[1];
                throw new UnsupportedOperationException(method.getName());
            });
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        set(null, Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings"), "context", context);
    }

    private static Method method(String type, String name, Class<?>... parameters) throws Exception {
        Method method = Class.forName(type).getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object call(Object owner, String name, Object... values) throws Exception {
        for (Method method : owner.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == values.length) {
                method.setAccessible(true);
                return method.invoke(owner, values);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    // Generated playlists -------------------------------------------------------------------------

    private static final long T0 = 1792093403797L;

    private static String time(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }

    /**
     * A Twitch-style live media playlist: [live] stream segments numbered from [first] (2 s each, the
     * first at [pdt]), then [ads] ad segments, the way Twitch stitches a break in.
     */
    private static String playlist(long first, long pdt, int live, int ads, String host) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n");
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        if (ads == 0) out.append("#EXT-X-TWITCH-LIVE-SEQUENCE:").append(first).append('\n');
        out.append("#EXT-X-TWITCH-ELAPSED-SECS:100.000\n");
        out.append("#EXT-X-DATERANGE:ID=\"playlist-session-1\",CLASS=\"twitch-session\",START-DATE=\"")
            .append(time(pdt)).append("\",END-ON-NEXT=YES,X-TV-TWITCH-SESSIONID=\"1\"\n");
        long seq = first;
        for (int i = 0; i < live + ads; i++, seq++) {
            long at = pdt + 2000L * i;
            if (i == live) {
                out.append("#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"")
                    .append(time(at)).append("\",DURATION=30.235,X-TV-TWITCH-AD-ROLL-TYPE=\"MIDROLL\"\n");
                out.append("#EXT-X-DISCONTINUITY\n");
            }
            out.append("#EXT-X-PROGRAM-DATE-TIME:").append(time(at)).append('\n');
            out.append("#EXTINF:2.000,").append(i < live ? "live" : "Amazon|2474283100494").append('\n');
            out.append("https://").append(host).append("/v1/segment/").append(seq).append(".ts\n");
        }
        if (ads == 0) out.append("#EXT-X-TWITCH-PREFETCH:https://").append(host).append("/v1/segment/next.ts\n");
        return out.toString();
    }

    /** A stream's playlist at the end of a break: [ads] ad segments from [first] at [pdt], then [live] stream ones. */
    private static String afterBreak(long first, long pdt, int ads, int live) {
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n");
        out.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n');
        out.append("#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"").append(time(pdt))
            .append("\",DURATION=30.235\n");
        out.append("#EXT-X-DATERANGE:ID=\"source-1\",CLASS=\"twitch-stream-source\",START-DATE=\"").append(time(pdt))
            .append("\",X-TV-TWITCH-STREAM-SOURCE=\"Amazon|1\"\n");
        for (int i = 0; i < ads + live; i++) {
            long at = pdt + 2000L * i;
            if (i == ads) {
                out.append("#EXT-X-DATERANGE:ID=\"source-2\",CLASS=\"twitch-stream-source\",START-DATE=\"").append(time(at))
                    .append("\",X-TV-TWITCH-STREAM-SOURCE=\"live\"\n#EXT-X-DISCONTINUITY\n");
            }
            out.append("#EXT-X-PROGRAM-DATE-TIME:").append(time(at)).append('\n');
            out.append("#EXTINF:2.000,").append(i < ads ? "Amazon|1" : "live").append('\n');
            out.append("https://a/v1/segment/").append(first + i).append(".ts\n");
        }
        return out.toString();
    }

    private static void model() throws Exception {
        Method parse = method(EXT + "$Playlist", "parse", String.class);
        Class<?> playlistType = Class.forName(EXT + "$Playlist");
        Method align = method(EXT, "align", playlistType, playlistType, long.class);

        Object player = parse.invoke(null, playlist(4240, T0, 6, 4, "a"));
        check((Long) field(player, "firstSeq") == 4240 && (Long) call(player, "lastSeq") == 4249, "Sequence numbers");
        check((Boolean) call(player, "hasAdAfter", 4245L) && !(Boolean) call(player, "hasAdAfter", 4249L),
            "Ads must be found by their EXTINF title");
        // A new session numbered like the live stream: same numbers.
        Object same = parse.invoke(null, playlist(4236, T0 - 8000, 15, 0, "b"));
        check((Long) align.invoke(null, player, same, 4245L) == 0, "Same live numbering must give offset 0");
        // A session numbered from elsewhere: matched by program date-time.
        Object other = parse.invoke(null, playlist(12, T0 - 4000, 15, 0, "b"));
        check((Long) align.invoke(null, player, other, 4245L) == 4240 - 14, "Offset by program date-time");
        // A pre-roll: nothing served yet, the new session keeps the player's first number.
        Object preroll = parse.invoke(null, playlist(0, T0, 0, 4, "a"));
        check((Long) align.invoke(null, preroll, other, -1L) == -12, "Pre-roll offset");
        // No shared time: the new session's live edge follows what was served.
        Object far = parse.invoke(null, playlist(500, T0 + 3600_000, 15, 0, "b"));
        check((Long) align.invoke(null, player, far, 4245L) == 4246 - 512, "Offset without shared time");

        Object renumbered = call(other, "renumbered", 4226L);
        String text = (String) call(renumbered, "text");
        check(text.contains("#EXT-X-MEDIA-SEQUENCE:4238\n") && !text.contains("LIVE-SEQUENCE"),
            "Renumbering must move the media sequence and drop the live sequence");
        check(text.contains("TWITCH-PREFETCH"), "Prefetch tags must stay");
        Object trimmed = call(renumbered, "trimmedBelow", 4243L);
        String kept = (String) call(trimmed, "text");
        check(kept.contains("#EXT-X-MEDIA-SEQUENCE:4243\n") && kept.contains("twitch-session")
            && kept.indexOf("twitch-session") < kept.indexOf("#EXTINF"), "Trimming must keep the session date range first");
        check(((List<?>) field(trimmed, "segments")).size() == 10, "Trimming must drop exactly the old segments");

        Object allStale = call(renumbered, "trimmedBelow", 5000L);
        String emptyWindow = (String) call(allStale, "text");
        check(emptyWindow.contains("#EXT-X-MEDIA-SEQUENCE:5000\n")
            && ((List<?>) field(allStale, "segments")).isEmpty()
            && !emptyWindow.contains("TWITCH-PREFETCH"), "All-stale windows must advance without old segments or prefetch");

        // Back from the break: the stream's own playlist, numbered on (5000..) by its ads, is matched by time
        // with what was served from picture-by-picture (4240.. from T0, 2 s each).
        Method resume = method(EXT, "resume", Map.class, String.class, playlistType, playlistType, long.class);
        Object served = parse.invoke(null, playlist(4240, T0, 26, 0, "p"));
        Object over = parse.invoke(null, afterBreak(5000, T0 + 12000, 16, 4));
        Object back = resume.invoke(null, new HashMap<>(), "m", over, served, 4265L);
        check(back != null && (Long) field(back, "offset") == 4262 - 5016 && !(Boolean) field(back, "backup"),
            "The stream after its break must continue the served numbering by time");
        String resumed = (String) call(field(back, "first"), "text");
        check(!resumed.contains("twitch-stitched-ad") && !resumed.contains("SOURCE=\"Amazon") && resumed.contains("SOURCE=\"live\""),
            "Old ad cues must be dropped, the live source cue kept:\n" + resumed);
        check(resume.invoke(null, new HashMap<>(), "m", parse.invoke(null, playlist(5000, T0 + 12000, 0, 16, "a")), served, 4265L) == null,
            "A stream still in its break must not be resumed");
        check(resume.invoke(null, new HashMap<>(), "m", over, served, 4255L) == null,
            "Ads after what the player was served must keep it on the backup");
        Object none = parse.invoke(null, "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:9\n");
        Object edge = resume.invoke(null, new HashMap<>(), "m", over, none, 4265L);
        check(edge != null && (Long) field(edge, "offset") == 4266 - 5017, "A lost backup continues at the stream's live edge");

        Method withQuery = method(EXT, "withQuery", String.class, String[].class);
        String url = (String) withQuery.invoke(null, "https://usher.ttvnw.net/api/channel/hls/a.m3u8?token=t&sig=s&cache_buster=1&play_session_id=x",
            new String[]{"play_session_id", "y", "cache_buster", "2"});
        check(url.contains("token=t") && url.contains("sig=s") && url.contains("play_session_id=y")
            && url.contains("cache_buster=2") && !url.contains("=x") && !url.contains("buster=1&"), "Query rewrite: " + url);
        Method valid = method(EXT, "tokenValid", String.class);
        long soon = System.currentTimeMillis() / 1000 + 30;
        long later = System.currentTimeMillis() / 1000 + 600;
        check(!(Boolean) valid.invoke(null, "https://usher.ttvnw.net/api/channel/hls/a.m3u8?token="
            + URLEncoder.encode("{\"expires\":" + soon + "}", "UTF-8")), "A token expiring within a minute is not reused");
        check((Boolean) valid.invoke(null, "https://usher.ttvnw.net/api/channel/hls/a.m3u8?token="
            + URLEncoder.encode("{\"expires\":" + later + "}", "UTF-8")), "A valid token is reused");
        Method isMaster = method(EXT, "isUsherMaster", String.class);
        check((Boolean) isMaster.invoke(null, "https://usher.ttvnw.net/api/v2/channel/hls/a.m3u8?x=1")
            && !(Boolean) isMaster.invoke(null, "https://usher.ttvnw.net/vod/1.m3u8")
            && !(Boolean) isMaster.invoke(null, "https://usher.ttvnw.net.example/api/channel/hls/a.m3u8"), "Usher master URLs");
        System.out.println("PASS: playlist model, offsets by time and for pre-rolls, renumbering, trimming, usher URLs");
    }

    // The real IVS consumer -----------------------------------------------------------------------

    /** What the IVS consumer handed to the player for one body. */
    static final class Delivery {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int calls;
        boolean last;
        Exception error;

        String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Object request(String url) throws Exception {
        return Class.forName("com.amazonaws.ivs.net.Request").getConstructor(String.class, String.class).newInstance(url, "GET");
    }

    /** Runs the patched ByteBufferConsumer.consume for [body] answering [request]. */
    private static Delivery consume(Object request, byte[] body) throws Exception {
        Class<?> providerType = Class.forName("com.amazonaws.ivs.net.ByteBufferConsumer$Provider");
        final int[] at = {0};
        Object provider = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType}, (proxy, m, values) -> {
            if (!m.getName().equals("writeTo")) return null;
            ByteBuffer buffer = (ByteBuffer) values[0];
            if (at[0] >= body.length) return -1;
            int n = Math.min(buffer.remaining(), Math.min(1000, body.length - at[0]));
            buffer.put(body, at[0], n);
            at[0] += n;
            return n;
        });
        Class<?> callbackType = Class.forName("com.amazonaws.ivs.net.ReadCallback");
        Delivery delivery = new Delivery();
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, m, values) -> {
            switch (m.getName()) {
                case "onData":
                    if (values[0] instanceof ByteBuffer) {
                        ByteBuffer buffer = (ByteBuffer) values[0];
                        check(buffer.isDirect(), "IVS gets direct buffers");
                        byte[] chunk = new byte[buffer.remaining()];
                        buffer.get(chunk);
                        delivery.bytes.write(chunk, 0, chunk.length);
                        delivery.calls++;
                        delivery.last = (Boolean) values[1];
                    }
                    return null;
                case "onError":
                    delivery.error = (Exception) values[0];
                    return null;
                case "getTimeout":
                    return 10;
                default:
                    return null;
            }
        });
        Class<?> consumerType = Class.forName("com.amazonaws.ivs.net.ByteBufferConsumer");
        Constructor<?> constructor = consumerType.getDeclaredConstructor(request.getClass(), providerType);
        constructor.setAccessible(true);
        Object consumer = constructor.newInstance(request, provider);
        Method consume = consumerType.getDeclaredMethod("consume", callbackType);
        consume.setAccessible(true);
        consume.invoke(consumer, callback);
        check(delivery.error == null, "Consumer error: " + delivery.error);
        check(delivery.last, "The last buffer must be flagged");
        return delivery;
    }

    // Twitch ----------------------------------------------------------------------------------------

    private static String http(String url, String json) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        if (json != null) {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Client-ID", CLIENT_ID);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (connection.getResponseCode() != 200) {
            InputStream error = connection.getErrorStream();
            String detail = error == null ? "" : new String(error.readAllBytes(), StandardCharsets.UTF_8);
            throw new AssertionError(url.substring(0, Math.min(60, url.length())) + " answered "
                + connection.getResponseCode() + " " + detail.substring(0, Math.min(300, detail.length())));
        }
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            for (int n; (n = in.read(chunk)) >= 0; ) out.write(chunk, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String gql(String query, JSONObject variables) throws Exception {
        JSONObject body = new JSONObject().put("query", query);
        if (variables != null) body.put("variables", variables);
        return http("https://gql.twitch.tv/gql", body.toString());
    }

    private static List<String[]> segments(String text) {
        List<String[]> found = new ArrayList<>();
        Matcher m = Pattern.compile("#EXT-X-PROGRAM-DATE-TIME:(\\S+)\\n#EXTINF:[^,]*,(.*)\\n(\\S+)").matcher(text);
        while (m.find()) found.add(new String[]{m.group(1), m.group(2), m.group(3)});
        return found;
    }

    private static long mediaSequence(String text) {
        Matcher m = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(text);
        check(m.find(), "No media sequence");
        return Long.parseLong(m.group(1));
    }

    /** [live] with an ad break stitched in after its last segment, the way Twitch does it. */
    private static String withBreak(String live) {
        List<String[]> listed = segments(live);
        String[] last = listed.get(listed.size() - 1);
        StringBuilder out = new StringBuilder();
        for (String line : live.split("\n")) {
            if (line.startsWith("#EXT-X-TWITCH-PREFETCH") || line.startsWith("#EXT-X-TWITCH-LIVE-SEQUENCE")) continue;
            out.append(line).append('\n');
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT);
        long at;
        try {
            at = format.parse(last[0]).getTime() + 2000;
        } catch (java.text.ParseException e) {
            throw new AssertionError(e);
        }
        out.append("#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"").append(time(at))
            .append("\",DURATION=30.235,X-TV-TWITCH-AD-ROLL-TYPE=\"MIDROLL\"\n#EXT-X-DISCONTINUITY\n");
        for (int i = 0; i < 3; i++) {
            out.append("#EXT-X-PROGRAM-DATE-TIME:").append(time(at + 2000L * i)).append('\n')
                .append("#EXTINF:2.000,Amazon|2474283100494\nhttps://example.invalid/ad").append(i).append(".ts\n");
        }
        return out.toString();
    }

    private static void live() throws Exception {
        JSONObject streams = new JSONObject(gql("query { streams(first: 5) { edges { node { broadcaster { login } } } } }", null));
        JSONArray edges = streams.getJSONObject("data").getJSONObject("streams").getJSONArray("edges");
        String channel = edges.getJSONObject(0).getJSONObject("node").getJSONObject("broadcaster").getString("login");
        JSONObject token = new JSONObject(gql("query($c: String!, $p: PlaybackAccessTokenParams!) { streamPlaybackAccessToken("
            + "channelName: $c, params: $p) { signature value } }", new JSONObject().put("c", channel)
            .put("p", new JSONObject().put("platform", "android").put("playerType", "mobile_player"))))
            .getJSONObject("data").getJSONObject("streamPlaybackAccessToken");
        String masterUrl = "https://usher.ttvnw.net/api/channel/hls/" + channel + ".m3u8?token="
            + URLEncoder.encode(token.getString("value"), "UTF-8") + "&sig=" + token.getString("signature")
            + "&player_backend=Core&allow_source=true&allow_audio_only=true&fast_bread=true&cache_buster=1"
            + "&play_session_id=" + UUID.randomUUID().toString().replace("-", "") + "&transcode_mode=cbr_v1";
        String master = http(masterUrl, null);

        // The master passes unchanged, in one flagged buffer from the hook.
        switches.put("avoid_stitched_ads", true);
        Delivery delivered = consume(request(masterUrl), master.getBytes(StandardCharsets.UTF_8));
        check(delivered.text().equals(master) && delivered.calls == 1, "The master must pass unchanged through the hook");
        // The top rendition: picture-by-picture has 360p at most, so the break is served at another quality.
        Matcher variant = Pattern.compile("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=\\d+x(\\d+)[^\\n]*\\n(\\S+)").matcher(master);
        String mediaUrl = null;
        int top = 0;
        while (variant.find()) {
            int height = Integer.parseInt(variant.group(1));
            if (height > top) { top = height; mediaUrl = variant.group(2); }
        }
        check(mediaUrl != null && top > 360, "No rendition above 360p in " + channel + "'s master");

        // A live media playlist passes unchanged.
        String live = http(mediaUrl, null);
        check(!segments(live).isEmpty() && segments(live).stream().allMatch(s -> s[1].equals("live")),
            channel + " is in an ad break right now; run the probe again later");
        delivered = consume(request(mediaUrl), live.getBytes(StandardCharsets.UTF_8));
        check(delivered.text().equals(live), "A live media playlist must pass unchanged");
        List<String[]> played = segments(live);
        long servedLast = mediaSequence(live) + played.size() - 1;

        // The same playlist with a break stitched in: the player gets picture-by-picture's playlist instead.
        String ad = withBreak(live);
        long started = System.currentTimeMillis();
        delivered = consume(request(mediaUrl), ad.getBytes(StandardCharsets.UTF_8));
        long took = System.currentTimeMillis() - started;
        String served = delivered.text();
        List<String[]> got = segments(served);
        check(!served.contains("Amazon") && !served.contains("stitched-ad") && !got.isEmpty()
            && got.stream().allMatch(s -> s[1].equals("live")), "The served playlist must have no ads:\n" + served);
        check(got.stream().noneMatch(s -> live.contains(s[2])), "The served segments must come from another session");
        long first = mediaSequence(served);
        check(first >= mediaSequence(live), "The media sequence must not go back");
        // The player's last segment and the backup's segment with that number cover the same time.
        String[] lastPlayed = played.get(played.size() - 1);
        int index = (int) (servedLast - first);
        check(index >= 0 && index < got.size() && got.get(index)[0].equals(lastPlayed[0]),
            "Segment " + servedLast + " must be the same time in both sessions");
        check(first + got.size() - 1 <= servedLast || served.contains("#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:"
            + got.get(index + 1)[0]), "The first segment from the backup must follow a discontinuity");
        System.out.println("  " + channel + " " + top + "p: switched to picture-by-picture in " + took + " ms, served "
            + first + ".." + (first + got.size() - 1) + " after " + servedLast);

        // The next request goes to picture-by-picture; the stream's own playlist (no real break) is checked
        // with it, found past its ads, and served again, numbered by time.
        Object next = request(mediaUrl);
        String target = (String) method(EXT, "requestUrl", Object.class, String.class).invoke(null, next, mediaUrl);
        check(!target.equals(mediaUrl) && target.contains(".m3u8"), "The next request must go to picture-by-picture");
        Thread.sleep(2500);
        String backup = http(target, null);
        check(segments(backup).stream().allMatch(s -> s[1].equals("live")), "Picture-by-picture must have no ads");
        delivered = consume(next, backup.getBytes(StandardCharsets.UTF_8));
        String again = delivered.text();
        String own = http(mediaUrl, null);
        List<String[]> later = segments(again);
        check(!again.contains("Amazon") && mediaSequence(again) >= first, "The returned playlist must not go back");
        check(later.stream().anyMatch(s -> own.contains(s[2])) && got.stream().noneMatch(g -> again.contains(g[2])),
            "After the break the player must get its own segments");
        int shared = 0;
        for (int j = 0; j < later.size(); j++) {
            for (int i = 0; i < got.size(); i++) {
                if (!got.get(i)[0].equals(later.get(j)[0])) continue;
                check(first + i == mediaSequence(again) + j, "A time must keep its number across sessions");
                shared++;
            }
        }
        check(shared > 0, "The returned playlist must share times with the backup's");
        String after = (String) method(EXT, "requestUrl", Object.class, String.class).invoke(null, request(mediaUrl), mediaUrl);
        check(after.equals(mediaUrl), "Back on its own session, the player's URL stays");
        String unrelated = "https://example.com/other.m3u8";
        check(unrelated.equals(method(EXT, "requestUrl", Object.class, String.class).invoke(null, request(unrelated), unrelated)),
            "Other requests must keep their URL");
        System.out.println("PASS: real master and live playlist unchanged; a stitched break moved the player to "
            + "picture-by-picture without ads, same time; next request redirected; back to its own stream after");

        // Switched off: a new stream is left alone and the consumer runs as it was (two buffers).
        switches.put("avoid_stitched_ads", false);
        String otherMaster = masterUrl.replace("cache_buster=1", "cache_buster=2");
        delivered = consume(request(otherMaster), master.getBytes(StandardCharsets.UTF_8));
        // The original consumer hands on each read (the fake source gives 1000 bytes at most) and an empty last one.
        int reads = (master.getBytes(StandardCharsets.UTF_8).length + 999) / 1000;
        check(delivered.text().equals(master) && delivered.calls == reads + 1, "Switched off, the original consumer must run");
        delivered = consume(request("https://example.com/a.ts"), new byte[]{1, 2, 3});
        check(delivered.calls == 2 && delivered.bytes.size() == 3, "Segments must use the original consumer");
        System.out.println("PASS: switched off and non-playlist bodies use the original consumer");
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        settings();
        model();
        live();
        System.exit(0);
    }
}
