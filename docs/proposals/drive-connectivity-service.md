# Connectivity / sync-health signal — Control → Drive

- **Status:** **IMPLEMENTED 2026-07-07** (global banner states; per-row upload
  badge §4 still deferred). The `SyncHealthStatus` relation + `syncHealth()`
  client read + `Offline` / `Syncing` banner states shipped by extending
  `:sync-service` (decision §3 option **b**). Was: sketch, surfaced by the
  local-first sync-status banner ([ADR 0019](../adr/0019-storage-capacity-gating.md)
  amendment, Phase 4), which shipped only the `LocalOnly` state.
- **Date:** 2026-06-06 (sketch) · 2026-07-07 (implemented)
- **Owner:** Benedikt
- **Related:** [ADR 0019](../adr/0019-storage-capacity-gating.md) (local-first
  reframe) · [`sync/design.md`](../sync/design.md) §2.4/§4 · `:storage-service`
  (the reactive-status pattern this copies) · `bgproc-scheduling-roles.md`
  (`SyncGate` / `NetworkStateProvider`)

> **As-built (2026-07-07).** Followed the §2 shape verbatim (copy the
> `StorageStatus` seam), with these resolved decisions:
> - **§3 module placement → (b): extended `:sync-service`.** `SyncHealthStatus`
>   composite (`:sync-service`) + `SyncService.syncHealthStatuses` single-row
>   relation + `syncHealth()` client extension (`:sync-service-client`); the
>   `SyncHealthHolder` + `initSyncHealth` derivation live in `:sync-service-proxy`.
>   No new module set, no DI wiring change — `syncServiceCommonModule` /
>   `syncServiceClientModule` are already registered, and the holder is constructed
>   *inside* `initSyncService` (so every platform gets it, no per-platform edit).
> - **§6 Q2 connectivity → relay-call outcome, not link state.** `connectivity`
>   is derived from the `GetChanges` pull result in `SyncScheduler.pullChanges`
>   (`online` after a round-trip, `offline` after a throw, unchanged when no relay
>   call was attempted). This is the more accurate "relay reachability" signal and
>   keeps the producer inside `:sync-service-proxy` — `NetworkStateProvider` (which
>   lives in `:control-shared-logic`) is deliberately **not** a dependency.
> - **§6 Q3 flicker → the banner keys `Syncing` on `pendingBlockCount > 0`, not
>   the raw `isSyncing` flag** (which flips true for the brief moment of each hot
>   tick). `isSyncing` is still carried on the model for a possible future inline
>   spinner. No VM debounce needed.
> - Banner priority: `LocalOnly` > `Offline` > `Syncing` > none.
>
> Tests: `SyncHealthReportingTest` + `SyncHealthRelationLocalTest`
> (`:sync-service-proxy`), `SyncBannerTest` (`:drive-shared-ui`). Android/iOS
> on-device pass pending. §4 per-row upload badge remains out of scope.

> **Amendment (2026-07-21) — the `Locked` state.** Found on a real device: Drive
> showed *"Local only — files on this device aren't backed up or synced. Set up
> storage…"* while Control was merely **locked**. Two independent defects behind
> one symptom:
>
> 1. **The states were indistinguishable.** `SyncManagerImpl.lock()` nulls
>    `currentOwnerId`; `initEntitlementStorageReader` then reads no `Entitlement`
>    and `entitlementToFigures(null, …)` yields `hasStorage = false` — the *exact*
>    input `LocalOnly` keys on. A sealed realm was therefore rendered as an
>    unsubscribed one, telling the user to **buy storage they already own** when
>    the only fix was to unlock. Closed by a new `SyncBannerState.Locked`,
>    evaluated **first** — ahead of `LocalOnly` *and* ahead of the
>    `storage == null` optimism guard, since a locked proxy never produces figures.
> 2. **The health row went stale while locked.** `SyncScheduler.tickOnce` returned
>    on `!syncManager.isEnabled()` *before* `reportSyncHealth()`, freezing the row
>    at its last pre-lock value — the identical bug already fixed once for the
>    metered branch. It now reports before returning.
>    `getUnlockStatus().isLocked` separates the two `!isEnabled()` cases (sealed
>    realm vs never-onboarded), so a fresh device does not claim to be locked.
>
> Carried as a **new `connectivity` value** (`CONNECTIVITY_LOCKED`), not a new
> field — same reasoning as `CONNECTIVITY_NEEDS_ATTENTION`/`_DEFERRED`: the
> positional composite is unchanged, **no `TENSHU_STORAGE_FORMAT_VERSION` bump is
> owed** (HR #9; smoke gate confirmed schema v8 on both sides), and a
> version-skewed Drive degrades to its "Offline" banner rather than crashing.
>
> Banner priority is now: `Locked` > `LocalOnly` > `NeedsAttention` > `Deferred` >
> `Offline` > `Syncing` > none. The CTA reuses `StorageSetupLauncher` — its Android
> implementation is exactly "launch Control", which is where the unlock screen
> lives. ⚠ The desktop implementation is a v1 no-op, so the button is inert there
> and the banner *text* carries the instruction (a limitation `LocalOnly` already
> had; fixing both is a separate slice). Tests: four `locked*` cases in
> `SyncBannerTest`. **On-device VERIFIED** (real ColorOS device, 2026-07-21): cold-start
> with the hard `UNLOCK_WRAPPER` present → Control seals → Drive's Files tab renders the
> lock-icon banner with the new copy (replacing "Local only — Set up storage"), and the
> **[Unlock]** CTA foregrounds Control's `LockedScreen`.
>
> ⚠ **Follow-up found by that same on-device pass — the startup race.** The health row is
> *persisted*, so a sealed cold start inherited the previous (unlocked) process's
> `connectivity = online` until the first scheduler tick, while `initEntitlementStorageReader`
> seeded `hasStorage = false` **synchronously**. In that gap Drive read "not subscribed, not
> locked" and rendered `LocalOnly` again — observed on ColorOS, which kills Control constantly,
> so the window recurs on every relaunch. Closed by `SyncHealthHolder.seedLocked(…)`, called
> from `initSyncService` with `syncManager.getUnlockStatus().isLocked` **before** the derivation's
> first emission. Tests: `seededLocked_publishesLockedOnTheVeryFirstEmission` +
> `seedLocked_falseLeavesTheOptimisticOnlineSeed` (`SyncHealthRelationLocalTest`).

## 1. Problem

The Phase-4 sync-status banner ([ADR 0019](../adr/0019-storage-capacity-gating.md))
can only render `SyncBannerState.LocalOnly`, because that is the *only*
sync-relevant signal the Drive UI can observe. Drive is an **IPC client of
Control's proxy** and runs no proxy of its own; everything else the banner wants
lives Control-side and never crosses the boundary:

| Signal the banner/badge wants | Where it lives today | Reachable from Drive? |
|---|---|---|
| Has storage subscription (`LocalOnly`) | `StorageStatus` relation (reactive) | ✅ yes — already consumed |
| Relay reachable / **offline** | proxy relay client + `NetworkStateProvider` | ❌ no |
| **Syncing** (drain/pull in flight) | `SyncScheduler.tickOnce()` | ❌ no |
| Pending-upload backlog | `SyncService.Collections.PENDING_BLOCKS` (proxy relation) | ❌ no |
| Last successful sync time | `SyncManager.lastSyncTimestamp` (proxy) | ❌ no |

Faking "offline" from Drive's *own* network state is wrong: Drive's connectivity
≠ Control's relay reachability (and on desktop the relay may be a remote host
while Drive talks to Control over a local socket). The signal must be **derived in
Control and pushed to Drive**.

## 2. Shape — copy the `StorageStatus` seam

`StorageStatus` already solved the isomorphic problem (a locally-derived,
**non-`Syncable`** status that Drive observes reactively over IPC). Reuse it
verbatim:

- A single-row composite — working name **`SyncHealthStatus`** — exposed as a
  one-row `TList<SyncHealthStatus>` relation projected to `Maybe<…>` at the client
  (same shape as `StorageService.statuses` → `status()`), **locally derived, not
  `Syncable`** (it never leaves the device; House Rule #1 untouched).

  Proposed fields (all plain value props):
  - `connectivity: TString` — `online` | `offline` (relay reachable last attempt).
  - `isSyncing: TBoolean` — a drain/pull is in flight this tick.
  - `pendingBlockCount: TInteger` — size of `PENDING_BLOCKS` (the personal realm's
    outbound backlog).
  - `lastSyncedAtMillis: TInteger` — wall-clock of the last successful pull.

- **Producer (proxy):** a `SyncHealthHolder` (mirrors `StorageCapacityHolder`)
  updated by `SyncScheduler` at tick boundaries (set `isSyncing` around the
  drain/pull; record relay-call success/failure → `connectivity`; read the
  `PENDING_BLOCKS` count) and by the existing `NetworkStateProvider` (the same one
  `ConstraintSyncGate` already consumes). An `initSyncHealth(scope, holder)` derives
  the single-row relation on emission — structurally identical to
  `initStorageService`.

- **Consumer (Drive):** `client.<service>.syncHealth(): Flow<SyncHealthStatusModel?>`,
  mapped in `TenshuFilesRepository`, collected in `FilesScreenViewModel`. Then
  `SyncBanner.evaluate(storage, health)` grows two cases — `Offline`, `Syncing` —
  and the existing `SyncBannerState` sealed hierarchy absorbs them with no call-site
  change (the Phase-4 banner was built for exactly this extension).

## 3. Module placement — open question

Two options; **recommend (b)** unless a broader "connectivity" surface is foreseen:

- **(a) A new `:connectivity-service` module set** (`+ -common/-client/-proxy/-test-utils`),
  symmetric with `:storage-service`. Cleanest separation; matches the maintainer's
  "ConnectivityService" framing. Cost: a 5-module set for one small status.
- **(b) Extend `:sync-service`** with the `SyncHealthStatus` relation + `syncHealth()`
  client extension. It *is* sync-domain state, the producer already lives in
  `:sync-service-proxy` (`SyncScheduler`), and it avoids standing up a module set
  for a single reactive row. Cost: `:sync-service` grows a (small) client-facing
  read surface it didn't have.

## 4. Per-row upload badge — separately hard, probably out of scope

The transient *per-file* "queued to upload" badge (the upload-axis counterpart to
the existing download-axis `offlineRowGlyph`) is **not** unlocked by this signal
alone. `PENDING_BLOCKS` is a queue of opaque sync blocks, not a per-file status,
and Drive has no block→file mapping. A true per-row badge needs either a per-file
sync-state projection (file-service-side) or a heuristic ("has local bytes +
global backlog non-empty"), which is noisy. **Recommendation:** ship the global
banner states first; treat per-row upload state as its own slice if it's still
wanted after the banner lands.

## 5. Threat-model note

The signal is **operational metadata, locally derived, non-`Syncable`**, crossing
only the already-authenticated Drive↔Control IPC channel (Binder on Android;
peer-cred-gated UDS on desktop, A14 / T-C-11). It exposes no plaintext user
content and adds **no relay surface** — House Rule #1 and assets A1–A4 / A9 are
untouched. The one consideration: it tells a connected client whether sync is
active and how big the backlog is; since which apps may connect is already
governed by app registration (A18 / T-C-14) and the signal is non-sensitive
operational state, this is expected to be **no new threat row** — to be confirmed
when the channel is built (per House Rule #2, the amendment lands with the code).

**Confirmed 2026-07-07 (as-built): no threat-model amendment.** `SyncHealthStatus`
is a non-`Syncable` composite — it is never serialised into a `SyncBlock`, never
enters `PENDING_BLOCKS`, and never reaches the relay (House Rule #1 / assets
A1–A4 / A9 untouched; the relay's data shape and the sync protocol are unchanged).
It crosses only the already-authenticated Drive↔Control IPC channel (A14 / A18).

## 6. Open questions

1. Module vs extension (§3) — maintainer's call; leaning (b).
2. **Connectivity derivation.** Desktop currently *assumes unmetered/online*
   (`DesktopNetworkStateProvider`); real relay-reachability needs the relay client
   to report last-call outcome, not just link state. Define `offline` precisely
   (link down? last relay call failed? both?).
3. Debounce / cadence — `isSyncing` flips every tick; the banner must not flicker.
   A min-visible-duration or a coalescing window, decided at the VM layer.
4. Per-row upload badge — own slice or dropped (§4).
5. Shared realms — does the signal aggregate personal + shared realm health, or
   personal only? (The Phase-1 gate already treats them separately.)
