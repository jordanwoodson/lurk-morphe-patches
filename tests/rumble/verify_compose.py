#!/usr/bin/env python3
"""Check the Compose assumptions the Rumble patches make, on a stock or patched Rumble APK.

1. Trace labels. Every composableFingerprint (and the bottom bar's lambda) finds its composable by the trace
   label the Compose compiler writes into it: "com.rumble.battles.….Name (File.kt:line)". The script reads
   each label from the patch sources and looks for it in the APK's dex strings. A build compiled without
   those labels fails every Compose hook at once; the script then says so in one message and lists the hooks.
2. App Info rows. The settings patch draws its Lurk row after the last of exactly three rows
   (Data Consent, Credits, Send Feedback) that Settings > App Info draws with one row composable. The script
   counts those calls in the AppInfoSection composable (dexdump of the one dex holding it).

Run it on a new Rumble version before updating compatibility:
  python3 -I tests/rumble/verify_compose.py <rumble.apk>
bin/test-rumble runs it on the APK it tests. Needs dexdump from the Android build-tools (ANDROID_HOME).
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [ROOT / 'patches/src/main/kotlin/io/github/jordanwoodson/patches/rumble',
           ROOT / 'patches/src/main/kotlin/io/github/jordanwoodson/patches/shared/compose']
APP_INFO = 'com.rumble.battles.settings.presentation.AppInfoSection'
# Any Compose trace label of Rumble's own code: "com.rumble.….Name (File.kt:12)".
TRACE = re.compile(r'^com\.rumble\.[\w.$<>]+ \([\w$]+\.kt:\d+\)$')


def labels():
    """{label: [source files]} for every trace label the patches match on."""
    found = {}
    for base in SOURCES:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob('*.kt')):
            text = path.read_text()
            hits = re.findall(r'composableFingerprint\(\s*"([^"]+)"', text)
            hits += [h[:-2] for h in re.findall(r'"(com\.rumble\.[^"]+ \()"', text)]
            for label in hits:
                found.setdefault(label, []).append(str(path.relative_to(ROOT)))
    return found


def short(label):
    """The composable's own name: the last part, or the one before a lambda's "<anonymous>"."""
    parts = label.split('.')
    return parts[-2] + '.' + parts[-1] if parts[-1].startswith('<') else parts[-1]


def uleb128(data, offset):
    result = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7f) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


def dex_strings(data):
    """Every string in a dex file's string table (MUTF-8 read as UTF-8, which matches for these labels)."""
    size = int.from_bytes(data[0x38:0x3c], 'little')
    table = int.from_bytes(data[0x3c:0x40], 'little')
    strings = []
    for i in range(size):
        offset = int.from_bytes(data[table + 4 * i:table + 4 * i + 4], 'little')
        _, start = uleb128(data, offset)
        end = data.index(b'\0', start)
        strings.append(data[start:end].decode('utf-8', 'replace'))
    return strings


def dexdump():
    home = Path(os.environ.get('ANDROID_HOME', ''))
    tools = sorted(home.glob('build-tools/*/dexdump'),
                   key=lambda p: [int(x) for x in re.findall(r'\d+', p.parent.name)])
    if not tools:
        sys.exit('dexdump not found under $ANDROID_HOME/build-tools')
    return str(tools[-1])


def app_info_rows(dex_bytes):
    """(row method, calls) of the AppInfoSection composable's row calls."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'classes.dex'
        path.write_bytes(dex_bytes)
        out = subprocess.run([dexdump(), '-d', str(path)], capture_output=True, text=True, errors='replace').stdout
    methods, current = [], []
    for line in out.splitlines():
        if re.match(r'\s+#\d+\s+:\s+\(in L', line):
            current = []
            methods.append(current)
        current.append(line)
    for method in methods:
        body = '\n'.join(method)
        if f'"{APP_INFO} (' not in body:
            continue
        # The row: (modifier, text, value, showArrow, composer, $changed, $default)V.
        calls = re.findall(r'invoke-static/range \{[^}]*\}, (L[^;]+;\.[\w$]+):\(L[^;]+;Ljava/lang/String;'
                           r'Ljava/lang/String;ZL[^;]+;II\)V', body)
        return calls
    return None


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    wanted = labels()
    strings, holder = set(), {}
    with zipfile.ZipFile(sys.argv[1]) as z:
        dexes = {name: z.read(name) for name in z.namelist() if re.fullmatch(r'classes\d*\.dex', name)}
    for name, data in dexes.items():
        for string in dex_strings(data):
            strings.add(string)
            if string.startswith(APP_INFO + ' ('):
                holder[name] = data

    traced = [s for s in strings if TRACE.match(s)]
    failures = []
    if not traced:
        failures.append(
            f'This Rumble build has no Compose trace labels ("com.rumble.….Name (File.kt:line)"), so every '
            f'Compose hook fails: {len(wanted)} labels in {len({f for fs in wanted.values() for f in fs})} '
            f'files ({", ".join(sorted(short(label) for label in wanted))}). The patches need a '
            f'build compiled with Compose source information, or new fingerprints.')
    else:
        for label, files in sorted(wanted.items()):
            hits = [s for s in strings if s.startswith(label + ' (')]
            status = f'{len(hits)} match' + ('' if len(hits) == 1 else 'es')
            print(f'{status:>10}  {label}  ({", ".join(files)})')
            if not hits:
                failures.append(f'trace label "{label} (" is missing (used by {", ".join(files)})')

    # A patched APK keeps the label in the string table of the dex its class was moved out of.
    found = [rows for rows in (app_info_rows(data) for data in holder.values()) if rows is not None]
    if len(found) != 1:
        failures.append(f'expected one AppInfoSection composable, found {len(found)}')
    else:
        rows = found[0]
        if len(rows) != 3 or len(set(rows)) != 1:
            failures.append(f'App Info draws {len(rows)} rows with {len(set(rows))} row composables; the settings '
                            f'patch needs three from one (Data Consent, Credits, Send Feedback) to add its Lurk row')
        else:
            print(f'      3 rows  App Info ({rows[0]})')

    if failures:
        sys.exit('FAIL:\n  ' + '\n  '.join(failures))
    print(f'PASS {len(wanted)} Compose trace labels and the three App Info rows')


if __name__ == '__main__':
    main()
