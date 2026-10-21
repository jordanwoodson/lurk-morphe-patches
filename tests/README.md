# Tests

Run the APK-free host suite before pushing:

```sh
bin/test-host
```

It needs Python 3, Git, a JDK (21 recommended), Chrome or Chromium and the pinned Python dependency:

```sh
python3 -m pip install -r tests/requirements-host.txt
```

`JAVA_HOME` selects the JDK, `CHROME` selects the browser and `MORPHE_WORK`
selects the scratch directory (default `~/build/lurk-morphe-patches`). It runs every registered check, reports
failures and exits nonzero if any check fails. Both PR builds and release jobs run it before building
or publishing. Add new standalone APK-free checks to the list in `bin/test-host`.

The host suite includes the Twitch interface, playback/link and privacy-consent JVM helpers, the
Kick player and privacy JVM helpers, deterministic shared sleep-timer regressions, Rumble identity and
saved-speed transitions, the offline Kick chat-page browser test, `test_release.py`, and
`test_patch.py`. The patch-command regression runs the real `bin/patch` in scratch fixture
repositories with a controlled Java boundary that reproduces Morphe's adjacent APKM extraction.
It checks unchanged inputs/repository/signing configuration, CLI options and install forwarding,
success/failure cleanup, plain APKs and concurrent per-run staging. It does not patch a real APK.
CI installs the pinned dependency and verifies the hosted Chrome binary before running the suite;
missing dependencies fail the checks. The interface probe exercises startup
destinations, Home and profile tabs, explicit Clips routing, and disabled settings. Release tests use
temporary local Git repositories and in-memory publication state: they cover missing publication,
stable backmerge retries, advanced dev, checkout restoration, output preservation and host gate failure
propagation without calling GitHub or building a bundle. Scratch fixtures are removed on Linux and
retained on macOS.

`tests/shared/test_sleep_timer.py` compiles the complete production SleepTimer, KickPlayer and
TwitchPlayer classes against platform stubs and drives their real scheduled callbacks with a virtual
clock. It covers new players after expiry, resuming the same player, missed old players, stop/release,
repeated expiry and disabling a pending fallback. A shared JVM/ART fixture also holds a two-thread
resume at the initial dispatch, queued executor and fallback boundaries, checks reentrant stop
callbacks, and covers stale/disabled queues and unchanged ordinary operations. All waits are bounded.
`tests/kick/verify_sleep_queue.py <apktool-dir>` checks the emitted executor argument flow in a Kick
APK patched with Sleep timer. `tests/rumble/test_state_transitions.py` runs the
production chat filter and Rumble helpers for empty and keywords-off joins, account changes, sign-out
and remember-speed on/off/on. Neither needs an APK, SDK or device.

`tests/twitch/test_async.py` compiles TwitchBackupStream and its production request helper unchanged.
It serves real loopback HTTP with delayed headers, trickling bodies and oversized responses, and drives
controlled IVS providers for absolute deadlines, cancellation, ad fallback and worker saturation.
It also checks delayed-source reconciliation, partial/all-stale playlist windows, subsequent request
hooks while cancelled HTTP remains blocked, and generation-safe unavailable/error delivery.
`tests/twitch/test_vod_requests.py` compiles TwitchVod unchanged and checks shared prefetch/open
results, failure retry/retention, successful-cache eviction, bounded queues/waiters, presenter ownership,
navigation, player detach/replacement, another live owner, and disabling. View fixtures reject worker
thread inspection. JSON, HTTPS and UI are platform doubles there; the existing ART
AdBackupProbe and visible VOD verification remain necessary for real platform/schema evidence.

The release script also runs the selected source's `bin/test-host` immediately before building a fresh
release or rebuilding an older release for recovery. A missing source gate or a failed check stops
publication for the run. This verifies the source actually being released even when the branch has
advanced since the workflow checkout. A divergent stable backmerge retains every dev generated file
and combines the stable changelog entry with dev's history.

The remaining checks need a patched APK, decoded output or a Hermes bundle, and for the probes an
Android emulator; those are separate from the host gate.

- `verify_*.py` and `test_*.py` inspect apktool output and Hermes bundles, or compile an
  extension helper against small stubs and run it on the JVM. Each script's docstring says
  what it takes.
- `*Probe.java` and `*Probe.kt` run real methods from the patched APK on ART. The
  `bin/test-<app>*` scripts compile them, push them to the emulator and run them.

See [docs/development.md](../docs/development.md#testing) for the workflow and the
per-app pages under `docs/` for what each check pins and why.

Kick patch integration regressions use the actual emitted APK and its Hermes manifest:

```sh
# hermes-dec must be installed in this Python environment.
python tests/kick/verify_hosting.py stock/index.android.bundle patched/kick.apk
ANDROID_SERIAL=emulator-5580 bin/test-kick-chat patched/kick.apk
ANDROID_SERIAL=emulator-5580 bin/test-rumble patched/rumble.apk RefreshProbe
python tests/rumble/verify_refresh.py patched/rumble-apktool
```

`verify_hosting.py` first verifies the stock hash, embedded bundle and manifest, then applies the
actual `stay_on_host` edit. It executes the host content, JSX callbacks, countdown hook and timer
callbacks, with React state/effect boundaries and persistent compiler caches. It executes the parent's
actual event-to-child output branch to unmount between hosts. Random duration alone is seeded at its
memo slot. Stock/off and three bytecode mutations check waiting, memo invalidation and manual routing.
It rejects unsupported executed instructions. Pusher delivery, React Native rendering and real routing
remain device integration work.

`test-kick-chat` needs SDK 36, a JDK, adb, an ART device and the host browser prerequisites above.
It runs production KickHttp/KickChat from the APK with real OkHttp responses and Android JSON/URI,
captures the WebView JavaScript boundary, then executes those exact scripts in offline Chrome.
Full/info responses, numeric/string IDs, malformed JSON/config, unknown channels, previews, independent
switches and unchanged response bodies are covered. It writes capture logs under `$MORPHE_WORK/tests/kick-chat`.
The APK-free page scenarios remain registered in `bin/test-host`; native captures require this separate command.

`RefreshProbe` executes the injected video-load registration, error-panel prefix, click callback,
reload bridge and real stock player `x4` on ART. ExoPlayer, Compose and state edges are supplied;
normal reloads retain source URL and 0/123456/4294967400 ms positions without persisting quality, with
per-video mapping and repeated taps. Null sources, disabled refresh, absent players and direct stock
quality changes are controls. A caught player Exception, escaping logger RuntimeException and escaping
Error check callback restoration. These are synthetic boundaries, not observed playback failures.
`verify_refresh.py` additionally checks the emitted fatal-state/mobile-panel branch and catch metadata.
Actual emulator fatal-panel/tap/recovery evidence now exists under an explicitly qualified
three-method error-policy fixture; see [the dated verification record](../docs/verification-2026-10-17.md).
Physical-phone and authenticated playback remain separate gates.


Twitch RN and provider checks:

```sh
bin/test-host
bin/test-twitch-chat-hermes stock/index.android.bundle
ANDROID_SERIAL=emulator-5580 bin/test-twitch-rn patched/twitch.apk
ANDROID_SERIAL=emulator-5580 bin/test-twitch-providers patched/twitch.apk
ANDROID_SERIAL=emulator-5580 bin/test-twitch-ui patched/twitch.apk
```

The Hermes check runs the production scanner/transplant on the pinned stock bundle, validates
branch/exception/register/header bounds and rejected donor artifacts, then independently reconstructs
stock bytes for off. The host consumer fixtures cover store identity, protected/retained rows and action
suppression, timestamps, appearance, Unicode provider spans, ownership and off export identity.
Provider host fixtures execute production Java with controlled clocks/network/platform edges; real
Android JsonReader payload tests are separate. Native RN bridge probes use isolated preferences and
no vibration service; they verify eligibility/dedup ownership without vibrating the device.

The UI probe uses actual MotionEvent, View trees and Handler queues in app_process, with controlled
Activity visibility/Window geometry. It covers quiet feeder navigation/recreation, stale-owner/off
callbacks, blocked text, Unicode truncation, queue bounds/pass-through, pointer/off/cancel behavior,
edge exclusions and brightness clamp/restoration. It does not claim a visible Activity, physical volume,
authenticated account, or real server event. Guest RN consumer/default/prepared/reload smoke and actual
store moderation require the separately prepared installed-app fixture and exact restoration afterward.

Twitch provider ownership regressions include reused RN rows across channel/source-room, user and user-ID
changes, retired effects and mismatched native catalog identities. Pronoun completion runs in both HTTP
response orders, including owner retirement, opt-out and shared native demand. The host check controls
JSON tokens/drawing; `bin/test-twitch-providers` repeats those completion assertions on ART with real
JsonReader/Canvas/PNG, controlled HTTP responses and isolated preferences (no provider traffic or app data).

`test_rn_chat.py` also runs `test_overlay_context.js` and `test_column_opacity.js`. They exercise
the original host/store/ChatLine boundary with committed immutable authority, canonical/protected
matching, retired publishers and unchanged host state, plus the measured nested column fills,
neutral/off/unsupported guards, sibling isolation and child state across opacity/PiP/docked changes.
These are controlled React/store boundaries. The [dated verification record](../docs/verification-2026-10-17.md)
separately records actual L floating provider images/local deletion controls and final N visible
column opacity, with explicit source linkage and cached badge/pronoun/default-control qualifications.
