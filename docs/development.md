# Development

The workspace side of the repo: building the bundle, getting apps, writing and testing a
patch, and how releases happen. The rules for contributions are in
[CONTRIBUTING.md](../CONTRIBUTING.md); the per-app pages next to this one say how each existing
patch works.

## Setup

```sh
bin/bootstrap
```

It checks the JDK (21+) and Android SDK (`ANDROID_HOME`, with platform 36 and build-tools),
installs jadx, apkeep, hermes-dec and uv through [mise](https://mise.jdx.dev) (`mise.toml`),
downloads Morphe Desktop and apktool into `tools/` with checksum checks, and checks GitHub
Packages access. Morphe's Gradle plugin is only published on GitHub Packages, so the build needs
a token with `read:packages`. `bin/build` borrows the `gh` CLI token; give it the scope once:

```sh
gh auth refresh -h github.com -s read:packages
```

For unattended builds (agents, a build server), put a classic token with `read:packages` in
`~/.gradle/gradle.properties` instead (`gpr.user`, `gpr.key`, mode 600): it doesn't depend on the
gh login, which loses the scope when it is renewed.

Everything regenerable (decompiled code, patched APKs, the emulator, logs) goes to
`$MORPHE_WORK`, default `~/build/lurk-morphe-patches/`. Set `MORPHE_WORK` to put it somewhere else.

## Layout

| Path | What |
|---|---|
| `patches/src/main/kotlin/io/github/jordanwoodson/patches/<app>/` | Patches for one app, one folder per feature with `<Name>Patch.kt` and its `Fingerprints.kt`. `shared/Constants.kt` holds the app's package, target versions and pinned signing cert. |
| `patches/.../patches/shared/` | Code shared across apps: extension paths, the settings screen builders. |
| `extensions/shared/` | Java injected into patched apps (`extensions/shared.mpe`): the Lurk settings screen, `Logger` (tag `lurk`), per-app helpers under `twitch/` and `kick/`. Add `extensions/<app>/` when an app needs its own module. |
| `tests/<app>/` | Output checks and on-device probes, run through `bin/test-<app>*`. See [Testing](#testing). |
| `docs/<app>.md` | Per-app notes: what each patch hooks, how it was found, how it was tested. |
| `assets/icon.png` | The Lurk icon at full size (1254 px). `patches-bundle.png` is a 512 px copy: Manager shows it for this source, and the README header uses it. |
| `assets/<app>-icon.png` | An app's Lurk launcher artwork, flattened to its two colors. `bin/app-icon` turns it into `patches/src/main/resources/<app>/icon/` (adaptive icon, vector logo, legacy `.webp`), which the app's icon patch copies in. |
| `assets/<app>-icon-round.webp` | The README patch-list icon for apps whose icon patch recolors Rumble's Lurk logo while patching instead of shipping its own (`twitch`, `kick`). |
| `apks/<app>/<version>/` | Original APKs, added with `bin/apk-add`. Not committed. |
| `tools/` | Downloaded jars and the Exodus tracker list. Not committed. |
| `$MORPHE_WORK` (`~/build/lurk-morphe-patches/`) | Decompiled code, patched APKs, the test emulator, reference repos, test output. Regenerable. |
| `~/.local/share/morphe/` | Morphe Desktop data, including the signing keystore. Keep it: updates only install over a patched app signed with the same key. |

Apps known to the workspace are the folders with a `shared/Constants.kt`: `twitch`, `kick`,
`rumble`. Add another with `bin/new-app <slug> "<Launcher name>" <package> [0xRRGGBB]`.

## Getting APKs

Only from **APKMirror**, **Google Play** or **Uptodown**. No other mirrors.

- APKMirror or Uptodown: download in a browser, then `bin/apk-add ~/Downloads/<file>`.
- Google Play: `bin/apk-fetch <app>` (latest version only). It needs a Google account token in
  `~/.config/apkeep/apkeep.ini`:

  ```ini
  [google]
  email = someone@gmail.com
  aas_token = aas_et/...
  ```

  Get the AAS token once with `apkeep -e <email> --oauth-token <oauth2_4/...>`, following
  [apkeep's Google Play guide](https://github.com/EFForg/apkeep/blob/master/USAGE-google-play.md).
  Use a spare account.

`bin/apk-add` reads the package and version, checks the base APK's signature, and compares the
signing cert with the one pinned in the app's `Constants.kt`. It pins the cert on an app's first
APK and refuses a mismatch after that. Files land in `apks/<app>/<version>/`.

Several variants of one version can sit side by side, and should: the phone's arm64-v8a build and
an x86_64 (or universal) one for the emulator. The patches change dex, Hermes and resources, not
native code, so a result on the x86_64 variant holds for the arm64 one. `bin/apk-info <app>` lists
each stored file's ABIs and splits; `bin/patch --abi=x86_64` picks a variant, and an install to an
`emulator-*` serial picks x86_64 by itself. Without such a variant the emulator runs ARM code
through its translation layer (slow, and Kick then needs the native-library workaround in
[kick.md](kick.md)). The smaller the variant, the faster every patch and install: Twitch's
all-ABI bundle patches to a 300 MB APK, a one-ABI one-density build to about 120 MB.

All three apps ship on APKMirror as `.apkm` bundles. Kick is React Native: most of its logic is
Hermes bytecode in `assets/index.android.bundle`. Patch it with `hermesPatch` from
morphe-patches-library (already a dependency); its native side (dex) patches like any other app.

## Workflow

```sh
bin/scan rumble          # tracker SDKs (Exodus Privacy signatures) and URL hosts in the app
bin/decompile rumble     # jadx Java, apktool smali, Hermes disassembly → $MORPHE_WORK/rumble/<version>/
# write patches under patches/.../rumble/
bin/patch rumble         # build the bundle, patch the newest stored version → $MORPHE_WORK/out/
bin/emu start            # headless Android 16 emulator (emulator-5580), ~5 s from its last state
bin/patch rumble --no-build --install=emulator-5580      # seconds; --no-build reuses the bundle
bin/ui launch rumble     # or: bin/ui open https://rumble.com/c/... --app rumble
bin/ui tree              # the screen as text; bin/ui tap "Settings", bin/ui wait "Lurk"
bin/ui lurk rumble       # the Lurk settings screen (runtime switches, no re-patch)
bin/ui log --dump        # Logger output from patched code (tag lurk); without --dump it follows
bin/emu save rumble-home # keep this state; bin/emu start rumble-home boots into it in ~6 s
```

`bin/ui --help` lists the rest (find, swipe, type, key, stop, reset, apps, shot). It honors
`ANDROID_SERIAL`. `bin/emu --port 5582 start` runs a second emulator with its own AVD for parallel
work; `bin/emu --port 5582 serial` prints its serial for `--install=` and `ANDROID_SERIAL`.

- `bin/app-icon <app> <artwork.png>` regenerates an app's launcher icon from square two-color
  artwork (a flat logo on a flat background).
- `bin/patch` passes anything after `--` to Morphe Desktop, for example
  `bin/patch twitch -- --exclusive -e "Disable analytics"`.
  Each run copies the unchanged input into its own `$MORPHE_WORK/tmp/patch.*` directory:
  Morphe extracts APKM components beside its input even when given `-t`. Extraction and
  temporary files are removed on exit; the original upload and shared signing directory
  stay intact, and the output remains in `$MORPHE_WORK/out/`.
- `bin/refs` clones Morphe's own repos (patcher docs, patches library, official patches, the
  template) into `$MORPHE_WORK/refs/` for API reference.
- `bin/apk-info <file|app>` shows a file's package, version and signing cert, or an app's stored versions.

## Writing a patch

The [Rumble notes](rumble.md) are the gentlest introduction: each Rumble patch, what it changes,
how it finds its code without obfuscated names, and how it was tested. The
[Twitch](twitch.md) and [Kick](kick.md) pages cover the native, React Native and Hermes cases.

One folder per feature under the app, with the patch and its fingerprints:

```
patches/.../patches/rumble/
  shared/Constants.kt
  analytics/DisableAnalyticsPatch.kt
  analytics/Fingerprints.kt
```

```kotlin
@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Stops the app from sending usage analytics.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)

    execute {
        SomeAnalyticsFingerprint.method.returnEarly()
    }
}
```

Fingerprints match on strings, signatures and instruction patterns rather than class names,
since R8 renames classes between versions. Each app's `shared/` folder has match helpers
(`Bytecode.kt`, `Hermes.kt`) that require exactly one match and throw a `PatchException`
otherwise, so a new app version fails to patch instead of patching the wrong method. See the patcher docs in
`$MORPHE_WORK/refs/morphe-patcher/docs/` (after `bin/refs`) and the
[fingerprinting guide](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_2_1_fingerprinting.md).

Patches with runtime behavior depend on the app's settings patch and register their switches
and choices on the app's `SettingsScreen`; the Lurk settings activity in `extensions/shared`
shows only the settings of the patches that were included. Code that runs inside the app is
plain Java in `extensions/shared/`, called from injected smali; `Logger.d(...)` (tag `lurk`) is
the quickest way to confirm a hook fires.

Two things that bite: Morphe silently drops a non-range `invoke-*` whose register is above
v15 (use `/range` forms and check with `dexdump`), and the extension must not pull the Kotlin
standard library into the patched app (`extensions/shared/build.gradle.kts` excludes it).

## Testing

Run `bin/test-host` for offline, APK-free checks with Python 3, Git, a JDK (21 recommended),
Chrome or Chromium, and `python3 -m pip install -r tests/requirements-host.txt`.
It honors `JAVA_HOME`, `CHROME` and `MORPHE_WORK`, runs all registered checks and fails if any check fails.
Both PR builds and release jobs require it before building or publishing. The suite exercises
the Twitch interface, playback/link and privacy-consent helpers, Kick player and privacy helpers,
the offline Kick chat page in headless Chrome,
and release recovery using local Git fixtures. See [tests/README.md](../tests/README.md) for coverage
and how to register another host check.

Before each release build, `release.py` reruns the selected source's host gate after checkout,
including an older release being rebuilt for recovery. A missing or failed gate stops publication.

The other checks need a patched APK, decoded output or a Hermes bundle, and for the probes a device.
The workspace emulator is `emulator-5580`; every Android `bin/test-*` script uses it unless
`ANDROID_SERIAL` names another device, so a connected phone is never patched by accident.

```sh
bin/emu start
bin/patch twitch --install=emulator-5580
bash bin/test-twitch "$MORPHE_WORK/out/twitch-31.7.0-patched.apk"       # injected loader and settings
bash bin/test-twitch-privacy "$MORPHE_WORK/out/twitch-31.7.0-patched.apk"
```

- The emulator (`bin/emu`) is a headless Android 16 x86_64 image under KVM with 8 cores, 6 GB and
  a 720x1600 screen at 280 dpi; rendering is SwiftShader on the CPU, so fewer pixels mean faster
  frames and smaller screenshots. One AVD per port lives in `$MORPHE_WORK/avd/`; the profile is in
  `bin/emu` and is applied on start (changing it drops the AVD's snapshots).
- Snapshots: `bin/emu stop` saves the running state and the next `bin/emu start` restores it in about
  5 s; `bin/emu save NAME` keeps a prepared state (apps installed, signed in, on a channel) and
  `bin/emu start NAME` boots into it in about 6 s, discarding changes at stop, while `bin/emu load NAME`
  jumps back in 2 s without a restart. `bin/emu snapshots` lists them, `bin/emu delete NAME` removes
  one, `bin/emu wipe` starts over with a fresh AVD.
- The host renderer keeps about 400 MB for every app process that is killed, so a day of force-stops
  and relaunches adds up. `bin/emu status` shows QEMU's size; `bin/emu restart` releases it and keeps
  the state. On Linux the emulator runs under a 16 GB memory cap.
- `bin/test-<app>` and the other `bin/test-twitch-*` scripts compile a probe from `tests/<app>/`
  with the Android SDK, push it with the patched APK and run it with `app_process`, so the
  probe calls the app's real, patched methods on ART. Several accept `--compile-only`.
- `tests/<app>/verify_*.py` and `test_*.py` are standalone Python scripts: they diff stock and
  patched apktool output, decode Hermes bundles, or compile an extension helper against small
  stubs and run it on the JVM. Each docstring gives the usage. They honor `MORPHE_WORK` and
  `JAVA_HOME`.
- `tests/verify_extension_calls.py <patched.apk> Class.method ...` checks that app code calls each
  named extension method. Run it after adding a hook: an injected call can vanish without an error
  (see below).
- `bin/decompile <app>` decodes the stock APK; decode a patched one the same way for the Python
  checks: `java -jar tools/apktool_*.jar d -f -p "$MORPHE_WORK/apktool-framework" -o <dir> <patched.apk>`.

The per-app pages record which checks exist for each patch and what could not be verified
(signed-in flows, for example). When adding a patch, add a check where there is something
worth pinning, and say in the pull request what you ran.

## Releasing

- Work on the `dev` branch. Pushing `feat:` or `fix:` commits there makes `release.yml` publish a
  pre-release; `chore:` commits don't release but still build. Merge `dev` into `main` (no squash)
  for a stable release; `open_pull_request.yml` keeps a `dev` → `main` PR open for that.
- `release.yml` runs `.github/scripts/release.py`. The version comes from the commits since the last
  release tag on the branch: `feat` is a minor release, `fix`, `perf` and `bump` a patch, `feat!:` or a
  `BREAKING CHANGE:` footer a major one; `dev` adds `-dev.N`. The script writes the generated files,
  builds the bundle, pushes the release commit and its tag together, then creates the GitHub release with
  the `.mpp`. If that last step fails, the next run (or "Run workflow" on the Release workflow) publishes
  the missing release. A stable release is merged back into `dev`; reruns also finish a failed backmerge
  after successful publication, without publishing again or creating duplicate merges. Published asset
  outputs survive later failures so the workflow can still attest them. To preview the next release and its
  notes: `git fetch && python3 .github/scripts/release.py --dry-run --branch dev`.
- Non-dry release runs require an index with no staged changes, including recovery of an older release.
  Commit or stash staged work first; the script rejects it before changing the checkout or publishing.
- Use semantic commit messages: `feat(twitch): ...`, `fix(kick): ...`, `chore: ...`.
- Don't hand-edit generated files: `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`, or
  the patch list in `README.md` (written by `.github/scripts/generate_patches_readme.py`). Don't
  create releases or tags by hand.
- When patches are tested on a new app version, update that version in the app's `targets` in
  `shared/Constants.kt`; Manager links users to the version listed there.
