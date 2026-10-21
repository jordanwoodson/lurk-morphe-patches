#!/usr/bin/env python3
"""Run production KickPrivacy Java with boundary doubles; no Android device is needed.

Usage: python3 tests/kick/verify_privacy.py
Requires the configured JDK. Generated sources/classes live under $MORPHE_WORK (default ~/build/lurk-morphe-patches)
and are removed on Linux. This checks helper behavior, not Android SDK transmission.
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / name if java_home else None
    return str(candidate) if candidate and candidate.is_file() else shutil.which(name) or name


def main():
    repo = Path(__file__).resolve().parents[2]
    scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "kick-tests"
    scratch.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="privacy-", dir=scratch))
    try:
        stubs = {
            "android/content/Intent.java": """
                package android.content;
                public class Intent {
                    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
                    public String value;
                    public Intent putExtra(String key, String value) { this.value = value; return this; }
                }
            """,
            "io/github/jordanwoodson/extension/shared/settings/Settings.java": """
                package io.github.jordanwoodson.extension.shared.settings;
                public class Settings {
                    public static boolean analyticsEnabled = true;
                    public static boolean linksEnabled = true;
                    public static boolean isEnabled(String key) {
                        if (key.equals("disable_analytics")) return analyticsEnabled;
                        if (key.equals("clean_share_links")) return linksEnabled;
                        throw new AssertionError("Unknown settings key: " + key);
                    }
                }
            """,
        }
        sources = []
        for name, content in stubs.items():
            source = output / name
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(content, encoding="utf-8")
            sources.append(str(source))
        sources.extend([
            str(repo / "tests/kick/PrivacyProbe.java"),
            str(repo / "extensions/shared/src/main/java/io/github/jordanwoodson/extension/shared/kick/KickPrivacy.java"),
        ])
        classes = output / "classes"
        subprocess.run([java_tool("javac"), "-d", str(classes), *sources], check=True)
        subprocess.run([java_tool("java"), "-XX:-UsePerfData", "-cp", str(classes), "PrivacyProbe"], check=True)
    finally:
        if sys.platform == "darwin":
            print(f"Test files retained on macOS: {output}")
        else:
            shutil.rmtree(output)


if __name__ == "__main__":
    main()
