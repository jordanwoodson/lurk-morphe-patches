# Twitch patch work

## Objective and current state

Apply the same workflow used for Rumble and Kick: inspect the original app, implement
the viable equivalents of the existing patches, verify the resulting APK and runtime
settings, and publish a tested stable Lurk release through the repository workflow.

The supported target is `tv.twitch.android.app` **31.7.0**, version code **3107006**.
The patches were written against 31.6.0 (3106006) and moved to 31.7.0 on 2026-10-21. Two APKMirror
bundles of 31.7.0 were added that day with `bin/apk-add`, which verified their signature and stored
them in `apks/twitch/31.7.0/`: the arm64-v8a 480-640 dpi bundle for phones (Android 10+, the default
input) and the arm64-v8a, x86 and x86_64 480 dpi bundle (Android 8.0+) that emulator installs pick.
The phone bundle's base APK SHA-256 is
`4c1e1bb8e75b52d964310cb74ec7c10587ae8eb7fed1efa4959a5e88098c9c56`.

Allowed sources are APKMirror, Google Play and Uptodown (see [CONTRIBUTING.md](../CONTRIBUTING.md)).
The variants used are listed here:

- [APKMirror release and variants](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch/twitch-live-streaming-31-7-0-release/)
- [APKMirror arm64-v8a bundle](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch/twitch-live-streaming-31-7-0-release/twitch-live-streaming-31-7-0-4-android-apk-download/)
- [APKMirror arm64-v8a + x86 + x86_64 bundle](https://www.apkmirror.com/apk/twitch-interactive-inc/twitch/twitch-live-streaming-31-7-0-release/twitch-live-streaming-31-7-0-2-android-apk-download/)

What changed from 31.6.0 for the patches: the React Native bundle (still Hermes v98) renumbered its
strings, functions, literals and registers, and the React Native player moved its player, metadata,
stream URL and release handling from `TwitchIVSPlayerView` into a new `PlaybackSession`. Patterns
that carry string IDs now resolve them from the bundle (`HermesBundle.id8`/`id16`). The edits whose
registers, offsets or memo-cache slots are the compiler's (Hide Clips in `DiscoveryFeedContent`,
the player's Share button) were re-derived from 31.7.0's disassembly, and the version-pinned tests
that name R8-renamed classes were moved to 31.7.0's names.

APKMirror reports the signing certificate SHA-256 as
`1338f9b049893cc70b78432a177582f90bd4bc6296ea4ed35bcc7df59687ac53`.
The supplied APK's verified signature matches this certificate, which is now pinned
in `twitch/shared/Constants.kt`. Decompiled Java, smali and resources are under
`~/build/lurk-morphe-patches/twitch/31.7.0/` (`bin/decompile twitch`). Twitch also embeds a Hermes v98 React Native bundle
and web-view JavaScript; native screens alone do not cover every feature.

## Coverage

The table below covers the 13 Twitch patches that match the Rumble set. The ones added after
comparing Lurk with other Twitch bundles are in [Patches beyond the Rumble set](#patches-beyond-the-rumble-set). These are original patches
based on this APK; the Rumble and Kick fingerprints do not apply directly.

| Rumble patch area | Twitch equivalent and boundary |
| --- | --- |
| Change app name | Opt-in launcher label, default `Twitch Lurk`. |
| Lurk icon | Purple Lurk artwork, adaptive/round/monochrome launcher resources. |
| Remove tracking permissions | Removes the two present advertising-ID and Play install-referrer permissions. This alone does not stop other identifiers or telemetry. |
| Clone app | Opt-in package `tv.twitch.android.app.lurk`; renames all nine provider authorities, including Amazon's suffix, and the app's signature permission. Amazon sign-in and verified app links are unverified after re-signing. |
| Disable analytics | Native/RN Spade, local vendor consent decisions, ComScore permission, Branch events/tracking mode, Sentry and Crashlytics collection controls. Details below. |
| Clean share links | Native/RN share intents and clipboard paths; removes known tracking parameters only on Twitch-owned hosts. Keeps timestamps, unknown parameters, fragments and unrelated URLs. |
| Disable autoplay | Separate switches for fresh feed-preview defaults and automatic next-video/clip advancement. Manual playback stays available. |
| Default video quality | Opt-in default rendition, Source initially. Saved choices including Auto win; unavailable renditions use Twitch's fallback. |
| Hide Shorts | `Hide Clips`: Home discovery and native channel-profile Clips tabs. Clip routes remain present. |
| Hide tipping and Rants | `Hide Bits tipping`: purchase/cheering controls, including native and RN chat buttons. Received chat messages stay untouched. |
| Hide Premium upsells | `Hide subscription upsells`: Subscribe/Gift prompts and Turbo promotions. Existing subscriber status and native member-management rows remain. |
| Block cookie notice | Hides the automatic notice only. Does not accept consent, change saved consent or disable manual privacy settings. |
| Block ads | `Block client ads`: takes the existing native client ad-decline path and completes its result. Does not remove ads stitched into streams or cover every RN ad path. |
| Unlock Premium | No patch. Local Turbo state exists, but no useful equivalent entitlement change was established. Signed playback/account benefits are server controlled. |

## Patches beyond the Rumble set

These were added on 2026-10-15 after comparing Lurk with other Twitch patch bundles and mods at the
feature level. Each is written from 31.6.0's own code.

### Feature checklist

Every feature from that comparison that Lurk didn't have, and what covers it now. The sources compared
were ReVanced, the community Morphe bundles (hoomans, uyu, Kizu, ryykitty, Canic), PurpleTV and
ReVive, bttv-android, TwitchMod, Xtra, Twire and Frosty.

| Feature in other bundles | Lurk |
| --- | --- |
| Stitched (SSAI) ad handling without a proxy | Avoid stitched ads (picture-by-picture stream during breaks) and Mute stitched ads (fallback) |
| Proxy ad blocking | Playlist proxy (opt-in, own URL) |
| Hide display ads, audio ads | Block display ads |
| Fix login / notifications in re-signed builds | Fix notifications. Sign-in has no signature gate (see below). |
| Disable Bugsnag | Not in 31.6.0. Nielsen and Sprig, the remaining reporters, are now in Disable analytics. |
| Disable Google Play services | Declined |
| Anonymous chat | Anonymous chat |
| Show deleted messages, ignore chat clears | Show deleted messages, with Ignore chat clears |
| Chat timestamps | Chat timestamps |
| Highlights and blacklist | Chat keywords |
| Chat appearance | Chat appearance |
| 7TV/BTTV/FFZ emotes | Third-party emotes (opt-in) |
| Third-party badges and pronouns | Third-party badges, with pronouns (opt-in) |
| Recent chat history | Declined: Twitch already loads it |
| Danmaku | Chat overlay (opt-in) |
| Hide Hype Train, leaderboards, gift and sub banners, pinned cheers, watch streaks | Hide chat highlights |
| Background audio | Background audio |
| Sleep timer | Sleep timer |
| Refresh stream | Refresh stream |
| Brightness/volume gestures, seek interval | Player gestures |
| Hide Clip/Cast/Share buttons | Hide player buttons |
| Video stats / latency | Show video stats |
| Disable low latency, force ExoPlayer | Normal latency, Force ExoPlayer |
| VOD chapters | VOD chapters |
| VODHunter (sub-only and deleted VODs) | Declined |
| Default startup tab | Default startup tab |
| Hide Home sections | Hide Home sections (and Hide Clips now covers the native Home tabs) |
| Hide mature-content warning | Hide mature content warning |
| Tablet UI | Tablet layout |
| Navigation bar position | Navigation bar position |
| Auto-claim channel points | Auto-claim channel points |
| Twitch debug mode | Declined |
| Twitch's newer React Native player bypassing native hooks | Use native player |

Two patches have no counterpart in those bundles. *Watch without signing in* opens Twitch's own
signed-out mode. *Fix stuck start screen* fixes a stock start-up stall. The reasons for each decline
are under [Declined](#declined).

### Watch without signing in

31.6.0 still ships Twitch's signed-out browsing mode. It sits behind the Eppo `loggedOutExperiment`,
which is unassigned by default. Three places decide between the main UI and `LoginActivity`: the
intent router, `ViewerLandingActivity.onCreate` and the settings screen. All three call the
experiment's one boolean getter: false while signed in, otherwise whether the device was given a
variant. The getter's class is found by its variant strings (`variant1` … `variant7`, `no_nudges`).
With the switch on, a signed-out device counts as in the experiment. The signed-in check runs first,
so signed-in use doesn't change.

Signed out, requests carry only Twitch's Client-ID and device ID, chat connects as an anonymous
`justinfan` reader, and following, chatting and other account actions open Twitch's own sign-in
prompts. Verified on the emulator:
- with the switch on, launch reaches `ViewerLandingActivity`, Home plays a live stream, a channel's
  native theatre plays with its chat, and Browse works;
- with it off, launch returns to `LoginActivity`.

This mode also makes signed-out playback and chat testable on the emulator.

### Fix notifications

Every Morphe-patched APK is re-signed. Firebase Installations checks `X-Android-Package` and
`X-Android-Cert` against Twitch's API key restrictions, so the re-signed app gets
`403 Requests from this Android client application … are blocked`. Push registration then never
completes. Checked against the live endpoint:
- re-signed certificate: 403;
- original certificate with `tv.twitch.android.app`: 200;
- the clone's package name: 403 even with the original certificate.

The patch sets both header values right before the two `addRequestProperty` calls in the
Installations client. The client is found by its three header strings. It sends the original package
name and the original certificate's SHA-1 (`8C68C138…9463`, from the pinned certificate).

Verified on the emulator: the clone's `PersistedInstallation` moved from `FisError: BAD CONFIG` to
status 3 (registered) with auth and refresh tokens, and an FCM token was stored. Delivering a
notification needs an account.

No client-side sign-in gate depends on the signature:
- Kasada `Client-Integrity` is attached only when available.
- Play Integrity "passport attestation" is a delayed background report whose failures are only
  logged.

### Default startup tab

The bottom bar is Home, Browse, Create, Activity and Profile. Following is not a tab. A plain launch
reaches the intent router (the method writing `destinationOrdinal`, with `intent_router`) with the
`Default` destination. The patch replaces only `Default`, with `Browse`, `Following` or
`NotificationCenter` looked up by name in the same enum. Links keep their destination.

Changing the bar's own default tab is not an option: the navigation host requires Home as the start
destination (`Must use NavGraphFragment resolver for start destination`). Verified on the emulator:
Browse opens at launch.

### Trackers in Disable analytics

31.6.0 has no Bugsnag, Facebook SDK, SafetyNet or Amazon APS. Disable analytics now also:
- turns Nielsen's server-to-server ping into a no-op. That is the method calling
  `NielsenS2SService.firePing`, a GET to `imrworldwide.com` with the advertising ID;
- skips `UserLeap.configure(Context, String)`, so Sprig surveys and tracking never get an
  environment. Every public `Sprig` method has zero spare registers, so the facade can't be guarded
  directly.

A blanket "disable Google Play services" switch is deliberately not offered. It would break push,
Google and FIDO sign-in, Cast and billing.

### Background audio and Show video stats

Twitch already has "Continue Playing Audio in the Background" (Always, Headphones/Speaker, Never),
with its own audio-only switch, `TwitchPlaybackService` foreground notification and media session.
Its default is Never. Every theatre reads the setting through one getter, and the patch rewrites that
getter's `"never"` fallback. A saved choice still wins. Verified on the emulator: leaving a live
stream with Home keeps the media session `PLAYING`, with an active media `AudioTrack` and Twitch's
transport notification.

The native player's stats panel (latency, bitrate, buffer, dropped frames, codecs, node, cluster)
is gated by one getter: staff account plus a debug preference. The getter is found by
`showVideoDebugPanel` and a `SharedPreferences.getBoolean` call, and the switch returns true from it.
Verified on the emulator: an (i) button appears in the overlay and opens the panel. Twitch's staff
debug menu is not exposed. Faking staff status would change experiment targeting and expose
network-altering tools.

### Use native player

Live channels open in the React Native "Ultralight" theatre only when
`RNTheatreRouteDecisionKt.rnTheatreRouteDecision` returns `Route`. That needs the Eppo flag
`android-ultralight`, which is off by default and server-controlled. Most Lurk playback, chat and
ad patches hook the native theatre. The method has no spare registers, so while the switch is on its
`isLiveChannel` parameter goes through `TwitchPlayback.routeLiveToReactNative` and reads false.
Twitch answers `RefuseNotLiveChannel`. Its two callers (`TwitchRNTheatreLauncher`,
`TwitchRNHostNavigationModule.routeChannelOpen`) treat every refusal the same way and open the native
theatre. VODs and clips are always native.

### Fix stuck start screen

On the emulator, stock Twitch stalled on its blank `LandingActivity` in about 1 of 4 cold starts: 2 of
8 stock runs, and the same rate with Lurk's patches. `LandingActivity` routes only when two RxJava
subjects have both emitted: Branch's app-open result and the install-referrer properties. When
Branch's session callback never fires, the activity stays up with an idle main thread until the app
is closed.

The patch makes the routing method (the one logging `parse_launcher_intent`) run once per activity.
`onCreate` schedules a 5-second fallback that calls it if the activity is still up and hasn't routed,
and a late Branch answer is then ignored. With the patch, 16 cold starts gave 0 stalls; the fallback
fired twice.

Implementing this exposed a patcher pitfall. A non-range `invoke-static { p0, v0 }` in a method whose
`p0` maps above v15 (here v30) is dropped from the output without an error or warning. Injected
calls therefore use `/range` or low registers, and a scan of the patched DEX lists every extension
method that app code calls (`~/build/lurk-morphe-patches/twitch-gap/scripts/ext_calls.py`).

### Player: sleep timer, refresh, gestures and latency

The player patches hook IVS `CorePlayerImpl` (the SDK's names are kept) and Twitch's own control
paths. Calls into obfuscated Twitch methods go through empty extension stubs whose bodies the patch
fills in.

*Sleep timer* re-arms on every `play()`. It pauses through the Guest Star pause-all registry (native
and portal) and through `TwitchMediaSessionModule`/`FeedPlayerRegistry` (React Native), so background
audio can be resumed from its notification.

*Refresh stream* calls the error screen's Retry, `N1(0, true)`. For live that fetches a new token and
playlist; a VOD keeps its position. It's triggered by a long press on the settings gear of the live
(`player_control_overlay`) and VOD/clip (`player_control_overlay_widget`) overlays, or a long press on
the middle of the video.

*Player gestures*:
- change the three VOD skip constants, and swap Twitch's "10"/"30" icons for arrow-only copies made
  at patch time;
- add brightness and volume swipes through `DraggableConstraintLayout`/`ConstraintTheatreContainerView`,
  landscape only, leaving the middle fifth for drag-to-minimize.

*Normal latency* sets `setLiveLowLatencyEnabled(false)` before native loads and overrides the React
Native and portal calls. On the emulator, latency rose from about 2 s to 6–7 s.

Verified on the emulator, signed out, in the classic theatre. The React Native, portal and vertical
theatres are checked statically only.

### Display ads, stitched ads and the playlist proxy

*Block display ads* wraps the four lazy creators of Twitch's `AdEdgeApi` Retrofit service
(`https://edge.ads.twitch.tv/`) in a proxy. The proxy returns `Result.error(IOException)`, Retrofit's
own network-failure result, which every native ad caller already treats as "no ad". That covers
stream display (squeezeback, lower third), audio, multi-format, headliner and feed ads. In React
Native:
- requests to `edge.ads.twitch.tv` fail inside `NetworkingModule`;
- the RN VAST player reports Twitch's `noAd` error;
- two equal-length Hermes edits remove RN feed ad slots (`extractAdSlots`) and FeedAd cards
  (`isEdgeVisible`).

Brand surveys, which replace unfilled feed ads, are dropped. Sponsorship and paid-promotion labels
are untouched.

*Mute stitched ads* watches each player's EXT-X-DATERANGE cues: the native player's `h(JSONObject)`
handler and the RN player's `PlaybackSession.handleMetadata`. Each RN player view owns one session and
is its display; the patch adds two public static helpers to the session, because its display and
player getter are private and `handleMetadata` has one free register. From `twitch-stitched-ad` until `twitch-stream-source`
= `live`, it mutes the IVS player through `setMuted` and covers the video in black. Twitch itself mutes
with the volume, so the user's own mute is untouched. A break also ends on a new stream, a release, or
a timer set from the ad's duration (capped at 240 s). The ad still plays for its full length.

*Avoid stitched ads* (on by default) is based on measurements taken on 2026-10-15, signed out, with
the app's Client-ID:
- A commercial reaches every full-quality session that's open when it starts, whatever the player
  type or platform (mobile_player, popout, embed, site, frontpage; android, web, ios).
- Sessions opened during a commercial get join-in ads within seconds.
- Android `picture-by-picture` and `autoplay` sessions got none, but stop at 360p.
- Only two commercials were observed in about 2.5 hours, so this rests on a small sample.

No full-quality player type avoids breaks, so the patch doesn't change the player type. Instead it
hooks IVS's Java HTTP client (`OkHttpClient.execute` for the request URL, `ByteBufferConsumer.consume`
for the body), which every Twitch player uses. When a media playlist's next segments are ads:
- it serves the playlist of an anonymous picture-by-picture session instead, renumbered by program
  date-time and marked as a discontinuity, so the player doesn't reload;
- it checks the stream's own playlist on each refresh and switches back once that is live again,
  without the old ad cues;
- a break past 4 minutes, an unreadable playlist or a backup that stops answering ends the switch;
- without a backup, Mute stitched ads applies.

Playlist reads and backup discovery share a six-second monotonic budget, including queued work,
headers and continually progressing bodies. Compressed and expanded bodies are limited to 1 MiB.
Three I/O workers and eight pending operations bound stalled providers; cancellation releases the
caller even if a provider ignores interruption. Workers check cancellation before further reads and
chained requests. An expired discovery retains the already-read ad playlist so the existing mute
fallback still applies. Delayed responses cannot restore a retired source or regress the media
sequence; when a newer source cannot be fetched, the last accepted window is reused. If that rendition
has no accepted window yet, the consumer reports an error without delivering a retired or manufactured
empty playlist. Native IVS probes found repeated synthetic empty windows cause rapid polling.
Provider I/O runs outside the short stream state monitor; source generations prevent late workers
from overwriting a newer transition, and request hooks stay usable while old I/O remains blocked.
`tests/twitch/test_async.py` verifies these paths with local delayed/trickling HTTP and controlled
IVS providers. A permanently blocked platform provider can retain one worker until it returns;
the worker count stays bounded and saturated callers fail promptly or use the existing body.

`bin/test-twitch-ad-backup` runs the hooked consumer on ART against live Twitch playlists. A test
build with a faked break went 1080p → 360p → 1080p without a reload. No real commercial was caught
in the app.

*Playlist proxy* is opt-in and does nothing without its `proxyUrl` option. It sends live usher requests
to the configured proxy, and the proxy receives the signed playback token.

`bin/test-twitch-ads` runs these hooks on ART. Signed out, no display or stitched ads were served,
so real ad delivery is verified only through the probe and the emitted bytecode. The proxy was checked
end to end with a local test proxy.

### Interface: chat highlights, player buttons, Home and mature labels

Native Home is not React Native: RN Home and the Ultralight theatre run only with `android-ultralight`.
That includes the signed-out Home. *Hide Clips* therefore also filters the native Home tab provider at
its three users, keeping Clips when Home opens on a clip.

*Hide chat highlights* covers:
- the native highlight dispatch, by type ID (Hype Train, gift banners);
- the chat-notice mapper, by event and subscription kind;
- the private-callout dispatch (watch streaks);
- the leaderboard, pinned-cheer and celebration state constructors;
- in RN, early returns from the matching services, components and `LibraryTmiClient` handlers.

*Hide player buttons* filters the clip button state and live overlay options, every AndroidX
`MediaRouteButton` (Cast), and the VOD and vertical-theatre share buttons. *Hide Home sections*
clears the `ShowStoriesShelf` argument and filters the Following page's section list. *Hide mature
content warning* makes the label-acknowledgement check return true without saving anything; age,
region, guardian and account gates still apply.

*Hide Bits tipping* now also filters `ConfigureMessageInput(isBitsPickerVisible=)`. The classic chat
input reads its Bits button and tray from that state, not from the toolbar states.

`bin/test-twitch-highlights` runs the real classes on ART with each switch on and off. Following
sections, Hype Train and the RN paths are verified only statically.

### Chat

*Anonymous chat* (off by default, restart) logs the native IRC socket in as a `justinfan` guest with
`PASS listen`, while the rest of the app stays signed in. The credential fields are found through the
`UserCredentials(nickname=, oauthToken=)` toString. React Native's `chatAuthOptions` returns its guest
login. The input shows Twitch's log-in restriction relabelled "read only", and the `SendChatMessage`
mutation returns Twitch's own failure result, so nothing posts under the account.

*Show deleted messages* keeps the text of deleted or timed-out messages, struck through. It covers
native lines already shown and messages that arrive deleted. React Native retention is limited to
verified received, ordinary chat bodies, including lines still waiting in the original chat-delay queue.
It preserves deleted flags and timing, suppresses content actions on newly retained rows, and leaves
hidden, unpublished, masked, AutoMod and low-trust content protected. Missing or invalid runtime helpers
use the original delayed-drop predicate. The setting requires restart. *Ignore chat clears* treats a `CLEARCHAT`
without a target user as a no-op, in native and React Native chat; timeouts and bans still apply.

*Chat timestamps* turns on Twitch's own history timestamp for live channel chat, not whispers, with a
24/12-hour choice. *Auto-claim channel points* (off by default, needs an account) calls the bonus
chest's own claim action once per claim ID.

`bin/test-twitch-chat <apk>` runs the real classes with each switch on and off. Signed-in IRC login
and bonus claims are verified only through bytecode and the probe.

*Chat keywords* highlights native and React Native chat lines that mention you (your signed-in login) or contain one
of your highlight words, with a tinted row and an optional vibration. It hides lines with blocked
words or from blocked users. The word and user lists are Lurk's first text settings: a `TextSetting`
in the registry JSON, edited in a dialog on the settings screen. Words match as whole words in any
case. Blocked live messages are dropped at the chat list feeder, before the Chat overlay sees them.
History and VOD replay lines are marked by the shared line builder (`ChatMessageSpanGroup`) and
collapsed by the row binders.

*Chat appearance* sets:
- chat text size;
- alternating row shading, numbered by arrival and skipping hidden lines;
- plain /me messages;
- the landscape chat column's width (the theatre guideline) and background opacity.

React Native wrappers use the actual row, body, timestamp, text-metrics, selector and landscape-column
exports. History filtering preserves the original store and stable snapshots. Live keyword vibration
subscribes only while its store is mounted; history, deleted lines and rerenders do not notify.
All-off restores the original exports. `bin/test-twitch-chat-style` checks native styling on ART,
including mentions with a fixture account; `bin/test-twitch-rn` checks native RN bridge ownership.

The original floating one-chat overlay projects away the message type and omits store/channel props.
Its original host now publishes its genuine store and exact numeric destination/login through
tree-local React context after commit. Projected rows must match the canonical original line's
id/body/user/userId/source-room/deleted/hidden/unpublished fields before using provider or received
deletion privileges. Immutable authority rejects a changed destination before effects run; cleanup
retires only its own publisher. The original store hook runs once and returns unchanged, and the
host component type/key stays stable so drag/collapse/scroll state survives. There is no extra
network request, timer or global render provenance.

The column width/opacity settings target `landscape-column-chat`, reached through the original
video double-tap cycle; floating one-chat retains its stock opacity. Nondefault column opacity
uses the existing empty background as black and existing foreground as theme color with alpha,
then clears only the two measured covering fills in ClassicChatColumn/ChatArea. Header/composer,
text/emote colors, player, gutters and actions keep their original styles. A stable React Provider
carries primitive opacity or neutral null without adding a native View or remounting chat when
opacity changes. While wrappers are installed, neutral paths preserve original native styles and
child objects, rather than literal outer React object identity; full configure-off restores the
original exports. Unknown shapes keep stock handling. The
[dated verification record](verification-2026-10-17.md) distinguishes actual visible opacity
controls from the earlier style-only readback, and real provider images from controlled cached
badge/pronoun metadata.

The original RN live store is keyed by channel login. Keyword notification ownership preserves that
bounded login exactly; provider row acquisition independently uses the numeric channel/source-room ID.

The pinned live RN owners are discovered by unique exports in Hermes98, with an additional compiled
bundle installed before consumers render. Both embedded and prepared main bundles retain
`assets://index.android.bundle`; ReactInstance and native-module teardown retire their own state.
The source/deleted-predicate bundles use the pinned official compiler. The original122-byte delayed
callback cannot hold the guarded fallback, so a verified241-byte compiled body is appended with its
full37-byte header and exception metadata. Off restores the original header, length and checksum.

VOD navigation is positively native: `TwitchRNHostNavigationModule.openVodInternal` constructs a
`PartialVodModel` and invokes the native theatre router. Native replay token/binder hooks apply there;
no separate RN replay renderer was established. Signed-out RN smoke uses an isolated final rollout
routing override, preserving earlier route/auth gates, followed by exact production restoration.
Actual guest Fabric rows and actual-store local moderation fixtures establish consumer integration;
these fixtures do not establish authenticated server moderation, sending, or physical-phone rendering.

### Third-party emotes and badges

Both are opt-in: every switch starts off, and its summary names the hosts it contacts. Emotes hook
Twitch's own whole-word matcher (`clientParsedEmotes`). They add 7TV, BetterTTV and FrankerFaceZ
emotes as Twitch emote objects with synthetic `lurk…` IDs, and the central emote URL builder maps
those IDs to each provider's CDN. Animated emotes use GIF; static ones, or all of them when Twitch's
animated-emotes setting is off, use PNG.

VOD replay splits its named message tokens. React Native chat gets `emotes=` ranges added to incoming
IRC frames, and its `ImageSource` maps the same URLs. Compiled RN row wrappers additionally enrich
history with cached emote/badge/pronoun snapshots. Offsets use Unicode code points, matching Twitch's
segmenter, and native emotes retain precedence. Provider effects explicitly acquire/release demand;
reading a catalog or IRC frame never schedules requests. One runtime timer polls a small native
revision token every two seconds while committed provider rows exist; unchanged tokens do not refresh
rows or fetch catalogs. Cache publication, badge/pronoun image availability and switch changes invalidate
mounted rows. Last release, all-off and module disposal stop polling. Lists are fetched in background workers;
failed/missing data leaves codes as text. Queues, per-room caches and snapshots are bounded. Queued
work rechecks switches and surviving owners before each new request, preserving shared native demand.

Badges download FrankerFaceZ's and Chatterino's public lists of badge holders and match them on the
device, so no chatter is looked up. The exception is *pronouns* (`badges_pronouns`, off by default):
pronouns.alejo.io has no bulk list, so the username of each chatter whose message you see is looked
up there.
- Answers, including "none", are cached for an hour, for up to 2000 users.
- Duplicate lookups are merged, at most 4 requests a second.
- A chatter's first line can appear before the pronoun is known.
- Pronouns are drawn as a small text badge.

*Chat overlay* (off by default) scrolls live chat right to left across the top of the video, like
danmaku. It's fed from the native chat list feeder, found through the `LiveChatMessage(messageId=`
toString. The overlay is a plain view on `android.R.id.content` that ignores all touches. It attaches
only in landscape outside picture-in-picture while a video surface covers at least half the width,
and detaches in portrait, in picture-in-picture, when the video goes away, or after 15 s without
chat. The exact viewer feeder owns posted messages. Its stream-model observer retires the former
destination without waiting for another message, and onDestroyView retires that feeder without removing
a newer viewer's overlay. A recreated view can reuse the feeder with fresh state. This takes effect at
the measured model emission or teardown, not before a navigation intent reaches either boundary.
React Native chat, VODs and chat replay don't pass through that feeder.

Twitch already loads recent chat history on join (native
`ChatHistory`, RN preload) and replays VOD chat, so a third-party history service isn't used.

### VOD chapters

*VOD chapters* (on by default) adds a Chapters button next to the rotate button in the native VOD
player's overlay. It reads the video's `moments(momentRequestType: VIDEO_CHAPTER_MARKERS)` (position,
duration, game) from gql.twitch.tv with the app's Client-ID only, since chapters are public. This
shares one background request per video between prefetches and open overlays. Two chapter jobs run
at once with eight queued; successful results and failure backoff each retain at most 32 videos.
Failures retry after 15 seconds. Each load keeps at most eight weak owners, and at most 32 pending
buttons can wait for a dialog. Requests use the same six-second monotonic budget and 1 MiB body
limit as playlist I/O. Tapping a row seeks through Twitch's own VOD seek.

Feed previews also load VOD presenters, so the button finds its video through its own overlay: the
presenter whose visible player view lies under it (captured in the base presenter's view binding),
mapped to the video it loaded. `VodModel.getId()` carries a `v` prefix that GQL rejects, so the
prefix is stripped. Pending dialogs check the original presenter, video, overlay attachment and
current setting before appearing. Player-view detach callbacks retire both prefetch and open demand on the main thread; an old
attachment cannot retire its replacement. Navigation and disabling chapters retire queued/provider work;
results remain shared when another owner still needs that video. `tests/twitch/test_vod_requests.py`
covers sharing, retry, eviction, queue/waiter bounds and stale delivery using platform HTTPS, JSON
and UI doubles; it does not verify Twitch's live GraphQL schema or actual Android rendering.
Verified on the emulator: the dialog listed a VOD's three chapters, and tapping
the second seeked to 26:29.

### Tablet layout, navigation bar position and Force ExoPlayer

*Tablet layout* makes Twitch read its tablet resources as true. `isTablet` switches the form factor
to Tablet, so Home and the player rotate and the player shows chat beside the video in landscape.
`is_tablet` only affects the cast dialogs. `following_drawer_use_sidebar` gives the navigation rail,
in landscape only unless "Navigation rail in portrait" is on: signed in, Twitch opens its 320 dp
Following sidebar next to the rail, which would leave almost no room in portrait.

*Navigation bar position* inflates a copy of the main layout with the bar anchored at the top and
hands the gesture-bar inset to the screen, or hides the bar's labels.

*Force ExoPlayer* sets `PlayerConfiguration.forceExoPlayer` for HLS content. Live channels and
videos then play on Twitch's dormant ExoPlayer 2 backend, which still sends `player_backend=Exo2` to
usher; Twitch itself only uses it for MP4 feed and story items. Clips and encrypted streams stay on
IVS. Lurk's IVS hooks (Normal latency, Mute stitched ads, video stats, quality names) don't apply to
ExoPlayer. `androidx.media3` in the APK is the React Native transparent-video view, not playback.

All three were verified on the emulator signed out. The Following sidebar is checked only
statically.

### Declined

These features appear in other Twitch bundles and mods but are deliberately not built:

- **Twitch debug mode** (ReVanced): 31.6.0's debug menu (`debug_app_actions`, RN `canReachDevTools`)
  is staff-gated, and release builds fold the debug-build check to false. Reaching it means passing
  the app off as a staff account, or bypassing the staff gate on a menu whose tools change experiment
  assignments, GQL and Spade endpoints, latency and ad behaviour on Twitch's service. *Show video
  stats* covers the debug view viewers use, the player stats panel.
- **Disable Google Play services** (PurpleTV): Play services carries push (see *Fix notifications*),
  Google and FIDO sign-in, Cast, Play Integrity attestation and billing. A blanket switch would break
  them; the analytics SDKs that use it are blocked individually instead.
- **VOD unlock / VODHunter** (PurpleTV: sub-only and deleted VODs): not implemented. Building the
  sub-only part was stopped by the assistant's safety checks, so it was left out. Recovering deleted
  VODs doesn't work in any case: for broadcasts whose VODs are gone, every VOD CDN host answered 403
  for the computed playlist path, so the segments are removed.
- **Third-party recent chat history** (PurpleTV, Xtra: recent-messages.robotty.de): Twitch already
  loads its own recent messages on join (native `ChatHistory`, the RN preload), and VODs replay their
  chat. A third-party source would duplicate lines and send each joined channel to another service.

## Implementation evidence

### Privacy and client ads

Native Spade dispatch completes the nullable success callback exactly once rather
than leaving callers waiting. The native batch transport is also guarded. RN's
`createSpadeReporter` retains its object/API while enqueue and flush return their
normal synchronous `undefined`. Local SDK consent lookup returns the actual Denied
enum without writing the account's consent model. ComScore's local permission is false.

Branch retains its SDK request/callback state machine with tracking-disabled mode
forced on and explicit event reporting suppressed. That mode permits some link
requests; the patch does not claim zero Branch traffic. Sentry's three startup
providers and explicit initialization are guarded. Crashlytics' automatic collection
arbiter and both collection setters are disabled, along with explicit report-send,
log, exception and user-ID methods. The `setCustomKey` overloads have no spare register,
so the internal method they all call is guarded instead. Its fatal handler may still retain local crash
files. Firebase's initialization provider and messaging remain intact. No
Firebase Analytics implementation was found in this APK.

The cookie patch changes only `ConsentOptions.shouldShowNotification`. Manual
settings, vendor values, age restrictions and DMA fields retain their original
values. Pair this with Disable analytics when local SDK restrictions are desired.
The ad patch targets one branch of a merged RxJava callback and returns the same
`Optional<Boolean.FALSE>` as Twitch's own `decline_all_ads` path. Other synthetic
branches remain unchanged. Authenticated streaming is needed to measure its actual
ad coverage; this is not a SureStream/server-side ad remover.

### Playback and sharing

Feed previews change the missing `autoplay_enabled` fallback to Never in both the
initial getter and observer. Existing Always, WiFi-only and Never values remain
authoritative. The next-video control is separate: it hides/cancels the native
recommendation countdown after binding the manual Play card, gates only the automatic
dispatch event, and disables the RN feed's automatic clip-advance hook. Manual
selection and swiping are preserved. This version has no implemented native getter
for the RN clip-advance preference, so Lurk's next-video switch controls that path.

Quality defaults affect missing native live/VOD/clip preferences, the last-used
video fallback after audio-only playback, and an absent RN `preferred-video-quality`
storage row. They never replace saved values.

The native player (live, VOD, vertical video) selects a rendition by its exact IVS
name, and Twitch names the source rendition like `1080p60 (source)`. Lurk's Source
choice is stored as `chunked`, the source's HLS group ID, which is never a name. While
a default is chosen, a hook at the start of the player's select-by-name resolves
`chunked`, or a named choice that is the source (`1080p60` for `1080p60 (source)`), to
the full name; exact names and Auto pass through. Requests made before the qualities
load are retried by Twitch through the same method. Native clips use `ClipQuality`
strings without frame rates (`source`, `1080`, `720`, `480`, `360`; 160p maps to the
smallest clip quality, `240`), and Twitch picks the closest one a clip has.

RN shares one exact rendition name across content types (after mapping `p30` to `p`);
if that name is unavailable, its existing effect resets to Auto and saves that. Source
has no fixed name, so it leaves the RN row absent and RN players start on Auto. Named
resolutions require an exact match, including frame rate, apart from the source suffix
on native players. This is not a nearest-resolution selector and does not force a
bandwidth minimum.

Sharing hooks cover six native/RN intent-producing methods and three clipboard
boundaries. They clean only `EXTRA_TEXT` among intent string extras and preserve
unchanged clipboard objects. Known UTM/click parameters and `tt_*` campaign fields,
including the `tt_click_id` UUID generated by this build, are removed. Timestamp
parameters, clip identifiers, unknown parameters and raw URL encoding are preserved.

### Interface and entitlement

RN Home filtering removes Clips from the tab labels, index model and pager children
together. Explicit clip requests, including initial requests with an empty Following
feed, use Twitch's existing native clip player. The initial effect retains its
deduplication so rerenders do not open the same clip repeatedly. Native channel tabs
filter the app's own `EnumEntries` through a read-only proxy, preserving the expected
interface and order. Explicit clip IDs, clip content and Clips profile scopes retain
their native tab. Native and RN Bits
flags are disabled, while dedicated purchase/tray/one-tap components return null.
Ordinary received chat messages are not filtered.

Subscription suppression distinguishes Subscribe/Gift states from existing
subscription state. RN Subscribe rendering retains the subscribed indicator through
normal, icon and cached-render paths. Dedicated Gift, Turbo and Theatre ad-upgrade
components are hidden. Native Turbo signup/startup offers and the account promotion
row are skipped; existing Turbo members retain their management row, and Subscriptions
and Drops remain. Intentional paywalls, direct purchase flows and other WebView
promotions are outside the tested hooks.

`UserModel.getHasTurbo` feeds stored profile/observer state and multiple consumers,
including portal state, promotion UI, player analytics and Drops. RN also queries
`hasTurbo`/`turboStatus` separately. Changing this bit cannot establish signed playback
or account entitlement, so no `Unlock Turbo` patch is advertised.

### Runtime settings and Hermes

Only included patches register controls in Lurk's non-exported settings activity.
Settings initialize before affected SDK providers and at Application startup, once
per process. Long-pressing the launcher icon opens the Lurk shortcut.

The original Hermes v98 bundle SHA-256 is
`50e73cce33e67272f9eb9a38b8d8e6f1ecd06ceccf4b88cc9dbc091743493fc9`.
Patch-time parsing pins that hash/version, verifies function boundaries and unique
instruction patterns, and permits only equal-length non-overlapping edits. The
runtime loader snapshots switches. When every switch is at its patch-time default it
loads the embedded asset as is and deletes any earlier private copy; otherwise it
validates the embedded hash and edit manifest, applies original/replacement bytes,
recomputes the Hermes footer and writes a private copy. All switches off must
reproduce the independently hashed stock bundle. Changes require a restart.

The shipped release `RNSourceModePolicy` disables Metro and selects the embedded
asset through `RNReactHostFactory` and `DefaultReactHost`; the file path argument is
null. The native asset loader is the hook. Although HotUpdater SDK classes are
present, no app call selecting a HotUpdater bundle URL was found, so no speculative
update bypass is added.

## Verification and limits

The test device is the disposable Android 16 x86_64 `emulator-5580`. Original
Twitch and the patched clone install together. Resource checks cover the label,
all icon layers, exact permission delta, nine provider authorities and their
protection attributes, component class identities and non-exported settings.
The patched APK passes signature and 16 KiB alignment verification; Android's
package compilation in verify mode succeeds.

The real settings activity displays included controls, publishes a launcher
shortcut, persists analytics off/on changes, and restarts into a new process from
its Restart Twitch action. Both states reach the same sign-in screen as stock
without fatal exceptions, missing-method errors or verification errors in the
captured startup logs. The native-only ad/consent selection has exactly two controls,
leaves the RN loader untouched and retains the original Hermes asset without a
manifest. The independent interface/playback selection also patches successfully.

Android probes execute the injected loader and actual app classes, not stand-ins
for the target methods:

- `bash bin/test-twitch <patched.apk>`: embedded defaults, process snapshot,
  independent switches and exact all-off reconstruction of the original bundle.
- `bash bin/test-twitch-privacy <patched.apk>`: real Spade callback once/null/off
  paths, completed client-ad emitter, Crashlytics upload permission and four
  dispatch guards. Disabled controls reach the deliberately empty original
  dependencies, proving they resume original execution.
- `bash bin/test-twitch-interface <patched.apk>`: real enum ABI, ordering,
  immutability and original-list identity, plus native Bits state constructors.
  `--stock <base.apk>` runs the original enum/chat negative controls.
- `bash bin/test-twitch-playback <patched.apk>`: real recommendation binding with
  next-video suppression off must reach the original progress-bar operation.
  `--stock <base.apk>` runs the original control path.
- `bin/test-twitch-interface-hermes <stock.bundle> [patched.bundle]`: production transforms and
  actual caller instructions for the two-tab model/pager, initial selection, swipe
  bounds, explicit native clip callbacks and empty-feed initial-effect deduplication.

The Python verifiers under `tests/twitch/` inspect emitted DEX and use the original
JavaScript/Hermes as behavioral inputs. They check callback completion, untouched
synthetic cases, saved/default quality and autoplay choices, automatic versus
manual playback, consent immutability, interface state and outgoing link boundaries.
An independent Hermes decoder checks all 28 edits across 21 functions, including
instruction boundaries and branches, with all five controls on, all off and each
on alone. `verify_interface_hermes_structure.py` also checks the 18 interface
functions and switch targets using the production interface probe's manifest.
`verify_playback_links.py`,
`verify_interface.py` and `test_privacy_consent.py` compile production Java helpers
with boundary fixtures. The existing Rumble and Kick Android probes also pass on
APKs made with the expanded patch bundle.

The [dated verification record](verification-2026-10-17.md) adds current guest native
VOD/replay/chapters/PiP and qualified RN consumer/provider/reload observations, with
artifact-specific provenance. The following account limits remain:
**signed-in playback and chat, sending messages, actual server ad delivery, purchases,
subscription/member screens and Amazon sign-in remain unverified.** Stock Twitch
requires Log In or Sign Up, including for a direct channel URL, and no test account
was available. Since "Watch without signing in" opens Twitch's own signed-out mode,
signed-out live playback, the native theatre and read-only chat can be checked on
the emulator. Bytecode and callback probes establish
specific local behavior, not complete authenticated end-to-end coverage. The
app also contains WebView code outside the covered native/RN presentation hooks.

Analysis, decoded APKs, build logs and emulator captures remain under
`~/build/lurk-morphe-patches/twitch/31.6.0/`, `build-twitch/`, `twitch-privacy/` and
`twitch-interface/`. The original upload is untouched.

## Implementation and release requirements

Use original, app-specific fingerprints based on strings, signatures and instruction
patterns. Do not copy third-party Twitch patches. Reuse the repository's shared Lurk
settings screen and extension infrastructure; register only settings for included
patches. Restore original execution when runtime switches are disabled. Features
read only during initialization must clearly require a restart.

Before each behavior change, establish a meaningful failing check against stock or
an isolated production helper. Verify emitted bytecode, register safety, return
contracts and callbacks as well as user-visible behavior. Test the full patch set
and independent combinations where dependencies could hide a failure. Use only the
disposable `emulator-5580` for runtime checks.

The release gate requires successful patching of the verified original package,
APK signature/alignment checks, startup and representative playback/chat checks,
settings toggling, and Rumble/Kick regression checks if shared code changes. Record
unavailable authenticated coverage explicitly. No unsupported feature should be
listed as implemented merely to match another app's patch count.

Work on `dev`. After local verification and review, push a semantic feature commit,
validate the workflow-built prerelease, merge `dev` into `main` without squashing,
and validate the published stable bundle and generated Morphe source metadata.
Do not edit generated release files or publish a release by hand.
