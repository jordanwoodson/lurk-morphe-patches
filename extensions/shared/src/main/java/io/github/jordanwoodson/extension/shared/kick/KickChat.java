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
import java.util.concurrent.ConcurrentHashMap;

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
    private static final ConcurrentHashMap<String, String> SLUGS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> OWNERS = new ConcurrentHashMap<>();
    private static String script;

    private KickChat() {
    }

    /**
     * Injection point: react-native-webview's RNCWebViewClient.onPageStarted and onPageFinished, after the super
     * call. At page start the WebView can still be showing the previous document, so the script runs at both and
     * applies itself once per page.
     */
    public static void onPage(WebView view, String url) {
        try {
            if (view == null || url == null) return;
            Uri uri = Uri.parse(url);
            // Kick's KICKs gift preview is the same page with preview=true, showing that one gift.
            if (!HOST.equals(uri.getHost()) || uri.getQueryParameter("preview") != null) return;
            JSONObject options = options(uri);
            if (options.length() == 0) return;
            String source = script(view.getContext());
            if (source == null) return;
            view.evaluateJavascript(source + "(" + options + ");", result -> Logger.d("chat page: " + result));
        } catch (RuntimeException | JSONException e) {
            Logger.d("chat page: " + e);
        }
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
