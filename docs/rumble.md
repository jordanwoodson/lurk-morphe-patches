# Rumble 3.21.0

`Unlock Premium` targets `com.rumble.battles` **3.21.0 / versionCode 887**, using the
APKMirror APKM and signing certificate pinned in `rumble/shared/Constants.kt`.

Sign in, or sign out and back in after installing, to fetch a fresh profile. The patch
overrides the profile's local premium flag; a signed-out session never fetches that
profile. Server-authorized streams, purchases and subscription metadata are unaffected.
Live signed-in playback has not been verified with an account.

## Implementation

Inspired by the profile-entry approach in
[hoomans-morphe-patches](https://github.com/arandomhooman/hoomans-morphe-patches/blob/018277825a611ad61f3a16a70e562291c06f4081/patches/src/main/kotlin/hooman/morphe/patches/rumble/premium/UnlockPremiumPatch.kt),
which targets 3.14.5. This implementation fingerprints the specific `isPremium:Z`
field read, method signature and two-instruction getter, requires a unique match,
and returns `true` after that read while the `unlock_premium` switch is on (see
[Lurk settings](#lurk-settings)). It does not depend on the obfuscated getter name
or the assumption that the DTO has only one boolean getter.

The independently traced 3.21.0 path is:

```text
UserProfile.l() reads isPremium (JSON is_premium)
  -> we.a.t(...) maps it into lf.h
  -> lf.h.x() returns the entity's premium flag
  -> mf.f passes it to yg.d.d1(...)
  -> session DataStore key isUserPremiumKey
```

These obfuscated names describe this APK only; they are not patch fingerprints.
Only the getter's result changes. Nullable `premiumData`, account
validation, age verification and onboarding retain their original behavior.

## Build and test

With GitHub Packages access configured as described in the workspace README:

```sh
bin/patch rumble
bin/emu start
bash bin/test-rumble ~/build/lurk-morphe-patches/out/rumble-3.21.0-patched.apk
```

`bin/test-rumble` runs every probe in `tests/rumble/`, or only the ones named after the APK
(`bash bin/test-rumble <apk> PremiumProbe`). Each probe calls the patched APK's real methods; settings are
switched by rewriting their defaults in the registry, since there is no Context under `app_process`
(`ProbeSupport.setting`). `tests/rumble/verify_settings.py <apktool-dir>` checks the settings registry of a
decoded patched APK: unique keys, valid sections and choices, and the documented defaults. `PremiumProbe` executes the actual APK's profile
getter and mapper on `emulator-5580` using free and paid fixtures, including absent subscription
metadata. It also checks unrelated account state. No probe uses a Rumble account or sends API
requests. The probes are deliberately pinned to 3.21.0 and must be re-derived for other versions.

To confirm the negative control, the same probe against the original base APK must
fail with `Profile must report premium when server is_premium=false`:

```sh
bash bin/test-rumble ~/build/lurk-morphe-patches/rumble/3.21.0/base.apk PremiumProbe
```

## Validation on 2026-10-14

- Stock APK: the expected assertion fails. Patched APK: free and paid fixtures pass.
- Morphe Desktop 1.18.1 applies the patch, rebuilds and signs successfully.
- The signed APK installs and cold-launches to Rumble's sign-in screen on Android 16.
- All six output DEX files pass `dexdump` and D8; APK manifest, signature and alignment
  verification pass. The optional cross-DEX verifier reports the same 42 missing
  optional SDK/reference issues on both stock and patched APKs, with no added issues.
- The workspace Gradle build was blocked by HTTP 401 from GitHub Packages, so the tested
  `.mpp` was compiled against the patcher API in the existing Morphe Desktop JAR, using
  the Kotlin compiler and Android D8. It contains both JVM classes and Android DEX.
  Releases build the patch with Gradle in CI.

Artifacts are in `~/build/lurk-morphe-patches/out/`: `rumble-3.21.0.mpp`,
`rumble-3.21.0-patched.apk` and the Morphe result JSON. Build and verification logs
are in `~/build/lurk-morphe-patches/build-rumble/`; the launch screenshot is in
`~/build/lurk-morphe-patches/shots/rumble-premium-launch.png`.

## Lurk icon

`Lurk icon` replaces the launcher icon with `assets/rumble-icon.png`. Rumble 3.21.0 builds its
adaptive icon from `drawable/ic_launcher_background.xml` and `drawable/ic_launcher_foreground.xml`
(`mipmap-anydpi/ic_launcher[_round].xml`) and ships `.webp` fallbacks per density. The patch
overwrites those files under the same names, so resource IDs and the manifest stay untouched, and
adds a themed (monochrome) layer. It fails if any of the files is missing in a new version.

Regenerate the resources after changing the artwork with
`bin/app-icon rumble <artwork.png>`.

Validated on 2026-10-14 with a local build: Morphe Desktop 1.18.1 applies `Lurk icon` and
`Unlock Premium` to 3.21.0; the launcher and the Android 16 splash screen show the new icon and the
app cold-launches to its home feed.

## Block cookie notice

Rumble 3.21.0 shows its cookie and privacy notice through the Ketch consent SDK (organization `rumble`,
property `rumble_android_app`). Rumble's consent service calls `Ketch.load()` at startup and on every
resume. Settings > Data Consent calls `Ketch.showPreferences()`. Each loads Ketch's web experience in a
WebView, which fetches the config for the user's jurisdiction and sends the identities Rumble supplied
(Android advertising ID and AppsFlyer installation ID) to `global.ketchcdn.com`. What it shows depends on
the jurisdiction: a banner or modal where privacy laws apply. In `us___no_privacy_laws` it shows nothing
and records consent to every purpose (`targeted_advertising`, `analytics`, `essential_services`).
Recorded consent starts AppsFlyer and enables Firebase Analytics. Opening Data Consent records the same
defaults as soon as the dialog appears, before anything is tapped.

The patch makes both SDK methods return `false` immediately, which is what they return when they cannot
create the WebView. They are found by the SDK's log lines ("Not loading…" / "Not showing preferences…
as an experience is already being shown") and the `(ZZII)Z` signature, so the R8-renamed class name
doesn't matter. Without stored consent, Rumble's tracker hooks only ever receive an empty purpose set:
AppsFlyer is never initialized, and Firebase Analytics, Firebase Performance and the Facebook SDK's
automatic event logging stay off.
Settings > Data Consent no longer opens anything.

Rumble also keeps the last decision in `consent_cache.json` and applies it at startup without Ketch, so
consent recorded while the switch was off, or before the patch was included, would still start the
trackers on every launch. While the switch is on, the cached model's `hasDecision` getter returns `false`
(found as the class whose `toString` starts `CachedConsent(hasDecision=`; it is the only `()Z` there).
Startup then hands every tracker an empty purpose set, as on a fresh install: Firebase Analytics,
Crashlytics, Performance and Facebook switch their persisted collection off again, and AppsFlyer and
Airship are never started. The cached consent strings still go into ad requests, which Block ads stops.
Turning the switch off uses the cached consent again.

The switch offers a restart after either toggle. It blocks future Ketch calls immediately, but cannot
undo SDK initialization or replace consent already applied to the running process. Restart to apply the
startup consent path consistently; turning it off also needs a restart to restore that path. The
actual-process consent/restart SDK-state matrix is recorded in
[the 2026-10-17 verification record](verification-2026-10-17.md); notification delivery and
live queued-event drain remain untested.

Validated on 2026-10-14 on the Android 16 emulator, fresh data each time, location resolved by Ketch as
US-MO:

- Stock: Ketch loads on launch, sends the advertising ID in its `log` request and writes
  `consent_cache.json` with every purpose granted. AppsFlyer records `first_launch`, Firebase consent is
  `G111` and measurement is enabled.
- Patched (`Block cookie notice` only): launch, signed-out home feed, background/resume and Settings >
  Data Consent make no Ketch requests and no crashes. No `consent_cache.json`, no AppsFlyer
  `first_launch`, Firebase consent `G100` with measurement disabled.
- Morphe Desktop 1.18.1 applies it together with `Unlock Premium`. The bundle was built with the local
  fallback described above, because Gradle still can't reach GitHub Packages.

The visible banner itself wasn't reproduced, since no notice is shown at that location; the patch doesn't
depend on jurisdiction, because both entry points that can display one are disabled.

Block cookie notice prevents Airship, Rumble's push service, from starting after a restart.
Airship-handled messages cannot be processed without it; Airship's push feature requires the
`essential_services` purpose. The actual consent matrix confirms startup and feature state,
but no notification was delivered. The inherited FirebaseMessagingService can independently
auto-display recognized notification payloads while the app is backgrounded or locked, before
calling Airship's `onMessageReceived`. Data-message handling has a separate Airship-availability
guard. This is therefore not a guarantee that every incoming FCM notification is suppressed.

## Lurk settings

Every Rumble patch that changes code can be switched off and on in the app, without patching again:
Settings > App info > **Lurk**, or long-press the launcher icon > **Lurk settings**. The screen lists
only the patches that were included. Each switch starts at the patch's default (or its patch option)
and keeps the user's choice from then on, including across updates. Reset to defaults forgets those
choices.

| Switch | Key | Takes effect |
|---|---|---|
| Block ads | `block_ads` | at the next ad request |
| Block cookie notice | `block_cookie_notice` | after a restart |
| Clean share links | `clean_share_links` | at the next share |
| Disable analytics, Keep watch progress | `disable_analytics`, `keep_watch_progress` | at the next upload |
| Hide Premium upsells | `hide_premium_upsells` | when the screen is drawn again |
| Hide Shorts | `hide_shorts` | after a restart |
| Hide tipping and Rants | `hide_tipping` | when the data is loaded again |
| Block update prompts | `block_update_prompts` | after a restart |
| Default video quality (a choice; "Rumble's default" is 0) | `video_quality` | at the next video |
| Live streams too | `video_quality_live` | after a restart |
| On mobile data (a choice; "Same as on Wi-Fi" is 0) | `video_quality_mobile` | at the next video |
| Show exact counts | `exact_counts` | when the screen is drawn again |
| Hide pinned messages, Hide sponsor reads | `chat_hide_pinned`, `chat_hide_sponsor_reads` | when the chat is drawn again |
| Hide raid notices, Hide gift notices | `chat_hide_raid_notices`, `chat_hide_gift_notices` | at the next chat messages |
| Hide chat badges, Hide live chat | `chat_hide_badges`, `chat_hide_all` | when a stream is opened again |
| Show deleted messages | `show_deleted_messages` | when the chat is drawn again |
| Chat timestamps | `chat_timestamps` | when the chat is drawn again |
| Chat keywords, Highlight mentions, Vibrate on highlights | `chat_keywords`, `chat_highlight_mentions`, `chat_highlight_vibrate` | at the next chat messages |
| Highlight words, Blocked words, Blocked users (text) | `chat_highlight_words`, `chat_blocked_words`, `chat_blocked_users` | at the next chat messages |
| Don't save searches | `no_search_history` | at the next search |
| Resume videos when signed out | `resume_signed_out` | at the next video |
| Remember playback speed | `remember_speed` | at the next speed read or pick |
| Fill screen | `fill_screen` | when the player is laid out again |
| Keep playing during other sounds (a choice; "Pause" is 0) | `audio_focus` | at the next interruption |
| Keep screen on while paused | `keep_screen_on_paused` | at the next pause |
| Sleep timer (a choice in minutes; "Off" is 0) | `sleep_timer` | when playback next starts |
| Refresh stream | `refresh_stream` | when the error panel is drawn again |
| Seek interval (a choice in seconds; "15 seconds (Rumble's)" is 0) | `seek_seconds` | at the next tap |
| Default startup tab (a choice; "Rumble's choice" is -1) | `startup_tab` | after a restart |
| Hide Create tab | `hide_create_tab` | after a restart |
| Hide category rows, Hide creator rows, Hide playlist rows | `home_hide_categories`, `home_hide_creators`, `home_hide_playlists` | when Home loads again |
| Hide Home sections by title (text) | `home_hide_titles` | when Home loads again |
| No autoplay of the next video, No feed previews | `autoplay_next_video_off`, `feed_previews_off` | after a restart |
| Unlock Premium | `unlock_premium` | after signing in again |

Resource patches (Lurk icon, Remove tracking permissions, Clone app, Change app name) change the
APK itself, so they aren't listed.

How it works:

- `rumble/settings/SettingsPatch.kt` is a hidden patch the others depend on. It merges
  `extensions/shared.mpe`, adds the extension's `SettingsActivity` to the manifest (with its own task
  affinity, so the launcher shortcut never opens inside Rumble's picture-in-picture task), and calls
  `Settings.init(context)` first thing in `RumbleApplication.onCreate()`.
- The Lurk row is a fourth `RowItemWithArrowView` after Send Feedback in the `AppInfoSection`
  composable, drawn by a static method the patch adds next to it with the same arguments as Rumble's
  rows. Its onClick is a `kotlin.jvm.functions.Function0` the extension makes with a `Proxy`, since
  the extension doesn't compile against Kotlin.
- Each patch registers its switches at the end of `execute`, so a patch that is left out or fails
  shows none. The settings patch writes them as JSON into `Settings.registryJson()` in `finalize`.
- Injected code calls `Settings.isEnabled(key)` (or `getInt(key)`) and runs Rumble's original code
  when it returns false. Values live in the `lurk_settings` SharedPreferences, written only when
  changed on the screen; unknown keys count as on.
- Restart Rumble (shown after changing a switch that needs it) relaunches the launcher activity and
  exits the process.

Validated on 2026-10-14 on the Android 16 emulator with a local build, every patch except Clone app
and Change app name applied (Gradle still can't reach GitHub Packages):

- The Lurk row appears after Send feedback and opens the screen in Rumble's task; Back returns to Settings.
- The launcher shortcut opens it full screen, also while a video plays in picture-in-picture.
- Hide Shorts off + Restart Rumble: the Shorts tab and Home row come back. On + restart: hidden again.
- The same walk-through (fresh launch, the featured video for 35 s, three scrolls) through the logging
  proxy: with Block ads and Disable analytics on, only Rumble's own hosts and `graph.facebook.com`.
  Both off: `e.rumble.com`, `a.ads.rmbl.ws`, `d4.ads.rmbl.ws`, `imasdk.googleapis.com` and
  `pagead2.googlesyndication.com` are back. Both on again: gone again. Each run started from a stopped
  app, so changes taking effect mid-session weren't checked separately.
- Keep watch progress is disabled while Disable analytics is off. The quality picker, Reset to
  defaults and dark mode (including switching while the screen is open) work.
- `bin/test-rumble` passes on the patched APK. A build with only Clean share links and Clone app
  shows just that switch and works under `com.rumble.battles.lurk`.

## Finding code in 3.21.0

R8 renames Rumble's classes and methods, so the patches below find code through what survives:

- Constructors check each parameter by name (`"eventRemoteDataSource"`, `"rumbleAdRepository"`, …).
- Kotlin data objects return their source name from `toString()` (`"Success"`, `"EmptyVideoAdList"`).
- Enum constants keep their names in `<clinit>` (`"SHORTS"`, `"Tip"`, `"OFF"`).
- Compose functions carry a trace label (`"com.rumble.battles.….ShortsListView (ShortsListView.kt:65)"`),
  matched without the line number.
- Gson DTOs in `com.rumble.network.dto` keep their class and field names; only the getters are renamed.

`shared/Bytecode.kt` has the helpers for these. Each patch fails with a message rather than guess when a
lookup doesn't find exactly one match.

## Disable analytics

Rumble's event repository (constructor strings `eventRemoteDataSource`, `appRequestName`,
`watchProgressDao`) sends every first-party event:

- to `e.rumble.com`: clicks on videos, categories, playlists, related videos, Shorts and the Premium button;
  viewable impressions; downloads; and watch progress. Watch progress carries the advertising ID, volume,
  playback rate, quality and fullscreen state.
- to Rumble's debug log (`debug/log2`): copies of AppsFlyer and Firebase events, plus search result clicks.

Bodies include the installation ID, and most events add the user ID, username and AppsFlyer ID. Only four
of the event use cases check consent, so Block cookie notice doesn't stop these. The patch returns at
the top of the three upload methods: `Success` for event lists (as after an upload, so queued watch time is
cleared rather than retried) and `Unit` for the other two.

The Keep watch progress switch (initially the `keepWatchProgress` option) lets an upload through when its list holds a watch progress event (found
by its `toString`, `"WatchProgressEvent(timeRange="`). Rumble's watch history is server side
(`watch_history.clear`), probably fed by these events; that is untested, as it needs an account.

## Block ads

The ad repository is the one implementation of the interface the video ad use case takes first
(constructor strings `rumbleAdRepository`, `getCustomAdTag`, `createPreRollVideoIdUseCase`). The patch
answers each request with a result Rumble already handles:

- **Video ads** (pre-rolls, mid-rolls and live mid-rolls, played through Google's IMA SDK):
  `EmptyVideoAdList`.
- **Feed and video page display ads** (`a.ads.rmbl.ws` / `a-delivery.rmbl.ws`, which receive the zone,
  keywords, user and video IDs, `ad_device_id` and the consent strings): `WaitingForAds`. Both injectors
  treat it like a failed request.
- **Impression pixels and click/progress beacons**: success. Boosted (promoted) videos stay in feeds,
  but their `d4.ads.rmbl.ws` impressions and clicks are no longer reported.

The Revcontent DTOs in the app are unused.

## Remove tracking permissions

Removes `AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION`, `AD_SERVICES_CONFIG`, the
install referrer permission, the Samsung and Huawei store referrer permissions and `READ_PHONE_STATE`.
It also removes the AppsFlyer referrer `<queries>` entries.

- Without `AD_ID`, Google Play services returns the all-zero ID. That covers every SDK and Rumble's own
  `ad_device_id` (home layout, banners, watch progress); Rumble's reader doesn't filter zeros.
- `READ_PHONE_STATE` is declared by the app but never requested, and no code needs it.
- Every other user catches the failure or checks the permission first.

## Hide Shorts

- **Bottom bar:** Other code refers to tabs by their position in the tab list (`ContentViewModel` saves
  index 0 or 3 as the default tab). So the Shorts entry stays in the list, and the bar's drawing loop
  skips it while still counting it. The Shorts route is found by `toString` (`"ShortsScreen"`).
- **Shorts rows:** `ShortsListView` returns immediately. That covers Home, My Feed, channels, search
  and My Videos.
- **Home sections:** `HomeSectionKind` maps `"shorts"` to `HIDE`, so the section isn't loaded either.
- **Channel tabs:** `Creator.tabs` loses `"shorts"`, unless it is the channel's only tab.
- **Category tabs:** the `SHORTS` entry is removed from the list the category tab builder makes.
- **Search filter chips:** `SHORTS` is removed from the copy of the filter enum's entries used for the chips.

`rumble.com/shorts/…` links still open the Shorts player.

## Hide Premium upsells

- The video page's Premium sheet: the check behind `premium_promo_display_delay_days` returns false.
- `PremiumBannerView` (Upgrade Now on Home and My Feed), `PremiumOfferBanner` (account screen) and
  `DownloadsUpsellView` return immediately.
- Live chat's Premium gift list (`LiveChatConfig.giftList`) is null.

The Premium tag on premium-only videos and the comment/chat paywalls stay, since they describe content.
Premium status itself isn't touched; that's Unlock Premium's job.

## Hide tipping and Rants

- `Tip` is dropped from the list of action buttons the channel and video headers may draw.
- `Creator.tipsInfo` and `VideoSource.wallet` (the Shorts tip button) are null.
- `LiveChatConfigRant.enable` returns false, which hides the Rant button.
- `LiveChatConfigRant.walletEnabled` returns false, which disables paying with Rumble Wallet.

Rants other viewers send still show.

## Disable autoplay

Both options change a DataStore default in `UserPreferenceManager` (constructor strings `autoplayOnKey`,
`playbackInFeedKey`, `videoQualityKey`). The patch follows each key to its field, the field's synthetic
accessor, and the inner class reading it. It then changes the value used when nothing is stored, so
Settings > Playback still works.

- **Next video:** `autoplayOnKey ?: true` becomes `?: !autoplay_next_video_off`.
- **Feed previews:** `playbackInFeedKey ?: ALWAYS_ON` becomes `?: OFF` while `feed_previews_off` is on. Home's featured video asks its
  player builder to play even when feed playback is off; the patch clears that flag, so the featured card
  follows the setting too.

## Default video quality

The player chooses its start source with (sources, last picked height, last picked bitrate, the server's
preferred height, isLive, data saver, live uses Auto). That signature is unique, with the string
`sourceList`.

- With no picked quality, Rumble uses `preferred_video_resolution`, which is 480p on phones.
- The patch fills in the chosen height and the highest bitrate, and Rumble's own nearest-height logic does
  the rest.
- A quality picked in the player is stored app-wide and keeps winning.
- Data saver still takes precedence.

Only starting a video runs the chooser: the player itself, when a Short is created or becomes current, and
the Shorts cache preloading the rendition it will play. A quality picked in the player plays that source
directly and is saved through Rumble's own quality-save use case.

**Live streams too** (`video_quality_live`, initially the `live` option, off). Before it looks at any
height, the chooser returns the Auto source (height and bitrate 0) for a live stream while Rumble's
"live uses Auto" preference is true. That is DataStore `liveVideoUseAutoKey`, read as `?: true`. Picking
Auto in the player stores true; picking a quality on a live stream stores false. While the switch is on
and a default quality is chosen, the patch changes the `?: true` default to false, the same way Disable
autoplay changes its defaults. Live streams then start at the default quality, until Auto is picked in the
player. With the switch off the default is Rumble's again.

**On mobile data** (`video_quality_mobile`, initially the `mobileQuality` option, "Same as on Wi-Fi").
On a metered network (mobile data, or a Wi-Fi hotspot marked as metered) a cap C lowers the quality a video
starts at; it never raises it:

- The start height is what Rumble or the default quality would start at (a saved pick, the default
  quality, or the server's preferred height), lowered to C. A saved Auto counts as C.
- Rumble picks the source nearest a height by the difference of reciprocals, which can land above C when no
  source has exactly that height (480 sits exactly between 360 and 720). So the extension reads each
  source's height (the `resolution` getter of the player source, found through its `toString`) and starts at
  the highest available height at or below the target, or the lowest one if every source is above it.
- While capped, the live-stream Auto return is skipped, so a live stream starts at the nearest rendition at
  or below C, even when Auto was picked. A stream that only has Auto stays on Auto.
- A quality picked in the player still plays, and Rumble saves it as usual. The next video on mobile data
  starts at C again; back on Wi-Fi the saved pick applies, because Lurk never writes `videoQualityKey`,
  `videoBitrateKey` or `liveVideoUseAutoKey`.
- With the choice at "Same as on Wi-Fi", or off a metered network, the chooser gets exactly the values it
  got before.

`QualityProbe` runs the real chooser with fake sources (default quality, a kept pick, live Auto, Rumble's
default), the live default with each setting, and the cap function. The metered check can't be faked under
`app_process`.

## Block update prompts

Rumble can lock old versions out from Firebase Remote Config. `ContentViewModel` (constructor strings
`shouldForceNewAppVersionUseCase`, `shouldSuggestNewAppVersionUseCase`) asks two use cases at startup,
passing the literal `"3.21.0"`:

- **Forced:** `ShouldForceNewAppVersionUseCase` compares `forced_app_version` with that version. If it is
  newer, `com.rumble.battles.landing.ForceUpdateView` replaces the app with an "update required" screen.
- **Suggested:** `ShouldSuggestNewAppVersionUseCase` compares `suggested_app_version`, at most once a day
  (DataStore `lastNewVersionDisplayTimeStampKey`), and shows
  `com.rumble.battles.landing.AppUpdateAvailableAlertDialog`.

Both are found by their Remote Config key and the name their catch block logs, plus the signature: `(String)Z`
and the suspend `(String, Continuation)Object`. While `block_update_prompts` is on (its default) they
return false before reading Remote Config. There is no Play in-app update library in the APK. The patch can't
help if Rumble's API starts refusing old clients; no such check was found in 3.21.0.

`UpdateProbe` calls both use cases with null dependencies: patched, they return false; with the switch off
(or on the stock APK) the original code runs and fails on the missing Firebase setup. The remote flags can't
be flipped from outside, so the blocked screens weren't seen on a device.

## Show exact counts

Every count in the app (video cards and the video page, Shorts like and comment buttons, channel
followers, "watching now") goes through one static formatter, `(long count, boolean keepDecimal)String`. It
returns the number below 1000, otherwise 12K, 1.2M or 3B; it is the only method with all three suffix
strings. No DTO carries a server-formatted count. While `exact_counts` is on, the formatter returns the
count grouped for the device locale (`NumberFormat.getIntegerInstance()`, 12,345).

The narrowest place is the Shorts side rail, which has no fixed width. A test build that showed 1,234,567
for every count kept the rail on screen (x 922-1071 of 1080) without covering the video, so the text size is
left alone. `CountProbe` checks the formatter both ways.

## Clone app and Change app name

Clone app sets the manifest package (default `com.rumble.battles.lurk`) and moves everything another
install would conflict with:

- permissions under `com.rumble.battles.` and `com.rumble.READ_RUMBLE_DATA`, with every reference to them
- provider authorities, including `com.rumble.provider`. That one is also a string in code, which the
  patch rewrites; Rumble uses it for the provider its other apps read.
- intent categories and task affinities carrying the package name

Class names stay. The app ID Rumble sends its API (`com.rumble.battles`, a constant) stays, so the server
sees an ordinary Rumble client. The patch runs in `finalize`, after other patches change the manifest.

Change app name sets `app_name`, the application's label; only `res/values` defines it. Quotes,
backslashes and a leading `@` or `?` are escaped.

## Clean share links

Every share sheet goes through one share use case: videos, live streams, Shorts, reposts, playlists and
channels. It takes `(text, title)`, and `android.intent.extra.TITLE` appears nowhere else in the app. The
patch cuts everything from the first `?` off the link when its host is `rumble.com` or a subdomain; other
links are left alone.

3.21.0 doesn't tag the links it shares:

- The use case gets the server's `url`, or `https://rumble.com/shorts/<id>`, unchanged. `e9s` and
  `src_v1_*` only reach analytics events.
- Shared links checked on 2026-10-14 had no query string, including a video opened from a Home
  recommendation row.

So the patch is a guard in case the server or a later version starts adding tags.

## Validation on 2026-10-14 (new patches)

All built with the local fallback (Gradle still gets 401 from GitHub Packages) against Morphe Desktop
1.18.1. All 14 Rumble patches apply together to 3.21.0.

Network was checked on the Android 16 emulator through a logging CONNECT proxy (the emulator's global
`http_proxy`). Each build ran the same walk-through: fresh install, signed out, Home, a live video for 40 s,
three scrolls, background.

- **Stock:** connects to `e.rumble.com`, `a.ads.rmbl.ws`, `d4.ads.rmbl.ws`, `imasdk.googleapis.com`,
  `pagead2.googlesyndication.com`, Ketch, AppsFlyer, Airship, Crashlytics and `app-measurement.com`.
- **Disable analytics + Block ads:** no `e.rumble.com`, `a.ads.rmbl.ws`, IMA or `pagead2` connections. The
  `d4.ads.rmbl.ws` pixel stopped after impressions were included.
- **All privacy patches:** only Rumble's own hosts (`rumble.com`, `hugh.cdn.rumble.cloud`, `wn0`, `web7`)
  and Firebase installations/Remote Config/logging, `graph.facebook.com` (the Facebook SDK's settings
  fetch) and Google Play services. No crashes, and no ART verifier errors in logcat.
- **`keepWatchProgress`:** one `e.rumble.com` connection, when a video starts; no crashes.
- **UI against stock:**
  - The bottom bar has no Shorts tab, and tabs still select correctly.
  - Home has no Shorts row, Premium banner or video Premium sheet.
  - Search shows All / Videos / Channels; stock adds Shorts.
  - On The Dan Bongino Show's channel, stock has a Tip button and Videos / Shorts / Reposts tabs; patched
    has no Tip and Videos / Reposts.
  - Settings shows Autoplay next video off and Playback in feeds Off; turning autoplay back on persists.
  - The featured card stays still (identical frames 2 s apart) but plays when opened.
  - A regular video starts at 1080p (with 360p / 720p / 1080p / Auto available).
- **Clean share links:** the share use case was called directly from the APK on the emulator
  (`app_process`, a Context that captures the share intent). Patched: rumble.com, `www.rumble.com` and
  Shorts links lose their query string and fragment; `notrumble.com` and `example.com` links are unchanged.
  Stock passes all of them through. Sharing a live stream in the app with all patches shows the clean link.
- **Clone:** `com.rumble.battles.lurk`, "Rumble Lurk" with the Lurk icon, installs and runs next to stock
  Rumble.

Not verified at runtime: category tabs, Rants and the Rumble Wallet (both need an account), the account
screen offer banner, and the downloads upsell.

## Don't save searches

Searches are kept only in the local Room table `queries(id, query, time)`. `SearchViewModel` (a kept name)
takes the use cases that save a search, update its time and read the recent searches. The patch finds each
one's type through the constructor's `checkNotNullParameter` names (`saveQueryUseCase`,
`updateQueryUseCase`, `getRecentQueriesUseCase`), following a copy back to the parameter, and patches
the one public suspend method of each. While `no_search_history` is on (its default), save and update
return `Unit` without writing, and the recent searches are an empty list. The list filtered as you type
filters that list, so it is empty too. Autocomplete suggestions come from Rumble's server and stay.

Stored rows aren't deleted, so turning the switch off shows them again; Clear all in Rumble's search
deletes them. Signed out, Rumble's own logout use case (which also deletes every search) runs at launch,
so the table is emptied at each start whether the patch is included or not.

`SearchProbe` calls the three use cases with no repository: they return before reaching it while on, and
fail on the missing repository while off.

## Resume videos when signed out

Rumble keeps a position per video in the local table `last_positions(video_id, last_position, user_id)`,
also signed out (`user_id` empty). The player saves it through `SaveLastPositionUseCase` when it stops, is
released or another video starts. `DefineCurrentWatchPositionUseCase` (constructor strings
`getLastPositionUseCase`, `sessionManager`) decides where a video starts:

1. It reads the local position.
2. If the server's `lastPositionSeconds` and the duration are both 0, it uses the local position.
3. Otherwise it asks the session whether you're signed in: signed in, the server's position (or the local
   one when the server has none); **signed out, 0**.
4. Near the end of the video it starts at 0.

jadx can't decompile the coroutine, so the patch works from the smali. After the `Boolean.booleanValue()`
of the signed-in check, the false branch is a `move-wide` of 0 over the register pair that holds the local
position on both paths into it. The patch replaces that move with `RumblePlayback.signedOutPosition`, which
returns the local position while `resume_signed_out` is on (its default) and 0 otherwise. Step 4 still
applies, and signed in nothing changes.

## Interface

### Default startup tab

Rumble remembers whether Home (0) or My Feed (3) was last selected, in SharedPreferences `rumble_prefs`
key `defaultTabIndexKey`, and `ContentViewModel` opens on it. The helper's getter is found as the
`()I` method calling `SharedPreferences.getInt` in the class whose constructor names those two strings.
While `startup_tab` is 0 or 3 it returns that instead; at -1 (its default) Rumble's stored tab is used. The
bottom bar's tab list is fixed (Home, Shorts, Create, My Feed, Account), and Hide Shorts and Hide Create
tab keep the positions, so the indexes stay valid.

### Hide Create tab

The bottom bar's tabs are routes: `Discover`, `ShortsScreen`, `CameraGalleryScreen`, `Feeds` and
`Profile`, Kotlin data objects found by their `toString`. Hide Create tab skips `CameraGalleryScreen`
the same way Hide Shorts skips `ShortsScreen` (the shared `hideBottomTab`): the entry stays in the list
and keeps its position.

### Hide Home sections

Home is a list of server sections, `HomeSectionDto(kind, title, ...)`, with kinds `videos`, `shorts`,
`categories`, `creators`, `playlists`, `ad_block` and `hide`. Most named rows (Editor Picks, Live, News, ...)
are `videos` sections with server titles; the app hardcodes no slugs. The patch passes the `kind` getter
through `RumbleInterface.homeSectionKind`, which returns `hide` for a categories, creators or playlists row
whose switch is on, or a section whose `title` (read by its field name) matches an entry of
`home_hide_titles`, ignoring case. A `hide` section is neither loaded nor drawn, as Hide Shorts relies on.

`InterfaceProbe` runs the real startup tab getter (with a stored My Feed) and the section `kind` getter with
each setting.

## Player

### Remember playback speed

The speed menu lists the nine constants of Rumble's speed enum (`SPEED_0_25` to `SPEED_2`, with `NORMAL`).
A Kotlin object keeps the picked speed per scope (video page, other) in memory; its setter and getter are
found through their `videoScope`/`speed` null checks, and the player service resets every scope to 1x. The
player takes the stored speed unless the video is live without DVR. While `remember_speed` is on, a pick is
also kept by name in the SharedPreferences file `lurk_rumble` (Lurk's own state, not the settings), and the
getter returns it for every scope, also after a restart. While off, picks don't replace Lurk's saved speed
and the getter uses Rumble's own value. The saved value is retained: turning the switch on again restores
the last speed picked while it was enabled, until another enabled pick replaces it. With no saved value,
Rumble's own speed applies until that first enabled pick.

### Fill screen

The phone players are Media3 `PlayerView`s that Rumble always gives the FIT resize mode (the custom
`SurfaceView` with its own FIT/ZOOM is only used on TV). Rumble sets the mode once and only again when it
changes, so the patch changes it where `AspectRatioFrameLayout` (inflated from XML, so the name is kept)
reads it in `onMeasure`. While `fill_screen` is on, in landscape and outside picture-in-picture,
`RumblePlayer.resizeMode` turns FIT into ZOOM: the video fills the screen and the edges are cropped. Each
rotation re-measures, so portrait goes back to FIT.

### Keep playing during other sounds

`RumblePlayerService` (named in the manifest) is the player's `OnAudioFocusChangeListener`: it resumes on
any gain and pauses on any loss. Its `onAudioFocusChange` asks `RumblePlayback.ignoreFocusLoss` first.
The `audio_focus` choice: 0 (Rumble's), 1 ignores transient losses (`LOSS_TRANSIENT` and
`LOSS_TRANSIENT_CAN_DUCK`), 2 ignores every loss. A loss is never ignored while the phone is in a call or
ringing (`AudioManager.getMode()`). Unplugging headphones still pauses.

### Keep screen on while paused

A static helper adds or clears `FLAG_KEEP_SCREEN_ON` on the activity of a `Context` (found as the
`(Context, boolean)` method calling both `Window.addFlags` and `clearFlags`). The player's state effect
calls it with "on unless paused or finished, outside an ad"; it is found as the caller that checks the state
against the Paused class (whose `toString` starts with `Paused(isBuffering=`). The state classes are
package-private, so the effect reads the state itself (in a register that is dead after the call) and passes
it to `RumblePlayer.keepScreenOn`, which keeps the screen on for Paused while `keep_screen_on_paused` is on.
The end of a video and leaving the player still let the screen turn off.

### Sleep timer

The same choices and behavior as Twitch's, in the shared `SleepTimer`: playback pauses the chosen time
after it last started or resumed. The player service's `Player.Listener` (the inner class checking states 1
and 4 in `onPlaybackStateChanged`) reports `onIsPlayingChanged` to it. To pause, the extension sends
`KEYCODE_MEDIA_PAUSE` through `AudioManager.dispatchMediaKeyEvent`: it reaches Rumble's media session and
its own pause path, also in the background and in picture-in-picture. Twitch's sleep timer uses the same
class with Twitch's own pause action.

### Refresh stream

When playback fails, the player draws `MobileErrorView` (Back and Settings; Replay only after the end).
While `refresh_stream` is on (its default), the panel's modifier gets Compose's `clickable`, resolved from
the App Info rows as for the Lurk row, and a tap reloads the player at the same point:

- The player's quality switch (found by its `videoSource` null check and `RumblePlayer` log tag) plays a
  source again, re-prepares and seeks to the current position. The patch adds a public static method to the
  player class that calls it with the current source; the extension reaches it through a bridged stub,
  because the player's fields are private.
- The quality switch also reports the source to Rumble's quality-change callback, which saves it as your
  pick. The added method swaps that callback for one that does nothing during the call, so a reload saves
  no quality.
- The player's load method (the one asking the start-source chooser) records which player loaded which
  video, so a panel reloads its own player.

Rumble retries network errors itself: with the network cut, live streams and VODs keep buffering for
minutes without the error panel and resume when the network returns. The panel, and so the reload, is for
errors the player gives up on.

### Seek interval

The player's rewind and forward buttons call its seek methods through their default-argument twins, which
fill in 15,000 ms; TV remote keys pass their own 10 s. The patch passes the twins' constant through
`RumblePlayer.seekMillis`, which returns the `seek_seconds` choice while one is set. The buttons' icons
(`ic_backward`, `ic_forward`) draw the arrow with strokes and "15s" as a filled path. A resource patch adds
copies with only the arrow (`lurk_seek_back`, `lurk_seek_forward`, with the helper Twitch's player
gestures use), and the icon reads (the R class fields with those IDs) return a copy while another interval is
chosen. At "15 seconds (Rumble's)" the buttons and icons are Rumble's own.

`PlayerProbe` runs the real speed store with the switch on and off, and the focus and keep-screen-on
decisions. `RefreshProbe` checks the clickable panel modifier and the silent callback.

## Live chat

Rumble's live chat arrives as JSON events on a chat endpoint (`web7.rumble.com` in testing). Gson parses
them into DTOs in `com.rumble.network.dto.livechat`, which keep their field names; a mapper turns each
`LiveChatMessage` into a `LiveChatMessageEntity` (a Kotlin data class, found through its `toString`), and
Compose rows draw the entities.

**The chat hook** is a hidden patch that Hide chat clutter depends on. The getters of
`LiveChatMessageData.messages` (each event's messages) and `LiveChatInitData.messages` (the history sent on
joining) pass their list through `RumbleChat.messages`. It drops the messages a switch hides before any
entity is built, so they don't count as unread either. It returns Rumble's own list while nothing is hidden,
and the same filtered list when the getter runs again on the same DTO. Switches of patches that weren't
included count as off there, because unknown keys normally count as on.

### Hide chat clutter

| Switch | How |
|---|---|
| Hide pinned messages | `PinnedMessageSectionView` (the pinned message above the chat) returns at once. |
| Hide raid notices | The chat hook drops messages with a `raidNotification`. The raid countdown, the auto-join dialog and Rumble's own Auto-join Raids setting stay. |
| Hide gift notices | The chat hook drops messages with a `giftPurchaseNotification`. The notice for your own gift stays. |
| Hide sponsor reads | `HostReadsMessageView` (`host_ad_read` events: a creator's sponsor spot with a product link) returns at once. These aren't ad-network ads, so they stay out of Block ads. |
| Hide chat badges | `LiveChatUser.badges` is empty. The message row tints messages from users with the `recurring_subscription` badge, so that tint goes too. |
| Hide live chat | `Video.liveChatDisabled` (`is_chat_disabled`) is true, as for a stream whose creator turned chat off. The video page shows the description and related videos instead, and Rumble no longer connects to the chat endpoint. |

Event types aren't remapped: an unknown type becomes a `LiveChatError` event, and whether that triggers a
reconnect wasn't traced.

### Show deleted messages

`LiveChatViewModel` marks a message deleted when moderators delete it or mute its sender
(`deleted = deletedIds.contains(id) || mutedUserIds.contains(userId) || deleted`), and the entity keeps the
original text. Two rows draw `R.string.live_chat_deleted_message` ("Message was removed by moderator")
instead: the deleted message row and the Rant row. After each `stringResource` call the patch passes the
label and the entity to `RumbleChat.deletedLabel`, which appends ": " and the text while
`show_deleted_messages` is on. The text comes from the entity's `message` getter through a bridged stub.
The row lambda keeps the entity in a captured field; in the Rant row it is the register of the nearest call
on the entity, checked not to change before the label. The label is a plain `String`, so there is no
strike-through.

### Chat timestamps

`LiveChatContentView(modifier, ..., message, userName, ...)` draws a message row. The regular and the
deleted message rows call it from lambdas that capture the entity; the patch follows the `userName`
argument back to the entity's `userName` getter and passes the name and entity to
`RumbleChat.nameWithTime`. While `chat_timestamps` is on it puts the time first (`H:mm` or `h:mm` after the
device's 24-hour setting) with the name's color and weight. `timeReceived` is already local time: the mapper
parses the DTO's ISO `time` and converts it to `ZoneId.systemDefault()`. Rants, notifications, pinned
messages and Premium purchase messages get no time.

### Chat keywords

The same switches, texts and defaults as Twitch's Chat keywords, matched by the shared `ChatFilter`
(whole words, any case; blocked users without the `@`):

- **Hiding:** the chat hook builds a `userId` to `userName` map from the DTO's `users` and drops messages
  from blocked users or with blocked words, live messages and the join history alike. They never become
  rows, can't be @-mentioned and don't count as unread.
- **Highlighting:** at the start of `LiveChatMessageView(modifier, entity, ...)` (its one caller passes
  the modifier) `RumbleChat.highlight` returns Rumble's modifier, or for a highlighted message
  `Modifier.background(Rumble green at 30%)` around it. The background call is the one the Rant row uses,
  resolved at patch time by its signature; the extension reaches it through a bridged stub. Rows that
  aren't highlighted keep Rumble's modifier instance, so Compose can still skip them.
- **Mentions:** the live chat event mapper `(time, event, baseUrl, currentUserId, creatorName)` gets the
  signed-in user's chat ID (`_u` and the user ID in base 36). The history sent on joining lists the
  current user among its `users`, which gives the name to match (`@name`, or the name as a word). Signed
  out there is no ID and no mention.
- **Vibrate:** a highlighted live message (not the history) vibrates, once per message and at most every
  three seconds.

The patches that read entities share one hidden patch that fills the extension's getter stubs (text,
sender, time), so each is filled once. `KeywordsProbe` runs the hook, the learned username, mention and
word highlights and the real Compose background modifier with each setting.

`ChatProbe` runs the real DTO getters (live messages, history, badges, `liveChatDisabled`) and the bridged
entity getters with each switch on and off, including the hook with the keys unregistered.

## Validation on 2026-10-16

Built with the local fallback (Gradle still gets 401 from GitHub Packages) against Morphe Desktop 1.18.1,
with every Rumble patch except Clone app and Change app name, and run on the Android 16 emulator signed out.
Lurk switches were set by writing `lurk_settings.xml` as root. `tests/verify_extension_calls.py` confirms
that app code calls every new extension method.

- **Block update prompts:** `UpdateProbe` passes. The app starts and plays normally with the switch on.
- **Show exact counts:** Home shows "3,339" watching instead of "3.3K". Shorts side rail checked with a
  forced 1,234,567 (see above).
- **Default video quality:**
  - Live streams too on: a live stream (360p, 720p and two 1080p renditions) starts at 1080p. Off: Auto.
  - On mobile data (Wi-Fi off; the emulator's cellular network is metered), cap 480p: the same live stream
    starts at 360p, its highest rendition at or below the cap. A VOD starts at 480p instead of 1080p.
  - Picking 1080p in the player plays it. The next VOD on mobile data starts at 480p again, and with Wi-Fi
    back on the next one starts at the saved 1080p.
  - A Short on mobile data starts at 480p.
- **Chat timestamps:** live chat rows show "2:30  StantheJokemanShow", matching the emulator clock (12-hour).
- **Hide chat clutter:** Hide chat badges removes every badge (10 on screen before, 0 after) and the
  subscriber tint. Hide live chat shows the video page without chat, and the proxy log has no
  `web7.rumble.com` (chat) connection; with the switch off it is opened 3 s after the stream starts.
  Pinned messages, raids, gift notices and sponsor reads didn't occur during testing; `ChatProbe` covers
  the raid and gift filter.
- **Show deleted messages:** no message was deleted while watching; `ChatProbe` covers the label.
- **Chat keywords:** on a busy live chat, highlight word "the" tints every message with the whole word "the"
  (on a subscriber's row it blends with Rumble's tint); blocked word "the" leaves none of the 14 visible
  lines with it. Mentions need an account and weren't tried; `KeywordsProbe` covers them.
- **Don't save searches:** with the switch on, a search for "news" leaves `queries` empty and the search
  box shows no recent searches. Off, "podcast" is stored and listed under Recent. Hiding kept rows is
  covered by `SearchProbe`: signed out, Rumble had already deleted the row at the next launch.
- **Resume videos when signed out:** search, watch the first result for 40 s, start the second (which saves
  the first at 41 s), go back and open the first again: it continues from 0:41 with the switch on and starts
  at 0:00 with it off. Positions survive a restart.
- **Remember playback speed:** 1.5x picked on one video; after a force-stop the next video plays at 1.5x
  (media session speed 1.5). Switch off: 1.0.
- **Fill screen:** a 16:9 video in landscape fullscreen on the 20:9 emulator fills the width with the switch
  on and is letterboxed with it off; portrait is unchanged.
- **Keep playing during other sounds:** another process took audio focus while a VOD played. Rumble's
  default pauses on a transient request; choice 1 keeps playing through it but pauses for a permanent one;
  choice 2 keeps playing through both.
- **Keep screen on while paused:** with a 15 s screen timeout (and stay-awake-while-charging off), a video
  paused for 30 s keeps the screen awake with the switch on; off, the screen goes to sleep.
- **Sleep timer:** checked with a test build that counts minutes as seconds: with "15", Rumble logs "sent
  pause" and its media session goes to PAUSED 15 s after playback starts; Twitch logs "paused 1 native
  players" 15 s after a stream starts.
- **Default startup tab:** "My Feed" opens Rumble on My Feed after a cold start, "Home" on Home.
- **Hide Create tab:** the bottom bar shows Home, My Feed and Account (with Hide Shorts); each still opens its
  own screen.
- **Hide Home sections:** with category and creator rows hidden and the titles "Editor Picks, sports", Home loses Editor
  Picks, Sports and Top Channels and keeps the other twelve sections. No categories or playlists row was on
  Home during testing.
- **Refresh stream:** cutting the network (Wi-Fi and data off) for four minutes during a live stream, and two
  and a half during a VOD, left both buffering, with no error panel; both resumed when the network returned.
  The reload was checked with a test build that triggers it 15 s after a video loads: playback carries on
  from its position, and Rumble's DataStore gets no quality keys.
- **Seek interval:** at 30 seconds the forward button moves a VOD about 30 s ahead and the buttons show
  arrows without "15s"; at Rumble's setting it moves about 15 s. Twitch's generated seek icons are
  byte-identical with the shared helper.
- **All together:** with every Rumble patch except Change app name, 30 patches apply; each new patch also
  applies on its own (`--exclusive`). All probes pass on the full build, and `verify_extension_calls.py`
  finds every new hook called from app code. With Manager's default selection, `verify_settings.py` passes
  and the Lurk screen shows each new setting in its section at its default; a setting that needs a restart
  shows Restart Rumble. The default build's walk-through (Home, a video, scrolling) connects only to Rumble's
  hosts, `graph.facebook.com` and Firebase logging, without crashes or verifier errors. The Clone app build
  installs and runs next to Rumble.
- **Twitch, after the shared ChatFilter and SleepTimer:** `bin/test-twitch`, `test-twitch-chat`,
  `test-twitch-chat-style` and `test-twitch-playback` pass, and Twitch's app code patches byte-identically
  to before.

Retained refresh integration regression: `bin/test-rumble <patched.apk> RefreshProbe` executes the
actual emitted video-load hook, error-panel prefix, click/reload bridge and stock `x4` on ART.
Player/state/Compose boundaries verify source URL, full-width seek position, per-video mapping,
repeated taps, disabled/null cases and preserved quality callbacks. The generated reload now restores
its callback in a catch-all handler too: stock `x4` catches ordinary Exceptions, but an escaping Error
or failure inside its logger previously left the no-op callback installed. These exceptions are
synthetic regression inputs; no live occurrence is claimed. `tests/rumble/verify_refresh.py` checks
emitted catch metadata and the fatal-state branch to the mobile panel. The
[dated verification record](verification-2026-10-17.md) adds real emulator network failure,
fatal-panel tap and continuing nonzero playback under an explicit three-method error-policy
fixture. Unchanged production recovered automatically from the observed outage; natural fatal
entry, physical-phone and authenticated/Premium behavior are not established by that fixture.
