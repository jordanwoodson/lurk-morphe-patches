package io.github.jordanwoodson.extension.shared.twitch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.Base64;
import android.util.JsonReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * pronouns.alejo.io pronouns for chatters, shown as a small text badge. This needs one request per chatter
 * (their username), so it is opt-in, and every answer, including "no pronouns set", is cached for an hour.
 * Lookups for the same user are merged, run one at a time at most four a second on a background thread,
 * and are dropped when too many are waiting. A chatter's pronouns appear on their lines once known.
 */
final class TwitchPronouns {
    private static final String API = "https://api.pronouns.alejo.io/v1/";
    private static final long TTL_MS = 60 * 60_000L;
    private static final long ERROR_TTL_MS = 5 * 60_000L;
    private static final long LIST_RETRY_MS = 2 * 60_000L;
    private static final int READ_TIMEOUT_MS = 5_000;
    private static final int MAX_USERS = 2_000;
    private static final int MAX_WAITING = 50;
    private static final long MIN_GAP_MS = 250;
    private static final int BADGE_HEIGHT = 54;

    private static final Object LOCK = new Object();
    /** login -> pronoun IDs, least recently used first. Guarded by LOCK. */
    private static final LinkedHashMap<String, Lookup> USERS = new LinkedHashMap<String, Lookup>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lookup> eldest) {
            return size() > MAX_USERS;
        }
    };
    /** Logins being looked up. Guarded by LOCK. */
    private static final Set<String> PENDING = new HashSet<>();
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_WAITING), runnable -> {
                Thread thread = new Thread(runnable, "lurk-pronouns");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile long lastRequest;
    private static final Map<String,TwitchThirdParty.Demand> DEMAND = new HashMap<>();
    private static TwitchThirdParty.Demand namesDemand;
    private static final Map<String,TwitchThirdParty.Demand> IMAGE_DEMAND = new HashMap<>();

    /** Pronoun ID -> { subject, object, "singular" or "" }, loaded once from /v1/pronouns. */
    private static volatile Map<String, String[]> names;
    private static final AtomicBoolean NAMES_LOADING = new AtomicBoolean();
    private static volatile long namesRetryAt;
    /** Badge label ("he/him") -> PNG data URI. */
    private static final ConcurrentHashMap<String, String> IMAGES = new ConcurrentHashMap<>();
    private static final Set<String> IMAGES_PENDING = ConcurrentHashMap.newKeySet();

    static {
        LOOKUPS.allowCoreThreadTimeOut(true);
    }

    private TwitchPronouns() {
    }

    /**
     * The pronoun badge of {@code login} as { label, image URI }, or null when it isn't known (yet). Never
     * waits: an unknown or expired user is queued for a lookup.
     */
    static String[] badge(String login) { return badge(login,null); }

    static void requestRn(String login, java.util.function.BooleanSupplier wanted) {
        if (wanted != null && wanted.getAsBoolean() && Settings.isRegistered(TwitchBadges.KEY_PRONOUNS)) badge(login,wanted);
    }

    /** Passive: rendering a cached snapshot must never enqueue work. */
    static String[] cachedRn(String login) {
        if (!Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) || !isLogin(login)) return null;
        Lookup entry;
        synchronized (LOCK) { entry = USERS.get(login.toLowerCase(Locale.ROOT)); }
        Map<String,String[]> list = names;
        if (entry == null || entry.primary == null || list == null) return null;
        String label = label(list,entry.primary,entry.alt);
        String image = label == null ? null : IMAGES.get(label);
        return image == null ? null : new String[]{label,image};
    }

    private static String[] badge(String login, java.util.function.BooleanSupplier wanted) {
        if (!Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) || !isLogin(login)) return null;
        String key = login.toLowerCase(Locale.ROOT);
        Map<String, String[]> list = names(wanted);
        Lookup entry;
        TwitchThirdParty.Demand scheduled = null;
        synchronized (LOCK) {
            entry = USERS.get(key);
            if (entry == null || entry.expires < SystemClock.elapsedRealtime()) {
                TwitchThirdParty.Demand demand = DEMAND.get(key);
                if (demand == null) {
                    demand = new TwitchThirdParty.Demand(); DEMAND.put(key,demand); PENDING.add(key); scheduled = demand;
                }
                demand.add(wanted);
            }
            // A cached user may precede the names response. Retain only bounded cache-entry demand.
            if (entry != null && entry.primary != null && names == null) {
                if (entry.waitingForNames == null) entry.waitingForNames = new TwitchThirdParty.Demand();
                entry.waitingForNames.add(wanted);
            }
        }
        list = names;
        if (scheduled != null) schedule(key,scheduled);
        if (entry == null || entry.primary == null || list == null) return null;
        String label = label(list, entry.primary, entry.alt);
        if (label == null) return null;
        String image = IMAGES.get(label);
        if (image == null) {
            synchronized (IMAGE_DEMAND) {
                TwitchThirdParty.Demand pending = IMAGE_DEMAND.get(label);
                if (pending != null) pending.add(wanted);
                else {
                    TwitchThirdParty.Demand demand = new TwitchThirdParty.Demand(); demand.add(wanted);
                    IMAGE_DEMAND.put(label,demand); IMAGES_PENDING.add(label);
                    TwitchThirdParty.run("pronoun badge", () -> Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) && demand.getAsBoolean(),
                        () -> image(label), () -> { synchronized (IMAGE_DEMAND) { IMAGE_DEMAND.remove(label);IMAGES_PENDING.remove(label); } });
                }
            }
            return null;
        }
        return new String[]{label, image};
    }

    private static boolean isLogin(String login) {
        if (login == null || login.isEmpty() || login.length() > 25) return false;
        for (int i = 0; i < login.length(); i++) {
            char c = login.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    private static void schedule(String login, TwitchThirdParty.Demand demand) {
        try {
            LOOKUPS.execute(() -> lookup(login,demand));
        } catch (RejectedExecutionException e) {
            // Too many waiting: drop it. The chatter's next line asks again.
            synchronized (LOCK) {
                PENDING.remove(login); DEMAND.remove(login);
            }
        }
    }

    /** On the lookup thread: one user, spaced at least MIN_GAP_MS from the previous request. */
    private static void lookup(String login, TwitchThirdParty.Demand demand) {
        Lookup entry;
        try {
            long wait = lastRequest + MIN_GAP_MS - SystemClock.elapsedRealtime();
            if (wait > 0) Thread.sleep(wait);
            if (!Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) || !demand.getAsBoolean()) {
                synchronized (LOCK) { PENDING.remove(login); DEMAND.remove(login); }
                return;
            }
            lastRequest = SystemClock.elapsedRealtime();
            String[] ids = TwitchThirdParty.getJson(API + "users/" + login, TwitchPronouns::parseUser, new String[2],
                    READ_TIMEOUT_MS);
            entry = new Lookup(ids[0], ids[1], SystemClock.elapsedRealtime() + TTL_MS);
        } catch (IOException | RuntimeException e) {
            entry = new Lookup(null, null, SystemClock.elapsedRealtime() + ERROR_TTL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entry = new Lookup(null, null, SystemClock.elapsedRealtime() + ERROR_TTL_MS);
        }
        synchronized (LOCK) {
            if (entry.primary != null && names == null) entry.waitingForNames = demand;
            USERS.put(login, entry);
            TwitchThirdParty.catalogChanged();
            PENDING.remove(login); DEMAND.remove(login);
        }
        Map<String, String[]> list = names;
        if (Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) && demand.getAsBoolean() && entry.primary != null && list != null) {
            String label = label(list, entry.primary, entry.alt);
            if (label != null && !IMAGES.containsKey(label)) image(label);
        }
    }

    /** /v1/users/{login}: { "pronoun_id": "hehim", "alt_pronoun_id": "theythem"|null, ... }. */
    private static String[] parseUser(JsonReader reader) throws IOException {
        String[] ids = new String[2];
        if (!TwitchThirdParty.beginObject(reader)) return ids;
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("pronoun_id".equals(name)) ids[0] = TwitchThirdParty.string(reader);
            else if ("alt_pronoun_id".equals(name)) ids[1] = TwitchThirdParty.string(reader);
            else reader.skipValue();
        }
        reader.endObject();
        return ids;
    }

    /** The pronoun names, loading them once in the background; null until loaded. */
    private static synchronized Map<String, String[]> names(java.util.function.BooleanSupplier wanted) {
        Map<String, String[]> list = names;
        if (list != null || SystemClock.elapsedRealtime() < namesRetryAt) return list;
        if (namesDemand != null) { namesDemand.add(wanted); return null; }
        namesDemand = new TwitchThirdParty.Demand(); namesDemand.add(wanted);
        TwitchThirdParty.Demand demand = namesDemand;
        NAMES_LOADING.set(true);
        TwitchThirdParty.run("pronoun list", () -> Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) && demand.getAsBoolean(), () -> {
            try {
                names = TwitchThirdParty.getJson(API + "pronouns", TwitchPronouns::parseNames, null);
                TwitchThirdParty.catalogChanged();
                if (names == null) namesRetryAt = SystemClock.elapsedRealtime() + LIST_RETRY_MS;
                else {
                    completeWaitingImages(names);
                    Logger.d("badges: pronouns: " + names.size());
                }
            } catch (IOException | RuntimeException e) {
                namesRetryAt = SystemClock.elapsedRealtime() + LIST_RETRY_MS;
                Logger.d("badges: pronoun list unavailable: " + e);
            }
        }, () -> { synchronized (TwitchPronouns.class) { namesDemand = null; NAMES_LOADING.set(false); } });
        return null;
    }

    /** Names completion resumes already requested image work, without involving passive catalog reads. */
    private static void completeWaitingImages(Map<String,String[]> list) {
        Map<Lookup,TwitchThirdParty.Demand> waiting = new LinkedHashMap<>();
        synchronized (LOCK) {
            for (Lookup entry : USERS.values()) {
                if (entry.waitingForNames != null) {
                    waiting.put(entry,entry.waitingForNames);
                    entry.waitingForNames = null;
                }
            }
        }
        // USERS bounds this batch. Recheck each owner/setting and draw each distinct label at most once.
        for (Map.Entry<Lookup,TwitchThirdParty.Demand> item : waiting.entrySet()) {
            if (!Settings.isEnabled(TwitchBadges.KEY_PRONOUNS) || !item.getValue().getAsBoolean()) continue;
            Lookup entry = item.getKey();
            String label = label(list,entry.primary,entry.alt);
            if (label != null && !IMAGES.containsKey(label)) image(label);
        }
    }

    /** /v1/pronouns: { "hehim": { "name", "subject": "He", "object": "Him", "singular": false }, ... }. */
    private static Map<String, String[]> parseNames(JsonReader reader) throws IOException {
        Map<String, String[]> out = new HashMap<>();
        if (!TwitchThirdParty.beginObject(reader)) return null;
        while (reader.hasNext()) {
            String id = reader.nextName();
            if (!TwitchThirdParty.beginObject(reader)) continue;
            String[] pronoun = new String[3];
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "subject":
                        pronoun[0] = TwitchThirdParty.string(reader);
                        break;
                    case "object":
                        pronoun[1] = TwitchThirdParty.string(reader);
                        break;
                    case "singular":
                        pronoun[2] = TwitchThirdParty.bool(reader) ? "singular" : "";
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (pronoun[0] != null) out.put(id, pronoun);
        }
        reader.endObject();
        return out.isEmpty() ? null : out;
    }

    /** "he/him", "she/they" (with an alternative), "any" (singular), or null. */
    static String label(Map<String, String[]> list, String primary, String alt) {
        String[] main = list.get(primary);
        if (main == null) return null;
        String[] other = alt == null ? null : list.get(alt);
        String label;
        if (other != null) label = main[0] + "/" + other[0];
        else if ("singular".equals(main[2]) || main[1] == null) label = main[0];
        else label = main[0] + "/" + main[1];
        return label.toLowerCase(Locale.ROOT);
    }

    /** Draws {@code label} as a grey pill badge, stores it as a PNG data URI and marks it wide. */
    private static void image(String label) {
        try {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextSize(BADGE_HEIGHT * 0.62f);
            float padding = BADGE_HEIGHT * 0.28f;
            int width = (int) Math.ceil(text.measureText(label) + 2 * padding);
            Bitmap badge = Bitmap.createBitmap(width, BADGE_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(badge);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFF6E6E7A);
            float radius = BADGE_HEIGHT * 0.22f;
            canvas.drawRoundRect(0, 0, width, BADGE_HEIGHT, radius, radius, fill);
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = BADGE_HEIGHT / 2f - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(label, padding, baseline, text);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            badge.compress(Bitmap.CompressFormat.PNG, 100, out);
            badge.recycle();
            String uri = "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            TwitchEmotes.registerWide(uri, (float) width / BADGE_HEIGHT);
            IMAGES.put(label, uri);
            TwitchThirdParty.catalogChanged();
        } catch (RuntimeException e) {
            Logger.d("badges: pronoun badge failed: " + e);
        }
    }

    private static final class Lookup {
        final String primary;
        final String alt;
        final long expires;
        /** Guarded by LOCK; discarded when names complete or this bounded cache entry is evicted. */
        TwitchThirdParty.Demand waitingForNames;

        Lookup(String primary, String alt, long expires) {
            this.primary = primary;
            this.alt = alt;
            this.expires = expires;
        }
    }
}
