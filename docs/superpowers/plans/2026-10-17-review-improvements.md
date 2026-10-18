# Review improvements implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Track steps and evidence below; temporary briefs/reports go in `~/scratch/morphe/improvements-2026-10-17/`.

**Goal:** Implement every item in the code review and proposed chat features, verify them, and publish a stable release.

**Architecture:** Keep pinned patch hooks and settings contracts. Centralize generation-aware sleep handling and bounded networking/request sharing where needed. Extend the real chat surfaces discovered in the original APKs. Separate host, patched-output and phone verification.

**Tech Stack:** Kotlin Morphe patches, Java Android extensions, Hermes bytecode, injected JavaScript, Python/shell checks, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-17-review-improvements.md`

## Global constraints

- Work on dev; start at aea51ab. Do not push intermediate unfinished work: pushes publish prereleases.
- JDK `~/.local/opt/jdk-21`, SDK `~/.local/opt/android-sdk`, USB serial `ZA2234BBJ8` (revalidate before use).
- All scratch artifacts under `~/scratch/morphe/`; no new toolchain copies or temporary files inside the repo.
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

- [ ] Exercise actual patched countdown options through host waiting, Watch now, Cancel/close, switch off and a second host event.
- [ ] Exercise production KickHttp/KickChat from full and info responses through URL config to page options; numeric/string IDs, malformed JSON/config, unknown channel, previews, independent toggles and response preservation.
- [ ] Execute refresh callback with test player state, verify reload position and quality invariants; repeat on the phone with a recoverable real stream failure.
- [ ] Fix any exposed implementation defects, run output checks and commit.

### Task 5: Kick emote features and resilient delivery

**Files:** `patches/.../kick/emotes/`, `patches/.../kick/chat/`, `patches/src/main/resources/kick/chat/chat-page.js`, `extensions/.../kick/KickChat.java`, focused composer/provider helpers as required, `tests/kick/`, `docs/kick.md`.

- [ ] Inspect pinned RN composer and WebView message bridge; record exact hook design in the spec before implementing.
- [ ] Add stalled-owner/provider and partial-success tests; implement deadlines and independent global rendering with channel precedence.
- [ ] Add 7TV zero-width fixtures and live add/update/remove fixtures; implement layering and live update lifecycle with bounded retries and shutdown.
- [ ] Add picker/autocomplete tests covering insertion at cursor, Unicode, existing draft, signed-out state, channel change and disabled provider setting; implement in the actual chat composer.
- [ ] Run browser/native/Hermes checks and phone UI tests; update descriptions, request disclosures and documentation; commit.

### Task 6: Twitch RN chat coverage and provider/UI tests

**Files:** Twitch chat/emote/badge patches, Hermes helpers, runtime chat/provider/layout helpers and tests as indicated by target inspection.

- [ ] Map RN live chat and replay surfaces in the pinned APK, and record current hooks versus missing coverage in the spec.
- [ ] Add behavioral fixtures and extend existing settings for supported timestamps, deletion retention, keywords, appearance and third-party rendering paths; keep account restrictions and stock off behavior.
- [ ] Add provider malformed/empty/error payload parsing, Unicode range, overlay ownership and gesture-cancellation tests.
- [ ] Check native and RN flows on phone, then update coverage docs and commit. Any unsupported path requires specific evidence and a recorded resolution, not silent omission.

### Task 7: Full output, phone, account and privacy verification

**Files:** verification scripts and `docs/verification-2026-10-17.md` (create), any failures' owning implementation files.

- [ ] Build the bundle and patch all three pinned APKs; verify emitted extension calls, resources, settings and Hermes edits. Record hashes and selected patches.
- [ ] Install on authorized USB phone and run relevant ART/UI probes; background/foreground, Wi-Fi/mobile, lock-screen and PiP transitions.
- [ ] Obtain user-entered account sessions and validate named authenticated flows. Do not send real chat, spend points/money, or change subscriptions without explicit scope for the specific action; use read-only/test-channel equivalents where they prove the behavior, and record remaining input needs.
- [ ] Inspect residual telemetry payloads/dependencies and compare relevant switch transitions, including Rumble consent restart and push consequences.
- [ ] Fix failures and rerun affected plus full host/build gates; have independent review of completed diff and resolve findings.

### Task 8: Push and stable release

- [ ] Audit every spec requirement against current evidence; no open implementation or required verification item.
- [ ] Run required final checks, commit remaining owned documentation, and push dev.
- [ ] Observe successful CI and dev prerelease, inspect generated patch list/assets.
- [ ] Merge dev-to-main PR without squash; observe stable release, downloadable valid bundle and successful merge back to dev.
- [ ] Synchronize local dev, verify clean tracked worktree, report released version and any retained scratch artifacts.
