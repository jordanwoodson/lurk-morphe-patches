#!/usr/bin/env python3
"""Offline release recovery tests; local git fixtures live below $MORPHE_WORK."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[1]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("lurk_release", REPO / ".github/scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseRecoveryTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "release-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="git-", dir=scratch))
        self.root = self.fixture / "work"
        self.root.mkdir()
        self.started = int(time.time())
        self.remote = self.fixture / "origin.git"
        self.command("git", "init", "--bare", "--quiet", str(self.remote))
        self.git("init", "--quiet", "-b", "main")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release@example.invalid")
        self.git("remote", "add", "origin", str(self.remote))
        for name, text in {"gradle.properties": "version = 1.0.0\n", "CHANGELOG.md": "## 1.0.0\n",
                           "patches-list.json": '{"version":"1.0.0"}\n', "patches-bundle.json": '{}\n',
                           "README.md": "Release fixture\n", "feature.txt": "base\n"}.items():
            (self.root / name).write_text(text)
        (self.root / "bin").mkdir()
        (self.root / "bin/test-host").write_text("#!/bin/bash\nexit 0\n")
        self.commit("chore: initial fixture")
        self.git("branch", "dev")
        self.git("push", "--quiet", "origin", "main", "dev")
        self.assets = self.fixture / "assets"
        self.assets.mkdir()
        self.output = self.fixture / "output"
        self.published = set()
        self.builds = []
        self.publications = []
        self.events = []
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(release, "ROOT", self.root))
        self.stack.enter_context(patch.object(release, "LIBS", self.assets))
        self.stack.enter_context(patch.object(release, "repository", return_value="fixture/repo"))
        self.stack.enter_context(patch.object(release, "release_exists", side_effect=lambda repo, tag: tag in self.published))
        self.stack.enter_context(patch.object(release, "build", side_effect=self.build))
        self.stack.enter_context(patch.object(release, "publish", side_effect=self.publish))
        self.stack.enter_context(patch.object(release, "run", side_effect=self.run_command))
        self.stack.enter_context(patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.output)}))
        # Any unmocked gh/HTTP operation is a test failure, never a real API request.
        actual_run = subprocess.run
        def offline(command, *args, **kwargs):
            if command[0] != "git":
                raise AssertionError(f"Unexpected external command: {command}")
            return actual_run(command, *args, **kwargs)
        self.stack.enter_context(patch.object(release.subprocess, "run", side_effect=offline))

    def tearDown(self):
        self.stack.close()
        if sys.platform == "darwin":
            print(f"Retained on macOS: {self.fixture}")
        else:
            shutil.rmtree(self.fixture)

    def command(self, *args):
        result = subprocess.run(args, cwd=self.root if hasattr(self, "root") else None,
                                text=True, capture_output=True, check=True)
        return result.stdout.strip()

    def git(self, *args):
        return self.command("git", *args)

    def commit(self, subject, body=None, *, minutes=None, empty=False):
        """Commit everything. `minutes` dates the commit that long after the fixture started, which
        fixes the log order of commits made on different branches."""
        self.git("add", ".")
        env = dict(os.environ)
        if minutes is not None:
            env["GIT_AUTHOR_DATE"] = env["GIT_COMMITTER_DATE"] = f"@{self.started + 60 * minutes} +0000"
        subprocess.run(["git", "commit", "--quiet", *(["--allow-empty"] if empty else []), "-m", subject,
                        *(["-m", body] if body else [])], cwd=self.root, check=True, capture_output=True, env=env)
        return self.git("rev-parse", "HEAD")

    def merge(self, branch, minutes):
        stamp = f"@{self.started + 60 * minutes} +0000"
        subprocess.run(["git", "merge", "--quiet", "--no-ff", "-m", f"Merge {branch}", branch], cwd=self.root,
                       check=True, capture_output=True,
                       env={**os.environ, "GIT_AUTHOR_DATE": stamp, "GIT_COMMITTER_DATE": stamp})

    def pre_release(self, version, minutes):
        """A dev release commit and its tag, pushed but not published (its GitHub release failed)."""
        sha = self.commit(f"chore: Release v{version} [skip ci]", f"## {version}\n\nDev notes",
                          minutes=minutes, empty=True)
        self.git("tag", f"v{version}")
        self.git("push", "--quiet", "origin", "dev", "--tags")
        return sha

    def stable(self, published=False, minutes=None):
        (self.root / "feature.txt").write_text("stable feature\n")
        self.commit("fix: fixture behavior", minutes=minutes)
        (self.root / "gradle.properties").write_text("version = 1.0.1\n")
        (self.root / "CHANGELOG.md").write_text("## 1.0.1\n\nStable notes\n\n## 1.0.0\n")
        (self.root / "patches-bundle.json").write_text('{"version":"1.0.1","description":"stable"}\n')
        (self.root / "patches-list.json").write_text('{"version":"1.0.1","patches":[]}\n')
        (self.root / "README.md").write_text("Stable patch list\n")
        sha = self.commit("chore: Release v1.0.1 [skip ci]", "## 1.0.1\n\nStable notes", minutes=minutes)
        self.git("tag", "v1.0.1")
        self.git("push", "--quiet", "origin", "main", "--tags")
        if published:
            self.published.add("v1.0.1")
        return sha

    def build(self, version, *tasks):
        self.builds.append((str(version), self.git("rev-parse", "HEAD")))
        self.events.append(("build", self.git("rev-parse", "HEAD")))
        path = self.assets / f"patches-{version}.mpp"
        path.write_bytes(b"fixture bundle")
        return path

    def publish(self, repo, version, text, asset):
        self.assertEqual(repo, "fixture/repo")
        self.assertTrue(asset.is_file())
        self.assertTrue(text.startswith("## "))
        self.published.add(version.tag)
        self.publications.append(version.tag)
        self.events.append(("publish", version.tag))

    def run_command(self, *command):
        if command == ("bash", "bin/test-host"):
            self.events.append(("verify", self.git("rev-parse", "HEAD")))

    def invoke(self, branch="main", dry_run=False):
        args = ["release.py", "--branch", branch] + (["--dry-run"] if dry_run else [])
        with patch.object(sys, "argv", args), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def assert_merged(self, sha):
        self.git("fetch", "--quiet", "origin", "dev")
        result = subprocess.run(["git", "merge-base", "--is-ancestor", sha, "origin/dev"],
                                cwd=self.root, capture_output=True)
        self.assertEqual(result.returncode, 0, "Stable release must be reachable from dev")

    def output_values(self):
        if not self.output.exists():
            return {}
        return dict(re.findall(r"(\w+)<<EOF\n(.*?)\nEOF\n", self.output.read_text(), re.S))

    def test_missing_publication_is_rebuilt_at_release_commit_and_backmerged(self):
        sha = self.stable()
        self.invoke()
        self.assertEqual(self.publications, ["v1.0.1"])
        self.assertEqual(self.builds, [("1.0.1", sha)])
        self.assert_merged(sha)
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.assertEqual(self.output_values()["published"], "true")

    def test_published_stable_is_finalized_when_no_new_commits_exist(self):
        sha = self.stable(published=True)
        self.invoke()
        self.assert_merged(sha)
        self.assertEqual(self.publications, [])
        self.assertEqual(self.builds, [])
        self.assertEqual(self.output_values()["published"], "false")

    def test_advanced_dev_keeps_its_work_and_generated_files(self):
        sha = self.stable(published=True)
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("new development\n")
        (self.root / "gradle.properties").write_text("version = 1.1.0-dev.1\n")
        (self.root / "CHANGELOG.md").write_text("## 1.1.0-dev.1\n\nDevelopment notes\n")
        self.commit("feat: next development")
        self.git("push", "--quiet", "origin", "dev")
        self.git("checkout", "--quiet", "main")
        self.invoke()
        self.assert_merged(sha)
        self.assertEqual(self.git("show", "origin/dev:dev.txt"), "new development")
        self.assertEqual(self.git("show", "origin/dev:gradle.properties"), "version = 1.1.0-dev.1")
        self.assertEqual(self.git("show", "origin/dev:CHANGELOG.md").count("## 1.0.1"), 1)
        first = self.git("rev-parse", "origin/dev")
        self.invoke()
        self.assertEqual(self.git("rev-parse", "origin/dev"), first, "Retry must not create another merge")
        self.assertEqual(self.git("branch", "--show-current"), "main")

    def test_source_only_dev_advancement_preserves_all_dev_generated_files(self):
        original = {path: self.git("show", f"dev:{path}") for path in release.GENERATED}
        sha = self.stable(published=True)
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("source-only advancement\n")
        self.commit("feat: next development source")
        self.git("push", "--quiet", "origin", "dev")
        self.git("checkout", "--quiet", "main")
        self.invoke()
        self.assert_merged(sha)
        for path, expected in original.items():
            if path != "CHANGELOG.md":
                self.assertEqual(self.git("show", f"origin/dev:{path}"), expected, path)
        self.assertEqual(self.git("show", "origin/dev:CHANGELOG.md"),
                         "## 1.0.1\n\nStable notes\n\n## 1.0.0")
        before = self.git("rev-parse", "origin/dev")
        self.invoke()
        self.assertEqual(self.git("rev-parse", "origin/dev"), before)

    def test_new_release_verifies_remote_tip_after_workflow_checkout_lags(self):
        original = self.git("rev-parse", "HEAD")
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        (self.root / "feature.txt").write_text("new remote source\n")
        selected = self.commit("fix: advance while workflow waits")
        self.git("push", "--quiet", "origin", "main", "--tags")
        self.git("checkout", "--quiet", "--detach", original)
        self.invoke()
        self.assertEqual(self.events, [("verify", selected), ("build", selected), ("publish", "v1.0.1")])

    def test_recovery_verifies_older_release_source_before_build_and_publication(self):
        sha = self.stable()
        (self.root / "later.txt").write_text("later source\n")
        self.commit("chore: newer branch source")
        self.git("push", "--quiet", "origin", "main")
        self.invoke()
        self.assertEqual(self.events, [("verify", sha), ("build", sha), ("publish", "v1.0.1")])

    def test_failed_recovery_gate_blocks_recovery_and_newer_publication(self):
        sha = self.stable()
        (self.root / "later.txt").write_text("newer fix\n")
        self.commit("fix: newer source")
        self.git("push", "--quiet", "origin", "main")
        def fail_gate(*command):
            self.run_command(*command)
            if command == ("bash", "bin/test-host"):
                raise SystemExit("fixture host check failed")
        with patch.object(release, "run", side_effect=fail_gate):
            with self.assertRaises(SystemExit):
                self.invoke()
        self.assertEqual(self.events, [("verify", sha)])
        self.assertEqual(self.builds, [])
        self.assertEqual(self.publications, [])
        self.assertEqual(self.output_values()["published"], "false")

    def test_missing_source_gate_blocks_publication(self):
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        self.git("rm", "--quiet", "bin/test-host")
        self.commit("fix: source without a host gate")
        self.git("push", "--quiet", "origin", "main", "--tags")
        with self.assertRaisesRegex(SystemExit, "bin/test-host"):
            self.invoke()
        self.assertEqual(self.builds, [])
        self.assertEqual(self.publications, [])

    def test_publication_outputs_survive_backmerge_failure_and_retry(self):
        sha = self.stable()
        with patch.object(release, "backmerge", side_effect=SystemExit("fixture backmerge failure")):
            with self.assertRaises(SystemExit):
                self.invoke()
        values = self.output_values()
        self.assertEqual(values["published"], "true")
        self.assertEqual(values["built"], "false", "Building a recovery does not verify current branch")
        self.assertEqual(Path(values["assets"]).read_bytes(), b"fixture bundle")
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.invoke()
        self.assert_merged(sha)
        self.assertEqual(self.publications, ["v1.0.1"], "Retry must not publish twice")

    def test_new_publication_uses_same_recoverable_finalization(self):
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        (self.root / "feature.txt").write_text("new fix\n")
        self.commit("fix: new release")
        self.git("push", "--quiet", "origin", "main", "--tags")
        with patch.object(release, "backmerge", side_effect=SystemExit("fixture backmerge failure")):
            with self.assertRaises(SystemExit):
                self.invoke()
        values = self.output_values()
        self.assertEqual(values["published"], "true")
        self.assertEqual(values["built"], "true")
        self.assertTrue(Path(values["assets"]).is_file())
        sha = self.git("rev-parse", "v1.0.1")
        self.invoke()
        self.assert_merged(sha)
        self.assertEqual(self.publications, ["v1.0.1"])

    def test_new_publication_rejects_unrelated_staging_without_publishing_it(self):
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        (self.root / "feature.txt").write_text("fix to publish\n")
        self.commit("fix: new release")
        self.git("push", "--quiet", "origin", "main", "--tags")
        private = self.root / "private-local.txt"
        private.write_bytes(b"private staged bytes\x00\n")
        self.git("add", "private-local.txt")
        private.write_bytes(b"private unstaged bytes\x00\n")
        before_head = self.git("rev-parse", "HEAD")
        before_status = self.git("status", "--porcelain")
        before_index = self.git("write-tree")
        before_refs = self.git("ls-remote", "origin")

        rejected = False
        try:
            self.invoke()
        except SystemExit as error:
            rejected = True
            self.assertIn("local changes", str(error))

        # Check the actual bare remote, including every branch and tag, before the
        # rejection assertions so RED exposes publication rather than a missing error.
        for ref in self.git("--git-dir", str(self.remote), "for-each-ref", "--format=%(refname)").splitlines():
            self.assertNotIn("private-local.txt", self.git("--git-dir", str(self.remote), "ls-tree", "-r", "--name-only", ref))
        self.assertTrue(rejected, "Staged work must be rejected")
        self.assertEqual(self.git("ls-remote", "origin"), before_refs)
        self.assertEqual(self.git("rev-parse", "HEAD"), before_head)
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.assertEqual(self.git("status", "--porcelain"), before_status)
        self.assertEqual(self.git("write-tree"), before_index, "Keep the exact staged bytes")
        self.assertEqual(private.read_bytes(), b"private unstaged bytes\x00\n")
        self.assertEqual(self.events, [], "Reject before verification, build or publication")
        self.assertEqual(self.output_values(), {"published": "false", "built": "false", "assets": ""})

    def test_staged_work_blocks_recovery_before_checkout_or_backmerge(self):
        sha = self.stable()
        self.git("checkout", "--quiet", "--detach", "dev")
        (self.root / "private-local.txt").write_text("keep staged recovery work\n")
        self.git("add", "private-local.txt")
        before_head = self.git("rev-parse", "HEAD")
        before_index = self.git("write-tree")
        before_status = self.git("status", "--porcelain")
        before_refs = self.git("ls-remote", "origin")
        for published in (False, True):
            with self.subTest(published=published):
                if published:
                    self.published.add("v1.0.1")
                with self.assertRaisesRegex(SystemExit, "local changes"):
                    self.invoke()
                self.assertEqual(self.git("branch", "--show-current"), "")
                self.assertEqual(self.git("rev-parse", "HEAD"), before_head)
                self.assertEqual(self.git("write-tree"), before_index)
                self.assertEqual(self.git("status", "--porcelain"), before_status)
                self.assertEqual(self.git("ls-remote", "origin"), before_refs)
                self.assertEqual(self.events, [])
                self.assertEqual(self.git("rev-parse", "origin/main"), sha)

    def test_recovered_asset_survives_failure_of_a_newer_release(self):
        sha = self.stable()
        (self.root / "feature.txt").write_text("another fix\n")
        self.commit("fix: next release")
        self.git("push", "--quiet", "origin", "main")
        def build(version, *tasks):
            if str(version) == "1.0.2":
                raise SystemExit("fixture later build failure")
            return self.build(version, *tasks)
        with patch.object(release, "build", side_effect=build):
            with self.assertRaises(SystemExit):
                self.invoke()
        self.assert_merged(sha)
        self.assertEqual(self.publications, ["v1.0.1"])
        self.assertEqual(self.builds, [("1.0.1", sha)])
        values = self.output_values()
        self.assertEqual(values["published"], "true")
        self.assertTrue(values["assets"].endswith("patches-1.0.1.mpp"))

    def test_stable_recovery_skips_newer_pre_release_commits_merged_from_dev(self):
        # v1.0.1's GitHub release failed; dev, with a newer pre-release commit, was merged before the rerun.
        sha = self.stable(minutes=1)
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("next feature\n")
        self.commit("feat: next development", minutes=2)
        self.pre_release("1.1.0-dev.1", minutes=3)
        self.published.add("v1.1.0-dev.1")
        self.git("checkout", "--quiet", "main")
        self.merge("dev", minutes=4)
        self.git("push", "--quiet", "origin", "main")
        self.invoke()
        self.assertEqual(self.publications[0], "v1.0.1", "The unpublished stable release must be recovered")
        self.assertEqual(self.builds[0], ("1.0.1", sha))
        self.assert_merged(sha)

    def test_pre_release_recovery_skips_newer_stable_release_commits(self):
        # v1.1.0-dev.1's GitHub release failed; an older stable release was merged into dev after it.
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("next feature\n")
        self.commit("feat: next development", minutes=1)
        sha = self.pre_release("1.1.0-dev.1", minutes=2)
        self.git("checkout", "--quiet", "main")
        self.stable(published=True, minutes=3)
        self.git("checkout", "--quiet", "dev")
        self.merge("main", minutes=4)
        self.git("push", "--quiet", "origin", "dev")
        self.invoke(branch="dev")
        self.assertEqual(self.publications[0], "v1.1.0-dev.1", "The unpublished pre-release must be recovered")
        self.assertEqual(self.builds[0], ("1.1.0-dev.1", sha))

    def test_pre_release_contained_in_a_newer_stable_release_is_not_recovered(self):
        # v1.0.1-dev.1's GitHub release failed, but the stable v1.0.1 made from it is out: publishing
        # the pre-release now would put an outdated build above the stable one.
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("development fix\n")
        self.commit("fix: development fix", minutes=1)
        self.pre_release("1.0.1-dev.1", minutes=2)
        self.git("checkout", "--quiet", "main")
        self.merge("dev", minutes=3)
        self.commit("chore: Release v1.0.1 [skip ci]", "## 1.0.1\n\nStable notes", minutes=4, empty=True)
        self.git("tag", "v1.0.1")
        self.git("push", "--quiet", "origin", "main", "--tags")
        self.published.add("v1.0.1")
        self.git("checkout", "--quiet", "dev")
        self.git("merge", "--quiet", "--ff-only", "main")
        self.git("push", "--quiet", "origin", "dev")
        self.invoke(branch="dev")
        self.assertEqual(self.publications, [])
        self.assertEqual(self.builds, [])

    def test_pre_release_push_rejected_by_a_stable_backmerge_is_dropped_without_failing(self):
        # While dev's pre-release run builds, the stable release made from the same commits lands on
        # main and is merged back into dev. The atomic push is rejected; the run must drop its release
        # commit and tag and finish without publishing, since the stable release supersedes it.
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("development fix\n")
        fix = self.commit("fix: development fix", minutes=1)
        self.git("push", "--quiet", "origin", "dev")
        other = self.fixture / "other"
        self.command("git", "clone", "--quiet", str(self.remote), str(other))

        def build_then_move_remote(version, *tasks):
            path = self.build(version, *tasks)
            run = lambda *args: subprocess.run(["git", *args], cwd=other, check=True, capture_output=True,
                                               env={**os.environ, "GIT_AUTHOR_DATE": f"@{self.started + 300} +0000",
                                                    "GIT_COMMITTER_DATE": f"@{self.started + 300} +0000",
                                                    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.invalid",
                                                    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.invalid"})
            run("checkout", "--quiet", "main")
            run("merge", "--quiet", "--no-ff", "-m", "Merge dev", "origin/dev")
            run("commit", "--quiet", "--allow-empty", "-m", "chore: Release v1.0.1 [skip ci]", "-m", "## 1.0.1\n\nStable")
            run("tag", "v1.0.1")
            run("push", "--quiet", "origin", "main", "--tags")
            run("checkout", "--quiet", "-b", "dev", "origin/dev")
            run("merge", "--quiet", "--no-ff", "-m", "chore: merge main into dev [skip ci]", "main")
            run("push", "--quiet", "origin", "dev")
            self.published.add("v1.0.1")
            return path

        with patch.object(release, "build", side_effect=build_then_move_remote):
            self.invoke(branch="dev")
        self.assertEqual(self.publications, [])
        self.assertEqual(len(self.builds), 1)
        self.assertNotIn("v1.0.1-dev.1", self.git("tag").split())
        self.assertEqual(self.git("rev-parse", "HEAD"), fix)
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_backmerge_fetch_failure_is_reported_for_retry(self):
        sha = self.stable()
        original = release.git_ok
        def fail_fetch(*args):
            if args[:3] == ("fetch", "--quiet", "origin"):
                return False
            return original(*args)
        with patch.object(release, "git_ok", side_effect=fail_fetch):
            with self.assertRaises(SystemExit):
                release.backmerge(sha, "## 1.0.1")
        self.assertEqual(self.git("branch", "--show-current"), "main")

    def test_source_conflict_aborts_merge_and_restores_checkout(self):
        sha = self.stable()
        self.git("checkout", "--quiet", "dev")
        (self.root / "feature.txt").write_text("conflicting development\n")
        self.commit("feat: conflicting development")
        self.git("push", "--quiet", "origin", "dev")
        self.git("checkout", "--quiet", "main")
        with self.assertRaises(SystemExit):
            release.backmerge(sha, "## 1.0.1")
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.assertEqual(self.git("status", "--porcelain"), "")

    def test_backmerge_restores_detached_checkout_after_push_failure(self):
        sha = self.stable()
        self.git("checkout", "--quiet", "dev")
        (self.root / "dev.txt").write_text("advanced\n")
        self.commit("feat: development")
        self.git("push", "--quiet", "origin", "dev")
        self.git("checkout", "--quiet", "--detach", sha)
        original = release.git
        def fail_push(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise SystemExit("fixture push failure")
            return original(*args, **kwargs)
        with patch.object(release, "git", side_effect=fail_push):
            with self.assertRaises(SystemExit):
                release.backmerge(sha, "## 1.0.1")
        self.assertEqual(self.git("branch", "--show-current"), "")
        self.assertEqual(self.git("rev-parse", "HEAD"), sha)

    def test_main_restores_original_checkout_after_build_failure(self):
        self.stable()
        self.git("checkout", "--quiet", "dev")
        with patch.object(release, "build", side_effect=SystemExit("fixture build failure")):
            with self.assertRaises(SystemExit):
                self.invoke()
        self.assertEqual(self.git("branch", "--show-current"), "dev")

    def test_new_build_failure_restores_generated_files_and_preserves_unstaged_work(self):
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        (self.root / "release-fix.txt").write_text("fix to publish\n")
        self.commit("fix: new release")
        self.git("push", "--quiet", "origin", "main", "--tags")
        self.git("checkout", "--quiet", "dev")
        (self.root / "gradle.properties").write_text("version = 1.1.0-dev.1\n")
        self.commit("chore: development version")
        (self.root / "feature.txt").write_text("keep unstaged changes\n")
        (self.root / "README-local.txt").write_text("keep untracked changes\n")
        before = self.git("status", "--porcelain")
        with patch.object(release, "build", side_effect=SystemExit("fixture new build failure")):
            with self.assertRaisesRegex(SystemExit, "fixture new build failure"):
                self.invoke()
        self.assertEqual(self.git("branch", "--show-current"), "dev")
        self.assertEqual((self.root / "gradle.properties").read_text(), "version = 1.1.0-dev.1\n")
        self.assertEqual(self.git("status", "--porcelain"), before)
        self.assertEqual((self.root / "feature.txt").read_text(), "keep unstaged changes\n")
        self.assertEqual((self.root / "README-local.txt").read_text(), "keep untracked changes\n")

    def test_dirty_generated_files_are_rejected_before_release_mutation(self):
        self.git("tag", "v1.0.0")
        self.published.add("v1.0.0")
        (self.root / "feature.txt").write_text("fix to publish\n")
        self.commit("fix: new release")
        self.git("push", "--quiet", "origin", "main", "--tags")
        for staged in (False, True):
            with self.subTest(staged=staged):
                (self.root / "gradle.properties").write_text("version = local-work\n")
                if staged:
                    self.git("add", "gradle.properties")
                before = self.git("status", "--porcelain")
                with self.assertRaisesRegex(SystemExit, "local changes"):
                    self.invoke()
                self.assertEqual((self.root / "gradle.properties").read_text(), "version = local-work\n")
                self.assertEqual(self.git("status", "--porcelain"), before)
                self.assertEqual(self.builds, [])
                self.assertEqual(self.publications, [])

    def test_dry_run_does_not_publish_merge_or_checkout(self):
        self.stable()
        self.git("checkout", "--quiet", "dev")
        (self.root / "private-local.txt").write_text("keep staged dry-run work\n")
        self.git("add", "private-local.txt")
        before_index = self.git("write-tree")
        before = self.git("rev-parse", "origin/dev")
        self.invoke(dry_run=True)
        self.assertEqual(self.git("branch", "--show-current"), "dev")
        self.assertEqual(self.git("rev-parse", "origin/dev"), before)
        self.assertEqual(self.git("write-tree"), before_index)
        self.assertEqual(self.builds, [])
        self.assertEqual(self.publications, [])


class ReleaseNotesTests(unittest.TestCase):
    CATALOG = {
        "twitch": release.App("Twitch", ("Sleep timer", "Third-party emotes", "Hide chat clutter")),
        "kick": release.App("Kick", ("Sleep timer", "Hide rating prompt")),
    }

    @staticmethod
    def commit(header, sha, body=""):
        kind, scope, bang, text = release.HEADER.fullmatch(header).groups()
        footer = release.BREAKING.search(body)
        breaking = footer.group(1).strip() if footer else (text if bang else "")
        return release.Commit(sha * 40, kind, scope or "", text, breaking)

    def notes(self, *commits):
        return release.notes(release.Version(1, 2, 0), release.Version(1, 1, 0), list(commits), "2026-10-22",
                             "owner/repo", self.CATALOG)

    def test_entries_are_grouped_by_app_then_section_with_shared_last(self):
        text = self.notes(self.commit("fix(twitch): keep the sleep timer running in picture-in-picture", "a"),
                          self.commit("feat(kick): hide rating prompt", "b"),
                          self.commit("bump(twitch): support Twitch 31.8.0", "c"),
                          self.commit("fix: serialize sleep dispatch", "d"),
                          self.commit("fix(tooling): isolate patch inputs", "e"),
                          self.commit("feat(twitch): Third-party emotes: show emotes in the picker", "f"),
                          self.commit("chore(twitch): document the hooks", "0"))
        headings = [line for line in text.splitlines() if line.startswith("#")]
        self.assertEqual(headings, [
            "## [1.2.0](https://github.com/owner/repo/compare/v1.1.0...v1.2.0) (2026-10-22)",
            "### Kick", "#### ✨ New Features",
            "### Twitch", "#### ✨ New Features", "#### 🚀 Updated App Support", "#### 🐛 Bug Fixes",
            "### Shared", "#### 🐛 Bug Fixes",
        ])
        self.assertIn("* **Hide rating prompt** ([bbbbbbb](https://github.com/owner/repo/commit/" + "b" * 40 + "))", text)
        self.assertIn("* **Third-party emotes:** show emotes in the picker (", text)
        self.assertIn("* keep the **sleep timer** running in picture-in-picture (", text)
        self.assertIn("* support Twitch 31.8.0 (", text)
        self.assertIn("* **tooling:** isolate patch inputs (", text)
        self.assertIn("* serialize sleep dispatch (", text)
        self.assertNotIn("document the hooks", text)

    def test_a_scope_naming_several_apps_lists_the_commit_under_each(self):
        text = self.notes(self.commit("fix(kick,twitch): keep the Sleep timer after a restart", "a"))
        self.assertEqual(text.count("keep the **Sleep timer** after a restart"), 2)
        self.assertNotIn("### Shared", text)

    def test_breaking_changes_come_first_and_keep_their_scope(self):
        text = self.notes(self.commit("feat(kick)!: drop Kick 40.31", "a"),
                          self.commit("fix(twitch): rename a key", "b", "BREAKING CHANGE: switches start off again"))
        lines = text.splitlines()
        self.assertEqual(lines[2], "### ⚠ BREAKING CHANGES")
        self.assertEqual(lines[4:6], ["* **kick:** drop Kick 40.31", "* **twitch:** switches start off again"])

    def test_manager_description_drops_commit_links_only(self):
        text = self.notes(self.commit("fix(twitch): close #12 for the Sleep timer", "a"))
        description = release.manager_description(text)
        self.assertNotIn("/commit/", description)
        self.assertIn("* close [#12](https://github.com/owner/repo/issues/12) for the **Sleep timer**", description)
        self.assertIn("/compare/v1.1.0...v1.2.0", description)

    def test_app_catalog_reads_names_and_patches_from_the_sources(self):
        scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "release-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        fixture = Path(tempfile.mkdtemp(prefix="catalog-", dir=scratch))
        self.addCleanup(shutil.rmtree, fixture)
        source = fixture / release.PATCHES_SRC
        (source / "kick/shared").mkdir(parents=True)
        (source / "kick/shared/Constants.kt").write_text('val COMPATIBILITY_KICK = Compatibility(\n    name = "Kick",\n)\n')
        (source / "kick/timer").mkdir()
        (source / "kick/timer/SleepTimerPatch.kt").write_text(
            'val sleepTimerPatch = bytecodePatch(\n    name = "Sleep timer",\n    description = "x",\n)\n'
            'val helper = bytecodePatch(description = "no name")\n')
        (source / "shared").mkdir()
        with patch.object(release, "ROOT", fixture):
            self.assertEqual(release.apps(), {"kick": release.App("Kick", ("Sleep timer",))})


class HostGateTests(unittest.TestCase):
    def test_every_registered_failure_fails_the_gate_and_all_checks_still_run(self):
        scratch = Path(os.environ.get("MORPHE_WORK", Path.home() / "build/lurk-morphe-patches")) / "host-gate-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        fixture = Path(tempfile.mkdtemp(prefix="gate-", dir=scratch))
        try:
            python = fixture / "python3"
            python.write_text('#!/bin/bash\nprintf "%s\\n" "$1" >> "$CHECK_LOG"\n'
                              '[[ "$1" != "$FAIL_CHECK" ]]\n')
            python.chmod(0o755)
            log = fixture / "checks.log"
            env = {**os.environ, "PATH": f"{fixture}:{os.environ['PATH']}", "CHECK_LOG": str(log),
                   "FAIL_CHECK": ""}
            result = subprocess.run(["bash", str(REPO / "bin/test-host")], env=env, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            checks = log.read_text().splitlines()
            self.assertGreaterEqual(len(checks), 6)
            self.assertIn(str(REPO / "tests/test_release.py"), checks)
            for check in checks:
                log.write_text("")
                result = subprocess.run(["bash", str(REPO / "bin/test-host")],
                                        env={**env, "FAIL_CHECK": check}, capture_output=True)
                self.assertNotEqual(result.returncode, 0, f"Gate ignored failed check {check}")
                self.assertEqual(log.read_text().splitlines(), checks, "All checks must run after a failure")
        finally:
            if sys.platform == "darwin":
                print(f"Retained on macOS: {fixture}")
            else:
                shutil.rmtree(fixture)


if __name__ == "__main__":
    unittest.main()
