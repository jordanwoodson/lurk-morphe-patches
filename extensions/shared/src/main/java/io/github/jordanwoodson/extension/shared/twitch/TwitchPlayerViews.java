package io.github.jordanwoodson.extension.shared.twitch;

import android.view.View;

import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * The one hook on native player presenters attaching their player view, shared by Refresh stream (which
 * player a long-press is on), VOD chapters (which video an overlay belongs to), Latency badge (which player
 * to read) and Force landscape (when the theatre's player shows). Each gets the view only when its patch is included.
 */
@SuppressWarnings("unused")
public final class TwitchPlayerViews {
    private TwitchPlayerViews() {
    }

    /** Injection point: a native player presenter attaching its player view. */
    public static void attached(Object presenter, View view) {
        if (Settings.isRegistered("refresh_stream")) TwitchPlayer.playerView(presenter, view);
        if (Settings.isRegistered("vod_chapters")) TwitchVod.playerView(presenter, view);
        if (Settings.isRegistered(TwitchLatency.KEY)) TwitchLatency.playerView(presenter, view);
        if (Settings.isRegistered(TwitchOrientation.KEY)) TwitchOrientation.playerView(presenter, view);
    }
}
