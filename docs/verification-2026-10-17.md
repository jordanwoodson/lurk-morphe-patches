# Review-improvements verification, 2026-10-17–18

Task 7 is incomplete. Task 6 was accepted at `3b5330c`; Task 7 fixes input staging at `783e045`
and measured RN landscape integration at `3ba886c`. Combined Task 7 scoped implementation
review is clean after the shared test-harness extraction at `f86098f`. Independent whole-branch
review of `590ef1e..f86098f` found one Important release-index defect and one Minor Rumble
notification-wording defect. Both are fixed at `4e1aa6e`; final scoped re-review through
`b252644` is clean (`final-fix-review.md`). Final technical review and installation of all three
current default APKs are complete. The user unlocked the phone at 15:01 UTC on 2026-10-18;
bounded physical checks are recorded below. Remaining physical-phone and named authenticated
flows are still required before release.
No push, tag, release or merge was performed for this verification. Missing prerequisites are
recorded as open gates, never as passing tests.

Evidence root (`ROOT` below):
`~/build/lurk-morphe-patches/improvements-2026-10-17/`. These local captures, manifests and logs are retained
for review; they are not committed or distributed release artifacts. The older observations in
the per-app pages describe their original artifacts. Current evidence is qualified below.

## Source, inputs and patch selections

Pinned originals in `ROOT/inputs/MANIFEST.tsv`:

| App | Package/version | Original APKM SHA-256 |
|---|---|---|
| Twitch | `tv.twitch.android.app` / 31.6.0 | `4545266092a493fcaaf6e84dfecd7d3ca7e754ad6de73d3404cbb765f56cc528` |
| Kick | `com.kick.mobile` / 40.32.1 | `17af0b07f4e3f8949bdf12668d6f3caf057014365999ae9b6aa0fddc60cc34ea` |
| Rumble | `com.rumble.battles` / 3.21.0 | `271cf31923f5657838d762a333150a6dcf0d5e4a38b5f592e24c8f9c2eca6ddf` |

Both default and expanded selections preserve package identity and the existing signing key.
Expanded selections add the optional non-identity patches below. Clone app and Change app name
are excluded; no new coverage of those options is claimed. Patch inclusion does not establish
the installed app's current preference value.

| App | Default / expanded patch count | Optional additions to defaults |
|---|---:|---|
| Twitch | 33 / 43 | Default video quality; Hide Bits tipping; Hide Clips; Hide Home sections; Hide chat highlights; Hide mature content warning; Hide player buttons; Hide subscription upsells; Playlist proxy; Show video stats |
| Kick | 19 / 24 | Bypass VOD ad sessions; Default video quality; Hide Clips; Hide KICKs tipping; Hide subscription upsells |
| Rumble | 26 / 30 | Default video quality; Disable autoplay; Hide Shorts; Hide tipping and Rants |

Current verification APKs: Twitch N uses `3ba886c`; Kick J uses `3b5330c` with its production
source unchanged since then. Fresh Rumble artifacts use `4e1aa6e`; its only production delta
since N is the two cookie-blocker metadata strings. No fresh device run is implied by that rebuild.

| Selection | Path relative to ROOT | APK SHA-256 |
|---|---|---|
| Twitch default | `task-7-n-output/twitch/twitch.apk` | `e5abd45cea3976e1444484fe83c21d632af5d746643a94722a0be315f134358c` |
| Twitch expanded | `task-7-n-output/twitch-expanded/twitch.apk` | `483839bb3c8843a4a3735bfd29ea73646db52e801e5141d7fdecda05668700d0` |
| Kick default | `task-7/candidate-task6-fix1/kick-default/kick.apk` | `4dc6226c95a0062ab4d91ab5eabea105deee5eed0c04b0bb4a082973247e163f` |
| Kick expanded | `task-7/candidate-task6-fix1/kick-expanded/kick.apk` | `b806bf7317c97b50ff17d21e4e9c79046cc5154752bbdd657150184a4bae2957` |
| Rumble default | `final-fix-output/rumble-default/rumble.apk` | `c444e2eb979127d9ebc5afdcf02346c2935c1b916ce0fddc92b76e63794755ae` |
| Rumble expanded | `final-fix-output/rumble-expanded/rumble.apk` | `aa423b4803db8bf863a560e9287701a489407422a0d768fabcb8c4189651b0cc` |

`task-7-n-output/verified-artifacts.json` records successful signature/16 KiB alignment checks
and compiler/source/bytecode contracts: Hermes compiler `250829098.0.19`, upstream
`facebook/hermes`, compiler SHA-256
`925ddf1bc2f17578e8af5df391c9d84dbd98441540fb43257aac3ef75c54b749`.
Final runtime source SHA-256 is `a7fad0225986fe058770d98bf67884d838de7cb6ff26727abb4c967c20f35bb9`;
HBC98 bytecode SHA-256 is `72fdec40d84347a7f253f32c351adced65de30af763632594ab263f5770dc552`.
`bundle-source.json` verifies both committed production source hashes, including RNChatPatch
`b3e51cfde9418572a7cb3cc67c00b525041bf72f521728283e2e0496d777b1b7`.
The copied `task-7-n-output/patches-1.11.1.mpp` snapshot SHA-256 is
`8d86a996b154a37533c04506635e2662641700a89cb9270571ea54ff72eda1ed`.
That N MPP is historical evidence and does not include the final Rumble wording correction.
`task-7/candidate-task6-fix1/verified-artifacts.json` records the four original J signature,
16 KiB alignment, package/version and successful-selection checks. Adjacent `*-result.json`
files retain each selected patch name and step. Their adjacent `bundle-source.json` binds the
earlier J bundle: SHA-256 `ad7fb8bbbdb8d36304eb480382c995df6cccd8f0a7f995548f031ad4e4ea22a7`.
`task-7-n-output/unchanged-kick-rumble-source.json` proves only Twitch's RN patch/runtime
production paths changed between J and N; shared extensions and Kick/Rumble source were
unchanged at N. Historical Rumble J default/expanded hashes are respectively
`19d1122f10109f90e410fde9a89b24ed060e896f753c2e7021d6ed7f3fbf880a` and
`2cd0a0d5abd986c3180569d45eb7d03d726af13da46bca6273bff32022df9839`.

The current `final-fix-output/patches-1.11.1.mpp`, built at
`4e1aa6e540b79eadf8c3f516e8a1623d83bd8a75`, has SHA-256
`52d8100e95a3f29abd3f98ed9464b7482c3edae6ea534fee7324ddc988289948`.
`final-fix-output/bundle-source.json` binds the Rumble patch source SHA-256
`00e53c6a2f767073073373d61663a8658bcb7eeae28ac6f1329493ba6b8c39bc`.
Compared with the N MPP, only that patch class, combined `classes.dex` and manifest timestamp
change; all other entries match. `final-fix-output/build.log` records the successful
14-second/46-task build and the existing Gradle 10 deprecation advisory.

`final-fix-output/verified-artifacts.json` records both fresh Rumble selections: 26/30 patches,
all patch/rebuild/sign steps successful, package `com.rumble.battles`/3.21.0, retained Morphe
signer and 16 KiB alignment. The MPP contains the narrowed description, and both emitted APKs
contain “After restarting: no Ketch consent notice and no consent recorded. May affect push
notifications.” Each APK differs from its matching J selection only in `classes6.dex` outside
signing metadata. The full default-to-default comparison of 39,077 unique decoded classes
finds only the Settings summary substitution; every other class text, hook and restart flag
is identical (`rumble-smali-diff.json`, `rumble-summary.diff` in that directory). The first
comparison correctly failed against a pre-existing decoded baseline containing optional patch
effects; freshly decoding the exact J default artifact resolved the baseline mismatch.
`final-fix-output/evidence.md` retains the commands and limits. Twitch N and Kick J artifacts
remain unchanged; earlier Rumble behavioral/device evidence applies to the unchanged hooks.
The later USB installation and subsequent physical checks are recorded separately below;
no notification-delivery result is claimed.

The earlier build at tooling commit `783e045` passed 46 tasks in 14 seconds
(`task-7/final-build.log`, `final-build-source.json`). Its MPP SHA-256 is
`64390e158346769b0b9fd866bc62f79305b447363dc0a1c116f0be132e9d22e4`.
This packaging rerun differs from the artifact-stage bundle hash above; they are separate
artifacts. Production Hermes/Java/DEX/Kotlin/resource tasks remained up to date, and source
comparison showed only the three tooling/test paths changed at that checkpoint. Final N's
later coherent build also passed 46 tasks in 14 seconds (`task-7-n-output/build.log`), with
33/43 successful Twitch selections and all 14 uniquely discovered direct owner contracts.

## Host, build, emitted output and ART

Tasks 1–6 retain their specific RED/GREEN, build and scoped review evidence in their reports.
Task 6's final host run passed all 14 then-registered checks and 19 release tests without
compiler warnings. J's coherent build passed 46 Gradle tasks. Production dependency versions
are unchanged; the local build uses the exact official pinned sources via
`build-composite.sh` and `composite-build.init.gradle` under ROOT. This local dependency
resolution does not prove that a future CI run can authenticate to GitHub Packages.

`task-7/candidate-31e9c97/evidence.md` and adjacent JSON manifests record all six H candidate
signatures, pinned package/version, 16 KiB alignment, input hashes, selected successful patch
steps, 16 emitted-output checks, and actual USB ART probes. Output checks cover extension
calls, resources/settings, privacy/interface/playback bytecode, Hermes edits and reversibility,
Kick quality/countdown/analytics/sleep and Rumble fatal refresh/catch metadata.
Twelve Twitch runners, Kick loader/catalog/native response captures and all ten Rumble probes
passed. These are isolated `app_process` executions on the locked physical phone, with controlled
platform/network/Compose boundaries where stated; they do not establish visible phone UI,
account authorization or real stream entitlement.

The first Kick wrapper browser stage failed because nested TMPDIR made Chrome's Unix socket
path exceed 108 bytes. The exact saved native captures passed using the existing shorter
`ROOT/tmp`; logs preserve both attempts. This is a test-environment path issue. Initial
extension-call checks also used incorrect `CompiledBundle` hook-owner names; corrected
`TwitchRNChat`/`KickComposer` checks pass. Rumble's RefreshProbe javac deprecated-API note
is retained, not hidden or treated as a failed test.

I linkage (`task-7/candidate-e949b96/`) proves only unused `TwitchRNChat` changed in Kick/Rumble;
their other decoded classes/resources/runtime assets are byte-identical to H. J changes Twitch
provider leases/pronoun completion. Fresh J USB RN and provider probes pass exact catalog
identity and all seven pronoun response-order/owner cases. Historical unchanged checks are
reused only with explicit source/byte comparisons; no claim of rerunning every suite on every
artifact is made. Historical Kick/Rumble J comparisons in `candidate-task6-fix1/*-smali-diff.json`
find only TwitchPronouns (including Lookup), TwitchRNChat and TwitchThirdParty changed from I.
The independent `*-method-diff.json` comparisons establish every other method's instructions
identical. All non-DEX ZIP payloads are identical. Those unused Twitch helpers do not change
Kick/Rumble's covered runtime paths.

`bin/patch` now stages an unchanged input copy and Morphe's temporary files inside a unique
`$MORPHE_WORK/tmp/patch.*` per run, removing the directory on exit. Morphe previously extracted
APKM components beside the repository input despite `-t`. `tests/test_patch.py` executes the
real shell command against scratch fixture repositories and a controlled Java boundary that
reproduces adjacent extraction. All four regressions first failed for the repository input
being passed directly; all four pass after staging. They cover success/failure cleanup, APKM
and APK inputs, overlapping runs, input hashes/repository contents, CLI/install options,
unchanged signing configuration and output path. This is command-boundary coverage, not a
real APK/signature test. `patch-staging-{red,green}.log` are under `ROOT/task-7/`; the regression
is registered in `bin/test-host`.

The tooling host checkpoint (`MORPHE_WORK=ROOT/task-7 TMPDIR=ROOT/tmp bin/test-host`)
passed all 15 registered checks (`task-7/host-final.log`). After the landscape fixes, the final
host command with `MORPHE_WORK=ROOT/task-7/rn-landscape-fix` and the pinned JAVA_HOME also
passed all 15 checks, including 19 release tests and four staging cases; `full-host-n.log`
in that directory retains the warning-clean output. The pinned-original Hermes gate passes
in `hermes-n.log`, including all new unique-owner assertions and existing restoration checks.

The final release-index regression first reproduced successful publication of an unrelated
staged binary file to a real local fixture remote (`final-fix-release-red.log`). Non-dry release
runs now reject staged work before fetch/checkout/recovery or any release mutation, preserving
the exact index tree and separate working-tree bytes. The retained tests inspect every fixture
remote branch/tag, cover already-published and unpublished recovery from detached HEAD, and
retain dry-run, failed-build cleanup and original-checkout restoration coverage. The focused
suite passes 21 tests (`final-fix-release-green.log`). One final full host run after all source
and test edits passed all 15 registered checks, including 21 release tests and four staging
cases (`final-fix-host.log`), with no compiler warnings. Command:
`JAVA_HOME=~/.local/opt/jdk-21 MORPHE_WORK=ROOT TMPDIR=ROOT/tmp PYTHONDONTWRITEBYTECODE=1 bash bin/test-host`.
These Git operations used disposable local fixtures; no actual release or remote backmerge
was performed. `final-fix-report.md` retains exact expanded commands, outputs and commit scope.

## Installed-app evidence and boundaries

The emulator is Android 36 `emulator-5580`, launched without audio. No audible-output or
physical-carrier claim follows from playback state/frames. The USB Motorola edge 2025
`ZA2234BBJ8` was securely locked during the earlier ART and installation work. Earlier ART probes
did not install/launch phone apps; the subsequent unlocked physical checks are recorded separately below.
Task 7 subsequently updated final default Twitch N and Kick with `adb install -r`, after verifying
their existing signer equals the candidate signer; both installed APK hashes match exactly.
N's 11:01:04 UTC readback records deviceLocked=1, UNTRUSTED, showing=true and secure=true
(`task-7/rn-landscape-fix/phone-install-n.json`).
No data wipe, account entry, phone app launch or security change was attempted. The earlier
Rumble same-signer update caller waits timed out after 240 seconds streaming and 90 seconds
via pushed APK/`pm install -r`. Their immediate installed-path/hash readbacks still showed
the previous `7beeaa46f7dcd3ee755a445b355aaca9a3fd00ffbdd0911e8824f47dca530918`
APK (`task-7/final-device-smoke/`). Later Android history marks both sessions `236658952`
and `1559316142` installed after about an hour each; the 11:44:33 UTC readback confirms J's
`19d1122f…` installed. Those terminal results supersede the earlier old-APK observations;
the bounded caller timeouts were not terminal installation failures. No delay cause is established.

The current final Rumble default update, explicit Android session `720042754`, completed at
12:45:27 UTC on 2026-10-18 after 3604.112 seconds from commit to terminal completion.
Android reports applied=true, failed=false and finalStatus=1; the original `pm install-commit`
command (exec session `86696`) exited 0 with `Success`. The operation was monitored through
completion, with no duplicate or abandoned install; no installer or observer job remains.
Fresh read-only package-path/SHA-256 checks at 12:45:44 UTC confirm all three current default
APKs in the table above are installed exactly: Twitch N, Kick J and Rumble final-fix `c444e2eb…`.
The 12:45:43 UTC readback reports deviceLocked=1 and UNTRUSTED; the 12:54:54 UTC refresh
confirmed the same state. Those are historical lock observations, before the user's 15:01 UTC
unlock. Installation alone did not establish physical UI/transitions or authenticated flows.

Evidence is under `task-7/phone-preparation/`: `current-install-evidence.md`,
`current-installed-artifacts.json`, `rumble-final-install-session.json`,
`rumble-final-install-status.json`, `rumble-final-install-commit.log` and
`phone-status-during-install.json`. Earlier late completions and readback are retained in
`rumble-install-session-status.json` and `rumble-late-install-readback.json`.
`rumble-final-install-diagnostic.json` records verifier configuration but does not establish
why completion took an hour. Normal replace preserved data; the owned remote input was
removed after successful install-write. No data wipe, security/verification setting change,
credential/token read, account entry, app launch or UI action was performed for this update.

Kick's stock broadcaster handoff has an additional prerequisite: the separate Go Live companion,
identified by [KICK's Android help](https://help.kick.com/en/articles/16536331-getting-started-with-the-kick-go-live-app-for-android)
and [Google Play](https://play.google.com/store/apps/details?id=com.kick.streaming) as
`com.kick.streaming`, was absent at 12:54:16 UTC (`pm path` exit 1). Earlier bounded approved-source
preparation obtained no complete APK/XAPK and started no companion install or launch; no cause
for the missing complete artifact is established. Historical evidence:
`task-7/kick-account-preparation/stock-companion/{evidence.md,preparation-result.json}`.
After unlock, the normal official Play UI installed stock Go Live 2.5.0/code 99. The 15:04:28 UTC
acquisition manifest records base plus arm64-v8a/xxhdpi splits under that directory's
`play-installed/`. Base APK SHA-256 is
`b6eb6edc8ab8ffdec0563d4b18bafc918f5eb7469d2069315e0da76197bba578`; APK signer SHA-256 is
`f016e8519887a75ca1f93aff0e47e4f0393e179117b916e4913754bf70d920fd`.
`play-installed/companion-static.md` inspects the original manifest and DEX: the incoming
broadcaster path directly calls the viewer's `getToken` provider without a query or signature
preflight in that sequence. The separate reverse viewer query requests a different permission
name from the companion's declared read permission. This is a static mismatch, not a measured
reverse denial; differing signer digests do not establish categorical handoff blocking.
The companion has not been launched and no live provider/token/account action was performed.
Its acquisition and static caller inspection supersede the earlier missing-companion prerequisite;
authenticated handoff and server acceptance remain unverified.

Earlier emulator and controlled-route observations (later physical checks are recorded separately):

| Flow | Evidence supplied | Boundary / remaining proof |
|---|---|---|
| Twitch native public VOD, replay and chapters | Production I APK `1eaadae6a6ab299a6d516ae0787096e3409187ebfa0dfca71e07c6b641caf6b4`; real four-chapter response/dialog; selected second chapter reached 4:25:09 | Emulator guest, before J provider fix. `task-7/twitch-i-ui/evidence.md`; later guest physical evidence below, account proof separate |
| Twitch PiP/background/network/screen sleep | Actual pinned PiP, system Expand return, Wi-Fi→emulated mobile→Wi-Fi; no-tap sleep/wake control retained PLAYING with increasing position | Emulator transports use host networking; later physical transport evidence below, audible output separate. Settings/network restored |
| Twitch RN consumer, moderation and reload | Original Fragment/ReactSurface and actual store/action controls; default/prepared/off execution and same-PID cleanup/rebootstrap in Task 6 | Explicit isolated route/observer fixture; production chooses native theatre in this guest session. No authenticated sending gate inferred from keyboard focus |
| Twitch provider images / pronouns | J mounted original RN provider images and real Android parser/Canvas/PNG pronoun cases; L adds actual post-rotation floating original row with genuine store/channel and three real CDN image decodes | L floating context/row code is byte-identical in N. Badge/pronoun consumer phase uses explicitly controlled cached metadata; no real-network badge/pronoun claim |
| Twitch overlay/gesture | Fourteen ART cases with real MotionEvent/View/Handler, owner retirement and pointer/off/cancel controls | Controlled Activity/window boundaries; later physical volume/brightness gestures below, other gesture/vibration paths separate |
| Kick picker/autocomplete/lifecycle | Task 5 actual native first mount/leave/reentry, cursor/manual moves, mixed stock span, autocomplete and follower-only restrictions | Emulator ARM-library extraction workaround only; no APK modification. Physical UI and feature-specific account compatibility remain open |
| Kick live provider sets / zero-width | Production-source EventAPI add/update/remove/gap/heartbeat/retry/resubscription and offline browser layering regressions | Controlled provider/browser boundaries; live provider-admin mutation not claimed |
| Kick same-process reload | Final default `4dc6226c…` plus two-call-through observer: original host.reload, same PID 8454, original release removes old registry, new instance/context on same host, bootstrap/configure/sourceURL twice, Home renders | `task-7/kick-reload/evidence.md`; default path proven. No prepared-main reload, GC/leak, mounted WebView disposal or broadcaster handoff claim; exact production APK/settings restored |
| Rumble playback/outage/PiP | Production `9905b9b2f0ad4a7eb371a3bdb3b76380c4986b094dc426be747c00ada7ad5653` decoded public video, automatically recovered from real network outage, entered PiP and returned | Task 4 emulator evidence; final/physical/authenticated/Premium proof separate |
| Rumble fatal error→Refresh→continuing position | Real Exo UnknownHostException, actual Compose fatal panel and actual tap→unchanged emitted callback→same video 02:32→03:02 under explicit three-method error-policy fixture | `ui-preparation/rumble-recovery/evidence.md`; fixture changes classification/fatal-state preservation only. Unchanged production did not naturally enter fatal during outage. Exact failure millisecond/source URL separately covered by ART; fixture not a release artifact |

Rumble refresh preference was originally false, temporarily enabled through actual UI, restored
false and production APK hash verified after the fixture. Twitch settings/network restoration
and historical J reinstall are recorded in Task 6 fix1; final N restoration is recorded below.
Original user sessions/data were
retained. No chat was sent, points/money spent, or follow/subscription changed. Kick's original
viewer provider is retained; a signature mismatch alone does not prove an incoming
`ContentProvider.call` is denied. Stock companion caller behavior is now statically inspected
above; authenticated token handoff remains unverified. No token was read or permission weakened.

Final production emulator smokes under `task-7/final-device-smoke/` additionally establish
Kick default `4dc6226c…` preserved the existing test-account profile and opened Sliggy's real
channel with changing decoded video and read-only incoming chat. `kick-live.png` is still Home;
`kick-channel-settled.png` and later playback captures establish the actual channel. A second
tap based on stale UIAutomator XML opened a read-only viewer card, which was dismissed without
an account action. The failed idle dump is retained and not treated as fresh hierarchy proof.
`kick-background-activity.txt` establishes a pinned task; the immediate screenshot alone is
not used as a settled PiP visual claim. MediaSession reported NONE, so video frames do not
imply a playing MediaSession or audible audio.

Rumble's historical J default `19d1122f…` opened Home and BobbyPoffGaming's public video; changing decoded
frames, visible PiP with `mode=pinned` and return to the same title are captured. These are
public playback checks; a video's embedded Premium logo does not prove Premium entitlement.
App preference values were not changed; both apps were force-stopped after capture.

J's portrait/landscape observation exposed a real context gap: the same original projected row
had three provider spans in portrait and none after rotation. The causal readback in
`task-7/rotation-diagnosis/run-v2/actual.log` established missing genuine store/channel/type
in the original floating overlay path; it was not waived as an unsupported fixture.
The first short readback entered an actual server ad and was inconclusive, which remains
explicit in the evidence. The bounded Task 7 fix now supplies committed tree-local context
without weakening canonical/protected/received/deletion gates or changing original host identity.

The fix's actual L observations in `task-7/rn-landscape-fix/observer/actual.log` establish:

- Same local original-store row gains genuine store/channel and three spans after rotation;
  original RN Image onLoad decodes real 7TV 96×96, BTTV 112×112 and FFZ 132×120 CDN images.
- A separately labeled cached-metadata phase supplies controlled badge/pronoun metadata
  while preserving native lease identity; the actual floating row consumes both titles.
  This proves consumer integration, not live badge/pronoun lookup.
- Original local delete handler changes the ordinary received row to retained deletion with
  strikethrough and suppressed URL/emote/actions. `observer-column/actual.log` separately
  verifies hidden/unpublished deletion controls gain no retained-deletion privilege or provider
  additions and preserve stock callback surfaces. No line or moderation was sent to Twitch.

L default SHA-256 was `f550a3d57c610cb043aad1436111f2c4e1d703d22c2ff85bd4d3d8139f0d0053`.
`l-to-n-source-linkage.json` verifies floating context and row-consumer regions byte-identical
in N; these are qualified L runs reused for unchanged code, not fresh N floating runs.

Original video double-tap cycles one-chat→no-chat→column-chat→one-chat. L measured 75%/25%
player/chat widths. M fixed the actual nested Fragment shape and returned alpha .5, but the
rendered panel remained opaque because original foreground and column fills covered it.
Bounded rendered controls rejected an extra-layer approach; it never entered production.
Final N uses existing empty background/foreground layers and clears only the two measured
ClassicChatColumn/ChatArea covering fills. A stable neutral React Provider avoids remounting
chat when opacity changes; it adds no native View. Header/composer, text/emote colors, player,
gutters and actions remain stock. Full configure-off restores original exports; malformed or
unsupported shapes retain stock handling. Floating one-chat opacity remains its stock behavior;
Lurk's column settings apply to `landscape-column-chat`.

N's unchanged-production `observer-n` proves real-native 50% settings render transcript RGB124
with ordinary incoming rows. Its file named `column-100.png` also contains 50%: that attempted
native getter control failed, so those two filenames do not establish a paired comparison.
`observer-n-pair` adds one explicit scratch-only cached-config default override at stage entry,
removed after eight seconds; the final 50% phase passes through real native settings unchanged.
The same-route 100%→50% pair reads transcript RGB247/247/248→124/124/124 across each 100×100
sample, composer #f7f7f8, and widths 75%/25%. The pair occurred during a stock ad interval and
proves rendering, not ad suppression or playback. Neither N fixture changes rows/provider metadata.

`task-7-rn-landscape-report.md` records exact source, fixture and earlier failed-control provenance.
Final `observer-n-pair/restored.json` and `ui-modes.json` verify production N's installed hash,
byte-identical original Lurk preferences, initial one-chat mode, free orientation, networks 1/1,
force-stop and removal of remote staging. The guest routing fixture overrides only the pinned
final capability/rollout return; it does not simulate authentication. No composer focus, draft,
account or provider-consent change occurred. Parent owns both devices; emulator shutdown to
release idle memory is parent-coordinated, not evidence of another app transition.

## Privacy investigation and Rumble restart matrix

`research-residual-telemetry.md` traces the pinned original DEX/JADX, and
`privacy-payload-probes/run-3.log` executes actual SDK serializers/callbacks with synthetic
identifiers and an in-memory transport. All fixture requests use that transport. The proof
establishes possible payload fields/completion behavior, not live account/device payloads.
No broad SDK, Firebase service or FCM block was added.

| Residual family | Established evidence | Remaining limits / compatibility dependency |
|---|---|---|
| Twitch Branch 5.20.3 | Actual limited-mode linked init retains synthetic advertising IDs/referrers; no-link init completes once with SDK -117 and zero requests; actual queued/synchronous URL serializers differ | Live advertising-ID presence/permissions, persisted transitions and real link routing untested. Link/share completion prevents safe blanket blocking; no complete identifier-scrub claim |
| Firebase Installations / FCM | Pinned registration/auth-refresh/header schemas and dependency paths; actual Rumble FIS body serializers with synthetic IDs | FIDs/auth and app/device metadata are identifying. Blocking shared workers can strand Tasks and break config/push; live registration/delivery untested |
| Rumble Remote Config | Actual fetch body/headers retain synthetic FID/auth, optional Analytics properties/firstOpen/custom signals; NO_CHANGE parsed; real launch listener resumes once for success and failure | Successful Task with result false resumes true (`isSuccessful`). Real namespace/cache/consent payloads unobserved; launch/config consumers retained |
| Rumble Firebase logging | Pinned CCT envelope/producer and separate Crashlytics endpoint traced | Hostname/CONNECT cannot identify producer, record age or queued payload. No live CCT decode, queued-drain or zero-telemetry claim |
| Rumble Facebook Graph | Startup settings constructor uses app token and SDK metadata; auto-event gating does not remove settings/gatekeeper fetch dependencies | Specific constructor has no advertising ID, which does not exclude other Graph requests. Auth/share compatibility untested; no blanket provider disable |

The actual emulator Rumble consent matrix uses production `9905…` plus an explicit single-call
observer in `RumbleMainActivity.onResume` and a new observer DEX. Other original method
instructions/ZIP contents are verified unchanged. User-visible Data Consent supplied real grants;
the observer neither sets consent nor fabricates SDK singletons. Disable analytics was explicitly
false throughout the main matrix, isolating cookie blocking.

| Actual UI transition | PID / observed SDK state |
|---|---|
| Cookie block off + Restart + Accept All/Confirm | 4433: applied grants, Analytics G111/measurement true, Performance/Crashlytics/Facebook flags true, Airship ready / feature mask 117 |
| Cookie block on, return without restart | Same PID 4433: effective cached getter false but already applied grants/SDKs/G111 remain enabled |
| Actual Restart Rumble | New PID 5401: raw cached grants preserved, effective decision false, empty applied purposes, G100/measurement false, tracked SDK flags false, no Airship instance |
| Cookie block off + actual Restart | New PID 5748: cached grants and prior enabled flags/G111/Airship restored |

`task-7/consent-fixture/run-v1/verified-matrix.json` assertions passed.
This supplies actual-process SDK-state evidence for the consent/restart finding. It does not
prove live payload absence, deletion of previously queued records or notification delivery.
POST_NOTIFICATIONS was denied throughout. Absent Airship mask is UNOBSERVED, not zero.

The push dependency trace (`task-7/consent-preparation/push-dependency.md`) corrects the old
blanket push-drop claim: Airship-handled messages require Airship startup and push features,
but inherited FirebaseMessagingService can display recognized notification payloads in the
background/locked state before invoking Airship's callback. Data-message handling has a
separate Airship-availability guard. This is static bytecode/source evidence of a possible path,
not receipt of any real notification. No delivery was attempted or inferred.

Afterward actual UI rejected optional grants; the real essential-only cache remains masked by
the restored default-on cookie blocker. Both privacy keys were restored to their original
implicit defaults, other settings checked unchanged, exact production APK/hash restored and
observer removed. Wi-Fi/mobile remained 1/1. The evidence records this cache change explicitly.

## Physical phone checks, 2026-10-18

The user unlocked the authorized phone at 15:01 UTC and authorized creating test accounts if
needed. Twitch, Kick and Rumble were physically observed signed out. The asynchronous account-email
and audible-output questions remain unanswered; no account creation or authenticated result is
claimed. Evidence root: `task-7/phone-validation-2026-10-18/`, especially `evidence.md` and its
named screenshots/XML/state captures. Screenshots are authoritative where UIAutomator reports
an underlying feed or fails to idle; those XML results are not treated as player hierarchy proof.

Twitch N's native public VOD/replay rendered in portrait and landscape. With a temporary custom
15-second interval, paused positions 271000→286000→271000 ms prove exact forward/back seeks.
Actual right-down volume swipe changed 3→1 with the 7% overlay; left-up brightness showed a
16% overlay and window override while global brightness stayed 13. Hardware volume-up later
restored 3/15, the immediate pre-gesture baseline. Audible output remains unverified.
Wi-Fi→validated cellular→Wi-Fi is observed with the existing VPN unchanged; selecting the real
second chapter while Wi-Fi was off reached PLAYING at 4:25:12 with a newly rendered game frame.
This establishes that bounded transition, not packet-level, gapless or audible behavior.
Actual system PiP had a pinned task; Android Expand returned to the native player with playback
progression. Refresh-gear long-press was followed by progression, but the filtered hook log was
empty: no callback/new-request success is inferred. The custom seek interval and swipe switch
were restored through visible UI to defaults (-10/+30 and off); sleep Off and refresh switches
remained unchanged, and Twitch was force-stopped after capture.

Kick J's guest public GMHikaru VOD rendered real replay messages/emotes. An earlier selected
VOD's mature gate was cancelled without acceptance. Real Wi-Fi→validated cellular→Wi-Fi and
progressing video/miniplayer frames establish bounded playback continuity; the attempted drag
left the player and does not prove an unbuffered seek or new segment fetch. Actual system PiP
was pinned, and Expand returned to Kick's original miniplayer. Later Home featured live playback
with real chat and a follower-only disabled composer; tapping it produced no editable keyboard.
No chat was entered/sent and no authenticated picker/autocomplete result is claimed. A transient
loading screen is not classified as a crash; retained exit metadata shows no new crash. No Kick
settings or account changes were made.

Rumble final-fix's account screen showed Sign In/Create Account; a featured public live stream
rendered with the guest Sign in to join gate. Video rendered on validated cellular with Wi-Fi
off, but the Wi-Fi return produced a natural “Something went wrong” player/chat error. This is
not a seamless-return pass; MediaSession still said PLAYING during the error and is insufficient
proof of playback. Tapping the actual clickable error panel at 15:41:47 UTC emitted the exact
`refresh: reloaded` line under the `lurk` tag and restored visibly changing live-video frames
(`rumble-natural-error.xml`, `rumble-natural-error-safe-log.json`,
`rumble-natural-refresh-after.png`). No classification fixture or new instrumentation was used.
This proves actual player recovery from the natural failure, not precise live/VOD position,
quality preservation or chat reconnection; chat still showed No Messages Yet. Home while errored
produced no PiP. After recovery, actual system PiP had a pinned Rumble task and Android Expand
returned to the playing original stream (`rumble-recovered-pip*`, `rumble-pip-menu/expanded`).
Rumble preferences, consent, accounts and entitlements were unchanged; Wi-Fi/mobile data were
restored to 1/1 with the existing VPN unchanged.

`rumble-chat-after-network-observation.md` records the unresolved chat continuity/history
observation with moderate usability impact for this session. Static comparison finds the player
owner and chat hooks unchanged; branch chat changes concern identity learning rather than
reconnect/history requests. This does not establish a branch regression or prove reproduction
in the untouched APK. Explicit disposition in `ROOT/progress.md`: keep the limitation visible,
without expanding player-only refresh into automatic chat/history recovery. An affected session
may need reopening and later focused transport/history investigation. No all-chat/network pass
is inferred. Rumble's attempted landscape inputs produced portrait captures, so landscape
rendering is not established by this run.

The custom Twitch seek exposed an inherited metadata/accessibility limitation: MediaSession
actions still say “Rewind 10 seconds / Forward 30 seconds” while the measured seeks are ±15
seconds and visible controls are arrow-only. `media-skip-label-observation.md` establishes the
unchanged baseline `590ef1e..2b9b75b` and original fixed-label construction. Explicit disposition
in `ROOT/progress.md`: low severity, outside the current explicit requirements, retained for
future focused repair rather than counted as a pass. The limitation can misstate duration to
media-control clients; actual TalkBack or lock-screen announcements were not tested. No runtime
source change follows from this ruled disposition.

Twitch's final native guest VOD check pairs actual MediaSession and trust state:
15:47:38 UTC PLAYING/speed 1 at 64672 ms, Awake and unlocked; 15:47:47 UTC at 72928 ms,
Dozing and deviceLocked=1; 15:47:50 UTC at 75914 ms, Awake after wake key but still locked.
`twitch-lock-{before,asleep,awake}.json` and `phone-lock-{before,asleep,awake}.json` establish
bounded physical screen-off/secure-lock/wake position progression. No security bypass or
unlock attempt was used. Audible output remains unconfirmed; AudioTrack configuration metadata
is not hearing evidence.

Final `device-settings-restored.json` verifies stay-on-while-plugged-in=0, accelerometer=1,
user rotation=0, screen-off timeout=300000 ms, auto-brightness=1 and Wi-Fi/mobile data=1/1.
Volume is 3/15, the latest measured pre-gesture baseline; system brightness varied naturally
under auto-brightness and was not written. Earlier visible-UI captures verify Twitch skip/swipe
defaults restored, with no Kick/Rumble preference changes. Twitch was force-stopped at
15:49:23 UTC with sessions empty (`twitch-test-stopped.json`); Kick and Rumble were stopped,
and Go Live remained unlaunched. At the end of that 15:49 UTC phase the phone was locked again;
the later user unlock and terminal state are recorded below. Installed APKs and reviewed
production source were unchanged. No accounts were created, and the email/audio questions
were unanswered at that checkpoint.

### Authorized test-account preparation, 16:00–16:05 UTC

A dedicated [Mail.tm](https://mail.tm/) inbox was created and ownership/active status verified
through its official API at 16:00 UTC, allowing signup attempts without a personal address.
Later explicit domain rejections below supersede that early prerequisite assessment.
This establishes an inbox, not a Twitch, Kick or Rumble account. Official host-browser signup
attempts created no platform accounts: Twitch's email-first form returned to an empty email field
after Continue (cause unconfirmed); Kick's visible form requires a birthday, which was not
provided or submitted; Rumble's web route stopped at security verification without challenge
interaction or bypass. The asynchronous date-of-birth/on-device-entry question was unanswered then.
Evidence: `task-7/test-accounts/{outcome.md,inbox-evidence.json,final-prerequisites.json}`.
At 16:04:56 UTC the phone is locked and all four packages (Twitch, Kick, Rumble and Go Live)
have no application PIDs. Account creation/sign-in, paid entitlements, physical authenticated
flows and audible-output confirmation remain open; no prior gate is passed by this preparation.

### Account and physical continuation after 16:39 UTC

The user supplied the missing date of birth and unlocked the phone. Subsequent actual Twitch
signup and Rumble's official app authentication route explicitly rejected the temporary inbox
domain. These are later explicit responses, separate from the earlier undiagnosed form reset
and security-verification page. Kick's native email step reported “The email has already been
taken”; that response does not establish an existing account. Its corrected official host form
produced no captured successful signup/authentication response, with cause still unconfirmed.
No platform account was created. A regular email address or existing signed-in device session
is now needed; that question remains pending. No disposable-domain rotation or existing-credential
access was attempted. Safe evidence: `task-7/test-accounts/phone-session-notes.md`; no supplied
birth date or account identifier is recorded here.

Rumble's actual visible fullscreen button now establishes physical landscape rendering:
settled 2712×1220 screenshots show moving live video/countdown 01:35:34→01:35:13; the
16:55:00 UTC state records Rumble focus and ROTATION_90. Android Back returned to the portrait
player/chat with continuing video. This supersedes the earlier unsuccessful orientation-input
attempt, without adding an authenticated, audio or RN pass. The initial rotation-animation capture
is not settled landscape evidence; a playing UIAutomator timeout supplies no hierarchy proof
and does not invalidate the settled screenshots. Rumble was
stopped after capture, with no account, settings, network or global orientation change for this run.

Twitch's later public live channels supplied real incoming native-chat rows with timestamps,
badges and stock emotes. Temporary actual Lurk UI changes (Use native player off, text size 130%,
alternating rows on, highlight word “the”, timestamps off) produced visibly larger text, alternate shading,
purple highlighting on “Win the game predictions” and absent timestamps in
`phone/twitch-changed-live-settled.png`. That capture's XML timed out, so the visual result is not
an XML pass. After force-stop/reopen and normal Browse→Live Channels navigation to a public
channel with Use native player still off, the 17:08:43 UTC activity-component capture identifies original native
`AppCompatTextView`/`app:id/chat_message_item`. This proves the observed production route stayed
native: that switch removes Lurk's refusal, while the original server rollout still determines RN.
Physical RN is still open, and earlier controlled RN-route evidence is not relabeled. Visible
badges/emotes do not establish third-party-provider events or authenticated behavior.

Navigating to another public channel replaced the settled incoming chat with that channel's
messages. Temporary Android orientation settings established 2712×1220 full native video, then
portrait return showed current incoming styled chat; landscape chat was collapsed, so this is
not a column/transcript or RN pass. Earlier unsuccessful rotate-button inputs remain unproven.
Home and normal launcher return preserved the current channel and setting effects
(`phone/twitch-chat-after-background.png`). No old-channel overlay was visible in these bounded
captures; no exhaustive ownership/leak claim follows. No chat was sent and no account-mention,
moderation, physical vibration, blocking or provider-event behavior was newly established.

Fresh actual Lurk UI verifies all five changed preferences restored: Use native player=true,
text size=Twitch default, alternating rows=false, highlight words empty/Not set, timestamps=true.
Timestamp format stayed 24h; keyword/appearance parents and blocked-word/user/vibration/provider
controls were unchanged. The settled `phone/twitch-chat-restored-live-settled.png` shows current
incoming rows with smaller default text and timestamps, no alternating shading and no highlight
on a recurring automated row containing “the”. The earlier unsuffixed restored screenshot shows
Browse, so it is not the restoration's chat evidence.

At 17:16:03 UTC, device settings matched this resume's baseline: USB stay-on=0,
accelerometer=1, user rotation=0, timeout=1800000 ms. The user's newer timeout was preserved;
the earlier phase's 300000 ms was not reapplied. This continuation made no brightness, media
volume or network/VPN writes. At 17:17:20 UTC the phone was USB-authorized and unlocked
(deviceLocked=0), all four packages had no PIDs, and tested playback was stopped. Go Live has
never been launched. No intentional relock, platform account creation, source/APK/build/review
change or publication occurred. Regular email/existing-session, verification/entitlement, audio
and physical RN/authenticated gates remain open.

One normal permanent-inbox attempt at Proton Mail Free reached
[Human Verification](https://proton.me/support/human-verification). The paid upsell was declined;
the offered Email alternative using the owned test inbox returned to the same form after loading,
with no code-input/success state and no received message in bounded checks. Cause is unconfirmed,
not an explicit Proton domain rejection. CAPTCHA was not interacted with or bypassed. No permanent
inbox or platform account was created. The dedicated browser exited and its profile was removed;
this host-only attempt changed no phone settings or sessions. Regular email/existing sessions or
normal user-completed verification remain prerequisites; other open gates are unchanged.

## Required remaining gates

- Complete remaining physical native/RN/composer/gesture, audible playback and lock-screen
  checks on final APKs. The bounded guest native/transport/PiP evidence above does not establish
  every required physical path or named account behavior.
- Establish named account sessions through normal on-device flows using a regular email for
  authorized test-account creation or an existing signed-in device session, resolving required
  verification prerequisites:
  Twitch authenticated chat/points/Amazon/member flows; Kick physical authenticated navigation/
  playback and stock broadcaster token handoff; Rumble authenticated/Premium playback. Required
  entitlements remain unavailable. A read-only/test-channel equivalent must prove the named
  behavior. Sending real chat, spending points/money and subscription changes need specific scope.
- Task 8 publication/CI/prerelease/stable/backmerge verification after all required evidence.

Real notification delivery, live wire payloads/queue drain, provider-admin mutations and audible
emulator playback are unclaimed evidence limits. The privacy investigation and actual SDK-state
matrix above satisfy their stated investigation/state scope without silently converting those
additional unobserved behaviors into passes.
