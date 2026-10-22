#!/usr/bin/env python3
"""Print the extension hooks a patched Rumble APK must call, from tests/rumble/expected-hooks.txt.

Reads the Lurk settings registry out of the APK's dex files and keeps the hooks whose setting is registered,
so a build with only some patches is checked for those. Prints nothing for an APK without a Rumble registry
(the stock APK). bin/test-rumble passes the output to tests/verify_extension_calls.py.

Usage: python3 -I tests/rumble/expected_hooks.py <patched.apk>
"""
import json
import re
import sys
import zipfile
from pathlib import Path

HOOKS = Path(__file__).resolve().with_name('expected-hooks.txt')
MARKER = b'{"name":"Lurk",'


def registry(apk):
    """The registry JSON the settings patch wrote into the extension (or an assets/lurk/ file), or None."""
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.startswith('assets/lurk/') and name.endswith('.json'):
                try:
                    found = json.loads(z.read(name))
                except ValueError:
                    continue
                if isinstance(found, dict) and found.get('app') == 'Rumble' and 'settings' in found:
                    return found
        for name in z.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name):
                continue
            data = z.read(name)
            start = data.find(MARKER)
            while start >= 0:
                end = data.index(b'\0', start)
                try:
                    found = json.loads(data[start:end].decode('utf-8', 'replace'))
                except ValueError:
                    found = None
                if found and found.get('app') == 'Rumble':
                    return found
                start = data.find(MARKER, end)
    return None


def expected(keys):
    hooks = []
    for line in HOOKS.read_text().splitlines():
        line = line.split('#', 1)[0].strip()
        if not line:
            continue
        wanted, hook = line.split()
        if wanted == '*' or any(key in keys for key in wanted.split('|')):
            hooks.append(hook)
    return hooks


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    reg = registry(sys.argv[1])
    if reg is None:
        return
    keys = {setting['key'] for setting in reg['settings']}
    print('\n'.join(expected(keys)))


if __name__ == '__main__':
    main()
