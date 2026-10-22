import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Fill screen, Seek interval and Sleep timer: measures Media3's real AspectRatioFrameLayout (with the patched
 * onMeasure) in landscape and portrait, runs the seek interval, and the pause the sleep timer reaches through
 * the player service listener, with the settings on and off.
 */
public final class PlaybackProbe {
    private static final String PLAYER_EXT = "io.github.jordanwoodson.extension.shared.rumble.RumblePlayer";
    private static final String SERVICE = "com.rumble.videoplayer.player.RumblePlayerService";

    public static void main(String[] args) {
        ProbeSupport.run("fill screen measure, seek interval and the sleep timer's pause", PlaybackProbe::run);
    }

    private static void run() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        // Without an application, Lurk settings read their registry defaults (ProbeSupport.setting).
        ProbeSupport.set(thread, "mInitialApplication", null);
        fillScreen(system);
        seekInterval();
        sleepTimer();
    }

    private static void fillScreen(Context system) throws Exception {
        Context landscape = withOrientation(system, Configuration.ORIENTATION_LANDSCAPE);
        Context portrait = withOrientation(system, Configuration.ORIENTATION_PORTRAIT);
        // A square video in a 1000 x 500 frame: FIT measures 500 x 500, ZOOM 1000 x 1000.
        ProbeSupport.check(measure(landscape)[0] == 500, "off (the default): FIT in landscape");
        ProbeSupport.setting("fill_screen", "true");
        int[] zoomed = measure(landscape);
        ProbeSupport.check(zoomed[0] == 1000 && zoomed[1] == 1000, "on: ZOOM in landscape, got " + zoomed[0] + "x" + zoomed[1]);
        ProbeSupport.check(measure(portrait)[0] == 500, "on: FIT in portrait");
        Method resize = Class.forName(PLAYER_EXT).getMethod("resizeMode", View.class, int.class);
        ProbeSupport.check((int) resize.invoke(null, new View(landscape), 1) == 1, "on: other modes stay");
        ProbeSupport.setting("fill_screen", "false");
    }

    private static Context withOrientation(Context base, int orientation) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.orientation = orientation;
        return base.createConfigurationContext(configuration);
    }

    private static int[] measure(Context context) throws Exception {
        Class<?> frameType = Class.forName("androidx.media3.ui.AspectRatioFrameLayout");
        View frame = (View) frameType.getConstructor(Context.class).newInstance(context);
        frameType.getMethod("setResizeMode", int.class).invoke(frame, 0);
        frameType.getMethod("setAspectRatio", float.class).invoke(frame, 1f);
        frame.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
        return new int[]{frame.getMeasuredWidth(), frame.getMeasuredHeight()};
    }

    private static void seekInterval() throws Exception {
        Method seek = Class.forName(PLAYER_EXT).getMethod("seekMillis", int.class);
        ProbeSupport.check((int) seek.invoke(null, 15000) == 15000, "Rumble's 15 s by default");
        ProbeSupport.setting("seek_seconds", "30");
        ProbeSupport.check((int) seek.invoke(null, 15000) == 30000, "30 s while chosen");
        ProbeSupport.setting("seek_seconds", "0");
    }

    private static void sleepTimer() throws Exception {
        Class<?> listenerType = ProbeSupport.type("rumble.sleep_timer.listener", "L" + SERVICE.replace('.', '/') + "$b;");
        Method pause = listenerType.getMethod("lurkPause", Object.class);
        Method rumblePause = ProbeSupport.method("rumble.sleep_timer.player_pause", "Lcom/rumble/videoplayer/player/a;->L3()V");
        Object listener = ProbeSupport.allocate(listenerType);
        ProbeSupport.check(Boolean.FALSE.equals(pause.invoke(null, new Object())), "not a listener: nothing paused");
        ProbeSupport.check(Boolean.FALSE.equals(pause.invoke(null, listener)), "no service: nothing paused");

        Class<?> serviceType = Class.forName(SERVICE);
        Object service = ProbeSupport.allocate(serviceType);
        for (Field f : listenerType.getDeclaredFields()) {
            if (f.getType() == serviceType) {
                f.setAccessible(true);
                f.set(listener, service);
            }
        }
        ProbeSupport.check(Boolean.FALSE.equals(pause.invoke(null, listener)), "no player: nothing paused");

        // With a player (whose own fields are empty here), the listener reaches Rumble's pause: the one the
        // media session's pause() calls. It fails inside that method, which shows where the call went.
        Object player = ProbeSupport.allocate(rumblePause.getDeclaringClass());
        for (Field f : serviceType.getDeclaredFields()) {
            if (f.getType() == rumblePause.getDeclaringClass()) {
                f.setAccessible(true);
                f.set(service, player);
            }
        }
        Object outcome = ProbeSupport.outcome(pause, null, listener);
        boolean reached = false;
        if (outcome instanceof Throwable) {
            for (StackTraceElement frame : ((Throwable) outcome).getStackTrace()) {
                reached |= frame.getClassName().equals(rumblePause.getDeclaringClass().getName())
                        && frame.getMethodName().equals(rumblePause.getName());
            }
        }
        ProbeSupport.check(Boolean.TRUE.equals(outcome) || reached,
                "the listener's pause reaches " + rumblePause.getDeclaringClass().getName() + "." + rumblePause.getName()
                        + ", got " + outcome);
    }
}
