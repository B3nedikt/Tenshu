# iOS first-party route-1 migration — move Fit/Timeline (and future Maps) off EPC

**Status: COMPLETE (P0–P4). v0.5 · 2026-07-30.** ⚠ This header said "Draft v0.1 … No code …
Nothing is built yet" until 2026-08-11, three versions and a whole phase map behind its own §9,
which is canonical and marks every phase `✅ LANDED` — Fit (P2) and Timeline
(P3) migrated 2026-07-19, Maps was born on route 1 (2026-08-01). What remains is the
live on-device pass for Fit/Timeline, per §9's closing note. Consolidates a decision recorded
2026-07-18 across three docs into a single, schedulable migration. Historically, Drive was the only
first-party iOS app on route-1. The **gating item is a design decision, not code** — the key-scope
question (§4) must settle before any app migrates.

Owned by the iOS platform track. Companion to:
- [`ios-drive-parity.md`](ios-drive-parity.md) — productized route-1 for Drive (the template this
  migration generalises); §20 is the one-paragraph seed this doc expands.
- [`ios-proxy-architecture.md`](ios-proxy-architecture.md) §0.5 — the authoritative two-model
  framing (route-1 first-party / EPC third-party) + the trade-off route-1 makes.
- [`fit-app.md`](fit-app.md) §11.8 — the Fit-specific decision + the rejected process-lifetime EPC
  host.

Those three sections are the **decision context** that led here; **this doc is the single source of
truth for the migration** (the phase map, the gating key-scope decision, and the per-app plan). A
small follow-up — adding one-line back-pointers from those sections to this doc — is deferred while
`fit-app.md` has concurrent edits in flight.

---

## 1. Why this exists

The iOS first-party apps do not share one connection model. **Drive** runs **route-1**: its own
in-process proxy graph over a shared App-Group replica, adopting Control's device identity, needing
**no live channel to Control** (`DriveLocalRuntime`, gated by `isProvisioned()`). **Fit** and
**Timeline** still run the **interim route-2 EPC** path (`EpcFitClient` / `EpcTimelineClient` +
`FitSurface` / `TimelineSurface` + `FitLiveClientSession` / `TimelineLiveClientSession`), which
requires Control to be *actively hosting* an EPC pairing session.

That interim path is the wrong pattern for a first-party app and it bites users concretely: Fit's
permanent **"Can't reach Tenshu Control"** banner is exactly the cost of it — the co-resident EPC
invite is published only while Control's *"Connect an app"* screen is on-screen, so a merely
installed/unlocked Control never satisfies Fit (diagnosed on-device 2026-07-18; `fit-app.md` §11.8).
EPC's whole ceremony — SAS, App Attest, Rei — exists to admit apps we *don't* trust; our own signed
apps should not be paying that tax or inheriting its liveness fragility.

**Decision (settled 2026-07-18): EPC is the third-party connection protocol; every first-party iOS
app belongs on route-1.** This doc is how that decision gets executed.

## 2. Scope

**In scope:** migrate **Fit** and **Timeline** (the two first-party iOS shells currently on EPC
route-2) onto route-1.

**Maps:** there is **no `maps-ios` shell yet**. When one is built, it should be born on route-1
directly — it never needs the EPC interim. Tracked here so the pattern is one story, but there is no
Maps migration *work* until the shell exists.

**Not in scope:**
- **Drive** — already route-1 (`ios-drive-parity.md`).
- **Retiring EPC** — EPC stays. It remains correct for (a) genuine third-party apps and (b) the
  *remote-proxy* case (an iPhone app reaching a Control on desktop/Android, where no shared
  App-Group replica is possible). See §8.
- **The paid-account / App-Group provisioning** itself — a prerequisite (§7), not this doc's work.

## 3. The route-1 substrate (what Drive already built and this reuses)

Route-1 is not new machinery to invent — Drive proved it (`ios-drive-parity.md` P1–P6). The reusable
pieces already live in shared modules:

- **In-process proxy assembly** — `DriveLocalRuntime` = `koinApplication { modules(proxyModule,
  platformProxyModule, <app overrides>) }`, reusing Control's *production* proxy modules verbatim
  plus a last-wins override module (Koin later-module-wins) for the app's package name + secure
  storage. Built fresh via `buildProxyModule()`/`buildPlatformProxyModule()` (the module-`val`
  singleton-leak footgun, P1c).
- **Shared replica** — `FileObjectStore` over `sharedReplicaDirectory("objects")` (the App-Group
  container; §7), the *same* replica Control writes; decode-plaintext reads with no live channel.
- **Cross-process single-writer** — `SharedReplicaDatabaseLock` (NSRecursiveLock + flock on
  `replica.writer.lock`), bound in `platformProxyModule` iosMain — **one binding already covers all
  apps** reusing the module.
- **Key adoption** — read `RealmKeyMaterialBridge` (A4 signing blob, `sharedIdentityKey`,
  `sharedVolumeKey`, A13, ownerId, deviceId) → pre-seed the storage delegate → `initializeWithKeys`
  + the `realmRelayRegistered` / `joinedFundedRealm` latches → the standard joiner emission/drain/pull
  machinery lights up (Drive rides Control's device identity; never holds master A1).
- **Provisioning gate** — `isProvisioned()` = `RealmKeyMaterialBridge.read() != null`.
- **Lifecycle** — teardown symmetry (P2a), foreground `rehydrateFromDisk()` (P2b), and BGAppRefresh
  where **the app itself is the background syncer** (P6a).

> ⚠ *History (2026-11-05, [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) B.22.9): the
> replica and lock bullets above describe the file-store era. The shared replica is now the SQLite
> `SharedReplicaStore` in the group container's `replica/`, bound by `sharedReplicaStoreModule` in
> every production graph; SQLite's own locking replaces `SharedReplicaDatabaseLock`, and the P2b
> rehydrate is gone (the store keeps no row cache). `FileObjectStore`, `rehydrateFromDisk` and
> `SharedReplicaDatabaseLock` are deleted.*

Control's iOS proxy already hosts **all** the services these apps need — `PlatformProxyModule.kt`
iosMain calls `initFileService` + `initHealthService` + `initPlaceService` + `initTimelineService`.
So a Fit/Timeline in-process graph just installs *its* repos over the same hosted services; there is
no new service wiring.

**Consequence:** the per-app migration is mostly *assembly + a gate swap + lifecycle parity*, not
new protocol. The one genuinely new design question is §4.

## 4. The gating design decision — key scope (⚠ settle before any code)

Route-1 makes an app a **key-holding realm device**, and today the hand-off is **all-or-nothing**:
`RealmKeyMaterial` (via `RealmKeyMaterialBridge`, one shared-Keychain blob) carries **realm-level**
keys — `sharedVolumeKey` (AEAD-decrypts *all* file content), `sharedIdentityKey`, A13 — **not**
app-scoped. So a route-1 **Fit would hold `sharedVolumeKey` and could decrypt Drive's file bytes.**

This collapses the app↔proxy least-privilege boundary that EPC/Rei gave *even to first-party apps*,
and it is **asymmetric with Android**, where a separate proxy process mediates what each app sees.
The **E2E guarantees still hold** — relay sees ciphertext only (HR#1), keys live only on provisioned
devices, master A1 never leaves Control — so what is traded is *inter-first-party-app least
privilege* (defense-in-depth between our own signed apps), a tier below the E2E guarantees.

For **Drive alone** this is moot (one route-1 app can't over-read anyone). It becomes real the moment
a **second** first-party app goes route-1. So this decision gates Fit (the second app), not Drive.

**Options:**
- **A — whole realm blob (status quo shape).** Every route-1 app reads the same `RealmKeyMaterial`.
  Simplest; zero new crypto. Cost: full cross-first-party-app read on-device.
- **B — scoped material.** The bridge hands each app only what it needs — e.g. per-service content
  keys, or a **metadata-only** key for apps that never touch file content (Fit/Timeline read
  structured health/place/timeline rows, not the file-content plane; they arguably need
  `sharedIdentityKey` for metadata blocks but **not** `sharedVolumeKey`). Preserves least privilege;
  costs a key-derivation/scoping design + a richer bridge.
- **C — accept A now, revisit under third-party.** Ship A for first-party (we sign every app; App
  Store review is the practical gate), and treat scoped material as part of the third-party track
  (where it is non-negotiable), not the first-party migration.

**⚠ Content-key scoping is binary + fights the lens model.** `sharedVolumeKey` is *one key for the
entire content plane* (per-blob CEKs are all wrapped under it), so app-level scoping is all-or-nothing:
an app either decrypts *all* file content or none. That collides with the "apps are lenses over one
realm" model the moment a nominally-metadata-only app needs file content — e.g. **Timeline showing a
Drive photo in a Moment**: it would need the whole content key (→ reads *all* Drive files), so scoping
either breaks the feature or degrades to whole-plane anyway. (Route-1 can't replicate the Android/
desktop model where the proxy holds the key and mediates per-file, permission-gated — on iOS each app
*is* its own in-process proxy and self-decrypts; per-app key custody is the only lever and it is
coarse.) Note previews soften this: `File.Thumbnail`/`File.Preview` are metadata-plane value props
(under `sharedIdentityKey`), so a metadata-only app can already render low-res previews without the
content key — only full-res originals need it.

**DECISION (2026-07-19, maintainer): C — whole-blob for first-party (supersedes the earlier B).**
Every route-1 first-party app reads the same whole `RealmKeyMaterial` (identity + content + A13 +
device keys), exactly as Drive already does. Rationale: they are all Tenshu-signed apps, App Store
review is the practical gate, and per-app content scoping (B) is binary/coarse and fights the lens
model (above) for **zero** benefit against a *buggy* first-party app and only theatre against a
*malicious* one unless taken all the way to per-app Keychain custody — not worth the machinery now.
- **Accepted posture:** on iOS route-1, first-party apps are **realm co-owners** — no inter-first-party-
  app least privilege (an app can decrypt any realm content/metadata on-device). E2E guarantees are
  unchanged (relay sees ciphertext, keys only on provisioned devices, master A1 stays in Control).
- **No scoped-custody work** — Control keeps publishing the single `RealmKeyMaterial` blob; a route-1
  app just reads it. This is why P2 needs no P2a foundation.
- **Future track (out of scope):** true per-file / per-app capability (per-app-wrapped CEKs or
  per-service sub-realms) — the *only* real fix for granular cross-app content access — is where B's
  goal actually belongs, and it becomes non-negotiable for **third-party** apps regardless.
- **Threat-model amendment** (House Rule #2): extend the A4 route-1 custody note (Drive already holds
  the whole blob) to Fit/Timeline; record "no inter-first-party-app least privilege on iOS route-1" as
  an accepted decision. Lands with **P2** (first app beyond Drive to adopt).

## 5. Generalisation approach — `DriveLocalRuntime` → a shared first-party runtime

`DriveLocalRuntime` is Drive-flavoured but ~90% generic. Extract the reusable assembly so each app is
a thin consumer:

- A shared **`FirstPartyLocalRuntime`** (name TBD) owning: fresh Koin assembly over
  `buildProxyModule()`/`buildPlatformProxyModule()`, replica-dir + lock wiring, key adoption from
  `RealmKeyMaterialBridge` (whole blob per §4 → C; no per-app scoping), `isProvisioned()`, teardown, and
  foreground rehydrate.
- Per-app **overrides**: package name, secure-storage delegate name, and the **repo set** installed
  onto the surface (Drive's 3 file repos vs Fit's health repos vs Timeline's place/timeline repos).
- `DriveLocalRuntime` becomes a thin front over it — ⚠ **its public API is frozen by this
  extraction**: the delegate exists so Drive's call sites need no change, so widening or
  reshaping it defeats the point and breaks the shared-runtime contract the other three apps
  now depend on. (No behaviour change — regression-guarded by the
  existing `DriveLocalRuntimeTest` + A/B sim harness).

This is deliberately an **extraction on the second consumer**, not a speculative framework — mirrors
how `:app-liveness` / `:app-outbox` / `:app-reachability` were pulled out when the second app needed
them. Do it *as part of* Fit (Phase 2), not ahead of it.

## 6. Per-app migration

Each app's shell work mirrors Drive's P1–P6, but most substrate is shared (§3/§5), so the per-app
delta is small:

**Fit (`fit-ios`):**
- Swap the gate: `RootView`/`MainShell` gate on `FirstPartyLocalRuntime.isProvisioned()` (like
  Drive's `RootView`), not `FitLiveClientSession.isActive`. The "Can't reach Control" banner logic
  goes away for the co-resident case (route-1 needs no hosting) — it only survives for the
  remote-proxy fallback (§8).
- Install Fit's health repos over the in-process surface (retire `EpcFitClient` for the first-party
  path; keep it for remote/third-party).
- Lifecycle parity: teardown, foreground rehydrate, BGAppRefresh as **app-as-syncer** (Fit already
  has a route-2 `FitBackgroundRefresh` that no-ops when Control isn't hosting — route-1 removes that
  caveat).
- The recorder / outbox / HealthKit paths are unaffected (they sit above the repo layer).

**Timeline (`timeline-ios`):** same shape over `TimelineSurface` + the place/timeline repos.

**Maps:** born on route-1 when `maps-ios` is created (no migration).

## 7. Gating — App-Group paid-account prerequisite

> ⚠⚠ **Corrected 2026-10-02.** This section said route-1 "cannot be provisioned" on a free team.
> That was the intent and not the behaviour: the device entitlements carried no App Group, the
> shared **Keychain** hand-off is free-tier, and `sharedReplicaDirectory` fell back silently — so
> every route-1 app *was* provisioned on the free team and ran on **its own** app-local replica
> under Control's one device id. The apps are on the paid company team now, every
> `*.device.entitlements` declares the group, and a device build that cannot resolve the container
> throws instead of falling back
> ([`ios-background-scheduling.md`](ios-background-scheduling.md) §4.1.1).

Route-1's shared replica is the **App-Group container** (`sharedReplicaDirectory` →
`appGroupContainerDirectory`, which returns null without the entitlement). The App-Group
entitlement requires a **paid Apple Developer account**. This is **moot for the shipping product**
(App Store distribution needs a paid account regardless), but it means:

- On a **free personal team**, route-1 has no shared replica; Fit/Timeline were meant to stay on
  the EPC interim there.
- The **free-tier interim** for un-migrated apps is EPC-over-relay + the honest banner copy already
  shipped ("open Control's *Connect an app* screen").

See [[project_ios_appgroup_fallback_free_signing]].

## 8. What stays on EPC (do not delete it)

EPC route-2 remains the correct model for two cases the migration does **not** cover:
1. **Third-party apps** — a foreign app reaching someone's Control. This is EPC's real home, where
   SAS + App Attest + Rei are load-bearing. (No third-party consumer exists yet; only the 4a
   software-attestor seam is built.)
2. **Remote proxy** — an iPhone first-party app whose Control lives on another device (desktop /
   Android). No shared App-Group replica is possible across devices, so this stays EPC (or a future
   "iPhone app as its own relay-syncing realm device", out of scope here).

So the migration **adds** route-1 as the co-resident first-party path and leaves EPC for the above.
An app may end up choosing at runtime: provisioned co-resident key material → route-1; else → EPC to
a remote/hosting Control.

> **2026-11-05 — the co-resident EPC handoff is deleted (cleanup PR D).** What this section protects
> is cases 1 and 2 above, and neither was ever the **co-resident** EPC handoff that predated
> route 1. That one was deleted, because route 1 (Decision C) is the co-resident case:
> - **Fit's `ControlAutoConnect`** is gone. It read the `tenshu://epc/pair#…` invite Control
>   publishes into the shared Keychain and connected with **no SAS compare**. It fired only when
>   route 1 was *not* provisioned **and** Control's "Connect an app" sheet happened to be open,
>   and a shared-Keychain read can never reach a Control on another device, so it was never
>   case 2. Fit's shipping gate is now route-1-only. Not provisioned, it shows *Set Up Tenshu
>   Control*, and its BGAppRefresh no-ops.
> - **The C3 `epc-session` presence token** is no longer minted (`provisionDriveSession` is
>   deleted; genesis, join and recovery call `ensureDriveProvisioned`, which publishes the key
>   material only). The `epc-session` *item* stays for `publishEpcInvite`, and `clearDriveSession`
>   still deletes it, which also sweeps leftover tokens off test phones.
> - **Drive's DEV_HARNESS Smokes tab** (`EpcLoopback`, the five EPC / rendezvous smokes,
>   `CryptoSmoke`, the two read/detail-surface smokes, `SmokeReport`) is gone. The Kotlin unit
>   tests cover the protocol, and the new `EpcDriveClientTest` guards `EpcDriveClient`'s assembly
>   on `iosSimulatorArm64Test`.
>
> ⚠ **Kept, deliberately:** Control's *Connect an app* host (`EpcAppPairingView`,
> `IosEpcPairingController`, `-epcAppPairing`), the Kotlin EPC host and protocol,
> `EpcDriveClient` / `EpcFitClient` / `EpcTimelineClient` and every `*LiveClientSession`. These are
> cases 1 and 2. After the deletion, no **shipping** iOS app consumes *Connect an app*: the only
> iOS readers left are the DEV_HARNESS *Pair* tabs (QR/paste + SAS) and Drive's DEV_HARNESS
> `-epcAutoConnectFromControl`. Its consumers are a third-party app or a first-party app on another
> device. The threat model's "Co-resident EPC auto-connect sub-amendment" is retired in the same PR.

## 9. Phase map

| Phase | What | Status / Gate |
|---|---|---|
| **P0 — Key scope** | Settle §4. Design only. | ✅ **DECIDED C** (2026-07-19) — whole-blob for first-party; no scoped custody |
| **P1 — Extract shared runtime** | `DriveLocalRuntime` → `FirstPartyLocalRuntime` + Drive as thin consumer (no behaviour change). | ✅ **LANDED** (`DriveLocalRuntimeTest` 11/11 + `drive-ios` xcodebuild green) |
| **P2 — Fit → route-1** | `FitLocalRuntime` (thin consumer over the P1 substrate, whole-blob) + Fit shell gate swap (`isProvisioned()` not `FitLiveClientSession.isActive`) + health repos over the in-process surface + lifecycle parity (teardown / foreground rehydrate / BGAppRefresh app-as-syncer). EPC kept for remote/3P. **Threat-model amendment** (extend A4 route-1 custody to Fit; record no-inter-app-least-privilege). | ✅ **LANDED** — P2-1 `FitLocalRuntime` + test, P2-2 shell gate + App-Group entitlement + A4 amendment; `FitLocalRuntimeTest` 2/2 + `fit-ios` xcodebuild green |
| **P3 — Timeline → route-1** | Same for Timeline (`TimelineLocalRuntime` + shell gate). | ✅ **LANDED** — P3-1 `TimelineLocalRuntime` + test, P3-2 shell gate + **entitlements from scratch** (timeline-ios was mock-only); `TimelineLocalRuntimeTest` 2/2 + `timeline-ios` xcodebuild green |
| **P4 — Maps** | Born on route-1 when `maps-ios` lands. | ✅ **LANDED 2026-08-01** — `maps-ios` shipped with `MapsLocalRuntime` from its first commit, so Maps was *born* on route 1 exactly as this row predicted: no EPC tier ever existed for it, so there was nothing to migrate, only a runtime to write. A thin `FirstPartyLocalRuntime` consumer needing **no new service wiring** (the graph already hosts `:place-service` + `MapRegionService`); `MapsLocalRuntimeTest` + `maps-ios` xcodebuild + a simulator render. As built: `ios-fit-timeline-maps-parity.md` §3.3.1. **Phase map P0–P4 COMPLETE.** |

Under C there is **no scoped-custody foundation** — P2/P3 are direct thin-consumer light-ups over the
existing whole `RealmKeyMaterial`. Each phase is independently shippable; P2/P3 reuse the P1 substrate.

**Migration code COMPLETE (2026-07-19): P0→P1→P2(Fit)→P3(Timeline) all merged.** Per-slice: unit
`*LocalRuntimeTest` + per-app `xcodebuild` green; no Android touch. ⚠ Two deferrals, neither blocking:
(1) the **live on-device pass** for Fit + Timeline — a Control-genesis A/B run + the paid App-Group
account (the sim builds verify compilation + the not-provisioned path; the live route-1 handshake is
the Drive-P1d posture); (2) **P4 Maps** on `maps-ios` arrival. On-device verification per app via
`scripts/ios-smoke.sh --build <app>` + the A/B sim harness (mirror `ios-drive-parity.md`).

### P2-2 / P3-2 as-built notes
- **Fit (P2-2):** gates route-1 **first**, keeps EPC (`ControlAutoConnect`) as the not-provisioned
  fallback; the banner shows only when neither route-1 nor EPC is live; `FitBackgroundRefresh` drives
  `FitLocalRuntime.runBackgroundRefresh` when provisioned. ⚠ `FitLocalRuntime.assemble` importer is
  **injectable** (default `HealthKitImporter`) — `HealthKitImporter.startImport` aborts a bare test
  host lacking `NSHealthShareUsageDescription`, so tests pass `ReferenceHealthImporter`.
- **Timeline (P3-2):** ⚠ shipping `timeline-ios` was **mock-only with no Control connection + no
  entitlements** (the EPC live client is `DEV_HARNESS`-only). So P3-2 added the route-1 gate **and**
  the `Timeline.entitlements` / `Timeline.device.entitlements` (shared Keychain + App Group) + the
  `project.yml` wiring from scratch. No EPC fallback in shipping → the not-provisioned fallback was
  the existing mock. ⚠ `.liveSessionChanged` is `DEV_HARNESS`-scoped (in `EpcPairingView.swift`) → the
  gate bumps its remount token directly instead. No new threat-model amendment (the P2-2 A4 note
  already covers "Timeline/Maps as they migrate").
- **Timeline mock fallback RETIRED (2026-07-31).** ⚠⚠ That "not-provisioned fallback is the existing
  mock" line was a real bug in the shipping app, not just an unfinished edge: an **unprovisioned user
  saw `MockTimelineRepository`'s invented places, trips and an invented "interlude" with nothing
  marking them as fabricated** — the `ImportStatus.Sample` anti-pattern Fit had already retired in
  §11.7. Closed by the Fit shape, verbatim: a new `NotConnectedTimelineRepository`
  (`:tenshu-sdk-ios`, the `NotConnectedFitRepository` twin — empty reads, `controlReachable = false`)
  is now `TimelineSurface`'s **default** and its teardown target (`installNotConnected()`, called by
  `TimelineLocalRuntime.deprovision` + `TimelineLiveClientSession.teardown`), and
  `TimelineRootView` stacks a `ControlNotConnectedBanner` above the nav stack off
  `TimelineSurface.controlReachable()` — the iOS twin of the Compose shell's
  `repository.controlReachable()` read (`timeline-shared-ui` `App.kt`). `MockTimelineRepository` and
  `installMock()` survive for tests and previews only. This answers **Q4** for Timeline: the
  not-connected surface is reachable in shipping (Control not yet unlocked), and its copy names
  *unlocking Control*, matching Fit's.

## 10. Open questions

- **Q1 (gating) — key scope. ✅ DECIDED 2026-07-19 → C (whole-blob for first-party; supersedes B).**
  Per-app content scoping is binary + fights the lens model (Timeline-photos-in-a-Moment); first-party
  apps are realm co-owners on iOS route-1, granular per-file access = a future capability track. See §4.
- **Q2 — runtime route selection.** When both a co-resident provisioned Control *and* a remote
  Control are possible, how does an app choose route-1 vs EPC? (Drive currently assumes co-resident;
  the remote-Control-from-iPhone case is unspecified for Drive too — settle once, share.)
- **Q3 — extraction shape.** Does `FirstPartyLocalRuntime` live in `:tenshu-sdk-ios` alongside the
  surfaces, or a new shared module? (Lean: keep it in `:tenshu-sdk-ios` next to `DriveLocalRuntime`;
  it is iOS-only glue.)
- **Q4 — banner/UX after migration.** Post-route-1, the "not connected" surface is only reachable in
  the remote-proxy fallback; confirm the copy + whether Fit/Timeline even support remote proxy at v1
  (if not, the banner can be dropped entirely for them).
  **2026-11-05:** the co-resident EPC fallback that could lift Fit's banner is deleted
  (`ControlAutoConnect`, §8 note). In a shipping Fit, *Set Up Tenshu Control* and the banner now
  clear only when route 1 is provisioned. No shipping build reaches a remote proxy, so the
  remote-proxy half of this question is still open and still unbuilt.

## 11. Implementation notes for Claude Code

| Concern | Existing code to build on |
|---|---|
| Route-1 template | `tenshu-sdk-ios/.../DriveLocalRuntime.kt` (generalise); `DriveReadSurface.installLocalRuntime()` |
| Shared assembly | `buildProxyModule()` / `buildPlatformProxyModule()` (avoid the module-`val` singleton leak, `ios-drive-parity.md` P1c) |
| Key adoption / gate | `core-common/.../RealmKeyMaterialBridge.kt`; `isProvisioned()` shape (§4 → C: whole blob, **bridge unchanged**) |
| Shared replica + lock | `SharedReplicaStore.kt` (`core-common/src/iosSqliteMain`), bound by `sharedReplicaStoreModule` (`tenshu-sdk-ios-core/.../SharedReplicaBinding.kt`); `sharedReplicaDirectory` (`SharedReplicaLocation.kt`). The file-era `FileObjectStore` / `SharedReplicaDatabaseLock` are deleted (`ios-shared-store-sqlite.md` B.22.9) |
| Services hosted | `control-shared-proxy/.../PlatformProxyModule.kt` iosMain (`initHealthService`/`initPlaceService`/`initTimelineService` already wired) |
| Fit shell to swap | `fit-ios/Sources/FitIosApp.swift` + `RootView`/`MainShell` gate; `FitSurface`/`FitLiveClientSession`/`EpcFitClient` (keep EPC for remote/3P) |
| Timeline shell | `timeline-ios/`; `TimelineSurface`/`TimelineLiveClientSession`/`EpcTimelineClient` |
| Lifecycle parity | Drive's teardown (P2a) / `BackgroundSync` app-as-syncer (P6a); P2b's `rehydrateFromDisk` is gone with the file store |

Verification bar per slice: `:tenshu-sdk-ios:iosSimulatorArm64Test` + `xcodebuild` for the touched
app + `scripts/ios-smoke.sh --build <app>` on hardware + the A/B sim harness. Threat-model amendment
lands with P0 and per-phase key-custody deltas (House Rule #2).

## Version

v0.6 · 2026-11-05 · **The pre-route-1 co-resident EPC handoff is retired** (§8 note, Q4). Fit's
`ControlAutoConnect`, the C3 `epc-session` presence token and Drive's DEV_HARNESS Smokes tab are
deleted. Route 1 covers the co-resident case. *Connect an app* and the §8 EPC clients are kept.
⚠ The v0.5 entry below says `ensureDriveProvisioned()` mints the presence token "only into an
empty slot". That is no longer true: it publishes only the key material.

v0.5 · 2026-07-30 · **Hardware find: the provisioning could be lost with no way back — fixed.**
Fit on a real iPhone showed "Can't reach Tenshu Control" *permanently*; the shared-Keychain items
(`epc-session` + `RealmKeyMaterial`) were both absent, and nothing in a shipping build could
re-create them — `provisionDriveSession()` runs only at genesis/recovery, the unlock hook was
refresh-only, and Control-on-iOS has no "Connect an app" host to fall back on. `ControlSurface`
gains `ensureDriveProvisioned()` (create-if-absent, presence token only into an empty slot), called
on unlock **and** on a cold start that lands `.ready` outright. This is the P0-C decision's
lifecycle half: if first-party apps are realm co-owners, an unlocked realm must *keep* provisioning
them, not only at first genesis. Full write-up: `ios-drive-parity.md` §25.

v0.4 · 2026-07-19 · **Migration code COMPLETE — P2 (Fit) + P3 (Timeline) LANDED.** P2-1 `FitLocalRuntime`
+ test → P2-2 Fit shell gate + App-Group entitlement + A4 amendment; P3-1
`TimelineLocalRuntime` + test → P3-2 Timeline shell gate + **entitlements from scratch**
(timeline-ios was mock-only). Per-slice unit `*LocalRuntimeTest` + per-app `xcodebuild` green;
no Android touch. §9 phase map + as-built notes updated. Deferred (non-blocking): the live on-device
pass for Fit/Timeline (Control-genesis A/B + paid App-Group), and P4 Maps on `maps-ios` arrival.

v0.3 · 2026-07-19 · **P0 re-decided → C (whole-blob for first-party), superseding B.** The
Timeline-photos-in-a-Moment case showed per-app content scoping is binary (`sharedVolumeKey` = one
key for the whole content plane) and fights the "apps are lenses over one realm" model; first-party
apps are accepted as realm co-owners on iOS route-1 (no inter-app least privilege), with true per-file
capability deferred to a future track. **P2a (scoped custody) dropped** — P2 is now a direct Fit
thin-consumer light-up. §4/§9/Q1 updated.

v0.2 · 2026-07-19 · **P0 decided → B (real per-app content-key custody scoping)** + **P1 landed**
(`FirstPartyLocalRuntime` extracted). Phase map reshaped: P2a (scoped custody, the B
foundation + threat-model amendment) now precedes P2b (Fit light-up). §4 records the decision +
the runtime-restraint-is-theater / metadata-leak-residual caveats. *(Superseded by v0.3.)*

v0.1 · 2026-07-19 · Initial draft. Consolidates the 2026-07-18 decision (fit-app.md §11.8 /
ios-drive-parity.md §20 / ios-proxy-architecture.md §0.5) into a schedulable migration; the key-scope
question (§4) is the gating design decision. No code.
