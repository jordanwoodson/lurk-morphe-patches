import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Current-source ART counterpart to the deterministic host scheduler; no Activity or app data needed. */
public final class SleepTimerArtProbe {
    public static final class Player {
        int pauses;
        public void pause() {
            pauses++;
            io.github.jordanwoodson.extension.shared.SleepTimer.onStop(this);
        }
    }

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if (args[0].startsWith("concurrency:")) {
            Class<?> settings = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> values = (java.util.Map<String, String>) settings.getField("values").get(null);
            values.put("sleep_timer", "1");
            run(() -> SleepTimerConcurrencyProbe.run(args[0].substring("concurrency:".length())));
            System.exit(0);
        }
        String[] test = args[0].split(":");
        Class<?> helper = Class.forName("io.github.jordanwoodson.extension.shared." + test[0]);
        Method play = helper.getMethod("onPlay", Object.class);
        Method stop = helper.getMethod("onStop", Object.class);
        Class<?> settings = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> values = (java.util.Map<String, String>) settings.getField("values").get(null);
        values.put("sleep_timer", "1");
        Field timer = Class.forName("io.github.jordanwoodson.extension.shared.SleepTimer").getDeclaredField("scheduledSleep");
        timer.setAccessible(true);
        Handler main = new Handler(Looper.getMainLooper());
        Player old = new Player();
        Player fresh = new Player();
        play.invoke(null, old);
        // Fire the real production timer after its arm callback, then give the platform fallback 3 s.
        main.postDelayed(() -> run(() -> ((Runnable) timer.get(null)).run()), 20);
        main.postDelayed(() -> run(() -> {
            switch (test[1]) {
                case "new": play.invoke(null, fresh); break;
                case "resume": stop.invoke(null, old); play.invoke(null, old); break;
                case "replay": play.invoke(null, old); break;
                case "stop": stop.invoke(null, old); break;
                case "disable": values.put("sleep_timer", "0"); break;
                case "repeat": break;
                default: throw new AssertionError(test[1]);
            }
        }), 100);
        if (test[1].equals("repeat")) {
            main.postDelayed(() -> run(() -> play.invoke(null, old)), 3300);
            main.postDelayed(() -> run(() -> ((Runnable) timer.get(null)).run()), 3340);
        }
        main.postDelayed(() -> run(() -> {
            int expected = test[1].equals("new") ? 1 : test[1].equals("repeat") ? 2 : 0;
            if (old.pauses != expected || fresh.pauses != 0) {
                throw new AssertionError(args[0] + " old=" + old.pauses + " fresh=" + fresh.pauses);
            }
            System.out.println("PASS ART current helper: " + args[0] + " old=" + old.pauses + " fresh=" + fresh.pauses);
            System.exit(0);
        }), test[1].equals("repeat") ? 6500 : 3300);
        Looper.loop();
    }

    private interface Action { void run() throws Exception; }
    private static void run(Action action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
