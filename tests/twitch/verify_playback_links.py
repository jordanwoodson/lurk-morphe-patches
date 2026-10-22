#!/usr/bin/env python3
"""Compile production playback/link helpers; outputs only under $MORPHE_WORK/twitch-playback."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'extensions/shared/src/main/java/io/github/jordanwoodson/extension/shared/twitch'

def main():
    for name in ['TwitchPlayback', 'TwitchLinks']:
        assert (JAVA / (name + '.java')).is_file(), f'missing production {name} helper'
    scratch = Path(os.environ.get('MORPHE_WORK', Path.home() / 'build/lurk-morphe-patches')) / 'twitch-playback'
    scratch.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='contracts-', dir=scratch))
    try:
        stubs = {
            'android/content/Intent.java': '''package android.content;
                public class Intent { public static final String EXTRA_TEXT="android.intent.extra.TEXT";
                public String value; public Intent putExtra(String k,String v){value=v;return this;} }''',
            'io/github/jordanwoodson/extension/shared/settings/Settings.java': '''
                package io.github.jordanwoodson.extension.shared.settings;
                public class Settings { public static boolean autoplay=true,links=true,next=true; public static int quality=1,metered=0;
                public static boolean isEnabled(String k) { if(k.equals("disable_autoplay"))return autoplay;
                if(k.equals("autoplay_next_video_off"))return next;
                if(k.equals("clean_share_links"))return links;throw new AssertionError(k); }
                public static android.content.Context appContext(){return new android.content.Context();}
                public static int getInt(String k){if(k.equals("video_quality_metered"))return metered;
                if(!k.equals("video_quality"))throw new AssertionError(k);return quality;} }''',
            'android/content/Context.java': '''package android.content; public class Context {}''',
            'io/github/jordanwoodson/extension/shared/Network.java': '''
                package io.github.jordanwoodson.extension.shared;
                public final class Network { public static boolean metered; public static int asked;
                public static boolean isMetered(android.content.Context c){asked++;if(c==null)throw new AssertionError();return metered;} }''',
            'io/github/jordanwoodson/extension/shared/Logger.java': '''
                package io.github.jordanwoodson.extension.shared;
                public final class Logger { public static void d(String m){} }''',
            'android/content/res/Resources.java': '''package android.content.res;
                public class Resources { public String getResourceEntryName(int id) {
                return new String[]{"autoplay_container","autoplay_progress","autoplay_time_remaining",
                "cancel_autoplay","manual_play","video_title"}[id]; } }''',
            'android/view/View.java': '''package android.view;
                public class View { public static final int NO_ID=-1,VISIBLE=0,GONE=8;
                private final int id; private int visibility;
                public View(int id){this.id=id;} public int getId(){return id;}
                public android.content.res.Resources getResources(){return new android.content.res.Resources();}
                public void setVisibility(int value){visibility=value;} public int getVisibility(){return visibility;} }''',
            'com/amazonaws/ivs/player/Quality.java': '''package com.amazonaws.ivs.player;
                public class Quality { private final String name; public Quality(String name){this.name=name;}
                public String getName(){return name;} }''',
            'com/amazonaws/ivs/player/Player.java': '''package com.amazonaws.ivs.player;
                public interface Player { java.util.Set<Quality> getQualities(); }''',
            'android/view/ViewGroup.java': '''package android.view;
                public class ViewGroup extends View { private final View[] children;
                public ViewGroup(int id,View... children){super(id);this.children=children;}
                public int getChildCount(){return children.length;} public View getChildAt(int i){return children[i];} }''',
        }
        sources = []
        for path, text in stubs.items():
            target = output / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
            sources.append(str(target))
        sources += [str(JAVA / (name + '.java')) for name in ['TwitchPlayback', 'TwitchLinks']]
        sources.append(str(ROOT / 'tests/twitch/PlaybackLinksProbe.java'))
        java_home = os.environ.get('JAVA_HOME')
        jdk = Path(java_home) / 'bin' if java_home else Path()  # else javac/java from PATH
        subprocess.run([str(jdk / 'javac'), '-d', str(output / 'classes'), *sources], check=True)
        subprocess.run([str(jdk / 'java'), '-XX:-UsePerfData', '-cp', str(output / 'classes'), 'PlaybackLinksProbe'], check=True)
    finally:
        shutil.rmtree(output)

if __name__ == '__main__':
    main()
