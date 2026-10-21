# Contributing to Lurk

Thanks for helping. Lurk is a small project, so the most useful contributions are clear bug
reports, tested patches for the supported app versions, and notes on how an app works
internally. This page says how to do each so it lands quickly.

## Reporting a bug

Not sure it's a bug? Ask in [Discussions](https://github.com/jordanwoodson/lurk-morphe-patches/discussions) first.
Open a [bug report](https://github.com/jordanwoodson/lurk-morphe-patches/issues/new?template=bug_report.yml).
It asks for what a fix needs:

- The app and its version, the Lurk version (the release tag) and the Morphe Manager or
  Morphe Desktop version.
- Which patches were selected, and whether the Lurk setting for the feature was on.
- Your Android version and device.
- Steps that reproduce it, and the output of `adb logcat -s lurk AndroidRuntime` if the app
  crashes or a patch seems inactive. Lurk logs under the `lurk` tag.

Before filing, check the app's notes in [docs/](docs/README.md): several things that look like
bugs are documented limits, such as ads that Twitch stitches into the stream on the server.
Problems with Manager itself (downloads, patching that fails before Lurk's patches run,
installs) belong to the [Morphe project](https://github.com/MorpheApp/morphe-manager/issues).

## Requesting a feature or an app

Open a [feature request](https://github.com/jordanwoodson/lurk-morphe-patches/issues/new?template=feature_request.yml).
Say what the feature should do, where in the app it lives, and why it matters. Patches run
on the phone only: anything that needs a server, an account on another service, or a change
Twitch, Kick or Rumble makes on their side is out of reach.

A new app needs an APKMirror, Google Play or Uptodown listing and someone willing to keep
its patches working across versions. Ask first; adding an app is a bigger job than it looks.

## Working on the code

[docs/development.md](docs/development.md) covers setup, the layout, the `bin/` commands,
the test probes and how releases work. Read it first, then:

1. **Say what you're doing.** Comment on an issue (or open one) before starting on a patch,
   so two people don't build the same thing.
2. **Branch from `dev` and target `dev`** in your pull request. `main` only receives merges
   from `dev`.
3. **Keep a pull request to one feature or fix.** A patch, its fingerprints, its setting, its
   test and its doc entry belong together; unrelated changes go in another PR.

### Rules

- **APKs come from APKMirror, Google Play or Uptodown only.** No other mirrors, not even to
  look. Never commit an APK, decompiled code or a patched build; `apks/` and `tools/` are
  ignored and everything regenerable lives outside the repo (`$MORPHE_WORK`, default
  `~/build/lurk-morphe-patches/`).
- **Original work.** Write patches from your own analysis of the app. Don't copy or port code
  or fingerprints from other Twitch, Kick or Rumble patch bundles. Morphe's own repos
  (`bin/refs`) are fine as API reference, and saying where an idea came from is fine, as
  `docs/rumble.md` does.
- **Fingerprint on what survives obfuscation:** strings, method signatures, access flags and
  instruction patterns. Never on obfuscated class or method names. A fingerprint must match
  exactly once; throw a `PatchException` with a clear message when it doesn't, so a new app
  version fails to patch instead of patching wrong.
- **Every behavior patch gets a Lurk setting**, registered only when that patch is included.
  With the setting off, the app must behave exactly as unpatched. Say "applies after a
  restart" when the value is only read at startup.
- **Describe honestly.** The patch description is what users read in Manager: say what it
  does and what it doesn't. "Blocks the requests for display ads; ads stitched into the
  stream by the server still play" beats "Blocks ads".
- **Kotlin in `patches/`, Java in `extensions/`.** Code that runs inside the app is plain Java
  in `extensions/shared/`; the Kotlin standard library must not end up in the patched APK.
- **Don't edit generated files**: `patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`
  and the patch list in `README.md` are written by the release workflow. Don't create tags
  or releases by hand.

### Testing

Patch the supported version and try it on a device or the workspace emulator, with the
setting on and off:

```sh
bin/emu start                                  # boots in seconds from its last state
bin/patch twitch --install=emulator-5580
bin/ui launch twitch && bin/ui tree            # the screen as text; bin/ui tap "…", bin/ui wait "…"
bin/ui lurk twitch                             # the Lurk settings screen
bin/ui log --dump                              # logcat, tag lurk
```

`bin/emu save NAME` keeps a prepared state (signed in, on a channel) that `bin/emu start NAME`
boots into; see [docs/development.md](docs/development.md#testing).

Where something is worth pinning, add a check under `tests/<app>/`: the Python scripts
inspect patched output, the Java and Kotlin probes run real app methods on the emulator
through `bin/test-<app>*`. Say in the PR what you ran and what you couldn't (signed-in
flows, for example).

### Documenting

Each app has a page under `docs/` that records, per patch, what it hooks, how the hook is
found without obfuscated names, and how it was tested. Add your patch there; it is what the
next person reads when the app updates and the fingerprint breaks.

### Commit messages

[Conventional commits](https://www.conventionalcommits.org/) with the app slug as scope:

```
feat(twitch): add Chat timestamps
fix(kick): keep the saved quality after a restart
chore(rumble): document the tipping hooks
```

`feat` and `fix` on `dev` publish a pre-release automatically; `chore`, `docs` and `test`
don't. Keep the subject under about 70 characters and explain the why in the body when the
diff doesn't.

## Releases

The maintainer merges `dev` into `main` without squashing; the release workflow builds the
bundle, writes the changelog and patch list, and publishes the GitHub release that Manager
picks up. Pre-releases come from `dev` the same way.

## Licensing

Contributions are licensed under the [GPL-3.0](LICENSE) like the rest of the project. See
[NOTICE](NOTICE) for the Morphe naming terms: "Morphe" only ever appears descriptively.
