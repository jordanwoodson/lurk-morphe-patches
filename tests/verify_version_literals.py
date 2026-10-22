#!/usr/bin/env python3
"""Fail on an app version written into a patch: a string literal like "31.7.0" outside shared/Constants.kt.

Each app's shared/Constants.kt holds the versions it targets; code and messages that need the version
read it from COMPATIBILITY_<APP>.targets, so a version bump changes one line. This scans the string
literals (not comments) of the Kotlin and Java sources under patches/src and lists every offender.

Files in PENDING had such literals when this check was added and are being moved over; their offenders
are listed without failing. Remove a file from PENDING once it is clean (the check says when).

    python3 tests/verify_version_literals.py [--strict]     # --strict: PENDING files fail too
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "patches/src"
VERSION = re.compile(r"(?<![\w.])\d+\.\d+\.\d+(?![\w.])")
_P = "patches/src/main/kotlin/io/github/jordanwoodson/patches/"
PENDING: set[str] = set()  # every file is clean since the 2026-10-22 review; add a path here only while moving a new one over


class Literals:
    """The string literals of Kotlin or Java source as (line, text); template expressions are code."""

    def __init__(self, text: str):
        self.text, self.pos, self.found = text, 0, []
        self.code()

    def code(self, in_template: bool = False) -> None:
        text, depth = self.text, 0
        while self.pos < len(text):
            if text.startswith("//", self.pos):
                end = text.find("\n", self.pos)
                self.pos = len(text) if end < 0 else end
            elif text.startswith("/*", self.pos):
                self.comment()
            elif text.startswith('"""', self.pos):
                self.string('"""')
            elif text[self.pos] == '"':
                self.string('"')
            elif text[self.pos] == "'":
                self.char()
            else:
                if in_template and text[self.pos] == "{":
                    depth += 1
                elif in_template and text[self.pos] == "}":
                    self.pos += 1
                    if depth == 0:
                        return
                    depth -= 1
                    continue
                self.pos += 1

    def comment(self) -> None:
        depth = 0  # Kotlin block comments nest
        while self.pos < len(self.text):
            if self.text.startswith("/*", self.pos):
                depth, self.pos = depth + 1, self.pos + 2
            elif self.text.startswith("*/", self.pos):
                depth, self.pos = depth - 1, self.pos + 2
                if depth == 0:
                    return
            else:
                self.pos += 1

    def string(self, quote: str) -> None:
        text, start = self.text, self.pos
        self.pos += len(quote)
        parts, begin = [], self.pos
        while self.pos < len(text):
            if quote == '"' and text[self.pos] == "\\":
                self.pos += 2
            elif quote == '"' and text[self.pos] == "\n":
                break  # unterminated: leave it
            elif text.startswith(quote, self.pos):
                while quote == '"""' and text.startswith('""""', self.pos):
                    self.pos += 1  # a raw string may end in quotes
                parts.append(text[begin:self.pos])
                self.pos += len(quote)
                break
            elif text.startswith("${", self.pos):
                parts.append(text[begin:self.pos])
                self.pos += 2
                self.code(in_template=True)
                begin = self.pos
            else:
                self.pos += 1
        self.found.append((text.count("\n", 0, start) + 1, "".join(parts)))

    def char(self) -> None:
        self.pos += 3 if self.text.startswith("\\", self.pos + 1) else 2
        end = self.text.find("'", self.pos)
        self.pos = len(self.text) if end < 0 else end + 1


def offenders(root: Path = ROOT) -> list[tuple[str, int, str]]:
    found = []
    for path in sorted((root / "patches/src").rglob("*")):
        if path.suffix not in (".kt", ".java") or path.match("*/shared/Constants.kt"):
            continue
        relative = path.relative_to(root).as_posix()
        for line, literal in Literals(path.read_text(encoding="utf-8")).found:
            if VERSION.search(literal):
                found.append((relative, line, literal))
    return found


def main() -> int:
    strict = "--strict" in sys.argv[1:]
    found = offenders()
    failing = [o for o in found if strict or o[0] not in PENDING]
    pending = [o for o in found if o not in failing]
    for title, entries in (("App versions written into patches (move them to shared/Constants.kt)", failing),
                           ("Pending, not failing yet (see PENDING)", pending)):
        if entries:
            print(f"{title}: {len(entries)}")
            for path, line, literal in entries:
                print(f'  {path}:{line}: "{" ".join(literal.split())}"')
    clean = sorted(PENDING - {path for path, _, _ in found})
    if clean:
        print("These PENDING files no longer have version literals; remove them from PENDING:")
        print("".join(f"  {path}\n" for path in clean), end="")
    if failing:
        print(f"FAIL: {len(failing)} version literals outside shared/Constants.kt")
        return 1
    print(f"PASS: no version literals outside shared/Constants.kt"
          + (f" apart from {len(pending)} pending" if pending else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
