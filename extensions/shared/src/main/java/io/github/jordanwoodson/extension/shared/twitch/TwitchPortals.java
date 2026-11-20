package io.github.jordanwoodson.extension.shared.twitch;

import android.net.Uri;
import android.net.http.SslError;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;

import java.util.Locale;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Twitch's portals: the in-app web pages (Create Clip, Report, Sign up, passkeys and others), each a Capacitor
 * WebView on a page from m.twitch.tv. Two WebViewClients serve them, Capacitor's and the one of Twitch's own
 * portal view. Both report every certificate error to the portal as a failed load, the page's own or any
 * request's it makes, and Create Clip then shows "There was an error, please try again later" and closes.
 */
@SuppressWarnings("unused")
public final class TwitchPortals {
    static final String FIX_KEY = "fix_web_pages";

    private TwitchPortals() { }

    /**
     * Injection point (Fix web page errors), at the start of each portal client's onReceivedSslError: the error
     * for Twitch to handle, or null once Lurk has. A certificate error on a request to another host than the
     * page's (a tracker that an HTTPS-filtering ad blocker, VPN or DNS block page answers with its own
     * certificate) is cancelled here, never proceeded, and Twitch doesn't hear of it: the page carries on
     * without that one request. An error on the page's own host, or while the page's host isn't known yet,
     * goes to Twitch as before. A null error never comes from WebView; it is cancelled the way Twitch's own
     * handling (WebViewClient's) would and goes no further.
     */
    public static SslError sslError(WebView view, SslErrorHandler handler, SslError error) {
        if (error == null) {
            if (handler != null) handler.cancel();
            return null;
        }
        try {
            if (handler == null || !Settings.isEnabled(FIX_KEY)) return error;
            String failed = host(error.getUrl());
            if (failed == null || isPageHost(view, failed)) return error;
            handler.cancel();
            Logger.w("web page: ignored a certificate error (" + error.getPrimaryError() + ") from " + failed +
                    "; the page stays open without that request");
            return null;
        } catch (RuntimeException e) {
            Logger.w("web page: certificate error check failed", e);
            return error;
        }
    }

    /** Whether {@code failed} is the host of the page {@code view} shows or loads; true when that isn't known. */
    static boolean isPageHost(WebView view, String failed) {
        String page = view != null ? host(view.getUrl()) : null;
        String original = view != null ? host(view.getOriginalUrl()) : null;
        if (page == null && original == null) return true;
        return failed.equals(page) || failed.equals(original);
    }

    static String host(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            return lower(Uri.parse(url).getHost());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String lower(String host) {
        return host == null || host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }
}
