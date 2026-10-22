"""APK helpers for bin/* scripts. Run with python3 -I.

Handles plain .apk files and the bundle formats the allowed sources hand out:
.apkm (APKMirror), .xapk (Uptodown) and .apks / split directories (Google Play via apkeep).
"""

import argparse
import io
import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.request
import zipfile

EXODUS_URL = "https://reports.exodus-privacy.eu.org/api/trackers"
HERMES_BUNDLE = "assets/index.android.bundle"
HERMES_MAGIC = bytes.fromhex("c61fbc03c103191f")
URL_RE = re.compile(rb"https?://([A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,})")


# region Reading files


def is_bundle(path):
    return path.endswith((".apkm", ".xapk", ".apks"))


def base_entry(zf, package=None):
    """Name of the base APK inside a bundle."""
    names = [n for n in zf.namelist() if n.endswith(".apk") and "/" not in n]
    for candidate in ("base.apk", f"{package}.apk" if package else None):
        if candidate in names:
            return candidate
    non_split = [n for n in names if not n.startswith(("split_", "config."))]
    if len(non_split) == 1:
        return non_split[0]
    # Last resort: the base is almost always the largest.
    return max(names, key=lambda n: zf.getinfo(n).file_size)


def read_base(path):
    """Bytes of the base APK of an apk or bundle."""
    if not is_bundle(path):
        with open(path, "rb") as f:
            return f.read()
    with zipfile.ZipFile(path) as zf:
        package = bundle_meta(zf).get("package")
        return zf.read(base_entry(zf, package))


def all_apks(path):
    """(name, bytes) for every APK in an apk or bundle."""
    if not is_bundle(path):
        with open(path, "rb") as f:
            yield os.path.basename(path), f.read()
        return
    with zipfile.ZipFile(path) as zf:
        for n in zf.namelist():
            if n.endswith(".apk") and "/" not in n:
                yield n, zf.read(n)


def bundle_meta(zf):
    """Package/version from the metadata APKMirror (info.json) or Uptodown (manifest.json) add."""
    names = zf.namelist()
    if "info.json" in names:
        d = json.loads(zf.read("info.json"))
        return {"package": d.get("pname"), "version": d.get("release_version"), "version_code": d.get("versioncode")}
    if "manifest.json" in names:
        d = json.loads(zf.read("manifest.json"))
        return {"package": d.get("package_name"), "version": d.get("version_name"), "version_code": d.get("version_code")}
    return {}


def badging(apk_path, aapt2):
    out = subprocess.run([aapt2, "dump", "badging", apk_path], capture_output=True, text=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='([^']*)' versionName='([^']*)'", out)
    if not m:
        sys.exit(f"aapt2 could not read {apk_path}")
    return {"package": m.group(1), "version_code": m.group(2), "version": m.group(3)}


def info(path, aapt2, tmp_base):
    meta = {}
    if is_bundle(path):
        with zipfile.ZipFile(path) as zf:
            meta = bundle_meta(zf)
    if not meta.get("package") or not meta.get("version"):
        with open(tmp_base, "wb") as f:
            f.write(read_base(path))
        meta = badging(tmp_base, aapt2)
    meta["format"] = os.path.splitext(path)[1].lstrip(".")
    return meta


# endregion

# region Dex parsing


def uleb128(buf, pos):
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def dex_strings(dex):
    count, offset = int.from_bytes(dex[0x38:0x3C], "little"), int.from_bytes(dex[0x3C:0x40], "little")
    strings = []
    for i in range(count):
        data_off = int.from_bytes(dex[offset + i * 4: offset + i * 4 + 4], "little")
        _, start = uleb128(dex, data_off)
        end = dex.index(b"\0", start)
        strings.append(dex[start:end])
    return strings


def dex_types(dex, strings):
    count, offset = int.from_bytes(dex[0x40:0x44], "little"), int.from_bytes(dex[0x44:0x48], "little")
    return [strings[int.from_bytes(dex[offset + i * 4: offset + i * 4 + 4], "little")] for i in range(count)]


# endregion

# region Commands


def cmd_info(args):
    """Shell assignments (package=..., version=...) for eval in bin/*. Values are quoted."""
    for key, value in info(args.file, args.aapt2, args.tmp).items():
        print(f"{key}={shlex.quote(str(value or ''))}")


def cmd_extract_base(args):
    with open(args.out, "wb") as f:
        f.write(read_base(args.file))


def cmd_pack_splits(args):
    """Zip a split-APK directory (apkeep Google Play output) into an .apks file Morphe accepts."""
    apks = sorted(n for n in os.listdir(args.dir) if n.endswith(".apk"))
    if not apks:
        sys.exit(f"no .apk files in {args.dir}")
    base = f"{args.package}.apk" if f"{args.package}.apk" in apks else "base.apk"
    if base not in apks:
        base = max(apks, key=lambda n: os.path.getsize(os.path.join(args.dir, n)))
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_STORED) as zf:
        for n in apks:
            arc = "base.apk" if n == base else (n if n.startswith("split_") else f"split_{n}")
            zf.write(os.path.join(args.dir, n), arc)


ABI_SPLIT = re.compile(r"^split_config\.(arm64_v8a|armeabi_v7a|x86_64|x86)\.apk$")
DPI_SPLIT = re.compile(r"^split_config\.((?:l|m|tv|h|xh|xxh|xxxh)dpi)\.apk$")
LANG_SPLIT = re.compile(r"^split_config\.[a-z]{2,3}(?:_[A-Za-z]{2,})?\.apk$")


def variant(path):
    """What a stored file is built for: ABIs with native code, density and language splits.
    A bundle names them in its splits; a plain APK shows them in lib/. No ABI = no native code."""
    abis, dpis, langs = set(), set(), 0
    if is_bundle(path):
        with zipfile.ZipFile(path) as zf:
            for n in zf.namelist():
                if (m := ABI_SPLIT.match(n)):
                    abis.add(m.group(1).replace("_v", "-v"))
                elif (m := DPI_SPLIT.match(n)):
                    dpis.add(m.group(1))
                elif LANG_SPLIT.match(n):
                    langs += 1
    if not abis:
        for _, apk in all_apks(path):
            with zipfile.ZipFile(io.BytesIO(apk)) as zf:
                abis.update(n.split("/")[1] for n in zf.namelist() if n.startswith("lib/") and n.count("/") >= 2)
    order = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"]
    return {"abis": sorted(abis, key=lambda a: (order.index(a) if a in order else 9, a)),
            "dpis": sorted(dpis, key=["ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"].index),
            "langs": langs}


def cmd_abis(args):
    """ABIs a stored file has native libraries for, space separated; 'none' when it has no native code."""
    print(" ".join(variant(args.file)["abis"]) or "none")


def cmd_variant(args):
    """One line describing a stored file, for bin/apk-info and bin/apk-add."""
    v = variant(args.file)
    parts = ["abi: " + (" ".join(v["abis"]) or "none (no native code)")]
    if v["dpis"]:
        parts.append("dpi: " + " ".join(v["dpis"]))
    if v["langs"]:
        parts.append(f"{v['langs']} language splits")
    print(" · ".join(parts))


def cmd_pin(args):
    """Put a signing cert digest into an app's Constants.kt if none is pinned yet."""
    with open(args.constants) as f:
        src = f.read()
    pinned = re.findall(r'"([0-9a-f]{64})"', src)
    if args.digest in pinned:
        print("match")
    elif pinned:
        print("mismatch")
    elif "signatures = null," in src:
        with open(args.constants, "w") as f:
            f.write(src.replace("signatures = null,", f'signatures = setOf("{args.digest}"),', 1))
        print("pinned")
    else:
        print("unpinned")


def exodus_trackers(cache):
    if not os.path.exists(cache) or time.time() - os.path.getmtime(cache) > 30 * 86400:
        os.makedirs(os.path.dirname(cache), exist_ok=True)
        with urllib.request.urlopen(EXODUS_URL, timeout=60) as r:
            data = r.read()
        with open(cache, "wb") as f:
            f.write(data)
    with open(cache) as f:
        return list(json.load(f)["trackers"].values())


def cmd_scan(args):
    classes, hosts, dex_count = set(), {}, 0
    hermes = None
    for name, apk in all_apks(args.file):
        with zipfile.ZipFile(io.BytesIO(apk)) as zf:
            for entry in zf.namelist():
                if re.fullmatch(r"classes\d*\.dex", entry):
                    dex = zf.read(entry)
                    dex_count += 1
                    strings = dex_strings(dex)
                    for t in dex_types(dex, strings):
                        if t.startswith(b"L") and t.endswith(b";"):
                            classes.add(t[1:-1].decode("utf-8", "replace").replace("/", "."))
                    for s in strings:
                        for m in URL_RE.finditer(s):
                            host = m.group(1).decode().lower()
                            hosts[host] = hosts.get(host, 0) + 1
                elif entry == HERMES_BUNDLE:
                    data = zf.read(entry)
                    version = int.from_bytes(data[8:12], "little") if data[:8] == HERMES_MAGIC else None
                    hermes = {"apk": name, "size": len(data), "version": version, "hosts": {}}
                    for m in URL_RE.finditer(data):
                        host = m.group(1).decode().lower()
                        hermes["hosts"][host] = hermes["hosts"].get(host, 0) + 1

    packages = {c.rsplit(".", 1)[0] + "." for c in classes if "." in c}
    found = []
    for tracker in exodus_trackers(args.cache):
        signature = tracker.get("code_signature") or ""
        if not signature:
            continue
        pattern = re.compile(signature)
        matches = sorted(p for p in packages if pattern.search(p))
        if matches:
            found.append((tracker["name"], ", ".join(tracker.get("categories") or []), matches))

    print(f"{dex_count} dex files, {len(classes)} classes")
    print(f"\nTrackers ({len(found)}, Exodus Privacy signatures):")
    for name, categories, matches in sorted(found):
        shown = ", ".join(m.rstrip(".") for m in matches[:3]) + (f" (+{len(matches) - 3})" if len(matches) > 3 else "")
        print(f"  - {name} [{categories}]: {shown}")
    if not found:
        print("  none matched")

    def show_hosts(title, counts):
        print(f"\n{title} ({len(counts)}):")
        for host, n in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[: args.hosts]:
            print(f"  {n:4d}  {host}")
        if len(counts) > args.hosts:
            print(f"  ... {len(counts) - args.hosts} more (raise --hosts)")

    show_hosts("URL hosts in dex strings", hosts)
    if hermes:
        print(f"\nHermes bundle: {hermes['apk']}:{HERMES_BUNDLE}, {hermes['size'] // 1024} KiB, bytecode v{hermes['version']}")
        print("  React Native app: most logic is Hermes bytecode. Patch it with hermesPatch (morphe-patches-library).")
        show_hosts("URL hosts in the Hermes bundle", hermes["hosts"])


# endregion


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("info")
    p.add_argument("file")
    p.add_argument("--aapt2", required=True)
    p.add_argument("--tmp", required=True, help="scratch path for an extracted base.apk")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("abis")
    p.add_argument("file")
    p.set_defaults(func=cmd_abis)

    p = sub.add_parser("variant")
    p.add_argument("file")
    p.set_defaults(func=cmd_variant)

    p = sub.add_parser("extract-base")
    p.add_argument("file")
    p.add_argument("out")
    p.set_defaults(func=cmd_extract_base)

    p = sub.add_parser("pack-splits")
    p.add_argument("dir")
    p.add_argument("package")
    p.add_argument("out")
    p.set_defaults(func=cmd_pack_splits)

    p = sub.add_parser("pin")
    p.add_argument("constants")
    p.add_argument("digest")
    p.set_defaults(func=cmd_pin)

    p = sub.add_parser("scan")
    p.add_argument("file")
    p.add_argument("--cache", required=True, help="where to keep the Exodus tracker list")
    p.add_argument("--hosts", type=int, default=40, help="how many hosts to list")
    p.set_defaults(func=cmd_scan)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
