#!/usr/bin/env python3
"""Static checks on the Kick patch sources that Manager users and version bumps depend on.

Usage: python3 -I tests/kick/verify_sources.py

- Every named Kick patch that edits Kick's JavaScript (depends on kickHermesPatch, or on the 7TV composer that does)
  ends its description with OTA_UPDATES_OFF, since including it turns off Kick's over-the-air updates.
- No Kick patch source spells the app version: it is written once, in shared/Constants.kt (COMPATIBILITY_KICK).
  kick/settings/SettingsPatch.kt is listed in KNOWN_VERSION_LITERALS until it derives its message too.
"""
from pathlib import Path
import re
import sys

KICK = Path(__file__).resolve().parents[2] / "patches/src/main/kotlin/io/github/jordanwoodson/patches/kick"
HERMES_DEPENDENCIES = ("kickHermesPatch", "kickComposerPatch")
KNOWN_VERSION_LITERALS = {"settings/SettingsPatch.kt"}


def main():
    failures = []
    patches = 0
    for path in sorted(KICK.rglob("*.kt")):
        relative = path.relative_to(KICK).as_posix()
        text = path.read_text()
        if relative != "shared/Constants.kt" and relative not in KNOWN_VERSION_LITERALS:
            for match in re.finditer(r'"[^"\n]*\b\d+\.\d+\.\d+\b[^"\n]*"', text):
                if re.search(r"\b40\.\d+\.\d+\b", match.group(0)):
                    failures.append(f"{relative}: version literal {match.group(0)}")
        for declaration in re.finditer(r"val \w+ = (?:bytecodePatch|rawResourcePatch|resourcePatch)\(\s*\n\s*name = ", text):
            patches += 1
            body = text[declaration.start():]
            header = body[:body.index(") {")]
            block = body[:body.index("execute {")] if "execute {" in body else body
            depends = re.search(r"dependsOn\(([^)]*)\)", block)
            edits_js = depends and any(name in depends.group(1) for name in HERMES_DEPENDENCIES)
            if edits_js and "OTA_UPDATES_OFF" not in header:
                failures.append(f"{relative}: edits Kick's JavaScript but its description lacks OTA_UPDATES_OFF")
    if patches < 20:
        failures.append(f"found only {patches} named Kick patches; the pattern no longer matches the sources")
    for failure in failures:
        print("FAIL " + failure)
    if failures:
        sys.exit(1)
    print(f"PASS: {patches} named Kick patches; every JavaScript-editing one says it turns off Kick's updates; "
          "no version literal outside Constants.kt")


if __name__ == "__main__":
    main()
