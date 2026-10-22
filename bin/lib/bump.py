"""Sort what breaks when the patches meet a new app version. bin/bump runs it; run with python3 -I.

    python3 -I bin/lib/bump.py <app> <version> --result result.json --input <new apk>
        [--current <apk of the version the patches target>] [--hermes-kt Hermes.kt] [--tests tests/<app>]

Patch failures from Morphe's result file are sorted by cause:

  SHA gate      the React Native bundle isn't the one Hermes.kt pins, so every Hermes edit stops there
  Hermes        a Hermes edit found its pattern no times or more than once
  Fingerprint   a fingerprint, class lookup or one of the apps' match helpers found nothing or too much
  Other         anything else, with its message

R8-pinned tests are found without a device: class names that the probes in tests/<app>/ use, that the
current version defines and the new one doesn't. A name that still exists may now be another class.
Exits 1 when anything failed or a pinned name is gone.
"""

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts
sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk  # noqa: E402

CAUSES = [
    ("sha", "SHA gate: the Hermes bundle is not the pinned one"),
    ("hermes", "Hermes pattern"),
    ("fingerprint", "Fingerprint, class or bytecode match"),
    ("other", "Other"),
]
FRAME = re.compile(r"^\s+at |^\s*\.\.\. \d+ (more|common frames omitted)")
EXCEPTION_PREFIX = re.compile(r"^(?:Caused by: )?(?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable)(?:: |$)")
CLASS_NAME = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+")
STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


class Failure:
    """One failed patch. A patch that failed because a patch it depends on did is traced to that root."""

    def __init__(self, name, reason):
        self.name = name
        reason = reason or ""
        chain = re.findall(r'depends on "([^"]+)"', reason)
        earlier = re.search(r"The patch '([^']+)' failed previously", reason)
        self.root = earlier.group(1) if earlier else (chain[-1] if chain else name)
        lines = [line for line in reason.splitlines() if line.strip() and not FRAME.match(line)]
        messages = [EXCEPTION_PREFIX.sub("", line).strip() for line in lines]
        messages = [m for m in messages if m and "depends on" not in m and "failed previously" not in m]
        # The deepest cause comes last in a stack trace and says the most. None: only the root knows.
        self.message = messages[-1] if messages else (None if earlier else "(no message)")
        self.cause = classify(messages) if self.message else None

    @property
    def via(self):
        if self.root == self.name:
            return ""
        # Patches without a name (shared helpers) show up as "BytecodePatch@1234".
        return " (in a shared patch)" if re.fullmatch(r"\w*Patch@\d+", self.root) else f" (in {self.root})"


def classify(messages):
    text = "\n".join(messages)
    if re.search(r"Hermes: unrecognized .*bundle", text):
        return "sha"
    if "Failed to match the fingerprint" in text or "Could not find class" in text:
        return "fingerprint"
    if re.search(r"hermes", text, re.I):
        return "hermes"
    # The apps' own match helpers (shared/Bytecode.kt and the patches) prefix their messages with the app.
    if re.search(r"^(?:Twitch|Kick|Rumble)\b[^:\n]*: ", text, re.M) or "expected exactly one" in text:
        return "fingerprint"
    return "other"


def failures(result):
    found = []
    for entry in result.get("failedPatches", []):
        patch = entry.get("patch")
        name = patch.get("name") if isinstance(patch, dict) else entry.get("name") or str(patch)
        found.append(Failure(name, entry.get("reason") or entry.get("exception") or ""))
    # "failed previously" carries no message: take it from a failure that reached the same root first.
    known = {f.root: (f.cause, f.message) for f in found if f.message}
    for failure in found:
        if failure.message is None:
            failure.cause, failure.message = known.get(failure.root, ("other", f"{failure.root} failed"))
    return found


def probe_class_names(tests):
    """Class-like string literals in the probes, with the files that use them."""
    names = defaultdict(set)
    for path in sorted(Path(tests).glob("*")):
        if path.suffix not in (".java", ".kt"):
            continue
        for literal in STRING.findall(path.read_text(encoding="utf-8", errors="replace")):
            if CLASS_NAME.fullmatch(literal):
                names[literal].add(path.name)
    return names


def summary(app, version, result, new_classes=None, current_classes=None, probes=None,
            pinned_sha=None, new_sha=None):
    """The report as lines, and whether anything needs work."""
    lines, broken = [], False
    if result is None:
        return [f"{app} {version}: Morphe wrote no result file, so patching stopped before the patches "
                "ran. See patch.log."], True
    failed = failures(result)
    applied = len(result.get("appliedPatches", []))
    lines.append(f"{app} {version}: {applied} of {applied + len(failed)} patches applied, {len(failed)} failed")
    steps = [s.get("step") for s in result.get("patchingSteps", []) if not s.get("success")]
    if steps:
        broken = True
        lines.append(f"  failed steps: {', '.join(steps)}")
    if pinned_sha and new_sha and pinned_sha != new_sha:
        lines.append(f"  the bundle changed: Hermes.kt pins {pinned_sha[:12]}..., {version} has {new_sha[:12]}...")
    by_cause = defaultdict(list)
    for failure in failed:
        by_cause[failure.cause].append(failure)
    for cause, title in CAUSES:
        if not by_cause[cause]:
            continue
        broken = True
        lines += ["", f"{title} ({len(by_cause[cause])})"]
        # Failures with the same root and message are one problem: list it once with every patch it stops.
        problems = defaultdict(list)
        for failure in by_cause[cause]:
            problems[(failure.message, failure.via)].append(failure.name)
        for (message, via), names in sorted(problems.items(), key=lambda item: (-len(item[1]), item[0])):
            lines.append(f"  - {shorten(message)}{via}")
            lines.append(f"    {', '.join(sorted(names, key=str.casefold))}")
    if probes is not None and new_classes is not None:
        pinned = {name: files for name, files in probes.items()
                  if current_classes is None or name in current_classes}
        if current_classes is None:  # without the current version, only names that look like app classes
            pinned = {n: f for n, f in pinned.items() if not n.startswith(("java.", "javax.", "android.", "sun.",
                                                                          "dalvik.", "kotlin.", "org.json.",
                                                                          "io.github.jordanwoodson."))}
        gone = {name: files for name, files in pinned.items() if name not in new_classes}
        if gone:
            broken = True
            basis = "the current version defines and " if current_classes is not None else ""
            lines += ["", f"R8-pinned tests: {len(gone)} class names the probes use that {basis}{version} doesn't"]
            by_file = defaultdict(list)
            for name, files in gone.items():
                for file in files:
                    by_file[file].append(name)
            for file in sorted(by_file):
                lines.append(f"  - {file}: {', '.join(sorted(by_file[file]))}")
    if not broken:
        lines += ["", "Everything applied and no probe names are gone."]
    return lines, broken


def shorten(text, limit=180):
    text = " ".join(text.split())
    return text if len(text) <= limit else text[:limit - 3] + "..."


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("app")
    parser.add_argument("version")
    parser.add_argument("--result", required=True)
    parser.add_argument("--input", required=True, help="the stored file of the new version")
    parser.add_argument("--current", help="the stored file of the version the patches target now")
    parser.add_argument("--hermes-kt", help="the app's shared/Hermes.kt, for React Native apps")
    parser.add_argument("--tests", help="tests/<app>, whose probes may pin R8 class names")
    args = parser.parse_args()

    result_path = Path(args.result)
    result = json.loads(result_path.read_text(encoding="utf-8")) if result_path.is_file() else None
    pinned_sha = new_sha = None
    if args.hermes_kt and Path(args.hermes_kt).is_file():
        found = re.search(r'const val SOURCE_SHA256 = "([0-9a-f]{64})"', Path(args.hermes_kt).read_text(encoding="utf-8"))
        pinned_sha = found.group(1) if found else None
        bundle = apk.hermes_bundle(args.input)
        new_sha = apk.hashlib.sha256(bundle).hexdigest() if bundle else None
    probes = probe_class_names(args.tests) if args.tests and Path(args.tests).is_dir() else None
    new_classes = apk.class_names(args.input) if probes else None
    current_classes = apk.class_names(args.current) if probes and args.current else None
    lines, broken = summary(args.app, args.version, result, new_classes, current_classes, probes, pinned_sha, new_sha)
    print("\n".join(lines))
    sys.exit(1 if broken else 0)


if __name__ == "__main__":
    main()
