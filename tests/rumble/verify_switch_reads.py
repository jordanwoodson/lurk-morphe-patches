#!/usr/bin/env python3
"""Check that code in a patched Rumble APK reads every Lurk setting it registers.

A switch whose key no code loads does nothing: a key typed differently in the patch and the extension, or
a hook that never made it into the dex. For each setting in the APK's registry this lists the classes that
load its key with const-string, app code (the patches' inline Settings.isEnabled calls, such as Disable
autoplay's DataStore defaults) or the extension, and fails for a key nobody loads.

Usage: python3 -I tests/rumble/verify_switch_reads.py <patched.apk>
Needs dexdump from the Android build-tools (ANDROID_HOME). bin/test-rumble runs it.
"""
import importlib.util
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXTENSION = 'Lio/github/jordanwoodson/extension/'


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    reg = load('expected_hooks').registry(apk)
    if reg is None:
        sys.exit(f'{apk} has no Lurk settings registry for Rumble')
    keys = [s['key'] for s in reg['settings']]
    compose = load('verify_compose')
    loaders = {key: set() for key in keys}
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as tmp:
        for name in sorted(n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)):
            data = z.read(name)
            strings = set(compose.dex_strings(data))
            if not any(key in strings for key in keys):
                continue
            path = Path(tmp) / name
            path.write_bytes(data)
            out = subprocess.run([compose.dexdump(), '-d', str(path)], capture_output=True, text=True,
                                 errors='replace').stdout
            current = None
            for line in out.splitlines():
                m = re.match(r"\s+Class descriptor\s+:\s+'(L[^']+;)'", line)
                if m:
                    current = m.group(1)
                    continue
                m = re.search(r'const-string(?:/jumbo)? v\d+, "([^"]*)"', line)
                if m and current and m.group(1) in loaders:
                    loaders[m.group(1)].add(current)
    missing = []
    for key in keys:
        classes = loaders[key]
        app = sorted(c for c in classes if not c.startswith(EXTENSION))
        ext = sorted(c.rsplit('/', 1)[1][:-1] for c in classes if c.startswith(EXTENSION))
        where = ', '.join(([f'app: {len(app)}'] if app else []) + ([f'extension: {", ".join(ext)}'] if ext else []))
        print(f'  {key:28} {where or "NOT READ"}')
        if not classes:
            missing.append(key)
    if missing:
        sys.exit('FAIL: no code reads ' + ', '.join(missing))
    print(f'PASS all {len(keys)} registered settings are read by code')


if __name__ == '__main__':
    main()
