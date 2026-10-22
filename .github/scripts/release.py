#!/usr/bin/env python3
"""
Publishes a release of the patch bundle from the branch release.yml runs on.

main publishes stable releases (v1.2.3), dev publishes pre-releases (v1.2.3-dev.4). The version follows
the conventional commits since the last release reachable from the branch: a breaking change (`feat!:` or
a `BREAKING CHANGE:` footer) is major, `feat` minor, `fix`, `perf`, `bump` and `revert` patch. Without any
of those there is no release.

A release sets the version, writes CHANGELOG.md, patches-bundle.json, patches-list.json and the README
patch list, builds the bundle, pushes the release commit and its tag together (both or neither), then
creates the GitHub release with the .mpp. If that last step fails, the next run (or "Run workflow" on the
Release workflow) publishes the missing release from its own commit before anything else. A stable
release is then merged back into dev; reruns finish that merge even if publication already succeeded.

    python3 .github/scripts/release.py [--dry-run] [--branch dev]

--dry-run prints what would be published, with the release notes, and changes nothing. It reads the
branch as origin/<branch>, so fetch first.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager, nullcontext
import datetime
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parents[2]
LIBS = ROOT / "patches" / "build" / "libs"

STABLE_BRANCH = "main"
PRERELEASE_BRANCHES = {"dev": "dev"}  # branch: pre-release name
BACKMERGE_BRANCH = "dev"
GENERATED = ["CHANGELOG.md", "gradle.properties", "patches-bundle.json", "patches-list.json", "README.md"]
# Release and merge commits are made as the Actions bot.
AS_BOT = ["-c", "user.name=github-actions[bot]",
          "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com"]

PATCH, MINOR, MAJOR = 1, 2, 3
LEVELS = {"feat": MINOR, "fix": PATCH, "perf": PATCH, "bump": PATCH, "revert": PATCH}
# Release note sections in order. Commits of other types are left out of the notes.
SECTIONS = [
    ("fix", "🐛 Bug Fixes"),
    ("feat", "✨ New Features"),
    ("bump", "🚀 Updated App Support"),
    ("perf", "🔧 Improvements"),
]

TAG = re.compile(r"v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z]+)\.(\d+))?")
HEADER = re.compile(r"(\w+)(?:\(([^()]*)\))?(!)?: (.+)")
BREAKING = re.compile(r"^BREAKING[ -]CHANGE: *(.*)", re.M | re.S)
RELEASE_SUBJECT = re.compile(r"chore: Release (v\S+) \[skip ci\]")


class Version(NamedTuple):
    major: int
    minor: int
    patch: int
    pre: str | None = None
    number: int = 0

    @classmethod
    def parse(cls, tag: str) -> Version | None:
        match = TAG.fullmatch(tag)
        if not match:
            return None
        major, minor, patch, pre, number = match.groups()
        return cls(int(major), int(minor), int(patch), pre, int(number or 0))

    def order(self) -> tuple:
        # A pre-release comes before its stable version.
        return self.major, self.minor, self.patch, self.pre is None, self.number

    def bump(self, level: int) -> Version:
        if level == MAJOR:
            return Version(self.major + 1, 0, 0)
        if level == MINOR:
            return Version(self.major, self.minor + 1, 0)
        return Version(self.major, self.minor, self.patch + 1)

    @property
    def tag(self) -> str:
        return f"v{self}"

    def __str__(self) -> str:
        core = f"{self.major}.{self.minor}.{self.patch}"
        return f"{core}-{self.pre}.{self.number}" if self.pre else core


class Commit(NamedTuple):
    sha: str
    type: str
    scope: str
    subject: str
    breaking: str  # what breaks; "" when nothing does

    @property
    def level(self) -> int:
        if self.breaking:
            return MAJOR
        if self.type == "build" and self.scope == "Needs bump":
            return PATCH
        return LEVELS.get(self.type, 0)


class Plan(NamedTuple):
    version: Version
    previous: Version | None
    commits: list[Commit]


class Progress:
    """Keep published assets even if a later build or stable finalization fails."""
    def __init__(self) -> None:
        self.assets: list[Path] = []
        self.built = False

    def write_output(self) -> None:
        output(published=str(bool(self.assets)).lower(), built=str(self.built).lower(),
               assets="\n".join(str(path) for path in self.assets))


class HostVerificationError(SystemExit):
    """A source failed its required gate; do not publish any further release this run."""


# -------------------------------------------------------------------------------------------------
# Git and commands


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and result.returncode:
        sys.exit(f"git {' '.join(args)} failed:\n{result.stderr.strip()}")
    # Only newlines: str.strip() would also take the \x1f and \x1e separators commits() asks for.
    return result.stdout.strip("\n")


def git_ok(*args: str) -> bool:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True).returncode == 0


@contextmanager
def restore_checkout():
    """Restore a branch or detached HEAD, including when a build, merge or push fails."""
    branch = git("symbolic-ref", "--quiet", "--short", "HEAD", check=False)
    original = branch or git("rev-parse", "HEAD")
    try:
        yield
    finally:
        git("checkout", "--quiet", *([] if branch else ["--detach"]), original)


def run(*command: str) -> None:
    print("$", " ".join(command), flush=True)
    if subprocess.run(command, cwd=ROOT).returncode:
        sys.exit(f"{command[0]} failed")


def repository() -> str:
    if repo := os.environ.get("GITHUB_REPOSITORY"):
        return repo
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", git("remote", "get-url", "origin"))
    if not match:
        sys.exit("Set GITHUB_REPOSITORY to owner/repo")
    return match.group(1)


def tags(merged: str | None = None) -> set[Version]:
    """The release tags' versions: all of them, or those reachable from `merged`."""
    names = git("tag", "--list", "v*", *(["--merged", merged] if merged else [])).split()
    return {version for name in names if (version := Version.parse(name))}


def commits(since: str | None, head: str) -> list[Commit]:
    """The conventional commits after `since` up to `head`, newest first. Merges are left out."""
    found = []
    span = f"{since}..{head}" if since else head
    for record in git("log", "--no-merges", "--format=%H%x1f%s%x1f%b%x1e", span).split("\x1e"):
        if not record.strip():
            continue
        sha, subject, body = record.strip("\n").split("\x1f", 2)
        match = HEADER.fullmatch(subject.strip())
        if not match:
            continue
        kind, scope, bang, text = match.groups()
        footer = BREAKING.search(body)
        breaking = footer.group(1).strip() if footer else (text if bang else "")
        found.append(Commit(sha, kind, scope or "", text, breaking))
    return found


# -------------------------------------------------------------------------------------------------
# What to release


def plan(pre: str | None, head: str, visible: set[Version] | None = None) -> Plan | None:
    """
    The next release from `head`: a `pre` pre-release, or a stable release when `pre` is None. None when
    no commit since the last release calls for one. `visible` limits the tags considered (for tests).
    """
    def known(versions: set[Version]) -> set[Version]:
        return versions if visible is None else versions & visible

    reachable = known(tags(merged=head))
    if pre is None:
        reachable = {version for version in reachable if version.pre is None}
    previous = max(reachable, key=Version.order, default=None)
    found = commits(previous.tag if previous else None, head)
    level = max((commit.level for commit in found), default=0)
    if not level:
        return None

    stable = max((version for version in reachable if version.pre is None), key=Version.order, default=None)
    core = stable.bump(level) if stable else Version(1, 0, 0)
    if pre is None:
        return Plan(core, previous, found)

    version = Version(core.major, core.minor, core.patch, pre, 1)
    if previous and previous.pre == pre:
        version = max(version, previous._replace(number=previous.number + 1), key=Version.order)
    taken = known(tags())
    while version in taken:  # a tag outside this branch, left by an earlier failed release
        version = version._replace(number=version.number + 1)
    return Plan(version, previous, found)


def notes(version: Version, previous: Version | None, found: list[Commit], date: str, repo: str) -> str:
    """Release notes in the changelog's format, without a trailing newline."""
    url = f"https://github.com/{repo}"
    title = f"[{version}]({url}/compare/{previous.tag}...{version.tag})" if previous else str(version)
    lines = [f"## {title} ({date})"]

    def scoped(commit: Commit, text: str) -> str:
        scope = f"**{commit.scope}:** " if commit.scope else ""
        linked = re.sub(r"(?<![\w/\[])#(\d+)\b", lambda m: f"[#{m[1]}]({url}/issues/{m[1]})", text)
        return f"* {scope}{linked}"

    # As the changelog always has: by scope (a commit without one by its subject in its place), then text.
    def order(commit: Commit, text: str) -> tuple:
        return collate(commit.scope or text), collate(text)

    breaking = sorted((c for c in found if c.breaking), key=lambda c: order(c, c.breaking))
    if breaking:
        lines += ["", "### ⚠ BREAKING CHANGES", ""] + [scoped(c, c.breaking) for c in breaking]
    for kind, heading in SECTIONS:
        entries = sorted((c for c in found if c.type == kind), key=lambda c: order(c, c.subject))
        if entries:
            lines += ["", f"### {heading}", ""]
            lines += [f"{scoped(c, c.subject)} ([{c.sha[:7]}]({url}/commit/{c.sha}))" for c in entries]
    return "\n".join(lines)


def collate(text: str) -> tuple[str, str]:
    """A sort key that orders like JavaScript's localeCompare: letters ignore case, lowercase first."""
    return text.casefold(), text.swapcase()


def release_exists(repo: str, tag: str) -> bool:
    result = subprocess.run(["gh", "api", f"repos/{repo}/releases/tags/{tag}", "--silent"],
                            capture_output=True, text=True)
    if result.returncode == 0:
        return True
    if "HTTP 404" in result.stderr:
        return False
    sys.exit(f"Couldn't check for the {tag} release: {result.stderr.strip()}")


def latest_release(pre: str | None, head: str) -> tuple[str, Version, str] | None:
    """The newest matching release commit, including one still needing stable finalization."""
    for line in git("log", "--format=%H%x1f%s", "-E", "--grep=^chore: Release v", head).splitlines():
        sha, subject = line.split("\x1f", 1)
        match = RELEASE_SUBJECT.fullmatch(subject)
        version = Version.parse(match.group(1)) if match else None
        if version is None:
            continue
        # The newest release commit may come from the other branch (a stable release merged into dev).
        if version.pre != pre:
            return None
        text = git("log", "-1", "--format=%b", sha)
        if not text:
            changelog = git("show", f"{sha}:CHANGELOG.md")
            text = re.split(r"\n(?=## )", changelog, maxsplit=1)[0].strip()
        return sha, version, text
    return None


# -------------------------------------------------------------------------------------------------
# Releasing


def verify_source() -> None:
    """Run the selected source's mandatory gate, including older recovery checkouts."""
    sha = git("rev-parse", "HEAD")
    if not (ROOT / "bin/test-host").is_file():
        raise HostVerificationError(f"{sha[:7]} has no bin/test-host; cannot verify or publish this source")
    print(f"Verifying release source {sha}", flush=True)
    try:
        run("bash", "bin/test-host")
    except SystemExit as error:
        raise HostVerificationError(f"Host verification failed for {sha[:7]}: {error}") from error


def build(version: Version, *tasks: str) -> Path:
    run("./gradlew", *tasks, ":patches:buildAndroid", "--no-daemon")
    mpp = LIBS / f"patches-{version}.mpp"
    if not mpp.is_file():
        sys.exit(f"The build made no {mpp.relative_to(ROOT)}")
    with zipfile.ZipFile(mpp) as bundle:
        names = bundle.namelist()
    if "classes.dex" not in names or not any(n.startswith("extensions/") and n.endswith(".mpe") for n in names):
        sys.exit(f"{mpp.name} has no classes.dex or extension: Manager couldn't use it")
    return mpp


def publish(repo: str, version: Version, text: str, mpp: Path) -> None:
    flags = ["--prerelease", "--latest=false"] if version.pre else ["--latest"]
    command = ["gh", "release", "create", version.tag, str(mpp), "--repo", repo, "--verify-tag",
               "--title", version.tag, *flags]
    # gh deletes its draft when the upload fails, so trying again is safe.
    for attempt in range(1, 4):
        print("$", " ".join(command), "--notes ...", flush=True)
        if subprocess.run([*command, "--notes", text], cwd=ROOT).returncode == 0:
            return
        if attempt < 3:
            time.sleep(20 * attempt)
    sys.exit(f"Couldn't create the {version.tag} release; the next run publishes it")


def finalize(repo: str, sha: str, version: Version, text: str, mpp: Path | None,
             progress: Progress) -> None:
    """Publish once, then complete the retryable stable backmerge."""
    if mpp is not None:
        publish(repo, version, text, mpp)
        progress.assets.append(mpp)
    if version.pre is None:
        backmerge(sha, text)


def finish_unpublished(repo: str, branch: str, pre: str | None, head: str, dry_run: bool,
                       progress: Progress) -> Path | None:
    """Recover publication and stable finalization independently, even without new commits."""
    pending = latest_release(pre, head)
    if not pending:
        return None
    sha, version, text = pending
    if release_exists(repo, version.tag):
        if not dry_run:
            finalize(repo, sha, version, text, None, progress)
        return None
    print(f"{version.tag}: release commit {sha[:7]} has no GitHub release yet, publishing it", flush=True)
    if dry_run:
        print(f"\n{text}\n")
        return None
    tagged = git("rev-list", "-n", "1", version.tag, "--", check=False)
    if tagged and tagged != sha:
        sys.exit(f"{version.tag} points at {tagged[:7]}, not at its release commit {sha[:7]}")
    with restore_checkout():
        git("checkout", "--quiet", "--detach", sha)
        verify_source()
        mpp = build(version)
    if not tagged:
        git("tag", version.tag, sha)
        git("push", "origin", f"refs/tags/{version.tag}")
    finalize(repo, sha, version, text, mpp, progress)
    return mpp


def write_files(repo: str, version: Version, text: str) -> None:
    properties = ROOT / "gradle.properties"
    content = properties.read_text(encoding="utf-8")
    content, count = re.subn(r"^version\s*=.*$", f"version = {version}", content, flags=re.M)
    properties.write_text(content if count else f"{content.rstrip()}\nversion = {version}\n", encoding="utf-8")

    changelog = ROOT / "CHANGELOG.md"
    existing = changelog.read_text(encoding="utf-8") if changelog.exists() else ""
    changelog.write_text(f"{text}\n\n{existing}" if existing.strip() else f"{text}\n", encoding="utf-8")

    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    write_json(ROOT / "patches-bundle.json", {
        "created_at": now,
        "description": text,
        "download_url": f"https://github.com/{repo}/releases/download/{version.tag}/patches-{version}.mpp",
        "signature_download_url": "",
        "version": str(version),
    })


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def backmerge(release: str, text: str) -> None:
    """
    Brings a stable release commit into dev: a fast-forward when dev has nothing newer, else a merge. The
    merge keeps dev's own generated files (its version and pre-release bundle) and adds the stable release
    to dev's changelog.
    """
    target = BACKMERGE_BRANCH
    with restore_checkout():
        if not git_ok("fetch", "--quiet", "origin", f"+refs/heads/{target}:refs/remotes/origin/{target}"):
            sys.exit(f"Couldn't fetch {target}; rerun to finish the stable backmerge")
        # Already merged (including an earlier successful retry): no new merge commit or push.
        if git_ok("merge-base", "--is-ancestor", release, f"origin/{target}"):
            return
        if git_ok("merge-base", "--is-ancestor", f"origin/{target}", release):
            git("push", "origin", f"{release}:refs/heads/{target}")
            return
        git("checkout", "--quiet", "-B", target, f"origin/{target}")
        try:
            git_ok(*AS_BOT, "merge", "--no-ff", "--no-commit", release)
            conflicted = git("diff", "--name-only", "--diff-filter=U").split()
            if not git_ok("rev-parse", "-q", "--verify", "MERGE_HEAD") or set(conflicted) - set(GENERATED):
                sys.exit(f"{STABLE_BRANCH} doesn't merge cleanly into {target}; merge it by hand, then rerun")
            # A generated path can merge cleanly when dev advanced only in source. Always
            # restore dev's complete generated state, then combine the changelog explicitly.
            git("restore", "--source=HEAD", "--staged", "--worktree", "--", *GENERATED)
            changelog = ROOT / "CHANGELOG.md"
            changelog.write_text(f"{text}\n\n{changelog.read_text(encoding='utf-8')}", encoding="utf-8")
            git("add", "--", *GENERATED)
            git(*AS_BOT, "commit", "--quiet", "--no-edit", "-m", f"chore: merge {STABLE_BRANCH} into {target} [skip ci]")
            git("push", "origin", f"HEAD:refs/heads/{target}")
        finally:
            if git_ok("rev-parse", "-q", "--verify", "MERGE_HEAD"):
                git("merge", "--abort")


def release(repo: str, branch: str, pre: str | None, head: str, dry_run: bool,
            progress: Progress) -> Path | None:
    """Publishes a new release when the commits since the last one call for it."""
    next_release = plan(pre, head)
    if next_release is None:
        print("No release: no feat, fix, perf, bump, revert or breaking commit since the last release")
        return None
    version, previous, found = next_release
    if version in tags():
        sys.exit(f"{version.tag} is already a tag outside this branch; delete it or merge its commit")
    text = notes(version, previous, found, datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d"), repo)
    print(f"Releasing {version.tag} (after {previous.tag if previous else 'nothing'})\n\n{text}\n", flush=True)
    if dry_run:
        return None

    if git("status", "--porcelain", "--untracked-files=all", "--", *GENERATED):
        sys.exit("Generated release files have local changes; commit or stash them before releasing")
    original = git("rev-parse", "HEAD")
    verify_source()
    try:
        write_files(repo, version, text)
        mpp = build(version, "generatePatchesList")
        progress.built = True
        patch_list = ROOT / "patches-list.json"
        write_json(patch_list, {**json.loads(patch_list.read_text(encoding="utf-8")), "version": str(version)})
        run(sys.executable, ".github/scripts/generate_patches_readme.py", repo, branch, "patches-list.json", "README.md")

        git("add", *GENERATED)
        git(*AS_BOT, "commit", "--quiet", "-m", f"chore: Release {version.tag} [skip ci]", "-m", text)
    except BaseException:
        # Only clean paths owned by this release were changed above. Keep unrelated work and
        # staging intact, and allow the caller to restore a checkout with a different version.
        git("restore", f"--source={original}", "--staged", "--worktree", "--", *GENERATED)
        raise
    git("tag", version.tag)
    # Atomic: the branch never gets a release commit without its tag, or the other way round.
    git("push", "--atomic", "origin", f"HEAD:refs/heads/{branch}", f"refs/tags/{version.tag}")
    finalize(repo, git("rev-parse", "HEAD"), version, text, mpp, progress)
    return mpp


def output(**values: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as file:
            for key, value in values.items():
                file.write(f"{key}<<EOF\n{value}\nEOF\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0].strip())
    parser.add_argument("--dry-run", action="store_true", help="print what would be published, change nothing")
    parser.add_argument("--branch", default=os.environ.get("GITHUB_REF_NAME"), help="default: GITHUB_REF_NAME")
    args = parser.parse_args()

    branch = args.branch or git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != STABLE_BRANCH and branch not in PRERELEASE_BRANCHES:
        print(f"No releases from {branch}")
        output(published="false", built="false", assets="")
        return
    pre = PRERELEASE_BRANCHES.get(branch)
    repo = repository()

    progress = Progress()
    try:
        # Both release and recovery/backmerge commits consume the index. Refuse
        # staged local work before any checkout or mutation can include it.
        if not args.dry_run and not git_ok("diff", "--cached", "--quiet"):
            sys.exit("The index has local changes; commit or stash them before releasing")
        with restore_checkout() if not args.dry_run else nullcontext():
            if args.dry_run:
                head = f"origin/{branch}"
            else:
                # A waiting run releases the latest branch, including a prior release commit.
                git("fetch", "--quiet", "--tags", "--force", "origin", f"+refs/heads/{branch}:refs/remotes/origin/{branch}")
                git("checkout", "--quiet", "-B", branch, f"origin/{branch}")
                head = "HEAD"

            # A recovery failure mustn't hold up a newer release, but the run still fails.
            unfinished = None
            try:
                finish_unpublished(repo, branch, pre, head, args.dry_run, progress)
            except HostVerificationError:
                raise
            except SystemExit as error:
                unfinished = error
                print(f"::error::{error}", flush=True)
            release(repo, branch, pre, head, args.dry_run, progress)
            if unfinished:
                sys.exit(1)
    finally:
        progress.write_output()


if __name__ == "__main__":
    main()
