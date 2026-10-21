# Shared setup for bin/* scripts. Source it; don't run it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Regenerable output (decompiled code, patched APKs, logs) lives outside the repo.
WORK="${MORPHE_WORK:-$HOME/build/lurk-morphe-patches}"
TOOLS="$ROOT/tools"
APKS="$ROOT/apks"
# A git worktree has neither apks/ nor tools/ of its own: share the main checkout's.
if [[ ! -d "$APKS" || ! -d "$TOOLS" ]]; then
    main_root="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    main_root="${main_root%/.git}"
    [[ -d "$APKS" || ! -d "$main_root/apks" ]] || APKS="$main_root/apks"
    [[ -d "$TOOLS" || ! -d "$main_root/tools" ]] || TOOLS="$main_root/tools"
fi
PATCHES_SRC="$ROOT/patches/src/main/kotlin/io/github/jordanwoodson/patches"
# Morphe Desktop keeps its signing keystore here. Keep it: Android only installs an update
# over a patched app when it is signed with the same key.
export MORPHE_DATA_DIR="${MORPHE_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/morphe}"

MORPHE_DESKTOP_VERSION=1.18.1
MORPHE_DESKTOP_SHA256=1b506ab5f03d16a2f65026d5e0e1910d01fc1e2152f21eaeb44ed2f30856597b
APKTOOL_VERSION=3.0.3
APKTOOL_SHA256=dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423

MORPHE_DESKTOP_JAR="$TOOLS/morphe-desktop-$MORPHE_DESKTOP_VERSION-all.jar"
APKTOOL_JAR="$TOOLS/apktool_$APKTOOL_VERSION.jar"

die() { echo "error: $*" >&2; exit 1; }
note() { echo ">> $*" >&2; }

# Absolute path of a mise-managed tool pinned in mise.toml (jadx, apkeep, hbc-*).
tool() { (cd "$ROOT" && mise which "$1") || die "$1 missing: run bin/bootstrap"; }

# Newest Android build-tools binary (aapt2, apksigner).
build_tool() {
    local dir
    dir="$(ls -d "${ANDROID_HOME:?ANDROID_HOME is not set}"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
    [[ -x "$dir$1" ]] || die "$1 not found in $ANDROID_HOME/build-tools"
    echo "$dir$1"
}

apk_py() { python3 -I "$ROOT/bin/lib/apk.py" "$@"; }

# Apps are the folders under $PATCHES_SRC that have a shared/Constants.kt.
app_slugs() {
    local f
    for f in "$PATCHES_SRC"/*/shared/Constants.kt; do
        [[ -e "$f" ]] && basename "$(dirname "$(dirname "$f")")"
    done
}

app_constants() {
    local f="$PATCHES_SRC/$1/shared/Constants.kt"
    [[ -f "$f" ]] || die "unknown app '$1' (known: $(app_slugs | tr '\n' ' '))"
    echo "$f"
}

app_package() { sed -n 's/.*packageName = "\([^"]*\)".*/\1/p' "$(app_constants "$1")" | head -1; }

slug_for_package() {
    local slug
    for slug in $(app_slugs); do
        [[ "$(app_package "$slug")" == "$1" ]] && { echo "$slug"; return; }
    done
    return 1
}

# Newest stored version of an app, or the one asked for.
app_version() {
    local slug="$1" version="${2:-}"
    if [[ -z "$version" ]]; then
        version="$(ls "$APKS/$slug" 2>/dev/null | sort -V | tail -1)"
        [[ -n "$version" ]] || die "no APKs stored for $slug: download one, then bin/apk-add <file>"
    fi
    [[ -d "$APKS/$slug/$version" ]] || die "no apks/$slug/$version (have: $(ls "$APKS/$slug" 2>/dev/null | tr '\n' ' '))"
    echo "$version"
}

# Every stored file (apk/apkm/apks/xapk) of an app version, sorted. Several variants of one version
# can sit side by side: the phone's arm64-v8a build and an x86_64 one for the emulator.
app_inputs() {
    find "$APKS/$1/$2" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.apkm' -o -name '*.apks' -o -name '*.xapk' \) | sort
}

# The stored file for an app version: the first one, or with an ABI the one that has native
# libraries for it (a file with no native code at all runs anywhere). Fails when none fits.
app_input() {
    local slug="$1" version="$2" abi="${3:-}" files f abis
    files="$(app_inputs "$slug" "$version")"
    [[ -n "$files" ]] || die "apks/$slug/$version has no apk/apkm/apks/xapk file"
    if [[ -z "$abi" ]]; then
        head -1 <<<"$files"
        return
    fi
    while IFS= read -r f; do
        abis="$(apk_py abis "$f")"
        if [[ "$abis" == none || " $abis " == *" $abi "* ]]; then
            echo "$f"
            return
        fi
    done <<<"$files"
    return 1
}

# GitHub Packages credentials for Morphe's Gradle plugin. ~/.gradle/gradle.properties
# (gpr.user/gpr.key) wins; otherwise borrow the gh CLI token, which needs read:packages.
github_packages_env() {
    if grep -qs '^gpr.key' "$HOME/.gradle/gradle.properties"; then
        return
    fi
    command -v gh >/dev/null || die "no gpr.key in ~/.gradle/gradle.properties and no gh CLI"
    export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user --jq .login)}"
    export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
}

latest_mpp() {
    ls -t "$ROOT"/patches/build/libs/patches-*.mpp 2>/dev/null | grep -v -e sources -e javadoc | head -1
}
