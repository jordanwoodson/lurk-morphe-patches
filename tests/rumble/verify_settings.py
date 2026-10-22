#!/usr/bin/env python3
"""Verify the Lurk settings registry of a patched Rumble APK, decoded with apktool.

Checks that the registry names Rumble, that keys are unique, that each setting's section is one of the
screen's sections, and that the settings of the included patches start at the documented defaults: off
for everything except lockout protection, local privacy, restoring expected behavior and the chat
features that match Twitch's.

Usage: python3 -I tests/rumble/verify_settings.py <apktool-dir>
"""
import json
import re
import sys
from pathlib import Path

SECTIONS = {"Privacy", "Interface", "Playback", "Chat", "Premium"}

# Settings of the patches added with the Lurk settings switch policy, and where each starts.
DEFAULTS = {
    "block_update_prompts": "true",
    "exact_counts": "false",
    "video_quality_live": "false",
    "video_quality_mobile": "0",
    "chat_hide_pinned": "false",
    "chat_hide_raid_notices": "false",
    "chat_hide_gift_notices": "false",
    "chat_hide_sponsor_reads": "false",
    "chat_hide_badges": "false",
    "chat_hide_all": "false",
    "show_deleted_messages": "true",
    "chat_timestamps": "true",
    "chat_keywords": "true",
    "chat_highlight_mentions": "true",
    "chat_highlight_vibrate": "false",
    "chat_highlight_words": "",
    "chat_blocked_words": "",
    "chat_blocked_users": "",
    "no_search_history": "true",
    "resume_signed_out": "true",
    "remember_speed": "false",
    "fill_screen": "false",
    "audio_focus": "0",
    "keep_screen_on_paused": "false",
    "sleep_timer": "0",
    "startup_tab": "-1",
    "hide_create_tab": "false",
    "home_hide_categories": "false",
    "home_hide_creators": "false",
    "home_hide_playlists": "false",
    "home_hide_titles": "",
    "refresh_stream": "true",
    "seek_seconds": "0",
}


def registry(root):
    files = list(Path(root).glob("smali*/io/github/jordanwoodson/extension/shared/settings/Settings.smali"))
    assert len(files) == 1, "Missing settings extension"
    text = files[0].read_text()
    method = re.search(r"\.method[^\n]* registryJson\(\)Ljava/lang/String;\n(.*?)\.end method", text, re.S).group(1)
    literal = re.search(r'const-string(?:/jumbo)? \w+, (".*")', method).group(1)
    # Smali escapes apostrophes even inside a double-quoted string; JSON does not.
    return json.loads(json.loads(literal.replace("\\'", "'")))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    reg = registry(sys.argv[1])
    assert reg["app"] == "Rumble", "Wrong app in the registry"
    settings = reg["settings"]
    keys = [s["key"] for s in settings]
    assert len(keys) == len(set(keys)), "Duplicate setting keys"
    for s in settings:
        assert s["section"] in SECTIONS, f"{s['key']}: unknown section {s['section']}"
        if s["key"] == "block_cookie_notice":
            assert s["restart"] is True, "cookie blocking must offer restart after either toggle"
        if s["type"] == "choice":
            assert s["default"] in [v for _, v in s["choices"]], f"{s['key']}: default is not a choice"
        if s["parent"] is not None:
            assert s["parent"] in keys, f"{s['key']}: missing parent {s['parent']}"
    checked = 0
    for s in settings:
        if s["key"] in DEFAULTS:
            assert s["default"] == DEFAULTS[s["key"]], f"{s['key']}: default {s['default']!r}, expected {DEFAULTS[s['key']]!r}"
            checked += 1
    print(f"PASS Rumble settings registry: {len(keys)} settings, {checked} defaults checked")


if __name__ == "__main__":
    main()
