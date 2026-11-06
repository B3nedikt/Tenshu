# HLC tip identity — one clock per *process*, one slot per *device*

**Status:** v0.5 (2026-09-07). **TC-1 + TC-2a + TC-2b BUILT** (as-built in §6); TC-3 is design.
⭐ **TC-2b is now DEVICE-VERIFIED** — the two-app pass ran on hardware 2026-09-07 and passed with
three runtimes; measurement in §6b. The outstanding item in this header is closed.
Written from a live diagnosis of the desktop tray's "Sync issue detected", reproduced and then
cleared on demand — see §0. TC-2 is the real fix and owes a threat-model amendment
(House Rule #2).

Owns the `DIVERGENCE` reword that
[`sync-metered-deferred-status.md`](sync-metered-deferred-status.md) §"Reworking `DIVERGENCE`
copy" and [`core-background-processing.md`](core-background-processing.md) §5.3.1 both defer.
The detector itself belongs to [`sync-backend-relay-plan.md`](sync-backend-relay-plan.md) §5.1;
this doc is the defect report against that design, not a replacement for it.

---

## 0. What was observed

Control desktop's tray sat on **"Sync issue detected"** for ~50 minutes while sync was, by every
other measure, perfectly healthy. Read out of the live JVM (`jcmd GC.heap_dump` — the state is
in-memory only and is never logged or exposed on the bridge):

```
SyncHealthFigures     online=1  isSyncing=0  pendingBlockCount=0
                      lastSyncedAtMillis = 15:57:04   (≈1 min before the probe)

DivergenceWarning.Regression
  peerDeviceId            = e4a23211-…            → Device entity name: "iPhone"
  previousHlcPhysicalTime = 15:07:19  (2026-08-28)
  currentHlcPhysicalTime  = 14:51:19  (2026-08-28)   ← 16 minutes backwards

HlcDivergenceTracker.highestSeenByDevice — 10 peers; the iPhone floor pinned at 15:07:19
while the Android phone advanced normally to 15:53:16.
```

`syncStateCode` (`control-shared/…/bridge/ControlBridge.kt:43`) ranks `DIVERGENCE` above
`OFFLINE`/`SYNCING`/`SYNCED`, so one latched warning outranks every state that actually described
the device. The tray string is `control-desktop/macos/TrayAgent/main.swift:361`.

**Prediction and confirmation.** The §1 mechanism predicts the warning clears the moment *any*
iPhone runtime that has ingested something newer than 15:07:19 polls the relay. Opening Drive on
the iPhone cleared the tray immediately. That is the mechanism, not a coincidence: the auto-clear
fires only on a strictly-advancing observation past the pre-regression floor.

---

## 1. Mechanism

Three facts, each individually reasonable, compose into a guaranteed false positive.

1. **The tip is a process's clock.** `SyncScheduler.buildSignedTip()` publishes
   `syncManager.hlcManager.current()`. `HLCManager.state` starts at `(0, 0)` on every process
   start, is never seeded from the store, and advances **only** on a local write (`tick()`) or an
   ingested block (`update()`) — `core-common/…/util/HybridLogicalClockUtil.kt:95-163`. So the
   tip means *"the highest HLC **this process** has seen since it launched"*.

2. **The relay's slot is a device's.** `HlcTipRegistry` keys `(ownerId, deviceId) → tip` and
   `record` overwrites unconditionally —
   `sync-relay-server/…/storage/SqliteHlcTipStore.kt:18-33`, *"the relay always serves whatever
   the device last claimed — regression detection is client-side"*. There is no monotonicity
   check on the write path (`routing/SyncRouting.kt:175`), by design.

3. **The tracker's floor is a device's.** `HlcDivergenceTracker.highestSeenByDevice` is keyed by
   `deviceId` and warns on any backward step.

A device that runs **more than one runtime under one `deviceId`** therefore publishes a
non-monotone sequence into a single slot. Runtime A (ingesting, at 15:07) writes the slot;
runtime B (idle since 14:51) overwrites it with its own older clock; the desktop reads a
16-minute rewind and calls it a forked view.

⚠⚠ **The signal is not recoverable by tuning the detector.** "Relay replayed an old signed tip"
and "a second runtime republished its own older tip" are the *same bytes*. Any client-side
tolerance that suppresses the false positive suppresses T-T-04 detection with it. **The fix has
to make one device publish one monotone sequence.**

### Why it stays red

The regression path deliberately does **not** lower the floor, and the recovery auto-clear fires
only when that peer advances *past* the pre-regression high-water mark. A lagging runtime that
keeps republishing values below the floor holds the tray red indefinitely — here, ~50 minutes,
and only user action ended it.

---

## 2. Who is exposed

The trigger is **N independent `HLCManager`s under one `deviceId`**, not disconnection.

| Platform | Exposed? | Why |
|---|---|---|
| **iOS (route 1)** | ⚠⚠ **Yes — architecturally** | Every first-party app runs its own in-process runtime and reads the same `deviceId` out of the shared Keychain access group (`core-common/…/epc/RealmKeyMaterialBridge.kt:103`, `tenshu-sdk-ios/…/FirstPartyLocalRuntime.kt:127`). Drive is explicitly the background syncer while Control also writes (`DriveLocalRuntime.kt:338-348`), so two clocks under one id is the *normal* state. This is the observed case. |
| **Web** | ⚠⚠ **Yes — and more easily** | The device ID is persisted in IndexedDB (`tenshu-sdk-js/…/JsRuntime.kt:374-381`, *"so the device ID survives reload"*), and IndexedDB is **per origin** — every tab shares it. Each tab builds its own `JsRuntime` → own `SyncManagerImpl` → own `HLCManager`, and there is **no leader election or cross-tab propagation** (`IndexedDbObjectStore.kt:43` puts it explicitly out of scope). **Two tabs of web Drive reproduce this exactly.** Users open tabs far more casually than they run five iOS apps. |
| **Desktop** | No | Control is one process with one `SyncManagerImpl`. ⚠ Note it currently runs **two** `SyncScheduler`s (§9, unrelated defect) — harmless here only because both share the one `HLCManager`. |
| **Android** | No | Control is the single proxy process; every app is a client of it. |

### "Can a client that was merely disconnected for a while cause it?"

**No — but it is not harmless either.** A restarted single-runtime client publishes `(0, 0)` as
its first tip, because `HLCManager` is zero-initialised and nothing seeds it from the store. The
empty-tip guard skips zero tips, so no warning fires; the next `update()`/`tick()`
floors the clock at `now`, which is ≥ anything previously published. A long-absent client
therefore goes *zero → fresh*, never *newer → older*.

What it does instead is **C3** below: it silently zeroes that device's slot on the relay.

---

## 3. Consequences

- **C1 — False `DIVERGENCE`.** Confirmed live. Fires on any multi-runtime device; on iOS and web
  that is the ordinary configuration, so the T-T-04 detector is structurally guaranteed to cry
  wolf. A detector that cries wolf on a normal Tuesday is one users learn to ignore, which is the
  actual security cost here.

- **C2 — The warning is effectively sticky.** Ranked above every other state, cleared only by a
  strictly-advancing tip from the same peer, in-memory so it also survives nothing. In practice it
  ends when the user opens an app on the lagging device — i.e. by accident.

- **C3 — The tip is also the tombstone-GC watermark, and it gets zeroed.** `MailboxStore.kt:155-160`
  drops a tombstone's body only once **every** device's tip is `>= targetHlc`. The relay records
  the `(0,0)` restart tip unconditionally, so every client restart resets that device's watermark
  until it ingests or writes something. An idle-but-online client — one that pulls nothing and
  writes nothing — never calls `update()` or `tick()` at all, so it can sit at `0`
  **indefinitely**, holding tombstone-body GC for the whole realm. This is silent: no warning, no
  surface, and it is the half of the problem that *is* caused by clients being away.

---

## 4. Why the obvious fix is wrong

The one-line fix is to floor `current()` at wall clock: `max(state.physicalTime, now)`. There are
only two callers (`SyncScheduler.kt:1917`, `SyncProcessor.kt:152`), both the tip, so it is
contained — and it does kill C1 and C2 outright, since every runtime on a device would then
publish ≈`now`.

⚠⚠ **Reject it as a standalone change.** The tip is doing two jobs at once, and this fixes one by
breaking the other: a device that has ingested *nothing* would claim it is caught up to `now`, and
the relay would GC tombstone bodies that device never applied (C3, in the dangerous direction).
T-T-08's defence — *"a device that has observed HLCs past the tombstone's HLC, but not the
tombstone itself, fires a consistency-warning"* — depends on the tip being an honest statement
about what was **observed**, not about what time it is.

---

## 5. Options

| # | Option | Verdict |
|---|---|---|
| O1 | Floor `current()` at wall clock | ❌ §4 — trades C1/C2 for a worse C3. |
| O2 | Split the wire: publish a *clock tip* (detector) and an *applied watermark* (GC) as two numbers | Honest, but reshapes `SignedHlcTip` → `RELAY_PROTOCOL_VERSION` bump + relay redeploy + threat-model amendment. Hold as the fallback if O3 proves unimplementable. |
| O3 | **One clock per device**: persist the HLC high-water mark in the device's store, build the tip from `max(persisted, in-memory)`, write back the max | ✅ **Recommended.** Fixes C1, C2 **and** C3 with no wire change, and makes the tip mean what the relay and the threat model already assume it means. |
| O4 | Make the detector tolerant (N consecutive regressions, ignore "known-old" values) | ❌ §1 — a second runtime's republish is byte-identical to the attack. This is deleting the detector while keeping its name. |
| O5 | Key the relay slot by `(ownerId, deviceId, runtimeId)`; tracker takes the max per device | Preserves detection exactly and is arguably the honest model (a *runtime* is what has a clock). ❌ for now: it grows `peerTips` unboundedly and gives the GC quorum a set of participants with no lifecycle — an uninstalled app's runtime would block GC forever, and device removal has no runtime-level equivalent. |

### Why O3 works without new IPC

Every exposed platform already shares a store between its runtimes: iOS first-party apps share the
App-Group replica, web tabs share IndexedDB. A persisted "highest HLC observed on this device"
row, read and written under `max`, is monotone across runtimes by construction — concurrent
writers can only push it forward. No lock, no leader election, no cross-process primitive.

⚠⚠ **The hazard is the cache, not the race.** Both shared stores are **cache-aside**: iOS bulk-loads
at open and refreshes at foreground (`DriveLocalRuntime` P2b), and `IndexedDbObjectStore` explicitly
does not propagate cross-tab. A watermark read through the cached store would be exactly as stale as
the in-memory clock it is meant to replace — i.e. this fix silently no-ops if implemented the
obvious way. **The tip-build path must read and write the watermark directly**, bypassing the
resident cache. That requirement is the whole engineering content of TC-2 and belongs in its test.

---

## 6. Phases

Three, not one — they have different blast radii, different gates, and different review surfaces.
TC-1 is copy and ranking with no security semantics and no platform work; TC-2 touches every
platform's storage path and needs an on-device iOS pass plus a two-tab web pass; bundling them
makes a same-day UX fix wait on a cross-platform storage change, and buries a threat-model-relevant
diff inside a string change.

### TC-1 — Stop the tray lying (surface only, no wire, no detector change) — **BUILT**

**1. Reworded.** `"Sync issue detected"` → **`"Consistency warning"`**
(`TrayAgent/main.swift`). The old string claimed the one thing that was, in every observed case,
false; the new one says what the state actually is — a warning about one peer, not a verdict on
sync.

**2. The detail now reaches the surface.** `DivergenceWarning` gains `observedAtMillis`, and
`ControlBridge.getSyncState` ships `divergenceKind` / `divergencePeerDeviceId` /
`divergencePeerName` / `divergenceObservedAtMillis` / `divergenceFresh` alongside the existing
boolean. The tray renders a second line — *"iPhone reported an out-of-order sync clock at 15:07."*
— falling back name → short id → "Another device", never guessing. The name is resolved at the
bridge from `SyncRepository.devices`, **not** carried on `DivergenceWarning`: a security-detector
type has no business holding presentation data. That read is bounded (2 s) and only runs when a
warning stands, because `getSyncState` is on the tray's poll path.

**3. Recency-bounded headline.** `divergenceIsFresh(observedAtMillis, nowMs)` +
`DIVERGENCE_HEADLINE_WINDOW_MS` (30 min) gate what `syncStateCode` receives. Past the window the
tray leads with the operational state again and the warning drops to the detail line — **demoted,
never dropped**: the raw `divergenceWarning` boolean and the full detail keep shipping, and the
tray keeps showing the line.

⚠⚠ **The non-obvious half.** A recency bound is inert unless the stamp can go stale, and it could
not: the offending tip sits in the relay's single per-device slot, so the *same* regression is
re-derived on every poll — 120 times an hour. `HlcDivergenceTracker.raise()` therefore keeps the
**first** stamp when the new warning reports the same evidence (`sameEvidenceAs`, spelled out per
variant because the timestamp is a constructor property and so part of the generated `equals`).
Detection is untouched: still raised, still latched, still cleared only by the existing recovery
path. Only the stamp is held. `repeatedRegression_keepsTheFirstStamp` is the test that pins it,
and without it every other part of step 3 is decoration.

⚠ `nowMs` on `HlcDivergenceTracker` is deliberately **required**, not defaulted. A default would
stamp warnings from an un-updated wiring at epoch 0, which a recency-bounded surface reads as
"ancient" and silently declines to show — a missing clock should be a compile error, not an
invisible loss of the warning.

**4. ⚖️ STILL OPEN — maintainer call:** should `DIVERGENCE` keep outranking `OFFLINE`/`SYNCING`?
Deliberately **not** answered by this slice. Demoting a security detector below operational states
is a posture change that belongs to whoever owns the threat model; the recency bound reduces how
long the question bites without pre-empting it. `syncStateCode`'s precedence is byte-identical.

Gate: `:sync-service-proxy:jvmTest` (16/16), `:control-shared:desktopTest` (incl. the 7 new
`DivergenceFreshnessTest` cases), `swiftc` on the five TrayAgent sources. No relay coordination,
no wire change, no schema bump. ⭐ The dedupe guard was **deleted and re-run**: exactly the two
`keepsTheFirstStamp` tests go red without it and nothing else does, so they pin the behaviour
rather than the shape.
⚠ Not verified on a device: the tray *renders* only under a live daemon with a standing warning,
so the new line and the 30-minute demotion have been unit-tested and compiled, not seen.

### TC-2a — One clock per device, everywhere it can be verified without a phone — **BUILT**

**The seam.** `HlcWatermarkStore` + `HlcWatermark` (`:sync-service-common`, beside
`ContentBlobStore` and threaded the same way): `read()` and `raiseTo(candidate)`, where `raiseTo`
never lowers and **returns the merged value**. That return is load-bearing — it is how a lagging
runtime learns the device's real position in the same round-trip instead of republishing its own.

**The merge.** `SyncScheduler.buildSignedTip()` is now `suspend` and goes through
`mergedHlc()`: raise the store with the local clock, then raise the local clock with the result.
Both directions matter — the first publishes this runtime's progress to its siblings, the second
stops it publishing below what a sibling already published. `HLCManager.seed()` is the raise-only
entry point, deliberately **not** floored at `now` the way `update()` is: reading a watermark
observes nothing, and an inflated clock makes this device claim it applied tombstones it never
saw. A store failure degrades to the process clock — the pre-TC-2 behaviour, degraded but
correct-in-isolation — because publishing *nothing* would strand the relay's GC quorum instead.

⚠ **No separate startup seed**, which the plan for this phase called for. A fresh process has an
all-zero clock, so its *first* merge on the tip path already returns the durable watermark and
adopts it — the `(0,0)` tip never leaves the process. One code path rather than two, and no
fire-and-forget `launch` in proxy init (`tech-debt.md`, "Fire-and-forget launches in proxy init")
racing the first poll.

**Platform impls.**

| Platform | What shipped | Effect |
|---|---|---|
| Desktop, Android | `SecureStorageDelegateHlcWatermarkStore` | Fixes **C3**. C1/C2 cannot occur — one process, one clock. |
| **Web** | `IndexedDbHlcWatermarkStore` | Fixes **C1/C2 and C3**. The merge runs inside one `readwrite` transaction; IndexedDB serialises those across tabs, and that serialisation *is* the cross-runtime exclusion. ⚠ Deliberately no in-memory cache and **not** built on `IndexedDbSecureStorageDelegate`, which fire-and-forgets its puts — two tabs racing a read-modify-write would lose the update and silently re-create the backward step. |
| iOS | `SecureStorageDelegateHlcWatermarkStore` | Fixes **C3 only.** Durable but app-private (`IosSecureStorageDelegate` sets no `kSecAttrAccessGroup`), so Control-iOS and Drive-iOS still keep separate watermarks under one shared deviceId. TC-2b is the rest. |

⚠⚠ **Do not "fix" iOS by giving the delegate a Keychain access group.** It would then be shared in
builds where `sharedReplicaDirectory()` falls back to app-local Documents and the *store* is not —
and a watermark wider than the store it describes is a forged ack, which the relay acts on by
dropping tombstone bodies that device never applied. Strictly worse than the bug it would appear
to fix. TC-2b's location must follow the replica's.

**Threat model** (House Rule #2, same PR): T-T-04 gains the store-scoped tip and the note that a
detector firing on ordinary multi-app use is itself a security cost; T-T-08 gains the durable ack
watermark and the asymmetry that a deflated tip stalls GC while an inflated one drops bodies.

**Tests.** `HlcManagerSeedTest` (5), `HlcWatermarkStoreTest` (7), `MultiRuntimeHlcTipTest` (3 —
the lagging-sibling case, the restart case, and a negative control asserting the clock stays
process-local with no store wired, so the other two cannot pass vacuously). ⭐ The merge was
**deleted and re-run**: the two positive tests go red and the negative control stays green.

### TC-2b — the iOS half — **BUILT** (⚠ not device-verified)

**What shipped.** `HlcWatermarkFile` (`:core-common/iosMain`) — a durable
`(physicalTime, logicalCounter)` pair in a file under [`FileAdvisoryLock`], plus
`IosSharedHlcWatermarkStore` (`:control-shared-proxy/iosMain`), a ~50-line adapter that picks the
directory and maps the types. The iOS wiring now uses it instead of TC-2a's delegate-backed store,
so Control-iOS and Drive-iOS agree on one position and the false positive is closed by
construction.

**⚠⚠ Co-location is the whole design.** The directory comes from `sharedReplicaDirectory(...)` —
*the same resolver the object store uses* — so the watermark shares exactly when the replica
shares: App-Group container where the entitlement grants one, app-local Documents where it does
not. Neither configuration can end up with a watermark wider than the data it describes. A
**sibling** subdirectory (`sync-hlc`), never the store's own, because `FileObjectStore` enumerates
its directory in six places.

**⚠⚠ The lock is tried, never waited on.** `flock` releases when the holder's descriptor closes —
process death included — but **not when iOS merely suspends the holder**, which it may do
mid-write. A blocking `lock()` would let a backgrounded Drive freeze Control's sync tick for as
long as the OS liked. So `raiseTo` retries with a bounded backoff (~100 ms) and then falls back to
a lock-free merge that **persists nothing**: the caller still gets a value never below its own, and
the next poll writes it. "Correct but not yet persisted" is the right way to degrade.

**⚠ Why the mechanism is in `:core-common` and not beside its caller.** It went into
`:control-shared-proxy` first and its test came back `SKIPPED` under a green build: the root build
disables `iosSimulatorArm64Test` — *and the test compile and link* — for every `-proxy` module,
because that LLVM link is the heaviest K/N step and enabling it across ~19 modules caused recurring
`Java heap space` OOMs. Rather than carve an exception into a policy with a measured reason, the
mechanism moved next to the lock it wraps, inside a module `iosTestCheck` enumerates. What decides
whether a device publishes a monotone clock should not be the untestable half. Cost: it cannot
reference `HlcWatermark` (`:sync-service-common` depends on `:core-common`, not the reverse), hence
`HlcWatermarkFile.Stamp` and a mapping adapter.

**⚠ A defect reported here that turned out not to be one — corrected 2026-08-30.** TC-2b
originally recorded that `(text as NSString).writeToFile(...)` — the form `AppGroupProbe.writeMarker`
and `tickSharedClock` used (the probe was deleted 2026-11-05; `SharedReplicaChoice.declare` still uses it) — was broken, on the strength of the compiler's *"This cast can never
succeed"* warning. **Measured, it works**: Kotlin/Native bridges String↔NSString at runtime and the
write succeeds on the simulator. `NSStringBridgingTest` (`:tenshu-sdk-ios-core`) now pins that, because
nothing else did and the warning had already cost one wrong diagnosis.

⚠⚠ The lesson is the one this doc keeps re-learning: a compiler warning is a hypothesis, and
"proves less than it appears to" was itself an unmeasured claim. `HlcWatermarkFile` still writes via
`ByteArray → NSData`, but for a weaker and honest reason — it does not rest on a bridging behaviour
the compiler disputes, and it matches `IosGetPreviewImplementation` and `DrivePreviewSurface`.

**Tests.** `HlcWatermarkFileTest`, 9 on the iOS simulator: never-lowers, counter tie-break inside
one millisecond, persistence across instances (the relaunch case), two instances over one directory
replaying the live 15:07 → 14:51 sequence, a held lock still yielding a correct value while
persisting nothing (the suspend-safety fallback), corrupt-file degradation, on-demand directory
creation, codec round-trip. ⭐ The monotonicity check was **deleted and re-run**: exactly
`raiseToNeverLowers`, `equalPhysicalTimeBreaksOnTheCounter` and
`twoRuntimesOverOneDirectoryNeverGoBackwards` go red — 3 of 9, nothing else.

**⚠⚠ Outstanding: the two-app device pass.** The simulator cannot cover two genuine *processes*,
nor iOS suspending a lock holder mid-write — the exact hazard the try-don't-wait design exists for
— nor the App-Group container path itself. Run Control-iOS and Drive-iOS on one phone, sync from
one while the other idles, and confirm the desktop tray stays quiet.

### TC-2b — original design notes

1. Persist a per-device HLC high-water mark; seed `HLCManager` from it at startup (which alone
   ends C3's restart-zeroing).
2. Build the tip from `max(persisted, in-memory)` and write back the max, **reading and writing
   the durable value directly, not through the resident cache** (§5 hazard).
3. Platform passes: iOS (two first-party apps, one foregrounded after the other has ingested) and
   web (two tabs of the same origin).
4. Threat-model amendment in the same PR (House Rule #2): T-T-04's defence paragraph gains the
   statement that the tip is device-scoped and durable, and T-T-08 gains the note that the
   watermark now survives a restart.

⚠ The store is per-platform, so "persist a value" is not one line — this is the phase that needs
the design review.

### 6b. TC-2b device pass — measured 2026-09-07, PASSED

The outstanding item this doc carried since 2026-08-30. Run against the maintainer's iPhone 17e
(`E5044A66…`, `deviceId e4a23211…`) with **three** route-1 runtimes installed — Control, Drive and
Fit — rather than two, alternated over two rounds. The observable is the relay's own `hlc_tip` row
for that device, sampled after each launch: whatever the phone actually published, read back from
the authority that stores it.

| step | published tip | delta |
|---|---|---|
| baseline | 1788794177166 | — |
| r1 Control-iOS | 1788797821353 | +3 644 187 |
| r1 Drive-iOS | 1788797821353 | **0** |
| r1 Fit-iOS | 1788797897923 | +76 570 |
| r2 Control-iOS | 1788797947211 | +49 288 |
| r2 Drive-iOS | 1788797947211 | **0** |
| r2 Fit-iOS | 1788798027042 | +79 831 |
| final Control-iOS | 1788798065815 | +38 773 |

**Seven transitions across three runtimes, zero regressions.**

⭐⭐ **The two zeros are the result, not a gap in it.** A freshly launched Drive-iOS starts at
`HlcState(0, 0)` — `HLCManager.state` is per process and is never seeded from the store — so without
TC-2b it would publish its own low clock into the shared slot and the tip would go **backwards**,
which is exactly the 15:07 → 14:51 regression §1 opens with. Instead Drive published *precisely*
Control's value: it read the shared watermark, raised its local clock to it, and had nothing higher
of its own to contribute. That is TC-2a's second direction — *"stops it publishing below what a
sibling already published"* — observed end-to-end on hardware.

⚠ **What this pass does not cover.** Launches were sequential with ~40 s between them, so it
exercises hand-off, not a genuine race: two runtimes publishing inside one `raiseTo` window are
untested, and `FileAdvisoryLock` is what would carry that case. It also ran with the App-Group
entitlement present, which is the shipped configuration but not the `?:` fallback branch of
`sharedReplicaDirectory` — under app-local stores the watermark is app-local too, and this result
says nothing about it.

⚠ Method note for whoever repeats it: read the tip from the **relay**, not from a device log. The
relay row is what peers actually consume, and it is the only vantage point from which "what the
device published" and "what a runtime believed" cannot be conflated.

---

### TC-3 — Prove it, and close the loop

1. A regression test that reproduces the actual bug: two `HLCManager`s over one `deviceId` and one
   store, alternating tips, tracker sees no warning. ⚠ Delete the fix and confirm this test goes
   red — a multi-runtime test that passes against the *old* code is testing nothing.
2. A relay-side observability counter for tip regressions per `(ownerId, deviceId)`. **Not**
   rejection: the relay must faithfully store what a device claimed, or a buggy client wedges
   itself and the client-side detector loses its input. Metadata-only, HR #1 intact.
3. C3's idle-client case: assert that a client which pulls nothing and writes nothing still
   publishes a truthful non-zero watermark.

---

## 7. Decisions

- **D1.** The tip stays a *single* number carrying both meanings (detector input + GC watermark),
  because O3 makes the two meanings agree. If TC-2 shows they still fight, O2 (split the wire) is
  the escalation — not O1.
- **D2.** The relay never rejects or clamps a regressing tip. Detection is client-side by design;
  a relay that silently repaired the sequence would erase the T-T-04 signal it exists to expose.
- **D3.** The detector is not made tolerant (O4). A false positive is a UX bug; a detector that
  cannot see a replayed tip is a security regression.
- **D4.** ⚖️ Whether `DIVERGENCE` keeps its rank above `OFFLINE`/`SYNCING` is TC-1's open call.

---

## 8. Doc drift found on the way

- `docs/sync/threat-model.md` T-T-04 says *"**UI rendering** of the warning is the remaining
  piece; until that lands the warning fires silently into the StateFlow with no user-visible
  surface."* That has been false since the desktop tray shipped `SyncStateCode.DIVERGENCE` — it is
  rendered, prominently, and this whole report is about what it renders. The residual-risk line
  ("**Low** with detection in place; **Medium** until UI surfaces it") should be re-read in light
  of C1: detection that fires on ordinary multi-app use is not obviously "Low".
- The `DIVERGENCE` reword is listed as outstanding in two other docs
  ([`core-background-processing.md`](core-background-processing.md) §5.3.1 and
  [`sync-metered-deferred-status.md`](sync-metered-deferred-status.md)), described in both as
  trivial and foldable into whichever slice touches the tray. It is not trivial — the copy is
  wrong because the *state* is wrong, and TC-1 owns it here.

---

## 9. Adjacent defect, filed not fixed

While reading the live process: the Control **desktop** proxy is running **two active
`SyncScheduler`s** (two `SyncProcessor`s, two `RealmRegistry`s, two `HlcDivergenceTracker`s, both
jobs in an active state, both trackers holding identical current floors) against **one**
`SyncManagerImpl` and **one** `RestSyncRelay`. `ClientRegistry.clients.size == 2` and
`systemSession == null` — `mintSystemSession = false` is deliberate and documented
(`control-shared-proxy/…/di/ProxyModule.kt:319-332`), but that comment asserts *"the scheduler
itself is deduped by `SyncRuntimeHolder`"*, and on this instance it plainly is not.

Consequence: the desktop double-polls the relay, and two schedulers drain the same queues behind
**per-instance** `tickMutex`es — so the mutual exclusion each one thinks it has does not exist
between them. Not the cause of the tray state (both share the one `HLCManager`, so they publish
identical tips), and not investigated further here.
