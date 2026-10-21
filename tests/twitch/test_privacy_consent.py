#!/usr/bin/env python3
"""Execute production TwitchPrivacy against context/settings boundary doubles."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def main():
    repo = Path(__file__).resolve().parents[2]
    scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "twitch-privacy"
    scratch.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="consent-", dir=scratch))
    try:
        sources = {
            "android/content/Context.java": """
                package android.content;
                public class Context {
                    public Context application;
                    public Context getApplicationContext() { return application; }
                }
            """,
            "io/github/jordanwoodson/extension/shared/settings/Settings.java": """
                package io.github.jordanwoodson.extension.shared.settings;
                import android.content.Context;
                public class Settings {
                    public static boolean enabled = true;
                    public static boolean analyticsEnabled = true;
                    public static int initializations;
                    public static Context context;
                    public static boolean isEnabled(String key) {
                        if (key.equals("block_cookie_notice")) return enabled;
                        if (key.equals("disable_analytics")) return analyticsEnabled;
                        throw new AssertionError(key);
                    }
                    public static void init(Context value) { context = value; initializations++; }
                }
            """,
            "PrivacyConsentProbe.java": """
                import android.content.Context;
                import io.github.jordanwoodson.extension.shared.settings.Settings;
                import io.github.jordanwoodson.extension.shared.twitch.TwitchPrivacy;
                public class PrivacyConsentProbe {
                    public static void main(String[] args) {
                        if (TwitchPrivacy.showCookieNotice(true) || TwitchPrivacy.showCookieNotice(false))
                            throw new AssertionError("enabled notice suppression");
                        Settings.enabled = false;
                        if (!TwitchPrivacy.showCookieNotice(true) || TwitchPrivacy.showCookieNotice(false))
                            throw new AssertionError("disabled preserves server presentation decision");
                        if (TwitchPrivacy.allowAnalyticsCollection(true) || TwitchPrivacy.allowAnalyticsCollection(false) ||
                                TwitchPrivacy.allowAnalyticsCollectionBoxed(null) != Boolean.FALSE ||
                                TwitchPrivacy.allowAnalyticsCollectionBoxed(Boolean.TRUE) != Boolean.FALSE)
                            throw new AssertionError("analytics enabled must reject default/true/false requests");
                        if (!TwitchPrivacy.disableBranchTracking(false) || !TwitchPrivacy.disableBranchTracking(true))
                            throw new AssertionError("analytics enabled must force Branch limited mode");
                        Settings.analyticsEnabled = false;
                        if (!TwitchPrivacy.allowAnalyticsCollection(true) || TwitchPrivacy.allowAnalyticsCollection(false) ||
                                TwitchPrivacy.allowAnalyticsCollectionBoxed(null) != null ||
                                TwitchPrivacy.allowAnalyticsCollectionBoxed(Boolean.TRUE) != Boolean.TRUE ||
                                TwitchPrivacy.allowAnalyticsCollectionBoxed(Boolean.FALSE) != Boolean.FALSE)
                            throw new AssertionError("analytics disabled must preserve SDK setter requests");
                        if (TwitchPrivacy.disableBranchTracking(false) || !TwitchPrivacy.disableBranchTracking(true))
                            throw new AssertionError("analytics disabled must preserve Branch requests");
                        TwitchPrivacy.initSettingsOnce(null);
                        if (Settings.initializations != 0) throw new AssertionError("null context");
                        Context provider = new Context();
                        Context application = new Context();
                        provider.application = application;
                        TwitchPrivacy.initSettingsOnce(provider);
                        TwitchPrivacy.initSettingsOnce(application);
                        TwitchPrivacy.initSettingsOnce(new Context());
                        if (Settings.initializations != 1 || Settings.context != application)
                            throw new AssertionError("providers and app must share one Application init");
                        System.out.println("PASS: consent and nullable/primitive collection decisions; null/provider/application startup contexts");
                    }
                }
            """,
        }
        paths = []
        for name, content in sources.items():
            source = output / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(content)
            paths.append(str(source))
        paths.append(str(repo / "extensions/shared/src/main/java/io/github/jordanwoodson/extension/shared/twitch/TwitchPrivacy.java"))
        java_home = os.environ.get("JAVA_HOME")
        jdk = Path(java_home) / "bin" if java_home else Path()  # else javac/java from PATH
        classes = output / "classes"
        subprocess.run([str(jdk / "javac"), "-d", str(classes), *paths], check=True)
        subprocess.run([str(jdk / "java"), "-XX:-UsePerfData", "-cp", str(classes), "PrivacyConsentProbe"], check=True)
    finally:
        if sys.platform == "darwin":
            print(f"Test files retained on macOS: {output}")
        else:
            shutil.rmtree(output)


if __name__ == "__main__":
    main()
