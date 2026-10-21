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
JDK 21 and Android SDK. Temporary output belongs in `~/build/lurk-morphe-patches/`. Runtime code
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

### Twitch delayed-moderation boundary (Task 6)

The pinned ordinary-viewer store already preserves received bodies when it marks visible
normal chat lines deleted. Its row chooses the placeholder without moderation attribution;
the RN consumer must change presentation only for verified received, unmasked content,
keeping `deleted:true` and all stock account/action restrictions. A separate private
122-byte predicate removes received-but-delayed messages before that visible-row path.
Its clear-chat predicate is independent and must remain independent.

A guarded helper plus the exact original fallback does not fit those 122 bytes. The
approved bounded extension compiles one predicate with the pinned HBC98 compiler,
widens string operands into the original string table, recalculates its own branches and
exception boundaries, and appends only its body/full header/exception table. It preserves
the original function ID and parent environment slot zero (the moderation event), and
redirects that function's compact header. No nested functions, new strings, switch tables,
or other external-table dependencies are supported. Missing helpers, a throwing global
getter/function, unmarked colliding globals, and invalid results execute the stock predicate.
The bootstrap must collision-check the private numeric global and install the exact marker.

HBC98's full header is 37 bytes with flags at +36; HBC99's is 36 bytes with flags at +35.
Retained fixtures compile with both pinned families to distinguish these layouts. The
HBC98 transplant drops donor debug offsets, leaves original global debug data in place,
and updates file length and SHA-1. Disabling retention restores the original compact
header, truncates the appended storage, and restores the original file length/footer;
with all settings off the complete file must equal the pinned stock bytes. A helper-only
or disassembler-only pass does not close actual target store and Fabric rendering gates.


### Twitch RN consumers and ownership (Task6)

Direct writable owners now include rows3425, body3626, timestamp3619, store3446, metrics3426,
badge registry3621, stage5010 and column5013, discovered by unique export signatures. Scope keyword
vibration to the original store's newly delivered line handlers with native owner/channel validation;
never trigger it during history rendering or retained deletion. Native snapshots remain passive,
provider acquisition is explicit and disposed interests prevent queued new requests. Preserve shared
native demand. Bounds include50 queued provider jobs,1000 row leases,16 rooms/stores,2000 snapshot
emotes and8192 wide-emote ratios.

Task7's measured landscape contract adds unique owners overlayHost4974, theatreStore4589,
ClassicChatColumn4285 and ChatArea4587. The original floating host owns the genuine store but
its ChatLine render path omits store/channel and projects away type. Preserve the original host
type/key and original store-hook call/return; publish the genuine committed store with immutable
numeric-destination/login authority through tree-local context. Match canonical message identity
and protection fields before recovering row eligibility. Retired publishers must not clear a
replacement; stale destinations fail synchronously before effects. No global render provenance,
network-on-render or weakening of received/protected/action gates.

Column appearance follows the actual Fragment/background/foreground tree and exact
ClassicChatColumn View→ErrorBoundary→LatencyNode→ChatArea path. For nondefault25/50/75 opacity,
use existing black background and alpha theme foreground, clearing only the two covering fills.
Keep header/composer, text/emotes, player, gutters, actions and unrelated siblings stock. A stable
React Provider carries opacity or neutral null; no native View is added. Neutral paths preserve
child/native-style/action identity while installed wrappers intentionally retain the Provider;
full configure-off restores original exports. Guard all owner capabilities/shapes before mutation.
Floating one-chat opacity remains stock; these settings apply only to landscape-column-chat.
The dated verification record supplies actual rendered controls and explicit fixture/source limits.

Native overlay ownership uses the measured viewer feeder field, stream-model destination emission
and onDestroyView. The observer branch's complete two-successor tail proves v2/v3 dead while preserving
v0/v1/v6 and has no exception handlers; unsupported shapes fail patching. Same-feeder view recreation
uses a new owner state so stale callbacks stay retired. Quiet navigation is observable at model emission
or view teardown; no earlier navigation-intent timing claim is made.


Provider completion uses one runtime-scoped, cache-only2s revision poll while committed row owners
exist, not one timer/bridge call per row. The small token combines emote generation, badge/pronoun
cache publication and opt-in state. Changes refresh mounted subscribers; unchanged polls perform
no catalog fetch, acquisition or network. The last release, off and native module disposal stop it.
Display latency is bounded by the polling interval after publication; existing provider TTL/retry
refresh still occurs on explicit acquisition, retaining last valid data between requests.
