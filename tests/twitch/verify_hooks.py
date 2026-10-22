#!/usr/bin/env python3
"""Check that a patched Twitch APK calls every extension method its included patches inject.

Reads tests/twitch/expected-hooks.txt, keeps the groups whose condition the APK meets (a Lurk settings key
in the APK's registry, or a file in the APK), and runs tests/verify_extension_calls.py on that list.

Usage: python3 -I tests/twitch/verify_hooks.py <patched.apk>
"""
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXPECTED = HERE / "expected-hooks.txt"
CHECKER = HERE.parent / "verify_extension_calls.py"


def registry_keys(apk):
    """The setting keys of the Lurk registry JSON, a string constant in the extension's dex."""
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = z.read(name)
            start = data.find(b'{"name":"Lurk"')
            if start < 0:
                continue
            end = data.index(b"\x00", start)
            # Dex strings are MUTF-8; the keys are ASCII, so a lossy decode is enough.
            registry = json.loads(data[start:end].decode("utf-8", "replace"))
            return {entry["key"] for entry in registry["settings"]}
    sys.exit("FAIL: no Lurk settings registry in " + apk)


def expected(apk, keys):
    with zipfile.ZipFile(apk) as z:
        files = set(z.namelist())
    names, applies = [], True
    for raw in EXPECTED.read_text().splitlines():
        line = raw.strip()
        if not line:
            continue
        if line == "# always":
            applies = True
        elif line.startswith("# if "):
            conditions = line[len("# if "):].split()
            applies = any(c[len("asset:"):] in files if c.startswith("asset:") else c in keys for c in conditions)
        elif not line.startswith("#") and applies:
            names.append(line)
    return names


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    names = expected(apk, registry_keys(apk))
    print(f"{len(names)} extension calls expected for the patches in {Path(apk).name}", flush=True)
    sys.exit(subprocess.run([sys.executable, "-I", str(CHECKER), apk, *names]).returncode)


if __name__ == "__main__":
    main()
