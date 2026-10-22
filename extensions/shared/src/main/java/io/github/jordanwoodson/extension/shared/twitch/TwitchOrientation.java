package io.github.jordanwoodson.extension.shared.twitch;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewParent;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Force landscape on play: when a native player opens in the theatre (a live stream or a video), the activity is
 * asked for landscape, as Twitch's own rotate button asks for it. When that player view leaves the window (the
 * theatre closes), the activity's earlier orientation request comes back, unless something else changed it in
 * the meantime. Home's feed previews don't count, and Twitch's rotate button still switches back to portrait.
 */
@SuppressWarnings("unused")
public final class TwitchOrientation {
    static final String KEY = "force_landscape";
    private static final int LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
    private static final int NONE = Integer.MIN_VALUE;
    /** Player views already watched, so a presenter binding its view again adds no second watcher. */
    private static final Map<View, Watcher> WATCHED = new WeakHashMap<>();
    private static int theatreId = -1;

    private TwitchOrientation() {
    }

    /**
     * From the shared player-view hook: a native player presenter attaching its player view. Main thread. Only
     * while the switch is on, so players opened with it off are untouched.
     */
    public static void playerView(Object presenter, View view) {
        if (view == null || !Settings.isEnabled(KEY)) return;
        try {
            Watcher watcher = WATCHED.get(view);
            if (watcher == null) {
                watcher = new Watcher();
                WATCHED.put(view, watcher);
                view.addOnAttachStateChangeListener(watcher);
            }
            // Its place in the theatre is settled once the current frame is done.
            if (view.isAttachedToWindow()) {
                Watcher posted = watcher;
                view.post(() -> posted.enter(view));
            }
        } catch (RuntimeException e) {
            Logger.d("orientation: " + e);
        }
    }

    /** One player view: asks for landscape when it shows in the theatre, gives the request back when it leaves. */
    private static final class Watcher implements View.OnAttachStateChangeListener {
        /** The activity's request before Lurk asked for landscape, NONE while Lurk hasn't. */
        private int previous = NONE;

        @Override
        public void onViewAttachedToWindow(View view) {
            view.post(() -> enter(view));
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (previous == NONE) return;
            int restore = previous;
            previous = NONE;
            Activity activity = activity(view.getContext());
            if (activity == null || activity.isFinishing() || activity.getRequestedOrientation() != LANDSCAPE) return;
            activity.setRequestedOrientation(restore);
            Logger.d("orientation: restored " + restore);
        }

        void enter(View view) {
            if (previous != NONE || !view.isAttachedToWindow() || !Settings.isEnabled(KEY) || !inTheatre(view)) return;
            Activity activity = activity(view.getContext());
            if (activity == null) return;
            int requested = activity.getRequestedOrientation();
            if (requested == LANDSCAPE
                    || activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                return;
            }
            previous = requested;
            activity.setRequestedOrientation(LANDSCAPE);
            Logger.d("orientation: landscape for a player (was " + requested + ")");
        }
    }

    private static boolean inTheatre(View view) {
        if (theatreId == -1) {
            Context context = view.getContext();
            // The theatre's content, live (theatre_root_container) and video (player_and_chat_vertical_container).
            theatreId = context.getResources().getIdentifier("video_presenter_container", "id", context.getPackageName());
        }
        if (theatreId == 0) return false;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getId() == theatreId) return true;
        }
        return false;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
