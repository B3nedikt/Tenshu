# Version compatibility — who has to update what, and how the platform says so

- **Status:** Spec'd / draft v0.2 (2026-09-15) — **no code**. v0.2 adds O6 (use-checked vocabulary,
  §4.5): *an app that does not use what changed must keep working* (§0). Opened by a device pass the same day
  (§1) on which all three skew cases fired at once on one phone. ⚠⚠ **§1.4 is code-verified but not
  measured** — an additive `:core` bump fences older *peer devices* as well as older apps; measure
  it (§10) before building on it.
- **Date:** 2026-09-15
- **Owner:** Benedikt
- **Extends:** [`core-schema-evolution.md`](core-schema-evolution.md) (the mechanisms — announce §6,
  realm epoch fence §7, migration chains §8, op cut-off §9) ·
  [`core-schema-skew-surface.md`](core-schema-skew-surface.md) (SK-1…SK-4, the surface) ·
  [ADR 0025](../adr/0025-forward-to-control-surface.md) (the route back to Control, tri-state
  presence and store route).
- **Supersedes, if accepted:** the deliberate strict core announce floor recorded at
  `CoreCommonModule.kt:1270-1287` (§1.1, §4.3); ADR 0025 §Decision 1's *"`Skew` → SK-1's wording,
  unchanged"* row for Android/desktop (§6.2, D7); `core-schema-skew-surface.md` §8.5's
  *"no button ships"* (§6.3); and it answers `tech-debt.md` *"Removing an operation has no
  compatibility window"* (§4.6).
- **Related:** House Rule 9 · [`core-client-resubscription.md`](core-client-resubscription.md)
  (reconnect semantics) · the sorted-folder-paging design, [`drive-sorted-folder-paging.md`](drive-sorted-folder-paging.md) (the
  `isA` bump that exposed §1.1) · [`docs/testing.md`](../testing.md) §6 (lockstep canaries).

---

## 0. The question

> *There is no coherent concept for "who has to update what", and no user-facing way to say so
> with a route to fix it.*

Three skews, each already detected somewhere, none of them governed by a stated rule:

1. **An app is newer than Control.** The user updated Drive; Control is old. The message must
   appear **in Drive** — Control may be too old to say anything, and Drive is what the user is
   looking at.
2. **An app is below Control's floor.** Control updated; Photos did not. Photos can no longer talk
   to it and must be updated.
3. **Another device in the realm is newer than this one.** Its data cannot be read here, so this
   device stops pulling until it is updated.

What is missing is not detection (`core-schema-skew-surface.md` §1 already found it done twice)
but the **model**: which changes owe which of these fences, when apps must move together and when
an older one may keep working, what a refused client does while it waits, and what the user is
told on each platform, with what button.

⭐ **The governing principle** (maintainer, 2026-09-15): *an app that does not use what changed must
keep working.* Per-service version slots were meant to buy exactly that — a bump to the file
service should not touch an app that never talks to it — and for feature services the announce
already scopes by what a client announces (*"proxy services the client did not announce are NOT
mismatches"*, `AnnounceSessionImplementation.kt:31-33`). Everything that violates the principle
today does so through a number that is **wider than what it protects**: the single `:core` slot
every app carries (§1.1), a whole-realm pull fence (§1.3), a session-wide refusal for one call's
vocabulary (§1, the `isA` fence). §4 is organised around narrowing each of them to *use*.

## 1. Evidence — the 2026-09-15 and 09-16 device passes

Legend: **M** measured on the phone · **C** verified in code · **I** inferred, not measured.

Setup: sorted folder paging (SP-1…SP-6) adds a `:core` operation `isA` and bump
`TENSHU_STORAGE_FORMAT_VERSION` 21 → 22 with an **empty** `schemaMigration(CORE_SERVICE_ID,
fromVersion = 21) {}`. The bump is a deliberate fence, not a reshape: `isA` rides *nested* inside
`sortBy`'s selector, so an older proxy fails at **decode**, and only a top-level unknown tag becomes
the typed `UnsupportedOperationError` (`BinaryParser.kt:550`; `core-schema-evolution.md` §9.3.1;
pinned by `UnsupportedOperationGateTest.anUnknownTypeInsideAKnownOperationIsNotReportedAsAnUnsupportedOperation`).
The intent — written into the v22 KDoc on the SP-3 branch — was *"refuse the session as
`announced > current`"* for a newer Drive against an older Control, while older apps at 21 stay
admitted, which the empty step was meant to buy.

### 1.1 The core floor is not derived from the chain, so an additive bump refuses every older app (M + C)

Installing a v22 Control:

```
TenshuSchemaAnnounce: app.tenshu.photos admitted=false [tenshu/core announced=21 floor=22 current=22]
TenshuSchemaAnnounce: app.tenshu.timeline admitted=false [tenshu/core announced=21 floor=22 current=22]
```

`SchemaProvider.currentVersionFloors` (`SchemaVersioning.kt:289-303`) derives each **service**
floor from the chain (`chains.floorFor(id, version)`, :300) but hard-codes
`coreVersion = TENSHU_STORAGE_FORMAT_VERSION` (:295). The **store** gate uses the chain for core
(`StoreSchemaMigrator.coreReadableFloor()`, `StoreSchemaMigrator.kt:59`), so the same empty step
that lets a v21 *store* migrate does nothing for a v21 *app*.

⚠⚠ **This is not an oversight, and the proposal must not call it one.** `CoreCommonModule.kt:1270-1287`
records the asymmetry as deliberate when the first core step (v19) landed: *"'can I read rows
written by the old build' and 'may an app compiled against the old core primitives talk to this
proxy' are different questions, and the strict answer is the safe one for IPC"*. The reasoning is
right about the questions being different and wrong about the remedy: a strict floor answers the
IPC question with *never*, so every core bump since that note — v20 (a reserved cursor row), v21
(EI-1) and v22 (`isA`) — is a lockstep event for every app on the device, including the two that
reshaped nothing a client ever parses. §2 is the general form of this.

### 1.2 A refused client storms Control (M + C)

`wireSchemaAnnounce` installs `RemoteBinaryTaskExecutor.sessionPreflight = { announcer.announce() }`
(`SchemaAnnouncer.kt:162`). A refused announce throws, the latch stays un-set
(`RemoteBinaryTaskExecutor.kt:81-92`), and the **next call of any kind re-announces** — no cached
refusal, no backoff, no trigger discipline. ⚠⚠ **And the refusal schedules its own next call**: the
announce's inner round trip parses a *successful* typed reply, so it reports `CallOutcome.Answered`,
which wakes the reachability probe at once — a self-sustaining loop bounded by one round trip, not by
the probe's 5 s interval. §5.1 has the chain with citations.

| Measured (M), phone, 2026-09-15 | Value |
|---|---|
| Photos, refused announces | **18.6 / s** |
| Timeline, refused announces | **19.9 / s** |
| Control CPU | **92–141 %** — ~¼ of samples in `AnnounceSessionImplementation.execute` |
| Photos / Timeline CPU | ~60 % each |
| Drive folder open (a *healthy*, admitted app) | **1.1 s → 2.6 s** |
| Force-stopping Photos/Timeline | no effect — both restart themselves |
| Installing Photos/Timeline at v22 | storm stops |
| Fit, Maps, Home, Calendar, Mia, Contacts — installed at v21, not running | admitted by nothing; **each would join the storm on launch**, although none of them sends `isA` (the principle in §0, violated by the core slot) |

The proxy side recomputes the whole answer per call: `currentVersionVector` and
`currentVersionFloors` each rebuild the list of every registered schema (`serviceSchemas()` =
`allSchemas().filterIsInstance`, `SchemaRegistry.kt:176-177, 203-204`), and the floors path builds a
fresh `SchemaMigrationRegistry` from every registered step (`SchemaVersioning.kt:290-292`) — per
announce, although the registered schema set is fixed for the process (`SchemaPullFence` already
caches the same vector `by lazy` for that reason, `SchemaPullFence.kt:110`). The md5 is the id
hash: `idToUuid` re-hashes the constant `"tenshu"` namespace on every call (`IdUtil.kt:11-15`), and
each announce builds a vector, floors and mismatches and codecs one vector in and two out — whether
the sampled md5 sits in composite creation inside `execute` or in the codec around it, the code
alone does not settle (**I**). ⚠ **The cost that hurt was not the refused
apps — it was Drive**, an admitted app sharing Control's CPU. A skewed app degrades every healthy
one beside it.

⚠ *Why force-stop did not help* is **inferred, not measured**: Timeline restarts itself by design
(a `START_STICKY` capture service, a 15-minute WorkManager watchdog, a `MY_PACKAGE_REPLACED` +
`BOOT_COMPLETED` receiver — `timeline-android/…/AndroidManifest.xml:93-98`), and Control rebinds
each client's `ClientCallbackService` with `BIND_AUTO_CREATE` on binder death
(`AndroidClientConnection.kt:56-65, 89-105`). But the storm's probe loop exists only once the UI
graph resolves (`PhotosModules.kt:57-65`), so a pure background restart should not reach 19/s —
§10 M2 reads the process-start reason.

### 1.3 The realm fence fired over a composite nobody syncs (M + C)

Simultaneously, Drive rendered `sync_banner_needs_update_text` — *"Update needed — your other
devices are using a newer version of Tenshu…"* (`drive-shared-ui/…/strings.xml:262`) — and
`tenshu/sync/schema/fenceStatus` named **`tenshu/sync/service`**. Cause: the recovery-phrase fix bumped `SyncService`
6 → 7 (`SyncService.kt`, `SyncServiceCommonModule.kt`) to add **one error composite**,
`RecoveryPhraseUnreadableError`, with an empty step — *"nothing reshaped, nothing persisted"*. A
realm peer on master wrote a sync-service row, `RealmSchemaEpochWriter` raised the realm's
`tenshu/sync/service` entry to the writer's **declared** version
(`RealmSchemaEpochWriter.kt:164`), and `SchemaPullFence` fenced this device because `7 > 6`
(`SchemaPullFence.kt:212`).

⚠⚠ **And the fence is whole-realm, not per-service**: a fenced realm skips its entire pull
(`SyncScheduler.drainAndPull`, `core-schema-evolution.md` §7.7). An error type that can only ever
travel from Control to a client over IPC stopped *every* app's data arriving on this phone.

### 1.4 The fence-only core bump also fences every older peer device (C + I, not measured)

`RealmSchemaEpochWriter` updates an existing epoch row's `coreVersion` whenever the writer's
`TENSHU_STORAGE_FORMAT_VERSION` is higher (`RealmSchemaEpochWriter.kt:176-182`, E2i §7.11), and
`SchemaPullFence` fences on `recorded.coreVersion > TENSHU_STORAGE_FORMAT_VERSION`
(`SchemaPullFence.kt:219`). So the first write from any v22 Control — or iOS route-1 app — into a
realm that already has an epoch row raises its core entry to 22, and **every v21 device in that
realm stops pulling**, over an operation that never touches a synced byte. The v22 KDoc discusses
the app fence and the store floor and does not mention this. ⚠ Not observed on 2026-09-15 (the
visible fence named `tenshu/sync/service`, §1.3); §10 M3 measures it.

### 1.5 The prior incidents are the same finding

| Date | Symptom | Mechanism | Guard that missed it |
|---|---|---|---|
| 2026-07-27 | Android silently dropped every desktop block | core skew fenced by nothing (E2i, §7.11) | all — now fenced |
| 2026-07-30 | Timeline *Activities* tab blank, no banner | client used a relation an older Control lacked; no version moved | announce admitted (no bump owed) |
| 2026-08-02 | "update needed" that rebuilding from master could not clear | epoch raised by a branch-only build; MAX-merge never decreases | — (working as designed) |
| 2026-08-24 | "pairing is broken" — one-way device list | peer devices on different commits, pull-fenced silently (`tech-debt.md` *"Pairing is broken" was schema skew*) | no fence surface then |
| 2026-09-01→05 | every backup upload failed for 3 days | op **renamed** under an empty-step file-service bump; older Drive stayed admitted (`tech-debt.md` *"Removing an operation has no compatibility window"*) | announce admitted — floor stayed low |
| 2026-09-15 | §1.1–§1.4 | this doc | — |
| 2026-09-16 | an iPhone's Drive store wiped, then unable to refill it | §1.6: a build one **service** slot behind wiped the whole store; the realm fence then blocked the backfill | `ios-smoke.sh` (asserts only that the pid lives) |

Two of these are *too strict* (a fence where nothing could be misread: §1.1, §1.3, §1.4) and two are
*too lax* (an admitted pair that could not work: the relation, the rename). ⚠ Both directions come
from the same place, which is why tightening or loosening one number cannot fix both.

### 1.6 A build one service slot behind wiped the whole store — and the fence closed the way back (M + C)

Setup: the maintainer's iPhone, Drive on route 1. ⚠⚠ **No App Group is granted on the free personal
team** (`drive-ios/Drive.device.entitlements` omits it deliberately), so `sharedReplicaDirectory`
falls back to the app's own sandbox and **each iOS app holds its own replica** — none of case F's
preconditions were present. The store had been written by a master build (`8ab9d118`) carrying
that bump, i.e. `tenshu/sync/service = 7`. Installed over it: a sorted-folder-paging branch build, which carries core
**22** but predates the sync bump and so declares `tenshu/sync/service = 6`.

What happened, in order:

1. **The core gate passed.** `StoreVersionGate` judges **core only** — *"per-service drift moved to
   the real migrator"* (`StoreVersionGateTest` KDoc) — and recorded 21 → current 22 is inside
   `[floor 21, current 22]`, so core migrated forward as intended.
2. **The per-service migrator wiped everything.** `StoreSchemaMigrator.kt:218-227`: `from (7) >
   current (6)` ⇒ *"store written by newer code; wiping"* ⇒ `wipeAndStampCurrent()` →
   `port.wipeAllRows()`. ⚠⚠ **`wipeAllRows` is not scoped to the drifting service** — one service
   slot being behind discards every row in the store, then re-stamps *every* service down to this
   build's declared numbers.
3. **Measured, not inferred** (M): all 3,929 files under `Documents/objects` carried the install
   day's date and **not one predated it**; `.serviceversions` read `tenshu/sync/service=6`; the log
   showed `SyncScheduler: backfill handover — live tail jumps 500 → 22233; history below fills in
   backwards` — a backfill onto an empty store.
4. **And the refill could not run.** The realm's epoch is at `tenshu/sync/service = 7`, so the same
   number that caused the wipe fenced the device against repairing it:
   `schema pull fence ENGAGED … behind a realm epoch for tenshu/sync/service`. Net: an empty Drive
   with no local recovery path. The data survived only because the relay and an Android peer still
   held it; re-installing a build at 7 cleared the fence and let the backfill restore it.

⚠⚠ **Three things this changes for §3.1 F and §8 VC-7.**

- **The data-loss case is not F's shape, and F is the rarer one.** F needs two route-1 *siblings*
  sharing an *App Group* replica. This needed none of that: one app, its own store, no core drift,
  one service slot behind. Call it **F′**. It is also not exotic — it is what *every* device test of
  a branch that predates a merged service bump does, which is exactly how it happened here.
- **The wipe and the fence key on the same number in opposite directions.** Recorded *above* this
  build ⇒ discard the store; declared *below* the realm ⇒ refuse the pull. So the mechanism that
  destroys the data is the one that then blocks its restoration. VC-7's *refuse, don't wipe* is
  therefore not only a data rule: refusing is what keeps recovery reachable at all.
- **A downgrade is a real event, not a theoretical one**, and it has no surface: nothing told the
  user their store had been discarded, and nothing told them why the folder was empty.

⚠ **The standing gate cannot see any of it.** `scripts/ios-smoke.sh` asserts the process is alive
6 s after launch, so a store wipe passes it green. Reading `.formatversion` + `.serviceversions`
before and after an install would have caught it in one line (§10 M7).

## 2. The finding: one version number is gating three different contracts

### 2.1 The three contracts

Every `service(id, version = N)` and `TENSHU_STORAGE_FORMAT_VERSION` is consumed by three gates that
protect three different readers:

| Contract | Reader it protects | Gate today | Floor/threshold derived from |
|---|---|---|---|
| **Store** — local rows | the same runtime, after an upgrade (and ⚠ on iOS, route-1 siblings over one replica — §3 case F) | `StoreVersionGate` + `StoreSchemaMigrator` | the migration chain ✅ |
| **Data** — synced payloads | *other devices* in the realm | `RealmSchemaEpoch` + `SchemaPullFence` (whole-realm pull stop) | the **declared** version of whatever the writer wrote |
| **Wire** — IPC vocabulary | the *other binary on this device* (client ↔ proxy) | `AnnounceSession` (session refusal) | services: the migration chain; core: the current version |

The migration chain is the right instrument for exactly one of these. It answers *"can this
runtime still read rows the old build stored"*. It has been drafted into answering the other two:

- **Data** fences on the declared version, so any bump — including one that adds a type no device
  will ever sync (sync 6 → 7) — stops peers pulling (§1.3, §1.4).
- **Wire** uses the chain for services, so a bump whose step is empty keeps the floor low **even
  when the bump removed or renamed an op** (the upload-op rename, file 8 → 9), and would equally admit an older
  client across a genuine reshape of a *returned* composite that has a registered step — there is
  no downcast: `SessionAnnouncement.announcedVector` is written (`AnnounceSessionImplementation.kt:88`)
  and read by nothing but the admitted check (§9.2's per-session routing was never built). For
  core it ignores the chain entirely, so the floor is as strict as it can be (§1.1).

### 2.2 What makes a change safe to skip past — unknown tags vs misread tags

The one ground fact everything below rests on (`core-schema-evolution.md` §2 facts 1 and 4): the
wire is positional, and type ids cross as 16-byte tags. A reader meeting a change fails in one of
**two** ways, and they differ in kind:

- **Unknown tag** — a type, op, constant or relation the reader has never registered. The parse
  **fails loudly** at that element: `UnknownTypeTagException` → `UnsupportedOperationError` at the
  top level of a request (`BinaryParser.kt:550`), an untyped internal error when nested (§1), and
  `CallOutcome.Unintelligible` on a response the client cannot read
  (`RemoteBinaryTaskExecutor.kt:274-287`), which the reachability engine already renders as
  `Skew(UpdateTarget.BOTH)` (`ControlReachability.kt:216`). Nothing is misread; one exchange fails.
- **Misread tag** — a **known** tag whose field list differs. The reader decodes at the wrong
  offsets and gets garbage, not an error. **This, and only this, is what a session refusal or a
  pull fence must prevent.**

So a change is **additive** for a reader iff every difference it can meet is an unknown tag, and
**breaking** iff any difference is a misread tag or a removal the reader still depends on. That is
a property of *who produces* the new vocabulary and *which* reader meets it — never of the number
alone:

| Change | Produced by | Store | Data (peers) | Wire: older client ↔ newer proxy | Wire: newer client ↔ older proxy |
|---|---|---|---|---|---|
| New op / new op param type (`isA`) | client | — | — | ✅ old client never sends it | ⚠ unknown tag on the proxy — breaks the calls that use it |
| New return / error composite, new result constant (sync 6 → 7) | proxy | — | — | ⚠⚠ unknown tag on the client, only if emitted to it — and **not** one call: `Unintelligible` sets the client-wide `unreadable` latch (`TenshuClient.kt:139-145`), which `ControlReachability.kt:219` renders app-wide as `Skew(BOTH)` until some later response parses | ✅ old proxy never emits it |
| New relation read by clients (the 2026-07-30 Activities tab) | client | — (a new property-kind entry) | — | ✅ | ⚠ unknown relation — breaks that read |
| New `Syncable` type, or new constant inside synced rows | device | new rows | ⚠⚠ **must fence**: an old peer drops the block and its cursor moves past it | as wire rows above | as wire rows above |
| Reshape of a composite with the same id (field added/removed/reordered) | either | ⚠⚠ real migration steps | ⚠⚠ fence, if synced | ⚠⚠ **misread** if it crosses IPC | ⚠⚠ **misread** if it crosses IPC |
| New local-only stored row (v19, v20) | — | ✅ empty step | — | — | — |
| Op/relation removed or renamed (file 8 → 9) | — | — | — | ⚠⚠ old client calls a tag the proxy dropped | ✅ |

⚠ **"Additive is not the same as backward-compatible"** (the sync 6 → 7 bump's commit message) is true, and the
table says precisely how: additive is compatible for **one direction per change**, and which
direction depends on who emits the new tag. A single number cannot carry a direction.

## 3. The skew cases

Nine, not three. Each names who detects it, where the user must be told, and what fixes it.

| # | Case | Detects | Tell the user in | Remedy | Today |
|---|---|---|---|---|---|
| **A** | App newer than Control (same device) | Control's announce (`announced > current`) | the app | update **Control** | refused session; SK-1 banner in 7 of 9 apps, no button; storms Control (§1.2, §5) |
| **B** | App below Control's floor | Control's announce (`announced < floor`) | the app | update **the app** | same as A; ⚠ fires for every additive core bump (§1.1) |
| **B′** | App admitted, but an op/relation it calls is gone or unknown | nothing at admission; per call | the surface that failed | update whichever side lacks it | file 8 → 9 upload-op rename: silent 3-day backup outage; 2026-07-30: blank tab |
| **C** | This device behind a peer (realm epoch) | this runtime's `SchemaPullFence` | every app on the device | update **the runtime owner** (Android/desktop: Control; iOS route-1: the app) | `Fenced` banner (Fit, Timeline, Maps, Contacts, Photos, Calendar) and Drive's `NeedsUpdate`; ⚠ Drive names no app; fires over non-synced changes (§1.3) |
| **C′** | A peer behind this device | nothing reads it — the peer's `DeviceSchemaVersion` row is synced (`DeviceSchemaVersion.kt`, E4-1) | this device (the one the user is holding) | update **that device** | no consumer outside compaction |
| **D** | Client vs relay protocol (`RELAY_PROTOCOL_VERSION`, 426) | relay, **at realm genesis only** (§7.11) | Control's onboarding / pairing | update the app, or redeploy the relay | generic HTTP status; no UI |
| **E** | Web: apex bundle vs peers | the in-browser runtime's fence | web Drive | **reload** (or wait for a deploy) | the only real update action in the tree — web Drive's *Reload* |
| **F** | iOS route-1 siblings at different versions over **one** replica | the older app's store-open gate | the older app | update **the older app** | ⚠⚠ the older app **wipes the shared replica** (`FileObjectStore.kt:390-404`); no announce, no surface. ⚠ *Since 2026-11-05 the replica is SQLite and the older app refuses a store stamped newer (`SKEW`, `ios-shared-store-sqlite.md` §7 D5, B.22); the file store is deleted. ⭐ *Since B.24 (2026-11-05) the newer sibling **migrates** the replica through the registry's chains, unpushed queues included; it wipes only a store below the chain's floor (§3.1 F)* |
| **G** | Third-party / EPC client, published `@tenshu/sdk` | Control's announce | the third-party app | its developer ships an update | post-release concern (`core-schema-evolution.md` §9.4) |

### 3.1 Per-case notes

- **A / B — the only cases the user framed as "same device", and on route 1 they are inert.** The
  verdict's direction is already carried end to end: `SchemaSkew.Direction`
  (`PROXY_BEHIND`/`CLIENT_BEHIND`/`BOTH`, `SchemaSkew.kt:30-49`) → `UnreachableReason.Skew(update:
  UpdateTarget)` (`UnreachableReason.kt:54`) via the one mapping `toUnreachableReason()`
  (`SchemaSkewSource.kt:21`). Rendered by Fit, Timeline, Maps, Contacts, Photos, Calendar and Drive
  (`NeedsAppUpdate`, `SyncBanner.kt:376`); ⚠ **Home and Mia feed the verdict into the engine and
  render nothing** — their shells wall only `NotInstalled` (`HomeClientModule.kt:166-178`,
  `home…/App.kt:82`; `MiaClientModule.kt:147-162`). No state carries a button
  (`bannerActionKind` returns `null` for `Skew`/`Fenced`, `ControlBannerPosture.kt:95-103`).
- **B′ — admitted and still broken.** Three shapes, each silent at admission: a removed/renamed op
  (file 8 → 9; top-level `UnsupportedOperationError`, named only by the caller, §9.3.1), an op or type the
  proxy lacks **nested** in a request (untyped internal error — the reason core 21 → 22 bumped), and a
  relation the proxy lacks (untyped; 2026-07-30). Only the third already has a smoke-gate net: step
  4.7 FAILs on `tenshu/core/error/(internal|dispatch/)` (`scripts/android-smoke.sh:408-463`).
- **C — the owner is known, the copy does not always use it.** `RuntimeIdentity` + `ProxyHost`
  resolve the runtime owner (`core-schema-skew-surface.md` §8.4) and six apps name it. ⚠ **Drive's
  `NeedsUpdate` names no app** — *"your other devices are using a newer version of Tenshu"* — which
  on Android tells the user a fact about *other* devices when the fix is Control on *this* one. ⚠
  **iOS has no Swift fence or skew surface at all**: the shells gate `ControlNotConnectedBanner` on
  reachability booleans (`FitIosApp.swift:235`, `MapsIosApp.swift:163`, `TimelineIosApp.swift:269`),
  a fenced device is reachable, so an iPhone that is fenced says nothing. Control, on every
  platform, renders neither skew nor fence (nothing under `control*` reads either).
- **C′ — the device the user is holding can name the stale one**, judged against the **recorded
  realm epoch** (§4.4, §6.2) — not the raw vectors, and not this device's own data version.
  `DeviceSchemaVersion`
  (`tenshu/sync/deviceSchemaVersion`, `Syncable(Eager, LWW)`, one row per device,
  `DeviceSchemaVersion.kt:62-92`) is exactly *"what each device runs"*, already synced for E4's
  roster floor. A fenced device cannot learn which peer is ahead (it stopped pulling); a *current*
  device can see which peers are behind. Today only compaction reads it. ⚠⚠ **The predicate is
  `SchemaPullFence`'s own, evaluated for the peer** (`SchemaPullFence.kt:212`, :219): per realm both
  participate in, is the realm's **recorded** `RealmSchemaEpoch` above the peer's declared version
  in some slot? This device's own version is not the comparand — the epoch rises only when an
  emitted value actually contained that service (`RealmSchemaEpochWriter.onLocalWrite`), so a device
  that has updated but written no new-shaped row has raised nothing and its peers are missing
  nothing. ⚠ On iOS route-1 siblings
  share one Keychain `deviceId`, so two apps at different versions write the **same** LWW row — the
  roster would flap between their vectors (**I**; §10 M9).
- **D — genesis-only, and currently exact-match.** The 426 gate runs on `/sync/realm` alone
  (`RealmRouting.kt:297`) and a device that has synced once never presents a version again
  (§7.11). `RELAY_PROTOCOL_VERSION = 3` and `RELAY_PROTOCOL_MIN_SUPPORTED_VERSION = 3`
  (`RelayProtocol.kt:121, 137`), so the range §7.5 bought is `[3, 3]` today. A 426 reaches the
  client as `GenericError("Realm creation failed: …")` (`RestSyncRelay.kt:721-735`) and no UI names
  it. ⚠ `RealmRouting.kt:295-296`'s *"ACCEPTED while the floor is still 1"* is stale.
- **E — web has the only real update action, and apex has none.** The in-browser runtime announces
  in-process as `app.tenshu.web` and is always admitted (`JsRuntime.kt:925-946`). Web Drive's fence
  banner renders *"This browser needs to reload to keep syncing"* with **Reload**
  (`web/drive/src/components/SyncBanner.tsx:112-133`) — the one update button in the tree; apex has
  only the gateway handler (`web/apex/src/lib/proxyGateway.ts:164`). ⚠ **Web Drive and apex are
  separate builds on separate origins joined by an iframe bridge whose handshake carries no
  version** (`web/sdk/src/bridge.ts:18-28`; an unknown method rejects as `unknown method '…'`) — a
  web-local case B′ between two of our own deploys. A long-lived tab keeps its bundle (`index.html`
  uncached, `/assets/*` immutable, `web/apex/Caddyfile:25-29`), and tabs on one origin share an
  IndexedDB whose gate wipes on `recorded > current` exactly like iOS (`IndexedDbObjectStore.kt:156-205`)
  — so a stale tab reopened after a newer one is case F on the web (**I**).
- **F — the only case where skew destroys data rather than pausing it.** ⚠ *History: as read on
  2026-09-15, over the file store. Since 2026-11-05 every iOS build opens the SQLite replica, where an
  older build refuses a store stamped newer at open and at every epoch change
  ([`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §7, D5) instead of wiping it, and
  `FileObjectStore` is deleted (B.22.9). The cited lines no longer exist. ⭐ **And since B.24
  (2026-11-05) the newer app no longer wipes either.** Until then the SQLite binding registered no
  migration chain (`NoMigrationChains`, B.13 (167)) and set every floor to its version, so the first
  sibling updated to a newer service version wiped the shared replica, outbound queues included. The
  binding now hands the open the graph's own chains (`RegistryReplicaMigrationSteps`) and floors them
  where the chains reach, so that sibling **migrates** the store in chunks behind the "updating" fence
  (§5.3 there), every row included; the older siblings are `SKEW` until updated and write nothing. A
  store below the floor (a step deleted, HR #9's compaction) still wipes, as on the JVM. What is left
  of F is the outage, not the loss: an older app is unusable until the App Store updates it.*
  Every route-1 app opens
  the **same** store in the App Group container (`sharedReplicaDirectory("objects")`,
  `PlatformProxyModule.kt` (iOS) :613-625; *"every route-1 app opens the same object store"*, :176-189).
  The store gate wipes iff `recorded < floor || recorded > current`
  (`StoreVersionGate.kt:39-40`), and `FileObjectStore.open` then wipes the blobs and **re-stamps the
  replica down** to the older version (`FileObjectStore.kt:390-404`). So an App Store update of Fit
  followed by a launch of a not-yet-updated Drive wipes the replica every route-1 app shares; the
  newer app then finds an older stamp and migrates or wipes again (**I**: ping-pong). There is no
  announce on route 1 (`FitLocalRuntime.kt:112-115` calls it *"unreachable by construction"*, which
  holds for app↔proxy and not for app↔sibling), and ⚠ `SchemaSessionPolicy.kt:19-21` still claims
  route-1 clients announce. Post-release this is routine, not exotic: the App Store updates apps
  independently.
- **F′ — the same loss with none of F's preconditions, and this one is measured (§1.6).** No App
  Group, no sibling, no shared replica, no core drift: an app opening **its own** store one
  *service* slot behind wipes it whole. `StoreVersionGate` judges core only; the per-service
  migrator takes `from > current` as *"written by newer code"* and calls `wipeAllRows()`
  (`StoreSchemaMigrator.kt:218-227`), which discards every service's rows, not the drifting one's.
  ⚠⚠ The realm fence then keys on that same number, so the device cannot pull back what the wipe
  took. F is the post-release shape; F′ is the one that fires during ordinary branch testing today.
- **G — third party and the hub.** EPC clients announce but discard the announcer
  (`EpcDriveClient.kt:139`); the hub runs its own proxy and SQLite store with migrations
  (`HubRuntime.kt:258-274`) and neither announces nor reads the fence. Out of scope for the UX
  (§6), in scope for the model (§4): the hub is a device in the realm and fences like one.

## 4. The compatibility model

### 4.1 Options

| | Option | What it fixes | What it costs / leaves |
|---|---|---|---|
| **O1** | **Declare lockstep.** Every bump of anything refuses every older app and fences every older peer; say so in HR 9, and make the smoke gate's equality check normative. | §1.1 becomes documented behaviour; zero mechanism | ⚠⚠ Every additive change is an outage for every non-updated app and device. Unshippable post-release: app stores stage rollouts per app, and third-party apps can never be forced (§9.4). And it *still* misses B′ (the rename bumped, and was admitted anyway under an empty step). |
| **O2** | **Fix the core floor only** — `coreVersion = chains.floorFor(CORE_SERVICE_ID, current)` at `SchemaVersioning.kt:295`. | §1.1 | Makes core as lax as services: a future core step with real `CompositeMigration`s would admit an older client across a misread tag. Leaves §1.3/§1.4/B′. |
| **O3** | **Classify each step, derive each gate from the steps that concern it.** Every `schemaMigration` step declares what it changed per contract (§4.2); the shape baseline verifies the declaration is not weaker than the observed diff; store, data and wire each walk the chain over only the steps that concern them. | §1.1, §1.3, §1.4, B′-by-removal; gives lockstep a definition | One declaration per bump (most are `ADDITIVE`), one new envelope for the ceiling (§4.7), a transition for the epoch's meaning (§4.4). |
| **O4** | **Three version numbers per service** (store / data / wire). | same as O3 | ⚠⚠ The frozen `VersionVector` cannot grow fields (§6.2), so all three envelopes — announce, epoch, stamp — need new composite ids and a transition each; three numbers to bump by hand is three to forget. O3 derives the same three from one declared number. |
| **O5** | **Capability negotiation** — the announce exchanges vocabulary (op/type/relation tag sets) and each side degrades per feature, the Google Play services `Feature` / `UnsupportedApiCallException` model. | B′ for additions, at feature granularity; no version reasoning for additive ops at all | Tag sets are ~KB per session; needs a per-feature UX in every app; and it cannot see a **misread** tag (same tag, different layout), so it replaces none of O3's floors. |
| **O6** | **Use-checked vocabulary** — every registered op/type/relation carries the `(slot, version)` it was introduced in (generated, baseline-verified); the client already receives the proxy's vector in the admission reply; before sending, it checks the tags **in this callstack** against that vector and fails *this call* typed (`ProxyLacksVocabulary(slot, needed, proxy)`) without IPC. | The principle, for additions a client *sends*: an app — or a surface — that never uses the new op works against the older proxy; the one that does gets a named, typed, per-call answer | One generated `introducedIn` table, one lookup per tag at serialize time; the app decides per surface whether that call's failure walls or degrades. Like O5 it cannot see a misread tag, so O3's floors stay. Unlike O5 it needs no vocabulary exchange: the version vector the proxy already sends is enough, because *both sides agree what each version contains* — the one thing a single first-party tree guarantees. |

### 4.2 Recommendation — O3 for floors and the data epoch, O6 for additions a client sends, O5's safety net, not O5's negotiation

**Classify the step.** Every registered step gains a compatibility declaration — a **set** of
effects, because one bump routinely carries several (a new request type *and* a new error type; a
removed op *and* a new local row):

```kotlin
schemaMigration(SyncService.ID, fromVersion = 6, compat = setOf(ProxyEmitsNewTypes)) {}   // RecoveryPhraseUnreadableError
schemaMigration(CORE_SERVICE_ID, fromVersion = 21, compat = setOf(ClientSendsNewTypes)) {} // isA
schemaMigration(FileService.ID, fromVersion = 8, compat = setOf(RemovesClientVocabulary)) {}     // upload-op rename
schemaMigration(CORE_SERVICE_ID, fromVersion = 19, compat = setOf(LocalRowsOnly)) {}      // v20
```

A small closed family of effects — the §2.2 table's rows, and nothing clever:

| Effect | Store | Data epoch moves | Wire floor (older clients) | Wire ceiling (older proxies) |
|---|---|---|---|---|
| `LocalRowsOnly` | step (empty or real) | no | walks past | walks past |
| `ClientSendsNewTypes` | — | no | walks past | **stops** (session) → per call under O6 (§4.5) |
| `ProxyEmitsNewTypes` | — | no | **stops** (session) → per response under O6's response half (§4.5) | walks past |
| `NewSyncedTypes` | — | **yes** | walks past | walks past — ⚠ a synced type that is internal to persistence/sync changes no IPC vocabulary; if the type is *also* reachable from something a client sends, the verifier observes `ClientSendsNewTypes` too and the set composes |
| `RemovesClientVocabulary` — an op, request-param subtype, relation or request-side constant a client can send, dropped | — | no | **stops** (or tombstone, §4.6) | walks past |
| `RemovesEmittedTypes` — a response, error or result constant dropped | — | no | walks past | **stops** — a newer client admitted by an older proxy could still be sent the dropped tag and hit `Unintelligible`; ⭐ avoided entirely by keeping the tag **registered as retired for parsing** until the ceiling can advance, which makes the effect disappear |
| `Reshape` | real steps | **yes, if synced** | **stops** | **stops** |

⚠⚠ **Effects compose per contract, strictest wins.** A step's store requirement is the strongest of
its effects', the epoch moves if **any** effect moves it, and a wire walk stops if **any** effect
stops it — so `setOf(ClientSendsNewTypes, ProxyEmitsNewTypes)` stops neither direction's
*session* floor under O6 and both without it, and `setOf(RemovesClientVocabulary, LocalRowsOnly)` raises
the wire floor while the store step stays empty. Nothing is forced up to `Reshape` to express a
mix. The baseline check (below) compares the **observed** effect set against the declared one and
fails if the declaration is missing any member (declared ⊇ observed).

⚠ **`ProxyEmitsNewTypes` stops the wire floor until O6's response half exists.** An earlier draft
had it walk past on the premise that an old client meeting a new error "degrades one call"; it does
not — the client-wide `unreadable` latch makes it an app-wide `Skew(BOTH)` (§2.2). So for now a
proxy-emitted addition keeps older clients behind the floor, and only the per-response check below
lets it walk past.

⚠⚠ **The declaration must be verified, never trusted.** The house pattern is forced accounting
(`SchemaMigrationBuilder`), and the shape baseline already sees each diff that matters:
`Unpinned` (addition — composite or constant), `ReshapedWithoutBump`/`StaleBaseline` (a layout
moved), `Retired` (removal). What it lacks is the **direction** — whether a new type is reachable
from an op's params (client sends), from a return/error/result (proxy emits), or from a
`Syncable`'s field graph (data). That is a walk over registered schemas the baseline test can do,
and it turns the declaration into a checked claim: a step declared `ProxyEmitsNewTypes` whose new
composite is reachable from an op parameter fails the test and names the op.

⚠⚠ **`genericData` defeats that walk, and no author claim can rescue it.** Six slots exist today —
`Setting.value` on `RealmSetting`/`DeviceSetting`, and the `EXTRAS` slots on `Routine`,
`RoutineRun`, `HomeDevice`, `DeviceState` — and **all six sit in `Syncable` rows**. A slot is
declared `genericData(id)` with no type bound (`Dsl.kt:986`), so the schema graph says a synced row
may hold *any* registered `Data`, and an honest walk must therefore conclude `NewSyncedTypes` for
every new composite or constant. ⚠⚠ **That default is not free — it re-breaks §1.3**: the `isA`
predicate is IPC-only, but under an unbounded slot it *is* reachable from a `Syncable`, so core's
data version moves and every v21 peer is fenced again, which is the exact defect O3 exists to fix.
An author opt-out cannot close the gap either: this section's whole premise is that a declaration
is checked against something, and *"my type cannot enter `genericData`"* is checkable against
nothing.

⭐ **So bound the slot, not the type.** `genericData(id, holds = …)` names the admissible type ids
(or one marker supertype); the **emit** path refuses a value outside the set, the way the read path
already refuses an unregistered id (`DbEntryMapper.kt:131`). Parsing stays permissive — a type a
*peer* widened its slot to is a routine thing to receive, and that is what the fence is for. The
walk then reaches exactly the declared set, and *"not reachable from a `Syncable`"* becomes a fact
the baseline test can check rather than a claim it must take. Declaring the six slots is VC-1 work,
not a phase of its own.

⚠ **And widening a slot is an epoch-moving change in the *admitted type's* service, not the
slot's.** The epoch writer attributes by contained type (`containedTypeIds` → `owningServiceOf`,
`RealmSchemaEpochWriter.kt:150-165`), so admitting a core-owned type into a preference row must
fence old readers on **core**'s recorded number — a step on the preference chain would raise the
wrong one. The baseline check states this as an invariant over the declared sets: for every
admitted type *T*, `dataVersion(owner(T)) ≥ introducedIn(T)`, over the table O6 already generates
(§4.5). D2 decides whether to bound the slots or accept the conservative default.

**Why O6 and not O5 as the mechanism.** Capability sets are the right answer for the case where one
side can never dictate the other's release — third-party EPC apps and the npm SDK (§9.4) — and the
wrong first answer for first-party apps that ship from one tree: they exchange a vocabulary per
session to learn what O6 reads off the version vector the admission already carries, because both
sides of a first-party pair agree on what each version contains. Both give use-granularity; O6 gets
it for one table lookup per tag (§4.5), and leaves the copy to the app — a surface that *is* the app
says *"Update Control to use this version of Drive"*, a peripheral one may scope it. ⭐ **But O5's safety net is cheap and orthogonal:** make every unknown tag
in a **request** parse typed, at any depth, as `UnsupportedVocabularyError(typeTag, position)`.
§9.3.1 confined typing to the top level because a nested unknown tag *"means different things in
different positions"* — true in general, but in a request the **client produced the tag**, so an
unknown one on the proxy can only mean the proxy lacks vocabulary the client has, which is exactly
case A for that call. That closes the untyped hole the `isA` bump had to fence around (D5).

### 4.3 Floors, ceilings, lockstep — derived, never declared

For service *S* (core is `CORE_SERVICE_ID`, finally treated identically):

- **Store floor** = walk back from current over every step (today's `floorFor`). Unchanged.
- **Wire floor** (lowest client version this proxy serves) = walk back from current over steps whose
  effects all walk the floor (§4.2: any stopping effect stops the walk). Computed **by the proxy**, which holds those steps.
- **Wire requirement** (lowest proxy version this client needs) = walk back from the client's
  current over steps whose effects all walk the ceiling. Computed **by the client**, which holds
  those steps — ⭐ the older side can never classify steps it does not have, so *each side judges
  the steps it owns*. At session level that is why a ceiling would need §4.7's envelope; under O6
  the ceiling is judged per call instead (§4.5), and §4.7 is left for `Reshape`.
- **Data version** = walk back from current over steps that do not move the epoch (§4.4).

**Lockstep is then a derived fact, not a convention:** a pair must move together exactly when a
`Reshape` crossing IPC sits between them. HR 9's *"clients still move across a bump in lockstep"*
becomes *"clients move in lockstep across a `Reshape` of an IPC type; every other step has a
window"*. Nothing is declared "lockstep" by hand, and the smoke gate's `storageFormatVersion`
equality check stops being the definition of correctness (D10).

Worked, for the four real bumps in §1:

| Bump | Declared effects | Store floor | Wire floor | Wire requirement of the new app | Data version | Outcome |
|---|---|---|---|---|---|---|
| core 21 → 22 (`isA`) | `ClientSendsNewTypes` | 21 | **21** | 21 (O6: none at session level) | 21 | v21 Photos admitted by v22 Control; **a rebuilt v22 Photos, which never sends `isA`, works against v21 Control**; v22 Drive against v21 Control: only the calls carrying `isA` fail typed (O6) — Drive chooses to wall, since its listing is the app; no peer fenced |
| sync 6 → 7 (`RecoveryPhraseUnreadableError`) | `ProxyEmitsNewTypes` | ≤ 6 | **7** until O6's response half, then ≤ 6 | ≤ 6 | **≤ 6** | no peer fenced (the fix that would have unblocked §1.3's phone); v6 clients refused by a v7 Control until the response half lands — after it, admitted, with the new error down-levelled for them |
| file 8 → 9 (upload-op rename) | `RemovesClientVocabulary` | ≤ 8 | **9** | ≤ 8 | ≤ 8 | v8 Drive refused → *"Update Drive"* (B) instead of three silent days |
| core 19 → 20 (cursor row) | `LocalRowsOnly` | 19 | 19 | 19 | 19 | nothing refused, nothing fenced |

(`≤` where earlier steps exist and their classification — `Unclassified` until declared, §8 VC-1 —
decides how much further each walk goes.)

### 4.4 The data epoch records the data version, the fence compares the declared one

Frozen-shape-compatible, with no transition hazard:

- **Writer** records `dataVersion(S)` — the declared version walked back over steps that do not move
  the epoch — instead of the declared version (`RealmSchemaEpochWriter.kt:164`, and the same for
  core at :176).
- **Reader** keeps fencing iff `recorded > declared(S)` (`SchemaPullFence.kt:212`, :219).

Why this is sound: a reader at *D* can read a writer at *E* iff no epoch-moving step lies in
*(D, E]*; the writer's data version *e* is the highest version ≤ *E* whose step moved the epoch, so
*"no such step above D"* is exactly *e ≤ D*. ⭐ **Old writers are conservative, not wrong**: a
pre-change writer records *E ≥ e*, which can only over-fence (today's behaviour), never under-fence.
So a mixed fleet needs no flag day, and the MAX-merge stays a valid semilattice. ⚠ An epoch that
old writers already raised (e.g. the realm that fenced §1.3's phone at `tenshu/sync/service = 7`)
stays raised — the MAX never decreases (`partial-sync` 2026-08-02 in §1.5) — and clears only when
the reader updates. Pre-release that is a one-time cost.

### 4.5 Additive operations — the three answers, and which to use when

1. **Use-checked vocabulary (O6)** — the default for first-party apps. The client checks the tags
   of the callstack it is *about to send* against the proxy vector from its admission, so a
   requirement follows **use**: Photos, which never sends `isA`, is unaffected by the `isA` step in
   either direction; Drive's sorted listing call fails typed and names Control. The app maps that
   typed failure to its posture: a surface that *is* the app walls (*"Update Control to use this
   version of Drive"*); a peripheral one degrades in place (*"Update Control to sort by date"*).
2. **Typed nested unknown tags (§4.2)** — always on. The safety net for anything O6's table does not
   know (an unclassified or unbumped addition — the 2026-07-30 relation): it fails as case B′ with a
   name instead of an `error/internal` swallowed into an empty list.
3. **Capability sets (O5)** — deferred to the first un-forceable client (EPC third party, npm SDK),
   where the two sides cannot be assumed to agree on what a version number contains.
   ⚠ Neither (1) nor (3) can see a misread tag: O3's floors stay for `Reshape`.

⚠⚠ **An earlier draft of this section concluded the opposite — "a requirement is computed from what a
client *registers*, not what it *calls*", so a rebuilt Photos would need Control 22 exactly as Drive
does.** That is true of a requirement computed *at admission*, which is where §4.7's envelope put
it, and it is the reason to not put it there. *Statically* a client cannot know which vocabulary a
code path reaches; *at serialize time* it knows exactly, because it is writing the tags. O6 moves the
check to the one moment the answer is exact, and it needs nothing from the proxy that the admission
does not already carry.

**What O6 needs, concretely:**
- **`introducedIn(tag) → (slot, version)`** for every registered op, composite, constant and
  relation a client can send — a **persisted, append-only** table, verified by the baseline test so
  a new addition cannot ship without an entry. ⚠⚠ It cannot be *generated from* the shape baseline:
  `PinnedShape.serviceVersion` is the owning service's version at the last regeneration, and every
  regeneration rewrites every existing type to the current version (the file-service types all read
  v10 today, most of them far older) — seeding from it would make a v10 client reject ordinary calls
  against a compatible v9 proxy. So the table is **seeded once**, from history rather than from the
  current file: a tag already present at its slot's floor is seeded at that floor; a tag introduced
  by a step *above* the floor is seeded at **that step's version** — ⚠ `isA` at 22, not at the
  walked-back floor of 21, or a v22 Drive would accept a v21 proxy locally and send it the nested
  tag anyway (the typed unknown-tag safety net would still catch it, but only after IPC). From then
  on the baseline test only *adds* entries — at the current version, for vocabulary it observes for
  the first time. A tag absent from the table is treated as present in every version (today's
  behaviour, no requirement).
- **The check** at `RemoteBinaryTaskExecutor`'s serialize step, after admission: for each tag,
  `proxyVector[slot] >= version`, else throw `ProxyLacksVocabulary` **without sending** — one hash
  lookup per tag, against a vector the executor already holds (`SessionAdmission.proxyVector`).
- **The response half, on the proxy.** The proxy already records each admitted session's vector
  (`SessionAnnouncement.announcedVector`, written at `AnnounceSessionImplementation.kt:88` and read
  by nothing but the admitted check today). Before serializing a response, it checks the response's
  tags against that vector the same way; a tag the client's version predates is **down-levelled** —
  a new error subtype replaced by its registered parent (`RecoveryPhraseUnreadableError` →
  `GenericError` carrying the message), a new result constant by a declared fallback — or, where no
  fallback is declared, the call fails with a typed error the old client *can* parse. Either way the
  client never sees an unknown tag, so the `unreadable` latch never fires for an addition, and
  `ProxyEmitsNewTypes` can walk the wire floor (§4.2). ⚠ A down-level fallback is part of the step's
  declaration and is checked like the rest of it: a new proxy-emitted type with neither a parent the
  old client has nor a declared fallback fails the baseline.
- **Route 1 is inert** (the app is its own proxy), and EPC third-party clients keep O5's deferral.
- ⚠ **This also shrinks what the `:core` slot has to mean.** With O6, adding query vocabulary to
  `:core` stops being an all-apps event, so D2's `isA` choice (a generic core op) no longer carries
  the lockstep cost the sorted-folder-paging design recorded for it. The slot's remaining jobs —
  store format and synced core types — are the ones O3 already derives per contract.

⚠ **A new relation needs (1) too.** The 2026-07-30 incident bumped nothing because relations were
not versioned vocabulary; under O3 a relation a client reads is `ClientSendsNewTypes`, and the
property-kind baseline (which already pins relations as `CHILD_ROW` entries) is the diff that
catches it.

### 4.6 Removals and renames

`tech-debt.md` lists three options for the upload-op rename. Under O3:

- **Default: `RemovesClientVocabulary` raises the wire floor** (the third option) — correct, no new
  mechanism, and it routes the failure into case B's surface instead of a swallowed per-call error.
- **Where a surface can degrade instead** — a background op whose loss should not wall the app —
  keep the removed id registered for one release as a **tombstone** answering a typed
  `OperationRetiredError(movedTo)`; the step then declares `ProxyEmitsNewTypes` and the floor stays.
  This is the second option, and it is the retired-id registry §9.3.1 described and deferred.
- ⚠⚠ The first option (the announce carries op ids) is O5 and is deferred with it.
- **The mirror case — a type the proxy *emits* is removed** (a response, error or result constant).
  The removal hurts the other direction: an older proxy still emits it to a newer client that no
  longer registers it. Default: keep the tag **registered for parsing, as retired**, for as long as
  any proxy the client may meet could still emit it — a registration costs nothing and the effect
  then never arises. Only if that is not possible does the step declare `RemovesEmittedTypes`, which
  stops the wire ceiling (§4.2).

### 4.7 The envelope: `AnnounceSessionV2`

⭐ **Superseded by an in-place reshape (2026-10-28).** The pre-Play production reset made changing V1
itself free, so there is no V2 and no V1 fallback: `AnnounceSession(appId, current, requires)` replies
with `SessionAdmission(admitted, updateProxy, updateClient, proxyVector, wireFloors, mismatches,
proxyAppId, proxyAppVersion)`. ⚠ The verdict is **two booleans**, not the `verdict` constant below —
an added constant member would be unparseable to an un-rebuilt client on the negotiation channel
itself; the booleans span the same four verdicts. The proxy's operation ids are deliberately not
carried (§4.6's first option stays deferred as O5). `core-schema-evolution.md` §6.2 and Appendix A.4
record the exception. The design reasoning below stands; read "V2" as "the shape V1 now has", and
skip the fallback paragraph.

⚠ **With O6 (§4.5) the request half needs no new envelope**: the requirement is checked per call
against the `proxyVector` V1 already returns, provided a newer client announces its *session
requirement* to a V1 proxy (the fallback below). V2 is needed for what V1 cannot say — the client's
**current** version beside its requirement, which O6's response half needs to down-level correctly;
the verdict direction for a genuine `Reshape`; and the proxy's build identity for the copy (D4):

The session ceiling for a `Reshape` needs one number the frozen envelope cannot carry: the client's
**requirement** alongside its **current** version (§4.3; a single field cannot hold both — a client
whose requirement is below the proxy's floor while its current version is above it would be wrongly
refused). Per `core-schema-evolution.md` §6.2/§9.1, a changed contract is a **new op id** with new
composites, frozen from birth; ~~V1 stays registered forever for old clients.~~ *(Superseded: V1 was reshaped in place, see the banner above.)*

- **Request** `AnnounceSessionV2(appId, current: VersionVector, requires: VersionVector)`.
- **Reply** `SessionAdmissionV2(verdict, proxyVector, wireFloors, mismatches, proxyAppId,
  proxyAppVersion)` — `verdict ∈ {ADMITTED, UPDATE_PROXY, UPDATE_CLIENT, UPDATE_BOTH}`, and the
  proxy's build (`RuntimeIdentity`'s pair) so copy can say *which* Control is installed.
- ~~**Fallback:**~~ *Struck (2026-10-28): there is no V2 and no older envelope to fall back to — every client and proxy is on the reshaped V1 after the reset.* The original text is kept below for the reasoning only:
  > **Fallback:** an old proxy answers V2 with the top-level `UnsupportedOperationError`; the client
  > retries **V1 announcing its session requirement, not its current version**. ⚠⚠ Retrying V1 with
  > the current vector would defeat O6 in exactly the case it exists for: a v22 Drive announcing
  > `core = 22` to a v21 Control is refused by `announced > current` before any call — and so before
  > the per-call check — could run, and `REJECT_IF_SILENT` then blocks every call. The session
  > requirement (the client's current walked back over steps whose effects do not stop the ceiling at
  > session level — under O6 `ClientSendsNewTypes` does not) is `core = 21` there, so V1 admits, and
  > O6 fails only the calls that carry `isA`. Nothing is lost by under-announcing to a V1 proxy: it
  > has no response down-levelling to feed, and it can only emit vocabulary it has, which the newer
  > client parses. ⚠ V1's proxy side gets only O2's floor fix for core, gated on steps that walk the
  > wire floor — so old clients benefit without a new envelope.
- ⚠ **Which makes V2 necessary, not optional, once O6's response half exists**: a VC-4 proxy needs
  the client's *requirement* to admit it and its *current* version to down-level responses
  correctly (announcing only the requirement would down-level types the client can in fact parse).

## 5. Refusal behaviour — a refused client must cost Control nothing

Regime note: this section is **not** a pre-release concern that goes away at the reset. Once apps
update independently through stores, a refused session is a routine state that lasts hours or days,
on every device where one app updated before another — so the storm in §1.2 is the default
post-release experience unless the rules below hold.

### 5.1 Why the rate is ~19/s: the refusal wakes its own probe (C)

1. The reachability probe issues a call; its preflight announces (`RemoteBinaryTaskExecutor.kt:81-92`).
2. The announce's own round trip carries `PreflightMarker`, completes, and parses a **successful**
   `TResult` wrapping `SessionAdmission(admitted = false)` — a refusal is a typed success by design
   (`AnnounceSessionImplementation.kt:35-38`). The executor classifies on `result is TResult.Failure`,
   so it reports **`CallOutcome.Answered`** (`RemoteBinaryTaskExecutor.kt:294`).
3. `DefaultTenshuClient` turns `Answered` into a `proxyAnswered` event (`TenshuClient.kt:139-141`).
4. `ControlReachability` feeds `proxyAnswered` into its conflated `wake` channel
   (`ControlReachability.kt:259`).
5. `SchemaAnnouncer.announce()` throws on the refusal (`SchemaAnnouncer.kt:97-102`); the probe's call
   fails (`RemoteBinaryTaskExecutor.kt:220-221`); the loop records unreachable and waits —
   `withTimeoutOrNull(5_000) { wake.receive() }` (`ControlReachability.kt:321`) — on a channel that
   already holds the token from step 4. It returns immediately, re-queries the package manager, and
   probes again.

One iteration is one package query plus one announce round trip; ~52 ms per iteration is ~19/s,
which matches both measured rates (**I** — the arithmetic, not a trace).

⚠⚠ **The code's own documentation says this cannot happen.** `CallOutcome`'s KDoc and
`ControlReachability`'s treat a refusal as `AnsweredWithError` — which is true for the refused
*caller* and false for the announce's inner call. Every test that exercises the routing feeds
outcomes through fakes (`CallOutcomeRoutingTest`, `ControlReachabilityTest`); none runs a real
executor against a refused announce, which is the one arrangement that closes the loop.

Amplifiers, each bounded on its own: every caller queued on `preflightMutex` finds the latch unset
and runs its own announce in turn (`RemoteBinaryTaskExecutor.kt:87-91`); failed subscriptions retry
at 1/2/4/8/15 s (`Stream.kt:281, 308`; `RetryingReads.kt:67`); `resubscribeAll` replays every
stream start on reconnect, each announcing behind the mutex. And the beacon prints on every
announce (`SchemaAnnouncer.kt:96`), so a storm is also a logcat flood.

### 5.2 The rules

- **R1 — A refusal is a verdict, not a failure to retry.** The client caches the refused admission
  on the executor, keyed by the transport connection. While it holds, every call fails fast with the
  cached typed error **without IPC**.
- **R2 — A refused announce never reports `Answered`.** Classify the preflight's refused reply as
  `AnsweredWithError` (it is one, semantically), so it cannot wake the probe. The fix is one
  condition; the guard is the missing test from §5.1 — a real executor against a refused announce,
  asserting no `proxyAnswered` and a bounded announce count. ⚠ Delete the fix and re-run: the test
  must go red, or it is testing a fake.
- **R3 — Re-announce only on an edge that can change the answer.**
  - **Transport reconnect.** A Control update restarts Control's process, and both transports
    already deliver that edge: Android `onServiceDisconnected`/`onBindingDied` → `onServiceConnected`
    (`BinderTaskTransport.kt:168-203`), desktop socket EOF → reconnect
    (`UnixSocketTaskTransport.kt:228-274`), both re-arming via `setOnReconnected`
    (`RemoteBinaryTaskExecutor.kt:116-123`). This covers *"the user updated Control"*.
  - **Client process start.** An app update restarts the app. Covers *"the user updated this app"*.
  - **The user's explicit "Check again"** on the surface (§6).
  - **A slow safety net** for a missed edge: `RejectionBackoff`'s shape
    (`sync-service-proxy/…/RejectionBackoff.kt:34`, 2 s × 2ⁿ) with a cap in minutes, not seconds.
    It already carries `reset()` for *"the situation may have changed"*; lifting it to
    `:core-common` is the one dependency move this needs.
  - ⚠ **Not** on *"an ordinary call was attempted"* — that is today's trigger and the storm's.
  - ⚠ An Android `ACTION_PACKAGE_REPLACED` receiver for Control's package is **not** needed: the
    binding edge arrives anyway, and no app registers one today (the only package receiver in the
    tree is Timeline's own `MY_PACKAGE_REPLACED`).
- **R4 — The reachability engine does not probe a refused session.** While `reason is Skew`, the
  probe parks on R1's invalidation edge; independently, woken probes keep a minimum gap
  (`ControlReachability.kt:314-322`), so a future outcome that wakes by mistake degrades to one probe
  per gap rather than one per round trip.
- **R5 — Background work parks on a refusal, and parking never loses work.** WorkManager jobs, the
  capture FGS's Control calls and sweeps consult R1's cache before calling, return without a retry
  storm, and resume on the invalidation edge. A cursor never advances past an item that could not
  be processed (the `drive-synced-folders.md` §8c rule) — which is what makes parking free.
  ⚠ No client-side write queue: on Android/desktop the apps have no store (ADR 0025 §Decision 1),
  and a queue would be a second store with its own skew.
- **R6 — Control pays O(1) for a repeated refusal.** Memoise `currentVersionVector` /
  `currentVersionFloors` on the `SchemaRegistry`, cleared in `registerConfig` exactly as
  `relationSpecs()` already is (`SchemaRegistry.kt:137, 233-237`); cache the namespace hash in
  `idToUuid`; keep the last reply per connection and return it for an identical vector; and, as the
  backstop against a client that ignores R1 (every build shipped before it), rate-limit refusals per
  connection.
- **R7 — Log and beacon per transition.** One `TenshuSchemaAnnounce` line per verdict change, the
  `DrainFailureLog` discipline. ⚠ The smoke gate's step 4.6 greps for the beacon's *presence*
  (`android-smoke.sh:357-406`), which one line per transition still satisfies.

**Budget, stated so a device pass can fail it:** per refused client, at most one announce per
reconnect, per process start, per user check and per backoff tick; Control's CPU with N refused
clients indistinguishable from idle; an admitted app's latency unchanged (Drive's 1.1 s folder open
is the baseline, §10 M1).

## 6. UX — per case, per platform

### 6.1 The rules the copy keeps

Carried from `core-schema-skew-surface.md` §4 and ADR 0025, and extended:

1. **Name the binary that must change, never a service.** For A/B the verdict names the side; for C
   it is the runtime owner (SK §3.1); for F it is the older app; for C′ it is a *device*.
2. **Say what is still true, and only that.** C: *"everything here is safe and still uploads"* —
   true because of push-allow. A/B on Android/desktop: nothing loads at all, so nothing may render
   that implies otherwise (ADR 0025 §1b's empty-library lesson).
3. **Never promise that waiting fixes it** (`waitingWillFix`, `WaitingWillFix.kt:27`).
4. **A button only where the route is real** — tri-state, §6.3.
5. ⚠ **Never describe other devices when the fix is on this one.** Drive's *"your other devices are
   using a newer version of Tenshu"* is true and unactionable on Android, where the fix is Control;
   it should read as the six other apps' fence copy does, naming the owner.
6. **Diagnostics are an expander, not the headline**: service ids, both vectors, both builds
   (`RuntimeIdentity.appVersion`, and V2's `proxyAppVersion`, §4.7).

### 6.2 The surface matrix

| Case | Android | Desktop | iOS route-1 | Control's own UI | Web |
|---|---|---|---|---|---|
| **A** app > Control | the app **walls**: *"Update Tenshu Control to use this version of Photos"* + route to **Control** | same | inert (the app is its own runtime) | Apps list row: *"needs a newer Control"* | n/a (in-process); web Drive ↔ apex is B′ |
| **B** app < floor | the app **walls**: *"Update Photos"* + route to **this app**; one notification if background work parked (§6.5) | same | inert | Apps list row: *"out of date"* | n/a |
| **B′** vocabulary gap while admitted | the failing surface only: scoped state *"Update Control to …"* / *"Update Drive to …"* | same | EPC only | error event names the op (caller-side, §9.3.1) | bridge `unknown method` → web Drive's banner, reload |
| **C** this device behind | every app: **banner** naming Control | same | every app: **banner** naming *this app* — ⚠ no Swift surface exists | Control's own banner (it is the owner) | web Drive: *Reload* (exists); apex: build one |
| **C′** a peer behind | — | — | — | Devices row: *"MacBook needs an update to receive changes"* — ⚠ only when a **shared realm's recorded epoch exceeds the peer's declared version** in some slot (§3.1 C′, §4.4), never on the raw vectors and never on this device's own data version: a v21 MacBook beside a v22 phone after the IPC-only `isA` step is behind numerically and misses nothing, and a phone that updated but has written no new-shaped row has raised no epoch, so nobody is behind it yet | apex Devices |
| **D** relay protocol | Control onboarding/pairing: typed, names the side (update Control, or *"Tenshu's server is being updated"*) | same | Control-on-iOS | — | apex onboarding |
| **E** stale tab / web deploy | — | — | — | — | *Reload* when the served bundle is newer; *"catching up"*, no button, when the web deploy is the stale side |
| **F** route-1 sibling older | — | — | the older app **walls** before opening the replica: *"Update Drive — another Tenshu app on this iPhone is newer"* | Control-on-iOS roll-up (SK §7 Q2) | same shape for a stale tab (**I**) |

⚠⚠ **A and B wall on Android and desktop; this amends ADR 0025 §Decision 1 (D7).** The ADR kept
`Skew` on SK-1's banner, but its own §1b test decides otherwise: *"on a platform where every read goes
over IPC, an absent Control leaves nothing truthful to render"* — and a refusing Control leaves
exactly as little, for as long (until the user acts, never a three-second blip). §8.6.1 of the
skew-surface doc and the Photos *"Something went wrong… Try again"* under an *"Update Control"*
banner are that contradiction on a real screen. SK §5's argument against a hard gate stands, and
does not apply: the transport **already** refuses the session; the wall renders that refusal
honestly instead of rendering empty lists beneath it. **C keeps the banner** for SK §5's reason —
reads work.

⚠ **F walls, and must wall *before* the store opens.** It is the one case where the current
behaviour destroys state (§3.1 F), so the surface is not the fix — the store gate is (§8 VC-7): an
older route-1 app that finds a replica stamped above its own version must refuse to open it rather
than wipe it, and render the wall from that refusal.

⚠⚠ **And an open-time check is not enough, because the older app may already be running.** (⚠ *The
rehydrate path named here is deleted with the file store, 2026-11-05; the SQLite store reads
`meta.epoch` at every outermost access, `ios-shared-store-sqlite.md` §7.*) A route-1
app that is resident — foregrounded, or woken for background work — when a newer sibling migrates
the shared replica never re-runs the open, and its foreground and background paths reuse the
existing store through rehydration (`FirstPartyLocalRuntime.rehydrate` →
`FileObjectStore.rehydrateFromDisk`), after which it can decode newer-shaped rows with its old schema
and write old-shaped rows over them. ⚠⚠ Reads are exposed as well: an uncached
`FileObjectStore.getObject` reads the blob straight from disk and `DatabaseImpl.getData` decodes it
with the older schema, before any rehydration or write batch would notice. So the guard sits **in the `ObjectStore` layer itself** — every
read and write it serves, plus every rehydration — rechecked under the cross-process replica lock.
⚠ Not at `DatabaseLock`: several production route-1 paths reach the store without it
(`OrphanedFileSweeper.sweepOnce` scans rows and writes sweep cursors; `SchemaDrivenContentWalk` and
`ProxyTypeScan.decodeRow` read and deserialize rows directly), and a guard any caller can walk
around is not one. A newer stamp invalidates the
live runtime (no further reads or writes from its in-memory state) and walls exactly as the
open-time refusal does. To keep that cheap on the hot read path, the check compares a **stamp
generation** (one small counter file, or its modification time) that the stamp-advancing side bumps
under the lock, and re-reads the full stamp only when the generation moved. Two things make that guard real rather than nominal:

- ⚠⚠ **It compares the whole stamp, not the core number.** A reshape of a *service*-owned type moves
  that service's version and leaves `TENSHU_STORAGE_FORMAT_VERSION` alone (House Rule 9), and the
  store records the two in separate sidecars — `.formatversion` and `.serviceversions`
  (`FileObjectStore.kt:346, 354`), so the guard reads every entry, not `.formatversion` alone.
- ⚠⚠ **And it compares *store versions*, not declared ones — or it recreates lockstep between
  siblings.** Stamping the declared version would make the `isA` step (core 21 → 22, no stored shape
  changed) wall a v21 Drive beside a v22 Fit, over an IPC addition route 1 never uses. So the stamp
  records, per slot, the **store version** — the declared version walked back over steps with no
  store effect, exactly as §4.4's data version walks back over steps that do not move the epoch —
  and an older runtime is invalidated only if a recorded store version exceeds the version its own
  build declares for that slot. The §4.4 argument carries over unchanged: a reader at *D* can read a
  replica written at *E* iff no store-affecting step lies in *(D, E]*, which is exactly
  *storeVersion(E) ≤ D*; and a pre-change writer that stamps its declared version only over-refuses
  (today's behaviour), never under-refuses.
- ⚠⚠ **The newer side must advance the stamp under the same lock.** `FileObjectStore.open` migrates
  and rewrites both sidecars (`FileObjectStore.kt:370-404, 583-602`) without taking the
  cross-process lock the recheck holds, so an older process could check the old stamp, a newer one
  migrate and advance it concurrently, and the older one still commit an old-shaped batch. VC-7
  moves open → migrate → stamp advance under that lock (or an equivalent atomic handoff: migrate to
  a staging directory and swap it in under the lock), so a check under the lock and a stamp
  advance can never interleave.

⚠ Both checks are small file reads under a lock the writer already takes, so they are cheap; the
failure they prevent is silent corruption of the one store every route-1 app shares.

### 6.3 Update routes

Generalise ADR 0025's `ControlInstallRoute` (`ControlInstallRoute.kt:23`) from *install Control* to
*get a binary to a version*: `AppRoute(target, purpose ∈ {INSTALL, UPDATE})`, keeping the same three
cases — `Store(url)`, `NotPublishedYet`, `SameChannelAs…` — and the same *configured-or-absent,
never derived* rule.

⚠⚠ **For UPDATE, the channel that decides is the *target's* install, not this app's.** ADR 0025
§Decision 6 reads *this* app's installer because the target is not installed; for an update the
target is right there, and a Play-installed Photos beside an adb-installed Control must not offer a
Play link for Control. Per platform:

| Platform | Target = this app (case B) | Target = Control (A, and C on Android/desktop) |
|---|---|---|
| Android | `getInstallSourceInfo(ownPackage)` → Play ⇒ store URL for this app | `getInstallSourceInfo("app.tenshu.control.android")` — ⚠ another package's installer is readable only if visible; every app already declares the `<queries>` entry (ADR 0025 §4), unverified that it suffices (§10 M6) |
| macOS | own bundle's `_MASReceipt` | `/Applications/TenshuControl.app`'s `_MASReceipt` (the presence probe already locates it) |
| Windows / Linux | `SameChannelAs…` | `SameChannelAs…` |
| iOS | per-app App Store id in `project.yml` → Info.plist (the `TENSHU_CONTROL_APP_STORE_ID` pattern) | Control's id, as today |
| Web | *Reload* — always a real route | n/a |

Two prerequisites, both outside UI code:

- ⚠⚠ **No build is updatable yet.** `versionCode = 1` in both `control/build.gradle.kts:46` and
  `drive-android/build.gradle.kts:46`, and iOS `CFBundleVersion` is a static `1`
  (`ios-drive-parity.md`): a store cannot deliver an update over the same code, and nothing can tell
  two installed builds apart. Every store route below is inert until versions move per build.
- The **URL-opening seam now exists** — `LocalUriHandler` in `ControlBannerPosture.kt:63-68` for
  *Get Control* — so `core-schema-skew-surface.md` §8.5's *"`LocalUriHandler` appears nowhere"* is
  stale and costs this nothing.

### 6.4 What keeps working while refused or fenced

| Case | Reads | Writes | Background | After the update |
|---|---|---|---|---|
| A / B (Android, desktop) | none — every read is IPC | none; user writes disabled with the wall | parked (R5), cursors frozen | resumes on the reconnect / process-start edge; nothing to replay |
| B′ | everything except the failing surface | same | the affected job parks | as above |
| C | all local reads | all local writes, **still pushed** (§7.3) | runs | pull resumes from the frozen cursor; ordinary merge |
| D | local | local; nothing syncs | — | genesis retried |
| F | none in the older app (it must not open the replica) | none | none | the updated app opens a replica it can read |

### 6.5 Notifications

- **B, when background work parks: the client notifies, once per verdict transition.** The upload-op rename's
  outage was expensive precisely because a background feature stopped silently for three days. The
  client owns the job that stopped and knows its own channel (§6.3). Action: the route.
- **A: no notification.** The user just updated this app and opens it — the wall is where they are.
- **C: no notification.** Degradation with data safe; the banner is enough. (D8 asks whether a long
  fence — days — should escalate.)
- ⚠ **Control does not notify on another app's behalf.** It could — it sees every refusal — but a
  notification from Control naming Photos is a second voice for one fact, and on Android Control
  may neither launch nor raise another app (`control-cross-app-open.md` §5e.1). Its Apps/Devices
  rows (§6.2) are passive and sufficient. D8.

## 7. Reconciliation — what this extends, supersedes, leaves alone

| Doc / code | Position |
|---|---|
| `core-schema-evolution.md` §3 asymmetric contract, §6.2 frozen envelope, §6.5 `REJECT_IF_SILENT`, §9.1 new op id | **kept**; V2 (§4.7) is a new id under §6.2's own rule |
| §4 *"floor derived from the migration chain"* | **refined**: the store floor stays exactly that; wire floor, wire requirement and data version are derived from the same chain filtered by `Compat` (§4.3) |
| §6.6 *"route-1 announces too"* | already false (SK §8.3 item 4); **replaced** for route 1 by the store-open refusal of case F |
| §7.2 epoch bump on first write | **refined**: the writer records the data version (§4.4); the trigger is unchanged |
| §9.2 per-session polyfill routing | still unbuilt, and **not needed** by this model |
| §9.3.1 typed error at the top level only | **extended** to every depth of a *request* parse (§4.2, D5); responses unchanged |
| §9.4 *"first-party apps update in near-lockstep"* | **withdrawn** as an assumption post-release; capability negotiation stays deferred there (§4.5) |
| `core-schema-skew-surface.md` §3.1 runtime-owner rule | **kept**, extended to case F |
| SK §4 banner placement, §5 against a hard gate | **kept for C**; A/B wall on Android/desktop (§6.2, D7) |
| SK §8.5 *"no button ships"* | **superseded** by §6.3 once versions and ids exist |
| ADR 0025 §Decision 1 `Skew` row | **amended** (D7); §6/§7's tri-state route generalised to UPDATE with the target-channel rule (§6.3) |
| `CoreCommonModule.kt:1270-1287` strict core wire floor | **superseded** by §4.3 (core derived like any service, over `Compat`) |
| House Rule 9 *"clients still move across a bump in lockstep"* | to be **amended when VC-3 lands**, not in this PR: lockstep across `Reshape` only (§4.3) |
| `tech-debt.md` *"Removing an operation has no compatibility window"* | **answered** by §4.6 |
| `docs/testing.md` §6 | stale: does not mention smoke steps 4.6/4.7; step 4.5's format equality is D10 |
| `mia-query-layer.md` (bump the version on an additive op so an old Control answers *"update Control"*) | consistent: that is `ClientSendsNewTypes` |

## 8. Phasing

### 8.1 Decision (2026-10-29): VC-0's client half misses v6, so R6 gates the first core bump

Installed apps cannot be retrofitted: whatever v6 (the first Play open-test build, after the
production reset) lacks on the client, every v6 install lacks for as long as it stays installed.
Measured against the code on 2026-10-29, nothing of VC-0 is built:
- **R1:** a refused preflight stays un-latched and the next call re-announces
  (`RemoteBinaryTaskExecutor`).
- **R2:** a refused `announceSession` is a `TResult.Success`, so `answered()` classifies it
  `Answered` and wakes the probe. That is §5.1's loop.
- **R3:** `RejectionBackoff` still lives in `:sync-service-proxy`.
- **R6:** `currentVersionVector` is recomputed on every announce.
- **R7:** a beacon line on every announce.

**R1–R3 are not squeezed into v6.** They are ~200–300 lines in `:core-common` plus a real-executor
test for R2 that must go red without the fix. They sit on the IPC hot path of every Android, desktop
and iOS-EPC app, so they owe the gate, the iOS lane, the Android smoke gate and a device pass:
1–2 days. A faulty refusal cache wedges every app as "refused forever", the failure this doc warns
about (a verdict outliving its fix), and v6 is the build with no room left to measure M1.

⚠⚠ **Consequence, binding: R6 must ship in Control before (or in the same release as) the first
post-release `:core` bump.** At that bump an updated Control refuses every v6 app that has not
updated yet (the strict core floor, `CoreCommonModule.kt:1270-1287`). Each refused v6 app then
re-announces in a loop bounded only by the round trip (~19/s, §5.1). The client cannot be fixed, so
only Control can bound that loop: memoise the reply per connection for an identical vector, and
rate-limit or delay repeated refusals, so a v6 client's loop runs at Control's pace. Control ships
through Play on its own schedule, so it can carry R6 in the release that introduces the bump. R1–R3
follow in v7, so the population that cannot be fixed stops growing.

⚠⚠ **The same "cannot be retrofitted" argument applies to VC-7's F′, and it is the data-loss one.**
`StoreSchemaMigrator.migrateStoreAtOpen` still calls `wipeAndStampCurrent()` when a store was written
by a newer build (`from > current`). On Android/desktop only Control owns a store, so this fires on a
Control *downgrade* (rare). On iOS route-1 the apps share one store, so the first time one app's update
migrates it, every older sibling that opens it wipes it (§1.6). That has fired on a real phone. Not
changed in the 2026-10-29 sweep, because refusing needs a decided behaviour for a Control that cannot
open its store. It is the one VC item to settle before an iOS build ships to users.

Each phase ships alone. ⚠ **VC-0 and VC-7 are not model-dependent** and should not wait for D1.

| Phase | Contents | Why here |
|---|---|---|
| **VC-0 — stop the storm** | R1–R7 (§5.2). No schema change, no wire change. ⚠⚠ **Not in v6 (decided 2026-10-29, §8.1): R6 is mandatory before the first post-release core bump.** | Degrades healthy apps today (§1.2) and becomes the default post-release experience. Independent of every decision. |
| **VC-1 — classify steps, verified; core like any service** | `compat` on `schemaMigration`, default `Unclassified` = **exactly today's behaviour per gate** (service wire floor walks, core wire floor stops, epoch moves); ⚠⚠ **the baseline verification lands in the same phase, for every service's declared steps** — a declared effect set may only relax any gate (wire floor here, the data epoch in VC-2) once the test has checked it against the observed diff, including the reachability walk from op params / returns / `Syncable` field graphs, and **bounding the six `genericData` slots** so that walk is exact (D2; without it every new type is `NewSyncedTypes` and §1.3 stands); declare the existing core steps; core's wire floor derived over the (verified) effects. | Fixes §1.1 without loosening anything on an unchecked claim. |
| **VC-2 — the data version** | epoch writer records the data version (§4.4); declare the sync 6 → 7 step. | Stops IPC-only bumps fencing peers (§1.3, §1.4). |
| **VC-3 — declarations become mandatory** | every new step must declare its effects (an undeclared new step fails the baseline instead of defaulting to `Unclassified`); HR 9 amended here. ⚠ **Not** the first time declarations are checked — VC-1 verifies every declared step, and an unverified declaration never relaxes a gate. | The E2j-1 lesson (a policy is only as safe as the test behind it), applied before the policy, not after. |
| **VC-4 — use-checked vocabulary** | O6 (§4.5), both halves: the generated `introducedIn` table and its baseline check; the client's per-call check and typed `ProxyLacksVocabulary` with each app's per-surface posture; the proxy's per-response down-level against the session's announced vector, which is what lets `ProxyEmitsNewTypes` walk the floor; typed unknown tags at any request depth (§4.2); `AnnounceSessionV2` only if D4 says so. | The remaining direction, at the granularity of use; retires the `isA` fence's *effect* on every other app. ⚠⚠ **An additive op still needs a version step** — O6 identifies an older proxy only because the owning slot moved (`proxyVector[slot] >= version`); without the bump an old proxy advertises the very version the new tag is recorded at and passes the check. The step's effects (`ClientSendsNewTypes`) are what relax the session floor, not the absence of a step. |
| **VC-5 — surfaces** | A/B wall (ADR 0025 amendment); Drive's `NeedsUpdate` names the owner; Home/Mia render skew; Swift fence + skew surfaces; Control Apps/Devices rows (C′); apex fence surface; one notification for parked background work. | After the model, so the copy names the right binary. |
| **VC-6 — update routes** | per-build version codes (Android, iOS); `AppRoute(UPDATE)` with target-channel detection; per-app store ids. | Inert until stores publish, but the mechanism must precede the first store build. |
| **VC-7 — cases F and F′: never wipe a store this build cannot read** | a store stamped above this build's versions — core **or any service entry** — is refused, not wiped, **at open and again in the `ObjectStore` layer, under the cross-process lock, on every read, write and rehydration** (§6.2); the open → migrate → stamp-advance path takes the same lock; on iOS route-1 (and IndexedDB across tabs); a live older runtime is invalidated and walls. ⚠⚠ **F′ (§1.6) is the same phase and the more urgent half**: `StoreSchemaMigrator`'s `from > current` branch must refuse rather than `wipeAllRows()`, and a refusal must never re-stamp the store down. | ⚠⚠ The only data-loss case, and F′ has already fired on a real phone (§1.6); device-verify first (§10 M7), including a resident older app during a sibling's migration. |
| **VC-8 — relay protocol, typed** | a typed `RelayProtocolUnsupported` naming the side; onboarding copy. | Small; genesis-only today. |
| *Deferred* | capability sets (O5), `REJECT_IF_SILENT` transition window, tombstones as each is needed. | Arrive with the first un-forceable client (§9.4). |

## 9. Open decisions

- **D1 — The model.** O3 (classify steps, derive every gate) plus O6 (use-checked vocabulary for
  additions a client sends) — recommended — or O1 (declare lockstep), O2 (core floor only), O4 (three
  numbers), O5 (capability negotiation)?
- **D2 — Defaults.** Unclassified steps keep today's per-gate behaviour (recommended, VC-1) or are
  treated conservatively as `Reshape`? And for `genericData`: bound the six slots so the
  reachability walk is exact (recommended, §4.2), or leave them unbounded and accept
  `NewSyncedTypes` for every new type — which leaves §1.3 broken?
- **D3 — The epoch's number.** Record the data version (§4.4, recommended), accepting that realms
  already raised stay raised until readers update?
- **D4 — A second frozen envelope.** ⭐ *Moot (2026-10-28): V1 was reshaped in place before the Play reset, carrying both vectors and the host identity — §4.7.* O6's request half works over V1 (a newer client announces its
  session requirement to a V1 proxy, §4.7); its response half needs the client's current version as
  well, which only V2 carries. Ship V2 with VC-4 (recommended), or ship only the request half and keep
  `ProxyEmitsNewTypes` behind the floor?
- **D5 — Typed nested unknown tags in requests** — reverse §9.3.1's top-level confinement for the
  request direction?
- **D6 — Removals.** Floor bump by default with an opt-in one-release tombstone (recommended), or
  tombstone by default?
- **D7 — A/B posture on Android/desktop.** Wall (amend ADR 0025 §Decision 1, recommended) or banner?
- **D8 — Who notifies.** The client for parked background work (recommended), Control on others'
  behalf, or nobody; and does a fence lasting days escalate?
- **D9 — Update routes.** Key UPDATE on the target's install channel (recommended); per-app store
  ids; who owns moving `versionCode`/`CFBundleVersion` per build?
- **D10 — Smoke gate step 4.5.** Keep `storageFormatVersion` equality as a FAIL, or demote it to a
  WARN once step 4.6's admission is the correctness assertion and lockstep is derived (§4.3)?
- **D11 — Case F.** Refuse-and-wall, with the stamp rechecked in the `ObjectStore` layer on every
  read, write and rehydration for a resident older app (recommended), read-only open, or keep the wipe? Same question for IndexedDB
  across tabs.
- **D12 — Case C′.** Surface peers-behind in Control's Devices only (recommended), or also in the
  app whose data is missing?
- **D13 — The web bridge.** Give the apex ↔ web Drive `hello`/`ready` handshake a version, or
  treat `unknown method` as B′ and rely on *Reload*?
- **D14 — Where an additive requirement is judged.** Per call, against the tags actually sent (O6,
  recommended — follows use, needs no new envelope), or per session from what a client registers
  (coarse: every rebuilt app needs the newest proxy, §4.5's corrected paragraph)? And who owns the
  per-surface posture — each app (recommended), or one shared rule (*every* `ProxyLacksVocabulary`
  walls)?

## 10. What to measure on devices

- **M1 — The storm, after VC-0.** Refused Photos + Timeline, foreground and background: announces/s
  per client, Control CPU, beacon lines per minute, and Drive's folder-open latency against the
  1.1 s baseline. Pass = §5.2's budget.
- **M2 — What restarts a force-stopped client.** Process-start reason for Photos/Timeline after
  `am force-stop` with a refused session: Control's `BIND_AUTO_CREATE` rebind, WorkManager, or the
  capture FGS (§1.2, inferred).
- **M3 — §1.4.** On an **isolated relay** (`core-schema-skew-surface.md` §8.6.2 — the emulator bakes
  the production relay): two devices at core 21, one at 22 writes; read `tenshu/sync/schema/fenceStatus`
  on the 21s for `tenshu/core`.
- **M4 — VC-1 and VC-4.** v21 Photos admitted by a v22 Control (VC-1). With VC-4: a v22 Photos that
  never sends `isA` works against a v21 Control; v22 Drive against a v21 Control fails only the
  sorted-listing calls, typed, and walls with *"Update Control"* and a Control route; no
  `TenshuSchemaAnnounce admitted=false` line for either.
- **M5 — The update edge.** With Photos refused, `adb install -r` Control: Photos re-announces on
  the reconnect, without a restart, within one reconnect. Desktop: replace `TenshuControl.app`.
- **M6 — Target-channel detection.** Control from a Play internal-testing track: is its installer
  readable from Photos via `getInstallSourceInfo`? adb-installed Control → `SameChannelAs…`.
- **M7 — Cases F and F′.** ⭐ **F′ is done (§1.6, 2026-09-16): it wipes, whole-store, and the fence
  then blocks the refill.** What remains is F proper — a *shared* replica, which needs the paid App
  Group: Fit at N+1, then launch Drive at N, watching for the launch-watchdog kill on a large
  replica, then relaunch Fit (ping-pong?). ⚠⚠ **And make the stamp read a standing step of
  `ios-smoke.sh`**, not a manual one: record `.formatversion` + `.serviceversions` before and after
  every install and FAIL on a decrease. Today the gate asserts only that the pid survives 6 s, which
  is why §1.6's wipe passed it green. ⚠ Prove the install by **symbol**, not by PASS — §1.6's first
  "fixed" rebuild reported PASS while re-installing the pre-merge binary (a fresh XCFramework does
  not force the app to relink; clearing the app's DerivedData did).
- **M8 — Web.** Two tabs across an apex deploy: does the older tab's IndexedDB gate wipe the newer
  store?
- **M9 — C′ on route 1.** Two route-1 apps at different versions under one `deviceId`: read the
  `DeviceSchemaVersion` row from a peer and check whether it flaps.

## 11. Prior art

- **Stable AIDL** (Android platform): frozen interface versions, `getInterfaceVersion()` /
  `getInterfaceHash()`, append-only methods, fields and enumerators, and a newer client calling a
  method an older server lacks gets `UNKNOWN_TRANSACTION` it must handle
  ([source.android.com — Stable AIDL](https://source.android.com/docs/core/architecture/aidl/stable-aidl)).
  The unknown-tag half of §2.2 is the same idea. ⚠ AIDL parcelables can append fields because they
  carry their size; our composites do not (`core-schema-evolution.md` §2 fact 1), so a field append
  here stays a `Reshape`.
- **Google Play services**: the client library declares the platform-app version it needs, and
  `GoogleApiAvailability` reports *update required* with a resolution that opens the store — case A
  with the route owned by the platform app; per-API `UnsupportedApiCallException` is O5's feature
  granularity
  ([UnsupportedApiCallException](https://developers.google.com/android/reference/com/google/android/gms/common/api/UnsupportedApiCallException),
  [GoogleApiAvailability](https://developers.google.com/android/reference/com/google/android/gms/common/GoogleApiAvailability)).
- **Schema registries** (Avro / Protobuf): per-schema compatibility modes — backward, forward, full,
  none — checked when a new version is registered. §4.2's `Compat` is that declaration per step,
  with the direction derived from who emits the type rather than chosen per subject.
