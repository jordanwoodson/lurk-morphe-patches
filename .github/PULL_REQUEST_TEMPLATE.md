## What

<!-- The patch, fix or change, in a sentence or two. Link the issue it closes. -->

## How it works

<!-- For a patch: what it hooks and how the fingerprint finds it without obfuscated names. -->

## How it was tested

<!-- App version, device or emulator, with the Lurk setting on and off. Say what you couldn't test. -->

## Checklist

- [ ] Original work; no code or fingerprints copied from other patch bundles
- [ ] Fingerprints match on strings, signatures and instruction patterns, not obfuscated names, and fail clearly when they don't match exactly once
- [ ] With its setting off (or the patch not selected) the app behaves as unpatched; the switch starts off unless CONTRIBUTING.md's exceptions apply, and no existing settings key was renamed
- [ ] Injected calls are built with `invokeStatic()` or guarded with `requireLowRegisters()`, new hooks are listed in `tests/<app>/expected-hooks.txt`, and matched names are recorded with `Locators.record()`
- [ ] No app version written outside `shared/Constants.kt` (`bin/test-host` passes)
- [ ] The patch description says what it does and what it doesn't
- [ ] `docs/<app>.md` updated; a check added under `tests/` where something is worth pinning
- [ ] No generated files edited by hand (`patches-list.json`, `patches-bundle.json`, `CHANGELOG.md`, the README patch list)
- [ ] Commits follow `feat|fix|chore(<app>): ...` and the PR targets `dev`
