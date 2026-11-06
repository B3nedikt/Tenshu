# Sync cursor / object-store lifecycle — desync self-heal (C) and the durable fix (A)

**Status: Option C shipped; Option A1 implemented — the cursor now lives in the
object store, so the desync is structurally impossible and C is retired to a redundant no-op net.**

## The bug

Under `ENTITLEMENT_ENFORCE=true`, a paired **iPhone** (route-1 Drive) exhibited persistent
one-way sync: inbound reads worked, but every `/sync/push` was rejected `402
{"error":"entitlement_required"}` and no pre-existing host data ever appeared. Root-caused
on-device 2026-07-18 (full trace in the PR description / session notes):

- The device's pull cursor (`SyncManager.lastSyncTimestamp`) sat at a **non-zero** value over an
  **empty object store**. A `GET /sync/changes?since=<cursor>` therefore returns nothing, so the
  owner's `Entitlement` **and every pre-join block** are skipped **forever** — no entitlement →
  no proof → `entitlement_required` → outbound blocked; no files → nothing inbound either.
- Forcing `since=0` re-pulled the backlog with keys present: the entitlement materialised
  (`entitlementPresent=true` → a proof was built) and the pending blocks pushed `200`. The block
  content, keys and crypto were all fine — the cursor was simply **ahead of the applied data**.

### Why the cursor and the data desynced

The two pieces of state live on **different persistence lifecycles**, and on iOS they can be
cleared independently:

| State | Where it lives | iOS lifecycle |
|---|---|---|
| Sync cursor (`lastSyncTimestamp`, `realmRelayRegistered`, …) | `SyncManager.storageDelegate` (Keychain-backed on iOS) | **Survives** app uninstall/reinstall; `-wipeDeviceState`/"forget device" runs through *Control's* delegate, not Drive's |
| Applied data (entitlement, files, devices) | the object store (`FileObjectStore`, a file in the app container) | **Wiped** on uninstall/reinstall |

So a reinstall (or a wipe that clears one delegate but not the store) leaves the device
"believing" it has synced up to cursor *N* while the store that should hold those *N* blocks is
empty. On **Android/desktop this never happens**: the cursor (in `storageDelegate`) and the
object store share the app data directory and are wiped together. It is an iOS-only manifestation
of a cross-platform latent hazard: *nothing enforces that the cursor never claims more than the
store has applied.*

## Invariant

> **The pull cursor must never claim more than the object store has applied.**

Every option below is a way to enforce (or restore) that invariant.

---

## Option C — self-heal at init (IMPLEMENTED)

`reconcileStaleSyncCursor(syncManager, database)` runs at the **top of `initSyncService`**, before
the scheduler starts:

```kotlin
if (syncManager.lastSyncTimestamp > 0L && database.isEmpty()) {
    syncManager.lastSyncTimestamp = 0L   // next pull back-fills from 0
}
```

`Database.isEmpty()` delegates to `ObjectStore.isEmpty()` (the cache-aside stores answer from
their resident map, hydrated from disk on open).

**Pros**
- **Cause-agnostic**: it checks the *actual* desynced state, so it heals every vector — uninstall,
  reinstall, `-wipeDeviceState`, a future partial wipe — without enumerating them.
- **Zero migration, zero wire/threat-model change**; inert on Android/desktop (they never desync).
- Re-applies are idempotent, so a spurious reset only costs one extra back-fill.

**Cons / limits**
- **Ordering-fragile**: it only fires if the store is still empty *when it runs*. It is placed
  before the scheduler (whose health-status writes would otherwise repopulate the store), but it
  relies on no other component writing a local row into the store before `initSyncService`. Today
  nothing does (client registration doesn't persist; app-registration/device rows arrive via
  sync), but that is a standing invariant a future change could break silently.
- **Coarse signal**: "store totally empty" only catches a *full* wipe. It would not catch a
  *partial* desync (cursor ahead of a store that still holds some rows) — though no such case is
  known today, because a running tick always applies its whole batch (there is no keys-race:
  `currentRealmContext()?.derivedKeys` is null iff `!isEnabled()`, and the tick is gated on
  `isEnabled()`), so the store is all-or-nothing.
- It is a **safety net, not a structural guarantee** — the cursor can still momentarily exceed the
  store between a crash mid-batch and the next boot.

C is the right *first* fix: it stops the data loss immediately and covers every cause. But its
fragility is exactly why a durable fix should follow.

---

## Option A — make the desync structurally impossible

Two shapes were considered. The question the maintainer posed: **move the cursor into the store
vs. reset it on wipe — which, and why?**

### A1 — co-locate the cursor with the data (RECOMMENDED — IMPLEMENTED)

**As built:** the cursor is persisted as one reserved, self-overwriting row in the object store
(`ObjectStore.loadSyncCursor()`/`storeSyncCursor()`, keyed by a sentinel hash, implemented once via
`getObject`/`storeObject` so every store gets it free). `SyncManagerImpl.lastSyncTimestamp`
delegates to it instead of the Keychain-backed `storageDelegate`; the three `PlatformProxyModule`s
inject the same `ObjectStore` the `Database` is built over. A store wipe now drops the cursor with
the data → `lastSyncTimestamp` reads 0 → back-fill from 0. Option C's guard stays but can no longer
fire usefully (a store holding the cursor row reads non-empty), so it is a documented no-op net,
removable once A1 has shipped to all clients. On-device verification is unnecessary here because the
desync is now unrepresentable by construction — proven by `SyncCursorStoreDesyncSystemTest`, which
clears the store and asserts the cursor resets with it.

⚠⚠ **The web was missed, and that regressed it** (found 2026-10-17). `JsRuntime` builds its own
`SyncManagerImpl` outside the three `PlatformProxyModule`s and did not pass `objectStore` /
`storeLock`, so on the web the cursors (forward, tombstone, backfill floor + target, first-pull
marker) went to the constructor's throwaway `InMemoryObjectStore`. Before A1 the web's cursor lived
in `IndexedDbSecureStorageDelegate` and survived a reload; after A1 it reset on **every page load**:
a full re-pull from 0, a fresh backfill walk and a catch-up raise, over an IndexedDB store that still
held every row — the very cursor/store split A1 makes unrepresentable, just in the benign direction
(cursor behind the data, so nothing was skipped, only re-done). `ForgetThisDevice`'s
`wipeLocalStore` likewise "dropped" the throwaway store and reported success (`Session.clear`'s
`purgeRealmStorage` cleared the real one afterwards, which hid it). Fixed by passing the runtime's
`IndexedDbObjectStore` + its lock; safe because that store is fully hydrated before the lazy
`syncManager` can run, so the cursor reads are cache hits, never read-through
(`bounded-store-cache.md`'s JS fork). Pinned by `:tenshu-sdk-js` `JsSyncCursorDurabilityTest`.

- With the real store wired, `ForgetThisDevice`'s `wipeLocalStore` now really wipes the IndexedDB
  rows (and reports so), rather than a throwaway store's.
- ⚠ The cursor-after-data ordering A1 relies on holds **within one IndexedDB database** only:
  `tenshu-sdk`'s puts commit in creation order, but file bytes live in `tenshu-sdk-files` and are
  fire-and-forget, so a cursor can be durable while the bytes of a block it covers are not. Low
  impact — bytes are on-demand and re-fetched — but it is not the same guarantee.
- ⚠⚠ **A durable web cursor needs one runtime per origin.** Each tab's runtime has its own
  in-memory copy of the store and IndexedDB is last-write-wins, so a lagging tab could leave an
  older row behind a newer cursor and no reload would re-pull it (the re-pull-from-0 used to heal
  this). Hence `RuntimeLease` — one exclusive Web Lock, taken before any store opens; a browser
  without Web Locks resets the cursors at boot instead. `docs/web/architecture.md` "Cross-tab".



Move `lastSyncTimestamp` (and the sibling latches `realmRelayRegistered` / `joinedFundedRealm`)
out of `storageDelegate` and into the **object store** — the same durable store that holds the
applied blocks.

**Why it's the real fix.** The bug is a *lifecycle mismatch* between two stores. If the cursor is
a row *in* the data store, there is exactly one lifecycle: wipe the container and the cursor goes
with the data; keep the container and both persist. **The desynced state becomes unrepresentable**
— you cannot have "cursor = N, store empty," because clearing the store clears the cursor. C's
guard would then be dead code (correct-by-construction), not a runtime patch.

Additional upside available (not required): today `SyncScheduler` sets `lastSyncTimestamp` *after*
processing a batch, in a separate write from the block applies. If the cursor is a store row, it
can be written **in the same `Database.transaction` as the applies**, so a crash mid-batch can
never leave the cursor ahead of the data even *within* a session — a strictly stronger invariant
than C restores.

**Costs / care**
- It relocates a **load-bearing** value. Pre-release we can just change where it's read/written
  (no migration data to preserve — a bump of `TENSHU_STORAGE_FORMAT_VERSION` resets stores
  anyway), but every reader of `lastSyncTimestamp` must move in lockstep (there are few:
  `SyncScheduler` read + advance, and the init reconcile which then disappears).
- The cursor is read on **every** tick; reading it from the cache-aside store is O(1) (resident
  map), so no perf concern. It must be readable **before** the first pull — it is, the store is
  open by `initSyncService`.
- The cursor is non-secret; moving it from the Keychain/plaintext delegate into the object store
  (which holds encrypted *values* but plaintext *framing*) is fine — it was never confidential.
- Minor: `realmRelayRegistered`/`joinedFundedRealm` are read in a few non-sync places
  (`SyncScheduler`, storage gate); they'd move too, or stay in `storageDelegate` if we accept the
  cursor being the only relocated field (the cursor is the one that actually causes data loss).

### A2 — reset the cursor on every wipe path (NOT recommended alone)

Hook `-wipeDeviceState` / "forget device" / Drive `deprovision()` to also clear Drive's cursor.

**Why it's insufficient.** The single most common desync vector — **app uninstall/reinstall** — is
**not a hookable code path**: the OS wipes the container while the app isn't running, and the
Keychain-backed cursor survives regardless. A2 can only cover *in-app* wipes, so it would leave
the reinstall case (the one actually hit in testing) broken. It also spreads the invariant across
every teardown path (easy to miss a new one) instead of centralising it. A2 only makes sense as a
*tidiness* companion to C, never as the structural fix.

### Comparison

| | C (self-heal) | A1 (cursor in store) | A2 (reset on wipe) |
|---|---|---|---|
| Desync possible? | Yes, healed after the fact | **No — unrepresentable** | Yes (uninstall unhooked) |
| Covers uninstall/reinstall | ✅ | ✅ | ❌ |
| Covers in-app wipe | ✅ | ✅ | ✅ |
| Crash mid-batch (cursor > data within a session) | ❌ (heals next boot only if store empty) | ✅ (single-txn advance) | ❌ |
| Ordering-fragile | ⚠️ must precede first write | No | No |
| Migration / format risk | None | Small (relocate one field, pre-release) | None |
| Cross-platform | Inert off-iOS | Neutral off-iOS | Neutral off-iOS |

**Recommendation: ship C now (done) as the immediate, cause-agnostic stop-loss; follow with A1**
(move the cursor into the object store) as the durable structural fix that makes the desync
impossible and retires C's fragility. A1 matches the maintainer's intuition ("just save it"). A2
is not worth building on its own.

## Test

`SyncCursorStoreDesyncSystemTest` (`:system-test-utils`, JVM-run, platform-agnostic logic): a host
creates a folder+file, a joiner converges, then the joiner's `InMemoryObjectStore` is `clear()`-ed
while its cursor is left intact — modelling the iOS container wipe. It asserts the desynced state
(store empty, cursor > 0, folder gone), that `reconcileStaleSyncCursor` resets the cursor, that the
joiner then back-fills the folder+file from 0, and that a second reconcile over the now-populated
store is a no-op (no needless re-pull). The wipe is only *reachable* on iOS's split lifecycle, but
the guard and the test are cross-platform.
