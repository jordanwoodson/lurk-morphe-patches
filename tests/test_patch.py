#!/usr/bin/env python3
"""Run real bin/patch with a controlled Java boundary that extracts beside its input.

Regresses repository/input pollution, failure cleanup and overlapping staging runs.
The fixture input/bundle/JAR are inert bytes; no APK tooling or device is invoked.
Also covers bin/bump's sorting of patch failures and the version-literal scanner.
"""
from __future__ import annotations

import hashlib
import importlib.util
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


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, REPO / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BumpSummaryTests(unittest.TestCase):
    """bin/bump's sorting of Morphe's failedPatches, with reasons shaped like Morphe Desktop 1.18's."""

    @classmethod
    def setUpClass(cls):
        sys.dont_write_bytecode = True
        cls.bump = load("lurk_bump", "bin/lib/bump.py")

    @staticmethod
    def failed(name, *lines):
        frames = "\n\tat app.morphe.patcher.Patcher$invoke$1.invokeSuspend(Patcher.kt:120)"
        return {"patch": {"name": name}, "reason": "\n".join(lines) + frames}

    def test_failures_are_sorted_by_cause_and_dependents_inherit_the_root_cause(self):
        exception = "app.morphe.patcher.patch.PatchException: "
        result = {"appliedPatches": [{"name": "Sleep timer"}], "patchingSteps": [{"step": "PATCHING", "success": True}],
                  "failedPatches": [
                      self.failed("Anonymous chat",
                                  exception + 'The patch "Anonymous chat" depends on "RawResourcePatch@1", which raised an exception:',
                                  exception + "Twitch Hermes: unrecognized Twitch bundle",
                                  "\tat io.github.jordanwoodson.patches.twitch.shared.HermesKt.fail(Hermes.kt:368)"),
                      self.failed("Chat timestamps",
                                  exception + 'The patch "Chat timestamps" depends on "BytecodePatch@2", which raised an exception:',
                                  exception + 'The patch "BytecodePatch@2" depends on "RawResourcePatch@1", which raised an exception:',
                                  exception + "The patch 'RawResourcePatch@1' failed previously"),
                      self.failed("Hide Clips", exception + "Twitch Hermes: expected one pattern in DiscoveryFeedContent"),
                      self.failed("Mute stitched ads", exception + "Could not find class: Lcom/twitchrn/player/PlaybackSession;"),
                      self.failed("Block client ads", exception + "Failed to match the fingerprint: AdFingerprint"),
                      self.failed("Normal latency", exception + "Twitch: no free register after the chat clear"),
                      self.failed("Clone app", "java.lang.IllegalStateException: disk full"),
                  ]}
        lines, broken = self.bump.summary("twitch", "31.8.0", result)
        text = "\n".join(lines)
        self.assertTrue(broken)
        self.assertIn("twitch 31.8.0: 1 of 8 patches applied, 7 failed", text)
        sections = {line.split(" (")[0]: line for line in lines if line and not line.startswith(" ")}
        self.assertIn("SHA gate: the Hermes bundle is not the pinned one (2)", text)
        self.assertIn("  - Twitch Hermes: unrecognized Twitch bundle (in a shared patch)\n    Anonymous chat, Chat timestamps", text)
        self.assertIn("Hermes pattern (1)", text)
        self.assertIn("Fingerprint, class or bytecode match (3)", text)
        self.assertIn("  - Could not find class: Lcom/twitchrn/player/PlaybackSession;\n    Mute stitched ads", text)
        self.assertIn("Other (1)", text)
        self.assertIn("  - disk full\n    Clone app", text)
        self.assertEqual(list(sections)[1:], ["SHA gate: the Hermes bundle is not the pinned one", "Hermes pattern",
                                             "Fingerprint, class or bytecode match", "Other"])

    def test_probe_class_names_gone_from_the_new_version_are_reported(self):
        probes = {"t1.o": {"AdsProbe.java"}, "tv.twitch.android.models.Tuid": {"AdsProbe.java"},
                  "index.android.bundle": {"BundleProbe.java"}}
        current = {"t1.o", "tv.twitch.android.models.Tuid"}
        lines, broken = self.bump.summary("twitch", "31.8.0", {"appliedPatches": [{"name": "A"}]},
                                          {"tv.twitch.android.models.Tuid"}, current, probes)
        self.assertTrue(broken)
        self.assertIn("  - AdsProbe.java: t1.o", lines)
        self.assertNotIn("index.android.bundle", "\n".join(lines))

    def test_a_clean_run_says_so(self):
        lines, broken = self.bump.summary("rumble", "3.22.0", {"appliedPatches": [{"name": "A"}], "failedPatches": []},
                                          {"a.b"}, {"a.b"}, {"a.b": {"P.java"}})
        self.assertFalse(broken)
        self.assertEqual(lines[-1], "Everything applied and no probe names are gone.")

    def test_a_missing_result_file_is_a_failure(self):
        lines, broken = self.bump.summary("kick", "41.0.0", None)
        self.assertTrue(broken)
        self.assertIn("no result file", lines[0])


class VersionLiteralTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.check = load("lurk_version_literals", "tests/verify_version_literals.py")

    def literals(self, source):
        return self.check.Literals(source).found

    def test_strings_are_found_and_comments_and_templates_are_not_strings(self):
        source = ('// "1.2.3" in a comment\n'
                  '/* nested /* "4.5.6" */ still a comment */\n'
                  'val a = "Twitch 31.7.0: ${f("inner")} done"\n'
                  "val c = '\"'\n"
                  'val raw = """raw "x" 7.8.9"""\n'
                  'val url = "https://example.com" // "9.9.9"\n')
        self.assertEqual(self.literals(source), [(3, "inner"), (3, "Twitch 31.7.0:  done"), (5, 'raw "x" 7.8.9'),
                                                 (6, "https://example.com")])

    def test_only_string_literals_with_versions_outside_constants_fail(self):
        scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "patch-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        root = Path(tempfile.mkdtemp(prefix="literals-", dir=scratch))
        self.addCleanup(shutil.rmtree, root)
        app = root / "patches/src/main/kotlin/app"
        (app / "shared").mkdir(parents=True)
        (app / "shared/Constants.kt").write_text('val v = "31.7.0"\n')
        (app / "Patch.kt").write_text('// for 31.7.0\nthrow PatchException("Twitch 31.7.0: no match")\n'
                                      'val ip = "10.0.0.1"\nval tool = "hermes-dec 0.1.7 is ${version}"\n')
        self.assertEqual(self.check.offenders(root), [
            ("patches/src/main/kotlin/app/Patch.kt", 2, "Twitch 31.7.0: no match"),
            ("patches/src/main/kotlin/app/Patch.kt", 4, "hermes-dec 0.1.7 is "),
        ])


if __name__ == "__main__":
    unittest.main()
