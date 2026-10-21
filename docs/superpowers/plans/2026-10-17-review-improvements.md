# Review improvements implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Track steps and evidence below; temporary briefs/reports go in `~/build/lurk-morphe-patches/improvements-2026-10-17/`.

**Goal:** Implement every item in the code review and proposed chat features, verify them, and publish a stable release.

**Architecture:** Keep pinned patch hooks and settings contracts. Centralize generation-aware sleep handling and bounded networking/request sharing where needed. Extend the real chat surfaces discovered in the original APKs. Separate host, patched-output and phone verification.

**Tech Stack:** Kotlin Morphe patches, Java Android extensions, Hermes bytecode, injected JavaScript, Python/shell checks, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-17-review-improvements.md`

## Global constraints

- Work on dev; start at aea51ab. Do not push intermediate unfinished work: pushes publish prereleases.
- JDK `~/.local/opt/jdk-21`, SDK `~/.local/opt/android-sdk`, USB serial `ZA2234BBJ8` (revalidate before use).
- All scratch artifacts under `~/build/lurk-morphe-patches/`; no new toolchain copies or temporary files inside the repo.
- Production behavior changes need regression evidence: record the failure before the fix and passing result afterward.
- Commit only owned paths. One implementation worker at a time; research/review workers may run independently.
- User's existing autonomous-execution and release authorization supersedes repeated skill approval prompts.

## Review focus

- A resumed instance must be protected just as a newly constructed player is (Task 2).
- Requests that continuously make small progress still hit the absolute deadline (Task 3).
- Disabling a patch or navigating away must stop new provider activity and stale UI work (Tasks 3, 5, 6).
- Corrupt/incomplete channel data must preserve chat and unrelated switches (Tasks 4, 5).
- A published release whose backmerge failed must be recoverable without duplicating a release (Task 1).

### Task 1: Host verification and release recovery

**Files:** `.github/scripts/release.py`, `.github/workflows/{build,release}.yml`, `tests/twitch/{verify_interface.py,InterfaceProbe.java}`, new `bin/test-host`, new `tests/test_release.py`, `tests/README.md`, testing section of `docs/development.md`.

**Interfaces:** `bin/test-host` exits nonzero for any failed registered APK-free check; supports the repository's JAVA_HOME/MORPHE_WORK conventions. Release script keeps existing CLI and generated output formats.

- [x] Reproduce interface compilation failure; repair doubles and assert startup destinations, Home tabs, explicit Clips routing and disabled behavior.
- [x] Write failing release tests for publication recovery, no new commits, advanced dev, retry after successful publication/failed backmerge, and checkout restoration.
- [x] Make stable finalization shared and idempotent; preserve release assets/output even when subsequent finalization fails.
- [x] Add host command with the five existing JVM helper scripts and release tests. Other workers will supply additional commands for registration.
- [x] Gate PR/release publishing with the host command; update test documentation. Run host checks, then commit owned paths.

### Task 2: Sleep generations and Rumble state transitions

**Files:** shared `SleepTimer.java`, `kick/KickPlayer.java`, sleep section of `twitch/TwitchPlayer.java`, `rumble/RumbleChat.java`, `rumble/consent/BlockCookieNoticePatch.kt`, `tests/rumble/{KeywordsProbe,PlayerProbe}.java`, new host/scheduler regression scripts, relevant Rumble docs.

**Interfaces:** existing injected onPlay/onStop signatures stay unchanged. A shared expiry representation carries eligible players plus generation; platform fallback callers use that representation.

- [x] Add scheduler-controlled failing tests: new player after expiry, resumed same player, genuinely missed player, stopped/released player, repeated expiry.
- [x] Implement generation invalidation and expiry-time player eligibility for both callers.
- [x] Add empty-history, keyword-off-at-join and account-change identity regressions before fixing discovery.
- [x] Mark cookie blocking restart-required and update its effect text; add saved-speed on/off/on behavior coverage and align docs.
- [x] Run host checks and current-helper Android probes; report commands for host-runner integration; commit owned paths.

### Task 3: Twitch asynchronous playback and chapters

**Files:** `twitch/TwitchBackupStream.java`, `twitch/TwitchVod.java`, focused shared/request helpers if needed, `tests/twitch/AdBackupProbe.java`, new host checks.

**Interfaces:** keep existing injected public signatures. Network deadlines must use monotonic elapsed time throughout each operation. Request-sharing helper exposes one result per VOD to all waiting callers.

- [x] Reproduce deadline overruns with delayed headers and trickling bodies; reproduce delayed old-source/all-stale playlist behavior.
- [x] Bound body bytes, absolute duration and cancellation; preserve correct source/sequence transitions and existing ad fallback.
- [x] Test repeated chapter prefetch/open, failure retry, eviction, queue bounds and navigation before response.
- [x] Add bounded request sharing, failure backoff and stale-dialog suppression; run focused and host checks; commit.

### Task 4: Kick and Rumble patch-integration regressions

**Files:** Kick `tests/kick/HermesProbe.kt`, `tests/kick/test_chat_page.py`, new Kick native chat boundary probe; Rumble `tests/rumble/RefreshProbe.java` and any defect uncovered in the corresponding hooks.

- [x] Exercise actual patched countdown options through host waiting, Watch now, Cancel/close, switch off and a second host event.
- [x] Exercise production KickHttp/KickChat from full and info responses through URL config to page options; numeric/string IDs, malformed JSON/config, unknown channel, previews, independent toggles and response preservation.
- [x] Execute refresh callback with test player state, verify reload position and quality invariants. The installed-app recoverable real stream failure remains required under Task 7.
- [x] Fix any exposed implementation defects, run output checks and commit.

### Task 5: Kick emote features and resilient delivery

**Files:** `patches/.../kick/emotes/`, `patches/.../kick/chat/`, `patches/src/main/resources/kick/chat/chat-page.js`, `extensions/.../kick/KickChat.java`, focused composer/provider helpers as required, `tests/kick/`, `docs/kick.md`.

- [x] Inspect pinned RN composer and WebView message bridge; record exact hook design in the spec before implementing.
- [x] Add stalled-owner/provider and partial-success tests; implement deadlines and independent global rendering with channel precedence.
- [x] Add 7TV zero-width fixtures and live add/update/remove fixtures; implement layering and live update lifecycle with bounded retries and shutdown.
- [x] Add picker/autocomplete tests covering insertion at cursor, Unicode, existing draft, signed-out state, channel change and disabled provider setting; implement in the actual chat composer.
- [x] Run browser/native/Hermes checks and actual emulator composer/lifecycle checks; update descriptions, request disclosures and documentation; commit. Physical-phone UI remains required under Task 7.

Task 5 implementation and scoped re-review complete at `14cd09b`: native picker/autocomplete, atomic cursor insertion, channel lifecycle and default/prepared loader paths verified on the emulator. Retained fixtures cover EventAPI mutations; live provider administration and physical-phone UI are not claimed.

### Task 6: Twitch RN chat coverage and provider/UI tests

**Files:** Twitch chat/emote/badge patches, Hermes helpers, runtime chat/provider/layout helpers and tests as indicated by target inspection.

- [x] Map RN live chat and replay surfaces in the pinned APK, and record current hooks versus missing coverage in the spec.
- [x] Add behavioral fixtures and extend existing settings for supported timestamps, deletion retention, keywords, appearance and third-party rendering paths; keep account restrictions and stock off behavior.
- [x] Add provider malformed/empty/error payload parsing, Unicode range, overlay ownership and gesture-cancellation tests.
- [ ] Check native and RN flows on phone, then update coverage docs and commit. Any unsupported path requires specific evidence and a recorded resolution, not silent omission.

Task 6 implementation and scoped re-review complete at `3b5330c`: all three final review
findings resolved, retained stale-provider/cold-pronoun regressions pass, actual Android
catalog/pronoun checks and mounted original RN provider images pass. Current emulator native,
RN consumer and same-process reload evidence is qualified in the dated verification record.
The unchecked phone item remains a Task 7 requirement; implementation acceptance does not close it.
Task 7 subsequently found and fixed the original floating RN path's missing genuine context and
the measured column opacity composition at `3ba886c`; combined Task 7 scoped implementation
review is clean after shared test-harness extraction at `f86098f`.

### Task 7: Full output, phone, account and privacy verification

**Files:** verification scripts and `docs/verification-2026-10-17.md` (create), any failures' owning implementation files.

- [x] Build the bundle and patch all three pinned APKs; verify emitted extension calls, resources, settings and Hermes edits. Record hashes and selected patches.
- [ ] Install on authorized USB phone and run relevant ART/UI probes; background/foreground, Wi-Fi/mobile, lock-screen and PiP transitions.
- [ ] Establish account sessions through normal on-device flows using a regular email for authorized test-account creation or an existing signed-in device session; resolve required verification and validate named authenticated flows. Do not send real chat, spend points/money, or change subscriptions without explicit scope for the specific action; use read-only/test-channel equivalents where they prove the behavior, and record remaining input/entitlement needs.
- [x] Inspect residual telemetry payloads/dependencies and compare relevant switch transitions, including Rumble consent restart and push consequences.
- [x] Fix failures and rerun affected plus full host/build gates; have independent review of completed diff and resolve findings.

Task 7 progress and exact artifact/evidence limits are recorded in
`docs/verification-2026-10-17.md`. The input-staging defect has retained real-command
RED/GREEN coverage. RN floating canonical context/retention/provider and actual column width/opacity
fixes are implemented at `3ba886c`; final N Twitch signatures/compiler/source/owner/build checks,
15-check host gate and qualified L/N actual consumer evidence are recorded. Twitch N and Kick J
retain their exact source-linked artifacts and prior checks. Rumble's final review correction
changes only the cookie-blocker description and restart/notification summary; its fresh artifacts
and unchanged-runtime comparisons are recorded in the verification document.
Same-process Kick reload and final production public playback smokes are supplied. Actual SDK
synthetic-payload investigation, real Rumble consent/restart
SDK-state transitions and the Firebase/Airship push dependency trace are supplied; no broad
SDK block was added. Physical-phone UI and named authenticated flows remain required.
Combined Task 7 scoped implementation review
is clean at `f86098f`. Whole-branch review of `590ef1e..f86098f` found one Important staged-index
publication defect and one Minor push-wording overclaim; both are fixed at `4e1aa6e`, with scoped
re-review clean through `b252644` (`final-fix-review.md`). Final technical review is complete.
The release regression causally fails on the
old successful path; the fixed focused suite passes 21 cases. One final host gate passes all
15 checks, including 21 release tests and four staging cases. All three current default USB
APKs are installed with exact hashes verified at 12:45:44 UTC on 2026-10-18: Twitch N, Kick J
and final-fix Rumble. Rumble session `720042754` completed successfully at 12:45:27 UTC after
3604.112 seconds; the original command exited 0 with `Success`, and no install job remains.
The earlier bounded waits and old-APK observations are retained as history, superseded by
later terminal success and installed-hash readbacks; the delay's cause is unknown. Lock readbacks
at 12:45:43 and 12:54:54 UTC describe the earlier state, before the user unlocked at 15:01 UTC.
Installation alone does not pass physical UI or account checks. The mixed installation/UI
checkbox above stays unchecked for remaining physical behaviors.
`task-7/phone-preparation/current-install-evidence.md` records the exact evidence. No push,
CI publication, release or backmerge occurred. Task 7 as a whole remains incomplete.
Kick's separate Go Live companion (`com.kick.streaming`) was absent during earlier bounded
preparation, which obtained no complete APK/XAPK. After unlock the official Play phone UI
installed stock 2.5.0/code 99; exact base/splits and static manifest/caller evidence are retained
in `task-7/kick-account-preparation/stock-companion/play-installed/`. The incoming caller directly
uses the viewer provider without a query/signature preflight in that path. The reverse permission-name
mismatch is static evidence, not an actual denial or categorical signature blocker. The companion
has not been launched; authenticated handoff remains unverified.

Bounded physical guest checks are recorded in `task-7/phone-validation-2026-10-18/evidence.md`.
All three apps were seen signed out. Twitch N's native portrait/landscape VOD/replay, exact ±15s
paused seeks, volume/brightness gestures, real Wi-Fi/cellular transition with chapter selection
and system PiP/Expand passed within their recorded scope. Temporary Twitch seek/swipe settings
were restored. Kick J's guest public VOD/replay chat, live chat with disabled composer, system
PiP/miniplayer return and bounded real-network continuity were observed; no unbuffered seek or
authenticated composer/handoff success is inferred. User authorization permits test-account
creation, but account-email and audible-output questions remain unanswered; no account pass.
Rumble's guest live video rendered on cellular, then Wi-Fi return produced a natural player/chat
error. Actual error-panel tap emitted `refresh: reloaded` and restored moving video without a
fixture; subsequent real PiP/Expand returned to the stream. This is player recovery, not seamless
transport return, precise position/quality or chat-reconnection proof; stale PLAYING state alone
was insufficient. No Rumble settings/account changes were made.
Rumble's unresolved chat continuity/history observation is explicitly ruled a moderate affected-session
limitation outside the player-only refresh requirement, which does not promise automatic chat/history
recovery. No branch regression or untouched-APK reproduction is confirmed. No chat/network pass
is claimed; reopening/focused follow-up may be needed. See
`rumble-chat-after-network-observation.md` and ROOT/progress.md.
Attempted Rumble landscape inputs left portrait captures and do not establish landscape rendering.
The inherited MediaSession 10/30-second labels under custom ±15s seek are explicitly ruled a
low-severity metadata/accessibility limitation outside current explicit requirements, unchanged
from `590ef1e..2b9b75b`; measured seeks pass, actual TalkBack/lock-screen announcements are
unverified. See `media-skip-label-observation.md` and the ruling in ROOT/progress.md.
Final Twitch physical secure-lock/wake check records PLAYING/speed 1 positions
64672→72928→75914 ms at 15:47:38/47/50 UTC, Awake/unlocked→Dozing/locked→Awake/still locked.
No audible-output or security-bypass claim. `device-settings-restored.json` verifies temporary
device settings restored (stay-on 0, accelerometer 1/rotation 0, timeout 300000, auto-brightness 1,
Wi-Fi/mobile 1/1); volume is latest pre-gesture 3/15, system brightness was not written.
Twitch was stopped with no sessions at 15:49:23 UTC, Kick/Rumble stopped, Go Live unlaunched.
At the end of that 15:49 UTC phase the phone was locked again; later unlock/state is recorded below.
Remaining physical/account requirements stay open; no waiver or Task 7 completion is claimed.

#### Authorized test-account preparation, 16:00–16:05 UTC

A dedicated [Mail.tm](https://mail.tm/) inbox was active and ownership verified for signup
attempts without a personal address; later domain rejections below supersede that assessment.
Official host-browser attempts created no platform accounts: Twitch's
email-first form reset after Continue (cause unconfirmed), Kick requires an unprovided birthday,
and Rumble stopped at security verification. The date-of-birth/on-device-entry question was
unanswered then. `task-7/test-accounts/outcome.md` and safe JSON record the limits. At 16:04:56 UTC
the phone is locked and all four app packages are stopped. Paid entitlements, audio confirmation
and actual signed-in physical flows remain open; Task 7 and Task 8 remain incomplete.

#### Account and physical continuation after 16:39 UTC

The user supplied the missing date of birth and unlocked the phone. Actual Twitch and Rumble
signup explicitly rejected the temporary inbox domain. Kick's native email-taken response does
not establish an existing account; its corrected official host form produced no captured successful
signup/authentication response, with cause unconfirmed. No platform accounts were created.
A regular email address or existing signed-in device session is now needed; that question is
pending. Rumble's settled 2712×1220 fullscreen video and portrait return now pass within their
recorded scope, superseding earlier unproven orientation inputs. Safe evidence:
`task-7/test-accounts/phone-session-notes.md`. Account, entitlement, audio and remaining physical
gates stay open; no completion/publication claim.

Later actual Twitch live native-chat rows visibly respond to paired text-size/alternating-row/
keyword-highlight/timestamp changes. The paired screenshot's XML timed out; no XML pass.
Normal cold navigation with Use native player off still produced original native message components
at 17:08:43 UTC, so physical RN remains open; controlled earlier RN evidence is not relabeled.
Two-channel navigation replaced settled incoming rows, wide full native video returned to current
portrait styled chat, and Home/return preserved the channel/settings. Landscape chat was collapsed;
no column/RN, exhaustive ownership, authenticated moderation/provider-event or chat-send pass.
All five modified preferences were freshly verified restored, with settled live rows showing
default smaller text/timestamps and no alternation/highlight. At 17:16:03 UTC the captured new
device baseline was restored: stay-on=0, accelerometer=1, rotation=0, timeout=1800000 ms;
the user's newer timeout was preserved, not reset to the earlier 300000 ms. At 17:17:20 UTC the phone was unlocked,
all four packages stopped, Go Live unlaunched. No intentional relock or account creation.
Regular email/existing-session, verification/paid entitlement, audio and actual physical RN/
authenticated gates remain open. Final technical review remains complete; Task 7/8 incomplete.

One normal Proton Mail Free permanent-inbox signup reached Human Verification. The offered
Email alternative returned to the same form with no code-input/success or message in bounded
checks; cause unconfirmed, no explicit Proton domain rejection. CAPTCHA was untouched, paid
upsell declined, no permanent inbox/platform account created. Browser exited/profile removed;
phone restoration unchanged. Normal email/session or user-completed verification prerequisites
and all remaining entitlement/audio/physical RN/account gates stay open.

### Task 8: Push and stable release

- [ ] Audit every spec requirement against current evidence; no open implementation or required verification item.
- [ ] Run required final checks, commit remaining owned documentation, and push dev.
- [ ] Observe successful CI and dev prerelease, inspect generated patch list/assets.
- [ ] Merge dev-to-main PR without squash; observe stable release, downloadable valid bundle and successful merge back to dev.
- [ ] Synchronize local dev, verify clean tracked worktree, report released version and any retained scratch artifacts.
