package io.github.jordanwoodson.extension.shared.kick;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.UUID;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Kick's chat page. Kick draws chat in a WebView showing https://chat.mobile.kick.com, which has its own realtime
 * connection and draws every chat line itself, so the switches that change chat lines work in that page: a script
 * (assets/lurk-chat.js, copied in by the patches) runs there with the switches that are on.
 */
@SuppressWarnings("unused")
public final class KickChat {
    private static final String HOST = "chat.mobile.kick.com";
    private static final String SCRIPT = "lurk-chat.js";
    private static final String IGNORE_CLEARS = "chat_ignore_clears";
    static final String SEVEN_TV = "emotes_7tv";
    /** A switch, then the page's elements for the chat lines it hides. */
    private static final String[][] HIDE = {
            {"hide_sub_messages", ".subscription-container", ".subgift-container"},
            {"hide_host_messages", ".host-container"},
            {"hide_reward_redemptions", ".reward-redeem-container"},
    };
    /** Kick channel ID -> its slug, and its owner's Kick user ID when the response had it, from KickHttp. */
    private static final Map<String, String> SLUGS = boundedMap();
    private static final Map<String, String> OWNERS = boundedMap();
    private static final Map<WebView, Page> PAGES = new WeakHashMap<>();
    private static WeakReference<WebView> active = new WeakReference<>(null);

    private static Map<String, String> boundedMap() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>(256, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > 256; }
        });
    }

    private static final class Page {
        final String url, generation = UUID.randomUUID().toString();
        final Object channel, slug;
        Page(String url, JSONObject options) {
            this.url = url; channel = options.opt("channelId"); slug = options.opt("sevenTvChannel");
        }
    }
    private static String script;

    private KickChat() {
    }

    /**
     * Injection point: react-native-webview's RNCWebViewClient.onPageStarted and onPageFinished, after the super
     * call. At page start the WebView can still be showing the previous document, so the script runs at both and
     * applies itself once per page.
     */
    public static synchronized void onPageStarted(WebView view, String url) {
        onPageDisposed(view);
        onPage(view, url);
    }

    /** End only this WebView's authority, even when no replacement page starts. */
    public static synchronized void onPageDisposed(WebView view) {
        PAGES.remove(view);
        if (active.get() == view) {
            active.clear();
            KickComposer.send("channel", "{\"enabled\":false}");
        }
    }

    public static synchronized void onPage(WebView view, String url) {
        try {
            if (view == null || url == null) return;
            Uri uri = Uri.parse(url);
            // Kick's KICKs gift preview is the same page with preview=true, showing that one gift.
            if (!HOST.equals(uri.getHost()) || uri.getQueryParameter("preview") != null) return;
            JSONObject options = options(uri);
            if (options.length() == 0) return;
            if (options.optBoolean("sevenTv")) {
                Page page = PAGES.get(view);
                if (page == null || !page.url.equals(url)) {
                    page = new Page(url, options); PAGES.put(view, page); active = new WeakReference<>(view);
                    JSONObject context = new JSONObject().put("enabled", true).put("generation", page.generation)
                            .put("channelId", page.channel == null ? JSONObject.NULL : page.channel);
                    KickComposer.send("channel", context.toString());
                }
                options.put("generation", page.generation);
            }
            String source = script(view.getContext());
            if (source == null) return;
            view.evaluateJavascript(source + "(" + options + ");", result -> Logger.d("chat page: " + result));
        } catch (RuntimeException | JSONException e) {
            Logger.d("chat page: " + e);
        }
    }

    /** Consume only our catalog message; every ordinary stock bridge message keeps its original path. */
    public static synchronized boolean onMessage(WebView view, String message, String sourceUrl) {
        if (message == null || message.length() > 1024 * 1024 || !message.startsWith("{")) return false;
        JSONObject data;
        try { data = new JSONObject(message); }
        catch (JSONException e) { return false; }
        String type = data.optString("type");
        if (!"lurk7tvCatalog".equals(type) && !"lurk7tvDispose".equals(type)) return false;
        try {
            Page page = PAGES.get(view);
            if (!on(SEVEN_TV) || page == null || active.get() != view || !chatOrigin(sourceUrl) ||
                    !chatOrigin(view.getUrl()) || !page.generation.equals(data.optString("generation")) ||
                    !java.util.Objects.equals(page.channel, data.opt("channelId")) ||
                    !java.util.Objects.equals(page.slug, data.opt("slug"))) return true;
            if ("lurk7tvDispose".equals(type)) {
                onPageDisposed(view);
                return true;
            }
            JSONObject clean = KickCatalog.sanitize(data, page.generation, page.channel, page.slug);
            if (clean == null) return true;
            KickComposer.send("catalog", clean.toString());
        } catch (RuntimeException e) {
            Logger.d("Kick composer: rejected catalog");
        }
        return true;
    }

    private static boolean chatOrigin(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost()) && uri.getPort() == -1 && uri.getUserInfo() == null;
    }

    static boolean on(String key) {
        return Settings.isRegistered(key) && Settings.isEnabled(key);
    }

    /** A Kick channel response (KickHttp): remembers which channel it is, for 7TV. */
    static void onChannel(String json) {
        try {
            JSONObject channel = new JSONObject(json);
            String channelId = id(channel.opt("id"));
            String slug = channel.optString("slug");
            if (channelId == null || slug.isEmpty()) return;
            String owner = id(channel.opt("user_id"));
            if (owner != null) OWNERS.put(channelId, owner);
            if (!slug.equals(SLUGS.put(channelId, slug))) Logger.d("7tv: channel " + channelId + " is " + slug);
        } catch (JSONException e) {
            Logger.d("7tv: unreadable channel response");
        }
    }

    private static JSONObject options(Uri uri) throws JSONException {
        JSONObject options = new JSONObject();
        if (on(IGNORE_CLEARS)) options.put("ignoreClears", true);
        JSONArray hide = new JSONArray();
        for (String[] lines : HIDE) {
            if (!on(lines[0])) continue;
            for (int i = 1; i < lines.length; i++) hide.put(lines[i]);
        }
        if (hide.length() > 0) options.put("hide", hide);
        if (on(SEVEN_TV)) {
            // The app passes the page a JSON config with the channel ID; 7TV knows channels by their owner.
            String config = uri.getQueryParameter("config");
            String channelId = null;
            try {
                channelId = config == null ? null : id(new JSONObject(config).opt("channelId"));
            } catch (JSONException e) {
                // A malformed channel config must not disable independent chat controls.
                Logger.d("7tv: unreadable chat config, global emotes only");
            }
            String slug = channelId == null ? null : SLUGS.get(channelId);
            String owner = channelId == null ? null : OWNERS.get(channelId);
            if (slug == null) Logger.d("7tv: channel " + channelId + " unknown, global emotes only");
            options.put("sevenTv", true);
            options.put("channelId", channelId == null ? JSONObject.NULL : channelId);
            options.put("sevenTvChannel", slug == null ? JSONObject.NULL : slug);
            options.put("sevenTvUser", owner == null ? JSONObject.NULL : owner);
        }
        return options;
    }

    private static String id(Object value) {
        if (value instanceof Number) return String.valueOf(((Number) value).longValue());
        if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        return null;
    }

    private static synchronized String script(Context context) {
        if (script != null) return script;
        try (InputStream in = context.getAssets().open(SCRIPT)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            script = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.d("chat page: no script: " + e);
        }
        return script;
    }
}
