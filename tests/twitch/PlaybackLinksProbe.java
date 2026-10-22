import android.content.Intent;
import com.amazonaws.ivs.player.Player;
import com.amazonaws.ivs.player.Quality;
import android.view.View;
import android.view.ViewGroup;
import io.github.jordanwoodson.extension.shared.twitch.TwitchPlayback;
import io.github.jordanwoodson.extension.shared.twitch.TwitchLinks;
import io.github.jordanwoodson.extension.shared.Network;
import io.github.jordanwoodson.extension.shared.settings.Settings;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public final class PlaybackLinksProbe {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + "; actual=" + actual);
    }
    private static Player player(String... names) {
        Set<Quality> qualities = new LinkedHashSet<>();
        for (String name : names) qualities.add(new Quality(name));
        return new Player() {
            @Override public Set<Quality> getQualities() { return qualities; }
        };
    }
    /** "On mobile data": replaces the default on a metered network only when it is lower. */
    private static void meteredQuality() {
        // Same as on Wi-Fi (0): the network isn't even asked.
        Settings.quality = 1;
        Settings.metered = 0;
        Network.metered = true;
        Network.asked = 0;
        equal("chunked", TwitchPlayback.qualityDefault("auto"));
        equal(0, Network.asked);
        // Lower than Source: used on a metered network, not on Wi-Fi.
        Settings.metered = 480;
        equal("480p", TwitchPlayback.qualityDefault("auto"));
        equal("480p", TwitchPlayback.qualityPreferenceDefault("auto", "_live"));
        equal("480", TwitchPlayback.qualityPreferenceDefault("auto", "_clip"));
        equal("\"480p\"", TwitchPlayback.storageQualityDefault("preferred-video-quality"));
        Network.metered = false;
        equal("chunked", TwitchPlayback.qualityDefault("auto"));
        equal(null, TwitchPlayback.storageQualityDefault("preferred-video-quality"));
        // Twitch's own default (Auto) counts as highest: the mobile choice applies.
        Network.metered = true;
        Settings.quality = 0;
        equal("480p", TwitchPlayback.qualityDefault("auto"));
        Network.metered = false;
        equal("auto", TwitchPlayback.qualityDefault("auto"));
        // Not lower than the default: the default stays, and the network isn't asked.
        Network.metered = true;
        Network.asked = 0;
        Settings.quality = 360;
        equal("360p", TwitchPlayback.qualityDefault("auto"));
        Settings.metered = 360;
        equal("360p", TwitchPlayback.qualityDefault("auto"));
        equal(0, Network.asked);
        // 720p60 is above 720p and 1080p below 1080p60.
        Settings.quality = 720;
        Settings.metered = 72060;
        equal("720p", TwitchPlayback.qualityDefault("auto"));
        Settings.quality = 108060;
        Settings.metered = 1080;
        equal("1080p", TwitchPlayback.qualityDefault("auto"));
        // The native player resolves Lurk's choice; a mobile choice is an exact name and passes through.
        Settings.quality = 1;
        Settings.metered = 720;
        Player stream = player("1080p60 (source)", "720p60", "720p");
        equal("720p", TwitchPlayback.playerQuality("720p", stream));
        equal("1080p60 (source)", TwitchPlayback.playerQuality("chunked", stream));
        Settings.metered = 0;
        Network.metered = false;
    }

    public static void main(String[] args) {
        equal("never", TwitchPlayback.autoplayDefault(""));
        Settings.autoplay = false;
        equal("", TwitchPlayback.autoplayDefault(""));
        View progress = new View(1), time = new View(2), cancel = new View(3), manual = new View(4), title = new View(5);
        ViewGroup recommendation = new ViewGroup(0, progress, time, cancel, manual, title);
        recommendation.setVisibility(View.GONE);
        Settings.next = false;
        equal(false, TwitchPlayback.showManualRecommendation(recommendation));
        equal(View.GONE, recommendation.getVisibility());
        Settings.next = true;
        equal(true, TwitchPlayback.showManualRecommendation(recommendation));
        equal(View.VISIBLE, recommendation.getVisibility());
        equal(View.GONE, progress.getVisibility());
        equal(View.GONE, time.getVisibility());
        equal(View.GONE, cancel.getVisibility());
        equal(View.VISIBLE, manual.getVisibility());
        equal(View.VISIBLE, title.getVisibility());
        Settings.quality = 1;
        equal("chunked", TwitchPlayback.qualityDefault("auto"));
        equal("chunked", TwitchPlayback.qualityPreferenceDefault("auto", "_live"));
        equal("chunked", TwitchPlayback.qualityPreferenceDefault("auto", "_vod"));
        equal("source", TwitchPlayback.qualityPreferenceDefault("480", "_clip"));
        // React Native matches exact names; the source's name varies, so Source leaves the row absent.
        equal(null, TwitchPlayback.storageQualityDefault("preferred-video-quality"));
        equal(null, TwitchPlayback.storageQualityDefault("another-setting"));
        // The native player matches IVS names; the source rendition is named like "1080p60 (source)".
        Player stream = player("1080p60 (source)", "720p60", "480p");
        equal("1080p60 (source)", TwitchPlayback.playerQuality("chunked", stream));
        equal("1080p60 (source)", TwitchPlayback.playerQuality("1080p60", stream));
        equal("720p60", TwitchPlayback.playerQuality("720p60", stream));
        equal("480P", TwitchPlayback.playerQuality("480P", stream));
        equal("auto", TwitchPlayback.playerQuality("auto", stream));
        equal("1080p", TwitchPlayback.playerQuality("1080p", stream));
        equal("chunked", TwitchPlayback.playerQuality("chunked", player()));
        equal("chunked", TwitchPlayback.playerQuality("chunked", player("720p60", "480p")));
        equal("chunked", TwitchPlayback.playerQuality("chunked", new Object()));
        equal(null, TwitchPlayback.playerQuality(null, stream));
        Settings.quality = 72060;
        equal("720p60", TwitchPlayback.qualityDefault("auto"));
        equal("\"720p60\"", TwitchPlayback.storageQualityDefault("preferred-video-quality"));
        equal("720", TwitchPlayback.qualityPreferenceDefault("auto", "_clip"));
        equal("720p60", TwitchPlayback.playerQuality("720p60", player("1080p60 (source)", "720p60")));
        equal("720p60 (source)", TwitchPlayback.playerQuality("720p60", player("720p60 (source)", "480p")));
        Settings.quality = 480;
        equal("480p", TwitchPlayback.qualityDefault("480p"));
        Settings.quality = 0;
        equal("auto", TwitchPlayback.qualityDefault("auto"));
        equal("chunked", TwitchPlayback.playerQuality("chunked", stream));
        equal("1080p60", TwitchPlayback.playerQuality("1080p60", stream));
        equal("480", TwitchPlayback.qualityPreferenceDefault("480", "_clip"));
        equal("chunked", TwitchPlayback.qualityPreferenceDefault("chunked", "_clip"));
        equal("480p", TwitchPlayback.qualityDefault("480p"));
        equal(null, TwitchPlayback.storageQualityDefault("preferred-video-quality"));
        Settings.quality = -1;
        equal("auto", TwitchPlayback.qualityDefault("auto"));
        for (int code : new int[]{108060, 1080, 72060, 720, 480, 360, 160}) {
            Settings.quality = code;
            String expected = code > 10000 ? (code / 100) + "p60" : code + "p";
            equal(expected, TwitchPlayback.qualityDefault("auto"));
            // ClipQuality has no frame rates and no 160p; Twitch picks the closest one a clip has.
            String clip = code == 160 ? "240" : String.valueOf(code > 10000 ? code / 100 : code);
            equal(clip, TwitchPlayback.qualityPreferenceDefault("auto", "_clip"));
        }
        meteredQuality();
        equal(null, TwitchLinks.cleanShareText(null));
        equal("Watch https://www.twitch.tv/videos/123?t=42s&clip=id#time", TwitchLinks.cleanShareText(
                "Watch https://www.twitch.tv/videos/123?tt_medium=mobile_web_share&tt_content=vod&t=42s&clip=id#time"));
        equal("https://clips.twitch.tv/Slug?foo=a%2Bb&foo=2#f", TwitchLinks.cleanShareText(
                "https://clips.twitch.tv/Slug?utm_source=a&foo=a%2Bb&tt_campaign=b&foo=2#f"));
        equal("https://www.twitch.tv/channel?t=7", TwitchLinks.cleanShareText(
                "https://www.twitch.tv/channel?utm_source=twitch_android&utm_medium=share&tt_click_id=uuid&t=7"));
        equal("(https://twitch.tv/channel).", TwitchLinks.cleanShareText("(https://twitch.tv/channel?tt_content=x)."));
        equal("https://twitch.tv/x?ref=friend&source=feed&utm_future=x", TwitchLinks.cleanShareText(
                "https://twitch.tv/x?ref=friend&source=feed&utm_future=x"));
        equal("HTTPS://M.TWITCH.TV/x?t=1&t=2", TwitchLinks.cleanShareText(
                "HTTPS://M.TWITCH.TV/x?TT_MEDIUM=a&%74t_content=b&t=1&t=2"));
        for (String url : new String[]{"https://evil.test/?tt_medium=a", "https://twitch.tv.evil.test/?tt_medium=a",
                "https://twitch.tv@evil.test/?tt_medium=a", "https://user@twitch.tv/?tt_medium=a",
                "https://twitch.tv/x?tt_medium=%zz"}) equal(url, TwitchLinks.cleanShareText(url));
        Intent intent = new Intent();
        equal(intent, TwitchLinks.putShareExtra(intent, Intent.EXTRA_TEXT, "https://twitch.tv/x?tt_content=a"));
        equal("https://twitch.tv/x", intent.value);
        TwitchLinks.putShareExtra(intent, "subject", "https://twitch.tv/x?tt_content=a");
        equal("https://twitch.tv/x?tt_content=a", intent.value);
        Settings.links = false;
        TwitchLinks.putShareExtra(intent, Intent.EXTRA_TEXT, "https://twitch.tv/x?tt_content=a");
        equal("https://twitch.tv/x?tt_content=a", intent.value);
        equal("https://twitch.tv/x?tt_content=a", TwitchLinks.cleanClipboardText("https://twitch.tv/x?tt_content=a"));
        Settings.links = true;
        equal("https://twitch.tv/x?t=10", TwitchLinks.cleanClipboardText("https://twitch.tv/x?tt_medium=a&t=10"));
        CharSequence richText = new StringBuilder("Unchanged clipboard text");
        equal(richText, TwitchLinks.cleanClipboardText(richText));
        System.out.println("PASS: " + checks + " Twitch playback/link helper assertions");
    }
}
