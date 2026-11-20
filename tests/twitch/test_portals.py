#!/usr/bin/env python3
"""Twitch's in-app web pages (TwitchPortals, issue 102) against WebView doubles (no APK).

Pins Fix web page errors: a certificate error on a request to another host than the page's is cancelled (never
proceeded) and kept from Twitch, which would close the page; an error on the page's own host (shown or still
loading, any letter case), with no page host known yet, without a URL or with the switch off goes to Twitch
unchanged.
"""
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'shared'))
from host_java import run

WEB = {
    'android/net/Uri.java': '''package android.net; public class Uri {
        private final java.net.URI uri; private Uri(java.net.URI u){uri=u;}
        public static Uri parse(String s){try{return new Uri(new java.net.URI(s));}
            catch(java.net.URISyntaxException e){throw new IllegalArgumentException(e);}}
        public String getHost(){return uri.getHost();} }''',
    'android/net/http/SslError.java': '''package android.net.http; public class SslError {
        private final int primary; private final String url;
        public SslError(int p,String u){primary=p;url=u;}
        public int getPrimaryError(){return primary;} public String getUrl(){return url;} }''',
    'android/webkit/SslErrorHandler.java': '''package android.webkit; public class SslErrorHandler {
        public int cancels,proceeds; public void cancel(){cancels++;} public void proceed(){proceeds++;} }''',
    'android/webkit/WebView.java': '''package android.webkit; public class WebView {
        public String url,original; public WebView(String u,String o){url=u;original=o;}
        public String getUrl(){return url;} public String getOriginalUrl(){return original;} }''',
}

PROBE = r'''
import android.net.http.SslError;
import android.webkit.*;
import io.github.jordanwoodson.extension.shared.settings.Settings;
import io.github.jordanwoodson.extension.shared.twitch.TwitchPortals;

public class TwitchPortalsProbe {
    static final String PAGE = "https://m.twitch.tv/wv/clip-creation?sid=1";

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    /** Runs the hook; true when Twitch gets the error (unchanged), false when Lurk kept it (cancelled). */
    static boolean toTwitch(WebView view, String failed, String what) {
        SslErrorHandler handler = new SslErrorHandler();
        SslError error = new SslError(3, failed);
        SslError result = TwitchPortals.sslError(view, handler, error);
        check(handler.proceeds == 0, what + ": never proceeds");
        if (result == null) {
            check(handler.cancels == 1, what + ": a kept error is cancelled once");
            return false;
        }
        check(result == error && handler.cancels == 0, what + ": Twitch gets its own error, its handler untouched");
        return true;
    }

    public static void main(String[] args) throws Exception {
        WebView view = new WebView(PAGE, PAGE);
        Settings.values.put("fix_web_pages", "true");
        check(!toTwitch(view, "https://spade.twitch.tv/track", "tracker on another host"), "subresource kept");
        check(!toTwitch(view, "https://k.twitchcdn.net/149e/p.js", "script on another host"), "other host kept");
        check(!toTwitch(view, "https://twitch.tv/x", "parent domain"), "a parent domain is another host");
        check(toTwitch(view, "https://m.twitch.tv/assets/a.js", "page host"), "page host goes to Twitch");
        check(toTwitch(view, PAGE, "page itself"), "main document goes to Twitch");
        check(toTwitch(new WebView("https://M.Twitch.TV/wv/x", null), "https://m.twitch.tv/wv/x", "case"), "host case");
        check(toTwitch(new WebView("https://m.twitch.tv/wv/old", "https://www.twitch.tv/wv/new"),
                "https://www.twitch.tv/wv/new", "loading page"), "the page being loaded counts as the page");
        check(toTwitch(new WebView(null, null), "https://spade.twitch.tv/track", "no page"), "unknown page: Twitch");
        check(toTwitch(new WebView("about:blank", null), "https://spade.twitch.tv/track", "blank"), "blank: Twitch");
        check(toTwitch(null, "https://spade.twitch.tv/track", "no view"), "no view: Twitch");
        check(toTwitch(view, null, "no url"), "error without URL: Twitch");
        check(toTwitch(view, "not a url", "bad url"), "error without host: Twitch");
        SslError error = new SslError(3, "https://spade.twitch.tv/track");
        check(TwitchPortals.sslError(view, null, error) == error, "no handler: Twitch");
        SslErrorHandler handler = new SslErrorHandler();
        check(TwitchPortals.sslError(view, handler, null) == null && handler.cancels == 1 && handler.proceeds == 0,
                "a null error is cancelled like WebViewClient's default and goes no further");
        Settings.values.put("fix_web_pages", "false");
        check(toTwitch(view, "https://spade.twitch.tv/track", "switch off"), "switch off: Twitch");
        Settings.values.remove("fix_web_pages");
        check(toTwitch(view, "https://spade.twitch.tv/track", "unregistered"), "patch not included: Twitch");

        System.out.println("PASS: certificate errors on other hosts kept (cancelled, never proceeded), the page's own and unknown ones to Twitch, switch off");
    }
}
'''

if __name__ == '__main__':
    run('TwitchPortalsProbe', ['twitch/TwitchPortals.java'], PROBE, extra=WEB)
