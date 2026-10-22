package io.github.jordanwoodson.extension.shared.kick;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.Network;
import io.github.jordanwoodson.extension.shared.SleepTimer;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Kick's native player: background audio, automatic picture-in-picture, normal latency, the sleep timer, the
 * mobile-data quality ceiling and Fill screen. Each hook returns Kick's own value, or does nothing, while its setting is
 * off. The bridges are filled in at patch time with calls into Kick's player classes.
 */
@SuppressWarnings("unused")
public final class KickPlayer {
    private static final String BACKGROUND_AUDIO = "background_audio";
    private static final String FILL_SCREEN = "fill_screen";
    private static final String NO_AUTO_PIP = "no_auto_pip";
    private static final String NORMAL_LATENCY = "normal_latency";
    private static final String QUALITY_METERED = "quality_metered";
    /** PlayerConfig.defaultMaxAutoQualityHeight, the starting ceiling Kick uses when no patch sets one. */
    private static final int KICK_DEFAULT_HEIGHT = 720;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Set when Kick is left while playing with background audio on; cleared when it's back in front. */
    private static volatile boolean leftApp;
    private static volatile WeakReference<View> lastView = new WeakReference<>(null);

    static {
        SleepTimer.setExpiryAction(KickPlayer::pauseAll);
    }

    private KickPlayer() {
    }

    // ---------------------------------------------------------------------------------------------
    // Background audio and picture-in-picture

    /**
     * Injection point: the start of MainActivity.onUserLeaveHint(), while Kick is still in front, so its audio
     * service may start. Returns true to skip Kick's automatic picture-in-picture.
     */
    public static boolean onUserLeaveHint(Activity activity) {
        if (enabled(BACKGROUND_AUDIO) && isPlaying()) {
            leftApp = true;
            try {
                startAudioService(activity);
            } catch (RuntimeException e) {
                Logger.d("background audio: " + e);
            }
        }
        return enabled(NO_AUTO_PIP);
    }

    /**
     * Injection point: the start of KickIvsPlayerV2View.updatePipParams(boolean, Rect, Rational): whether the system
     * may enter picture-in-picture by itself when Kick is left (Android 12 and later).
     */
    public static boolean autoEnterPip(boolean requested) {
        return requested && !enabled(NO_AUTO_PIP);
    }

    /**
     * Injection point: the start of KickIvsPlayerV2Manager.shouldRunAudioService(). True keeps Kick's audio
     * service (and the player) going while Kick is in the background; Kick itself only keeps it for a locked
     * screen or picture-in-picture.
     */
    public static boolean keepAudio() {
        return leftApp && enabled(BACKGROUND_AUDIO);
    }

    /** Injection point: the start of KickIvsPlayerV2Manager.onActivityResume(): Kick's own rules apply again. */
    public static void onResume() {
        leftApp = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Normal latency

    /** Injection point: IVS CorePlayerImpl.setLiveLowLatencyEnabled(boolean), which Kick calls with true. */
    public static boolean lowLatency(boolean requested) {
        if (requested && enabled(NORMAL_LATENCY)) {
            Logger.d("normal latency: low-latency mode off");
            return false;
        }
        return requested;
    }

    // ---------------------------------------------------------------------------------------------
    // Sleep timer

    /** Injection point: the start of IVS CorePlayerImpl.play(). May run on any thread. */
    public static void onPlay(Object player) {
        SleepTimer.onPlay(player);
    }

    /** Injection point: the start of IVS CorePlayerImpl.pause() and release(). */
    public static void onStop(Object player) {
        SleepTimer.onStop(player);
    }

    /** Injection point: the start of KickIvsPlayerV2View.play(), the view React Native plays through. */
    public static void onViewPlay(View view) {
        lastView = new WeakReference<>(view);
    }

    /**
     * Pauses through Kick's player view, which also updates its audio service and tells React Native, then
     * pauses any player that view didn't stop a few seconds later.
     */
    private static void pauseAll(SleepTimer.Expiry expiry) {
        View view = lastView.get();
        if (view != null) {
            try {
                pauseView(view);
            } catch (RuntimeException e) {
                Logger.d("sleep timer: " + e);
            }
        }
        MAIN.postDelayed(() -> expiry.pauseLeftovers("Kick's view"), 3000);
    }

    // ---------------------------------------------------------------------------------------------
    // Quality on mobile data

    /**
     * Injection point: the start of KickIvsPlayerV2View.setAutoMaxQuality(Integer), the ceiling every stream
     * load, Auto and quality pick goes through. On a metered network a starting ceiling (none, Kick's 720 or
     * the Default video quality height) is capped; any other height is a pick and passes through.
     */
    public static Integer autoQualityCeiling(View view, Integer height) {
        int cap = Settings.isRegistered(QUALITY_METERED) ? Settings.getInt(QUALITY_METERED) : 0;
        if (cap <= 0 || !Network.isMetered(view.getContext())) return height;
        if (height != null && height != KICK_DEFAULT_HEIGHT && height != lurkHeight()) return height;
        int capped = height == null ? cap : Math.min(height, cap);
        Logger.d("quality on mobile data: starting ceiling " + height + " -> " + capped);
        return capped;
    }

    // ---------------------------------------------------------------------------------------------
    // Fill screen

    /**
     * Injection point: KickIvsPlayerV2View.updateSurfaceViewTransform(), where it checks its IVS resize mode
     * against FIT, with the video's and the view's width / height. Kick keeps FIT. In landscape, outside
     * picture-in-picture, Fill screen hands back FILL when FIT would leave bars at the sides of a video wider than
     * tall (a 16:9 stream on a 20:9 screen): the video fills the width and its top and bottom are cropped. A
     * player next to the chat, which FIT letterboxes, and vertical videos keep FIT. The view runs this on every
     * layout, so rotating back to portrait restores FIT.
     */
    public static Object resizeMode(View view, Object mode, float videoAspect, float viewAspect) {
        if (!(mode instanceof Enum) || !"FIT".equals(((Enum<?>) mode).name()) || view == null || videoAspect <= 1f
                || viewAspect <= videoAspect || !enabled(FILL_SCREEN)) {
            return mode;
        }
        try {
            if (view.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return mode;
            Activity activity = activity(view.getContext());
            if (activity != null && activity.isInPictureInPictureMode()) return mode;
            return fill((Enum<?>) mode);
        } catch (RuntimeException e) {
            Logger.d("fill screen: " + e);
            return mode;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fill(Enum<?> fit) {
        return Enum.valueOf((Class) fit.getDeclaringClass(), "FILL");
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Bridges, filled in at patch time.

    /** KickIvsPlayerV2Manager.INSTANCE.isPlaying(). */
    private static boolean isPlaying() {
        return false;
    }

    /** AudioService.Companion.start(context): Kick's media-playback foreground service and notification. */
    private static void startAudioService(Context context) {
    }

    /** ((KickIvsPlayerV2View) view).pause(). */
    private static void pauseView(View view) {
    }

    /** The Default video quality height chosen when patching, or 0 without that patch. */
    private static int lurkHeight() {
        return 0;
    }

    private static boolean enabled(String key) {
        return Settings.isRegistered(key) && Settings.isEnabled(key);
    }
}
