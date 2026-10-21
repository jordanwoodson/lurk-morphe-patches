# Kick

Static analysis of the supplied APKMirror bundle on 2026-10-14, compared with the 14
Rumble patches in this repository. **None of the existing patches applies to Kick
unchanged.** All declare Rumble compatibility; most also fingerprint Rumble Kotlin
classes or Compose UI. Kick uses React Native/Expo, Hermes bytecode and native
player modules. Several features are good candidates for independent Kick patches.

The assessment below, up to "Workspace result", was the starting point and is kept as
it was written. Twelve Kick patches were implemented from it, and a second batch,
inspired by Xtra and by Lurk's own Twitch patches, added more. The notes from
"Implemented patch set" on describe them and separate tested behavior from the
remaining limitations. The analysis below was done on Kick 40.32.1. The patches are now
checked against Kick 40.32.3, where the patched code is unchanged apart from renumbered
string, shape and literal IDs.

## Input and analysis

- Package/version: `com.kick.mobile`, `40.32.1`, version code `60006965`.
- Supplied bundle: `com.kick.mobile_40.32.1-60006965_1arch_2dpi_22lang_fd6d4ac6871fe5704e9fdc59b19d8d86_apkmirror.com.apkm`.
- Architecture: arm64-v8a; includes density and language splits.
- `bin/apk-add --copy` verified the base APK signature and pinned its certificate in
  `kick/shared/Constants.kt`: `b12320227268c9c37e190b493120a7f6ce83c0e9a761f9213df677b58d095b73`.
  This is a first-import pin, not a comparison against a previously trusted Kick certificate.
- Base APK SHA-256: `d4e58dc6fac60d983608fda6338c489ed8756f55761cb189489dabd22d9544d2`.
- Hermes bundle SHA-256: `a9d911b3f8b640d8e56b7527929a093c2356d97992409c453d167fdf39538d9a`.
- 11 DEX files; `assets/index.android.bundle` is Hermes v99, approximately 12.5 MiB.
  Its SHA-1 footer matches its contents.

`bin/decompile kick 40.32.1` produced Java, smali, resources, Hermes disassembly and
decompiled JavaScript under `~/build/lurk-morphe-patches/kick/40.32.1/`. `bin/scan kick 40.32.1`
provided an initial SDK inventory. Code inspection expanded that inventory; SDK
presence alone does not establish that it transmits data in a particular session.

JADX reported decompilation errors. hermes-dec 0.1.7 warns that v99 is not formally
supported and emitted 4,922 warnings, including unknown environment registers.
Its output contains 46,821 function entries, 570 with zero bytecode length. Do not
treat reconstructed JavaScript as executable source or a zero-length entry as proof
that a feature is absent. The targets below are supported by manifest/resource
inspection, named bytecode instructions and native code where available; exact edits
still need validation against smali/Hermes instructions and runtime behavior.

## Patch-by-patch assessment

| Rumble patch | Kick assessment | Evidence and required change |
| --- | --- | --- |
| **Change app name** | Straightforward | Kick uses `@string/app_name`, defined as `Kick` only in the base `res/values/strings.xml`. Reuse the resource-editing approach with Kick compatibility and defaults. |
| **Lurk icon** | Straightforward | Kick has launcher and round icons, but adaptive XML lives in `mipmap-anydpi-v26`, with foreground/background WebP resources. Rumble's exact file list would fail. Supply Kick artwork and handle split-resource precedence. |
| **Remove tracking permissions** | Straightforward | Four of Rumble's eight permissions are present. Remove those four; replace the Rumble-specific validation and referrer-query assumptions. This reduces tracking access but does not disable all telemetry. |
| **Clone app** | Feasible; broader verification | Rename Kick's package, declared permissions and 13 provider authorities. Rumble's required `com.rumble.provider` bytecode target is absent. Review deep links, token sharing and sign-in under the new package/signature. |
| **Disable analytics** | Strong candidate; new implementation | Firebase event logging, Insider, Datadog, Mux/Datazoom and first-party player telemetry have separate paths. Rumble's single event-repository patch cannot cover them. Preserve callback/Promise completion and distinguish telemetry from playback/session requests. |
| **Disable autoplay** | Strong candidate for feed previews | `preferencesSettingsSlice` defaults `showStreamPreviews` to true and has a toggle. Change that default while respecting stored preferences. Rumble's separate next-video behavior was not established in Kick. |
| **Default video quality** | Strong candidate; new implementation | The player store starts with `selectedQualityGroupPreset: 'auto'`; it supports `higherQuality`, `dataSaver`, `advanced` and user-selected quality. Native IVS exposes quality selection and an adaptive-quality ceiling. Decide whether the patch promises a preferred resolution or only a ceiling. |
| **Hide tipping and Rants** | Good equivalent: hide KICKs/gifting entry points | `KicksShopButton`, `GetKicksButton`, purchase/send screens and gift-subscription buttons are identifiable. Hide entry points without changing balances, purchase authorization or received messages. Rants and Rumble Wallet themselves have no direct equivalent. |
| **Hide Premium upsells** | Good equivalent: hide subscription/gift prompts | Kick exposes `ChannelSubButton`, `ChannelGiftSubButton` and subscription sheets. Target these UI components; keep genuine subscriber-only content indicators. There is no matching Rumble Premium promotion system. |
| **Hide Shorts** | Feasible equivalent: Hide Clips | `BrowseClipsScreen`, `ChannelClipsScreen`, Clips routes and clip-list queries exist. This would hide Kick clips, which are a different content category from Rumble Shorts. Route/tab and browse/category entry points need their own coverage. |
| **Clean share links** | Feasible, currently low benefit | Traced channel/live/VOD/clip share hooks construct clean path URLs. No tracking query was found in these paths. A host-scoped sanitizer could be added to Kick's share boundary if needed; do not blindly remove functional queries from every shared URL. |
| **Block ads** | Research candidate; full removal unproven | Kick has PAL, Amazon MediaTailor sessions, manifest/tracking URL selection and player ad events. Rumble's empty-ad-repository result cannot be reused. Blocking measurement or hiding an ad overlay does not establish that media segments are ad-free. |
| **Block cookie notice** | No matching target established | Rumble's two Ketch entry-point strings are absent from Kick's DEX strings, and no equivalent Ketch notice flow was found. Kick has a personalised-ads preference and SDK consent APIs; those are different targets. |
| **Unlock Premium** | No useful direct equivalent established | Rumble overrides `UserProfile.isPremium`. That DTO is absent. Kick's `useSubscribeToChannel` reads channel info, payment configuration and the signed-in user's channel relationship. Faking a local subscribed value would not establish paid access or subscription benefits. |

## Concrete targets

Paths in this section are relative to `~/build/lurk-morphe-patches/kick/40.32.1/`. Function IDs
identify this exact bundle for investigation; they are not version-stable fingerprints.

### Resources and cloning

`apktool/AndroidManifest.xml` and `apktool/res/values/strings.xml` establish the
label, icons, permissions, providers and update configuration.

The four tracking permissions shared with Rumble are:

```text
com.google.android.gms.permission.AD_ID
android.permission.ACCESS_ADSERVICES_AD_ID
android.permission.ACCESS_ADSERVICES_ATTRIBUTION
com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE
```

Kick does not declare Rumble's `AD_SERVICES_CONFIG`, `READ_PHONE_STATE`, Samsung
store-referrer permission or Huawei store-referrer permission. The existing patch
requires all eight, so merely changing `compatibleWith` would still fail.

Kick declares `com.kick.mobile.SHARE_TOKEN` and
`com.kick.mobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Its exported
`com.kick.mobile.tokenprovider` is protected by the former signature permission;
`expo/modules/kickauthtokenprovider/KickAuthTokenProvider.java` also checks allowed
calling packages. A clone must preserve these restrictions while renaming its own
authority/permission. Sharing authentication with the official broadcaster app
cannot be assumed to work after re-signing. Deep-link schemes include `kick`,
`com.kick.mobile` and `exp+kick-mobile`; verify intent routing with both apps installed.

### Playback preferences

In `hermes/disassembly.hasm` and `hermes/decompiled.js`:

- `preferencesSettingsSlice` initializes `showStreamPreviews: true` and
  `muteStreamPreviews: true`. `toggleShowStreamPreviews` is function **35368**.
  This is a concrete default-setting target that can retain Kick's own toggle.
- `usePlayerStoreIVSModuleSlice` is function **16410**. Its initial object includes
  `selectedQualityGroupPreset: 'auto'`, `autoQualityMode: true` and
  `userSelectedQuality: undefined`.
- `setUserSelectedQuality` (**34162**) updates `autoMaxQuality` from the chosen
  height while keeping adaptive quality enabled. `setSelectedQualityGroupPreset`
  (**34163**) handles `auto`, `higherQuality`, `dataSaver` and `advanced`.
- `jadx/sources/com/kick/mobile/kickivsplayerv2/KickIvsPlayerV2View.java` exposes
  `setQuality(String)`, `setAutoMaxQuality(Integer)` and `setAutoQualityMode(boolean)`.
  A literal resolution and an adaptive upper limit have different behavior.

Changing the global player `autoplay` field is not a verified replacement for
Rumble's next-video default. It could affect intentionally opened streams. Target
the preview preference first and trace any next-video policy separately.

### UI and sharing

Hermes contains these useful component/hook anchors:

| Target | Function ID | Intended use |
| --- | --- | --- |
| `KicksShopButton` | 26415 | Hide chat gift-shop entry point. |
| `GetKicksButton` | 26693 | Hide KICKs purchase entry point. |
| `ChannelSubButton` | 26323 | Hide channel subscription CTA. |
| `ChannelGiftSubButton` | 26307 | Hide gift-subscription CTA. |
| `ChannelSubscribeBottomSheet` | 22554 | Trace subscription sheet entry points. |
| `BrowseClipsScreen` | 26980 | Trace browse Clips UI. |
| `ChannelClipsScreen` | 27776 | Trace channel Clips UI. |
| `useShareStreamLink` | 24095 | Builds live, VOD and clip share payloads. |
| `useShareChannelLink` | 27837 | Builds channel share payloads. |
| `useSubscribeToChannel` | 26320 | Shows why channel subscription state differs from Rumble Premium. |

The traced share payloads use `BASE_URL/channel`, `BASE_URL/channel/videos/id` and
`BASE_URL/channel/clips/id`, passed to the React Native sharing library. This does
not prove every share surface is clean, but makes link cleaning a lower priority.

For UI patches, trace parents as well as components so hiding a button does not leave
an empty row or sheet. Keep navigation routes valid if deep links should still work.

### Analytics and ads

Kick's app-level Hermes `logEvent` wrapper (**14492**) appends current/previous
navigation paths and `app_platform: android_mobile`, then calls Firebase Analytics.
Native integration is through
`io/invertase/firebase/analytics/NativeRNFBTurboAnalytics.java`. Manifest collection
defaults are `firebase_analytics_collection_enabled=true` and
`firebase_analytics_collection_deactivated=false`. Crashlytics' manifest collection
default is already false; runtime changes were not tested.

Other concrete surfaces include:

- `com/useinsider/react/RNInsiderModule.java`: initialization and `tagEvent`.
- Datadog native modules/provider and Hermes tracking configuration.
- `com/kick/mobile/kickivsplayerv2/KickIvsPlayerV2View.java`:
  `initializeMuxAnalyticsListener`, `setMuxAnalyticsConfig`,
  `setDatazoomAnalyticsConfig` and `sendDatazoomAnalyticsEvent`.
- Hermes `postPlayerTelemetry` (**16565**): authenticated POST to
  `/api/v1/stream/{id}/playback` on the mobile subdomain. Despite its name this is
  **playback acquisition**: its response supplies stream URLs and configuration.
  The analytics patch deliberately preserves this call.

FingerprintJS integration also exists, including `RNFingerprintjsProModule` and
`useFingerprintId`. Removing `AD_ID` does not disable this code. It needs separate
dependency analysis before deciding whether to include it in an analytics patch.
Likewise, do not remove Pusher/Centrifugo indiscriminately; the package contains chat
transport integration, not just analytics libraries.

`DatazoomAnalyticsManager.java` creates Amazon MediaTailor sessions with optional
tracking URLs and attaches ad-event listeners. PAL handles nonce requests and playback
signals. Hermes `usePlayerStoreAdsSlice` (**16391**) supplies `onAdEvent` (**34139**),
and playback loaders inspect `manifestUrl` and `trackingUrl`.

These establish ad-related integration, but not a tested way to obtain ad-free
media. The next ad investigation should compare actual live/VOD playback responses
and manifests and test both playback and ad delivery. Do not classify disabling PAL,
Datazoom or ad overlays alone as a complete ad blocker. Datazoom's analytics and ad
session handling are coupled, which also matters for the analytics patch.

## Shared infrastructure and implementation order

**Account for Expo updates before shipping Hermes edits.** The manifest enables
`expo.modules.updates.ENABLED`, sets checks on launch to `ALWAYS`, and configures a
production Expo update URL/channel. Downloaded JavaScript could supersede patched
embedded code. The native `expo/modules/updates/UpdatesController.java` has an
explicit disabled-controller path. A Kick-specific dependency should ensure the
patched embedded bundle is used, including when an existing install has cached
updates; verify fresh installs and upgrades. Disabling updates also gives up those
over-the-air fixes until the app is repatched.

The local Morphe `hermesPatch` helper checks the Hermes magic, performs byte-pattern
replacements and recalculates the SHA-1 footer. It is not a JavaScript compiler or
semantic fingerprint engine. Its replacement helper edits the first match and pads
shorter replacements. Add explicit version/match-count checks and preserve bytecode
layout; do not globally replace shared strings such as `auto` or `true`.

Rumble's runtime-settings dependency also needs a Kick adapter. The shared Java
settings activity/registry and launcher shortcut can be reused, initialized from
`com.kick.mobile.MainApplication.onCreate`. The Rumble Compose settings-row hook
cannot transfer to React Native. Hermes switches need a native bridge or another
explicit way to read settings; including the shared extension alone does not provide
that connection. (The runtime bundle loader under "Implementation contracts" now
provides it.)

Do not advertise cookie-notice blocking or premium unlocking for Kick on the evidence
available here. Recheck all targets when changing the supported version.

## Workspace result

The original upload was left in place; the verified copy is in `apks/kick/40.32.1/`.
About 1.7 GiB of extracted/decompiled analysis material remains in
`~/build/lurk-morphe-patches/kick/40.32.1/` for implementation follow-up. Temporary resource-split
audit extracts were removed. The resource-split audit found no `app_name` or launcher
icon overrides in the supplied language/density splits.

The original assessment checked the 18 documented Hermes function IDs against
nonempty disassembly entries and covered all 14 Rumble patches. Implementation
details and validation follow.

## Implemented patch set

| Patch | Selected by default | Lurk settings | Behavior |
| --- | --- | --- | --- |
| Change app name | No | None (patch time) | Configurable launcher label; default `Kick Lurk`. |
| Lurk icon | Yes | None (patch time) | Adaptive Lurk icon in Kick green, including themed icon. |
| Remove tracking permissions | Yes | None (patch time) | Removes the four verified advertising/referrer permissions. |
| Clone app | No | None (patch time) | Separate package, permissions and 13 provider authorities; defaults to `com.kick.mobile.lurk`. |
| Disable analytics | Yes | On | Firebase collection/event bridge, Insider initialization/events/queued jobs, Datadog initialization, Mux/Datazoom and PAL nonce suppression. Promise completion is preserved. |
| Disable autoplay | Yes | On | Changes the initial feed-preview preference; saved preferences and Kick's own toggle take precedence. |
| Default video quality | No | On; quality on mobile data, Same as on Wi-Fi | Configurable adaptive default/Auto ceiling, consistently applied to initialization, reset and cleanup. Explicit player selections retain Kick's normal rules. A lower starting ceiling can be set for mobile data. |
| Hide KICKs tipping | No | On | Gift-shop, purchase and leaderboard tipping entry points; received gifts remain. |
| Hide subscription upsells | No | On | Subscribe/gift-subscription prompts, preserving the subscribed badge. |
| Hide Clips | No | On | Browse/channel/category tabs and channel clip rail. Direct playback routes remain. |
| Clean share links | Yes | On | Known campaign/click parameters on Kick URLs only; functional parameters, other hosts and attachments remain. |
| Bypass VOD ad sessions | No | Off | Experimental original-VOD-URL fallback. No live-ad removal claim. |
| Block update prompts | Yes | On | Kick's forced-update screen and update sheet, and the Play Store version lookup behind them. |
| Hide rating prompt | Yes | On | Kick's automatic "rate the app" prompts. |
| Show deleted messages | Yes | On; Ignore chat clears, on | Moderator-style struck-through deleted, timed-out and banned messages in chat; a moderator's clear keeps the messages. |
| Hide chat highlights | Yes | Nine switches, all off | Pinned messages, polls and predictions, goals, sub and gift messages, host messages, reward redemptions, event and drops banners, pinned KICKs, celebration prompts. |
| Stay on channel when it hosts | Yes | Off | The host dialog waits for Watch now instead of switching when its countdown ends. |
| Background audio | Yes | On; a second switch for picture-in-picture, off | Audio keeps playing through Kick's own audio service when you leave the app. |
| Normal latency | Yes | Off | Turns off low-latency mode for live streams. |
| Sleep timer | Yes | Off (choice) | Pauses playback a chosen time after it last started. |
| Default startup tab | Yes | Home (choice) | Opens Kick on Browse or Following instead. |
| Hide Home sections | Yes | Three switches, all off | Featured, recommended streams and categories rows on Home. |
| Seek interval | Yes | Kick's 10 seconds (choice) | 5, 15, 20 or 30-second skips in videos and rewound live streams. |
| Don't save searches | Yes | On | New searches aren't added to the search history. |
| AMOLED black | Yes | Off (choice) | Pure black background, optionally near-black cards and sheets. |
| Third-party emotes | Yes | 7TV emotes, off | The channel's and 7TV's global emotes in live chat. |

The second batch follows one switch policy, the same in all three apps: every patch whose
effect can be decided at run time has a Lurk setting that turns it fully off, and a new
patch with a switch is selected by default while its switch starts off, so a freshly
patched app behaves like stock until you turn something on. The exceptions start on:
lockout protection (Block update prompts), nags (Hide rating prompt), local privacy (Don't
save searches), and features that match what the same patch does by default on Twitch
(Show deleted messages, Background audio).

There is no cookie-notice or Premium-unlock patch: no corresponding Kick feature
was established. The VOD patch does not promise removal of ads already stitched
into the source stream. Its generator still completes with an object, allowing
both the VOD and live-rewind consumers to use their existing original-URL fallback.
Live playback authorization and media URLs remain unchanged.

Long-press the launcher icon and select **Lurk settings**. Settings show only the
patches included in that APK. Hermes and SDK-startup switches require a restart;
the screen offers a restart action. The quality height is a patch-time option;
its runtime switch restores Kick's default when disabled.

Hermes patches disable Expo OTA updates, including the controller path that reads
the update database. Native-only combinations leave Expo enabled. Repatch with a
supported new app version to receive JavaScript updates.

## Second batch

Each entry says what Kick does, what the patch changes and how it was checked. Hermes
switches take effect after **Restart Kick** on the Lurk settings screen; native ones apply
at once. Function names are Kick's own JavaScript names; anonymous functions are found by
the properties they read and the strings they load.

### Block update prompts

Kick's root layout renders `AppUpdateHandler`. On start it compares the remote minimum
version (`mobileAndroidForcedUpdateVersion`) and the suggested-update flags with the
installed version, asks Google Play for the store version, and stores the result. A child
then opens the forced-update screen, which can't be dismissed, after five seconds, or
shows an "update available" sheet. A patched app can't take a Play update, so the patch
returns null from `AppUpdateHandler`: neither prompt shows and the Play lookup doesn't
run. It is a leaf of the root layout with no children, and nothing else calls the check.
Expo's own over-the-air updates are already off whenever Kick's JavaScript is patched.
If Kick's server ever rejects an old version, you won't see Kick's warning: patch a newer
version. Checked: Kick starts and renders Home with the switch on; the forced path can't
be triggered from outside.

### Hide rating prompt

`useAppRating` opens Kick's rating sheet after a won prediction, a number of follows or a
redeemed reward, through `showAppRatingDialog`. That asks the rating store's
`shouldShowAppRating` first (never shown, or more than 120 days ago), and the patch makes
it return false. The developer-settings button that forces the sheet is untouched. The
follow trigger fires on a follow once you have followed ten channels and the app has been open
for an hour in total (`app-session-time`); below that it only counts the follow. Checked on the
emulator with a test account: after ten follows and an hour in the app, the next follow with the
switch on (the default) showed no sheet and left the count at ten, so the trigger fired and was
suppressed; with the switch off and Kick restarted, the next follow opened the "Rate us" sheet.
One more path needs no account: a trigger that fires in landscape persists
`shouldShowAppRatingOnNextPortrait`, and `AppRatingOrientationHandler` opens the sheet on the
next portrait screen through the same check; with that flag set in Kick's AsyncStorage
(`app-rating`), the switch off showed the sheet at start and the switch on didn't.

### Show deleted messages

The app doesn't draw chat messages itself. On the stream screen and on a channel's Chat tab,
`HTMLChatWrapper` opens Kick's chat web page, `https://chat.mobile.kick.com`, in a WebView
and passes it a JSON `config` URL parameter: the chatroom and channel IDs, the viewer's
`userCanModerate`, text size, locale and session headers. The page keeps its own realtime
connection and draws every chat line, including deletions, bans, subs, gifts, hosts and
reward redemptions. The app's own chat store has handlers for the same events
(`handleDeleteChatMessageEvent`, `handleClearChatEvent`, `handleSubscriptionEvent` and
others), but in 40.32.1 nothing calls most of them, and the entries it keeps are only drawn
by VOD chat replay. Chat lines are changed through that config, and through a script Lurk runs in the
page (see [Chat page script](#chat-page-script)).

The page shows a removed message according to `userCanModerate`. When it's false, a deleted
message's text becomes "Deleted by a moderator" and a ban or timeout removes the user's
messages. When it's true, the text is struck through with "(Deleted)", "(Timed out)" or
"(Banned)", with Kick's "AI Mod" label and the rules it flagged when its AI moderation
acted, and lines such as "X timed out Y for 10 minutes", "X banned Y" and "X unbanned Y"
appear. The patch makes the config builder, the function that loads the chat page's URL,
store `true` in the slot its object literal names `userCanModerate`. The app's own
`userCanModerate`, which its moderation menus read, doesn't change. The page uses the value
for one control too: large (mid and high tier) KICKs gifts in chat get the moderators' menu
with Delete. The page only hands that tap to the app (a `delete-kicks` click message), and
the app's handler calls `deletePinnedItem` only when its chatroom store's own
`userCanModerate` is true, so for anyone else the tap does nothing and no request reaches
Kick. The config is part of the page's URL, so Kick's server also sees the value in the
request for the page.

Messages removed before you opened the chat don't come back: history arrives without them.
The page only marks messages it has already drawn. Kick's AI moderation often removes a
message within milliseconds, while it's still waiting in the page's queue, and such a message
then shows as an ordinary one, as in stock Kick.

"Ignore chat clears", the second switch, is on by default as on Twitch. When a moderator
clears the chat, the page empties its message list (`app.innerHTML = ""`, from its
`ChatroomClearEvent` handler); the chat page script makes that one assignment on the list do
nothing, so the messages stay and new ones keep arriving.

Checked on the emulator (Android 16, signed out) on large live channels. Real events: with the
switch on, 43 timeouts in about 20 minutes, a moderator's delete and a permanent ban left the
messages in place, marked "(Timed out)", "(Deleted)" or "(Banned)", with "moderator timed out X"
lines; with it off and Kick restarted, a timeout removed the user's message and added no line,
as in stock. No moderator cleared a chat in 15 minutes across 14 large live channels. So for
clears, and for both states of every event, a test build with WebView debugging on handed
realtime frames in the page's own format (a delete, a permanent ban, a clear, a mid-tier KICKs
gift) to the page's socket, which runs them through Kick's own handlers. With the switches on: the
deleted and the banned user's messages stayed struck through with "(Deleted)" and "(Banned)"
plus "X banned Y", the clear left all messages, and the KICKs gift showed the moderators' menu.
With them off: "Deleted by a moderator", the banned user's messages removed with no line, the
chat emptied (40 lines to none) and no menu, as in stock. The menu's Delete wasn't tapped on
the device; the app's handler (above) ignores it from a non-moderator.

### Hide chat highlights

| Switch | What it changes | Hidden |
| --- | --- | --- |
| Hide pinned messages | `PinnedMessage` returns null | The message pinned above chat |
| Hide polls and predictions | `PollFloating`, `PredictionBannerView` return null | Poll and prediction cards; a hidden poll can't be voted in |
| Hide goals | `FloatingGoalCard` returns null | The channel goal card |
| Hide sub and gift messages | The sub and gift card callbacks return; the page hides its sub and gift lines | "Just subscribed" and gifted-sub lines in chat, and their cards above it |
| Hide host messages | The host card callback returns; the page hides its host lines | "X is hosting with N viewers", in chat and as a card above it |
| Hide reward redemptions | The page hides its reward lines | "X has redeemed ..." lines in chat |
| Hide event and drops banners | `DropsBanner`, `StreamEventBanner` return null | Drops campaign and "Only on Kick" event banners |
| Hide pinned KICKs | `PinnedKicks` returns null | KICKs gifts pinned above chat |
| Hide celebration prompts | `ConsumingCelebration` returns null | The celebration button in the chat footer |

The components' parents are ordinary lists and footers, so the space closes up. A new
subscriber, a gift and a host each queue a timed card (`EventBanner`) in the floating stack
above chat, where pinned messages, polls and goals also live. Three anonymous callbacks, which
Kick's realtime listeners call, queue them; they now return at once. The lines for the same
events inside the chat, and reward redemptions, which only appear there, come from Kick's chat
web page (see Show deleted messages); the app's own sub, gift, host and reward handlers are
never called. The chat page script hides those lines by the page's own classes for them
(`subscription-container`, `subgift-container`, `host-container`, `reward-redeem-container`),
so they take no space; the page still receives them. Pinned KICKs sits here rather than under
Hide KICKs tipping because it shows a gift someone already sent. Checked on the emulator: the
pinned KICKs and prediction cards disappear, and with the sub callbacks returning no sub card
appeared above chat. On a channel with channel-point rewards, all 85 real redemptions in nine
minutes were drawn by the page and hidden. Subs, gifts and hosts were rare during the tests, so
those were handed to the page's socket as realtime frames in a test build (see Show deleted
messages): with the switches on Kick drew each line and it stayed hidden; with them off each
showed, as in stock.

### Stay on channel when it hosts

When the channel you watch hosts another one, Kick's realtime connection sends
`ChatMoveToSupportedChannelEvent` and `JoinHostDialogContent` shows "X is taking you to watch
another stream" with a countdown (`useCountdown({duration, startOnMount: true, onEnd})`). The
component is mounted with the stream screen; the countdown restarts when an event arrives,
and at zero `onEnd` switches to the hosted channel if you're on a stream, clip or VOD screen.
Watch now doesn't switch by itself: it sets the dialog's `pressed` state, which hides it, and
shows a "taking you to X in N seconds" toast, and the countdown's end does the switching.
Cancel and close clear the event.

The patch sets `startOnMount` to that `pressed` state instead of `true`, so the countdown only
runs after Watch now. Until then the dialog waits, showing the full duration, for Watch now,
Cancel or close. The literal's `startOnMount` is a constant, so the store goes where the
options memo wrote its cache entry for `onEnd`; the options object is then rebuilt on each
render, which `useCountdown`, reading only its fields, doesn't notice. (The first version
returned from `onEnd` instead, which also stopped Watch now from switching.)

Checked on the emulator with a test build that hands the app's own realtime module a host
event, so the real dialog appears: with the switch off, the countdown ran and switched
channels; with it on, the dialog waited at "Redirect in 10s" for over half a minute, and Watch
now switched once Kick's countdown had run.

### Background audio

Kick already keeps audio going with the screen locked and in picture-in-picture: its
`KickIvsPlayerV2Manager.shouldRunAudioService()` is `isPlaying && (screen locked ||
picture-in-picture)`, and `AudioService` is a media-playback foreground service with a
notification. Leaving the app without picture-in-picture (the device has none, or the
second switch is on) starts nothing, so the audio stops once Android freezes the app.

The patch starts Kick's audio service from `MainActivity.onUserLeaveHint()`, while Kick is
still in front, which Android allows; starting it later from the background would throw.
`shouldRunAudioService()` then also returns true while Kick was left playing, so the player
state changes, unlock and resume that would stop the service keep it, and
`onActivityResume` hands control back to Kick's own rule. With background audio on and
picture-in-picture off by default, the only change from stock is where Kick couldn't open
picture-in-picture: the audio keeps playing. Closing the picture-in-picture window still
pauses, as in stock. "No automatic picture-in-picture" skips Kick's automatic window when
you leave while watching: both the window Kick opens from `onUserLeaveHint` and, on
Android 12 and later, the system's auto-enter, which Kick turns on in the window's
picture-in-picture parameters (`updatePipParams`). Kick's own picture-in-picture button
still works. Checked on the emulator (Android 16), signed out, on a live stream: with
the defaults, Home opens picture-in-picture and `AudioService` runs in the foreground as
in stock; with automatic picture-in-picture off, Home leaves no window, `AudioService`
runs in the foreground and the stream's audio track keeps playing.

### Normal latency

Kick's player view turns on IVS low-latency mode for every player it creates, and nothing
else sets it. The patch hooks the IVS SDK's `setLiveLowLatencyEnabled(boolean)`, the same
hook Lurk uses on Twitch: with the switch on, the request becomes false. It applies to
streams opened afterwards.

### Sleep timer

The shared sleep timer arms on IVS `play()` and stops tracking a player on `pause()` and
`release()`, as on Twitch. When it fires, it pauses through Kick's player view (the one
React Native last played through), whose `pause()` also updates the audio service and lets
the player controls show Play. That view queues its IVS command on a player executor. The
injected executor hook wraps only operations enqueued during a sleep dispatch and checks the
expiry again when the command executes; a new start or resume invalidates queued broad pauses.
Ordinary player operations keep their original Runnable. The dispatch context is cleared even if
the action throws. Three seconds later, the fallback pauses only players eligible at expiry whose
play generation still matches; a genuinely missed old player remains eligible even when a different
player started afterwards. A notice says the timer paused playback.

The APK-free sleep tests cover both dispatch boundaries and the fallback with bounded two-thread
fixtures, including reentrant IVS stop callbacks. After decoding a Kick APK patched with Sleep timer,
run `python3 -I tests/kick/verify_sleep_queue.py <apktool-dir>` to check that the actual executor
Runnable argument receives the emitted expiry guard. Helper tests establish ordering and exclusion;
live playback, control state and background transitions still need device UI verification.

### Quality on mobile data

Part of Default video quality. Every quality ceiling reaches the player through
`KickIvsPlayerV2View.setAutoMaxQuality(Integer)`: the starting one, Auto, a picked quality
and the re-apply on each player state. Kick doesn't remember a pick across videos, so on a
metered network the choice caps a starting ceiling: none, Kick's 720 or the patch's own
height. Any other height is a pick and passes through, and Kick's stored values aren't
changed. Picking exactly 720p (or the patch's height) on mobile data can't be told apart
from a start and is capped too. Picking Auto is capped, since Auto means "let the app
decide". It applies when the ceiling is next set (a stream load or a player state change),
not on a network change mid-stream.

### Default startup tab

Kick is an Expo Router app: links go through `Linking.getInitialURL()`, which reads the
activity's intent when its action is VIEW. Its link mapper already turns
`https://kick.com/browse` into Browse and `https://kick.com/following` into Following. On a
fresh start from the launcher (no saved state, no link), the patch makes the launch intent
such a link before `MainActivity.onCreate` reads it. Links, notifications and restored
activities keep their own intent, and since the activity is `singleTask`, returning to a
running Kick keeps the current screen.

### Hide Home sections

`HomePageIndex` renders `FeaturedStreamsRow`, `RecommendedStreamsRow` and `CategoriesRow`,
then the sections Kick's server configures. Each row loads its own data and shows its own
loading placeholder, so returning null removes it and its placeholder; the server
sections move up. The recommended row only shows when signed in.

### Seek interval

Kick's player config holds `controls.skip.scale = {livestream: [60], vod: [10, 10, 10],
'live-vod': [10, 10, 10], clip: [10]}`. The `vod` and `live-vod` arrays read the same three
integers in the literal buffer, and nothing else reads them, so a choice edit rewrites the
three values. The live-edge and clip arrays share their bytes with unrelated literals and
stay.

### Don't save searches

The search-history store's `addHistoryEntry` prepends each query. It returns at its
opening guard instead, so nothing is added; removing a saved search still works. The store
is saved together with the sign-in tokens, so the patch doesn't clear it natively, and
searches saved before stay until you remove them.

### AMOLED black

Kick is dark only. Its `Colors.surface.default` object holds `highest` `#1C1E20`, `base`
`#141517` and `lowest` `#090A0B`. Hermes stores strings overlapping each other (`'#141517'`
shares bytes with `'1'`, `'17'` and others), so the colours aren't edited in place: the
object's string IDs in the literal buffer are swapped for existing strings. "Black
background" makes `lowest` `#000000`; "Black background and surfaces" also makes `base`
`#0B0B0C`, one step above black, so cards and sheets still stand out. `highest` (raised
elements and borders) stays. `MainActivity.onCreate` also sets the window background,
seen during transitions, to black. The splash screen, drawn before any app code runs,
stays Kick's `#090A0B`, and colours hardcoded in components stay as they are.

### Chat page script

The switches that change chat lines (Ignore chat clears, Hide sub and gift messages, Hide host
messages, Hide reward redemptions and 7TV emotes) work inside Kick's chat page, since that is
where the lines are drawn. Any patch with one of them adds a hook to react-native-webview's
`RNCWebViewClient.onPageStarted` and `onPageFinished`, after their super calls. For a page on
`chat.mobile.kick.com`, other than the single-gift KICKs preview (`preview=true`),
`KickChat` evaluates the script (`patches/src/main/resources/kick/chat/chat-page.js`, copied
into the APK as `assets/lurk-chat.js`) with the switches that are on, and logs what it did.
With none on, nothing is evaluated. At page start the WebView can still be showing the
previous document, so the script runs at both, checks the page's host and applies itself once
per page. It only adds a style rule, replaces the list's `innerHTML` setter and observes the
list for new messages; everything else on the page is Kick's. The page is served by Kick and
can change at any time: the script then does nothing for what it no longer finds, and Lurk
can't check it at patch time. `tests/kick/test_chat_page.py` runs it in headless Chrome on a
copy of the page's structure.

### Third-party emotes

"7TV emotes" (`emotes_7tv`) starts off and requires restart. Enabling it adds channel
and global emotes to messages, the existing native picker and native `:query`
autocomplete. A channel alias wins over a global alias; native Kick names win in the
picker so name-only native callbacks remain unambiguous. Selecting 7TV inserts the raw
word in the existing draft at its UTF-16 selection, preserving replies and stock send
handling. It requires the authenticated session and the current native composer's
committed `editable` permission/rules prop. It never sends a message itself.

The page independently fetches globals and the channel owner's set from 7tv.io. Each
request has a 10-second header/body deadline, a 2 MiB response limit and at most three
transient attempts; 429 honors a capped Retry-After. Successful globals render while
owner lookup stalls. Failed or malformed refreshes retain the last valid catalog. Authoritative no-set responses
and omitted empty emote vectors clear the removed entries and reconcile set subscriptions. When needed, the
owner lookup uses `kick.com/api/v2/channels/<slug>` without credentials. Native channel
lookup maps hold at most 256 entries. A channel not yet observed gets globals only.

Active-set and emote-data zero-width flags layer over the adjacent emote, including a
native Kick image; layers never cross text, links, newlines or messages. A standalone
overlay renders normally. Live refresh restores only Lurk-owned nodes and preserves
existing native/link handlers. Catalog changes update existing messages and native
suggestions without replacing draft/reply state.

The feature subscribes to current set IDs at **wss://events.7tv.io/v3**, debounces
validated set updates into refetches, watches heartbeats, and reconnects with capped
backoff (at most eight attempts per burst). Visible pages revalidate every five minutes
to find changed channel sets. Hidden pages abort requests and close sockets; page
teardown cancels observers, timers, sockets and fetches. Provider GETs omit cookies and
referrers; images use no-referrer. 7TV receives the channel owner's Kick ID, set IDs and
normal request IP metadata; it receives no Kick credentials or chat draft. Images load
from **cdn.7tv.app**. With the patch excluded or the switch off, Lurk makes no provider
requests. 7TV badges and paints are not included.

The WebView sends only bounded catalog snapshots for its current native document token
and channel. Native validation strips unknown fields, rejects foreign origins/stale
documents and allows only 7TV CDN emote URLs. Input-ref reset/remount retires the native
catalog generation; a fresh page association is required before augmentation resumes.
Pagehide and native WebView destruction invalidate only that page’s authority. The stock WebView bridge continues to
handle ordinary messages. A separately compiled HBC99 bootstrap extends chatRoomStore,
ImageCacheV2.getImage and the existing picker/autocomplete actions. Module IDs come
from unique export/Metro registration signatures in the hash-locked stock bundle.
Native images use Kick's cache. Exact `files.kick.com` and `files.cdn.kick.com` image
requests are mapped to 7TV. Static requests use `2x_static.webp` for animated sources;
naturally static sources retain `2x.webp`. The native section supplies Kick's supported
globe tab icon and an accessible 7TV label.

Builds compile committed `patches/src/main/runtime/{kick,twitch}/*.js` through
`bin/lib/compile-hermes.py`, using official mise-managed `hermes-compiler` packages
`260318099.0.4` (HBC99) and `250829098.0.19` (HBC98). The packages have no executable shim;
the script resolves their package location with Node and verifies package provenance,
compiler version and emitted header. Generated resource metadata records source,
compiler-binary and bytecode SHA-256. Gradle regenerates resources and removes obsolete
outputs; CI installs the same pins before host checks and build. No generated HBC is
checked in. The reusable Java loader validates metadata and queues the compiled bundle
after either main-loader branch, retaining only weak runtime references cleared on
destroy.

`tests/kick/test_chat_page.py` executes the real page script in offline Chrome;
`test_composer.js` is a boundary fixture, not proof of the native UI.
`test_page_lifecycle.js` drives the production page function with controlled provider
responses and timers to verify authoritative removals, sequence-gap refetch, heartbeat
expiry, retry exhaustion, set-ID resubscription and disposal. `bin/test-kick-modules <stock index.android.bundle>` checks the pinned HBC export/registration discovery, and `bin/test-kick-catalog` exercises
the APK's Java data validator on ART. Actual runtime loader ordering, image rendering,
composer insertion and sourceURL preservation are checked separately in the target app.
The emulator smoke rendered the native 7TV picker and suggestions, inserted raw words
into a middle-of-draft selection and proved its native caret by subsequent typing. It
uses the documented ARM translation workaround, so it is not physical-phone UI proof.
Insertion uses the pinned AndroidTextInput Commands.setTextAndSelection with the current
host event counter, followed by stock draft/selection setters. Text and caret change
atomically, so the later native span replacement preserves the caret's distance from
the new end. There is no delayed correction to overwrite later user movement. Authenticated
send correctness requires a separately authorized send test.

## Implementation contracts and verification

The Hermes helper accepts only the pinned v99 bundle SHA-256, parses its function
and string tables, requires unique targets and rejects overlapping or unequal-length
edits. The official Morphe `hermesPatch` performs the actual replacements and footer
update. A generated manifest (`assets/lurk-hermes.json`, format 2) records each edit's
original bytes and, for a switch, its replacement and default, or, for a choice, the
bytes each choice value writes and the value the APK ships with. The native
bundle loader takes a settings snapshot. When every setting is at its patch-time
default it loads the embedded asset as is and deletes any earlier private copy;
otherwise it verifies the embedded hash, chooses ranges from the snapshot, refreshes
SHA-1 and atomically writes an app-private bundle. Turning every switch off and every
choice to 0 reconstructs the original bundle byte for byte; reloading in the same
process keeps the original snapshot. On a manifest/file error it logs and uses
embedded defaults.

The edit helpers, all equal-length and fail-closed:

- `returnNull` / `returnAtEntry`: return null, undefined or false as a function's first
  instruction. `returnAtEntry` takes any prologue of environment, global, parameter and
  property loads, decoded with the v99 instruction lengths, and refuses a function that
  branches into the replaced bytes.
- `trueProperty` / `falseProperty`: the function's single `GetById` of a property loads
  a constant instead, in the same general register.
- `skipGuard`: a handler's opening `if (!event) return;` jump becomes a `Mov` of the
  tested register onto itself, so the handler always returns before doing anything.
- `dataEdit` / `dataChoice`: values in the literal buffer that array and object literals
  read, as a switch or a choice. Hermes shares value bytes between literals, so each edit
  names the literals allowed to read its bytes and fails if any other literal does. Their
  replacement window is grown until its original bytes are unique in the bundle.
- `functionUsing`: selects an anonymous function by the properties it reads and the
  strings it loads, rather than by bytes that embed build-local string IDs.
- `objectKeys`: an object literal's property names, read from the bundle's shape table in
  slot order, so an edit can find the slot a named property is stored in.
- `literals` / `slotArrays`: every array and object literal instruction with the value
  bytes it reads, and the array literals stored into an object's slots right after it is
  made. Literal edits find their values through these, not through buffer offsets.
- `sequence`, `reads`, `creates`, `getById`: one run of instructions matched by opcode,
  property, object keys and register flow, and a `GetById` pattern built with this bundle's
  string ID. Shape numbers, literal offsets and string IDs change in every Kick release;
  these keep the registers and code shape exact without pinning those numbers.

Verification tools retained in this repository:

- `tests/kick/verify_resources.py`: actual decoded resource/manifest assertions,
  including signature-protected token sharing, intact internet permission and
  (`--analytics`) Firebase Analytics starting disabled.
- `tests/kick/HermesProbe.kt`: actual-bundle parsing, negative controls for every
  helper, uniqueness, overlap rejection, preview literals, unique literal windows and
  exact restoration. It runs on the JVM, compiled with `kick/shared/Hermes.kt` against
  Morphe Desktop.
- `tests/kick/test_hermes.py`: independent instruction/branch decoding of emitted
  edits; all-on, all-off, each switch alone and each choice value (plus 0 and an
  unknown value); SHA checks.
- `tests/kick/verify_literals.py`: for literal-buffer edits, decodes every function and
  requires that only the intended array/object literals read the edited bytes, and that
  every choice value keeps each literal's tags, count and length. `test_hermes.py` runs it.
- `bin/test-kick`: executes the injected Java loader on Android, checking defaults,
  process snapshots, independent switches, the exact bytes of each choice value and
  exact stock restoration; invokes the two Insider job guards and checks synchronous
  completion without SDK dispatch.
- `tests/kick/verify_privacy.py`: production share/PAL helpers with boundary doubles;
  31 assertions, including host spoofing, functional parameters and switch isolation.
- `tests/kick/verify_quality.py`: interprets actual reset/setter instructions to
  verify first playback, Auto, explicit selections and clearing a selection. Its
  negative control reproduces the initial-only patch's 1080p-to-720p reset bug.
- `tests/kick/verify_analytics_jobs.py`: compares actual decoded job methods with
  stock, including unchanged instructions, branches and exception handlers when off.
- `tests/kick/test_chat_page.py`: the chat page script in headless Chrome, offline, on a copy
  of the page's structure: host guard, one application per page, hidden lines, ignored
  clears (with a control), 7TV words in old and new messages, the owner lookup, and no
  referrer or cookies on its requests (with a control).
- `tests/kick/verify_player.py`: production `KickPlayer` with boundary doubles: the
  mobile-data ceiling's decision table (with and without a Default video quality
  height), normal latency and the background audio decisions.

The combined APK was built and patched with all twelve patches. Its resource,
signature and alignment checks passed. Native-only and Clips-only combinations
passed and registered exactly their selected settings. Rumble still patched with
the shared extension, and its profile/entity integration probe passed.

Runtime tests use the existing Android emulator. Both stock Kick and the patched
clone initially fail in SoLoader because the x86 emulator's direct-APK loader searches
for x86 libraries while this upload supplies ARM libraries. Extracting the original
ARM libraries into each test install's native-library directory allows startup through
the emulator's existing translation layer. This workaround changes only the emulator;
it is not shipped in a patch or APK. The apps coexist, the patched bundle executes,
Home/Browse/channel screens open, and the Lurk screen/shortcut is registered. Browse
and channel tabs show the intended Clips removal, and Videos navigation works.
Public live playback and incoming chat were exercised with analytics and interface
patches enabled. The final combined asset has 25 edits across 20 functions and six
independent Hermes switches; the native sharing/analytics switches are separate.
The visible Hide Clips switch and Restart Kick action were exercised: the process
restarted, its private bundle matched the selected bytes exactly, and Browse's Clips
tab returned. The launcher shortcut points to the non-exported settings activity.

These tests do not establish zero telemetry from the first process instruction.
Firebase's content provider initializes before the application collection hook, so
Disable analytics also sets the manifest's `firebase_analytics_collection_enabled`
default to false; Firebase prefers the value the startup hook persists, so the switch
still turns collection back on.
The [dated verification record](verification-2026-10-17.md) distinguishes current native
composer/provider and loader evidence from historical observations above.
Authenticated feature flows, broadcaster token sharing and every server ad configuration
need device/account coverage. Do not interpret SDK suppression or static VOD fallback
validation as verified removal of every video ad.

Retained patch-integration regressions: `tests/kick/verify_hosting.py` executes the emitted
`stay_on_host` manifest edit, actual JSX handlers/countdown and persistent React compiler caches.
It covers waiting, Watch now, Cancel/close, stock/off behavior, route guards and consecutive events,
with three failing bytecode mutations. `bin/test-kick-chat <patched.apk>` captures production
KickHttp/KickChat JavaScript on ART and runs it in offline Chrome, covering response preservation,
full/info channel IDs, malformed and missing config, globals, previews and independent switches.
Malformed config now falls back to global emotes while retaining hide/ignore-clear options.
See `tests/README.md` for prerequisites and the exact boundary limitations.
