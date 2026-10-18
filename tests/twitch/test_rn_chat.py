#!/usr/bin/env python3
"""APK-free behavior of the compiled Twitch RN helpers. Target tests are separate and mandatory."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
subprocess.run(['node', str(ROOT / 'tests/twitch/test_delayed.js'),
                str(ROOT / 'patches/src/main/runtime/twitch/delayed.js')], check=True)

subprocess.run(['node', str(ROOT / 'tests/twitch/test_chat.js'),
                str(ROOT / 'patches/src/main/runtime/twitch/chat.js')], check=True)
