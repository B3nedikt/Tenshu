# Metered-Deferred Sync Status — slice spec

**Status:** v0.2 (2026-07-12). Spun out of
[`core-background-processing.md`](core-background-processing.md) §4.6 + §5.3.1 (Phase
L2-DRAIN, steps 3 + 5). **Sub-slice A SHIPPED.** **Sub-slice B: B1 (`MeteredExempt`
trait + exempt-block routing/drain) + B2 (`DeviceSyncStatus` synced record + publisher) BUILT +
GREEN** — see the As-built section below. **Remaining: B3** (desktop tray peer rendering) + the
publisher's production activation (gated off until B3).

> ⚠⚠ **B1 and B2 are DELETED (2026-10-29, before the production reset):** `MeteredExempt`, the
> `PENDING_EXEMPT_BLOCKS` queue and its drain, `DeviceSyncStatus` and its publisher are gone. Nothing
> read them, and the publisher never ran in production (`sync-metered-plane-split.md` P2). The
> As-built section below is history; B3 has nothing left to render.

> ⚠⚠ **§0's premise is SUPERSEDED (2026-08-01) by
> [`sync-metered-plane-split.md`](sync-metered-plane-split.md).** This doc took the metered
> gate's *placement* as given and built the surfacing on top. The placement is wrong: the gate
> wraps the whole scheduler tick, so it withholds the **kilobyte** metadata plane (and the
> inbound pull, silently) while file **bytes** upload ungated through `emitFileContent`. That
> makes this doc's banner count the realm-wide `PENDING_BLOCKS` queue — every app's writes, not
> Drive files — so it is non-zero even when Drive has uploaded everything, which is exactly how
> the bug was reported. The shipped machinery here (the banner, `CONNECTIVITY_DEFERRED`,
> `forceDrainOnce`) is **reused**, re-keyed onto the content backlog; `MeteredExempt` and the
> exempt queue lose their purpose once nothing is left to bypass (that proposal's P2).

## As-built (sub-slice B, 2026-07-12)

- **`MeteredExempt` trait** (`:core`) — fieldless marker like `HeadlessSafe`. **Routing is
  per-block `hasTrait` at the emit boundary**, NOT the `SchemaProvider.meteredExemptTypeIds()`
  enumeration §3 sketched: `SyncObserver.processSavedData` already resolves the plaintext source
  schema, so it just tests `schema.hasTrait(MeteredExempt.ID)` and threads an `exempt` flag into
  `generateSyncBlock`, which routes to a new `PENDING_EXEMPT_BLOCKS` personal-realm queue. The
  enumeration was dropped (no consumer — the queue is per-block, not a type list).
- **Exempt drain** — `SyncScheduler.drainExemptBlocks()` runs at the top of `tickOnce`, *before*
  the metered-gate branch, so it fires even on a deferred tick. Still gated by `storageGate` +
  entitlement backoff (metered-gate exemption only); an entitlement rejection only *grows* the
  backoff (no reset — the personal drain owns that) to avoid a 402 storm.
- **`DeviceSyncStatus`** (`:sync-service`) — `Syncable(Eager)` + `MeteredExempt`, keyed on
  `deviceId`. Registered in `syncServiceCommonModule` (composite + Syncable trait + DataIdProvider,
  like `RetainedRealmKey`). Fields: `state` / `reason` / `pendingCount` / `updatedAtMillis`.
- **Publisher** — `initDeviceSyncStatusPublisher` observes the `SyncHealthHolder` figures,
  `distinctUntilChangedBy` the state string (NOT pendingCount — avoids a per-drain-tick flood), and
  `saveData`s a `DeviceSyncStatus` on transition. **Gated behind `initSyncService(publishDeviceSyncStatus
  = false)`** — off in tests/legacy (it would otherwise inject exempt blocks that exact-count drain
  tests don't expect) and **not yet flipped on in production DI** (activate with B3).
- **Tests:** `SyncSchedulerGateTest` (+3: exempt drains under closed metered gate, held by closed
  storage gate, drains-while-normal-held); `DeviceSyncStatusSerializationTest` (+2: wire round-trip +
  Syncable/MeteredExempt membership). Full `:sync-service-proxy` suite green; Control Android compiles.
- **No relay/wire-shape change** (House Rule #1 intact): `DeviceSyncStatus` is an ordinary
  E2E-encrypted `SyncBlock`; the exemption changes *when* a block pushes (a few bytes on cellular
  under Wi-Fi-only), not the relay-observable shape. No threat-model amendment required (§6).
- **Deferred:** B3 desktop rendering; the publisher's production activation; a publisher/on-transition
  unit test (deferred to B3's cross-device system test, which exercises it end-to-end); Android smoke
  gate (a pre-merge requirement — this touches the drain path that feeds Control's androidMain).

## 0. Why

On-device 2026-07-11: a folder upload on a phone left a ~770-block backlog that never
reached the paired desktop, and the desktop tray showed **"Sync issue detected."** Root
cause was benign — the realm's **Wi-Fi-only** policy on a 5G connection, so the sync cycle
was gated off (`SyncScheduler.tickOnce():320` — `!syncGate.shouldSync()` short-circuits the
whole tick). The bug is that a *user-configured, benign deferral* was rendered as an
*error*, with no reason and no way out. This slice makes the deferral **legible and
actionable on the phone** and (optionally) **visible on the desktop** — without ever
rendering it as an error.

It does **not** cover the Layer-2 FGS liveness that lets a large drain *finish* in the
background (that's Phase L2-DRAIN steps 1–2) nor the §7.1 heap bound. Deferral surfacing
only.

## 1. Scope — two sub-slices

| | Sub-slice A — **phone banner** | Sub-slice B — **cross-device visibility** |
|---|---|---|
| Surface | Drive (the deferring device) | Desktop tray (the observer) |
| Message | *"Waiting for Wi-Fi — N items"* + *"Sync now over cellular"* | *"{device} is waiting for Wi-Fi"* |
| Mechanism | Extend the **local** `SyncHealthStatus` (Control→Drive) | New synced `DeviceSyncStatus` record carrying the **`MeteredExempt`** trait |
| Needs the trait? | **No** — self-observed, IPC-only, never leaves the device | **Yes** — must sync over the metered-gated push |
| Ship order | **First** (small, high value, stands alone) | Second (additive) |

Sub-slice A alone kills the confusing warning. B is the OneDrive-parity nicety.

---

## 2. Sub-slice A — phone banner (no trait)

### 2.1 Status shape
`SyncHealthStatus` already carries `connectivity` (`online`/`offline`/`needs_attention`),
`isSyncing`, `pendingBlockCount`, `lastSyncedAtMillis`. Add one connectivity value:

```
const val CONNECTIVITY_DEFERRED = "deferred"   // push withheld by policy, backlog waiting
```

`N` for the banner is the **existing** `pendingBlockCount` field — no new field.

### 2.2 Derivation — and the load-bearing `tickOnce` restructure
Today `tickOnce()` returns *before* `reportSyncHealth()` when the gate is closed, so on
metered the status goes **stale** (it can't even say "deferred"). Restructure so a
gate-closed tick still reports health:

```kotlin
internal suspend fun tickOnce() {
    if (!syncManager.isEnabled()) return
    if (!syncGate.shouldSync()) {
        // Gate closed (Wi-Fi-only on metered). Do NOT push/pull, but still report health
        // so the banner can say *why* we're idle instead of looking broken.
        reportSyncHealth()            // sets CONNECTIVITY_DEFERRED when pendingBlockCount > 0
        return
    }
    syncHealthHolder?.markSyncing(); tickOnline = null
    try { drainAndPull() } finally { reportSyncHealth() }
}
```

`reportSyncHealth()` gains a branch: `!syncGate.shouldSync() && pendingBlockCount > 0` →
`CONNECTIVITY_DEFERRED`. Precedence vs the existing derivation (`SyncHealthInitialization`
§49–51): `entitlementBlocked` (needs_attention) > `deferred` > `online` > `offline`. A
gate-closed device with an *empty* backlog is just idle — stays `online`/`offline`, not
`deferred` (nothing is waiting).

### 2.3 Banner + the one-shot override
- Drive's existing sync-health banner (`drive-connectivity-service.md`) renders
  `CONNECTIVITY_DEFERRED` → *"Waiting for Wi-Fi — {pendingBlockCount} items"* with a
  **secondary** (never error-red) treatment, plus:
  - **"Sync now over cellular"** → a **one-shot** `syncGate` override: run exactly one
    `drainAndPull()` despite `shouldSync() == false`, then revert. New seam:
    `SyncScheduler.forceDrainOnce()` (sets a one-shot flag consumed by the next tick +
    `wakeSignal.wake()`). One-shot, not a persistent toggle — the user opted into *this*
    drain, not "always use cellular".
  - A deep link to the Wi-Fi-only setting (persistent change lives there).

### 2.4 What A explicitly does not touch
No trait, no new synced type, no relay traffic — `SyncHealthStatus` is a single-row local
projection consumed over IPC on the same device. Pure Control→Drive.

---

## 3. The `MeteredExempt` trait (the primitive for B)

A schema **marker trait**, following the `RealmData`/`HeadlessSafe` pattern
(`core/src/commonMain/kotlin/org/tenshu/core/trait/`):

```kotlin
// core/src/commonMain/kotlin/org/tenshu/core/trait/MeteredExempt.kt
/**
 * Marks a syncable type whose blocks may be PUSHED even while the SyncGate withholds the
 * cycle (Wi-Fi-only on a metered network). For tiny, latency-sensitive control metadata
 * (device status heartbeats) whose whole point is to be deliverable precisely when normal
 * sync is deferred. Push-only; does NOT exempt the storage/entitlement gates.
 */
interface MeteredExempt : Trait {
    companion object { const val ID = "tenshu/core/trait/meteredExempt"; val Schema = /* trait(ID) */ }
}
```

Enumeration mirrors realm-data (`SchemaProvider`):
```kotlin
fun meteredExemptTypeIds(): List<String> = emptyList()   // overridden per SchemaProvider
```

### 3.1 Why the exemption is decided at *emit* time, not drain time
A queued `SyncBlock` is `encryptedPayload` + a **hashed** `targetEntityIdHash`
(`SyncBlock.kt`) — the drain cannot recover a block's domain type to test the trait (and
must not, HR#1). So the check happens where the plaintext type is still known: the block
**emit boundary** (`SyncObserver`/block emitter). A block whose source type is in
`meteredExemptTypeIds()` is enqueued into a **separate** `pendingExemptBlocks` array rather
than `pendingBlocks`. The drain then partitions by *queue*, never by inspecting ciphertext.

---

## 4. Sub-slice B — cross-device visibility

### 4.1 `DeviceSyncStatus` — the synced record
A small `Syncable` carrying `MeteredExempt`:
```
DeviceSyncStatus : MeteredExempt
  deviceId: String
  state:    "syncing" | "deferred" | "paused" | "idle"
  reason:   optional String   // e.g. "wifi_only_metered"
  pendingCount: Int
  updatedAtMillis: Long
```
Published by each device about **itself**; other realm devices pull it. It is an ordinary
E2E-encrypted block — the relay sees ciphertext (no relay/wire-shape change; §6).

### 4.2 Drain partition + the reduced tick
Extend §2.2's restructure so a gate-closed tick *also* flushes the exempt queue:
```kotlin
if (!syncGate.shouldSync()) {
    drainExemptBlocks()     // pushes ONLY pendingExemptBlocks (DeviceSyncStatus heartbeat)
    reportSyncHealth()
    return
}
```
`drainExemptBlocks()` reuses the ordinary push path (`pushBlock`) for exempt blocks only.
`storageGate`/entitlement gating still applies (an unsubscribed device pushes nothing, even
exempt) — the exemption is **only** from the `syncGate` (metered) axis.

### 4.3 Publish cadence — minimise cellular pushes
Publish `DeviceSyncStatus` **on state transition only** (idle→deferred, deferred→syncing,
etc.), coalesced on the singleton key, **not** periodically. One or two tiny pushes per
deferral episode, not a heartbeat stream.

### 4.4 Desktop rendering
Desktop reads peers' `DeviceSyncStatus` → tray *"{device} is waiting for Wi-Fi"* (tier-2,
no action — it can't toggle the phone's Wi-Fi). Absent a fresh record, fall back to the
inference wording *"Waiting for {device}"* from tip staleness (§5.3.1). Never red.

---

## 5. Decisions to confirm before coding

- **D1 — ship A without B?** Recommend **yes**: A stands alone, needs no trait, and removes
  the false warning immediately.
- **D2 — `deferred` as a `connectivity` value vs a separate reason field?** Recommend the
  connectivity value + reuse `pendingBlockCount` for N (smallest change).
- **D3 — override granularity.** Recommend **one-shot** `forceDrainOnce()`, not a persistent
  "always cellular" toggle (that already lives in the Wi-Fi-only setting).
- **D4 — trait exempts which gates?** Recommend **only** `syncGate` (metered), never
  `storageGate`/entitlement — an unfunded device stays local-first even for status.
- **D5 — separate `pendingExemptBlocks` queue vs a local flag on the block.** Recommend the
  **separate queue** (clean, given the encrypted-block constraint of §3.1).
- **D6 — trait name.** `MeteredExempt` (alt: `SyncGateExempt`, `UngatedPush`).

## 6. Threat model

`DeviceSyncStatus` is an ordinary E2E-encrypted `SyncBlock`: the relay sees ciphertext, no
new relay-visible shape, no wire change. It exposes coarse device-activity state **only to
the user's own realm devices** (already mutually trusted). The `MeteredExempt` exemption
lets a few bytes ride cellular under a Wi-Fi-only policy — a deliberate, user-legible
trade (the point of the trait), negligible data. Expected verdict: **no threat-model
amendment for A; a one-line note for B** confirming no relay-observable delta. Confirm at
implementation per House Rule #2.

## 7. Tests

- **A:** `SyncHealthStatus` derivation → `CONNECTIVITY_DEFERRED` iff gate-closed *and*
  `pendingBlockCount > 0` (and *not* when backlog empty); `tickOnce` reports health on the
  gate-closed path (previously silent); `forceDrainOnce()` runs one `drainAndPull` despite a
  closed gate then reverts.
- **Trait:** `meteredExemptTypeIds()` includes `DeviceSyncStatus`; emit routes an
  exempt-typed block to `pendingExemptBlocks` and a normal one to `pendingBlocks`.
- **B / drain partition:** gate-closed tick pushes `pendingExemptBlocks` and leaves
  `pendingBlocks` queued; `storageGate` closed → even exempt blocks hold.
- **System (desktop-verifiable):** metered phone with backlog → `DeviceSyncStatus` reaches
  a peer, the bulk backlog does not; override drains the backlog.

## 8. Out of scope

- Layer-2 FGS liveness so a large drain *finishes* backgrounded (Phase L2-DRAIN steps 1–2).
- Per-upload progress "syncing 320/770" (shape (a); after streaming `UploadFile`, §7.1).
- The `.toList()` heap bound / block-size cap (§4.4 / §7.1).
- Reworking `DIVERGENCE` copy — trivial, folded into whichever slice touches the tray.
