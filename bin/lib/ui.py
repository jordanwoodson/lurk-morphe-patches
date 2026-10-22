"""Drive the test emulator (or a phone) from the shell. bin/ui runs this with python3 -I.

Shows the screen as a compact element list instead of a screenshot, taps elements by text, waits
for them, opens links, launches apps and the Lurk settings screen, and reads the `lurk` logcat tag.
Honors ANDROID_SERIAL (default emulator-5580) and MORPHE_WORK.
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = Path(os.environ.get("MORPHE_WORK") or Path.home() / "build/lurk-morphe-patches")
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5580")
PATCHES = ROOT / "patches/src/main/kotlin/io/github/jordanwoodson/patches"
SETTINGS_ACTIVITY = "io.github.jordanwoodson.extension.shared.settings.SettingsActivity"
LOG_TAGS = ["lurk:V", "AndroidRuntime:E", "*:S"]
GENERIC = {"View", "ViewGroup", "FrameLayout", "LinearLayout", "RelativeLayout", "ReactViewGroup", "ComposeView"}
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
LAST_XML = WORK / "ui/last.xml"


def adb(*args, check=True):
    r = subprocess.run(["adb", "-s", SERIAL, *args], text=True, capture_output=True)
    if check and r.returncode:
        sys.exit(f"adb {' '.join(args)}: {(r.stderr or r.stdout).strip() or 'failed'}")
    return r.stdout


def shell(*args, **kw):
    return adb("shell", *args, **kw)


def note(msg):
    print(msg, file=sys.stderr)


# region Apps


def packages():
    """slug -> package for the apps in the workspace (their shared/Constants.kt)."""
    out = {}
    for f in PATCHES.glob("*/shared/Constants.kt"):
        m = re.search(r'packageName = "([^"]+)"', f.read_text())
        if m:
            out[f.parents[1].name] = m.group(1)
    return out


def package(name):
    """The package for an app slug (twitch), or a package name as given (clones)."""
    if "." in name:
        return name
    known = packages()
    if name in known:
        return known[name]
    sys.exit(f"unknown app '{name}' (known: {' '.join(sorted(known))}); give a package name for a clone")


def is_root():
    return "uid=0(" in shell("id", check=False)


def ensure_root():
    if is_root():
        return
    out = adb("root", check=False)
    if "cannot run as root" in out:
        sys.exit("needs a rooted device: use the emulator, or open Lurk settings from the launcher (long-press the icon)")
    adb("wait-for-device")
    for _ in range(20):
        if is_root():
            return
        time.sleep(0.5)
    sys.exit("adb root did not take effect")


# endregion

# region Screen


class Node:
    __slots__ = ("index", "cls", "text", "desc", "rid", "pkg", "x", "y", "w", "h", "flags")

    def __init__(self, index, el):
        a = el.attrib
        self.index = index
        self.cls = a.get("class", "").rsplit(".", 1)[-1]
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.rid = a.get("resource-id", "").split("/", 1)[-1]
        self.pkg = a.get("package", "")
        m = BOUNDS.match(a.get("bounds", ""))
        x1, y1, x2, y2 = (int(v) for v in m.groups()) if m else (0, 0, 0, 0)
        self.x, self.y, self.w, self.h = (x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1
        flags = []
        if a.get("clickable") == "true":
            flags.append("clickable")
        if a.get("checkable") == "true":
            flags.append("checked" if a.get("checked") == "true" else "unchecked")
        if a.get("selected") == "true":
            flags.append("selected")
        if a.get("focused") == "true":
            flags.append("focused")
        if a.get("enabled") == "false":
            flags.append("disabled")
        if a.get("scrollable") == "true":
            flags.append("scrollable")
        self.flags = flags

    @property
    def labelled(self):
        """Worth a line by default: has text or a description, or an id and some state (clickable…)."""
        return bool(self.text or self.desc or (self.rid and self.flags))

    def line(self, verbose=False):
        parts = [f"#{self.index}"]
        if verbose or self.cls not in GENERIC:
            parts.append(self.cls)
        if self.text:
            parts.append(f'"{self.text[:80]}"')
        if self.desc:
            parts.append(f'desc="{self.desc[:60]}"')
        if self.rid:
            parts.append(f"id={self.rid}")
        parts.append(f"@{self.x},{self.y}")
        if verbose:
            parts.append(f"{self.w}x{self.h}")
        parts.extend(self.flags)
        return " ".join(parts)


def dump():
    """Nodes of the current screen, in document order. Keeps the XML for `#N` references."""
    out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
    start, end = out.find("<?xml"), out.rfind("</hierarchy>")
    if start < 0 or end < 0:
        sys.exit(f"uiautomator dump gave no hierarchy (screen off, or a secure window?): {out.strip()[-200:]}")
    xml = out[start:end + len("</hierarchy>")]
    LAST_XML.parent.mkdir(parents=True, exist_ok=True)
    LAST_XML.write_text(xml)
    return parse(xml)


def parse(xml):
    return [Node(i, el) for i, el in enumerate(ET.fromstring(xml).iter("node"))]


def last_nodes():
    if not LAST_XML.exists():
        sys.exit("no previous `bin/ui tree` to take #N from")
    return parse(LAST_XML.read_text())


def focus():
    """package/activity of the focused window, or ''."""
    out = shell("dumpsys", "window", "windows", check=False)
    m = re.search(r"mCurrentFocus=Window\{[^}]*\s(\S+)\}", out)
    if m:
        return m.group(1)
    out = shell("dumpsys", "activity", "activities", check=False)
    m = re.search(r"(?:topResumedActivity|mResumedActivity)=ActivityRecord\{[^}]*\s(\S+/\S+)\s", out)
    return m.group(1) if m else ""


def screen_size():
    out = shell("wm", "size")
    m = re.search(r"(?:Override|Physical) size: (\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (720, 1600)


def tree(nodes, verbose=False):
    lines = [f"# {focus() or 'no focused window'}"]
    lines += [n.line(verbose) for n in nodes if verbose or n.labelled]
    return "\n".join(lines)


def matches(nodes, query):
    """Nodes matching a query: '#N', 'text=…', 'desc=…', 'id=…', 'class=…', or any of text/desc/id.
    Exact (case-insensitive) matches first, then substrings, in screen order."""
    if re.fullmatch(r"#\d+", query):
        index = int(query[1:])
        return [n for n in last_nodes() if n.index == index]
    field, _, value = query.partition("=")
    fields = {"text": ["text"], "desc": ["desc"], "id": ["rid"], "class": ["cls"]}.get(field.lower()) if _ else None
    if not fields:
        fields, value = ["text", "desc", "rid"], query
    value = value.lower()
    exact = [n for n in nodes if any(getattr(n, f).lower() == value for f in fields)]
    partial = [n for n in nodes if n not in exact and any(value in getattr(n, f).lower() for f in fields)]
    return exact + partial


def pick(query, verbose=False):
    nodes = dump() if not re.fullmatch(r"#\d+", query) else last_nodes()
    found = matches(nodes, query)
    if not found:
        note(f"no element matches '{query}'. Screen:\n{tree(nodes, verbose)}")
        sys.exit(1)
    if len(found) > 1:
        note(f"{len(found)} elements match '{query}'; using the first (#{found[0].index}, {found[0].text or found[0].desc or found[0].rid!r})")
    return found[0]


# endregion

# region Commands


def cmd_tree(a):
    print(tree(dump(), a.all))


def cmd_find(a):
    found = matches(dump(), a.query)
    print("\n".join(n.line(a.all) for n in found) or f"no element matches '{a.query}'")
    sys.exit(0 if found else 1)


def cmd_tap(a):
    m = re.fullmatch(r"(\d+),(\d+)", a.query)
    if m:
        x, y = int(m.group(1)), int(m.group(2))
        label = f"{x},{y}"
    else:
        n = pick(a.query, a.all)
        x, y, label = n.x, n.y, n.line()
    if a.long:
        shell("input", "swipe", str(x), str(y), str(x), str(y), "800")
    else:
        shell("input", "tap", str(x), str(y))
    print(f"tapped {label}")


def cmd_wait(a):
    deadline = time.monotonic() + a.timeout
    while True:
        nodes = dump()
        found = matches(nodes, a.query)
        if bool(found) != a.gone:
            print(found[0].line() if found else f"'{a.query}' is gone")
            return
        if time.monotonic() > deadline:
            note(f"timed out after {a.timeout} s waiting for '{a.query}' to {'go away' if a.gone else 'appear'}. Screen:\n{tree(nodes)}")
            sys.exit(1)
        time.sleep(0.5)


def cmd_type(a):
    text = " ".join(a.text)
    if not text.isascii():
        sys.exit("`input text` only types ASCII")
    shell("input", "text", shlex.quote(text.replace(" ", "%s")))
    print(f"typed {text!r}")


def cmd_key(a):
    for key in a.keys:
        code = key if key.isdigit() else "KEYCODE_" + key.upper().removeprefix("KEYCODE_")
        shell("input", "keyevent", code)
    print(f"pressed {' '.join(a.keys)}")


def cmd_swipe(a):
    w, h = screen_size()
    cx, cy = w // 2, h // 2
    dx, dy = int(w * a.length), int(h * a.length)
    start, end = {
        "up": ((cx, cy + dy // 2), (cx, cy - dy // 2)),
        "down": ((cx, cy - dy // 2), (cx, cy + dy // 2)),
        "left": ((cx + dx // 2, cy), (cx - dx // 2, cy)),
        "right": ((cx - dx // 2, cy), (cx + dx // 2, cy)),
    }[a.direction]
    shell("input", "swipe", *map(str, start + end), str(a.ms))
    print(f"swiped {a.direction}")


def start(*args):
    out = shell("am", "start", "-W", *args, check=False)
    if "Error" in out or "Exception" in out:
        sys.exit(out.strip())
    m = re.search(r"TotalTime: (\d+)", out)
    return f" in {m.group(1)} ms" if m else ""


def cmd_open(a):
    args = ["-a", "android.intent.action.VIEW", "-d", a.url]
    if a.app:
        args += ["-p", package(a.app)]
    print(f"opened {a.url}{start(*args)}")


def cmd_launch(a):
    pkg = package(a.app)
    out = shell("cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", pkg)
    component = out.strip().splitlines()[-1] if out.strip() else ""
    if "/" not in component:
        sys.exit(f"{pkg} has no launcher activity (installed? bin/ui apps)")
    print(f"launched {component}{start('-n', component)}")


def cmd_lurk(a):
    pkg = package(a.app)
    ensure_root()
    print(f"opened Lurk settings of {pkg}{start('-n', f'{pkg}/{SETTINGS_ACTIVITY}')}")


def cmd_stop(a):
    for app in a.apps:
        shell("am", "force-stop", package(app))
    print(f"stopped {' '.join(a.apps)}")


def cmd_reset(a):
    for app in a.apps:
        out = shell("pm", "clear", package(app))
        if "Success" not in out:
            sys.exit(out.strip())
    print(f"cleared the data of {' '.join(a.apps)}")


def cmd_apps(a):
    by_package = {v: k for k, v in packages().items()}
    installed = sorted(line.split(":", 1)[1] for line in shell("pm", "list", "packages", "-3").split() if ":" in line)
    for pkg in installed:
        slug = by_package.get(pkg)
        print(f"{pkg}  ({slug})" if slug else pkg)
    if not installed:
        print("no third-party apps installed")


def cmd_shot(a):
    (WORK / "shots").mkdir(parents=True, exist_ok=True)
    path = WORK / "shots" / f"{a.name or time.strftime('%Y%m%d-%H%M%S')}.png"
    with open(path, "wb") as f:
        subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=f, check=True)
    print(path)


def cmd_log(a):
    if a.clear:
        adb("logcat", "-c")
    args = ["adb", "-s", SERIAL, "logcat", "-v", "time"]
    if a.dump:
        args.append("-d")
    if a.tail:
        args += ["-t", str(a.tail)]
    args += a.tags or LOG_TAGS
    os.execvp("adb", args)


# endregion


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True, metavar="command")
    query_help = "'#N' from the last tree, 'text=…', 'desc=…', 'id=…', 'class=…', or a word in text/desc/id"

    s = sub.add_parser("tree", help="the screen as one line per element (text, desc, id, centre, state)")
    s.add_argument("--all", action="store_true", help="every node, with class and size")
    s.set_defaults(func=cmd_tree)
    s = sub.add_parser("find", help="elements matching a query")
    s.add_argument("query", help=query_help)
    s.add_argument("--all", action="store_true")
    s.set_defaults(func=cmd_find)
    s = sub.add_parser("tap", help="tap the first element matching a query, or x,y")
    s.add_argument("query", help=query_help + ", or x,y")
    s.add_argument("--long", action="store_true", help="long press")
    s.add_argument("--all", action="store_true", help="show every node when nothing matches")
    s.set_defaults(func=cmd_tap)
    s = sub.add_parser("wait", help="wait for an element to appear (or, with --gone, to disappear)")
    s.add_argument("query", help=query_help)
    s.add_argument("--timeout", type=float, default=20, help="seconds (default 20)")
    s.add_argument("--gone", action="store_true")
    s.set_defaults(func=cmd_wait)
    s = sub.add_parser("type", help="type ASCII text into the focused field")
    s.add_argument("text", nargs="+")
    s.set_defaults(func=cmd_type)
    s = sub.add_parser("key", help="press keys: back, home, enter, tab, del, menu, volume_up, app_switch, …")
    s.add_argument("keys", nargs="+")
    s.set_defaults(func=cmd_key)
    s = sub.add_parser("swipe", help="swipe across the middle of the screen")
    s.add_argument("direction", choices=["up", "down", "left", "right"])
    s.add_argument("--length", type=float, default=0.5, help="fraction of the screen (default 0.5)")
    s.add_argument("--ms", type=int, default=300)
    s.set_defaults(func=cmd_swipe)
    s = sub.add_parser("open", help="open a link (deep link into an app with --app, since a patched app's cert fails app-link verification)")
    s.add_argument("url")
    s.add_argument("--app", help="app slug or package to handle it")
    s.set_defaults(func=cmd_open)
    s = sub.add_parser("launch", help="start an app from its launcher activity")
    s.add_argument("app", help="slug (twitch, kick, rumble) or package")
    s.set_defaults(func=cmd_launch)
    s = sub.add_parser("lurk", help="open an app's Lurk settings screen (root on the emulator)")
    s.add_argument("app")
    s.set_defaults(func=cmd_lurk)
    s = sub.add_parser("stop", help="force-stop apps")
    s.add_argument("apps", nargs="+")
    s.set_defaults(func=cmd_stop)
    s = sub.add_parser("reset", help="clear apps' data (sign-in, settings): a fresh first start")
    s.add_argument("apps", nargs="+")
    s.set_defaults(func=cmd_reset)
    s = sub.add_parser("apps", help="installed third-party packages, with the workspace app they are")
    s.set_defaults(func=cmd_apps)
    s = sub.add_parser("shot", help="screenshot to $MORPHE_WORK/shots/")
    s.add_argument("name", nargs="?")
    s.set_defaults(func=cmd_shot)
    s = sub.add_parser("log", help="logcat of the lurk tag (and crashes); follows until Ctrl-C unless --dump")
    s.add_argument("-c", "--clear", action="store_true", help="clear the buffer first")
    s.add_argument("-d", "--dump", action="store_true", help="print what is buffered and exit")
    s.add_argument("-t", "--tail", type=int, help="only the last N lines")
    s.add_argument("tags", nargs="*", help="logcat filters instead of lurk:V AndroidRuntime:E *:S")
    s.set_defaults(func=cmd_log)

    a = p.parse_args()
    a.func(a)


if __name__ == "__main__":
    main()
