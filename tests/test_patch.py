#!/usr/bin/env python3
"""Run real bin/patch with a controlled Java boundary that extracts beside its input.

Regresses repository/input pollution, failure cleanup and overlapping staging runs.
The fixture input/bundle/JAR are inert bytes; no APK tooling or device is invoked.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]

# Model the observed Morphe APKM side effect below the real shell command.
JAVA = r'''
import hashlib, json, os, pathlib, sys, time
args = sys.argv[1:]
source = pathlib.Path(args[-1])
work = pathlib.Path(os.environ["MORPHE_WORK"])
record = {"args": args, "input_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
          "signing_dir": os.environ["MORPHE_DATA_DIR"]}
(source.parent / "base.apk").write_bytes(b"extracted base")
(source.parent / "split_config.arm64_v8a.apk").write_bytes(b"extracted split")
(work / ("observed-" + str(os.getpid()) + ".json")).write_text(json.dumps(record))
if os.environ.get("PATCH_TEST_CONCURRENT"):
    deadline = time.monotonic() + 10
    while len(list(work.glob("observed-*.json"))) < 2:
        if time.monotonic() > deadline:
            sys.exit(92)
        time.sleep(.02)
    if not source.exists():
        sys.exit(93)
if os.environ.get("PATCH_TEST_FAIL"):
    sys.exit(27)
pathlib.Path(args[args.index("-o") + 1]).write_bytes(b"patched output")
'''


class PatchInputTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "patch-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="run-", dir=scratch))
        self.root = self.fixture / "repository"
        self.work = self.fixture / "scratch"
        self.data = self.fixture / "existing-signing"
        for directory in [self.root / "bin/lib", self.root / "tools", self.root / "apks/kick/1.0",
                          self.root / "patches/build/libs", self.work, self.data, self.fixture / "boundary"]:
            directory.mkdir(parents=True, exist_ok=True)
        for name in ["bin/patch", "bin/lib/common.sh"]:
            shutil.copy2(REPO / name, self.root / name)
        self.bundle = self.root / "patches/build/libs/patches-fixture.mpp"
        self.bundle.write_bytes(b"existing bundle")
        (self.root / "tools/morphe-desktop-1.18.1-all.jar").write_bytes(b"inert jar")
        build = self.root / "bin/build"
        build.write_text('#!/bin/bash\ntouch "$MORPHE_WORK/build-called"\n'
                         'printf "%s\\n" "$PWD/patches/build/libs/patches-fixture.mpp"\n')
        build.chmod(0o755)
        java = self.fixture / "boundary/java"
        java.write_text(f"#!{sys.executable}\n" + JAVA)
        java.chmod(0o755)
        (self.data / "signing.keystore").write_bytes(b"keep existing signing key")
        self.env = dict(os.environ, MORPHE_WORK=str(self.work), MORPHE_DATA_DIR=str(self.data),
                        PATH=str(java.parent) + os.pathsep + os.environ["PATH"])

    def tearDown(self):
        if sys.platform == "darwin":
            print(f"Retained on macOS: {self.fixture}")
        else:
            shutil.rmtree(self.fixture)

    def input(self, suffix=".apkm"):
        self.original = self.root / ("apks/kick/1.0/original upload" + suffix)
        self.original.write_bytes(b"unchanged original input")
        self.before = {str(p.relative_to(self.root)): p.read_bytes()
                       for p in self.root.rglob("*") if p.is_file()}

    def command(self, *args, **env):
        return subprocess.run(["bash", str(self.root / "bin/patch"), "kick", *args],
                              cwd=self.root, env=dict(self.env, **env), text=True,
                              capture_output=True, timeout=20)

    def check_result(self, result, *, failed=False, forwarded=()):
        self.assertEqual(result.returncode, 27 if failed else 0, result.stderr)
        records = [json.loads(p.read_text()) for p in self.work.glob("observed-*.json")]
        self.assertTrue(records, result.stderr)
        for record in records:
            args = record["args"]
            staged = Path(args[-1])
            self.assertTrue(staged.is_relative_to(self.work / "tmp"),
                            f"CLI input must be staged in scratch, got {staged}")
            self.assertEqual(staged.name, self.original.name)
            self.assertEqual(record["input_sha256"], hashlib.sha256(b"unchanged original input").hexdigest())
            self.assertEqual(record["signing_dir"], str(self.data))
            self.assertEqual(args[:3], ["-jar", str(self.root / "tools/morphe-desktop-1.18.1-all.jar"),
                                       "patch"])
            self.assertEqual(args[args.index("-p") + 1], str(self.bundle))
            self.assertEqual(args[args.index("-o") + 1], str(self.work / "out/kick-1.0-patched.apk"))
            for value in forwarded:
                self.assertIn(value, args)
            if "Patch with spaces" in forwarded:
                self.assertEqual(args[args.index("-e") + 1], "Patch with spaces")
            temporary = Path(args[args.index("-t") + 1])
            self.assertTrue(temporary.is_relative_to(self.work / "tmp"))
            self.assertFalse(staged.exists(), "staging must be removed after success or failure")
        after = {str(p.relative_to(self.root)): p.read_bytes()
                 for p in self.root.rglob("*") if p.is_file()}
        self.assertEqual(after, self.before, "original input and repository must remain untouched")
        self.assertEqual(list((self.work / "tmp").iterdir()), [], "all extraction/temp artifacts must be cleaned")
        self.assertEqual((self.data / "signing.keystore").read_bytes(), b"keep existing signing key")
        output = self.work / "out/kick-1.0-patched.apk"
        if failed:
            self.assertFalse(output.exists())
            self.assertEqual(result.stdout, "")
        else:
            self.assertEqual(output.read_bytes(), b"patched output")
            self.assertEqual(result.stdout.strip(), str(output))
        return records

    def test_apkm_build_install_and_options_stage_unchanged_input(self):
        self.input()
        result = self.command("1.0", "--install=fixture-serial", "--", "-e", "Patch with spaces", "-f")
        self.check_result(result, forwarded=("--install=fixture-serial", "-e", "Patch with spaces", "-f"))
        self.assertTrue((self.work / "build-called").exists())

    def test_failed_cli_preserves_input_and_cleans_extraction(self):
        self.input()
        self.check_result(self.command("--no-build", "--install", PATCH_TEST_FAIL="1"),
                          failed=True, forwarded=("--install",))
        self.assertFalse((self.work / "build-called").exists())

    def test_plain_apk_uses_same_isolation(self):
        self.input(".apk")
        self.check_result(self.command("--no-build"))

    def test_overlapping_runs_have_independent_staging(self):
        self.input()
        commands = [subprocess.Popen(["bash", str(self.root / "bin/patch"), "kick", "--no-build"],
                                    cwd=self.root, env=dict(self.env, PATCH_TEST_CONCURRENT="1"),
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE) for _ in range(2)]
        try:
            for command in commands:
                stdout, stderr = command.communicate(timeout=20)
                self.assertEqual(command.returncode, 0, stderr)
                self.assertEqual(stdout.strip(), str(self.work / "out/kick-1.0-patched.apk"))
        finally:
            for command in commands:
                if command.poll() is None:
                    command.kill()
                    command.wait()
        result = subprocess.CompletedProcess([], 0, str(self.work / "out/kick-1.0-patched.apk"), "")
        records = self.check_result(result)
        self.assertEqual(len(records), 2)
        self.assertNotEqual(records[0]["args"][-1], records[1]["args"][-1])


if __name__ == "__main__":
    unittest.main()
