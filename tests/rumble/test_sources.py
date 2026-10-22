#!/usr/bin/env python3
"""Fail on an app version written out in the Rumble patch sources instead of taken from COMPATIBILITY_RUMBLE.

Every Rumble version string in code comes from rumble/shared/Constants.kt (RUMBLE.version), so a version bump
changes one file. Comments may name versions; string literals may not. Runs offline; test_state_transitions.py
runs it as part of bin/test-host.

Usage: python3 -I tests/rumble/test_sources.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / 'patches/src/main/kotlin/io/github/jordanwoodson/patches/rumble'
# A version-looking literal inside a Kotlin string: "3.21.0", "Rumble 3.21.0 …".
VERSION = re.compile(r'"[^"\n]*\b\d+\.\d+\.\d+\b[^"\n]*"')


def main():
    found = []
    for path in sorted(SOURCES.rglob('*.kt')):
        if path.name == 'Constants.kt':
            continue
        for number, line in enumerate(path.read_text().splitlines(), 1):
            code = line.split('//', 1)[0]
            if VERSION.search(code):
                found.append(f'{path.relative_to(ROOT)}:{number}: {line.strip()}')
    if found:
        sys.exit('FAIL: app versions written out (use RUMBLE.version):\n  ' + '\n  '.join(found))
    print('PASS: no app version literals outside rumble/shared/Constants.kt')


if __name__ == '__main__':
    main()
