# Review improvements and feature completion

The user requested implementation of all improvements and features suggested in
`docs/review-2026-10-17.md`, followed by push and release after completion. They have
authorized autonomous execution and use of the USB Android phone. This specification
preserves that scope, including the review's optional feature proposals.

Success requires the eight review findings to be addressed, the named regression and
integration gaps to have retained checks, the proposed chat features to be implemented,
and a verified stable release through the existing workflow. An unavailable account or
unverified real-app behavior remains an open item; passing helper tests is not a substitute.

## Runtime behavior

- Sleep expiry belongs to a playback generation. A fallback may pause only players
  still belonging to that expiry. A new start or resume invalidates stale work. A player
  actually missed by the app's pause path must still be stopped.
- Rumble cookie blocking offers restart after a toggle; documentation distinguishes
  future Ketch calls from tracking that already initialized. Empty chat history and
  disabled keyword filtering must not prevent learning the signed-in identity. Account
  changes clear it. Remember-speed off/on behavior is documented and covered.
- Twitch replacement playlist operations share a monotonic absolute deadline, bound
  response bodies, and honor cancellation. Slow headers, trickling bodies, source
  changes, entirely stale playlists and failure fallbacks are tested.
- Chapter requests share work for a VOD, have bounded concurrency/cache and failure
  backoff, and cannot show an old video's dialog after navigation. No new dependency
  is required just to implement caching.
- Kick emote lookup has deadlines and can display global emotes while channel lookup
  stalls. Channel names override global names. Channel lookup and injected options
  are tested from native responses through page injection, including malformed data.

## Feature additions

- Kick 7TV: picker and autocomplete insert emote text into the existing chat composer,
  respecting signed-out/read-only state; zero-width emotes layer correctly; live set
  changes update rendering and suggestions. Existing off-by-default privacy control
  remains authoritative, with no provider requests while disabled.
- Twitch RN chat: inspect the pinned bundle's actual surfaces, then extend the existing
  chat settings to supported RN paths for timestamps, deleted-message retention,
  keywords, appearance and third-party rendering where those paths exist. Preserve
  native behavior, deep links, stock disabled behavior and account restrictions.
  Any actual unsupported path must be evidenced and resolved explicitly, not silently
  treated as implemented.
- Provider parsing, Unicode ranges, gesture cancellation, overlay ownership and
  network/background/PiP transitions gain behavior-focused coverage.

## Verification and release

- One APK-free test command runs local checks and gates PR and release workflows.
  Device/APK/Hermes stages have explicit prerequisites and do not silently skip failure.
- Stable release finalization is idempotent across publication and backmerge failures,
  including an advanced dev branch. The intended checkout is restored.
- Retain Kick host countdown/Watch now/Cancel/repeated-host tests and Rumble real
  refresh callback/position coverage. Install current patched builds on the authorized
  USB phone for relevant UI checks. Protect unrelated user data.
- Signed-in coverage: Twitch chat sending/points/Amazon login/member screens; Kick
  authenticated navigation/playback/broadcaster token sharing; Rumble account refresh
  and premium playback. Credentials are entered by the user on the device only.
- Investigate documented residual telemetry by payload and functional dependency;
  add blocking only when the behavior is understood and compatibility can be verified.
- Semantic commits on dev; push after required validation. Observe successful dev
  prerelease, merge the dev-to-main PR without squash, observe stable assets and the
  merge back into dev. Never hand-edit generated release files or tags.

## Constraints

Use pinned Twitch 31.6.0, Kick 40.32.1 and Rumble 3.21.0 APKs. Sources only APKMirror,
Google Play or Uptodown; no copying third-party patch implementations. Reuse installed
JDK 21 and Android SDK. Temporary output belongs in `~/scratch/morphe/`. Runtime code
stays Java and fingerprints use strings/signatures/patterns with unique-match checks.
All settings must act only when their patch is included. Preserve opt-in provider
privacy and disclose any added external requests.
