# Drive on the web — parity with the KMP and iOS apps

**Status:** ⚠ this said "Draft v0.1 (2026-08-27). **No code.** Sweep + phasing only." until
2026-09-14, by which point most of §4 had been built. Read that line as the doc's origin, not its
state.

⚠⚠ **§4's per-phase markers are the state, and they are not a clean landed/outstanding split.**
Most phases that shipped shipped *in part*, and each says which part in its own heading — **W5**
(sort + layout done, paging blocked), **W6** (presence shipped, evict blocked on a missing
signal), **W7** (download streams to disk, upload still memory-bound), **W8** (the achievable
half), **W9** (first slice), **W12** (the bridge envelope is in and `SyncBanner` is migrated and
origin-verified; the primitives above it and the remaining pollers are not), **W13** (the reset is
unified, the naming and reachability are not).
Done outright: **W2a**, **W2b**, **W3**, **W4**, **W10a**. **W10b** is the one where only the
marker exists (`RelayRefusal.DEVICE_REMOVED`) and the routing is still open. **W1** and **W11**
carry no marker of their own — W1 is verified cross-device in §5, and W4 records the W11
projection as in use. ⚠ Take a phase's own heading over any summary, this one included.

⚠⚠ The feature tables in §2 still carry their pre-work ❌ marks for W1, W2, W3, W11 and W12 and
have **not** been re-scored; read them as the original survey, not as current state.
Original framing follows.

**Owner:** Benedikt

**Relationship to other docs.** [`drive-web-app.md`](drive-web-app.md) built web Drive
(Phases A–C, 2026-06-24→26) and [`drive-web-mode-b-sharing.md`](drive-web-mode-b-sharing.md)
added folder sharing (MB-1…MB-5). Both are **complete as scoped**. This doc covers the
**drift since**: 78 commits landed in `drive-shared-logic` between 2026-06-26 and today, and
one of them (C7) moved thumbnails onto a
plane the web runtime does not have. It does not restate those docs' architecture — the thin-SDK
fork is **locked** (`drive-web-app.md` §2) and nothing here reopens it.

---

## 1. The symptom, and what it actually is

> *"Drive on the apex does not seem to sync thumbnails and binaries."*

Both halves are the **same defect**, and it is one argument.

`FileSystem.getPreview` on web reads the file's bytes and renders a thumbnail in a canvas
([`FileSystem.kt:171`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/FileSystem.kt#L171)
→ [`CanvasPreview.kt`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/CanvasPreview.kt)).
No bytes ⇒ no thumbnail. So "thumbnails don't sync" is not a thumbnail defect at all; it is
"binaries don't sync" seen through a row icon. Fix the bytes and the thumbnails follow —
which is why §5 does not phase them separately.

**Bytes don't move because the personal content plane is unwired on JS, in both directions.**
The web runtime calls `initSyncService` without a `contentBlobStore`
([`FirstPartyPlugins.kt:292`](../../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/FirstPartyPlugins.kt#L292)),
and that single omission severs both directions:

- **Inbound.** `manifestApplier` is built only when a content store *and* a file database are
  both present ([`SyncProxyInitialization.kt:540`](../../sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncProxyInitialization.kt#L540)).
  With it null, an arriving `FileContentManifestPayload` is a **clean no-op**. Everything
  upstream of that works on web today — `OnDemandResolutionImpl` fetches the entity's blocks,
  the personal processor is published into `syncProcessorHolder`, the terminal re-resolves —
  and then the one step that would write bytes into `FileDatabase` is missing, so
  `File.Data` resolves `null` and `readFileData` returns null.
- **Outbound.** `SyncObserver.emitFileContent` opens with
  `val personalStore = contentBlobStore ?: return false`
  ([`SyncObserver.kt:1222`](../../sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncObserver.kt#L1222)),
  so a browser upload writes its bytes to IndexedDB, emits its metadata block, and **never
  offers the content**. The row appears on the phone; the file is empty there forever.

⚠ **The asymmetry is what makes this hard to see.** A file uploaded *in the browser* opens
fine *in the browser* — the bytes are in the local `IndexedDbFileDatabase` and never needed
the plane. Only cross-device reads fail, and they fail as a null rather than an error.

### 1.1 A second, independent defect: web-uploaded files are thumbnail-less *everywhere*

Post-C7, both thumbnail classes are minted **once, on the device that has the bytes, at
upload**, by dispatching `GetPreview` with `GENERATE_FRESH`
([`UploadFileImplementation.kt:503`](../../file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/UploadFileImplementation.kt#L503)).

`GetPreview.ID` is registered in the **Android, iOS and JVM** factories. There is no
`jsMain` source set in `:file-service-proxy` at all — so on web the dispatch finds no
implementation, the `try` swallows it, and **no `File.Thumbnail` / `File.ThumbnailLarge` is
ever minted for a file uploaded from a browser.** Wiring the content plane (§1) does not fix
this: there is nothing on the plane to fetch. Android and iOS will show the generic category
icon for every browser-uploaded file, permanently.

⚠ This is the reverse-direction twin of §1 and needs its own fix. The renderer already
exists — `CanvasPreview.generatePreview` produces the same shape Android's implementation
does; it is simply not reachable as a `GetPreview` actual.

### 1.2 What C7 changed for the web, and why it was invisible

Before C7, `File.Thumbnail` was a stored
value on the **metadata** plane. C7 moved both classes to `dynamicProperty(Syncable(OnDemand))`
on the **content** plane, after the C6 census measured them at 97.26 % of every byte a `File`
row occupies ([`drive-synced-thumbnails.md`](drive-synced-thumbnails.md) §11).

⚠ **This did not regress web** — and it is worth saying so, because the timeline invites the
conclusion. Web never consumed `File.Preview`; it has always hand-rolled the canvas path from
local bytes, so it never received the pre-C7 eagerly-synced thumbnails either. What C7 changed
is **where the fix has to happen**: before C7 the web fix was "use `File.Preview` instead of
canvas"; after C7 it is "wire the content plane, *then* use `File.Preview` through C7's
two-read cold protocol" (§11.2 of that doc — projecting `File.Preview` alone on a cold device
resolves to `null` *forever*, because the dynamic-property path is synchronous and cannot await
a fetch).

### 1.2b ⚠⚠ The tab has to be in front — web sync is a page timer

Measured 2026-08-27 from the preview proxy's relay log. A browser left open but
**backgrounded** stops syncing altogether. Successive gaps between polls:

> 2.0 s → 4.5 s → 9.6 s → 25.3 s → **5 m 45 s** → **11 m 37 s** → nothing (55 min and counting)

The app cannot produce the last three. `SyncScheduler`'s ceiling is `pollingIntervalMs =
30 s` (1.5 s while a stream is hot) and its rejection backoff caps at 2 min — the JS runtime
overrides neither. Gaps of 6 and 12 minutes are **Chrome throttling and then freezing a
hidden tab**, and the sync loop lives on that tab's main thread: there is no Worker, no
Service Worker and no `visibilitychange` handling anywhere in the JS runtime or the apex.

⚠⚠ **No native platform has this failure mode**, which is why it appears in no existing
doc: on Android and desktop sync runs in Control, and on iOS route-1 it has a background
drain. On web the *app window is the proxy*. So "new files stopped arriving" on web has a
benign and very common cause — the user switched to another window — and from the user's
side it is indistinguishable from sync being broken.

⚠ It also makes any web sync bug hard to diagnose: a quiet relay log means "the tab was not
allowed to run", not "nothing happened". Check poll spacing before reading anything else
into it.

### 1.2c ⚠⚠ There is no diagnostics on web — which is why every bug here costs a day

Raised while debugging §1.2b, and it is the reason that debugging was expensive.

`:diagnostics` declares a `js { browser() }` target, so it *could* compile — but it has no
`jsMain`, `:tenshu-sdk-js` does not depend on it, and its only consumers anywhere are
`timeline-android`, `timeline-shared-ui` and `timeline-shared-data`. `:logging-service` is
not a web dependency either, and the level-gated `Log` facade
([`core-logging-facade.md`](core-logging-facade.md)) is not built on any platform.

So the web's entire logging story is **four raw `println` calls in the JS SDK plus six in
`SyncObserver`'s commonMain**, landing in a browser console that no one but the user can
read and that is gone on reload.

⚠⚠ The practical cost, measured today: a dead sync chain was invisible for over an hour.
The relay was silent, the proxy log was empty, and both were consistent with three
completely different causes (a frozen tab, a broken content plane, a misconfigured relay
URL). It took building an ad-hoc console sink and a postMessage tap to tell them apart —
tooling that exists because the platform's own diagnostics surface does not reach here.

⚠ Scope the claim honestly: `:diagnostics` is **Timeline-only** even on device, so this is
not "native has it and web doesn't" across the board. It is "the one place a maintainer
cannot attach a debugger is also the one place with no durable log".

### 1.2d ⚠⚠ A browser's realm identity lives on storage the browser may delete

Measured 2026-08-27 by reading the origin's IndexedDB directly from an automated browser.

A web device's identity is **not** a cookie or a session. It is a device signing keypair plus
master-key material in five per-origin IndexedDB databases — `tenshu-sdk-master-keys`,
`tenshu-sdk-secure`, `tenshu-sdk-session`, `tenshu-sdk`, `tenshu-sdk-files`. So "this device"
means *this browser profile on this origin*, and nothing narrower survives: clearing site
data, a fresh profile, or incognito each produce a different device.

⚠⚠ **`navigator.storage.persisted()` returns `false`, and neither `web/apex` nor
`:tenshu-sdk-js` ever calls `navigator.storage.persist()`** — grep finds no `StorageManager`
use anywhere. The master keys therefore sit on **evictable** storage: Chrome may delete them
under pressure, with no prompt and no recovery. On Android and iOS the equivalent keys live in
KeyStore/Keychain, which the OS does not evict. This is a durability gap with no native twin.

⚠ **Correction (same day, before anyone builds on it): eviction is NOT a permanent lockout,
and the first version of this section said it was.** The reasoning was that the relay's F2′
check refuses a removed `deviceId` on both the join and offer routes — true — and that
eviction would therefore strand the browser. It does not. `currentDeviceId` is a random id
persisted in the *same* origin storage as the keys (`SyncManagerImpl` → the secure-storage
delegate → `tenshu-sdk-secure`), and browsers evict an origin whole. So eviction takes the
deviceId with the keys, the next visit mints a fresh identity, and re-pairing works.

The genuine dead-end needs the identity to **survive** while realm membership is removed —
which is the iOS Keychain case the relay comment describes, not this one. Web's equivalent
(owner removes a browser that still holds its keys) does have an in-app remedy: **"Disconnect
this browser"** purges local state and mints a new identity, verified here by reading the
origin's IndexedDB before and after. That is web's RG-1, and it exists.

**What is left, and is still worth fixing:** eviction silently destroys the device and
everything local to it — including any state not yet pushed — and recovery requires re-pairing,
which requires a host device able to admit. A browser can quietly forget the realm between two
visits, with no warning beforehand and no explanation afterwards.

⚠⚠ **And the remedy is not reachable from the failure — observed 2026-08-28.** A browser whose
row the owner removed keeps its `deviceId` (it lives in the same origin storage as the keys and
nothing told it otherwise), so the next join attempt goes out under the dead identity and the
relay refuses it on both routes. What the user sees is `Offer-response failed: 403 …` on the
*host* device, and nothing anywhere offers the one action that works.

The client never looks. `RestSyncRelay` wraps the status into
`GenericError("Offer-response failed: ${response.statusWithReason()}")` — a terminal, precisely
diagnosable condition flattened into an opaque string. ⚠ The codebase already has the pattern
for this: `ENTITLEMENT_REJECTION_MARKER` turns the 402 into something callers branch on, and
`UNAUTHORIZED_REJECTION_MARKER` does it for the 401. F2′ has no equivalent.

⚠ **The fix is not "stop reusing the identity".** The offer flow reuses it deliberately — that
flow exists for a device the relay has *forgotten* (a lost registration), where minting a new id
would churn a device row for nothing. What is missing is the distinction between a **transient**
rejection and a **terminal** one: after F2′ the identity is permanently dead for that realm, and
continuing to present it can only fail. So mark the rejection, and on it offer "start fresh" —
⚠⚠ **but not before the approver has been told.** Landed the same day and reaching the same
finding from the native side,
[`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)
§7h.2 shows the two fixes cancel: the F2′ refusal reaches the **approver**, so it is the only
moment the system says *"this is a device you removed"* to the person deciding — and routing the
user straight to "start fresh" means the retry arrives under a **new** id and the approver learns
nothing. F6 sequences it (approver told → then start fresh) and marks F6-4 as must-not-ship-first.
Read §7h before building this half. ⭐ The marker itself is F6-2's twin and the gap this doc names
— F2′ having no equivalent of `UNAUTHORIZED_REJECTION_MARKER` — is the same one, so build them
together rather than twice. ✅ **Built 2026-08-28**: `RelayRefusal` in `:core-common` (a constant
both the relay and the client compile against, so it cannot drift the way the `401` reason phrase
did) + `DEVICE_REMOVED_REJECTION_MARKER` / `isDeviceRemoved()`. ⚠ The first cut tagged the
**join** route only; the offer-response route — the one this section's `Offer-response failed:
403` actually comes through — got the token on 2026-09-03, pinned on both sides
(`PairingOfferRoutingTest`, `RestSyncRelayUnauthorizedContractTest`). ⏳ The "start fresh"
routing half is still open, and §7h.2's ordering still governs it.

### ⭐ DECISION: web removal is deliberately more complete than native's, and that is the point

Taken 2026-08-28. The browser is **two populations wearing one runtime**, and the same removal has
to serve both:

* **A borrowed computer.** The user signed in somewhere they do not control. Removal must let them
  **destroy everything, easily and permanently** — that is the whole reason someone reaches for it,
  and a half-purge that leaves realm data in an origin they are walking away from is the worst
  possible outcome.
* **A PWA, or someone who simply never clears their browser.** For them the browser is a device
  like any other, and it should behave like one.

⇒ **Web keeps the destructive default and adds the transparent recovery**, rather than choosing.
The recovery matters most for the case F2′ handles worst: someone who removed a device **by
mistake** — the phone turned up again — and would otherwise re-download the entire realm to get
back to where they were. Making that path transparent costs the attacker almost nothing, because
they still have to get a human to approve them; it costs the honest user an afternoon of syncing.

⚠⚠ **The security this preserves is exactly and only "hard to get back in without significantly
tricking the user".** Not "impossible" — `ios-identity-reinstall-gate.md` D2 already settled that
"start fresh" needs no credential, so a holder of the device can always mint a new identity. See
[`admission-revocation-names-the-capability.md`](admission-revocation-names-the-capability.md)
§7h.1 for why that is fine and §7h.2 for the ordering that keeps the approver informed — which is
the actual control.
the same shape iOS needed as RG-1. Web's purge already exists ("Disconnect this browser", which
does mint a new identity — verified by reading the origin's IndexedDB before and after); it is
simply never offered at the moment it is needed.

⚠ This tightens §1.2d's own correction: saying web "has an in-app remedy" was mechanically true
and practically misleading. A remedy nobody can find from the error is close to no remedy.

⚠ Related UI defect seen in the same session: a **stale pairing request stays approvable**.
The relay keeps pending requests without a TTL, so a request from an already-removed device
remains in the host's list, and pressing Approve fails server-side every time with the F2′
message and no visible progress — it reads as "Approve does nothing".

### 1.2e ⚠⚠ "0 B on every row" — reproduced locally, and the obvious fix does not work

**Reproduced 2026-08-27 with no pairing, no relay and no sync**: a fresh browser, "Just try it
locally", upload a 4242-byte file, and the listing renders `probe-4242.bin | 0 B` seconds later
with the bytes sitting in that same browser. So this is **not** W1, not the content plane, and
not a cross-device problem — it is a listing defect that has affected every file on web since
Phase C. ⚠ The date is missing for the same reason and by the same mechanism; the row reads
`0 B` rather than `0 B · <date>` because `.filter(Boolean)` drops the empty half.

**Root cause.** `File.Size`, `File.CreatedAt` and `File.LastModifiedAt` are `valueProperty`
declarations, not fields of the `File` composite. `FileSystem.list` takes raw composites and
`toJs()` flattens `composite.fields`, so no `size` key is ever emitted and
`formatBytes(undefined)` renders 0 B. Native never had this: `TenshuFilesRepository` projects
exactly those three with `select { relations(...) }`. Same schema, two readers, one of them
missing a rule — the structural cost of the §2 fork, now with a measured instance.

⚠⚠ **The one-line fix does not work, and this was tried, not assumed.** Adding native's
`select { relations(File.Size, …) }` to `FileSystem.list`:
  1. first fails `Field tenshu/file/path not found` — a projected composite carries only what is
     asked for, and `path` is a composite FIELD while the sizes are value PROPERTIES;
  2. adding the `fields(File.Field.PATH, Folder.Fields.FILES)` half then fails with a
     `ClassCastException` in `JsRuntimeTest`, because a projected field arrives as a `Composite`
     and the walker's `is Composite -> data.toJs()` branch **nests** it — so `entry.path` becomes
     an object rather than a string.

The `recentFiles` comment was right all along: *"the walker is only proven against the raw
composites `list` returns."* So the fix is one of — teach `toJs()` to flatten projected
composites, or give the listing an explicit typed mapper mirroring native's `fileMapper` and stop
routing it through the walker. Both are real changes with their own risk, which is why this is
its own phase and was reverted out of W1.

⚠ A guard belongs with it: nothing today compares the JS reader's emitted field set against the
native mapper's. Both sides compile, both suites pass, and the divergence surfaces only as a
wrong number in a UI nobody diffs. `File.LastModifiedAt` sits right next to `Size` and is broken
the same way — the next value property will be too.

### 1.2f ⭐ The mapper is not a second style — the SDK already moved to it

Worth stating because the obvious objection to W11 is "now half the SDK walks and half maps".
That is already true, and the mapper half is the newer half:

| Uses `toJs()` (the schema walker) | Uses an explicit mapper |
|---|---|
| Devices, Files, FileSystem, Notifications, Storage — the Phase A–C surfaces | **Contacts, Timeline, SharedFiles, Membership — zero `toJs()` between them** |

`Timeline.moments` is the reference shape: `select { fields(*MOMENT_FIELDS) }` → `map(::momentOf)`
→ a **typed** `TimelineMomentJs`. The surfaces that are broken are exactly the ones that never
migrated.

⚠ The walker's supposed benefit — not hand-writing the mapping — does not hold, because the JS
*type* is hand-written anyway (`web/sdk/src/files.ts`). The shape is asserted by hand and produced
by machine with nothing checking they agree, which is the whole defect. It also derives keys from
the schema id's last segment, which is how `tenshu/file/lastModifiedAt` met a TS type declaring
`lastModified`. A mapper names keys explicitly and returns a typed value, so both classes vanish.

⚠ Open question, bigger than W11: the field *reads* in a `momentOf`-style mapper are identical to
native's. If the reading half lived in a module with a JS target (`file-service-common` rather
than `drive-shared-logic`), both platforms could share it and only the final object construction
would differ — closing the §2 divergence structurally instead of by convention.

### 1.2g Reactive shape — the bridge has no push channel at all

Separate axis from the mapper, and the constraint is in the transport. `web/sdk/src/bridge.ts`
defines **four** message kinds — `hello`, `ready`, `req`, `res`. There is no server→client
envelope, so **nothing can be subscribed to across the origin split**: the Drive subdomain can
only poll, which is why "snapshot + refetch over TanStack Query" is the pattern.

⚠ In-apex is different: `Devices` and `Notifications` already expose
`observe(callback): () => void` — the conventional unsubscribe-function contract. So the *shape*
exists on one side of the bridge and cannot cross it.

⚠⚠ **But only one of those two is push.** `Notifications.observe` collects a real chain `flow()`;
`Devices.observe` is a **4-second poll loop** (`POLL_INTERVAL_MS`) wearing a callback. Corrected
2026-08-31 — this section previously called both "real push from the Kotlin runtime", and W12's PR
repeated it. The distinction matters when picking what to migrate: a subscription backed by an
apex-side poll moves the poll, it does not remove it.

**There is no single web standard to adopt, but there are standard primitives per job:**

| Job | Web-standard primitive | Status here |
|---|---|---|
| one-shot read | `Promise` | ✅ used everywhere |
| change notification | `subscribe(cb) => unsubscribe` | ⚠ in-apex only (`observe`) |
| ordered/paged sequence | `AsyncIterable` (`for await`) | ❌ absent |
| byte streams w/ backpressure | `ReadableStream` (WHATWG) | ❌ absent — W7's natural home |

⚠ Observables are a **TC39 proposal**, not a standard; RxJS is a library. Neither belongs in the
core contract.

**End state: a framework-neutral core plus thin idiomatic wrappers.** `@tenshu/sdk` speaks the
primitives above; a separate `@tenshu/sdk-react` supplies hooks. That keeps House Rule #5 intact
(the protocol layer is shared, the UI layer is not), keeps the core usable by a third-party app
over the bridge, and matches how SDKs normally ship. ⚠ `observe` is *close* to what React's
`useSyncExternalStore` wants but not a drop-in: its `subscribe` callback takes no arguments and
the value comes from a separately-supplied `getSnapshot`, which must be referentially stable or
React re-renders forever. That adapter is precisely the wrapper package's job.

⚠⚠ **Prerequisite for any of this reaching Drive: a fifth bridge message kind** plus subscription
lifecycle (ids, cleanup on disconnect, re-subscribe after an apex reload). That is a protocol
change and its own item — see W12.

### 1.2h "Forget this device" exists as a shared op — and the web reimplements it

Raised by the maintainer 2026-08-28 as a consistency question; it is one.

**There is a shared operation.** `ForgetThisDevice` lives in `sync-service-proxy/commonMain`
with its own implementation, an `Ro` in `sync-service-client`, and
`SyncManagerImpl.forgetThisDevice()`. Control and iOS both go through it. **`:tenshu-sdk-js`
contains zero references** — the web hand-rolls `Session.clear({ purgeRealm: true })`, which
`clearAll()`s four IndexedDB stores.

⭐ **At rest the web is fine, and that is luck rather than design.** `CUSTODY_SLOTS` names
eleven keys, all held by the secure-storage delegate; the web clears that whole store, so its
coverage is a *superset* and cannot drift. But the native list exists precisely because it
already drifted once — its own comment: *"the two hand-maintained lists drifted while both
claimed they could not (iOS was missing SEEDED_REALM_EPOCH). One definition, both callers."*
Web is a **third caller that was never folded into that construction**.

⚠⚠ **In memory it is not equivalent, and this half is real.** `forgetThisDevice()` also nulls
`derivedKeys`, `currentMasterKeyPair`, `currentOwnerId`, `realmEncryptionKeyPair`,
`sessionRecoveryPhrase`, `admissionKeyPair` and `admissionCertChain` — because, as it says, *"the
session must not keep signing with keys that no longer exist at rest."* The web purge does none
of that.

⭐ **Resolved 2026-08-31 — and two of this section's claims were already stale.**

  - *"`LockedFlow.onDisconnect` finishes with an SPA navigation, not a reload, so the `JsRuntime`
    singleton survives the purge intact"* — **no longer true.** Both purge call sites
    (`LockedFlow.onDisconnect`, `DeviceMenu.wipeAndLeave`) call `reloadAfterPurge('/')`. That was
    fixed in an earlier phase of this same track; the section was not updated with it.
  - The `currentDeviceId` hypothesis is **confirmed at the code level, and it is narrower than it
    read**: `override val currentDeviceId: String = storageDelegate.getString(DEVICE_ID) ?: …` is
    resolved once at construction, `hlcManager = HLCManager(currentDeviceId, …)` is bound to it,
    and `forgetThisDevice()` removes `DEVICE_ID` (the first entry in `CUSTODY_SLOTS`) **without**
    resetting either. So a live `SyncManagerImpl` is inconsistent with its own storage from the
    moment it forgets — memory holds an id that no longer exists at rest.
    ⚠ On web this is now inert, because both purge paths reload. ⚠⚠ It is **not** obviously inert
    on a native host whose proxy process outlives the purge, which is a separate question this
    track cannot answer.

⚠ A device-pass observation that looked like the above — a browser still listing its old realm's
files after a reset — turned out to be **the observer's fault**, not a purge bug: that run never
successfully reached "Disconnect this browser", so nothing was purged. Recorded because it is a
cheap mistake to repeat: `device_unrecognized` renders over a *cached* listing, so a dead browser
looks like a working one.

⚠ **Naming and reachability are tangled too.** Native says "Forget this device"; web says
"Disconnect this browser" — and apex *separately* has "Disconnect from the Tenshu Relay?" under
Services, a different action entirely. Worse, `locked.disconnect.*` renders **only in
`LockedFlow`**: a browser that is unlocked but dead server-side cannot see the one control that
fixes it without locking first.

### 1.2i ⚠⚠ `GetPreview` has two call paths and only one of them can await

This is the constraint W2b turns on, and it outlives W2b. `GetPreview` is reached two ways:

1. **The dispatcher path** — `executionContext.execute*(GetPreview.ID, …)`. This is what
   `UploadFileImplementation.renderThumbnail` uses to mint the ladder at upload. It **can**
   await: `ExecutionContext.executeAsync` already existed, and resolves the `AsyncReference`
   that the sync `execute` returns for a non-background `AsyncOperationImplementation`.
2. **The in-chain path** — `File.Preview`, a *synchronous* dynamic property resolved through
   `findOperation<GetPreview>`. It **cannot** await, by construction.

A browser has no synchronous image decode, so a JS `GetPreview` can only ever be an
`AsyncOperationImplementation`. Path 1 is therefore fine and path 2 is not: against an async
implementation `File.Preview` resolves an unawaited `AsyncReference` instead of bytes.

⚠⚠ **Nothing on web may project `File.Preview`.** Read the stored classes
(`File.Thumbnail` / `File.ThumbnailLarge`) instead — they go to `GetStoredThumbnailImplementation`,
which lives in the *common* factory, is wired on web already, and is synchronous.

**Trait vs implementation kind — do not confuse these.** `IoOperation`
(`core/…/trait/IoOperation.kt`) is a **schema trait**: adding it changes the operation's contract
for every platform and every caller at once, and is what House Rule #3 pairs with `TaskInteractor`.
`AsyncOperationImplementation` is an **implementation kind**, one of three, chosen per-platform by
the factory — `ExecutionContext` dispatches on the implementation's *type*, not on any trait. W2b
needed the second, which is why it touches no schema and no native implementation. Making
`GetPreview` a real `IoOperation` would be the honest model *if* in-chain previews ever had to
resolve asynchronously, but that is gated on the chain evaluator being able to await at all
(`@defer` Ph5 / the `streamed(...)` sketch) — a core track, not a web slice.

**Known duplication this leaves behind.** The stored-class selection (`storedThumbnail` /
`coveringStoredThumbnail`) is a *private copy in each of the three* native implementations, and
the ladder's px constants are private to `UploadFileImplementation`. Web now carries a fourth,
deliberately minimal echo (one threshold in `FileSystem.kt`). Unifying all four into commonMain is
the follow-up — worth doing, but it touches three platforms' preview behaviour and did not belong
in a web phase.

### 1.3 Already fixed today — do not re-file it

The relay refused **every** web write with `402 entitlement_required` for as long as
enforcement has been on: `RestSyncRelay`'s `entitlementProofProvider` defaults to `{ null }`
and `tenshu-sdk-js` passed none. The fix — the browser presents the proof — landed
at 15:11 today (`7f38f902`), verified
against the production relay as `[jwt]` 402 ×6 → `[jwt+proof]` 200 ×6.

⭐ **It is a prerequisite, not a separate track.** The content-plane `PUT` is entitlement-gated
too (`ContentRouting.kt:66` `resolveEntitlement`), and `RestContentBlobStore` already accepts
an `entitlementProofProvider` for exactly that header. W1 consumes the provider that fix built.

⚠ Open, and **not** web-specific: `sharedRealmContentBlobStoreFactory` passes no entitlement
provider on **any** platform, so Mode-B shared-realm content writes are unproven under
enforcement. Verify before assuming W1 covers it.

---

## 2. The parity sweep

Native reference: `FilesRepository` (34 operations) + the `files` / `filedetails` / `home` /
`movepicker` / `settings` / `shared` screens. Web surface: 42 bridge handlers in
`proxyGateway.ts` + 40 components under `web/drive/src/components/`.

| Capability | Native | Web today | Phase |
|---|---|---|---|
| Browse, navigate, breadcrumbs | ✅ | ✅ | — |
| Create / rename / move / delete, multi-select | ✅ | ✅ | — |
| Upload (single file) | ✅ | ✅ | — |
| Details + activity | ✅ | ✅ | — |
| **File size + date in the listing** | ✅ | ❌ **every row reads `0 B`, no date** | **W11** |
| **Subscribe to changes from the app subdomain** | ✅ reactive IPC | ❌ **bridge is req/res only** | **W12** |
| Mode-A link sharing + manage | ✅ | ✅ | — |
| Mode-B folder sharing, contacts, grants | ✅ | ✅ | — |
| Inline preview (image / PDF / text) | ✅ | ✅ *(local bytes only)* | W1 |
| **Cross-device file bytes** | ✅ | ❌ **inert both ways** | **W1** |
| **Thumbnails on a byte-less device** | ✅ | ❌ | **W1** |
| **Thumbnail generation on upload** | ✅ | ❌ **no JS `GetPreview`** | **W2** |
| Transfer progress (`contentTransfer`) | ✅ | ❌ | W3 |
| Sync health banner (`syncHealth`) | ✅ | ❌ | W3 |
| Schema-skew banner (`schemaFence`) | ✅ | ❌ | W3 |
| Blocked / locked realm banner | ✅ | ❌ | W3 |
| Storage banner + quota gating | ✅ | ⚠ read-only Settings section | W3 |
| `syncNow()` | ✅ | ❌ | W3 |
| **Sync while not in the foreground** | ✅ (proxy / bg drain) | ❌ **stops when the tab is hidden** | **W8** |
| **A durable diagnostics log** | ⚠ Timeline-only | ⚠ sink shipped; scheduler not routed | **W9** |
| **Identity survives normal OS/browser behaviour** | ✅ KeyStore / Keychain | ⚠ `persist()` requested + reported (W10a) | W10b |
| **One shared "forget this device"** | ✅ `ForgetThisDevice` op | ❌ **reimplemented, in-memory half missing** | **W13** |
| Search | ✅ | ✅ | — |
| Sort menu + list/grid + persistence | ✅ | ✅ (⚠ local-only, no PreferenceService) | — |
| Paged listing + scroll restoration | ✅ (SR-1…SR-5) | ❌ `listPaged` deferred | W5 |
| Keep-offline / evict / `hasLocalData` | ✅ | ⚠ presence only (evict blocked) | W6 |
| Cache size limit | ✅ | ❌ | W6 |
| Streaming large files to disk | ✅ | ⚠ stream handlers bridged, no sink | W7 |
| Folder upload | ✅ | ❌ | W7 |
| `Placement` "Add to my Drive" | ⏸ deferred natively too | ❌ | — |
| Synced Folders / photo backup | ✅ Android | N-A | — |

---

## 3. Decisions

- **D1 — W1 is the whole of the reported bug, and it is one seam.** Wire the personal
  `contentBlobStore`; do not restructure the web byte path around it. Everything else in the
  chain already works.
- **D2 — the web keeps the native thumbnail ladder, it does not fork one.** `CanvasPreview`
  becomes the JS `GetPreview` *actual*, reached through the ordinary op dispatch, rather than a
  parallel path called directly from `FileSystem.getPreview`. One class policy
  (`drive-synced-thumbnails.md` §10.1's invariant) on every platform.
- **D3 — ⭐ the web is the one platform where the schema-skew banner can have a button.**
  `core-schema-skew-surface.md` records "no platform has an update route, so no button". A
  browser's update route is a reload. W3 should ship the affordance the native surfaces cannot.
- **D4 — parity is not uniformity.** Synced Folders and photo backup are OS-integration
  features with no browser equivalent and are **out of scope**, not deferred. Say so in the UI
  rather than leaving a hole where a native user expects one.

---

## 4. Phases

**W1 — the personal content plane** *(the reported bug; blocks nothing else)*
Build a personal `RestContentBlobStore` in `JsRuntime` — same shape as
`sharedRealmContentBlobStoreFactory`, but `ownerIdProvider = { syncManager.currentOwnerId }`
and carrying the same `entitlementProofProvider` — and pass it to `initSyncService`. Inbound
gets a `manifestApplier`; outbound emit stops returning false. Verify **both** directions
against a real relay: a phone upload opens in the browser, a browser upload opens on the phone.

**W2a — read the stored thumbnail classes** ✅ *done*, ⚠⚠ **but it never worked until W2b —
see the correction below**
`FileSystem.getPreview` now mirrors `TenshuFilesRepository.getPreview`: ask for the stored class,
then on a miss run C7's two-read cold protocol (`relations(File.Thumbnail, File.ThumbnailLarge)`
through a terminal, *then* read `eager(File.Preview(...))`) so the OnDemand classes materialise
over W1's content plane. ⚠ `eager(...)` is load-bearing — `File.Preview` is deferred-by-default
and a one-shot `.get()` resolves null without it. Before this the web went straight to
`readFileData` + canvas, which rendered nothing on a byte-less device and, after W1, would pull a
whole 3 MB photo to make a 40 px row icon; the class is ~7 KB.

⚠⚠ **Correction (W2b).** That is what W2a *intended*; on web it never happened. The stored-class
read projected `File.Preview`, and web had **no `GetPreview` implementation at all** — so
`ExecutionContext.execute` raised *"No implementation found for operation"*, `readPreviewJs`'s own
`onFailure = { null }` turned it into a silent null on **every** call, and every thumbnail the
browser ever displayed came from the local-bytes fallback. The 3 MB-photo-for-a-row-icon cost W2a
set out to remove is exactly what web was paying the whole time. W2b moves the read onto
`File.Thumbnail` / `File.ThumbnailLarge` (§1.2i), which are served by the shared, synchronous
`GetStoredThumbnailImplementation`. ⭐ The lesson is the recurring one: a read wrapped in a
catch-all cannot distinguish "no data" from "this path is not wired", so it reports success at
being empty.

**W2b — generation on web** ✅ *done*
A browser upload now mints the stored ladder, so a file uploaded on web arrives on Android, iOS
and desktop **with** its thumbnails. Three parts:

  - **Shared (`commonMain`, one file).** `UploadFileImplementation.renderThumbnail` becomes
    `suspend` and dispatches through `executeAsync` instead of `execute`. Its three call sites did
    not move — they were already inside `override suspend fun execute`. ⚠ It also needs a
    `CancellationException` rethrow ahead of its best-effort `catch (e: Throwable)`: once the
    function suspends, a cancelled upload surfaces there, and a swallowed cancellation reads as
    "still running" to every layer above (the "Registering your realm" failure mode).
  - **JS.** `JsGetPreviewImplementation`, an `AsyncOperationImplementation` over the existing
    suspend `CanvasPreview.generatePreview`, registered in `FilesPlugin.operationFactory`.
    Native is untouched: the three `SyncOperationImplementation`s dispatch through the sync branch
    and `executeAsync`'s await loop returns their value on the first pass.
  - **The read.** `readPreviewJs` moves off `File.Preview` onto the stored classes — required by
    §1.2i, and the fix for the W2a correction above.

⚠⚠ **Format is a cross-platform contract, not a preference.** `CanvasPreview` hardcoded
`image/webp`; the ladder's rungs each name a format, and these bytes sync to devices whose
encoders differ. `generatePreview` now takes the caller's mime type; WebP stays the default only
for `FileSystem.getPreview`, whose output never leaves the browser.
⚠ The rungs are **all JPEG** since 2026-08-31 — `LIST` was PNG, which is lossless and therefore
~9× larger on photographic content. See `tech-debt.md`; the earlier justification here ("the JVM
cannot decode WebP") was wrong and is corrected there.

⭐ The tests assert the **encoded format**, not "non-empty bytes", and that is the whole point:
`getPreview` returns bytes either way — minted class or WebP fallback — so only the format
distinguishes a working mint from a silently broken one. Verified by deleting the registration and
re-running: both fail, on the format assertion, which is also the direct evidence for the W2a
correction.

**W3 — the banners, and the state behind them** ✅ *done*
`syncHealth` is bridged and rendered: `sync.health` → `SyncBanner`, which speaks for
`device_unrecognized`, `locked`, `needs_attention` and `offline`, and stays deliberately silent
for `online` and `deferred`.

⭐ The signal was never missing. `initSyncService` has always called `initSyncHealth`, so the
single-row `SyncHealthStatus` has been maintained in the browser's store since Phase A with
nothing reading it — which is why a browser could sit in `device_unrecognized` for hours behind a
normal-looking file list on 2026-08-28.

`contentTransfer`, `schemaFence` and `syncNow()` landed too, so **W3 is complete**.

⭐ The fence banner is the one thing web can finish that no native platform can.
`core-schema-skew-surface.md` records *"no platform has an update route, so no button"* — true of
Android, desktop and iOS, and untrue here, because a browser's update route is a reload. It ships
with the button. ⚠ The fence outranks connectivity in the banner: a fenced device is *reachable*,
so it reads `online` while syncing nothing.

⚠ `syncNow` is offered **only** when offline — a kick cannot help a locked realm, a refused
entitlement or a device the relay does not know, and a button that provably does nothing is worse
than none. ⚠⚠ It is also **not** a fix for §1.2b: a backgrounded tab still stops syncing, and this
helps only someone who already noticed.

⭐ The fence banner is the one thing web can finish that no native platform can.
`core-schema-skew-surface.md` records *"no platform has an update route, so no button"* — true of
Android, desktop and iOS, and untrue here, because a browser's update route is a reload. It ships
with the button. ⚠ The fence outranks connectivity in the banner: a fenced device is *reachable*,
so it reads `online` while syncing nothing.

⚠ `syncNow` is offered **only** when offline — a kick cannot help a locked realm, a refused
entitlement or a device the relay does not know, and a button that provably does nothing is worse
than none. ⚠⚠ It is also **not** a fix for §1.2b: a backgrounded tab still stops syncing, and this
helps only someone who already noticed.

**W4 — search** ✅ *done*
`FileSystem.search` mirrors `TenshuFilesRepository.searchFiles` exactly — `traverse()` +
`filter { name.contains(needle, ignoreCase = true) }` + the W11 projection, so the predicate runs
proxy-side and only matching rows cross the bridge. ⚠ Matches the derived **name**, not the path:
a path match surfaces every unrelated descendant of a folder whose name contains the needle.
⚠ `ignoreCase` is an explicit opt-in — `contains` defaults case-sensitive. ⚠ A full traverse per
query, not an index: the same trade native made, and on web the resident-set ceiling bites before
the CPU one. The UI debounces at 250 ms so a burst of typing costs one traverse, not one per key.

**W5 — listing at scale** ⚠ *sort + layout done; paging is blocked, and not on web*
Sort (name / modified / created / size, each with its own default direction) and a list/grid
toggle, persisted in `localStorage`.

⚠ **The persistence is a deliberate divergence.** Native stores these as synced per-device
settings; `:preference-service` has **no JS plugin**, so on web these choices stay on one browser
and do not follow you to another device. Wiring a preference plugin is its own slice.

⚠⚠ **`listPaged` is NOT simply "add `drop().take()`", and the blocker is shared with native.**
Drive's listing is folders-first, which reorders the *whole* list — so a page is a slice of the
wrong thing, and per-page grouping floats page 3's folders above page 1's files. That is exactly
the open item in `core-ordered-window-fusion.md` ("Outstanding: Drive's `foldersFirst` two-source
paging"), so web is not lagging here: it inherits an unsolved problem. Building naive paging would
ship a visibly scrambled listing. ⚠ Native's own paged path avoids it only by dropping
folders-first and using natural store order, with explicit sorts taking a whole-folder path.

**W6 — custody** ⚠ *presence shipped; evict is blocked on a missing signal*
`hasLocalData` is bridged and surfaced per row — a cloud mark on files whose bytes are not on this
device. ⚠⚠ Probed **lazily, per visible row**. The op's own contract demands it, and not for web
reasons: resolving presence eagerly across a listing tripped Android's Binder watchdog and forced
the original cloud icon's removal. A batched variant was written here first and deleted
— fewer round trips does not make eager resolution cheap, it moves the cost.

⚠⚠ **Evict is NOT shipped, and the reason is a data-loss hazard, not effort.**
`retention-quota-model.md` §2 says tier 3 is "always evictable and never authoritative" because
eviction is free — re-fetch from tier 2. **That premise fails for content that has not reached
tier 2 yet**, and `EvictFileBytesImplementation` is a bare `fileDatabase.deleteFile(path)` with no
guard. On web this is live rather than theoretical: a browser can sit in `402` for hours with
blocks queued (observed 2026-08-28), and during that window its local bytes are the *only* copy.
Evicting them is the durable-content/cache conflation (`retention-quota-model.md` §2) wearing different clothes.

A safe guard needs a **per-file** "has this content reached tier 2" signal, and none exists —
`ContentTransferStatus` carries only aggregates (`pendingFileCount`, `deferredContentBytes`).
⚠ This is a cross-platform gap that web merely makes obvious; native's evict has the same hole.

⚠ A per-device cache ceiling is deliberately not proposed for web: the browser already manages
the origin's quota and evicts on its own terms (§1.2d), so a second ceiling underneath it fights
a manager it cannot see.

**W8 — sync that survives a background tab** ✅ *the achievable half is done* — ⚠⚠ **and the
phase's own goal was not achievable as written** *(§1.2b)*

What shipped:
  - `SyncScheduler.notifyForegrounded()` — a **gate-neutral** wake in shared code. ⚠⚠ It is
    deliberately weaker than its two neighbours: `forceDrainOnce()` is the user's explicit "sync
    over cellular" override and `notifyEntitlementCredentialChanged()` clears the 402 backoff.
    Calling either on every foreground would silently defeat a Wi-Fi-only policy or hammer a
    relay that is refusing the device — **coming back to a window is not consent to either**.
    The wake is conflated, so tab flapping collapses to one tick.
  - `installForegroundWake` in the JS runtime: a `visibilitychange` listener that wakes the loop,
    and logs both edges to `:diagnostics` (§1.2c — a quiet relay log means "the tab was not
    allowed to run", and until now there was no way to tell that from "nothing happened").
  - An honest freshness surface in `SyncBanner`: "Last synced *N* ago" / "Not synced yet" once a
    healthy tab passes 2 minutes without a sync, naming the cause in the user's terms. It keeps
    its **own clock** — React Query's structural sharing means an unchanged
    `lastSyncedAtMillis` does not re-render, so the age would otherwise freeze at exactly the
    moment it started mattering.

⚠⚠ **"So a hidden tab still drains" cannot be built, and nobody should try again.** A frozen tab
runs *no code* — no timer, no coroutine, not even the `visibilitychange` handler. This is not a
cadence that needs tuning; it is the browser declining to run the page. What W8 removes is the
*second* half of the failure: on return the loop used to wait out the rest of its tick, so the
user saw a stale list and concluded sync was broken.

⚠ **A Service Worker is not the missing piece.** On web the runtime *is* the proxy — store,
crypto, schemas, the whole Kotlin/JS bundle — so "sync in the SW" means hosting a second copy of
it against the same IndexedDB, with the two-writer problem that implies. And the API that would
actually schedule it, Periodic Background Sync, is Chrome-only and gated on an installed PWA plus
engagement heuristics, so it is opportunistic rather than a cadence. Real background sync on web
is a PWA track with a worker-hosted runtime, not a slice of this one.

⭐ `lastSyncedAtMillis` was already on `SyncHealthStatus` and already bridged to Drive — the third
signal on this track that existed with nothing reading it (cf. W3, W6). ⚠ It was also `0` in the
shared banner-test fixture, harmlessly, right up until something read it: a default that means
"unspecified" becomes a claim the moment a field is consumed.

**W9 — a diagnostics stream that reaches the browser** ✅ *first slice done* *(§1.2c)*
`:diagnostics` has a `jsMain` sink, `:tenshu-sdk-js` depends on it, and `Diagnostics.recent()` /
`exportText()` / `clear()` are exported.

⚠⚠ **`localStorage`, not IndexedDB as this phase originally said.** `DiagnosticSink.append` must be
*synchronous* — the crash handler calls it while the process is dying — and every IndexedDB write
is async, so an IDB-backed sink cannot honour the contract. `localStorage` is the only synchronous
durable store a browser offers. ⚠ The line format moved to a shared `DiagnosticLine`, so the file
and web sinks cannot drift into two readers of one format (§1.2e's shape).

Remaining, and it is where the value is: **route the sync scheduler's state changes through it.**
The lines that would have solved 2026-08-28 — "reporting this device offline", "the relay does not
recognise this device", "push rejected 402" — are `println`s in `sync-service-proxy`'s commonMain,
which has no `:diagnostics` dependency. Adding one is a cross-platform decision, not a web slice.
⚠ Today the web log carries boot + relay target only: a spine, not a diagnosis.

**W10a — make the browser's identity durable** ✅ *done*
`ensurePersistence()` asks once from the signed-in shell and the Services page reports the state.
⚠ Asked **once per browser**, not once per load: Chrome grants on its own heuristics without
prompting, but Firefox *prompts*, and re-asking every load turns a one-time decision into a
recurring interruption. ⚠ The *state* is still read fresh every time — a user who later installs
the app must see the truth, not a cached memory of a refusal. ⚠ `unknown` renders nothing, per the
rule `TryModeBanner` states: where the signal has not arrived, say nothing rather than guess.

**W10b — legibility of a dead identity** — ✅ the marker exists (`RelayRefusal.DEVICE_REMOVED`,
on **both** admit routes as of 2026-09-03; see §1.2). ⏳ Still open: route it to "start fresh"
instead of `Offer-response failed: 403`. ⚠⚠ Ordering
is constrained: see `admission-revocation-names-the-capability.md` §7h.4b — the F2′ refusal reaches the **approver**, so routing the joiner
straight to "start fresh" means the retry arrives under a new id and the approver learns nothing.
Request `navigator.storage.persist()` at realm creation/join and surface `persisted()`
honestly — "this browser may forget your realm" is a true sentence today and the user is
never told it. ⚠ Recovery already exists ("Disconnect this browser"); what is missing is the
*warning before* and the *explanation after*, so a silent forget stops looking like a bug in
sync. ⚠ Also prune or mark stale pending pairing requests, so Approve cannot be pressed on a
request that can never succeed.

**W11 — retire the walker for reads, starting with the listing** *(§1.2e, §1.2f)*
Give `list` / `recentFiles` / `details` native's projection plus an explicit typed mapper, in the
shape `Timeline.moments` already uses. ⚠ A bare `select { relations(…) }` is NOT the fix — §1.2e
records the two failures it produces, and the mapper is what makes the projection usable. Ship a
conformance test with it: assert the JS reader emits every field the native mapper reads.
⭐ This is convergence, not a new style — see §1.2f.

**W12 — a push channel on the bridge, and the API shape above it** ⚠ *the envelope is in and the
first consumer is migrated; the primitives above it and the remaining pollers are not* *(§1.2g)*

The bridge had four kinds — `hello`, `ready`, `req`, `res` — all request/response, so **nothing
could be subscribed to across the origin split** and every Drive read was "snapshot + refetch
over TanStack Query". Three kinds now close that: `sub` (subdomain → apex), `evt` (apex →
subdomain, `error` set meaning terminal) and `unsub`.

  - `ProxyClient.subscribe(method, params, onValue, onError?) => unsubscribe` — the conventional
    contract, and deliberately the same shape as the in-apex `observe(cb): () => void` that
    `Devices` and `Notifications` already return, so wiring one through is a pass-through rather
    than an adapter. ⚠ Observables are a **TC39 proposal**, not a standard, and RxJS is a
    library; neither belongs in the transport contract.
  - `ProxySubscriptionHandler` is a *separate* map from `handlers`. The two have different
    lifetimes, and subscribing to a one-shot method should be a clear error, not a silently
    wrong shape.

⚠⚠ **Two correctness edges, both guarded by tests that were confirmed to fail without them:**

  - **Cancel-during-start.** A subscription handler may be async, so `unsub` can arrive while it
    is still starting — the unsubscribe finds nothing to cancel, the handler then registers, and
    the stream leaks for the life of the page. The gateway keeps a `cancelled` set and tears
    down on arrival.
  - **Envelope ids are now globally unique**, not per-client. A colliding `c0` was harmless for
    `req`/`res` because responses go back to the requesting window, but a subscription is state
    the *gateway* holds, so two frames sharing an id would let one frame's unsubscribe cancel
    the other's.

⚠ Client `dispose()` posts `unsub` for everything still live. The iframe dying would collect
them anyway, but a client disposed while its page lives on would otherwise leave the apex
pushing into nothing.

⭐ **First consumer migrated: `SyncBanner`** (2026-08-31). It polled `sync.health` every 15 s;
it now takes one snapshot to paint and receives every later change by push, writing them into the
query cache so the freshness branch keeps reading one source of truth. The latency mattered —
`device_unrecognized` is the state a browser sat in for hours while rendering a normal file list.

⭐⭐ **And the reactive source was never missing.** `SyncService.syncHealth()` has always returned
a `Flow<SyncHealthStatus?>`; `SyncHealth.status()` simply took its `first()`. The web snapshotted
because the *bridge* could not carry a stream, not because the runtime could not produce one — the
fourth signal on this track that existed with nothing able to read it (cf. W3, W6, W8). The new
`SyncHealth.observe` is four lines over a Flow that was already there.

⚠ The guard asserts the snapshot is fetched **once and never again**, not merely that the banner
updated — the latter passes just as well against the old refetch, since both end with the right
text on screen. ⚠ The schema-fence query still polls: it has no reactive read wired yet.

**Not done, and each is its own slice:** the primitives layered above `subscribe` —
`AsyncIterable` for ordered/paged sequences and `ReadableStream` for byte streams with
backpressure (W7's natural home) — the React hooks wrapper package, and migrating the existing
pollers (`SyncBanner`'s 15 s `refetchInterval` is the obvious first consumer). The transport was
the blocker; it no longer is.

**W13 — one reset, one name, reachable from the failure** ⚠ *the reset is unified; the naming
and reachability are not* *(§1.2h)*

`Session.clear({ purgeRealm: true })` now dispatches the shared `ForgetThisDevice` op before
purging the browser-only stores, so the custody list has one definition and three callers.

⚠⚠ **What web was actually missing was the step order, not the key list.** At rest its
`clearAll()` was a *superset* of `CUSTODY_SLOTS`, which is why it looked fine. The op is four
ordered steps, each pinned by an incident: retire the device row while the key that signs its
tombstone still exists (otherwise the wipe manufactures the orphaned `Device` row it exists to
prevent); purge the outbound queues *before* custody goes (afterwards, minting an
`ExecutionContext` silently yields nothing — iOS, 2026-08-19); drop the local store *before* the
identity, because custody-gone-store-present is the half-state D3 forbids; then null the
in-memory keys. A `clearAll()` reproduces none of that.

⚠ `purgeRealmStorage()` still runs, and still *after*: the op wipes the object store and the
custody bucket but knows nothing about the browser's other IndexedDB stores — file bytes above
all. Web's at-rest coverage stays a superset.

⭐ `IndexedDbObjectStore` already implements `WipeableObjectStore`, so the op's store-drop step
is functional on web rather than reporting "⚠ local store NOT dropped".

**Outstanding, and deliberately not guessed at:** the naming is tangled across three surfaces —
native "Forget this device", web "Disconnect this browser", and apex's separate "Disconnect from
the Tenshu Relay?" under Services, which is a different action. `locked.disconnect.*` still
renders only in `LockedFlow`, so an unlocked-but-dead browser reaches the reset only through
`DeviceMenu`'s differently-named control. Picking the one name is a product-voice decision, and
reachability follows from it.

**W7 — large files** ✅ *the download half is done; ⚠ upload is still memory-bound*

**Download now streams to disk.** Saving used to be `getData(path)` — the whole file into the
tab's heap — and then a *second* full copy into a `Blob` before the object URL, so peak cost was
~2N for an N-byte file on top of whatever the runtime already held. `saveToDisk.ts` points the
already-bridged read ops (`openContentStream` / `readContentRange` / `closeContentStream` — the
same ones the Service Worker drives for `<video>` Range requests) at a
`FileSystemWritableFileStream`, one `STREAM_RESPONSE_WINDOW` (2 MiB, the ADR 0017 chunk size) at
a time. The heap never holds more than one window.

  - ⚠⚠ **The picker is called first, before any other `await`.** `showSaveFilePicker()` needs
    transient activation, which expires seconds after the click — opening the content stream
    first would usually work and would intermittently throw `NotAllowedError` under a slow read.
  - ⚠ **One dialog on every path.** When a file has no seekable session (`openContentStream`
    answers null) the fallback writes `getData` bytes into the handle the user *already* chose,
    rather than dropping to an anchor download that would ask them a second time.
  - ⚠⚠ **Abort, never close, on a mid-write failure.** Closing publishes a truncated file that
    looks complete — the user keeps it, deletes the original, and finds out later.
  - Firefox and Safari have no File System Access API; they keep the in-memory path, which is
    the same bytes bounded by what the tab can hold.

**Folder upload** reconstructs the tree. A directory pick arrives as a *flat* `FileList` whose
structure exists only inside each file's `webkitRelativePath`, so without rebuilding it every
file lands in the root and the user's folders are silently flattened. Each ancestor is created
once, parents first. ⚠ `createFolder`'s return is deliberately ignored — it answers `false` both
for a real failure and for "already exists" (documented idempotent), so it cannot discriminate;
a directory that genuinely could not be created surfaces as the upload into it failing.

⚠⚠ **Upload is still whole-file in memory, and that is not a web problem to fix.** `uploadFile`
takes a complete byte array and there is no chunked upload op to bridge — the streaming ops W7
uses are read-side only. So "large files" is half done: a 2 GB file can now be *downloaded* on
web and still cannot be *uploaded*. The missing piece is a content-plane write op with the shape
the read ops already have, which is a core/sync change, not a Drive-on-web one.

---

## 5. Verification

### ✅ W1 + W2a + W2b were verified cross-device on 2026-08-31

Browser (localhost apex, W2b build, production relay via the local CORS proxy) → **desktop**.
A generated 1200×900 PNG was uploaded through the Drive web UI and the desktop Control store was
inspected directly. What it proved, in order:

  - the metadata synced (`w2b-roundtrip` present in the desktop store's WAL — with a control
    check that other filenames *are* greppable, so the hit is real and an absence would have
    meant something);
  - the bytes and the ladder reached the relay (`PUT /sync/content/blob-…`, several distinct
    blobs, all `204`, `[jwt+proof]`);
  - desktop **fetched the classes on display** — opening Drive took its content cache from 24 to
    46 files;
  - the LIST class is a **PNG 100×75** and LARGE a **JPEG 256×192** — exactly 4:3, matching the
    1200×900 source, and exactly what W2b mints;
  - ⭐ and the PNG was **decoded pixel-by-pixel** and contains the source image (the generator's
    exact RGB values in the right places). Geometry and format alone would only have been
    *consistent with* success; the decode is what makes it proof.

⚠ Desktop, not a phone, and deliberately: desktop is the JVM, the one platform whose
`GetPreview` decodes through `ImageIO`.

⚠⚠ **The CDP harness cannot keep a paired browser.** Its passkey is a *virtual authenticator*,
which lives in the Chrome **process**, not the profile — kill Chrome and the realm is
unrecoverable except by recovery phrase. That is how the previous debug browser was lost. Pair,
verify and finish inside one session, or skip passkey setup and treat the browser as disposable.

⚠ Two other things that cost time here: the drive dev server needs
`VITE_APEX_ORIGIN=http://localhost:4173` or its bridge iframe silently points at an unserved
`:5173` and the listing hangs on "Loading…" forever; and a **hidden tab does not drain** (§1.2b) —
`Page.bringToFront` before expecting sync.

### Device pass, 2026-08-31 — what a browser found that the suites could not

⭐ **W8's freshness banner verified live.** Wiping the origin and reloading rendered
"Not synced yet" exactly as designed, and the earlier foreground/background run logged
`backgrounded …` → `foreground after 6034ms hidden — waking the sync loop` against an injected
6-second gap.

⚠ **And it found a copy defect no test would have.** A browser that had *never* synced was told
its quiet was "normal after it has been in the background" — a confident explanation of
something that had not happened. Every unit test passed, because they asserted the title, not
whether the *reason* was true. Fixed; the never-synced branch now says so plainly.

⚠⚠ **W7's streaming download is NOT device-verified.** The unit tests cover the loop and were
confirmed to fail without it, but no browser has run `streamToDisk` end to end. Two things got
in the way, both worth knowing before the next attempt:

  - A previewable file's visible **Download is `FilePreview`'s `<a download>` anchor**, not
    `FileDetails`' button — clicking an image row opens the lightbox, not the details drawer.
    That is defensible (the lightbox already holds the whole file in memory to *display* it, so
    streaming its download would save nothing, and it hides Download entirely for video) but it
    means **W7's sink is reached only via the row menu → Details, and on files with no preview**.
    Undocumented until now, and easy to mistake for "the streaming path did not run".
  - Driving `Details` out of the row menu defeated the CDP harness; the menu's items are not
    plain `button`/`[role=menuitem]` nodes.

⚠ Also: a revoked device still renders its **cached listing**, so the app looks healthy while
every content read fails silently. `device_unrecognized` is shown by the banner, but the file
rows beneath it are indistinguishable from a working realm.

### Browser pass, 2026-09-01 — W12 confirmed, W7 only half confirmed

⭐ **W12 + the SyncBanner migration are verified across a real origin boundary.** Recording
bridge envelopes in the Drive page from before its own scripts run (`addScriptToEvaluateOnNewDocument`):
**17 `tenshu/bridge/evt`** envelopes arrived from `http://localhost:4173`, the first ~5 s in,
alongside 1 `ready` and 3 `res`. The banner rendered a *pushed* state. That is the transport, the
gateway's subscription handler, the client's `subscribe`, and the React consumer — all exercised
together for the first time, none of it faked.

⚠⚠ **W7's streaming loop does not run at all — and the realm was not the reason.** Driving the
real UI (row menu → Details → Download) with only `showSaveFilePicker` stubbed produced **one
write of the whole file**, never a window: 7,293,633 B in a local-only realm, then — after
pairing into a real relay-backed realm — 12,402,267 B on a cold 12.4 MB photo, and again on a
second run with the bytes already local. Bytes correct every time (decoded 1800×1350 / 2304×4096);
windowing never happened.

`ContentStream.openStream` was answering `null`, which W7 reads as "this file has no stream".
Instrumented, the truth was a refusal: *"Content stream unavailable — file not synced yet or realm
locked"* — for a synced file in an unlocked realm.

⭐ **Root-caused and fixed the same day.** `JsRuntime`'s `OnDemandResolution` wrapper overrode
**only `resolve`**; the interface's three streaming methods have permissive defaults for test
fakes, so the production wrapper became one while `OnDemandResolutionImpl` sat behind it
implementing all three. With them forwarded, the same 12.4 MB photo now downloads as
`[2097152 ×5, 1916507]` — five full windows and a remainder, size-exact, decoding correctly.
**W7 is verified.** ⭐ And so is **S6b**: the same three methods back the Service Worker's
`<video>` Range path, and an 11.2 MB `.mp4` now plays from the virtual stream URL
(`readyState: 4`, `duration 6.016s`, 1080×1920, SW controlling). It could not have worked before —
`openVideoStream` returned before minting a URL, and every Range request would have 500'd.

⭐ What the pass *did* establish is that the fallback is correct end to end — which is what
Firefox/Safari and every file currently get.

⚠ Harness notes, both of which cost a run: the row menu opens only on a **real** mouse event
(`Input.dispatchMouseEvent`), not a synthetic `.click()`, and its items are plain **`DIV`s** — no
`button`, no `[role=menuitem]`. It also toggles, so a script that assumes it is closed will close
one a previous run left open.

### ⚠⚠ Android mints WebP where every other platform mints PNG

Found while verifying the above, and **pre-existing — not something W2b introduced**.
`GetPreview.Params` documents *absent format ⇒ PNG*, and iOS (`UIImagePNGRepresentation`), the
JVM (`… else "png"`) and now JS all honour that. `AndroidGetPreviewImplementation` does not: its
non-JPEG branch is `Bitmap.CompressFormat.WEBP_LOSSY`, with a comment calling WebP "the existing
default". So the `LIST` rung is **WebP on Android and PNG everywhere else** — in the realm
inspected here, 17 of 20 stored `list` classes are WebP.

⚠ This also **corrects the rationale given for W2b's format change**: that change was justified
partly on "a browser minting WebP would produce thumbnails the desktop app cannot display", and
that overstates it — Android has been shipping WebP `LIST` classes all along and desktop displays
them fine (Compose renders through Skia, which decodes WebP; only the `ImageIO`-based *decode*
path inside `JvmGetPreviewImplementation` would refuse one). The change is still right — honour
the documented contract, match the other platforms, keep the bytes maximally portable — but the
danger was smaller than stated. Which platform is wrong is a real open question and its own
follow-up: either Android should honour PNG, or the contract should say WebP.

⚠⚠ Every defect in §1 is a **cross-device** one, and the web gates cannot see any of it:
`pnpm -F drive test` and `pnpm -F apex test` run in jsdom against a single runtime. W1 and W2
are only provable with a browser and a second real device against a live relay. Budget for
that pass; do not accept a green suite as evidence.

⚠ The `:tenshu-sdk-js` jsTest suite shares **one** singleton `JsRuntime` across test classes,
and sibling suites' `Session.clear` wipes rows written only at runtime-boot — so a W1/W2 test
must drive its state in-body (`StorageStatusTest` is the worked example).
