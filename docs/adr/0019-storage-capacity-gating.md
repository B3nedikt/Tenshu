# ADR 0019 — Storage capacity gating: account-and-fits, enforced at the op

- **Status:** Accepted, implemented (arc closed 2026-06-04); **amended 2026-06-06
  — local-first reframe** (`hasStorage` gates *relay contact*, not *local ops*;
  see the Amendment section below)
- **Date:** 2026-06-02 (proposal) → 2026-06-04 (Phases 1–5 land)
  → 2026-06-06 (local-first reframe)
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Drive's sharing and upload features were fully available with **no storage
account at all** — *Generate link*, *Share folder*, and file/folder upload all
ran unconditionally, even for a user who had never signed up for Tenshu storage
(free tier or otherwise). The user-visible promise ("your stuff is stored and
shareable") had no gate behind it.

The first framing was "gate sharing behind a subscription tier." The maintainer
reframed it: the gate is **not** "what plan does the user have" — it is the same
two-part question for *every* storage-consuming action:

> Allowed iff the user **has a storage account** *and* the action **fits** in
> their remaining quota.

Sharing is not special: Mode-B *Share folder* copies the folder subtree into a
shared realm on the relay, and Mode-A *Generate link* re-encrypts and uploads a
relay copy ([ADR 0018](0018-file-sharing-mode-a.md)) — both consume the owner's
storage exactly like an upload. The subscription tier (Free / Lifetime / Rei)
only matters insofar as it sets the quota; the gate never branches on plan.

What existed at the start: a `StorageService` *named* in the IA proposals
(`control-ia.md`, `web-control-plane.md`) but unimplemented; the entitlement
(`PublisherClient` / `PublisherAccount`, `tenshu-publisher.md`) living only in
`:control-shared`, mocked, and **not** on Drive's `TenshuClient` boundary (Drive
runs no proxy); and an explicitly honour-system relay (no per-owner quota
enforcement, `tenshu-publisher.md` §10.1).

## Decision

A dedicated `:storage-service` provides the gate signal and enforcement; Drive
gates its share/upload entry points on it. Module + op surface in
[`docs/proposals/storage-capacity-gating.md`](../proposals/storage-capacity-gating.md)
(graduated stub) and the per-`org.tenshu.storage` source. Eight anchor
decisions:

1. **Gate condition = `hasStorage && fits`, never plan tier.** Account-existence
   + capacity, applied uniformly to upload, folder upload, Mode-A link share,
   Mode-B folder share. The tier sets the quota and nothing else.

2. **Three tiers, each paying only for what it needs.** (1) A **cached reactive
   `StorageStatus`** signal gates the UI with **zero per-action round trips**;
   (2) an **op-embedded authoritative check** is the un-bypassable gate; (3) a
   **`CheckCapacity` pre-flight** is reserved for folder uploads (one deliberate
   call). The move that avoids a check-then-act round-trip storm: **enforce
   inside the op, on the proxy, where the byte size is already in hand** — so
   single-item actions need no separate capacity call.

3. **`:storage-service` is its own module set, decoupled from Publishing.**
   *Entitlement* (what the user is granted — the Publisher's / Rei's job) is
   separate from *provision* (storing the bytes — the relay's job).
   `StorageService` owns provision: it reads the **limit from the entitlement**
   and the **usage from the relay** through an internal `StorageProvider` seam
   and depends on **neither's code**. v1 binds a mock `StorageProvider` (reads
   the mocked `PublisherClient.account`) **in Control's DI**, so `:storage-service`
   carries no `:control-shared` / Publisher dependency. The real Publisher +
   relay-reported usage later swap the binding behind the same contract — no
   Drive or contract change.

4. **`StorageStatus` is a single value, not a list** — "one Remote Service ==
   one Provider"; multiple concurrent providers is a deliberate non-goal.
   Realised as a single-row `TList<StorageStatus>` relation projected to
   `Maybe<StorageStatus>` at the client (there is no bare-`Maybe` service-property
   precedent). It is **locally derived, not `Syncable`** — each device's proxy
   derives it from entitlement + usage.

5. **Hard limit.** The op rejects exactly at `used + required > limit`; no grace
   band. The 80%-usage banner (`control-subscription-ux.md` §2.3) stays a
   Control-side *warning* and never relaxes the gate.

6. **v1 is a UX gate + proxy-against-mock enforcement; the relay stays
   honour-system.** No A1–A4 / A9 / sync-protocol surface is touched, so no
   threat-model amendment. Real relay-side quota enforcement is deferred to the
   real Publisher track (`tenshu-publisher.md`).

7. **Typed errors cross the wire.** `NoStorageError` and
   `InsufficientStorageError(requiredBytes, availableBytes)` are `TError`
   subtypes (the `core-error-channel.md` `TError` taxonomy) registered so they
   dispatch structurally on the client. The op throws them via a
   `StorageCapacityHolder.ensureCapacity(ctx, bytes)` guard fed by the same
   `StorageProvider` collection that drives the Tier-1 relation — one source,
   both tiers. The guard is a **no-op when figures are unwired/unknown** (test
   harnesses, pre-derivation), matching Tier-1's optimistic-on-unknown.

8. **The "set up storage" CTA deep-links to Control's Services screen.** Drive
   puts a plain string extra on Control's launcher intent; Control's
   `MainActivity` reads it → `App(deepLink = Destination.Services)`. **No
   manifest intent-filter** — the target is the already-exported LAUNCHER
   activity. The deep link applies **only when Control resolves to its Active
   landing**; a Locked / FirstTime landing wins, so a deep link cannot bypass
   unlock or onboarding.

## Consequences

- **One gate, all storage-consuming actions.** Share-a-folder, generate-link,
  file upload, and folder upload share the same condition. The user reaches
  storage setup from any blocked action.
- **Tier-1 (`drive/domain/StorageGate`)** decides `Allow / NeedsStorage / Full`
  from the cached `StorageStatusModel`; unknown ⇒ `Allow` (optimistic) because
  Tier-2 is the authoritative backstop. The two-state `StorageGateDialog`
  (no-storage / out-of-storage) is shown at the Link / Share-folder entry points.
- **Tier-2 is un-bypassable.** `UploadFile`/`ReplaceFile` (+ Bg), `ShareLink`,
  and the Android SAF upload/replace impls call the guard with the real size;
  `ShareFolderIntoRealm` enforces the account requirement up-front (byte-precise
  folder-share enforcement needs a size pre-pass — deferred, and dormant under
  the mock's zero usage). A skipped/raced Tier-1 still fails cleanly with no
  unusable share/upload minted.
- **Tier-3 folder pre-flight.** `TenshuFilesRepository.uploadFolder` sums the
  locally-known `FolderEntry.sizeBytes` and calls `checkCapacity` once before the
  loop; `!fits` ⇒ early `RepositoryError.InsufficientStorage`, surfaced as a
  distinct `FolderUploadOutcome.InsufficientStorage` ("Not enough storage to
  upload this folder") — no partial upload. `checkCapacity` is **internal** to
  the repository (its only consumer); single items rely on Tier-2.
- **New module set** `:storage-service` (+ `-common` / `-client` / `-proxy` /
  `-test-utils`), mirroring `:preference-service`. Registered in both Drive's and
  Control's `coreSchemaConfigs`; the mock provider + `initStorageService` +
  op-factory are wired in Control's platform proxy modules.
- **`RepositoryError.InsufficientStorage`** rippled to four exhaustive `when`
  sites (create-folder ×2, move-picker, folder-upload notification), each given
  a sensible branch.
- **Android glue is desktop-verified only.** The Drive launcher intent, Control
  `MainActivity` deep-link handling (`onNewIntent`/`recreate`), and the Android
  upload/replace op-impl threading were written but **not compiled on Android**
  (no SDK in the build env) — they need an on-device sweep.
- **House Rule #1 unchanged.** The relay still sees ciphertext only; storage
  gating adds no server-side business logic.

## Amendment 2026-06-06 — local-first reframe

The original decision applied `hasStorage && fits` uniformly to **every**
storage-consuming action, including upload (Decision 1). In use this proved
**inverted**: upload *hard-failed* with `NoStorageError` for a user with no
subscription — yet the file bytes are written to the local DB before the relay is
ever involved, so there was no technical reason to fail — while the personal sync
cycle and first-device realm registration *unconditionally* contacted the relay
regardless of subscription. We were blocking the local thing and freely doing the
relay things.

The maintainer reframed the principle: **`hasStorage` gates *relay contact*, not
*local operations*.** No subscription ⇒ a fully local app (create realms locally,
upload locally, browse) with **zero relay traffic**; a subscription ⇒ relay
registration + the sync drain turn on and flush what queued. Shipped as a 5-phase
track:

1. **Personal-realm sync gate.** `SyncScheduler.tickOnce()` gates the personal
   realm's drain/pull on a `StorageSyncGate` (reads the same `StorageCapacityHolder`
   as Tier-2). No subscription ⇒ the personal realm is local-only; queued blocks
   flush once it binds. **Shared realms are exempt** (owner-funded — an invited
   free member still syncs them). **Refinement (2026-06-08):** push and pull are
   gated *separately* — **push/genesis/drain** on `StorageSyncGate`, but **inbound
   pull** additionally opens on `SyncManager.realmRelayRegistered` (the realm was
   funded at least once). This closes the cross-device entitlement bootstrap
   deadlock for the **genesis** device when the subscription lives on another of the
   owner's devices (and for devices paired before the `joinedFundedRealm` latch
   existed); a *never-funded* realm is still never contacted. See
   [`storage-entitlement-sync.md` §3.3a](../proposals/storage-entitlement-sync.md).
2. **Lazy realm registration.** `CreateRealm` is local-only; the `POST /sync/realm`
   genesis is deferred to first-subscribe (`RealmRegistrar`, persistent
   `SyncManager.realmRelayRegistered` latch). Pairing a 2nd device now requires a
   subscription. See [`sync/design.md` §2.2](../sync/design.md).
3. **Local-first upload.** A new `ensureWithinQuota` guard replaces `ensureCapacity`
   on the **upload** path: no subscription ⇒ **no-op** (store locally + queue),
   only a *subscribed* user over quota is blocked (`InsufficientStorageError`).
   `ensureCapacity` (the `!hasStorage` → `NoStorageError` hard block) stays on the
   **share** paths — Mode-A link / Mode-B folder share genuinely cannot work
   without the relay. This **supersedes Decision 1 for upload only** (upload is no
   longer gated on account-existence; sharing still is). It also closed the
   originally-reported bug: a no-account upload silently failed (mapped to
   `RepositoryError.Unknown`, swallowed by `AddFileActionsViewModel`); now it
   succeeds locally, and the only remaining upload error — subscribed-over-quota —
   is surfaced via the `StorageGateDialog` (the *When to revisit #2* item, now
   done for upload).
4. **Sync-status banner.** A persistent `SyncBannerState.LocalOnly` banner above
   the Files list (driven by the reactive `StorageStatus`) tells the user their
   files are local-only, with a "set up storage" CTA. Global banner, not a per-row
   badge (the reason is account-wide). The transient `Offline` / `Syncing` states
   are deferred — they need a Control→Drive sync-health channel
   ([`docs/proposals/drive-connectivity-service.md`](../proposals/drive-connectivity-service.md)).

**Threat model.** No change to assets A1–A4 / A9, the relay's data shape, or the
sync protocol wire. The only deltas are *client-side timing* (when `POST /sync/realm`
and the first push happen) and *which local action is gated* — both strictly
*reduce* relay exposure for unsubscribed users (Decision 6's "no A1–A4 / A9 /
sync-protocol surface" still holds). Recorded as a non-asset timing note in
`docs/sync/threat-model.md` rather than an asset amendment.

## When to revisit

1. **Real relay-reported per-owner usage lands.** Swap the `StorageProvider`
   binding from the mock to relay-metadata + the real `Entitlement` composite
   (`tenshu-publisher.md` §10.2) — by construction this touches no contract and
   no Drive code. At that point add **relay-side quota enforcement** (lifting
   the v1 honour-system, Decision 6) and the **byte-precise folder-share size
   pre-pass** in `ShareFolderIntoRealm` (currently account-gate-only).
   **Cross-device entitlement** (the mock is device-local, so a 2nd device never
   learns of a subscription — and the local-first amendment's personal-realm gate
   deadlocks the joiner's bootstrap pull) is designed in
   [`storage-entitlement-sync.md`](../proposals/storage-entitlement-sync.md): the
   `Entitlement` becomes a synced realm record, the provider derives from it, and
   the gate gains an owner-scoped `joinedFundedRealm` bootstrap path.
2. **Phase 3b — map the Tier-2 typed errors to the gate dialog.** Drive
   surfaces `NoStorageError` / `InsufficientStorageError` as a clean typed
   failure today; re-showing the Phase-2b `StorageGateDialog` on a Tier-2
   race/bypass is a UX nicety (Tier-1 already covers the normal path).
3. **A second storage provider appears.** Decision 4's "one provider" assumption
   (single-value status) is revisited only if a real second `Remote Service`
   exists.
4. **On-device Android verification** of the launcher deep-link, the
   `MainActivity` round-trip, and the Android op-impl gating — the parts that
   could only be desktop-compiled here.

## Alternatives considered

- **(i) Gate on subscription *tier* (Free can't share / paid unlocks).**
  Rejected — the maintainer reframing: the real condition is account-existence
  + capacity, not plan. "Free one or otherwise" still unlocks sharing; tier only
  sizes the quota.
- **(ii) Check-then-act: a `checkCapacity` call before every action.** Rejected
  — a per-action round-trip storm, and it forces the client to obtain a size it
  otherwise wouldn't need (remote files, re-encrypted payloads). The proxy
  already holds the bytes at op-execution time, so the exact check lives *inside*
  the op; `checkCapacity` is reserved for the folder pre-flight where the client
  cheaply sums *local* sizes.
- **(iii) `StorageService` coupled to / inside the Publisher.** Rejected —
  entitlement and provision are different concerns with different owners
  (Publisher/Rei vs the relay). Coupling them would drag a Publisher dependency
  into the file ops and block the relay-backed swap.
- **(iv) `TList<StorageStatus>` multi-provider surface.** Rejected for v1 — one
  Remote Service == one Provider; a list is speculative complexity.
- **(v) Relay-side quota enforcement in v1.** Deferred — there is no real
  per-owner usage tracking yet; the honour-system matches `tenshu-publisher.md`
  §10.1. Decision 6 + *When to revisit* #1 carry the lift.
- **(vi) `checkCapacity` as a public `FilesRepository` method.** Rejected — its
  only consumer is the folder-upload pre-flight, so it stays internal to
  `TenshuFilesRepository` (no speculative public surface). Single-item flows use
  Tier-2.
- **(vii) A manifest `intent-filter` deep-link (custom scheme).** Rejected —
  unnecessary. Control's `MainActivity` is already the exported LAUNCHER, so a
  plain string extra on the existing launcher intent is the smaller contract;
  the precedence rule (Active-only) lives in shared nav code.

## References

- [`docs/proposals/storage-capacity-gating.md`](../proposals/storage-capacity-gating.md)
  — graduated to a redirect stub; the per-tier round-trip analysis + phase plan
  it held are reflected in this ADR + the code.
- `:storage-service/.../{StorageService,StorageStatus,CheckCapacity,CapacityResult,NoStorageError,InsufficientStorageError}.kt`
  — service surface, op, result, typed errors.
- `:storage-service-proxy/.../{StorageProvider,StorageCapacityHolder,StorageProxyInitialization,StorageServiceProxyImplementationFactory}.kt`
  + `impl/CheckCapacityImplementation.kt` — the provision seam, the Tier-2 guard
  (`ensureCapacity`), the single-collection feed, and the Tier-3 op.
- `:control-shared/.../data/MockStorageProvider.kt` + `di/{ProxyModule,PlatformProxyModule}.kt`
  — the v1 mock binding + DI (kept the service Publisher-free).
- `:drive-shared/.../domain/StorageGate.kt`, `ui/share/StorageGateDialog.kt`,
  `data/{StorageSetupLauncher,TenshuFilesRepository,FolderUpload,RepositoryResult}.kt`
  — Tier-1 gate, dialog, the launcher seam, the folder pre-flight.
- `control-shared/.../ui/App.kt` (`resolveStartDestination`) +
  `control/.../MainActivity.kt` + `drive-shared/.../AndroidStorageSetupLauncher.kt`
  — the Services deep-link.
- [`docs/proposals/tenshu-publisher.md`](../proposals/tenshu-publisher.md) — the
  entitlement / Publisher boundary the provision side reads; §10.1 honour-system,
  §10.2 `Entitlement` + `GetCurrentEntitlements`.
- [ADR 0018](0018-file-sharing-mode-a.md) — Mode-A link sharing; a share is a
  storage-consuming action gated here.
