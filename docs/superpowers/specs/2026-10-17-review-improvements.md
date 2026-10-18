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

## Pinned chat integration design

Inspection of the original bundles established the following implementation contracts.
Module numbers below identify evidence in the pinned versions; patch-time discovery
must use unique exports/signatures and the existing bundle hash checks, rather than
assuming that a number is portable to another app version.

Kick uses the global `chatRoomStore` (module 3232) for its composer, picker and
autocomplete. Extend its existing `emotesSections`/`allowedEmotes` catalogs and wrap
the stock insertion actions for 7TV names, preserving draft, UTF-16 selection,
authentication, editability, replies and native-emote behavior. Synthetic emote IDs
map through the mutable `ImageCacheV2.getImage` class method (module 2716) to validated
7TV CDN URLs. Preserve stock-name precedence so name-only insertion callbacks remain
unambiguous. The context-only `useChatStore` hook is not a global store.

The Kick WebView supplies catalog snapshots to a narrowly scoped native receiver;
validate source origin, document generation, channel identity, size and URL allowlists.
Forward only catalog/configuration data to an RN callable module. Do not pass chat,
drafts, credentials or arbitrary executable code through this bridge. Navigation and
disable transitions remove the old catalog and close timers, fetches and event sockets.
Live changes use the 7TV EventAPI plus bounded revalidation/refetch, preserving the last
valid catalog during transient errors. Rendering supports active-set and emote-data
zero-width flags and never crosses text, link, newline or message boundaries.

Twitch's live RN surface is `ChatMessageRow` (module 3425), backed by the shared chat
store (3446) and `ChatArea` (4587). Timestamp formatting, deleted-message state,
keyword filtering/highlighting and nested message appearance must integrate at their
actual row/store/body boundaries. Detailed moderation state alone does not establish
ordinary-viewer retention: verify that path and preserve unavailable AutoMod content.
Reuse existing provider caches and the RN IRC/ImageSource emote path; the pinned
message segmenter uses Unicode code points. Provider/badge adapters expose bounded
cached snapshots and perform no network operations on the JS thread. Existing VOD
navigation reaches native replay; no separate RN VOD renderer has been established.

Both apps load a small additional compiled Hermes bundle after the main bundle,
through their existing bridgeless `ReactInstance` loader hooks. Cover embedded and
prepared-file paths, preserve source URL, avoid recursive loading, and scope native
runtime references to the instance lifetime. Verify execution before initial chat
consumers capture exports; replacing global `__r` alone cannot intercept captured
Metro imports. Mutate supported writable exports/class methods or use narrowly
fingerprinted hooks where exports cannot safely be wrapped.

Pin official `hermes-compiler` packages separately: Twitch HBC98 uses
`250829098.0.19`; Kick HBC99 uses `260318099.0.4`. Resolve the actual platform binary
inside the mise-managed package (the package has no npm executable shim), verify the
compiler and emitted bytecode header, and build assets from committed JS source.
Compiled bytecode avoids relying on unverified native raw-source evaluation support.
Actual app-runtime smoke tests must establish loader ordering, module contracts,
normal startup, reload cleanup, picker images and disabled behavior before release.

### Kick pinned hook details (Task 5)

Discover the Metro factory registrations from unique exported names: chatRoomStore /
useChatroomStore, ImageCacheV2, useSessionStore / AppSessionTiming, and
getInternalInstanceHandleFromPublicInstance / getNativeTagFromPublicInstance. Validate
__d factory/id/dependency register flow against the hash-locked HBC99 bundle. The
composer permission guard reads the current inputRef.getNativeRef() through the latter
host helper and requires stateNode.canonical.currentProps.editable === true, in addition
to authenticated session status. That prop is stock canSendMessage && !needToAcceptRules;
focus is deliberately not a permission test because the picker closes the keyboard.
Use the pinned AndroidTextInput Commands.setTextAndSelection with the current native
ref, committed mostRecentEventCount, raw result and UTF-16 cursor, then stock
setInputContent, store setSelection and ref.focus. Never call send. Wrap setEmotes with the synchronous listener
suppressed until its nested updateRecents finishes, then merge the provider section once.
Channel/document changes clear only owned emote data; preserve draft and reply.

Append the compiled bootstrap directly after native main-bundle load in the asset
(default and prepared-file) and file delegate branches. Preserve the original sourceURL.
The extra asset uses the native ReactInstance asset method directly, avoiding recursive
delegate loading. A weak instance registry is cleared on destroy; configure and catalog
calls use NativeArray data through the bridgeless callable-module API.

Runtime verification refined the stock image boundary to the exact `files.kick.com`
and `files.cdn.kick.com` hosts. Preserve provider animated metadata: a stock PNG/static
request maps animated sources to `2x_static.webp`, while naturally static sources retain
`2x.webp`. The picker section needs the supported `global` icon as well as its 7TV name.
Android's updateExtraData preserves the collapsed cursor's distance from the old text end.
A null-text selection command before replacement clamps against the old length; a later
span replacement can move it to the new end. Discover the unique Commands /
__INTERNAL_VIEW_CONFIG / AndroidTextInput Metro factory (pinned module58), verify its
view name and callable command, and require a nonnegative integral current host event
counter. Set new text and cursor atomically through Commands.setTextAndSelection before
the stock setter commits its children/spans. The subsequent same-length replacement
preserves both the insertion cursor and later deliberate cursor movements. Do not keep
a timed correction or pending selection override. Native target checks must prove this
ordering, manual end/old-length movement, and mixed native-emote draft preservation.


Task 5 lifecycle refinement after review: successful explicit no-set responses, or a valid
provider identity with an omitted optional set, clear the channel catalog. A valid set
identity with an omitted empty emote vector normalizes to an empty catalog. Transient and
malformed responses retain the last valid data. Subscription reconciliation removes only
retired set IDs. Bind native augmentation to the input-ref container and mounted instance
at the current page association; stock reset or ref replacement retires that generation.
The initial ref may attach after association within the same container. Ordinary native
catalog rebuilds preserve this authority. Validated pagehide and the exact pinned
RNCWebView.destroy boundary end only their own document's native catalog authority.
