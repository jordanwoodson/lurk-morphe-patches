package io.github.jordanwoodson.extension.shared.twitch;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * "Avoid stitched ads". Measured on Twitch's servers: an ad break the server stitches into a live stream
 * reaches every full-quality playlist session open when it starts, and a session opened during the
 * break gets ads of its own within seconds. Sessions of Twitch's own "picture-by-picture" player type
 * (Android, 360p at most), which the app shows in a small window during breaks, get neither.
 * <p>
 * Every Twitch player is an IVS player, and IVS fetches playlists through its own Java HTTP client.
 * Usher (master) playlists are read on the way, to know each media playlist's stream and rendition.
 * When a media playlist's next segments are ads, the player is served a picture-by-picture session's
 * playlist instead, renumbered to continue the player's sequence. At each refresh the player's own
 * playlist is checked too, and once its break is over the player gets it back, renumbered the same way.
 * The player doesn't reload; during the break it plays the stream at 360p, with sound.
 * <p>
 * It can't get stuck there: a break that outlasts any Twitch break, or a stream playlist that can't be
 * read, gets a new full-quality session instead, and a picture-by-picture session that stops answering
 * is dropped for the break. When no picture-by-picture session can be had in time, the stream's
 * playlist passes unchanged, with its ad cues, so "Mute stitched ads" still mutes and covers the break.
 * Streams started while the switch is off are left alone. Turning it off doesn't start new breaks; a
 * stream already switched (on a picture-by-picture session, or renumbered after one) keeps being served
 * until it ends, so the player never sees its media sequence jump back, and a break in progress returns
 * to the stream the usual way when it's over.
 * <p>
 * Only Twitch's own usher master playlists are read. A master loaded through Playlist proxy comes from
 * the proxy's host, so its stream is never tracked: with the proxy on, this does nothing and the proxy
 * decides what the playlists hold.
 */
@SuppressWarnings("unused")
public final class TwitchBackupStream {
    private static final String KEY = "avoid_stitched_ads";
    private static final String USHER_HOST = "usher.ttvnw.net";
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String TOKEN_QUERY = "query StreamAccessTokenQuery($channelName: String!, "
            + "$params: PlaybackAccessTokenParams!) { streamPlaybackAccessToken(channelName: $channelName, "
            + "params: $params) { signature value } }";
    /** The player type Twitch plays during breaks, and the platform it's asked for with. */
    private static final String BACKUP_TYPE = "picture-by-picture";
    private static final String BACKUP_PLATFORM = "android";
    /** Each switch must finish well inside IVS's 10 s request timeout. */
    private static final long BUDGET_MS = 6000;
    /** After a failed switch, the next try waits this long; the break plays (muted) meanwhile. */
    private static final long RETRY_MS = 15000;
    /** Longer than any Twitch break (3 minutes at most): then a new full-quality session takes over. */
    private static final long MAX_BREAK_MS = 240000;
    private static final long FRESH_RETRY_MS = 20000;
    private static final int MAIN_FAILURES = 3;
    private static final long NO_TIME = Long.MIN_VALUE;

    /** A retired response with no accepted window must use IVS's error path, never synthetic HLS. */
    private static final class UnavailablePlaylist extends RuntimeException { }

    private TwitchBackupStream() {
    }

    // State ---------------------------------------------------------------------------------------

    /** A playlist session the player can be served from. */
    static final class Source {
        final Map<String, Rendition> renditions;
        /** Served media sequence number = this session's number + offset. */
        final long offset;
        /** The playlist fetched while switching to it, served right away. */
        final Playlist first;
        /** A picture-by-picture session, used only during a break. */
        final boolean backup;
        final String masterUrl;

        Source(Map<String, Rendition> renditions, long offset, Playlist first, boolean backup, String masterUrl) {
            this.renditions = renditions;
            this.offset = offset;
            this.first = first;
            this.backup = backup;
            this.masterUrl = masterUrl;
        }
    }

    /** One usher load. The state monitor is never held during provider I/O. */
    static final class Stream {
        final String masterUrl;
        final String channel;
        /** Only bounded operation workers take this monitor, never an injected request hook. */
        final Object processing = new Object();
        /** Incremented on every source transition, including an unanswered-backup fallback. */
        long generation;
        /** The player's own session as loaded, served unchanged. */
        final Source own;
        /** What the player is served from now. */
        volatile Source current;
        /** The full-quality session to return to after the break. */
        Source main;
        long breakStart;
        volatile long retryAfter;
        long freshAfter;
        int mainFailures;

        Stream(String masterUrl, String channel, Map<String, Rendition> renditions) {
            this.masterUrl = masterUrl;
            this.channel = channel;
            this.own = new Source(renditions, 0, null, false, masterUrl);
            this.current = own;
            this.main = own;
        }
    }

    /** A media playlist the player polls, and what it was served. */
    static final class Media {
        final Stream stream;
        final Rendition rendition;
        long servedFirst = -1;
        long servedLast = -1;
        Source lastSource;
        /** Last accepted body, used when an interleaved response belongs to a retired source. */
        volatile String lastPlaylist;
        /** Requests sent to a backup since one was answered. */
        int unanswered;
        /** Where served segments change sessions. */
        final List<Long> discontinuities = new ArrayList<>();

        Media(Stream stream, Rendition rendition) {
            this.stream = stream;
            this.rendition = rendition;
        }
    }

    static final class Rendition {
        final String id;
        final String url;
        final int height;
        final long bandwidth;

        Rendition(String id, String url, int height, long bandwidth) {
            this.id = id;
            this.url = url;
            this.height = height;
            this.bandwidth = bandwidth;
        }
    }

    /** Known media playlists, by URL without query. */
    private static final Map<String, Media> media = lru(256);
    /** The session each IVS media playlist request was sent to. */
    private static final Map<Object, Source> requested = new WeakHashMap<>();

    private static <K, V> Map<K, V> lru(int size) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        });
    }

    // Injection points ----------------------------------------------------------------------------

    /**
     * Injection point: IVS's OkHttp client, with the URL of a request it is about to send. A media playlist
     * goes to the session its stream is served from.
     */
    public static String requestUrl(Object request, String url) {
        if (url == null || !url.contains(".m3u8")) return url;
        try {
            Media known = media.get(key(url));
            if (known == null) return url;
            Stream stream = known.stream;
            synchronized (stream) {
                if (!handled(stream)) return url;
                Source current = stream.current;
                if (current.backup && ++known.unanswered > MAIN_FAILURES) {
                    stream.current = stream.main;
                    stream.generation++;
                    stream.retryAfter = TwitchRequest.now() + RETRY_MS;
                    current = stream.current;
                    Logger.d("ads: picture-by-picture stream of " + stream.channel + " not answering, the break plays");
                }
                Rendition target = match(current.renditions, known.rendition);
                if (target == null) return url;
                synchronized (requested) {
                    requested.put(request, current);
                }
                return current == stream.own ? url : target.url;
            }
        } catch (RuntimeException e) {
            Logger.d("ads: playlist request left as is: " + e);
            return url;
        }
    }

    /**
     * Injection point: the start of IVS's response body consumer. True when it delivered the body itself
     * (a Twitch playlist it read, and changed if needed), false to let the consumer run as it was.
     */
    public static boolean consume(Object consumer, Object request, Object callback) {
        String url;
        Source source;
        boolean master;
        Media known;
        try {
            url = (String) method(request, "getUrl").invoke(request);
            if (url == null || !url.contains(".m3u8")) return false;
            synchronized (requested) {
                source = requested.remove(request);
            }
            master = isUsherMaster(url);
            known = master ? null : media.get(key(url));
            if (master ? !Settings.isEnabled(KEY) : known == null || !handled(known.stream)) return false;
            if ((Boolean) method(request, "isCancelled").invoke(request)) return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }

        final String requestUrl = url;
        final Source requestSource = source;
        final Media requestMedia = known;
        final boolean isMaster = master;
        final Stream requestStream = known == null ? null : known.stream;
        long deadline = TwitchRequest.now() + BUDGET_MS;
        byte[] body = TwitchRequest.run(deadline,
                () -> handled(requestStream) && !cancelled(request), () -> read(consumer));
        if (body == null) {
            // The consumer was taken over: an expired read must still answer the player, or it waits.
            if (!cancelled(request)) fail(request, callback, new IOException("Lurk: playlist read expired or unavailable"));
            return true;
        }
        AtomicBoolean unavailable = new AtomicBoolean();
        byte[] out = TwitchRequest.run(deadline,
                () -> handled(requestStream) && !cancelled(request), () -> {
            byte[] result = body;
            try {
                boolean gzip = body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
                String text = new String(gzip ? gunzip(body) : body, StandardCharsets.UTF_8);
                TwitchRequest.check();
                if (isMaster) {
                    onMaster(requestUrl, text);
                } else {
                    String served = onMedia(requestMedia,
                            requestSource == null ? requestMedia.stream.own : requestSource, text);
                    if (served != null) {
                        byte[] bytes = served.getBytes(StandardCharsets.UTF_8);
                        result = gzip ? gzip(bytes) : bytes;
                    }
                }
            } catch (UnavailablePlaylist e) {
                unavailable.set(true);
                return null;
            } catch (IOException | RuntimeException e) {
                Logger.d("ads: playlist passed unchanged: " + e);
            }
            TwitchRequest.check();
            return result;
        });
        if (cancelled(request)) return true;
        if (unavailable.get()) {
            fail(request, callback, new IOException("Lurk: retired playlist unavailable"));
            return true;
        }
        if (out == null) {
            // A failed/expired switch must retain the existing ad fallback once the body is read.
            // Never replay an in-flight body belonging to a source that another rendition retired.
            Source answered = source == null && known != null ? known.stream.own : source;
            if (known == null || answered == known.stream.current) {
                out = body;
                if (known != null && !answered.backup) {
                    known.stream.retryAfter = TwitchRequest.now() + RETRY_MS;
                }
            } else {
                String accepted = known.lastPlaylist;
                if (accepted == null) {
                    fail(request, callback, new IOException("Lurk: retired playlist unavailable"));
                    return true;
                }
                byte[] bytes = accepted.getBytes(StandardCharsets.UTF_8);
                try {
                    out = body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b
                            ? gzip(bytes) : bytes;
                } catch (IOException e) { fail(request, callback, e); return true; }
            }
        }
        deliver(request, callback, out);
        return true;
    }

    private static boolean cancelled(Object request) {
        try { return (Boolean) method(request, "isCancelled").invoke(request); }
        catch (ReflectiveOperationException | RuntimeException e) { return true; }
    }

    // Master playlists ----------------------------------------------------------------------------

    static boolean isUsherMaster(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            return USHER_HOST.equalsIgnoreCase(uri.getHost()) && path != null && path.endsWith(".m3u8")
                    && (path.startsWith("/api/channel/hls/") || path.startsWith("/api/v2/channel/hls/"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A playlist's identity: its URL without the query. Twitch's media playlists carry none. */
    static String key(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static void onMaster(String url, String text) {
        String path = Uri.parse(url).getPath();
        String channel = path.substring(path.lastIndexOf('/') + 1, path.length() - ".m3u8".length())
                .toLowerCase(Locale.ROOT);
        Map<String, Rendition> renditions = renditions(text);
        Stream stream = new Stream(url, channel, renditions);
        for (Rendition rendition : renditions.values()) {
            media.put(key(rendition.url), new Media(stream, rendition));
        }
        Logger.d("ads: watching " + channel + " for stitched ads, " + renditions.size() + " renditions");
    }

    /** An HLS master playlist's renditions, by Twitch's id: STABLE-VARIANT-ID, else the VIDEO group. */
    static Map<String, Rendition> renditions(String master) {
        Map<String, Rendition> found = new LinkedHashMap<>();
        String[] lines = master.split("\r?\n");
        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            String uri = lines[i + 1].trim();
            if (uri.isEmpty() || uri.startsWith("#")) continue;
            String line = lines[i];
            String id = attribute(line, "STABLE-VARIANT-ID");
            if (id == null) id = attribute(line, "VIDEO");
            if (id == null) id = attribute(line, "IVS-NAME");
            if (id == null) id = key(uri);
            String resolution = attribute(line, "RESOLUTION");
            int x = resolution == null ? -1 : resolution.indexOf('x');
            int height = x < 0 ? 0 : (int) number(resolution.substring(x + 1), 0);
            found.put(id, new Rendition(id, uri, height, (long) number(attribute(line, "BANDWIDTH"), 0)));
        }
        return found;
    }

    /** An attribute of an HLS tag line, without quotes, or null. */
    static String attribute(String line, String name) {
        int at = line.indexOf(name + "=");
        while (at > 0 && line.charAt(at - 1) != ',' && line.charAt(at - 1) != ':') {
            at = line.indexOf(name + "=", at + 1);
        }
        if (at < 0) return null;
        int start = at + name.length() + 1;
        if (start < line.length() && line.charAt(start) == '"') {
            int end = line.indexOf('"', start + 1);
            return end < 0 ? null : line.substring(start + 1, end);
        }
        int end = line.indexOf(',', start);
        return line.substring(start, end < 0 ? line.length() : end);
    }

    private static double number(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The same rendition in another session, else the closest one of the same kind (video or audio). */
    static Rendition match(Map<String, Rendition> renditions, Rendition wanted) {
        Rendition same = renditions.get(wanted.id);
        if (same != null) return same;
        Rendition best = null;
        for (Rendition candidate : renditions.values()) {
            if ((candidate.height == 0) != (wanted.height == 0)) continue;
            int distance = Math.abs(candidate.height - wanted.height);
            if (best == null || distance < Math.abs(best.height - wanted.height)
                    || distance == Math.abs(best.height - wanted.height) && candidate.bandwidth > best.bandwidth) {
                best = candidate;
            }
        }
        return best;
    }

    // Media playlists -----------------------------------------------------------------------------

    /**
     * A media playlist response from [source], the session the request went to: what to serve the
     * player, or null to serve it unchanged.
     */
    static String onMedia(Media known, Source source, String text) {
        return onMedia(known, source, Playlist.parse(text), true);
    }

    private static String onMedia(Media known, Source source, Playlist playlist, boolean redirect) {
        Stream stream = known.stream;
        // Serialization is confined to workers whose callers have a deadline. requestUrl only needs
        // the separate, short state monitor and can retire a blocked worker's source immediately.
        synchronized (stream.processing) {
            long now = TwitchRequest.now();
            Source current;
            long generation;
            boolean tryBackup;
            synchronized (stream) {
                if (!TwitchRequest.active()) return known.lastPlaylist;
                // Switched off before this stream was ever changed: the player keeps its own playlist.
                if (!handled(stream)) return null;
                current = stream.current;
                generation = stream.generation;
                if (source == current && source.backup) known.unanswered = 0;
                if (source == current && !source.backup && !playlist.hasAdAfter(known.servedLast - source.offset)) {
                    return serve(known, playlist, source);
                }
                // Switched off, a changed stream is still served, but no new break starts.
                tryBackup = Settings.isEnabled(KEY) && now >= stream.retryAfter;
            }
            if (current != source) {
                Playlist switched = redirect ? fetch(current, known, now + BUDGET_MS) : null;
                synchronized (stream) {
                    if (!current(stream, current, generation)) return retired(known);
                }
                if (switched != null) return onMedia(known, current, switched, false);
                return retired(known);
            }
            if (source.backup) return duringBreak(known, source, playlist, now, generation);
            Source backup = tryBackup
                    ? openBackup(stream, known, playlist.renumbered(source.offset), now + BUDGET_MS) : null;
            synchronized (stream) {
                if (!current(stream, source, generation)) return retired(known);
                if (tryBackup) {
                    if (backup != null && Settings.isEnabled(KEY)) {
                        stream.main = source;
                        stream.current = backup;
                        stream.generation++;
                        stream.breakStart = now;
                        stream.mainFailures = 0;
                        Logger.blocked("stitched ad break on " + stream.channel + ", picture-by-picture stream until it's over");
                        return serve(known, backup.first, backup);
                    }
                    stream.retryAfter = now + RETRY_MS;
                    Logger.d("ads: no picture-by-picture stream for " + stream.channel + ", the break plays");
                }
                return serve(known, playlist, source);
            }
        }
    }

    private static String retired(Media known) {
        String accepted = known.lastPlaylist;
        if (accepted == null) throw new UnavailablePlaylist();
        return accepted;
    }

    /** Called under the state monitor before committing any result from outside it. */
    private static boolean current(Stream stream, Source source, long generation) {
        return TwitchRequest.active() && stream.current == source && stream.generation == generation;
    }

    /** A backup refresh; network I/O never retains the state monitor used by requestUrl. */
    private static String duringBreak(Media known, Source backup, Playlist playlist, long now, long generation) {
        Stream stream = known.stream;
        Source mainSource;
        synchronized (stream) {
            if (!current(stream, backup, generation)) return retired(known);
            mainSource = stream.main;
        }
        boolean lost = playlist.segments.isEmpty();
        Playlist main = fetch(mainSource, known, now + BUDGET_MS);
        Source back = back(stream, known, mainSource, backup, generation, main,
                playlist.renumbered(backup.offset), lost, now);
        synchronized (stream) {
            if (!current(stream, backup, generation)) return retired(known);
            if (back != null) {
                stream.current = back;
                stream.main = back;
                stream.generation++;
                Logger.d("ads: break over on " + stream.channel + " after " + (now - stream.breakStart) / 1000
                        + " s, back to its stream");
                return serve(known, back.first, back);
            }
            if (lost && main != null) {
                stream.current = mainSource;
                stream.generation++;
                stream.retryAfter = now + RETRY_MS;
                Logger.d("ads: picture-by-picture stream of " + stream.channel + " lost, the break plays");
                return serve(known, main, mainSource);
            }
            return serve(known, playlist, backup);
        }
    }

    /** Resolve a return session; its caller validates the generation again before committing it. */
    private static Source back(Stream stream, Media known, Source mainSource, Source backup, long generation,
                               Playlist main, Playlist served, boolean lost, long now) {
        long deadline = now + BUDGET_MS;
        Source resumed = main == null ? null
                : resume(mainSource.renditions, mainSource.masterUrl, main, served, known.servedLast);
        synchronized (stream) {
            if (!current(stream, backup, generation)) return null;
            if (main == null) stream.mainFailures++;
            else stream.mainFailures = 0;
            if (resumed != null) return resumed;
            boolean overdue = lost || now - stream.breakStart > MAX_BREAK_MS || stream.mainFailures >= MAIN_FAILURES;
            if (!overdue || now < stream.freshAfter) return null;
            stream.freshAfter = now + FRESH_RETRY_MS;
        }
        String masterUrl = tokenValid(stream.masterUrl) ? newSession(stream.masterUrl) : newToken(stream, null, deadline);
        String master = masterUrl == null ? null : http(masterUrl, null, deadline, stream);
        if (master == null) return null;
        Map<String, Rendition> renditions = renditions(master);
        Rendition target = match(renditions, known.rendition);
        String text = target == null ? null : http(target.url, null, deadline, stream);
        if (text == null) return null;
        Playlist fresh = Playlist.parse(text);
        if (fresh.hasAdAfter(Long.MIN_VALUE)) return null;
        Logger.d("ads: new full-quality session for " + stream.channel);
        return resume(renditions, masterUrl, fresh, served, known.servedLast);
    }

    /**
     * [main] numbered to continue [served], if it is past its ads and shares a segment time with what was
     * served (or nothing was, from a lost backup: then its live edge follows); null if not yet. Its old ad
     * cues are dropped: the player is past them.
     */
    static Source resume(Map<String, Rendition> renditions, String masterUrl, Playlist main, Playlist served,
                         long servedLast) {
        List<Segment> segments = main.segments;
        if (segments.isEmpty() || segments.get(segments.size() - 1).ad) return null;
        int lastAd = -1;
        for (int i = 0; i < segments.size(); i++) if (segments.get(i).ad) lastAd = i;
        long offset = NO_TIME;
        if (served.segments.isEmpty()) {
            offset = servedLast + 1 - segments.get(Math.max(lastAd + 1, segments.size() - 3)).seq;
        }
        for (int i = segments.size() - 1; i > lastAd && offset == NO_TIME; i--) {
            Segment segment = segments.get(i);
            if (segment.pdt == NO_TIME) continue;
            for (Segment other : served.segments) {
                if (other.pdt != NO_TIME && Math.abs(other.pdt - segment.pdt) <= 500) {
                    offset = other.seq - segment.seq;
                    break;
                }
            }
        }
        if (offset == NO_TIME || main.renumbered(offset).hasAdAfter(servedLast)) return null;
        return new Source(renditions, offset, main.withoutAdCues(), false, masterUrl);
    }

    /** What the player gets of [playlist] from [source]; records it. Null when that is the playlist as is. */
    private static String serve(Media known, Playlist playlist, Source source) {
        if (!TwitchRequest.active()) return known.lastPlaylist;
        if (playlist.lastSeq() + source.offset < known.servedFirst && known.lastPlaylist != null) {
            return known.lastPlaylist;
        }
        if (known.lastSource != null && known.lastSource != source && known.servedLast >= 0
                && !known.discontinuities.contains(known.servedLast + 1)) {
            known.discontinuities.add(known.servedLast + 1);
        }
        known.lastSource = source;
        if (source == known.stream.own && known.discontinuities.isEmpty()
                && playlist.firstSeq >= known.servedFirst) {
            known.servedFirst = Math.max(known.servedFirst, playlist.firstSeq);
            known.servedLast = Math.max(known.servedLast, playlist.lastSeq());
            known.lastPlaylist = playlist.text();
            return null;
        }
        Playlist out = playlist.renumbered(source.offset).trimmedBelow(Math.max(0, known.servedFirst));
        if (!source.backup && source != known.stream.own) out = out.withoutAdCues();
        for (int i = known.discontinuities.size() - 1; i >= 0; i--) {
            long seq = known.discontinuities.get(i);
            if (seq < out.firstSeq) known.discontinuities.remove(i);
            else out.markDiscontinuity(seq);
        }
        known.servedFirst = Math.max(known.servedFirst, out.firstSeq);
        known.servedLast = Math.max(known.servedLast, out.lastSeq());
        known.lastPlaylist = out.text();
        return known.lastPlaylist;
    }

    private static Playlist fetch(Source source, Media known, long deadline) {
        Rendition target = match(source.renditions, known.rendition);
        String text = target == null ? null : http(target.url, null, deadline, known.stream);
        return text == null ? null : Playlist.parse(text);
    }

    /**
     * Opens a picture-by-picture session for the stream and checks that its playlist for [known]'s
     * rendition has no ads. [served] is the player's playlist with the ads, numbered as the player has it.
     */
    private static Source openBackup(Stream stream, Media known, Playlist served, long deadline) {
        for (int attempt = 0; attempt < 2 && TwitchRequest.active() && TwitchRequest.now() < deadline; attempt++) {
            String masterUrl = newToken(stream, BACKUP_TYPE, deadline);
            String master = masterUrl == null ? null : http(masterUrl, null, deadline, stream);
            if (master == null) continue;
            Map<String, Rendition> renditions = renditions(master);
            Rendition target = match(renditions, known.rendition);
            String text = target == null ? null : http(target.url, null, deadline, stream);
            if (text == null) continue;
            Playlist playlist = Playlist.parse(text);
            if (playlist.segments.isEmpty() || playlist.hasAdAfter(Long.MIN_VALUE)) {
                Logger.d("ads: the picture-by-picture stream of " + stream.channel + " has ads too");
                continue;
            }
            return new Source(renditions, align(served, playlist, known.servedLast), playlist, true, masterUrl);
        }
        return null;
    }

    /**
     * The offset that numbers [backup] in the player's sequence: the last stream segment before the
     * player's next ad matches the backup's segment with the same time. Without one (a pre-roll, or no
     * shared time), the backup's live edge continues what the player was served.
     */
    static long align(Playlist served, Playlist backup, long servedLast) {
        Segment before = null;
        for (Segment segment : served.segments) {
            if (segment.ad && segment.seq > servedLast) break;
            if (!segment.ad) before = segment;
        }
        if (before != null && before.pdt != NO_TIME) {
            for (Segment segment : backup.segments) {
                if (segment.pdt != NO_TIME && Math.abs(segment.pdt - before.pdt) <= 500) return before.seq - segment.seq;
            }
        }
        if (servedLast < 0) return served.firstSeq - backup.firstSeq;
        return servedLast + 1 - Math.max(backup.firstSeq, backup.lastSeq() - 2);
    }

    /** The player's usher URL as a new request; Twitch opens a new playlist session for each. */
    static String newSession(String masterUrl) {
        return withQuery(masterUrl, "play_session_id", UUID.randomUUID().toString().replace("-", ""),
                "cache_buster", String.valueOf(System.currentTimeMillis()));
    }

    /** [url] with these query parameters set (name, value, name, value...), the others kept in order. */
    static String withQuery(String url, String... pairs) {
        Uri uri = Uri.parse(url);
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            boolean replaced = false;
            for (int i = 0; i < pairs.length; i += 2) replaced |= pairs[i].equals(name);
            if (!replaced) builder.appendQueryParameter(name, uri.getQueryParameter(name));
        }
        for (int i = 0; i < pairs.length; i += 2) builder.appendQueryParameter(pairs[i], pairs[i + 1]);
        return builder.build().toString();
    }

    /** Whether the signed token in an usher URL is good for another minute. */
    static boolean tokenValid(String masterUrl) {
        try {
            String token = Uri.parse(masterUrl).getQueryParameter("token");
            long expires = token == null ? 0 : new JSONObject(token).optLong("expires", 0);
            return expires * 1000 > System.currentTimeMillis() + 60000;
        } catch (JSONException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The player's usher URL as a new session with a new token for the channel: of [playerType] on
     * Android, or (null) of the player's own type and platform. The token request sends the app's
     * Client-ID and nothing else: no account, no device id.
     */
    private static String newToken(Stream stream, String playerType, long deadline) {
        try {
            JSONObject params;
            if (playerType != null) {
                params = new JSONObject().put("platform", BACKUP_PLATFORM).put("playerType", playerType);
            } else {
                JSONObject old = new JSONObject(Uri.parse(stream.masterUrl).getQueryParameter("token"));
                params = new JSONObject().put("platform", old.optString("platform", "android"))
                        .put("playerType", old.optString("player_type", "mobile_player"));
            }
            JSONObject body = new JSONObject().put("operationName", "StreamAccessTokenQuery").put("query", TOKEN_QUERY)
                    .put("variables", new JSONObject().put("channelName", stream.channel).put("params", params));
            String response = http(GQL, body.toString(), deadline, stream);
            if (response == null) return null;
            JSONObject token = new JSONObject(response).getJSONObject("data").getJSONObject("streamPlaybackAccessToken");
            return newSession(withQuery(stream.masterUrl, "token", token.getString("value"),
                    "sig", token.getString("signature")));
        } catch (JSONException | RuntimeException e) {
            Logger.d("ads: no playback token for " + stream.channel + ": " + e);
            return null;
        }
    }

    // Media playlist model ------------------------------------------------------------------------

    static final class Segment {
        final List<String> tags;
        final String uri;
        final long seq;
        final long pdt;
        final boolean ad;

        Segment(List<String> tags, String uri, long seq, long pdt, boolean ad) {
            this.tags = tags;
            this.uri = uri;
            this.seq = seq;
            this.pdt = pdt;
            this.ad = ad;
        }
    }

    /** A Twitch HLS media playlist: header, segments with the tags before each, trailing tags (prefetch). */
    static final class Playlist {
        final List<String> header = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        final List<String> trailer = new ArrayList<>();
        long firstSeq;

        static Playlist parse(String text) {
            Playlist playlist = new Playlist();
            long seq = 0;
            List<String> tags = new ArrayList<>();
            boolean inSegments = false;
            for (String line : text.split("\r?\n")) {
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    seq = (long) number(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), 0);
                    playlist.firstSeq = seq;
                    playlist.header.add(line);
                } else if (!inSegments && isHeader(line)) {
                    playlist.header.add(line);
                } else if (line.startsWith("#")) {
                    inSegments = true;
                    tags.add(line);
                } else {
                    playlist.segments.add(new Segment(tags, line, seq++, pdt(tags), ad(tags)));
                    tags = new ArrayList<>();
                }
            }
            playlist.trailer.addAll(tags);
            return playlist;
        }

        private static boolean isHeader(String line) {
            return line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION")
                    || line.startsWith("#EXT-X-TARGETDURATION") || line.startsWith("#EXT-X-TWITCH-LIVE-SEQUENCE")
                    || line.startsWith("#EXT-X-TWITCH-ELAPSED-SECS") || line.startsWith("#EXT-X-TWITCH-TOTAL-SECS")
                    || line.startsWith("#EXT-X-START") || line.startsWith("#EXT-X-PLAYLIST-TYPE")
                    || line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE");
        }

        private static long pdt(List<String> tags) {
            for (String tag : tags) {
                if (!tag.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) continue;
                try {
                    return parseTime(tag.substring("#EXT-X-PROGRAM-DATE-TIME:".length()).trim());
                } catch (RuntimeException e) {
                    return NO_TIME;
                }
            }
            return NO_TIME;
        }

        /** Twitch titles stream segments "live", and ad segments after the ad. */
        private static boolean ad(List<String> tags) {
            for (String tag : tags) {
                if (!tag.startsWith("#EXTINF:")) continue;
                int comma = tag.indexOf(',');
                String title = comma < 0 ? "" : tag.substring(comma + 1).trim();
                return !title.isEmpty() && !title.equals("live");
            }
            return false;
        }

        /** Date ranges that tell the app an ad plays: the ad, its quartiles, and the ad as stream source. */
        private static boolean adCue(String tag) {
            return tag.startsWith("#EXT-X-DATERANGE") && (tag.contains("CLASS=\"twitch-stitched-ad\"")
                    || tag.contains("CLASS=\"twitch-ad-quartile\"") || tag.contains("CLASS=\"twitch-stream-source\"")
                    && !tag.contains("X-TV-TWITCH-STREAM-SOURCE=\"live\""));
        }

        long lastSeq() {
            return segments.isEmpty() ? firstSeq - 1 : segments.get(segments.size() - 1).seq;
        }

        boolean hasAdAfter(long seq) {
            for (Segment segment : segments) if (segment.ad && segment.seq > seq) return true;
            return false;
        }

        private Playlist copyHeader(long first, boolean liveSequence) {
            Playlist out = new Playlist();
            out.firstSeq = first;
            for (String line : header) {
                if (!liveSequence && line.startsWith("#EXT-X-TWITCH-LIVE-SEQUENCE:")) continue;
                out.header.add(line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ? "#EXT-X-MEDIA-SEQUENCE:" + first : line);
            }
            out.trailer.addAll(trailer);
            return out;
        }

        /** This playlist numbered [by] higher. Twitch's live sequence header no longer applies then. */
        Playlist renumbered(long by) {
            if (by == 0) return this;
            Playlist out = copyHeader(firstSeq + by, false);
            for (Segment s : segments) out.segments.add(new Segment(s.tags, s.uri, s.seq + by, s.pdt, s.ad));
            return out;
        }

        /**
         * Without the segments numbered below [seq], so the media sequence never goes back. Their
         * playlist-wide date ranges (session, timestamp) move to the first segment kept.
         */
        Playlist trimmedBelow(long seq) {
            if (firstSeq >= seq) {
                return this;
            }
            Playlist out = copyHeader(seq, true);
            if (segments.isEmpty() || lastSeq() < seq) out.trailer.clear();
            List<String> carried = new ArrayList<>();
            for (Segment s : segments) {
                if (s.seq < seq) {
                    for (String tag : s.tags) if (tag.startsWith("#EXT-X-DATERANGE") && !adCue(tag)) carried.add(tag);
                    continue;
                }
                List<String> tags = s.tags;
                if (!carried.isEmpty()) {
                    tags = new ArrayList<>(carried);
                    tags.addAll(s.tags);
                    carried.clear();
                }
                out.segments.add(new Segment(tags, s.uri, s.seq, s.pdt, s.ad));
            }
            return out;
        }

        /** Without the date ranges that would tell the app an ad plays. */
        Playlist withoutAdCues() {
            Playlist out = copyHeader(firstSeq, true);
            for (Segment s : segments) {
                List<String> tags = new ArrayList<>();
                for (String tag : s.tags) if (!adCue(tag)) tags.add(tag);
                out.segments.add(new Segment(tags, s.uri, s.seq, s.pdt, s.ad));
            }
            return out;
        }

        /** Marks the segment numbered [seq], if listed, as the first after a discontinuity. */
        void markDiscontinuity(long seq) {
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                if (s.seq != seq || s.tags.contains("#EXT-X-DISCONTINUITY")) continue;
                List<String> tags = new ArrayList<>(s.tags);
                tags.add(0, "#EXT-X-DISCONTINUITY");
                segments.set(i, new Segment(tags, s.uri, s.seq, s.pdt, s.ad));
            }
        }

        String text() {
            StringBuilder out = new StringBuilder();
            for (String line : header) out.append(line).append('\n');
            for (Segment s : segments) {
                for (String tag : s.tags) out.append(tag).append('\n');
                out.append(s.uri).append('\n');
            }
            for (String line : trailer) out.append(line).append('\n');
            return out.toString();
        }
    }

    /** A UTC time as Twitch writes it (2026-10-15T19:53:23.797Z), in ms. */
    static long parseTime(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ROOT).parse(value).getTime();
        } catch (ParseException e) {
            throw new IllegalArgumentException(value);
        }
    }

    // IVS body plumbing ---------------------------------------------------------------------------

    private static final Map<String, Method> methods = new HashMap<>();
    private static Field provider;

    private static synchronized Method method(Object owner, String name) throws NoSuchMethodException {
        String id = owner.getClass().getName() + "." + name;
        Method found = methods.get(id);
        if (found == null) {
            found = owner.getClass().getMethod(name);
            found.setAccessible(true);
            methods.put(id, found);
        }
        return found;
    }

    private static Method interfaceMethod(Object owner, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> type : owner.getClass().getInterfaces()) {
            try {
                Method found = type.getMethod(name, parameters);
                found.setAccessible(true);
                return found;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** The whole body, from the consumer's provider (the OkHttp response). */
    private static byte[] read(Object consumer) throws IOException, ReflectiveOperationException {
        Object source;
        synchronized (TwitchBackupStream.class) {
            if (provider == null) {
                Field field = consumer.getClass().getDeclaredField("provider");
                field.setAccessible(true);
                provider = field;
            }
            source = provider.get(consumer);
        }
        Method writeTo = interfaceMethod(source, "writeTo", ByteBuffer.class);
        ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        while (true) {
            TwitchRequest.check();
            buffer.clear();
            int read;
            try {
                read = (Integer) writeTo.invoke(source, buffer);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                throw e;
            }
            TwitchRequest.check();
            if (read < 0) return out.toByteArray();
            buffer.flip();
            int n = buffer.remaining();
            buffer.get(chunk, 0, n);
            if (n > TwitchRequest.MAX_BYTES - out.size()) throw new IOException("Twitch playlist too large");
            out.write(chunk, 0, n);
        }
    }

    /** Hands IVS the body the way its consumer does: direct buffers, the last one flagged. */
    private static void deliver(Object request, Object callback, byte[] body) {
        try {
            Method onData = interfaceMethod(callback, "onData", ByteBuffer.class, boolean.class);
            Object lock = method(request, "lock").invoke(request);
            Method cancelled = method(request, "isCancelled");
            int at = 0;
            do {
                int n = Math.min(16384, body.length - at);
                ByteBuffer buffer = ByteBuffer.allocateDirect(16384);
                buffer.put(body, at, n);
                buffer.flip();
                at += n;
                synchronized (lock) {
                    if ((Boolean) cancelled.invoke(request)) return;
                    onData.invoke(callback, buffer, at >= body.length);
                }
            } while (at < body.length);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.d("ads: playlist not delivered: " + e);
            fail(request, callback, new IOException("Lurk: playlist not delivered", e));
        }
    }

    private static void fail(Object request, Object callback, Exception error) {
        try {
            Method onError = interfaceMethod(callback, "onError", Exception.class);
            synchronized (method(request, "lock").invoke(request)) {
                if (!(Boolean) method(request, "isCancelled").invoke(request)) onError.invoke(callback, error);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.d("ads: playlist error not delivered: " + e);
        }
    }

    // HTTP ----------------------------------------------------------------------------------------

    /** GET [url], or POST [json] to GQL, within the time left before [deadline]. Null on any failure. */
    private static String http(String url, String json, long deadline) {
        return http(url, json, deadline, null);
    }

    /** A request for [stream]'s work, given up when the stream is no longer handled. */
    private static String http(String url, String json, long deadline, Stream stream) {
        return TwitchRequest.http(url, json, TwitchRequest.CLIENT_ID, deadline, () -> handled(stream));
    }

    /**
     * Whether [stream] is served by this class: while the switch is on, and after that for as long as
     * the stream is on another session than its own or renumbered, since serving its own playlist as it
     * is then would move the player's media sequence. Null (a new stream) follows the switch.
     */
    static boolean handled(Stream stream) {
        return Settings.isEnabled(KEY) || stream != null && (stream.current != stream.own || stream.main != stream.own);
    }

    private static byte[] gunzip(byte[] body) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return TwitchRequest.read(in);
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(body);
        }
        return out.toByteArray();
    }
}
