#!/usr/bin/env python3
"""Run production KickPlayer Java with boundary doubles; no Android device is needed.

Usage: python3 tests/kick/verify_player.py
Requires the configured JDK. Generated sources/classes live under $MORPHE_WORK (default ~/build/lurk-morphe-patches)
and are removed on Linux. Checks the decision tables of the mobile-data quality ceiling, normal latency
background audio and Fill screen; the hooks themselves are checked on a device (Fill screen's also by
bin/test-kick on ART).
"""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

STUBS = {
    "android/content/Context.java": "package android.content; public class Context { }",
    "android/content/ContextWrapper.java": """
        package android.content;
        public class ContextWrapper extends Context {
            private final Context base;
            public ContextWrapper(Context base) { this.base = base; }
            public Context getBaseContext() { return base; }
        }
    """,
    "android/content/res/Configuration.java": """
        package android.content.res;
        public class Configuration {
            public static final int ORIENTATION_PORTRAIT = 1;
            public static final int ORIENTATION_LANDSCAPE = 2;
            public int orientation;
        }
    """,
    "android/content/res/Resources.java": """
        package android.content.res;
        public class Resources {
            public final Configuration configuration = new Configuration();
            public Configuration getConfiguration() { return configuration; }
        }
    """,
    "android/app/Activity.java": """
        package android.app;
        public class Activity extends android.content.ContextWrapper {
            public boolean pip;
            public Activity() { super(null); }
            public boolean isInPictureInPictureMode() { return pip; }
        }
    """,
    "android/os/Looper.java": """
        package android.os;
        public class Looper { public static Looper getMainLooper() { return new Looper(); } }
    """,
    "android/os/Handler.java": """
        package android.os;
        public class Handler {
            public Handler(Looper looper) { }
            public boolean post(Runnable r) { return true; }
            public boolean postDelayed(Runnable r, long delay) { return true; }
        }
    """,
    "android/view/View.java": """
        package android.view;
        public class View {
            public int orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
            public android.content.Context context = new android.content.Context();
            public android.content.Context getContext() { return context; }
            public android.content.res.Resources getResources() {
                android.content.res.Resources resources = new android.content.res.Resources();
                resources.configuration.orientation = orientation;
                return resources;
            }
        }
    """,
    "io/github/jordanwoodson/extension/shared/Logger.java": """
        package io.github.jordanwoodson.extension.shared;
        public class Logger { public static void d(String message) { } }
    """,
    "io/github/jordanwoodson/extension/shared/Network.java": """
        package io.github.jordanwoodson.extension.shared;
        public class Network {
            public static boolean metered;
            public static boolean isMetered(android.content.Context context) { return metered; }
        }
    """,
    "io/github/jordanwoodson/extension/shared/SleepTimer.java": """
        package io.github.jordanwoodson.extension.shared;
        public class SleepTimer {
            public static final class Expiry { public void pauseLeftovers(String platform) { } }
            public static void setExpiryAction(java.util.function.Consumer<Expiry> action) { }
            public static void onPlay(Object player) { }
            public static void onStop(Object player) { }
            public static java.util.List<Object> started() { return new java.util.ArrayList<>(); }
        }
    """,
    "io/github/jordanwoodson/extension/shared/settings/Settings.java": """
        package io.github.jordanwoodson.extension.shared.settings;
        public class Settings {
            public static final java.util.Map<String, String> values = new java.util.HashMap<>();
            public static boolean isRegistered(String key) { return values.containsKey(key); }
            public static boolean isEnabled(String key) { return Boolean.parseBoolean(values.getOrDefault(key, "true")); }
            public static int getInt(String key) { return Integer.parseInt(values.getOrDefault(key, "0")); }
        }
    """,
}

PROBE = """
import io.github.jordanwoodson.extension.shared.Network;
import io.github.jordanwoodson.extension.shared.kick.KickPlayer;
import io.github.jordanwoodson.extension.shared.settings.Settings;

public class PlayerProbe {
    /** Stands in for IVS ResizeMode: the hook only uses the constants' names. */
    enum Mode { FIT, FILL, ZOOM }

    static int checks;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    static Integer ceiling(Integer height) { return KickPlayer.autoQualityCeiling(new android.view.View(), height); }

    public static void main(String[] args) {
        int lurk = Integer.parseInt(args[0]);
        // Not registered (Default video quality left out) or "Same as on Wi-Fi": every value passes through.
        Network.metered = true;
        check(ceiling(null) == null && ceiling(720) == 720 && ceiling(1080) == 1080, "unregistered changed a ceiling");
        Settings.values.put("quality_metered", "0");
        check(ceiling(null) == null && ceiling(720) == 720, "Same as on Wi-Fi changed a ceiling");
        // 480p cap on a metered network: starting ceilings are capped, picks pass.
        Settings.values.put("quality_metered", "480");
        check(ceiling(null) == 480, "no ceiling isn't capped");
        check(ceiling(720) == 480, "Kick's 720 start isn't capped");
        check(ceiling(1080) == (lurk == 1080 ? 480 : 1080), "1080 handled wrongly for Lurk height " + lurk);
        check(ceiling(360) == 360 && ceiling(160) == 160, "a lower pick changed");
        check(ceiling(936) == 936, "a higher pick changed");
        // A cap above the start keeps the start.
        Settings.values.put("quality_metered", "720");
        check(ceiling(720) == 720 && ceiling(null) == 720, "720 cap");
        check(ceiling(1080) == (lurk == 1080 ? 720 : 1080), "720 cap on the Lurk height");
        // Wi-Fi: nothing changes.
        Network.metered = false;
        Settings.values.put("quality_metered", "160");
        check(ceiling(null) == null && ceiling(720) == 720 && ceiling(1080) == 1080, "unmetered changed a ceiling");

        // Normal latency: off keeps Kick's request; on turns low latency off; false stays false.
        check(KickPlayer.lowLatency(true), "low latency changed while unregistered");
        Settings.values.put("normal_latency", "false");
        check(KickPlayer.lowLatency(true) && !KickPlayer.lowLatency(false), "normal latency off");
        Settings.values.put("normal_latency", "true");
        check(!KickPlayer.lowLatency(true) && !KickPlayer.lowLatency(false), "normal latency on");

        // Background audio: only after leaving Kick while playing (the bridge reads false here, so leaving
        // doesn't arm it), and resuming clears it.
        Settings.values.put("background_audio", "true");
        Settings.values.put("no_auto_pip", "false");
        check(!KickPlayer.onUserLeaveHint(new android.app.Activity()), "picture-in-picture skipped");
        check(!KickPlayer.keepAudio(), "kept audio without playback");
        Settings.values.put("no_auto_pip", "true");
        check(KickPlayer.onUserLeaveHint(new android.app.Activity()), "picture-in-picture not skipped");
        Settings.values.remove("no_auto_pip");
        check(!KickPlayer.onUserLeaveHint(new android.app.Activity()), "unregistered switch skipped picture-in-picture");
        KickPlayer.onResume();
        check(!KickPlayer.keepAudio(), "kept audio after resume");

        // Fill screen: FILL only in landscape, outside picture-in-picture, when FIT would leave side bars around a
        // video wider than tall. Kick's other modes, vertical videos and a view narrower than the video keep theirs.
        float video = 16 / 9f, screen = 20 / 9f;
        android.view.View view = new android.view.View();
        view.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FIT, "filled while unregistered");
        Settings.values.put("fill_screen", "false");
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FIT, "filled while off");
        Settings.values.put("fill_screen", "true");
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FILL, "not filled in landscape");
        check(KickPlayer.resizeMode(view, Mode.ZOOM, video, screen) == Mode.ZOOM, "changed Kick's ZOOM");
        check(KickPlayer.resizeMode(view, "FIT", video, screen).equals("FIT"), "changed a non-enum mode");
        check(KickPlayer.resizeMode(null, Mode.FIT, video, screen) == Mode.FIT, "filled without a view");
        check(KickPlayer.resizeMode(view, Mode.FIT, 9 / 16f, screen) == Mode.FIT, "filled a vertical video");
        check(KickPlayer.resizeMode(view, Mode.FIT, video, 1066 / 720f) == Mode.FIT, "filled next to the chat");
        check(KickPlayer.resizeMode(view, Mode.FIT, video, video) == Mode.FIT, "filled a view of the video's shape");
        android.app.Activity activity = new android.app.Activity();
        view.context = new android.content.ContextWrapper(activity);
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FILL, "not filled in an activity");
        activity.pip = true;
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FIT, "filled in picture-in-picture");
        activity.pip = false;
        view.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
        check(KickPlayer.resizeMode(view, Mode.FIT, video, screen) == Mode.FIT, "filled in portrait");
        System.out.println("PASS: " + checks + " player decision-table assertions (Lurk height " + lurk + ")");
    }
}
"""


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / name if java_home else None
    return str(candidate) if candidate and candidate.is_file() else shutil.which(name) or name


def run(output, repo, lurk_height):
    sources = []
    for name, content in STUBS.items():
        source = output / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(content, encoding="utf-8")
        sources.append(str(source))
    player = (repo / "extensions/shared/src/main/java/io/github/jordanwoodson/extension/shared/kick/KickPlayer.java")
    text = player.read_text(encoding="utf-8")
    if lurk_height:
        # What the Default video quality patch's bridge does at patch time.
        text, count = re.subn(r"(private static int lurkHeight\(\) \{\s*return )0;", rf"\g<1>{lurk_height};", text)
        assert count == 1, "lurkHeight stub not found"
    copy = output / "io/github/jordanwoodson/extension/shared/kick/KickPlayer.java"
    copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_text(text, encoding="utf-8")
    (output / "PlayerProbe.java").write_text(PROBE, encoding="utf-8")
    classes = output / "classes"
    subprocess.run([java_tool("javac"), "-d", str(classes), *sources, str(copy), str(output / "PlayerProbe.java")],
                   check=True)
    subprocess.run([java_tool("java"), "-XX:-UsePerfData", "-cp", str(classes), "PlayerProbe", str(lurk_height)],
                   check=True)


def main():
    repo = Path(__file__).resolve().parents[2]
    scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "kick-tests"
    scratch.mkdir(parents=True, exist_ok=True)
    for lurk_height in (0, 1080):
        output = Path(tempfile.mkdtemp(prefix="player-", dir=scratch))
        try:
            run(output, repo, lurk_height)
        finally:
            if sys.platform == "darwin":
                print(f"Test files retained on macOS: {output}")
            else:
                shutil.rmtree(output)


if __name__ == "__main__":
    main()
