import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Kick's player hooks on ART, through the patched APK's real classes: Kick's player view (made without its
 * constructor around the fields the hooks use), its IVS listener and Amazon's own IVS classes, with the IVS player
 * itself a stand-in that records calls. Usage: PlayerProbe <patched.apk>; bin/test-kick runs it.
 *
 * Show video stats: Kick's real handlePlayerStatistics, through the injected hook, puts a panel with the player's
 * numbers in the view's overlay with the switch on, keeps Kick's own code running after it, survives a player that
 * throws, and removes the panel at the next refresh once the switch is off.
 *
 * Mute stitched live ads: synthetic IVS AdCue and AdBreak objects and Kick's own MediaTailor ad events, fed to Kick's
 * real listener and player view, mute the player through the view's setMuted and cover the video, and both come back
 * when the break's time is up, the break ends or another stream loads. Cues that aren't ads, and the switch off,
 * change nothing.
 */
public final class PlayerProbe {
    private static final Map<String, Boolean> switches = new HashMap<>();
    private static JSONObject locators = new JSONObject();
    private static String registry = "";
    private static Context system;
    private static Object unsafe;
    private static Method allocate;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** The class and method recorded as [key], or the given fallback while the APK has no locator for it. */
    private static String[] locate(String key, String className, String method) {
        String value = locators.optString(key, "");
        if (value.isEmpty()) return new String[]{className, method};
        java.util.regex.Matcher reference = java.util.regex.Pattern.compile("L([^;]+);->([^(]+)\\(").matcher(value);
        check(reference.lookingAt(), "Malformed locator " + key + ": " + value);
        return new String[]{reference.group(1).replace('/', '.'), reference.group(2)};
    }

    private static final class Stop extends RuntimeException {
    }

    /** Runs the main looper for [milliseconds]: what the hooks post there, and their timers, run meanwhile. */
    static void pump(long milliseconds) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> { throw new Stop(); }, milliseconds);
        try {
            Looper.loop();
        } catch (Stop expected) {
            // The loop is left by the sentinel and can be entered again.
        }
    }

    /** A Kick player view made without its constructor: a context, resources and nothing else yet. */
    static View playerView(Class<?> type) throws Exception {
        View view = (View) allocate.invoke(unsafe, type);
        field(View.class, "mContext").set(view, system);
        field(View.class, "mResources").set(view, system.getResources());
        // 720 x 405, as on the emulator in portrait. layout() needs the render node the constructor makes.
        field(View.class, "mRight").setInt(view, 720);
        field(View.class, "mBottom").setInt(view, 405);
        return view;
    }

    /** An IVS Player that answers from [answers] (method name to value) and records every call in [calls]. */
    static Object player(Map<String, Object> answers, List<String> calls) throws Exception {
        Class<?> type = Class.forName("com.amazonaws.ivs.player.Player");
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, values) -> {
            String call = method.getName() + (values == null ? "[]" : Arrays.toString(values));
            synchronized (calls) {
                calls.add(call);
            }
            Object answer = answers.get(method.getName());
            if (answer instanceof RuntimeException) throw (RuntimeException) answer;
            if (answer != null) return answer;
            Class<?> result = method.getReturnType();
            if (result == long.class) return 0L;
            if (result == int.class) return 0;
            if (result == float.class) return 0f;
            if (result == boolean.class) return false;
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Show video stats

    private static void verifyStats() throws Exception {
        if (!registry.contains("\"video_stats\"")) {
            System.out.println("SKIP: Show video stats isn't in this APK");
            return;
        }
        String[] located = locate("kick.video_stats.player_statistics",
            "com.kick.mobile.kickivsplayerv2.KickIvsPlayerV2View", "handlePlayerStatistics");
        Class<?> type = Class.forName(located[0]);
        Method statistics = type.getDeclaredMethod(located[1], Class.forName("com.amazonaws.ivs.player.Player"));
        statistics.setAccessible(true);
        View view = playerView(type);

        Class<?> statsType = Class.forName("com.amazonaws.ivs.player.Statistics");
        Object numbers = statsType.getDeclaredConstructor().newInstance();
        statsType.getMethod("setBitRate", int.class).invoke(numbers, 5_800_000);
        statsType.getMethod("setFrameRate", int.class).invoke(numbers, 60);
        statsType.getMethod("setDroppedFrames", int.class).invoke(numbers, 3);
        statsType.getMethod("setDecodedFrames", int.class).invoke(numbers, 1200);
        java.lang.reflect.Constructor<?> qualityConstructor = Class.forName("com.amazonaws.ivs.player.Quality")
            .getDeclaredConstructor(String.class, String.class, String.class, String.class, String[].class,
                int.class, int.class, int.class, float.class, boolean.class);
        qualityConstructor.setAccessible(true);
        Object quality = qualityConstructor.newInstance("1080p60", "avc1.64002A,mp4a.40.2", "source", "1",
            new String[0], 6_000_000, 1920, 1080, 60f, true);
        Map<String, Object> answers = new HashMap<>();
        answers.put("getLiveLatency", 2100L);
        answers.put("getAverageBitrate", 6_000_000L);
        answers.put("getBandwidthEstimate", 23_400_000L);
        answers.put("getBufferedPosition", 10_500L);
        answers.put("getPosition", 6_200L);
        answers.put("getDuration", 0L);
        answers.put("getStatistics", numbers);
        answers.put("getQuality", quality);
        List<String> calls = new ArrayList<>();
        Object player = player(answers, calls);

        Class<?> stats = Class.forName("io.github.jordanwoodson.extension.shared.kick.KickStats");
        Method shown = stats.getDeclaredMethod("shown", View.class);
        shown.setAccessible(true);
        Field lastBitrate = field(type, "lastBitrate");

        switches.put("video_stats", false);
        statistics.invoke(view, player);
        pump(200);
        check(shown.invoke(null, view) == null, "Show video stats off: a panel appeared");
        check(calls.stream().noneMatch(c -> c.startsWith("getStatistics") || c.startsWith("getQuality")),
            "Show video stats off: the hook read the player: " + calls);
        check(Long.valueOf(6_000_000L).equals(lastBitrate.get(view)), "Kick's own statistics code didn't run (off)");

        switches.put("video_stats", true);
        statistics.invoke(view, player);
        pump(200);
        String[] lines = (String[]) shown.invoke(null, view);
        List<String> expected = Arrays.asList(
            "Latency  2.1 s",
            "Bitrate  5.80 Mbps, network 23.40 Mbps",
            "Buffer   4.3 s",
            "Dropped  3 of 1200 frames, 60 fps",
            "Quality  1080p60, 1920x1080",
            "Codecs   avc1.64002A, mp4a.40.2");
        check(lines != null && Arrays.asList(lines).equals(expected), "Show video stats on: panel " +
            (lines == null ? "missing" : Arrays.toString(lines)));
        lastBitrate.set(view, null);
        statistics.invoke(view, player);
        check(Long.valueOf(6_000_000L).equals(lastBitrate.get(view)), "Kick's own statistics code didn't run (on)");

        // The panel is a drawable in the view's overlay: it draws its box and text in the top left corner.
        Object overlay = view.getOverlay();
        Object group = field(Class.forName("android.view.ViewOverlay"), "mOverlayViewGroup").get(overlay);
        @SuppressWarnings("unchecked")
        List<Drawable> drawables = (List<Drawable>) field(group.getClass(), "mDrawables").get(group);
        check(drawables.size() == 1, "Show video stats on: the overlay holds " + drawables.size() + " drawables");
        Bitmap bitmap = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
        drawables.get(0).draw(new Canvas(bitmap));
        int corner = bitmap.getPixel(30, 30);
        check((corner >>> 24) > 0x80, "Show video stats on: no box in the top left corner");
        check(bitmap.getPixel(700, 390) == 0, "Show video stats on: the panel covers the whole player");

        // A player that fails while it's read: no panel update and no crash; Kick's own code still runs.
        answers.put("getStatistics", new IllegalStateException("released"));
        lastBitrate.set(view, null);
        statistics.invoke(view, player);
        check(Long.valueOf(6_000_000L).equals(lastBitrate.get(view)), "Kick's own code didn't run after a failed read");
        answers.put("getStatistics", numbers);

        // Off again: the next refresh removes the panel, and the overlay is empty.
        switches.put("video_stats", false);
        statistics.invoke(view, player);
        pump(200);
        check(shown.invoke(null, view) == null && drawables.isEmpty(), "Show video stats off: the panel stayed");
        switches.remove("video_stats");
        System.out.println("PASS: Show video stats draws " + expected.size() + " lines from Kick's player through " +
            "handlePlayerStatistics, never reads the player while off, and removes the panel when switched off");
    }

    // ---------------------------------------------------------------------------------------------
    // Mute stitched live ads

    /** The last setMuted the stand-in player got, or "none". */
    private static String lastMute(List<String> calls) {
        synchronized (calls) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).startsWith("setMuted")) return calls.get(i);
            }
        }
        return "none";
    }

    private static int mutes(List<String> calls) {
        synchronized (calls) {
            return (int) calls.stream().filter(call -> call.startsWith("setMuted")).count();
        }
    }

    private static boolean covered(View view) {
        Drawable cover = view.getForeground();
        return cover != null && cover.getClass().getName().endsWith("KickAds$AdCover");
    }

    /**
     * Mute stitched live ads, with synthetic ad signals fed to Kick's real code: IVS AdCue objects to the real onCue of
     * Kick's IVS listener, IVS AdBreak objects to the listener's added break methods, and Kick's own MuxAdEventData to
     * the player view's MediaTailor callback. The player view mutes through its real setMuted and player thread (a
     * single-thread executor here); the stand-in IVS player records what it is told.
     */
    private static void verifyAds() throws Exception {
        if (!registry.contains("\"mute_live_ads\"")) {
            System.out.println("SKIP: Mute stitched live ads isn't in this APK");
            return;
        }
        Class<?> type = Class.forName("com.kick.mobile.kickivsplayerv2.KickIvsPlayerV2View");
        Class<?> listenerType = type.getDeclaredField("playerListener").getType();
        java.lang.reflect.Constructor<?> makeListener = listenerType.getDeclaredConstructor(type);
        makeListener.setAccessible(true);
        View view = playerView(type);
        List<String> calls = new ArrayList<>();
        field(type, "player").set(view, player(new HashMap<>(), calls));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        field(type, "playerOperationExecutor").set(view, executor);
        Object listener = makeListener.newInstance(view);

        Method onCue = listenerType.getMethod("onCue", Class.forName("com.amazonaws.ivs.player.Cue"));
        Class<?> breakType = Class.forName("com.amazonaws.ivs.player.AdBreak");
        Method breakStarted = listenerType.getMethod("onAdBreakStarted", breakType);
        Method breakEnded = listenerType.getMethod("onAdBreakEnded", breakType);
        check(breakStarted.getDeclaringClass() == listenerType && breakEnded.getDeclaringClass() == listenerType,
            "Kick's IVS listener got its own ad break methods");
        Method setMuted = type.getMethod("setMuted", boolean.class);
        Method loadSource = type.getMethod("loadSource", int.class);
        Class<?> muxEvent = Class.forName("com.kick.mobile.kickivsplayerv2.MuxAdEventData");
        String[] located = locate("kick.mute_live_ads.media_tailor_event", type.getName(), "_init_$lambda$0");
        Method mediaTailor = type.getDeclaredMethod(located[1], type, muxEvent);
        mediaTailor.setAccessible(true);
        java.lang.reflect.Constructor<?> makeMux = muxEvent.getConstructor(String.class, String.class, String.class,
            String.class, String.class, Double.class, Double.class, Integer.class, Integer.class, Double.class);

        Class<?> cueType = Class.forName("com.amazonaws.ivs.player.AdCue");
        Class<?> roll = Class.forName("com.amazonaws.ivs.player.AdCue$RollType");
        java.lang.reflect.Constructor<?> makeCue = cueType.getDeclaredConstructor(long.class, long.class, roll,
            long.class, String.class, String.class, String.class, String.class, String.class, String.class,
            String.class, String.class, int.class, int.class);
        makeCue.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object mid = Enum.valueOf((Class) roll, "MID");
        java.util.function.BiFunction<Long, Integer[], Object> adCue = (duration, pod) -> {
            try {
                return makeCue.newInstance(0L, duration, mid, duration, "https://ads.example/ad", "advertiser",
                    "creative", "line", "order", "https://ads.example/click", "https://ads.example/start",
                    "https://ads.example/complete", pod[0], pod[1]);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        Object metadata = Class.forName("com.amazonaws.ivs.player.TextMetadataCue")
            .getConstructor(long.class, long.class, String.class, String.class, String.class)
            .newInstance(0L, 0L, "TXXX", "{\"poll\":1}", "metadata.live-video.net");

        // How long a cue's break lasts: its ad's duration (milliseconds, or seconds when under one) for each ad left
        // in its pod, else the cue's own span, else 30 s.
        Method cueMillis = Class.forName("io.github.jordanwoodson.extension.shared.kick.KickAds")
            .getDeclaredMethod("adCueMillis", Object.class);
        cueMillis.setAccessible(true);
        Object spanOnly = makeCue.newInstance(1_000L, 16_000L, mid, 0L, null, null, null, null, null, null, null, null,
            0, 1);
        Object bare = makeCue.newInstance(0L, 0L, mid, 0L, null, null, null, null, null, null, null, null, 0, 0);
        long[] lengths = {
            (Long) cueMillis.invoke(null, adCue.apply(30_000L, new Integer[]{0, 1})),
            (Long) cueMillis.invoke(null, adCue.apply(30L, new Integer[]{0, 1})),
            (Long) cueMillis.invoke(null, adCue.apply(15_000L, new Integer[]{1, 3})),
            (Long) cueMillis.invoke(null, spanOnly),
            (Long) cueMillis.invoke(null, bare),
        };
        check(Arrays.equals(lengths, new long[]{30_000, 30_000, 30_000, 15_000, 30_000}),
            "ad cue lengths " + Arrays.toString(lengths));

        // Kick asks for sound, as when a stream starts.
        setMuted.invoke(view, false);
        pump(150);
        check(lastMute(calls).equals("setMuted[false]") && mutes(calls) == 1, "Kick's own unmute reaches the player");

        // Off: an ad cue changes nothing.
        switches.put("mute_live_ads", false);
        onCue.invoke(listener, adCue.apply(1200L, new Integer[]{0, 1}));
        pump(150);
        check(!covered(view) && mutes(calls) == 1, "Mute stitched live ads off: an ad cue changed something");

        // On: a cue that isn't an ad (IVS timed metadata) changes nothing either.
        switches.put("mute_live_ads", true);
        onCue.invoke(listener, metadata);
        pump(150);
        check(!covered(view) && mutes(calls) == 1, "a timed-metadata cue was taken for an ad");

        // An ad cue mutes and covers at once; Kick's unmute during the break keeps the player muted; the cue's
        // 1.2 s plus 2 s of slack later both come back.
        onCue.invoke(listener, adCue.apply(1200L, new Integer[]{0, 1}));
        pump(150);
        check(covered(view) && lastMute(calls).equals("setMuted[true]"), "an ad cue didn't mute and cover");
        Drawable cover = view.getForeground();
        cover.setBounds(0, 0, 720, 405);
        Bitmap bitmap = Bitmap.createBitmap(720, 405, Bitmap.Config.ARGB_8888);
        cover.draw(new Canvas(bitmap));
        check(bitmap.getPixel(10, 10) == 0xFF000000, "the cover isn't opaque black");
        boolean label = false;
        for (int x = 300; x < 420 && !label; x++) label = bitmap.getPixel(x, 202) != 0xFF000000;
        check(label, "the cover has no label in the middle");
        setMuted.invoke(view, false);
        pump(150);
        check(lastMute(calls).equals("setMuted[true]"), "Kick's unmute during the break reached the player");
        pump(1500);
        check(covered(view), "the break ended before its time");
        pump(1700);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "the break didn't end on time");

        // A cue for the first ad of a pod of three covers the whole pod: 3 x 1 s plus the slack.
        onCue.invoke(listener, adCue.apply(1000L, new Integer[]{0, 3}));
        pump(4500);
        check(covered(view), "the break ended before the rest of its pod");
        pump(800);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "the pod's break didn't end");

        // Another stream ends a break at once.
        onCue.invoke(listener, adCue.apply(60_000L, new Integer[]{0, 1}));
        pump(150);
        check(covered(view), "a long ad cue didn't cover");
        loadSource.invoke(view, 1);
        pump(150);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "a new stream didn't end the break");

        // A player Kick had muted stays muted after the break.
        setMuted.invoke(view, true);
        pump(150);
        onCue.invoke(listener, adCue.apply(1000L, new Integer[]{0, 1}));
        pump(150);
        check(covered(view), "an ad cue on a muted player didn't cover");
        pump(3100);
        check(!covered(view) && lastMute(calls).equals("setMuted[true]"), "the break unmuted a player Kick had muted");
        setMuted.invoke(view, false);
        pump(150);

        // IVS's own ad break events, through the methods added to Kick's listener.
        java.lang.reflect.Constructor<?> makeBreak = breakType.getDeclaredConstructor(String.class, int.class,
            long.class, int.class, Class.forName("com.amazonaws.ivs.player.AdBreakMetadata"));
        makeBreak.setAccessible(true);
        Object adBreak = makeBreak.newInstance("break-1", 1, 60_000L, 2, null);
        breakStarted.invoke(listener, adBreak);
        pump(150);
        check(covered(view) && lastMute(calls).equals("setMuted[true]"), "an IVS ad break didn't mute and cover");
        breakEnded.invoke(listener, adBreak);
        pump(150);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "the IVS ad break's end didn't restore");

        // MediaTailor: adStart covers; an adEnd followed by the next adStart within the gap keeps the cover; the last
        // adEnd restores after the gap.
        java.util.function.Function<String, Object> mux = name -> {
            try {
                return makeMux.newInstance(name, "ad", "creative", "universal", "https://ads.example/asset", 30.0,
                    0.0, 1, 2, 0.0);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
        mediaTailor.invoke(null, view, mux.apply("adStart"));
        pump(150);
        check(covered(view) && lastMute(calls).equals("setMuted[true]"), "a MediaTailor adStart didn't mute and cover");
        mediaTailor.invoke(null, view, mux.apply("adEnd"));
        pump(500);
        mediaTailor.invoke(null, view, mux.apply("adStart"));
        pump(1500);
        check(covered(view), "the cover came off between two ads of a MediaTailor break");
        mediaTailor.invoke(null, view, mux.apply("adEnd"));
        pump(1800);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "the last MediaTailor adEnd didn't restore");

        // Switched off during a break: nothing new starts, and the running break still ends.
        onCue.invoke(listener, adCue.apply(60_000L, new Integer[]{0, 1}));
        pump(150);
        switches.put("mute_live_ads", false);
        loadSource.invoke(view, 2);
        pump(150);
        check(!covered(view) && lastMute(calls).equals("setMuted[false]"), "a break outlived the switch");
        switches.remove("mute_live_ads");
        executor.shutdown();
        System.out.println("PASS: Mute stitched live ads mutes and covers for synthetic IVS ad cues and ad breaks and " +
            "MediaTailor ads through Kick's own listener and player view, keeps Kick's unmute out during a break, " +
            "restores on time, on a new stream and for a player Kick had muted, and ignores non-ad cues and the switch off");
    }

    public static void main(String[] args) throws Exception {
        // app_process kills the whole process on an exception in any thread; say what it was first.
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            System.out.println("Exception in " + thread.getName() + ":");
            failure.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        });
        Looper.prepareMainLooper();
        // app_process has no system font map, and Typeface's default would be set twice (a native abort) when the
        // panels' paints first need it: give it one font for every family first.
        android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(
            new java.io.File("/system/fonts/Roboto-Regular.ttf")).build();
        Method makeFace = Typeface.class.getDeclaredMethod("createFromFamilies", String.class,
            android.graphics.fonts.FontFamily[].class);
        makeFace.setAccessible(true);
        Typeface face = (Typeface) makeFace.invoke(null, "sans-serif",
            new android.graphics.fonts.FontFamily[]{new android.graphics.fonts.FontFamily.Builder(font).build()});
        Method fonts = Typeface.class.getDeclaredMethod("setSystemFontMap", Map.class);
        fonts.setAccessible(true);
        fonts.invoke(null, Map.of("sans-serif", face, "serif", face, "monospace", face));
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        system = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
        allocate = unsafeClass.getMethod("allocateInstance", Class.class);

        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, args[0]);
        try (InputStream input = assets.open("lurk/locators.json")) {
            locators = new JSONObject(new String(input.readAllBytes(), "UTF-8"));
        } catch (java.io.IOException absent) {
            System.out.println("Locators: no assets/lurk/locators.json, using the names PlayerProbe knows");
        }
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("getBoolean")) {
                    return switches.getOrDefault((String) values[0], (Boolean) values[1]);
                }
                if (method.getName().equals("getString")) return values[1];
                if (method.getName().equals("getAll")) return new HashMap<String, Object>();
                if (method.getName().equals("contains")) return switches.containsKey((String) values[0]);
                if (method.getName().startsWith("registerOn") || method.getName().startsWith("unregisterOn")) return null;
                throw new UnsupportedOperationException(method.getName());
            });
        Context context = new ContextWrapper(system) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        };
        Class<?> settings = Class.forName("io.github.jordanwoodson.extension.shared.settings.Settings");
        field(settings, "context").set(null, context);
        Method registryJson = settings.getDeclaredMethod("registryJson");
        registryJson.setAccessible(true);
        registry = (String) registryJson.invoke(null);

        try {
            verifyStats();
            verifyAds();
        } catch (InvocationTargetException e) {
            throw new AssertionError("a hooked method threw", e.getCause());
        }
    }
}
