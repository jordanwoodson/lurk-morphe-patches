#!/usr/bin/env python3
"""Check that the app code of a patched APK calls each named extension method.

Morphe drops an injected non-range invoke whose registers are above v15 without an error, so a patch
can apply cleanly and still never call its extension. This reads the APK's dex files with dexdump and
counts, for each method, the calls from classes outside the extension package.

Usage: python3 -I tests/verify_extension_calls.py <patched.apk> Class.method [Class.method ...]
       python3 -I tests/verify_extension_calls.py <patched.apk> --list <ClassName> [...]
Class is the simple name of a class under io.github.jordanwoodson.extension (RumbleChat, Settings, ...).
bin/verify-hooks <app> <patched.apk> runs it with the app's tests/<app>/expected-hooks.txt.
Needs dexdump from the Android build-tools: set ANDROID_HOME.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter
from pathlib import Path

EXTENSION = "Lio/github/jordanwoodson/extension/"


def dexdump():
    if not os.environ.get("ANDROID_HOME"):
        sys.exit("Set ANDROID_HOME to the Android SDK (dexdump comes from its build-tools)")
    home = Path(os.environ["ANDROID_HOME"])
    tools = sorted((home / "build-tools").glob("*/dexdump"), key=lambda p: [int(x) for x in re.findall(r"\d+", p.parent.name)])
    if not tools:
        sys.exit(f"dexdump not found under {home}/build-tools")
    return str(tools[-1])


def calls(apk):
    """Counter of (class simple name, method) for invokes of extension methods from non-extension code."""
    counted = Counter()
    declared = {}
    tool = dexdump()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            path = Path(tmp) / name
            path.write_bytes(z.read(name))
            current = None
            out = subprocess.run([tool, "-d", str(path)], capture_output=True, text=True, errors="replace").stdout
            for line in out.splitlines():
                m = re.match(r"\s+Class descriptor\s+:\s+'(L[^']+;)'", line)
                if m:
                    current = m.group(1)
                    continue
                m = re.match(r"\s+name\s+:\s+'([^']+)'", line)
                if m and current and current.startswith(EXTENSION):
                    declared.setdefault(current.rsplit("/", 1)[1][:-1], set()).add(m.group(1))
                    continue
                m = re.search(r"invoke-\w+(?:/range)? \{[^}]*\}, (L[^;]+;)\.([^:]+):", line)
                if m and current and not current.startswith(EXTENSION) and m.group(1).startswith(EXTENSION):
                    counted[(m.group(1).rsplit("/", 1)[1][:-1], m.group(2))] += 1
    return counted, declared


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    apk, names = sys.argv[1], sys.argv[2:]
    counted, declared = calls(apk)
    if names[0] == "--list":
        for cls in names[1:]:
            for method in sorted(declared.get(cls, ())):
                print(f"{counted[(cls, method)]:4d}  {cls}.{method}")
        return
    missing = []
    for name in names:
        cls, method = name.rsplit(".", 1)
        n = counted[(cls, method)]
        print(f"{n:4d}  {name}")
        if n == 0:
            missing.append(name)
    if missing:
        sys.exit("FAIL: never called from app code: " + ", ".join(missing))
    print(f"PASS {len(names)} extension methods are called from app code")


if __name__ == "__main__":
    main()
