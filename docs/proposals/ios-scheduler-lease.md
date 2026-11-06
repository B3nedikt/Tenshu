# iOS shared-store safety, scheduler lease and `IosPlatformScheduler` (S0b, S1)

**Status:** ⚠⚠ **DRAFT v0.14 (2026-10-02). Design only, no code, and NOT ready to build from.**
Merged as a record after thirteen review rounds, on the maintainer's decision, with **three
serious findings open (§15)**. The scheduler and lease parts (§3, §4, §6–§9) were stable for the
last several rounds. The shared-store part (§5) was not: every round fixed what it found and
uncovered something as serious elsewhere in it. ⇒ **§5 is to be carved into its own design**,
after a read-only investigation of whether SQLite should back the shared store on iOS instead of a
hand-built protocol over one file per object (§15.2). Until that lands, S0b has no buildable
design and the App Group build stays uninstalled.
⚠⚠ **§5 is superseded (2026-10-03)** by [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md):
the maintainer decided the shared replica on iOS is backed by SQLite, and that document is S0b's
design, resolving the three findings of §15.1; **M12 is decided** (accepted 2026-10-03 as D5, its §7). §5 is kept below
as history; §3, §4 and §6–§9 stand, with the changes listed in its §9. ⚠ Superseded instructions
outside §5 too: the lease **files** of §4.2–§4.3 (directory, digests, temp-and-rename) are a
`schedule_lease` table in the same SQLite transaction as the registry row, and §4.6's `flock`
reasoning, the generation file and rule C's intent markers no longer apply.

⭐ The maintainer accepted every recommendation in §13 on 2026-10-02 and settled M10 (the gate and
the quiesce step are built whatever the device test shows); M12 was open here and is decided in
`ios-shared-store-sqlite.md` §7 (D5, accepted 2026-10-03). Phases **S0b and S1** of
[`ios-background-scheduling.md`](ios-background-scheduling.md), written to be built from without
re-deriving anything. Maintainer decisions D1, D2, D4, D5 and D6 of that doc (§7) are inputs here.
The sibling design for S4 is [`sync-push-wake.md`](sync-push-wake.md).

**v0.7 restructures the design into two layers (maintainer decision, 2026-10-02).** Five review
rounds kept finding the same two things, and neither was about the lease:

- a process can save from a stale resident base and overwrite a peer's rows, and the lease was
  guarding that per job;
- the lease and the registry were validated under separate locks, so there was always a window
  between reading a row and acquiring.

So:

- **Layer 1, the store protects itself (§5.5).** A generation for the shared replica, advanced by
  every write transaction under the store lock. A process whose resident base is behind rehydrates
  before its next store access, whichever job, app or entry point is running. Nothing in the lease
  mentions rehydration any more.
- **Layer 2, the lease only answers "who runs" (§4).** It is read and written **inside the store
  lock**, in the same critical section as the read of the registry row. It has no lock of its own.

Kept from v0.6: the lock gate and protection tokens, the tried store lock, quiesce, the scheduler
mapping, `Cadence` and `Demand`, the wake runner and its budget, the default lease that never
withholds a dispatch, the entry table, and the checkable form (§4.3, §4.4, §4.7). Removed: the
lease guard lock, the registry authority and its fresh reads, fingerprint reconciliation across two
locks, `mustRehydrate` and its two record fields, the minute refresh, and every explicit rehydrate.
**M2 is retired, M1 and M7 change meaning (§13).**

**v0.8, after a sixth review and the decision on M11 (2026-10-02).**

- **M11 is decided: the generation counter alone, with a full rehydrate when behind.** The change
  journal of v0.7 is out of the protocol and deferred (§5.6). What is left of layer 1 is one small
  file and one rule.
- **Nothing touches the replica directory outside the store lock and the generation**: the
  store-less wipe, the live wipe and `open` itself are brought in (§5.5, paths table), and §5.5
  lists every crash point.
- A registration **incarnation**, bumped inside the `Schedule` and `CancelScheduled` transactions,
  replaces the content fingerprint, so a cancel followed by an identical re-registration is seen
  (§4.4).

**v0.9, after a seventh review.** Three rules replace sequences that were specified one by one,
and each list of cases is now derived from its rule:

- **Rule C, ordering** (§5.5): durable intent, then the generation, then the destructive step, then
  clear the intent; any process that sees an intent completes it. The crash table is regenerated
  from it.
- **Rule P, fail closed** (§5.3): the gate is open exactly while the app is in the foreground or
  holds a live token. Every way protection can be refused or lapse lands on "gate closed and
  drained, loops stopped".
- **One classification** decides which items touch the lease at all (§3.3): only realm-wide ones.

⚠⚠ **And a blocker, by maintainer decision the same day: schema migration of a shared store** (§5.7,
M12). It is an open question with no mechanism chosen, and the App Group build must not be
installed until it is answered.

**v0.10, after an eighth review.** No new mechanism; the two rules are applied where they had not
been. A wipe or a migration **replaces** the store, and a process that finds it replaced is
**fenced**: it can write nothing through its old handle (§5.5). So a holder cannot write past a
wipe, and the wipe may remove the lease records. Every lease call a **holder** cannot complete
ends in "stop", never "carry on" (§4.4). Lease files are named by a fixed-length digest (§4.2).

**v0.11, after a ninth review** (four points, none structural). The next wake request is submitted
unless the app is *known* to be unprovisioned, so a wake before first unlock does not end the chain
(§7.4). A one-shot that the policy table does not name is refused at registration (§3.3). §7.1 has
the module of every new type, checked against the build files. A generation write that is not all
16 bytes is a failure and no row is touched after it (§5.5).

**v0.12, after a tenth review**, all in the wake runner (§7.4 to §7.6). Every delivered task leaves
a request pending on every exit path: the first act of a wake is a submission that needs no runtime
and no store (invariant I14). The sync drain, the app-local jobs and the registry items each have
their own slice of the wake. `ListScheduled` shows the lease's attempt count (§6.1).

**v0.13, after an eleventh review** (two points). A deferral is not a completion: it leaves
`lastCompletedAtMs` and `attempts` alone and sets only the retry time, and §4.4 has one table of
what every way a run can end writes. Whether an item is punctual is read from its operation's
schema whenever a plan or a wake filter is built, never from a registration argument (§7.4).

**v0.14, after a twelfth review** (two points). The two container files a process keeps a
descriptor on, the lock file and the generation file, are **never unlinked and never renamed
over**, and each lock entry checks that its descriptors still name a linked file (§5.5, rule D).
Wake requests go through one serialised submitter that does not depend on whether a second
submission replaces the first (§7.4).

**Relates to:** [`ios-background-scheduling.md`](ios-background-scheduling.md) §4.2, §4.3, §6;
[`core-background-processing.md`](core-background-processing.md) §5.5, §5.6, §8 Phase 5–7 (where
`PlatformScheduler` is documented; there is no separate doc for it);
[`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md) §6;
[`ios-drive-parity.md`](ios-drive-parity.md) §7, §11, §15, §16;
[`ios-first-party-route-1-migration.md`](ios-first-party-route-1-migration.md) §8–§9;
[`calendar-app.md`](calendar-app.md) §4.3 and CA-8c; [`routines-engine.md`](routines-engine.md);
[`core-task-composite.md`](core-task-composite.md) (an unrelated "lease", §2);
[`docs/sync/threat-model.md`](../sync/threat-model.md) A1, A4, I9;
[`sync-push-wake.md`](sync-push-wake.md) (S4), which calls the interface in §10.2.

**How to read the evidence.** *Read* means the statement is taken from the cited file and line on
master at `2b254be75`. *Inferred* means it follows from what was read but was not observed.
*Platform* means it is Apple-documented behaviour that nothing in this repository has exercised.

---

## 1. What this delivers, in two layers

**Before the App Group build is used on a device (sub-phase S0b, §12):**

1. **Layer 1: the shared store protects itself** against a stale resident base (§5.5).
2. **A gate in front of every file lock in the shared container**, protection tokens, and a
   **quiesce** step, so no process is suspended holding such a lock (§5.1 to §5.4).

Both are properties of the store and of the process, not of scheduling. S0 makes both necessary and
nothing provides either today.

**Then S1 proper:**

3. **Layer 2: a run lease**, one small state file per `dedupKey` in the App Group container. It
   decides which process runs a realm-wide job and records when the job last finished (§4).
4. The same lease gating the **in-process loops** (`ScheduleDrainLoop`, and `SyncScheduler`'s tick),
   so two live processes do not both run a realm-wide job (§6).
5. **`IosPlatformScheduler`** replacing `NoOpPlatformScheduler`, and one Kotlin wake runner over
   `BGAppRefreshTask`, used by every route-1 app (§7).
6. The three hand-written drivers retired (§12).

Not here: `BGProcessingTask` and constraint enforcement (S2), Calendar re-arm in the background
(S3), push (S4), any change to Android or desktop behaviour.

## 2. Vocabulary

| Term | Meaning here |
|---|---|
| **run lease** | The cross-process claim on one `dedupKey`. ⚠ Not the *task lease* of `core-task-composite.md` (`RenewTaskLease`, `TaskRegistry`), which keeps a client's long task alive inside one proxy. The type is named `ScheduleRunLease` to keep them apart. |
| **realm-wide item** | A job whose effect lands in the shared replica or on the relay, so one run serves every app on the device. Leased. |
| **app-local item** | A job only one app can do: it needs that app's OS entitlement, notification centre or `URLSession`. Never leased; other apps skip it. |
| **wake** | A background launch or resume granted by iOS: a `BGAppRefreshTask` in S1, a `BGProcessingTask` in S2, a silent push in S4. |
| **protection** | What lets a process run without being suspended: it is in the foreground, or it holds at least one live protection token, each backed by a background-task assertion (§5.3). |
| **quiesce** | Close the lock gate and wait until no thread is inside a shared-container lock. Only then is protection given up (§5.3). |
| **resident base** | What one process holds in memory of the shared replica: its index of which rows exist and its cache of decoded rows. |
| **generation** | A counter for the shared replica, in the group container, advanced by every write transaction (§5.5). |
| **catch-up** | Bringing a resident base up to the current generation, by a full rehydrate (§5.5). |

## 3. Where the registry lives and who writes it

### 3.1 What the code does today (read)

- A `ScheduleItem` is a row in one array of the object store,
  `tenshu/sync/scheduleService/scheduledItems` (`ScheduleStore.kt:15-24`), keyed by `dedupKey`
  alone (`ScheduleItem.kt:70-82`).
- It is **device-local, not synced**. Its schema carries `DataIdProvider` and no `Syncable` trait
  (`ScheduleItem.kt:72`), and `SyncObserver` emits only data whose schema has that trait
  (`SyncObserver.kt:1249`).
- It is not per-process state: the row lives in the store. What *is* per-process is everything
  that decides when it runs — the tick accumulators and "has fired once"
  (`ScheduleDrainLoop.kt:114-115`), the single-flight map (`:125`) and the generation guard
  (`ScheduleRunState.kt:22-25`, which names route 1 as its own limit).
- Every route-1 app assembles the same proxy graph (`FirstPartyLocalRuntime.kt:134`), so every app
  registers every item and starts both loops (`SyncProxyInitialization.kt:960`, `:976`).
- On iOS the store is `sharedReplicaDirectory("objects")` (iOS `PlatformProxyModule.kt:663-675`).

So after S0 puts every app on one store there is **one registry on the device**, written by every
process at its start and read by every process's loop. Before S0 a device has one registry per app.

⚠ Each process reads the rows through its own resident cache. A row another process wrote is
invisible until `FileObjectStore.rehydrateFromDisk()` (`FileObjectStore.kt:280-330`), which runs
only on a foreground transition today. That is a store problem, not a registry problem, and layer
1 (§5.5) solves it for every row at once.

*Read:* the registry's two writers already work under the store lock. `Schedule` writes the row
inside `Database.transaction` and only afterwards calls `platformScheduler.register`
(`ScheduleImplementation.kt:79-91`); `CancelScheduled` removes it inside one and then calls
`platformScheduler.cancel` (`CancelScheduledImplementation.kt:38-51`). `Database.transaction` is the
`DatabaseLock` (`DatabaseImpl.kt:80-87`), which on iOS is the cross-process `flock`
(`SharedReplicaDatabaseLock.kt:49-63`). ⚠ Both decide from this process's cached array
(`scheduleItemFor`, `ScheduleStore.kt:56-62`): today a cancel in a process that has not seen the
row removes nothing. With layer 1 the cache is current by the time the transaction's block runs,
and neither operation needs changing.

### 3.2 Every item registered on iOS today (read from the code)

| `dedupKey` | Operation | Registered at | Kind, period, first delay | Constraints stored | Class |
|---|---|---|---|---|---|
| `tenshu/sync/sliceSnapshot` | `WriteSliceSnapshots` | `SyncProxyInitialization.kt:965` | periodic, 24 h, 10 min | unmetered, charging, idle, battery not low | realm-wide |
| `sync/contentDeclarationBackfill` | `BackfillContentDeclarations` | `SyncProxyInitialization.kt:968` | periodic, 24 h, 15 min | same four | realm-wide |
| `tenshu/file/offlineKeeper` | `OfflineKeeper` | iOS `PlatformProxyModule.kt:751` | periodic, 6 h | unmetered | realm-wide ¹ |
| `tenshu/file/pendingMoveReplay` | `ReplayPendingMoves` | `:757` | periodic, 15 min, 2 min | none | realm-wide |
| ~~`tenshu/inference/mediaIndexPass`~~ | `MediaIndexPass` | **no longer registered on iOS** ² | periodic, 12 h | no network, charging, idle, battery not low | — ² |
| `tenshu/calendar/subscriptionRefresh` | `RefreshSubscriptions` | `:815` | periodic, 6 h | unmetered | realm-wide |
| `tenshu/calendar/nextReminder` | `DeliverDueReminders` | `ReminderArmer.kt:36-76`, on a calendar write | one-shot, delay to the next reminder | none | app-local (Calendar) |

¹ The byte store it fills is `IosFileDatabase`, "beside the object-store replica in the same
App-Group container" (`PlatformProxyModule.kt:638-646`), so one run serves all apps.
² **Resolved 2026-10-07 (PIO-7 PR-3, [`ios-on-device-inference.md`](ios-on-device-inference.md)
§3.1 / §13): the embedding store is app-private on iOS, and the item is retired there.** The store
lives in Photos' own container and no other process can write one, so the job was never
realm-wide. The iOS `PlatformProxyModule` no longer calls `registerMediaIndexSchedule()`; Photos
drives its passes itself, and S1a has nothing to classify. ⚠ *The retirement below was removed at
the 1.0.1 re-cut (every store wiped, all apps ship together), so no such row exists.* **A row an earlier build registered is
cancelled**: every iOS process lists the registry at proxy init and, if the row is there, issues
`CancelScheduled` for the key (`retireMediaIndexSchedule`), so an upgraded install stops
dispatching it. ⚠ It looks first because a cancel takes the writer lock every app shares.
⚠⚠ **And the row is removed again whenever it is dispatched**: on iOS the scheduled op itself is
the retirement (`RetiredMediaIndexPassImplementation`). The apps update one by one, and a sibling
on an older build registers the row at each of its starts; the first updated process whose drain
loop dispatches it cancels it, with no pass and no deferral. S1's planner still must not lease
this key: any row it sees is one an old build wrote. ⚠⚠ That is safe because
**the registry is device-local**: `ScheduleItem` carries no `Syncable` trait (`ScheduleItem.kt:70-72`),
so a row never leaves the device and its removal emits no tombstone (`TombstoneRegistry.kt:206,222-225`).
"Realm-wide" in this table means shared by this device's apps through the App Group replica, never
shared with another device. Android and desktop keep their own rows and keep registering them.

**Not registered on iOS**, each checked against the iOS module:

| `dedupKey` | Why absent |
|---|---|
| `sync-drain` | `BackgroundSyncScheduler` is constructed only on Android (`androidMain PlatformProxyModule.kt:1197-1203`). ⚠ On iOS the sync drain is **not a registry item at all**: it is `SyncScheduler`'s own loop (`SyncScheduler.kt:705-748`, 30 s, `:66`). §6.2. M3 registers it in S1, as a marker the planner reads and the registry loop never dispatches (§3.3). |
| `sync-drain-expedited` | Android only (`:1220`). |
| `tenshu/file/thumbnailBackfill` | Registered on Android and desktop (`:897` in both), not on iOS. |
| `tenshu/routine/nextWake` | The armer is built only with a routine engine (`RoutineEngineFactory.kt:56`), and iOS builds none: its op factory is given `RoutineExecution.NEVER` (iOS `PlatformProxyModule.kt:1208-1219`, `routines-engine.md` RE-D13). D6. |
| `tenshu/sync/schema-compaction` | No production registration on any platform. |

**Work that runs on iOS without being a registry item**, which a lease must still account for:

| Work | Where | Class |
|---|---|---|
| Sync cycle: push, pull, shared realms, tombstones | `SyncScheduler.tickOnce` (`SyncScheduler.kt:908`) | realm-wide |
| Invite response pickup | sync-cycle hook (`PlatformProxyModule.kt:982`) | realm-wide, inside the cycle |
| Orphaned-file sweep | maintenance hook, before the enabled guard (`:990`, `SyncScheduler.kt:916-924`) | realm-wide, inside the cycle |
| Reclamation sweep, dry-run | built by `initSyncService` (`:889`, `:926`) | realm-wide, inside the cycle |
| Health import and recorder outbox | `HealthImportBootstrap`, `FitSurface.runBackgroundRefresh` (`FitSurface.kt:211-216`) | app-local (Fit: HealthKit) |
| Photo backup catch-up | `DriveBackupSurface.backupNow()` (`DriveLocalRuntime.kt:359`) | app-local (Drive: PhotoKit) |
| Content upload transport | background `URLSession` (`BackgroundUpload.swift:25-38`) | app-local (Drive) |
| Pending-request alerts | `ControlSurface.runPendingRequestRefresh` (`ControlSurface.kt:504-512`) | app-local (Control posts them) |
| Reminder pre-arm | `IosNotificationPresenter`, Calendar only (`PlatformProxyModule.kt:294-311`) | app-local (Calendar) |
| Visit and significant-location capture | `timeline-ios` | app-local (Timeline) |

### 3.3 How an item gets its class

A stored field would reshape `ScheduleItem` (House Rule 9), and the owner of an app-local item is an
app, which no schema trait can name. So the class is a **Kotlin-only table**, in
`:sync-service-proxy` commonMain next to `ScheduledWorkPlan.kt`:

```kotlin
enum class RunScope { REALM_WIDE, EXTERNALLY_DRIVEN, APP_LOCAL }
class ScheduleRunPolicy(
    val thisApp: String,
    private val appLocalOwners: Map<String, String>,
    private val realLease: ScheduleRunLease,
) {
    fun scopeOf(dedupKey: String): RunScope
    fun runsHere(dedupKey: String): Boolean        // false for an APP_LOCAL key another app owns
    fun leaseFor(dedupKey: String): ScheduleRunLease   // the ONE place that decides who is leased
}
```

**`leaseFor` is the single classification.** Every caller that would touch the lease, to `peek`,
`registrationChanging`, `tryAcquire`, `renew`, `complete` or `abandon`, asks `leaseFor(key)` and
uses what it returns. Nobody holds the real lease directly.

| Scope | `leaseFor` returns | So |
|---|---|---|
| `REALM_WIDE` | the real lease | leased; rule A and the heartbeat apply |
| `EXTERNALLY_DRIVEN` (`sync-drain`) | the real lease | the registry loop never dispatches it; `SyncScheduler` leases the cycle (§6.2) |
| `APP_LOCAL`, owned by this app | `AlwaysGrantedLease` | **never leased.** It runs on the in-process timing exactly as today: no file, no refusal, no heartbeat, whatever state the lease directory is in |
| `APP_LOCAL`, owned by another app | not asked: `runsHere` is false | never dispatched here |
| a `ONE_SHOT` in none of the above | — | never registered: `Schedule` refuses it |

⚠ An app-local item has no other process to contend with, so a lease could only take a run away
from it. Routed through the file lease, Calendar's reminder would be `Refused(UNAVAILABLE)` whenever
the lease directory was unusable (M4), and a reminder would be lost for nothing.

- A key not in the table is `REALM_WIDE`. That is the safe default: at most one runner, any app.
- `EXTERNALLY_DRIVEN` is `sync-drain` alone. Its row exists so the planner has a period to plan
  from and `ListScheduled` shows it (M3). **`ScheduleDrainLoop` never dispatches it**, in `tick` or
  in `runDueNow`: the cycle is driven by `SyncScheduler`'s own loop in the foreground and by step 6
  of a wake (§7.5), and those two take the lease (§6.2). Dispatching `DrainSync` as well would run
  a second cycle in `Demand` mode straight after the first.
- ⚠ S1 leases **periodic** items only. Every one-shot on iOS today is app-local (§3.2), and what a
  realm-wide one-shot needs (a re-arm that resets its run history) is owed with D6 (§10.5).
- ⚠⚠ **A one-shot the table does not name is refused at registration.** On iOS, `Schedule` asks the
  policy first, and for a `ONE_SHOT` whose key is not in `appLocalOwners` it fails with a typed
  error before it writes anything. Neither fallback is sound: the row does not record which app
  registered it and cannot be made to without a reshape (House Rule 9), so "local to whoever
  registered it" is unknowable to every other process; `REALM_WIDE` would run it in whichever app
  got there first; and "every app that sees it" would run it in all of them.
  **What a developer adding a one-shot does:** add its `dedupKey` and owning app to
  `appLocalOwners` in the same change. `ScheduleRunPolicyTest` lists every `ONE_SHOT` registration
  in the codebase (today `ReminderArmer.DEDUP_KEY` and `RoutineScheduleArmer.DEDUP_KEY`) and fails
  if one is missing from the iOS table, so the refusal is met in a test and not on a phone. The
  default policy on Android and desktop refuses nothing (I4).
- `appLocalOwners` on iOS: `tenshu/calendar/nextReminder` → Calendar's client id. `thisApp` is the
  `platformPackageName` each runtime already binds (`FirstPartyLocalRuntime.kt:88`).
- Android, desktop and the hub bind a policy with an empty table and their own id; nothing changes
  there.

## 4. Layer 2: the lease

### 4.1 Requirements

These are the goals. §4.7 restates what the design guarantees as numbered invariants with tests.

1. Two live processes never both run the same realm-wide item on purpose.
2. A second process that looks a minute after a completed run does not run it again.
3. A holder that crashed, was killed, or was suspended stops blocking others within a bounded time.
4. Losing the lease is **safe**: the lease saves work and wake budget, it is not what protects the
   store. Store integrity is the store lock's and layer 1's (§5.5), and every leased operation
   must stay correct when run twice. That is true today by construction, since every process runs
   every job.

Requirement 4 is what allows a lease that expires by time. It must be stated in the
`ScheduleRunLease` KDoc, because a later author who treats the lease as mutual exclusion for
correctness will be wrong.

⚠ **The lease says nothing about stale data.** Whether a process's view of the replica is current
is layer 1's question and is answered before any lease code runs.

### 4.2 Mechanism: a record with a deadline, written inside the store lock

The obvious design holds a `flock` for the whole run, because the OS drops a dead holder's lock. It
is rejected for the reason in §5.1: a run can last minutes, and a process suspended with that lock
held in a shared container is terminated. A pid file is rejected too: a pid is reused, and one
sandboxed app cannot reliably ask whether another app's pid is alive (*platform*, not tested here).

Chosen: the lease is a **record** with a renewal deadline.

| Aspect | Design |
|---|---|
| Directory | `sharedReplicaDirectory("sched-lease")`: a sibling of `objects` and `sync-hlc`, resolved by the same function, so it is shared exactly when the replica is. ⚠ Never inside `objects`: `FileObjectStore` enumerates that directory (`HlcWatermarkFile.kt:53-54`). |
| State files | One per key: `<SHA-256 of the key's UTF-8 bytes, 64 hex characters>.lease`, 70 bytes whatever the key. ⚠ Not the key itself in hex: `Schedule` puts no limit on a `dedupKey`, and one of 125 bytes or more would pass the 255-byte filename limit and be refused for ever. The record carries the full key (§4.3); a record whose key is not the one asked for is treated as unusable for that key (T16) and logged. |
| Lock | **None of its own.** Every read-modify-write of a record happens inside `DatabaseLock.withLock`, the store's cross-process lock, in the same critical section as the read of the registry row it depends on. |
| Write | Whole file, via temp file and rename (`writeToFile(atomically = true)`), so a reader outside the lock sees the old record or the new one. |
| Read | `peek(key)` is lock-free. |
| Clock | Wall clock, `systemClock.nowEpochMillis()` (`PlatformProxyModule.kt:163`). §4.5. |

- ⚠ Why no lock of its own. v0.6 gave the lease a guard and took it after the store lock had been
  released; a cancel could commit between the two, and the acquirer was then granted a job whose
  row was gone. With one lock there is no "between".
- A lease write is not a replica write: it does not advance the generation (§5.5).
- The store lock is held for a row read and one small file write: well inside the 1 s ceiling of
  §5.3.

### 4.3 What is recorded

One line of `key=value` pairs, prefixed `v1`. A malformed file decodes as an absent one, exactly as
`HlcWatermarkFile.decode` treats a corrupt file (`:171-185`): a damaged lease must cost a duplicate
run, never a launch. ⚠ The record holds **no registration state**: whether a key is registered, and
as what, is the store row's to say. All times are epoch milliseconds; every duration in the
interface is milliseconds.

| Field | Meaning | Written by | When |
|---|---|---|---|
| `registeredAtMs` | When the current registration began on this device. Base for a first run's `initialDelayMs`. | the registration step | when the record is created, and again when a key is registered after having had no row (R1) |
| `incarnation` | Counts the real changes to this key's registration: a registration where there was no row, a replacement by a different row, a cancel. An identical re-registration, which every process repeats at its start, does not move it. A grant remembers the value it was given under. | the registration step, inside the `Schedule` or `CancelScheduled` transaction | R1, R2, R3 (§4.4) |
| `key` | The `dedupKey`, in full. The file name is only its digest. | whoever creates the record | at creation |
| `holder` | A token minted per acquisition: 16 random bytes, hex. Empty when free. | `tryAcquire`; `complete`, `abandon` | set at a grant; cleared when the run has stopped |
| `holderApp` | The holder's client id. Diagnostics and the device log only. | `tryAcquire` | at a grant |
| `acquiredAtMs`, `renewedAtMs` | When the holder took it and last renewed it. | `tryAcquire`, `renew` | at a grant; at every heartbeat |
| `lastCompletedAtMs` | When a run last ended with `DONE` or `FAILED`. ⚠ Not `DEFERRED`: a deferral is not a completion. | `complete` | at `DONE` and `FAILED` |
| `lastOutcome` | `DONE`, `FAILED`, `DEFERRED` or `NOT_FINISHED`. | `complete`, `abandon` | at the end of a run |
| `nextEligibleAtMs` | A **deferral** only: `now + retryAfterMs` after `DEFERRED`; 0, meaning none, otherwise. The period is never stored; it is read from the row (`waitMs`). | `complete`; cleared by the registration step (R1, R2) | set at `DEFERRED`; cleared at `DONE` and `FAILED` |
| `attempts` | Runs so far. Replaces the row's `attempts` on iOS (§6.1). | `complete` | `+1` at `DONE` and `FAILED`, not at `DEFERRED` |

No other field exists and no other caller writes. Every write is one whole-file replacement inside
the store lock (§4.2).

### 4.4 Protocol

**Rule A: read the row and act on the lease in one critical section.** Every lease operation that
depends on what is registered runs inside one `Database.transaction { }`, which is the store lock:
read the key's `PENDING` row, then do the lease operation, then leave. The row is current because
layer 1 caught this process up when it took the lock (§5.5); nothing can change it before the
lease is written, because every writer of the registry needs the same lock.

**Rule B: a registration change and its incarnation move together.** `Schedule` and
`CancelScheduled` tell the lease what they are about to do **inside their own transaction, before
they touch the row**, at the point where they already bump the in-process `ScheduleRunState`
(`ScheduleImplementation.kt:82`, `CancelScheduledImplementation.kt:41`). There is no later callback
that could arrive out of order.

```kotlin
// :sync-service-proxy commonMain
interface ScheduleRunLease {
    val isReal: Boolean                                         // false: AlwaysGrantedLease
    fun peek(key: String): RunLeaseState?                       // lock-free; null: nothing to say

    // Everything below is called only inside Database.transaction { } on a real lease.
    fun registrationChanging(key: String, change: RegistrationChange, nowMs: Long)
    fun tryAcquire(key: String, nowMs: Long, mode: AcquireMode): Acquire
    sealed interface Acquire {
        class Granted(val grant: Grant) : Acquire
        class Refused(val why: Why, val retryInMs: Long) : Acquire
    }
    enum class Why { HELD, NOT_YET, UNAVAILABLE }
    interface Grant {
        fun renew(nowMs: Long): Renewal                         // STOP: stop the run
        fun complete(outcome: RunOutcome, nowMs: Long, retryAfterMs: Long = 0)
        fun abandon(nowMs: Long)                                // the run has stopped; NOT_FINISHED
    }
    enum class Renewal { KEEP, STOP }
}
enum class RegistrationChange { NEW, REPLACED, CANCELLED, UNCHANGED }
enum class RunOutcome { DONE, FAILED, DEFERRED, NOT_FINISHED }   // Kotlin only, never wire
sealed interface AcquireMode {
    data class Cadence(val minIntervalMs: Long, val firstDelayMs: Long = 0) : AcquireMode  // "is it time yet?"
    data object Demand : AcquireMode                             // "someone asked for this now"
}
fun ScheduleItem.registrationFingerprint(): String               // pure; compared, never stored
fun waitMs(state: RunLeaseState, minIntervalMs: Long, firstDelayMs: Long, nowMs: Long): Long
```

Non-suspend: a row read and one atomic file write. House Rule 3 is untouched: no `Operation`
interface changes. ⚠ House Rule 9 is untouched too: the incarnation is a field of the lease file,
not of the row, and `Schedule`'s schema, parameters and return are as they are.

- ⚠ **A `null` from `peek` means "the lease has nothing to say", and never blocks a dispatch.** The
  caller carries on with its own in-process timing. It is what `AlwaysGrantedLease` always returns.
  The only thing that withholds a run is a `Refused` from `tryAcquire`, which `AlwaysGrantedLease`
  never returns.
- With `isReal` false the callers do **not** wrap anything in a transaction, and
  `registrationChanging` does nothing: the paths are the code on master (§6.3, I4).
- Every caller obtains its lease from `policy.leaseFor(key)` (§3.3). On iOS that is the real lease
  for a realm-wide key and `AlwaysGrantedLease` for an app-local one, so the previous point is also
  what happens to Calendar's reminder.

**How `Schedule` classifies its change.** Inside its transaction it reads the key's existing row,
which layer 1 has made current, and compares `registrationFingerprint()`, a hash of `opId` and the
`ScheduleSpec`, `ConstraintSpec` and `RetryPolicy` fields, of the old row and the new item:

| Existing row | `Schedule` reports | Why |
|---|---|---|
| none | `NEW` | includes a registration that follows a cancel, however identical |
| present, different fingerprint | `REPLACED` | |
| present, same fingerprint | `UNCHANGED` | the re-registration every process repeats at its start |

`CancelScheduled` reports `CANCELLED` when it finds a row and nothing when it does not. The
fingerprint is computed and compared in that one transaction and stored nowhere: the incarnation
is what persists. ⚠ A fingerprint alone could not tell "never touched" from "cancelled and
registered again with the same fields": a holder would renew a run that had been cancelled.

**`waitMs`, the one definition of "how long until this may run".** The acquirer, the loop's priming
(§6.1) and the planner (§7.4) all call it; none computes its own.

| Record | `waitMs` |
|---|---|
| never completed a run | `max(registeredAtMs + firstDelayMs, nextEligibleAtMs) - now` |
| has completed a run | `max(lastCompletedAtMs + minIntervalMs, nextEligibleAtMs) - now` |
| either | never below 0; the clock guards of §4.5 apply first |

⚠ A deferral leaves `lastCompletedAtMs` where it was. For a run that was due, that is already a
period or more in the past, so the first term has passed and the wait is the retry delay alone. A 12-hour pass
that asks to be tried again in 15 minutes waits 15 minutes.

The period is an **argument**, taken from the row just read, never a stored product of an old one.
So a key re-registered from 24 h to 15 min is due 15 min after its last run, at once if that has
passed. That is what the in-process loop does today for a key replaced while idle: it keeps the
elapsed time and reads the new period (`ScheduleDrainLoop.kt:207-215`, `:333-335`).

**States.** A state is derived from the record and the clock; it is not a stored field.

| State | Condition |
|---|---|
| `NO_RECORD` | no file, or a malformed one |
| `FREE` | `holder` empty |
| `HELD` | `holder` set and `0 <= now - renewedAtMs < TTL` |
| `HELD`, stopping | `HELD`, and the holder has learned that it must stop (the incarnation moved, its row is gone, or it was cancelled). **The record is identical to `HELD`**, which is the point: nobody can acquire until the holder's run has actually stopped |
| `STALE` | `holder` set and `now - renewedAtMs >= TTL`, or `renewedAtMs` in the future |

`HEARTBEAT = 10 s`, `TTL = 30 s`.

**Registration transitions.** Caused by `Schedule` and `CancelScheduled`, inside their transaction,
before the row is written or removed. **None touches `holder`**: a run in flight is never cut loose
by a registration change, it is told at its next heartbeat (T9).

| # | Change | Record before | Writes |
|---|---|---|---|
| R1 | `NEW` | any, or none | `incarnation + 1` (1 if there was no record); `registeredAtMs = now`; clears `lastCompletedAtMs`, `nextEligibleAtMs`, `lastOutcome`, `attempts`. A key registered after a cancel starts from its first delay as one that never ran |
| R2 | `REPLACED` | any, or none | `incarnation + 1`; clears `nextEligibleAtMs` (a deferral belonged to the old operation). Keeps `lastCompletedAtMs` and `registeredAtMs`, so the new period counts from the last run |
| R3 | `CANCELLED` | any, or none | `incarnation + 1`. Nothing else: the history is inert without a row, and R1 clears it if the key comes back |
| R4 | `UNCHANGED` | a record exists | nothing |
| R5 | `UNCHANGED` | no record (a row older than the lease, or a lease directory that was unusable) | creates it: `incarnation = 1`, `registeredAtMs = now` |

**Run transitions.** Every one, with who causes it. Each happens inside the store lock. "Anyone"
is any process, the holder included.

| # | From | Event | To | Writes |
|---|---|---|---|---|
| T1 | `NO_RECORD` | `tryAcquire` (anyone), the row having been read in the same critical section | as from `FREE` | first creates the record as R5 does, then T4 or T5 |
| T4 | `FREE` | `tryAcquire`, `Demand`; or `Cadence` with `waitMs = 0` | `HELD` | fresh `holder`; `holderApp`; `acquiredAtMs = renewedAtMs = now`. The grant remembers `incarnation` |
| T5 | `FREE` | `tryAcquire`, `Cadence` with `waitMs > 0` | `FREE` | nothing. `Refused(NOT_YET, waitMs)` |
| T7 | `HELD` | `tryAcquire` (anyone but the holder), either mode | `HELD` | nothing. `Refused(HELD, HEARTBEAT)` |
| T8 | `HELD` | heartbeat by the holder; `incarnation` is the grant's and the row is there | `HELD` | `renewedAtMs = now`. `KEEP` |
| T9 | `HELD` | heartbeat by the holder; `incarnation` has moved, or the row is gone | `HELD`, stopping | `renewedAtMs = now`. `STOP`: the holder cancels its run and **keeps renewing** until the run has stopped |
| T10 | `HELD`, stopping or not | `abandon` by the holder, from its run's completion handler | `FREE` | clears `holder`; `lastOutcome = NOT_FINISHED`. Nothing else |
| T11 | `HELD`, not stopping | `complete(DONE)` or `complete(FAILED)` by the holder; `incarnation` is the grant's and the row is there | `FREE` | `lastCompletedAtMs = now`, `lastOutcome`, `nextEligibleAtMs = 0`, `attempts + 1`; clears `holder` |
| T11d | `HELD`, not stopping | `complete(DEFERRED, retryAfterMs)` by the holder; `incarnation` is the grant's and the row is there | `FREE` | `nextEligibleAtMs = now + retryAfterMs`, `lastOutcome`; clears `holder`. **`lastCompletedAtMs` and `attempts` are not touched** |
| T11b | `HELD` | `complete` by the holder; `incarnation` has moved, or the row is gone | `FREE` | as T10: the run is not recorded as a completion |
| T12 | `HELD` | `TTL` passes with no `renew` (the holder crashed, was killed, or was suspended without quiescing) | `STALE` | nothing |
| T13 | `STALE` | `tryAcquire` (anyone) | as from `FREE` | first clears `holder` and sets `lastOutcome = NOT_FINISHED`, then T4 or T5 |
| T14 | `STALE` | `renew` by the old holder, resumed, and nobody has taken over | `HELD` | `renewedAtMs = now`, then T8 or T9 as the incarnation says. It is still the only runner |
| T15 | any, after a takeover or a wipe | `renew`, `complete` or `abandon` by a holder whose token is no longer in the record, or whose record is gone | unchanged | nothing. `renew` returns `STOP` and that run stops within one heartbeat |
| T16 | any | a lease call **cannot be completed**: the directory or the file cannot be read or written (§10.1, M4), the record's `key` is not the key asked for, the process is fenced (§5.5), or the store lock was not obtained within one `HEARTBEAT` | unchanged | nothing is written. What the caller gets is the table below: it is never "carry on" |

(T2, T3 and T6 of v0.7 were the `reconcile` step; R1 to R5 replace them.)

There is no transition that clears another process's live `holder`, none that writes a completion
for a run whose registration changed under it, and none that grants a key whose row is absent: the
caller reads the row in the same critical section and does not call `tryAcquire` without one.

**When a lease call cannot be completed (T16): fail closed.** One rule: a caller that cannot learn
or record the lease's state behaves as if it does **not** hold it.

| Call | Who makes it | When it cannot be completed |
|---|---|---|
| `tryAcquire` | a would-be runner | `Refused(UNAVAILABLE, HEARTBEAT)`: no run |
| `renew` (the heartbeat) | **the holder** | **`STOP`.** The run is cancelled. ⚠ `KEEP` here would let a holder that alone cannot reach its file run on while its record went `STALE` and a sibling took over: two runners with no end. A holder that cannot prove it still holds the lease has to assume it does not |
| `complete` | the holder, its run over | nothing recorded. The record stays `HELD` until the TTL, then T13. The job runs again later, which requirement 4 of §4.1 allows |
| `abandon` | the holder, its run over | the same: the TTL frees it |
| `registrationChanging` | `Schedule`, `CancelScheduled` | **the operation fails**, before it touches the row. Changing the row without moving the incarnation would hide a cancel from a holder |
| `peek` | a loop priming, the planner | `null`: no priming, and the planner assumes `now + initialDelayMs`. Nothing is run or withheld on the strength of a `peek` |

The only lease that answers "carry on regardless" is `AlwaysGrantedLease`, on platforms and for
keys where there is nobody to contend with (§3.3, §6.3).

**Steps.**

| Step | One critical section containing | Then, outside the lock |
|---|---|---|
| **Register** (`Schedule`) | Read the existing row; classify; `registrationChanging` (R1, R2, R4 or R5); write the row. | the scheduler hint: ask for a resubmission (§7.4) |
| **Cancel** (`CancelScheduled`) | Read the existing row; if there is one, `registrationChanging(CANCELLED)` (R3); remove the row. | the scheduler hint |
| **Acquire** | Read the key's `PENDING` row. None: stop, the key is not registered. Otherwise `tryAcquire`: T13 if `STALE`, T1 if `NO_RECORD`, then T4, T5 or T7. | `Granted`: dispatch **that row's** `opId`. `Refused`: wait `retryInMs`. |
| **Heartbeat**, immediately before dispatch and then every 10 s while the run's job is alive | Read the row; T8 or T9; or T15; or T16 if any of it cannot be done. | `STOP` (T9, T15, T16): cancel the run. The heartbeat goes on until the job has completed. |
| **End of a run**, from the run's completion handler, so only once the run **and the operation under it** have stopped (§6.1, `cancelOpsWithRun`) | Read the row; T11 if the run returned or threw, T10 if it was cancelled; T11b if the registration changed. | — |

⚠ **Order inside Register and Cancel: the incarnation first, the row second.** A process killed
between the two leaves an incarnation that moved for a row that did not change: a running holder
stops needlessly and the item runs again later. The other order would leave a changed row behind
an unmoved incarnation, which a holder cannot see. It is the same order the in-process guard
already uses, for the same reason (`ScheduleImplementation.kt:80-83`).

⚠ **What a cancel or a replacement can and cannot do.** It needs the store lock, so it commits
either before an acquisition's critical section or after it.

| The cancel or replacement commits | Result |
|---|---|
| before the acquisition | the acquirer reads no row, or the new row, and is granted under the new incarnation. Nothing obsolete is granted |
| after the grant, before or during the run | the key is `HELD` under the old incarnation. The runner's next heartbeat, the first of which is immediately before dispatch, is T9: the run stops and records nothing. A replacement becomes runnable once the old runner has abandoned |
| after the grant, and then the same item is registered again before the next heartbeat | two changes, R3 then R1: the incarnation moved twice. T9 all the same. With a content fingerprint this was T8, and the cancelled run carried on |

There is no other case: the read and the grant are not separated by anything, and a change and
its incarnation are not separated by anything.

The scheduler callbacks after the transactions (`platformScheduler.register` / `cancel`) are
**hints** that ask for a resubmission and write nothing. Nothing depends on their order or on
their arriving.

Who uses which mode:

| Caller | Mode |
|---|---|
| `ScheduleDrainLoop.tick` and `runDueNow` for a due item | `Cadence(minPeriodMs, initialDelayMs)`, both from the row just read |
| `SyncScheduler` tick because the poll interval elapsed | `Cadence(the interval this process just waited)` |
| The sync drain of a refresh or processing wake (§7.5 step 6) | `Cadence(the `sync-drain` item's period)`: 15 min under M3 |
| `SyncScheduler` tick because of a wake signal; `drainNow()`; the sync drain of a push wake (§10.2) | `Demand` |

A `Demand` run completes like any other and moves `lastCompletedAtMs`, so the pollers that follow
it skip. A gate inside the work itself (the 402 backoff, the metered gate, a schema fence) is not
the lease's business and still applies under `Demand`. ⚠ Without `Demand` a local save made one
second after a completed cycle could not push for an interval, however often it retried.

**Every way a run can end, and what it writes.** One table, the same for a registry item and for
the sync cycle. Every row also clears `holder`, except the two where the record is not this
holder's to write.

| How the run ended | Transition | `lastCompletedAtMs` | `nextEligibleAtMs` | `attempts` | `lastOutcome` | Next run, by `waitMs` | Today's loop |
|---|---|---|---|---|---|---|---|
| Returned | T11 | `now` | cleared | `+1` | `DONE` | a period from now | period reset (`ScheduleDrainLoop.kt:290-291`) |
| Threw | T11 | `now` | cleared | `+1` | `FAILED` | a period from now | "periodic: period reset, `attempts` +1" (`:57-58`) |
| Threw `ScheduledRunDeferred` | T11d | **unchanged** | `now + retryAfterMs` | **unchanged** | `DEFERRED` | after `retryAfterMs` | "fires again after its `retryAfterMs` instead of a full period, leaving the stored row untouched … `attempts` unchanged" (`:58-60`) |
| Stopped at a heartbeat: registration cancelled or replaced (T9) | T10 | unchanged | unchanged | unchanged | `NOT_FINISHED` | as before the run | new |
| Cut off: the wake's slice or budget ended, or the wake expired | T10 if the run stops in time, else left to T12, T13 | unchanged | unchanged | unchanged | `NOT_FINISHED` | as before the run | new: a cut-off run is not a run |
| Stopped by quiesce on leaving the foreground | the same | unchanged | unchanged | unchanged | `NOT_FINISHED` | as before the run | new |
| Returned or threw, but the registration had changed | T11b | unchanged | unchanged | unchanged | `NOT_FINISHED` | by the new registration | stands down (`:281-288`) |
| The heartbeat could not be completed (T16), or another holder took over (T15) | nothing is written by this holder | unchanged | unchanged | unchanged | unchanged until T13 | after the TTL, as before the run | new |
| Fenced by a wipe or a migration (§5.5) | nothing: the record is gone, or belongs to the new store | — | — | — | — | by the new store's registrations | new |
| The process crashed or was killed | T12, then T13 | unchanged | unchanged | unchanged | `NOT_FINISHED`, written by the successor | as before the run | — |
| Never started: `Refused` | none | unchanged | unchanged | unchanged | unchanged | `retryInMs` | — |

Only the first two rows move `lastCompletedAtMs`, and only the third sets a retry time. Everything
else leaves the schedule exactly where it was before the run.

⚠ **A completer never writes a period.** It cannot know the next caller's: the sync cycle is polled
at 30 s, faster while a stream is open, and at 15 min by a wake; a registry item's period can be
replaced while it runs. Each caller brings `minIntervalMs` to `waitMs` instead.

### 4.5 Clock

Wall clock, because the stamps must mean the same in every process and across a reboot, and periods
are minutes to a day. Two guards make a stepped clock harmless:

- a `renewedAtMs` in the future is treated as stale (the lease may be taken);
- a `lastCompletedAtMs` or `registeredAtMs` in the future was written by a clock that has since been
  set back. It is treated as absent, and `nextEligibleAtMs` with it, so the item is due. The rule
  needs no period, which `tryAcquire` is not given.

A clock set forward makes a live lease look expired and can cause one duplicate run, which
requirement 5 allows. A monotonic clock with a boot identifier was considered and dropped: it
removes that one duplicate and adds a second field, a sysctl read, and a reboot rule.

### 4.6 What `flock` guarantees in the group container

*Read:* `flock` is bound to the open file description, so two processes that each `open` the path
contend, and the OS drops a dead holder's lock (`FileAdvisoryLock.kt:22-25`,
`SharedReplicaDatabaseLock.kt:24-25`). Cross-process exclusion was tested with two handles in one
process on the simulator (`ios-drive-parity.md` §7).

*Not established:* exclusion between two real processes in a real App Group container on a device.
`docs/how-to/ios-signing.md` §5 describes the two-process check and accepts a desktop run as a
proxy; no record was found that it ran on a device, and no device build has had the App Group. S0's
verify step ("kill one, another sees its write") covers it. ⚠ Both layers now rest on it: the
generation of §5.5 and the lease of §4 are both written inside this lock. It is the first thing
S0b's device pass checks (§11.4).

### 4.7 The design, checkable

**Invariants.** Each is a property of the tables in §4.4, §5.3 and §5.5, and each names its tests
in §11.

| # | Layer | Invariant | Rests on |
|---|---|---|---|
| **I1** | 2 | **One runner per key.** At most one process is between a `Granted` and the stop of that run and of the operation under it. Two exceptions, both bounded. A holder that sent no successful heartbeat for `TTL` (it was suspended without quiescing, or its heartbeats failed and it was told to stop): it stops within one heartbeat of being able to run, but an operation that takes longer than the rest of the `TTL` to unwind after its cancel can overlap a successor. And a holder fenced by a wipe (§5.5): it may still be computing, and it can no longer write to the store. | T6, T7, T9, T10, T16; the end-of-run step |
| **I13** | 1 | **Nothing is written past a replacement.** Once the store has been wiped or migrated, no process writes a row through a handle opened before it. | §5.5, the fence |
| **I2** | process | **No lock without protection.** A thread is inside a file lock in the group container only while the process is in the foreground or holds a live protection token, and the last token's assertion ends only after the gate has closed and drained. No thread ever blocks in the kernel waiting for such a lock. | §5.3: Q1, Q2, the tried store lock |
| **I3** | 1 | **No stale base.** No store access in any process, read or write, runs on a resident base that lacks a transaction another process committed before that access took the store lock. It holds whichever job, app or entry point is running, and whether or not a lease is involved. | §5.5 |
| **I4** | both | **The defaults change nothing.** With `AlwaysGrantedLease` the loops behave exactly as on master, and layer 1 does not exist off iOS: no other platform's `DatabaseLock` or store is touched. | `isReal`; §6.3; §5.5 |
| **I5** | 2 | **The row decides, atomically.** No key is granted whose row is absent at the instant of the grant, and no run continues past a heartbeat, or is recorded as complete, after its registration was cancelled or replaced, even if an identical one was registered since. | rules A and B; R1 to R3; T9, T11b |
| **I9** | 1 | **No crash leaves a peer thinking it is current, and none leaves a destructive change that nobody finishes.** Whatever step a process dies after, every other process rehydrates or is fenced, and an intent left on disk is completed by the next process to take the lock. | §5.5, rule C and the crash table |
| **I11** | 2 | **Only realm-wide keys touch the lease files.** An app-local item is never refused, renewed or recorded by the file lease. | §3.3, `leaseFor` |
| **I12** | process | **Protection fails closed.** The gate is open exactly while the app is in the foreground or holds a live token. | §5.3, rule P |
| **I14** | wake | **The chain survives.** Every delivered background task leaves a request pending, on every exit path, unless the app is known to be unprovisioned. One stated gap: a process killed between a cancel and its resubmission, which can only happen if iOS rejects a second request instead of replacing the first (§7.4). | §7.5, step 0 and the exit table |
| **I10** | 1 | **Nothing changes the replica directory outside the protocol.** Every creation, replacement or removal of a file under `objects` happens inside the store lock, with the distrust step of rule C first: the generation for a row, a new `instance` for a wipe or a migration. | §5.5, paths table |
| **I6** | 2 | **A cut-off run is not a completion.** Expiry, quiesce, a lost lease and a crash leave `lastCompletedAtMs` where it was. | T10, T13 |
| **I7** | both | **One lock, one order.** The only cross-process lock anything here waits for is the store lock. The generation file and the lease files are read and written inside it and have no lock of their own. No lock is held across a run. | §5.5 "Lock order" |
| **I8** | 2 | **A completion sets nobody else's pace.** No completion writes a period. | T11; `waitMs` |

**Walks.** Each scenario, against the transitions and the invariants. A and B are two processes.

| # | Scenario | What happens | Holds |
|---|---|---|---|
| W1 | First registration on a device (A; first delay 10 min, period 24 h) | `Schedule`, one transaction: no existing row, R1, then the row. A's loop, first sight: `peek`, primed with `waitMs`. Due: one critical section reads the row and acquires (T4); heartbeat before dispatch (T8); run; T11. B starts later and re-registers the same item: `UNCHANGED`, R4, nothing moves. | I5, I8 |
| W2 | A cadence run already done by a sibling (B looks later) | B's first store access after A's run catches B up (§5.5); that is before anything here. B's first sight primes from `waitMs`. Due: T5, `Refused(NOT_YET)`, B re-primes with `retryInMs`. | I1, I3 |
| W3 | A demand: B saves locally one second after A's cycle | B's save is a store access: B is caught up first, then writes. Wake signal, `Demand`, T4, push, T11. | I3, I8 |
| W4 | A refusal while A is mid-cycle | B: T7, `Refused(HELD, 10 s)`. A demand retries each second and stays a demand; a cadence poll waits one heartbeat. | I1 |
| W5 | The row is replaced while B runs the old registration | A's `Schedule`: R2, `holder` untouched. B's next heartbeat: T9; B cancels, keeps renewing, and abandons when the operation has stopped (T10). A then acquires: T4, with `waitMs` on the new period. | I1, I5, I6 |
| W6 | The row is cancelled by another process | A's `CancelScheduled`: R3. Idle B: its next due critical section reads no row; nothing is granted. Running B: T9, T10. The record stays, `FREE` and unused, until R1. | I5, I6 |
| W6b | **Cancel, then an identical re-registration, inside one heartbeat of a running B** | R3 then R1: the incarnation is two ahead of B's grant and the run history is cleared. B's heartbeat: T9, T10. The new registration starts from its first delay; it does not inherit the cancelled one's `lastCompletedAtMs`. | I5 |
| W7 | A crashes after pulling rows and before `complete` | A advanced the generation before its first row write (§5.5), so B rehydrates at its next store access whether or not A finished. T12; B: T13, T4, runs. | I3, I6 |
| W8 | A wake expires mid-run | `expire()`: cancel, then `closeAndDrain(2 s)`, then the assertion ends (§5.3 step 5). A run that stopped in time abandoned (T10); one that did not cannot enter the store lock any more and is left to T12, T13. Either way no completion. | I2, I6 |
| W9 | A thread is waiting for the store lock, held by a peer, when the app expires | The waiter is in the tried loop of §5.3: not inside the lock, not counted. The gate closes and drains at once. The waiter's next attempt meets the closed gate and parks. When the peer unlocks, this process takes nothing. | I2 |
| W10 | A location relaunch stores a visit off the main thread | Process starts `CLOSED`. `withProtection`: token, gate opens, tried lock, catch-up, write (generation advances), release, quiesce, drain, assertion ends. No lease is involved. | I2, I3 |
| W11 | Android or desktop, default lease | `registrationChanging` does nothing. First sight: `peek` returns `null`, so no priming and no waiting. Due by today's accumulator: no transaction is opened, `tryAcquire` is `Granted`, dispatch as today. No heartbeat is started, nothing is cancelled, the completion writes the row as today. There is no generation file. | I4 |
| W12 | A holder is suspended without quiescing and resumes after another took over | Its heartbeat meets a foreign `holder` (T15); it stops within one heartbeat and records nothing. Its first store access on resuming caught it up. | I1's stated exception, I3, I6 |
| W13 | **A foreign write under a different key.** A's `sync-drain` pulls rows; B, last runner of `offlineKeeper`, acquires `offlineKeeper` | B's acquisition is a store-lock entry: the generation has moved, so B catches up before it reads the registry row, before the grant, and before the operation reads anything. Which key A ran is irrelevant. | I3 |
| W14 | **A cancel or replace between reading the row and acquiring** | Cannot be arranged: they are one critical section. The cancel waits for the lock and commits after the grant, which is W6's running case, bounded by the heartbeat taken before dispatch. | I5 |
| W15 | **A long catch-up against the TTL** | Catch-up runs at lock entry, before the row is read and before `acquiredAtMs` is written. Between the grant and the dispatch there is one heartbeat and nothing else. The TTL is never running during a catch-up. | I1 |
| W16 | **A user's write in a second foreground app** (Split View), no job involved | The same as W3 without the lease: the write's store access catches the process up first. v0.6 had to exclude this case from its I3. | I3 |

| W17 | **A process dies right after advancing the generation, before writing any row** | Peers see a generation they do not hold and rehydrate, finding nothing changed. Wasted work, never a stale base. The reverse order would have left changed rows behind an unmoved generation. | I9 |
| W18 | **Control wipes the store while Photos has it open and is running `offlineKeeper`** | Rule C: under the store lock, the wipe writes its marker, mints a new `instance`, deletes rows and lease records, removes the marker. Photos, at its **very next store access**, heartbeat or not: lock; if the marker is still there the wipe was cut short, so Photos mints a new `instance` and finishes it; then Photos finds `instance` is not its own and is **fenced**: that access and every later one through the old handle reads nothing and writes nothing, its grants are void, its run is cancelled, its runtime is rebuilt. It cannot re-save a row from the realm that was wiped, and a registration made after the wipe gets its grant without a second writer existing. | I9, I10, I13 |
| W22 | **A holder alone cannot read its lease file** | Its heartbeat cannot be completed: T16, `STOP`. The run is cancelled. The record stays `HELD` until the TTL; a sibling then takes it (T13). The holder was told to stop 20 s or more before that. | I1 |
| W23 | **A `dedupKey` of 300 bytes** | Its lease file is 70 bytes of name like any other; the record's `key` is checked on every read. | — |
| W24 | **A refresh task is delivered after a reboot, before the first unlock, to an app that was not running** | Step 0: provisioning is `UNKNOWN`, so the fallback request is submitted, with no runtime and no store. Step 2: a token. Step 3: protected data is unavailable: exit, `finished = false`. A request is pending; the next wake, after the unlock, does the work. | I14 |
| W20 | **iOS refuses the assertion as the app enters the background** | `acquire` returns `null`. Rule P: the loops are stopped and the gate is closed and drained all the same, with no assertion to end afterwards. | I12 |
| W21 | **Calendar's reminder is due and the lease directory is unusable** | `leaseFor("tenshu/calendar/nextReminder")` in Calendar is `AlwaysGrantedLease`: the reminder is delivered. In Control the key does not run at all. The file lease is never asked. | I11 |
| W19 | **The container outlives an uninstall and an app is installed again; or something outside this design deletes the generation file or the lock file while processes are running** | A surviving file is adopted as it is. Nothing here unlinks either file (rule D). If something else does, a running process's descriptor still names the old inode: at its next lock entry `fstat` shows a link count of 0, and the handle is fenced before it reads a stale generation or trusts a lock that excludes nobody. The first process to open the path afterwards creates the file with a new random `instance`. | I9, I13 |

**Removed in v0.7, and what stands in its place.**

| Removed | Was for | Now |
|---|---|---|
| The lease guard `guard.lock` | serialising lease writes | the store lock, already held (§4.2) |
| `ScheduleRegistryAuthority`, `current(key)`, the targeted rehydrate, the minute `refreshAll()` | reading a registry row fresh despite a stale cache | layer 1: every store access is on a current base |
| `mustRehydrate`, `acquireSeq`, `lastAcquirerProc` | rehydrating after another process ran the same key | layer 1, for every key and every write |
| Reconciling a fingerprint read under one lock into a record under another | keeping the lease in step with the row | rule A: one critical section. v0.8 then drops the stored fingerprint for an incarnation (rule B) |
| The explicit rehydrates: in the wake (§7.5), in the two drivers' Kotlin entries, on every foreground transition | each entry remembering to refresh | layer 1. The existing calls become redundant and are deleted in S1c |
| M2's "the skipping process rehydrates", and its measurement gate | a second foreground app seeing a sibling's pull | layer 1 |

## 5. ⚠⚠ The shared store: two rules every process keeps

A process sharing the App Group container owes its siblings two things, and neither is about
scheduling: it must never be **suspended holding a lock** there (§5.1 to §5.4), and it must never
**read or write from a stale base** (§5.5, layer 1). Both are built in sub-phase S0b, before the
App Group build is used on a device (§12).

### 5.1 The suspension rule

*Platform:* iOS terminates an app that is **suspended while it holds a file lock on a file in a
shared container**. The crash report carries the termination code `0xdead10cc`. Apple documents it
for file locks and SQLite locks and tells apps to finish under a background task assertion. Whether
it applies to BSD `flock` as well as to `fcntl` record locks is **not confirmed here**, and §11
makes confirming it the first device step of S1.

### 5.2 What the code does about suspension today: nothing (read)

- Before this document the string `dead10cc` appeared nowhere in the repository, including
  `docs/tech-debt.md`.
- No code requests background time: there is no `beginBackgroundTask` and no
  `performExpiringActivity` call in any Kotlin or Swift source.
- `SharedReplicaDatabaseLock.withLock` takes `flock` with a **blocking** `lock()` on every outermost
  store access and holds it for the block (`SharedReplicaDatabaseLock.kt:49-63`). Some blocks are
  long: a rehydrate, a wipe, a sweep page (`FileObjectStore.kt:206` notes one that "stalled every
  co-resident first-party app").
- Nothing stops the loops when an app leaves the foreground. The `.background` handlers only arm
  the next wake (`RootView.swift:46`, `ControlIosApp.swift:310`, `FitIosApp.swift:116`, `:179`).
  `ScheduleDrainLoop` keeps reading the store once a second and `SyncScheduler` keeps ticking until
  iOS suspends the process.
- `HlcWatermarkFile` is the one place that reasons about suspension. It says a suspended holder
  *keeps* its lock (`HlcWatermarkFile.kt:58-61`) and therefore only tries. In a shared container the
  documented outcome is termination instead. Its try-only behaviour is right under either reading.

So `SharedReplicaDatabaseLock` does **not** deal with this. It has not mattered because the lock
file has been in a shared container only on the simulator, where apps are not suspended this way.

⚠⚠ **This is a live hazard that S0 exposes, independent of the lease.** The day the App Group is on
a device build, any app backgrounded while a store transaction is in flight can be killed. S1's wake
runner makes it more likely, because it adds background execution that ends in suspension. The fix
is therefore sub-phase S0b (§12), which the App Group build must contain before it is used on a
device.

### 5.3 What the design does about suspension

**Invariant Q** (I2 in §4.7), in two halves an implementer can test:

- **Q1.** A thread is inside a file lock in the group container only while the process is in the
  foreground or holds at least one live *protection token*. No thread ever *blocks* waiting for
  such a lock: every `flock` in the container is a non-blocking try.
- **Q2.** The last token's assertion is ended, and any completion handler iOS is waiting on is
  called, only after the gate has been closed and has drained.

So once the last token is released, **no code path in this process holds or can take a
shared-container lock without first obtaining protection again**, whatever is still running or is
resumed later.

Stopping work cannot provide that, which is why Q is stated about the lock and not about the loops:

- A scheduled operation is not a child of the run that dispatched it. `executeAsync` with no
  evaluation scope substitutes the session scope (`ExecutionContext.kt:571-575`) and the operation
  runs as `scope.async` on it (`:474-475`, `:515-523`). `ScheduleDrainLoop.stopAndAwait()` cancels
  and joins the run, which stops *waiting*; the operation carries on (`ScheduleDrainLoop.kt:391-401`).
- A background operation is `sessionScope.launch`, detached, with nothing to await
  (`ExecutionContext.kt:457-470`).
- The session scope also carries work no loop owns: view-model reads, the emit consumer, the
  health collectors.

Any of these can take the store lock after the loops have stopped. So the mechanism is a gate
every lock passes through, opened only by protection. Stopping and cancelling make the gate drain
quickly; they are not what keeps Q.

**1. The lease never holds a lock across a run** (§4.2).

**2. Protection tokens.** `BackgroundQuiesce`, in `:tenshu-sdk-ios`:

```kotlin
object BackgroundQuiesce {
    fun install()                                    // from registerAtLaunch (§7.2)
    fun acquire(reason: String): Protection?         // any thread; null: iOS grants no time
    suspend fun <T> withProtection(reason: String, block: suspend () -> T): T?
    interface Protection { fun release() }           // idempotent
}
```

| | |
|---|---|
| What a token is | One `beginBackgroundTask(expirationHandler:)` assertion. *Platform:* it may be begun from any thread, and returns an invalid identifier when iOS will grant no background time; `acquire` then returns `null` and the caller does not touch the store. |
| `acquire` | Begins the assertion, adds the token to the live set, opens the gate. |
| `release`, other tokens still live, or the app is in the foreground | Ends this token's assertion at once. The gate stays open under the others. |
| `release` of the **last** token while not in the foreground | Runs quiesce (below), and ends the assertion only when it has returned. |
| The foreground | Counts as protection without a token. Leaving it is rule P, below. |
| Why every token has its own assertion, even inside a wake that iOS already granted time for | One rule for every entry, and Q2 needs nothing but "end the assertion last". The event's own completion handler is called after `release` returns (§5.4). |

**3. `SharedContainerLockGate`**, one per process, in `:core-common` iosMain beside the locks it
guards. It knows nothing of UIKit: `BackgroundQuiesce` hands it a `protect: () -> Boolean`.

| | |
|---|---|
| State | `OPEN` or `CLOSED`, plus the number of threads currently *admitted*. Default `OPEN` with no `protect`, so tests and any process that never calls `install()` behave as today. |
| What goes through it | Every `FileAdvisoryLock` in the group container. There are two: the outermost entry of `SharedReplicaDatabaseLock.withLock`, and `HlcWatermarkFile.raiseUnderLock` (`HlcWatermarkFile.kt:109-124`). The lease has no lock (§4.2). A nested entry on a thread already inside passes: it belongs to an access the gate is waiting for. |
| One attempt, the same for both locks | **Admit** (count up; only possible while `OPEN`), then `tryLock()`. Got it and the gate is still `OPEN`: run the block, unlock, count down. Got it but the gate closed meanwhile: unlock at once, count down, and treat as not admitted. Did not get it: count down. A thread is therefore counted only from admission to release, and holds a lock only while counted. |
| The watermark's tried lock | One attempt, with its existing retries (5, 20 ms apart). Not admitted or not obtained: reported as unavailable, and it merges without persisting (`HlcWatermarkFile.kt:104-105`). |
| The store lock | ⚠ **No longer `fileLock.lock()`** (`SharedReplicaDatabaseLock.kt:52`). It repeats the attempt, sleeping between tries (1 ms, doubling to 20 ms), until it has the lock. A thread waiting for a peer is between attempts: not counted, not in the kernel, and it meets the gate again on every try. After 10 s of waiting it logs `[store-lock] waiting on a peer` once. |
| Not admitted, the store lock, **any thread** | Call `protect()`. Granted: an *implicit* token now exists, the gate is open, try again. |
| … refused, off the main thread | Park on the gate's condition, holding no lock, until `OPEN`. After 1 s parked in a running process, log `[gate] parked without protection: <label>` once. |
| … refused, on the main thread | Wait 250 ms and call `protect()` again. Never park. |
| `closeAndDrain(deadlineMs)` | Sets `CLOSED`; from that instant nothing is admitted without `protect()`. Waits until the count is zero or the deadline passes. Returns `Quiesced` or `TimedOut`. |
| `open()` | Called only by `BackgroundQuiesce`, when protection exists: a token was acquired, or the app entered the foreground. |

⚠ **Why the store lock must be tried, not waited on.** A thread admitted by an open gate and then
blocked in `flock` behind another process is invisible to a drain: this process holds nothing, so
the drain would time out or, worse, be told to ignore it. When the peer later unlocks, the kernel
hands the lock to that thread with the gate long closed and no assertion left, which is exactly the
state Q1 forbids. With a tried lock there is nobody in the kernel to hand it to.
`HlcWatermarkFile` tries for the same reason (`HlcWatermarkFile.kt:56-64`). The cost is fairness:
a waiter can be overtaken. Store accesses are bounded at 1 s (step 5), so the wait is too.

- `protect()` is `acquire("implicit")`. The implicit token is released when the count has been zero
  for 250 ms, so it does not outlive the access that asked for it. iOS bounds the total: assertions
  taken in the background share one allowance.
- After an **expiration** (step 5) `protect()` refuses threads off the main thread until an explicit
  `acquire` succeeds or the app enters the foreground. Without that latch, residual work would
  re-take an assertion iOS has just withdrawn, be expired again, and spin until suspension.
- ⚠ **The main thread must never wait for a signal only the main thread can deliver.** The foreground
  notification is posted on the main thread, so a main thread parked until `open()` would never see
  it and the app would be killed by the launch watchdog. Retrying `protect()` needs no notification:
  after a resume into the foreground `beginBackgroundTask` succeeds, the gate opens, and the
  notification is then delivered and ends that token.
- ⚠ The gate does not stop work; it makes work carry protection. Leftover work after a release
  re-opens the gate under its own assertion and finishes. That is what Apple asks of a lock holder,
  and it is why quiesce also stops the loops: so that nothing *starts* more.

**Rule P: protection fails closed.**

> The gate is `OPEN` **exactly** while the app is in the foreground or the live-token set is not
> empty. Whenever that stops being true, for whatever reason, the process **shuts down**: the loops
> are stopped and the gate is closed and drained. Nothing else opens the gate and nothing leaves it
> open.

"Shut down" is steps 2 to 4 of the quiesce table below. It needs no assertion to run; an assertion
only gives it time. Every way protection can be refused or lapse, and where it lands:

| Event | Protection at that moment | What runs |
|---|---|---|
| The app enters the background and `acquire` is granted | the new token | release it: quiesce steps 1 to 5, `drainMs` 10 s |
| The app enters the background and `acquire` returns `null` | **none** | shut down at once: steps 2 to 4 with `drainMs` 2 s. There is no assertion to end. *Platform:* suspension may arrive during it; whatever is inside a lock then is the `TimedOut` case of step 5, and nothing could have prevented it |
| The last token is released (end of a wake, of an entry of §5.4, of an implicit token) | that token, until step 5 | quiesce steps 1 to 5 |
| An assertion or a task expires | all tokens, for a moment longer | step 5's expiration path: shut down with `drainMs` 2 s, then end every assertion |
| An explicit `acquire` returns `null` in the background (a wake, an entry of §5.4) | none, and the gate is already `CLOSED` | nothing changes: the caller does no store work and reports failure |
| `protect()` is refused at the gate | none, gate `CLOSED` | the thread parks, or on the main thread retries (§5.3 step 3) |
| Taking a token throws | none | treated as `null`: the two rows above |
| A process is launched in the background | none | it starts `CLOSED` with its loops stopped (step 6) |

Each row ends with the gate closed and drained and the loops stopped, or with the gate never
having opened.

**4. Quiesce.** What the release of the last token does:

| # | Step | Why |
|---|---|---|
| 1 | Protection is held: the token being released has not ended its assertion yet. | Everything below needs time. |
| 2 | Cancel: `ScheduleDrainLoop.stop()`, `SyncScheduler.stop()`, the wake runner's job. On iOS the loop dispatches each operation with `withEvaluationScope(<the run's scope>)` (`ExecutionContext.kt:272`), so the operation is a child of its run and the cancel reaches it. | So nothing starts more work. ⚠ `stop()`, not `stopAndAwait()`: a run parked at the closed gate would never join. |
| 3 | Wait up to 1 s for the cancelled runs to stop. Each abandons its own grant from its completion handler as it stops (§4.4, T10). | Siblings need not wait out the TTL. ⚠ Quiesce abandons nothing itself: a grant given up while its operation is still unwinding would let a second runner start (I1). A run that has not stopped keeps its lease until the TTL. |
| 4 | `gate.closeAndDrain(drainMs)`. | **Q1 from here on.** |
| 5 | Only now end the assertion, then return to the caller, which calls any completion handler iOS is waiting on. | **Q2.** |

- `drainMs` is 10 s on entering the background and 2 s at the end of a wake (§7.5 has the budget).
  It normally returns in milliseconds.
- Entering the foreground (`UIApplicationWillEnterForegroundNotification`, observed in Kotlin, so no
  app needs Swift for it): `open()`, restart both loops. No rehydrate: the first store access
  catches the process up (§5.5). Each loop restarts after `stop()`
  (`ScheduleDrainLoop.kt:133-158`, `SyncScheduler.kt:705-707`, `:2545-2551`).
- The loops run only in the foreground. A token does not start them; a wake drives its work
  explicitly (§7.5). ⚠ One exception, for the sub-phase order only: §12, S0b-2.
- The scoped dispatch in step 2 is a parameter of `ScheduleDrainLoop`, off by default. On Android
  and desktop a stopped loop keeps today's behaviour, where the operation runs on.

**5. When time runs out with work still in flight.** An assertion's expiration handler, and a
`BGTask`'s, run steps 2, 4 and 5 for every live token at once, synchronously, with `drainMs = 2 s`
and without step 3's wait: there is no time for it, and a grant not abandoned is freed by the TTL. It does not skip step 4 and it does not end an assertion first. Every token is
then dead: a later `release` on it does nothing.

| `closeAndDrain` returns | State of the process | Then |
|---|---|---|
| `Quiesced` | No thread is inside a lock. An operation still alive is either computing, or suspended and will ask for protection at its next store access and be refused. Q holds. | End the assertions. Suspension is safe. |
| `TimedOut` | One thread is still inside a single store access that outlasted the cancel and the 2 s. | Log `[quiesce] TIMED OUT holder=<label> heldMs=<n>`, end the assertions. *Platform:* iOS is then expected to terminate the process, and the OS frees its lock. |

Why `TimedOut` ends in the process's death and not in something gentler:

- Unlocking from the handler while the thread is mid-access would let the process survive. But the
  frozen thread would later resume and finish its writes with no exclusion, from a base a sibling
  has since changed: the stale-write hazard of `ios-drive-parity.md` §11. A killed process cannot
  do that. A store access cut short by death is already a case the store must survive.
- Keeping the assertion past expiration does not help: *platform*, iOS terminates an app that does.

So Q is exact for a process that survives, and the one way out is bounded and loud. To keep it
rare, **no outermost store access may hold the lock longer than 1 s**. The gate logs every hold
over 500 ms with the caller's label. The known long holders are a full rehydrate (§5.5: about
0.2 s at 107,000 rows, inferred), a wipe and a sweep page (`FileObjectStore.kt:206`). A sweep must
work in pages that release between them. A wipe and a rehydrate cannot be split, because a peer
must not see them half done; they are measured first. S0b
measures this on the maintainer's store (§11.4 step 0).

**6. A background-launched process starts closed.** `install()` opens the gate only if the app is
not in the background. Otherwise the gate is `CLOSED` and its first store access, on whichever
thread, gets protection through an explicit token (§5.4) or through `protect()`. Its runtime is
built with the loops stopped: `initSyncService` already takes `startBackgroundLoops`
(`SyncProxyInitialization.kt:384-390`), and the iOS module passes `false` when the app is in the
background at assembly.

### 5.4 Every way into the process, and where its protection comes from

A foreground launch and a `BGTask` wake are two entries among several. Each row says where the
token is taken and what ends it. *Read* cites the code that exists; the last column is the design.

| Entry | Code today (read) | Thread of the store access | Token taken | Token released, then |
|---|---|---|---|---|
| Foreground launch or resume | every app | any | none needed: the foreground is protection | on entering the background: a token is taken and released, which quiesces |
| `BGAppRefreshTask` (S1), `BGProcessingTask` (S2) | the three drivers (§12) | the task's queue, then coroutines | `TenshuBackgroundWake.run`, step 2 (§7.5) | step 9; then `setTaskCompleted` |
| Silent push (S4) | none yet | the app delegate's callback, then coroutines | the same `run(PUSH)` | step 9; then the fetch completion handler |
| Background `URLSession` relaunch (Drive) | `DriveIosApp.swift:299-307` stores the completion handler; `BackgroundUpload.swift:64-72` reports each transfer off the main thread; `:74-81` calls the handler | the session's delegate queue | in `handleEventsForBackgroundURLSession`, before `reattach()` | in `urlSessionDidFinishEvents`; then the stored completion handler |
| A transfer completing while Drive is merely running in the background | `BackgroundUpload.swift:64-72` → `DriveLocalRuntime.onBackgroundUploadCompleted` (`DriveLocalRuntime.kt:333-335`) | the session's delegate queue | `onBackgroundUploadCompleted` wraps its body in `withProtection` | when the body returns |
| Visit or significant-location relaunch (Timeline) | `IosLocationCapture.kt:139-161`; the runtime is rebuilt at launch so the event is received (`TimelineIosApp.swift:29`) | the location manager's thread, then coroutines | the capture handler wraps what it persists in `withProtection`; building the runtime at launch is on the main thread and goes through `protect()` | when the write returns |
| Workout recording in the background (Fit) | continuous location, pedometer and a Bluetooth strap (`IosRecordingEngine.kt:200`, `:317-322`, `:362-384`; `fit-ios/project.yml:132-134`) | delegate callbacks, then coroutines | each store write wraps in `withProtection`. ⚠ Not a token held for the whole workout: an assertion expires after about 30 s, and iOS keeps the app running for the location session anyway | when the write returns |
| Notification action handled without opening the app (Drive's new-folder prompt) | `DriveIosApp.swift:290-292` | main | `withProtection` around the handler's work | when it returns; then the response's completion handler |
| HealthKit background delivery | none: no `enableBackgroundDelivery` or `HKObserverQuery` in the code. Fit's import rides its refresh task | — | when built: `withProtection`, then HealthKit's completion handler | — |
| Photo-library change observer | none: deferred (`DriveBackupSurface.kt:41`) | — | when built: `withProtection` | — |
| Anything not listed, on any thread | — | any | `protect()` at the gate, an implicit token | 250 ms after its access ends |

- The last row is what makes a forgotten entry safe instead of stuck: it still gets protection. The
  explicit tokens above exist for what the implicit one cannot do: hold protection across a
  *sequence* (a batch of transfer events, a whole wake) and call iOS's completion handler only
  after the drain.
- ⚠ **Not traced:** whether Fit's recorder and Timeline's capture write to the shared store while in
  the background, or only to their own files (a recorder outbox, a raw fix journal). If only to
  their own files, their rows cost nothing. S0b-2 must read both before wiring them.
- An entry whose `acquire` returns `null` does no store work and reports failure to iOS where there
  is a handler to report it to. It must not fall back to running unprotected.
- ⚠ **A consequence for Fit.** Today the loops keep running for as long as iOS keeps the app alive,
  so a workout recorded in the background keeps syncing throughout. With S1 a backgrounded app's
  loops are stopped whatever keeps it alive, and it syncs at its next wake or in the foreground.
  The recording itself is unaffected.

### 5.5 Layer 1: the store protects itself against a stale base

**The hazard, in the store's own words** (`FileObjectStore.kt:280-285`): "a co-resident app's later
direct disk writes are invisible to a LIVE process until it re-reads the directory; worse, a stale
cache is a *write* hazard (saving an entity from a stale base clobbers the other app's rows)."

**What exists today (read).**

| Fact | Where |
|---|---|
| One file per row, named by its hash. A write is one synchronous, atomic file write. | `FileObjectStore.kt:93-107`, `:336-340` |
| A process holds `present`, the set of hashes on disk, built from one directory enumeration with no file opened, and `cache`, the rows it has read through. Rows over the large-row threshold are served and not kept. | `:77-79`, `:109-110`, `:151-169`, `:623-631` |
| Nothing on disk says that anything changed: no log, no version, no sequence. | whole file |
| **Every** database call, read or write, takes `DatabaseLock.withLock`; `transaction` is the same lock held for a block. | `DatabaseImpl.kt:80-97`, `:116`, `:324`, `:519` |
| On iOS that lock is an in-process recursive lock plus the cross-process `flock`, taken on the outermost entry and released on the outermost exit. | `SharedReplicaDatabaseLock.kt:49-63` |
| The only catch-up is `rehydrateFromDisk()`: enumerate every name, diff against `present`, re-read **every resident row** and compare its bytes. "O(resident), not O(dataset)" for file reads; the enumeration is O(dataset) names. | `:300-330`, `:294-298` |
| It is called by convention: on each app's foreground transition, off the main thread and detached; and first thing in the two wake entries. | `ControlIosApp.swift:301-303`, `FitIosApp.swift:134`, `RootView.swift:87`, six more; `DriveLocalRuntime.kt:353`, `FitLocalRuntime.kt:232` |
| A targeted primitive exists: `invalidateCached(hashes)`. | `:259-261` |

So today a stale base is avoided only if (a) every entry remembers the call, (b) nothing writes
before a detached rehydrate lands, and (c) no second process writes while this one is live. (c)
fails with two apps in the foreground, with a wake in one app while another is in use, and with a
background recording. The three existing drivers already produce the second case.

**Is the HLC watermark the generation?** No, but it is the model. `HlcWatermarkFile` is a
cross-process, monotonic value beside the replica. It cannot serve: it advances with observed HLCs,
and a local-only or derived row write moves no HLC; and its `raiseTo` is allowed to persist nothing
when its lock is busy (`HlcWatermarkFile.kt:104-105`), which a generation must never do. What is
taken from it: the co-location rule (`:45-51`), lenient decoding (`:171-185`), and living in
`:core-common` iosMain where `iosTestCheck` runs (`:32-40`).

**The design.** One file beside `objects`, resolved by `sharedReplicaDirectory`, and one value in
each process.

| | |
|---|---|
| `replica.generation` | 16 bytes: `instance`, `generation`. Read and written **in place** with `pread` / `pwrite` on a descriptor opened once. ⚠ Never deleted, never replaced by a rename (rule D below). |
| `generation` | A counter, `+1` for every critical section that writes or removes rows. It says **"your base is behind"**. |
| `instance` | A random 64-bit value. It says **"this is not the store you opened"**. Minted when the file is created, and again by every change that **replaces** the store rather than adding to it: a wipe, a migration. Random, so that no later value can reproduce an earlier one. |
| `replica.wiping` | A marker: a wipe was started and may not have finished. |
| In each lock handle | `base`: the 16 bytes its resident base reflects. Set under the lock when the store is opened, when the cache is empty and therefore current. A runtime has one handle and one store object; rebuilding the runtime makes new ones. |

| Moment | What happens | Added cost |
|---|---|---|
| Outermost entry of `withLock`, once the `flock` is held | Rule D's check on both descriptors, then `pread` the 16 bytes. Equal to `base`: go on. A short file counts as a different `instance`. | one read of 16 bytes from an open descriptor, on **every** outermost store access |
| … an intent exists (`replica.wiping`, or a staged migration's commit marker) | **Complete it** by rule C below, then compare as in the next two rows. | rare |
| … `instance` differs | **Fence** this handle (below). The store was replaced under it. | rare |
| … only `generation` differs | **Catch up**: `rehydrateFromDisk()` (`:300-330`), then set `base` to the file's value. | a full rehydrate; below |
| First row write or removal of the critical section | `pwrite` all 16 bytes, with `generation + 1`, **before** the row is touched. ⚠ **Anything but 16 bytes written is a failure:** the store throws, `base` is not moved, and **no row is touched** in this critical section. Only a complete write lets the mutation proceed and sets `base`. | one write of 16 bytes per write transaction |
| Outermost exit | Unlock. | none |

The same holds for minting an `instance` in a wipe or a migration: a write that is not all 16
bytes stops the change there. Its intent is already on disk, so the next process to take the lock
mints one itself and completes it (rule C).

**Rule D: a file somebody holds a descriptor on is never unlinked and never renamed over.**

A descriptor names an inode, not a path. If the file at the path is deleted or replaced, a process
that already has it open goes on reading the old inode: it would see the old `instance` and
`generation` for ever and never be fenced, and a `flock` on an unlinked inode excludes nobody.

| Container file | Descriptor kept open? | How it changes | Guard |
|---|---|---|---|
| `replica.writer.lock` | **yes**, for the life of the process (`SharedReplicaDatabaseLock.kt:24-25`) | never written at all | never unlinked. Created with `open(O_CREAT)`, which leaves an existing file alone. ⚠ Today it is created by `fileExistsAtPath` then `createFileAtPath` (`FileAdvisoryLock.kt:66-73`): two processes starting together can both find it missing, and the second create can replace the first's file (*inferred* from `createFileAtPath` overwriting; not tested), after which they lock different inodes. S0b-1 changes the creation |
| `replica.generation` | **yes** | `pwrite` in place, all 16 bytes | never unlinked, never renamed over. Created with `open(O_CREAT)` under the store lock and filled in place; an empty or short file is "a different `instance`" to everyone, the creator included, until 16 bytes are there |
| Row files, version sidecars | no: opened, read or written whole, closed (`FileObjectStore.kt:151-155`, `:338-340`) | atomic rename | none needed: every reader opens the path anew |
| Lease records | no (§4.2) | atomic rename | none needed |
| `replica.wiping`, the migration's commit marker | no: tested by path at each lock entry | created, removed | none needed |
| `sync-hlc/watermark.state` | no: read by path (`HlcWatermarkFile.kt:95`) | atomic rename (`:146`) | none needed |
| `sync-hlc/watermark.lock` | no: opened, tried, closed in one call (`:110-123`) | never written | the creation race above applies to its first use; the same `open(O_CREAT)` fixes it |
| `files/…` (`IosFileDatabase`) | no: a handle per call | atomic rename, or ranged writes | outside layer 1 (paths table) |

**The check, at every outermost lock entry, before the lock is trusted:** `fstat` the lock
descriptor and the generation descriptor; a link count of 0 means the file was unlinked or renamed
over behind this process. Nothing in this design does that, so it is a defence against a path that
should not exist: a debugging tool, a future wipe written without this rule. On finding it, the
handle is **fenced** (below) without running the caller's block; the rebuilt runtime opens the
paths again. Two `fstat` calls per store access, *inferred* to cost about what the `pread` does.

So a generation file is never "recreated" while anything is running. It is absent exactly once per
container: before the first process ever opens it.

**The fence.** A handle that finds a different `instance` is dead, for good:

| | |
|---|---|
| The access that found it, and every later one through this handle | Reads return "absent". Writes and removals throw a typed `ReplicaReplaced` error **before** touching a file. The lock is released at once |
| Every grant this process holds | void: `renew` answers `STOP` without reading a file, `complete` and `abandon` do nothing. The runs are cancelled. The records were removed by the wipe, or belong to the migrated store |
| The loops | stopped, as in rule P's shutdown (§5.3) |
| The runtime | rebuilt: a new graph with a new handle and a new store object, which adopts the new `instance` with an empty cache. The old graph is orphaned until the process dies, which is what `deprovision` already does to a graph it seals (`DriveLocalRuntime.kt:390-407`) |

- ⚠ It is immediate. A holder does not learn of a wipe at its next heartbeat, up to 10 s later, but
  at its **next store access**, which is the only thing it could do harm with.
- ⚠ Reads return "absent" and do not throw, on purpose. A Kotlin exception crossing into Swift
  through a function not declared to throw ends the process; a read on a store that was just wiped
  finding nothing is also simply true. A write must not silently vanish, so it throws, and it does
  so inside an operation, where the executor turns it into a failure.
- The process that performs the wipe or the migration is not fenced by its own change: its handle
  adopts the `instance` it minted. A process that **completes someone else's** intent is: it too
  was built on the store that is gone.
- ⚠ Not designed here: how each runtime rebuilds itself (`ControlSurface`, each `*LocalRuntime`).
  The precedent is `deprovision` followed by `install`; S0b-1 has to make it one step.

- **Advance first, so a crash is safe.** The generation is written before the first row. A process
  killed mid-transaction leaves a generation that moved for rows it may not have reached; a peer
  rehydrates and finds what is on disk. The opposite order would leave rows changed with nothing
  to say so.
- **No `fsync`.** The generation only has to be agreed between live processes. After a reboot every
  process starts with an empty cache and adopts whatever the file says.
- The check is on **every** outermost access, reads included, not only before writes. A read on a
  stale base feeds a later write, and one 16-byte read is cheaper than deciding which reads matter.
- A catch-up collapses any number of foreign writes into one rehydrate: it is paid per *access that
  follows a foreign commit*, not per foreign commit.

**What it costs.**

| | Today | With layer 1 | Basis |
|---|---|---|---|
| A store access that finds nothing changed | the lock | + one 16-byte `pread` | *inferred* to be microseconds; not measured |
| A write transaction of k rows | k atomic file writes | + one 16-byte `pwrite` | *inferred* negligible beside k create-write-rename sequences; not measured |
| A catch-up | `rehydrateFromDisk()`, on each foreground transition only | the same call, whenever an access finds the generation moved | **about 0.2 s at 107,000 rows, inferred** from the figure below |

⚠ **The one measured number, and what it is.** Indexing a 107,368-row store took **208 ms** on the
maintainer's iPhone (`store.readHashes rows=107368 ms=208`, from the Drive launch-time work that made
`TombstoneRegistry` a point lookup; the log line is the maintainer's and is not in the repository). That is the directory
enumeration, which a rehydrate also does (`FileObjectStore.kt:301`, `:623-631`). A rehydrate adds a
re-read and re-encode of every **resident** row (`:320-328`), which depends on how much the process
has touched. So a catch-up is roughly 0.2 s or more at that size: not a few milliseconds, not
seconds. `rehydrateFromDisk()` itself has not been timed; §11.4 step 0 does that. Other recorded
sizes: 107,055 rows (`ios-route1-replica-scope.md` §1) and 144,610 (`ios-drive-parity.md` §29.3).

⚠ **The bad case, named.** Two apps live at once while one of them pulls heavily: Split View, or a
wake draining in one app while another is in use, or a background recording. Every store access
the second app makes after a commit by the first pays a catch-up, about 0.2 s, **under the lock
both need**. The reader stutters and the writer waits for it.

| What bounds it | What does not |
|---|---|
| One catch-up per access, however many commits preceded it | The number of catch-ups over a long pull: a reader that touches the store ten times a second pays up to ten |
| It needs two **live** processes. A backgrounded app is quiesced with its loops stopped (§5.3), so in ordinary use one process writes at a time | Split View and a wake during use are real, and the three existing drivers already cause the second |
| A writer that commits many rows in one transaction advances the generation once | How the pull batches its commits was not traced |
| A wake is at most about 25 s (§7.5) | A foreground pull in Split View is not bounded |

⚠ A rehydrate is **not** split to release the lock part-way: a peer would see a half-refreshed
base, which is the thing being prevented. If the measured cost under this case hurts, the answer
is §5.6, not a partial rehydrate.

**Rule C: one order for every change to the container's files.** All of it under the store lock.

> 1. **Intent.** If the change is more than one file, write a durable marker that says what is
>    being done, complete enough for another process to finish it.
> 2. **Distrust.** Make every peer distrust what it holds: advance the `generation` if rows were
>    added, changed or removed one by one; mint a new `instance` if the store is being replaced.
> 3. **Change.** The destructive or rewriting steps.
> 4. **Clear** the intent.
>
> **Recovery:** any process that takes the lock and finds an intent does 2, 3 and 4 itself, before
> anything else. It does step 2 again because it cannot know whether the dead process reached it.

A change that is a single file write needs no intent: it either happened or it did not. It still
does step 2 first.

The sequences, derived from the rule:

| Change | 1. Intent | 2. Distrust | 3. Change | 4. Clear |
|---|---|---|---|---|
| **Row write or removal** (one file) | none needed | `generation + 1`, once per critical section | the row file, an atomic rename, or its removal | — |
| **Wipe**, live (`wipeAllObjects`) or store-less (`wipeFileObjectStore`) | `replica.wiping`, written atomically. It records what to stamp afterwards, if anything (a format wipe at `open` stamps the new version; a device-forget stamps nothing) | **a new `instance`**: every other handle is fenced from its next access | delete the row files, the staging directory and the lease records; write the stamp the marker names | remove `replica.wiping` |
| **Migration at `open`** | the staging directory with **the rewritten rows and the new version sidecars**, then the commit marker (`FileObjectStore.kt:534-549`). Before the marker, staging is garbage and is discarded | **a new `instance`**: a handle opened on the old shape must not write old-shaped rows into the new one | move the staged rows and sidecars over the live ones; delete rows the staged set lacks (`:555-579`) | remove the staging directory |
| **Register / Cancel** (§4.4) | none needed: nothing to complete if it dies half-way, the operation simply failed | the incarnation, which is what makes a *holder* distrust; the row write advances the generation | the row | — |
| **Lease record**, **generation file created** | one file | — | the file | — |

**Crash points (I9), regenerated from those sequences.** What a peer does at its next lock entry if
the process died after each step. The lock is released by the death.

| Change | Died after | Peer |
|---|---|---|
| **Row write** | the generation | rehydrates; nothing changed on disk. Consistent |
| | some rows of a transaction | rehydrates; consistent with the disk. ⚠ A transaction of several rows may be half applied and **nobody completes that**: it is the store's existing property on every platform (`ScheduleStore.kt:29-33`: a lock, not a rollback), and layer 1 neither adds nor removes it |
| | a torn or short generation write (the process died in it, or `pwrite` returned fewer than 16 bytes) | no row was touched: a short write is a failure and the mutation does not proceed. So if the bytes happen to equal a peer's `base` (a short write can rewrite only the unchanged `instance`), the peer is right to think nothing changed. If they differ, it rehydrates, for nothing. The next writer overwrites all 16 bytes |
| **Wipe** | nothing yet (lock only) | nothing |
| | 1. the marker | finds the marker: mints a new `instance`, **finishes the wipe**, removes the marker; then it is fenced and rebuilds on the empty store |
| | 2. the new `instance` | the same. Had the peer looked before the recovery, it was fenced already: nothing of the old store can be written back in between |
| | 3. some or all deletions, lease records among them | the same; deleting what is already gone is a no-op. A holder whose record is gone was fenced at step 2 and cannot write |
| | 4. the marker removed | fenced; rebuilds on the empty store. Consistent |
| **Migration** | staging written, no commit marker | no intent: rows unchanged, staging discarded at the next `open` (`:381`). Nothing to complete |
| | 1. the commit marker | finds it: mints a new `instance`, **finishes the move, sidecars included**, removes staging; then it is fenced and rebuilds |
| | 2. the new `instance` | the same |
| | 3. part of the move | the same; the move is idempotent (`:526-528`) |
| | 4. staging removed | fenced; rebuilds. Consistent. Whether its build can read the result is §5.7 |
| **Register / Cancel** | the incarnation | a holder stops needlessly; the row is unchanged and the caller's operation failed. Consistent |
| **Lease record** | — | the old record or the new one |
| **Generation file created** (once per container) | `open(O_CREAT)`, then 16 bytes in place | empty or short counts as a different `instance`: fenced, rebuilds, and finds 16 bytes or writes them itself under the lock |

No row ends in "the peer believes it is current and is not", and the only row that ends in "nobody
completes it" is the half-applied transaction, which is not this design's to change.

**What regenerating the table found**, beyond the wipe order that review reported:

- ⚠ **A format wipe at `open` had no intent at all** (`FileObjectStore.kt:396-405` deletes, then
  stamps). It is the same hole as the device-forget wipe: dying mid-wipe left a part-wiped store
  with the old stamp. It now uses the wipe sequence, with the stamp recorded in the marker.
- ⚠ **A migration's version stamp was outside its intent.** Today the sidecars are written after the
  move (`:477-480`), so a peer that finished a dead process's staged move would leave migrated rows
  under the old stamp, and the next `open` would migrate them a second time. The sidecars now
  travel in the staging directory and are applied by whoever completes the commit.
- **Recovery must do the distrust step itself.** A peer that only completed the change would leave
  a third process, which had looked between the intent and step 2, believing it was current.
- ⚠ **A wipe that only advanced the generation left holders able to write.** A rehydrate makes a
  process's *reads* current; it does not stop an operation that was half-way through from writing
  what it had computed from the old store. Review found it for lease records (a holder repopulating
  a wiped realm); it is the same for a sync cycle writing a cursor past rows that no longer exist.
  Hence `instance` and the fence: a wipe or a migration is not a write, it is a different store.
- A catch-up no longer has its own "finish a staged commit" step: completing an intent is one
  rule, applied at lock entry before the generation is even compared.

**Paths that touch the replica directory, and whether they are inside the protocol (I10).** Found
by searching for every use of `sharedReplicaDirectory`, `wipeFileObjectStore`, `wipeAllObjects`,
`readObjectStoreEntries` and `FileObjectStore.open`.

| Path | Code (read) | Today | In v0.8 |
|---|---|---|---|
| Row writes and removals through the database | `DatabaseImpl.kt:116`, `:324`; `FileObjectStore.kt:93-107`, `:140-144` | under the store lock | inside: the generation is advanced first |
| **Live wipe** | `FileObjectStore.wipeAllObjects` (`:133-138`), called by `SyncManagerImpl.wipeLocalStore` (`SyncManagerImpl.kt:803-805`) and `ControlSurface.kt:1571` | ⚠ **called on the store directly, not through the database: no lock at all** | rule C's wipe sequence; the store takes the lock itself |
| **Store-less wipe** | `wipeFileObjectStore` (`:672-685`), called first by `ControlSurface.wipeLocalObjectStore` (`ControlSurface.kt:1551-1564`), which exists so that a discard works before any runtime is built | ⚠ **no lock, no store, no signal to a sibling** | rule C's wipe sequence, through a store-less entry that opens the lock file itself and goes through the gate. It keeps needing no runtime |
| **`open`** | `FileObjectStore.open` (`:370-486`): `resumeStagedCommit`, `discardStaging`, the format wipe (`:396-401`), the migration (`:454-476`), the version sidecars, `readHashes` | ⚠ **no lock**: it runs while the Koin graph is built (`PlatformProxyModule.kt:663-675`). A second app launching can rewrite the store under the first | the whole of `open` is one critical section; a wipe or a migration follows rule C. ⚠ Whether a migration may run at all on a shared store is open: §5.7 |
| The rehydrate | `rehydrateFromDisk` (`:300-330`) | under the lock by its callers (`FirstPartyLocalRuntime.kt:211-215`) | inside; reads only |
| Dev probe: write | `AppGroupProbe.writeReplicaProbeEntry` (`AppGroupProbe.kt:128-152`): its own `flock` handle and its own store instance | outside the gate; no generation | deleted, or moved onto `SharedReplicaDatabaseLock`. It must not ship beside S0b as it is |
| Dev probe: read | `AppGroupProbe.readReplicaEntries` (`:110-114`), `readObjectStoreEntries` (`FileObjectStore.kt:687-695`) | no lock; read-only; holds nothing | harmless: no resident base, no write. Left |
| Legacy app-local store cleanup (S0) | `LegacyAppLocalReplica.kt` in S0 | touches the app's **own** container only | not a shared path: no peer can see it |
| Deprovision, custody purge | `FirstPartyLocalRuntime.kt:236-238`; each `*LocalRuntime.deprovision` | Keychain only: "The replica in the shared container is left alone" (`DriveLocalRuntime.kt:388-389`) | does not touch the directory |
| Plaintext bytes | `IosFileDatabase` (`IosFileDatabase.kt:76-78`): `files/` beside `objects` | no lock; **no resident copy**: every read and write goes to the file (`:80-131`) | outside layer 1 and needing none of it: there is no base to go stale. ⚠ Its own hazard is two processes writing ranges of one file (`writeBytesRange`), which neither layer addresses; recorded in §14 |
| Drive's upload staging | `sharedReplicaDirectory("upload-staging")`, bound only in Drive's overrides (`DriveLocalRuntime.kt:137`, `:165`) | one app writes it | outside; one writer. It becomes a shared path the day a second app binds it |
| HLC watermark | `HlcWatermarkFile` in `sync-hlc/` | its own tried lock | unchanged; goes through the gate (§5.3) |
| Lease records | `sched-lease/` (§4.2) | new | inside the store lock; removed by a wipe |

⚠ A wipe also removes the lease records, in step 3, after the new `instance` of step 2. Run
history belongs to the realm that was left; kept, it would make the next realm's first jobs wait
out the old one's periods. Removing a `HELD` record is safe **because** of the order: its holder
was fenced at step 2, so it cannot write, whatever a successor is granted. It learns that it was
stopped at its next store access or heartbeat, whichever comes first.

⚠⚠ **Not solved here: `open` in an app built with a different schema.** Rule C makes a migration
or a version wipe safe against a crash and visible to a sibling. It does not make a sibling built
with another schema able to read the result. That is §5.7, and it blocks installing the App Group
build.

**The same value twice (ABA).**

| Value | Can it repeat? |
|---|---|
| `generation` | It only increases within one `instance`, and it is never compared without it |
| `instance` | Random, 64 bits, minted at creation and at every wipe and migration, always written in place. `(instance, generation)` never repeats even if the counter does. A file deleted behind a running process is caught by rule D's link-count check, not by comparing values. *Platform:* the group container outlives an app's uninstall while any app of the group remains; then the file simply persists and is adopted as it is. When the last app goes the container goes, and every process with it |
| The 16 bytes after a torn or short write | They can equal a peer's `base`. That is safe only because a row is never touched after a write that was not complete; without that rule a short write that rewrote just the `instance` would hide a mutation from every peer |
| `incarnation` (§4.4) | A counter in a file that only a wipe removes; a wipe also removes every `holder`, so no grant survives to compare against a restarted count |
| `holder` | Random per acquisition |

**Lock order, stated once.** Everything in this design uses it.

1. **Gate admission** (§5.3): a count, not a lock. Never waited on while holding anything.
2. The store's **in-process recursive lock**.
3. The store's **`flock`**, tried, never waited on. If it is not obtained, 2 is released and 1 is
   given back before sleeping.
4. Inside: the generation file, the lease files, the registry row. **None has a lock.**
5. `watermark.lock` may be tried, inside or outside the store lock. It is never waited on.

| Who | Takes |
|---|---|
| Any store read or write | 1, 2, 3 |
| A lease acquisition | 1, 2, 3; inside: row read, lease file write |
| A heartbeat | 1, 2, 3; inside: row read, `renewedAtMs` |
| A completion or an abandon | 1, 2, 3; inside: row read, lease file write |
| `CancelScheduled`, `Schedule` | 1, 2, 3; inside: the incarnation in the lease file, then the row |
| A wipe, a migrating `open` | 1, 2, 3; inside: the intent, a new `instance`, then the files |
| `closeAndDrain` | nothing: it flips the gate and waits for the count |

It cannot deadlock: the only things waited for are the in-process lock, always taken before the
`flock` and released if the `flock` is not obtained, and the gate's count, which is waited for by a
thread that holds nothing. No thread waits in the kernel for a container lock (I2).

**What layer 1 does not reshape.** No stored row, no wire type, no schema, no service version;
`SchemaShapeBaseline` is untouched (House Rule 9). The generation file and the wipe marker are
device-local, never synced, and not inside `objects`. `ObjectStore` and `DatabaseLock` keep their interfaces: the hooks are in
`SharedReplicaDatabaseLock` and `FileObjectStore`, both iOS-only, and the JVM, Android and JS stores
are not touched. *Threat model:* the file holds a random value and a counter, nothing about any
row; nothing reaches the relay. No amendment is owed, as for the advisory lock itself
(`ios-drive-parity.md` §7).

**Not covered, and said so.** Layer 1 is about the object store's `present` and `cache`.
`IosFileDatabase` was traced and holds no resident copy (paths table). The preview cache is
`InMemoryPreviewCache`, per process and derived (`PlatformProxyModule.kt:648`): it can show a stale
preview, it cannot write one back. What sits *above* the store in a process, a view model's list or
a repository's memo, is refreshed by the store's listeners exactly as it is by a rehydrate today.

### 5.6 Deferred: incremental catch-up

**Not part of this design.** v0.7 specified a change journal; the maintainer deferred it on
2026-10-02 (M11). It gets its own design and review if it is built at all.

- **The idea.** Beside the generation, an append-only list of the hashes each write transaction
  changed. A process that is behind re-reads only the rows it holds among those, instead of
  enumerating the directory and re-reading its whole resident set.
- **The trigger for building it.** §11.4 step 0 (e): two apps active at once on the real store, one
  doing a large pull, the other scrolling. If the second app visibly stutters, or the pull is
  slowed by the reader's catch-ups, the journal is worth its complexity. Not before.
- **Two hazards already known for it**, both from review of v0.7:
  1. When the journal is truncated or replaced, the value that tells readers so must be published
     **before** the destructive step. In the other order a process killed in between leaves a reader
     trusting an offset into a journal that no longer has it.
  2. **Every** wipe path must invalidate it, the store-less one included (§5.5, paths table). A
     path that deletes rows without doing so leaves readers believing they are current.

### 5.7 ⚠⚠ Open: schema migration of a shared store (blocks installing the App Group build)

**Maintainer decision, 2026-10-02: this is a blocker, and no mechanism is chosen.** It is listed
with S0b in §12 and is decision M12 in §13. This section lays out what the code does and what the
options cost. It does not choose.

**The problem.** One store, several apps, and the App Store updates them one at a time. An app on a
newer schema and an app on an older one will open the same store.

**What the code does today (read).**

| | Where |
|---|---|
| `.formatversion` holds the core version the store was written under. A store is openable only if `recorded in floor..current`. | `StoreVersionGate.kt:39-40`; `FileObjectStore.kt:383-395` |
| Not openable, **which includes a store newer than this build**: every row is deleted. | `FileObjectStore.kt:396-401` |
| Then the sidecar is rewritten with **this build's** version whenever it differs. | `:402-405` |
| `.serviceversions` holds one version per service. Equal to this build's: open lazily, touch nothing. | `:423-426` |
| A service recorded **above** this build's: "store written by newer code; wiping", the whole store. Below the floor: the same. In between: run that service's migration chain and persist the result. | `StoreSchemaMigrator.kt:185-188`, `:216-227`; `FileObjectStore.kt:454-486` |
| All of this runs in `open`, with no lock, while the app's graph is built. | `PlatformProxyModule.kt:663-675` |
| A process that already has the store open is never told. | — |

So, with one shared store and two builds:

- The **newer** app opens: it migrates the rows forward, or wipes if the older stamp is below its
  floor.
- The **older** app is launched afterwards: the store is newer than it, so it **deletes every row**
  and stamps its own older version.
- The newer app is launched again: it finds an older, empty store and stamps or migrates it; the
  next pull back-fills the realm.
- Each wipe also deletes what was not yet synced: the outbound queues live in the same store.
- An older app that was **already running** when the newer one migrated keeps going. Layer 1 makes
  it rehydrate; it then reads rows whose shape it does not know.

**What "lockstep" means in the docs.** `core-schema-evolution.md` lists as a ground fact: "Apps on
one device must move in lockstep. The Android smoke gate asserts every installed app reports the
same version (House Rule #9), because a skewed pair fails as a cryptic `IPC Client -> Failure`".
That is Android, where one process (Control) opens the store and the apps are its clients. On iOS
route 1 every app opens the store itself, nothing asserts lockstep, and the user decides which app
is updated when. `core-schema-skew-surface.md` is the surface that tells a user to update; on iOS
route 1 the app to name is the app itself.

**Options.** They are not exclusive, and every one of them needs `open` under the store lock, which
S0b-1 already does.

| | What it is | What it costs |
|---|---|---|
| **(a) Refuse, do not wipe** | A build that finds the store newer than itself does not open it and does not touch it. It shows the update surface. | An out-of-date app is unusable until it is updated. The "newer than me" verdict must be split from "below my floor", which today share one wipe. Every route-1 app needs the skew surface. It does nothing for a sibling that is **already running** when the store is migrated |
| **(b) Migrate only when every app can read the result** | Each app records the versions it was built with in the container. A newer app migrates only if every recorded app is at or above the target; otherwise it is the one that waits. | The newest app is blocked by the oldest. A registry of installed apps that must not go stale after an uninstall. The newer app still cannot read old-shaped rows, so "waits" means unusable |
| **(c) A minimum-version marker the older app honours** | The migrating app writes, under the lock and by rule C, the versions needed to read the store. Every app checks it at `open` **and at every catch-up**, and one that is below it stops using the store and shows the update surface. | As (a) for the out-of-date app, and it also covers the sibling already running, because the check rides the catch-up. One more file and one more comparison at catch-up. It protects only against builds that already contain the check, so it must be in the first App Group build |
| **(d) Keep today's wipes, made safe** | Change nothing but put the wipe and the migration inside rule C. Skewed apps wipe each other's store at alternate launches and the relay back-fills it. | No new mechanism. Every skewed launch costs a full back-fill and **loses whatever was unsynced**. A running older sibling still reads rows it does not understand, unless combined with (c). Arguably acceptable only while the realm is test data (D1) |

**What is not known.** Whether a wiped shared store with a fenced, out-of-date sibling can refill
at all; how often the ten apps would in practice be on different schemas; whether (a) or (c) can be
decided inside `open` without building the rest of the graph.

## 6. The same lease in the in-process loops

The change is platform-neutral: both loops take a `ScheduleRunLease` and a `ScheduleRunPolicy`,
defaulting to `AlwaysGrantedLease` and an empty policy. Android, desktop, the hub, JS and every
existing test get the defaults and behave exactly as today. Only the iOS module binds the file
lease.

### 6.1 `ScheduleDrainLoop`

| Where | Change | Under the default lease (I4) |
|---|---|---|
| Constructor | `lease: ScheduleRunLease = AlwaysGrantedLease`, `policy: ScheduleRunPolicy = ScheduleRunPolicy.Unrestricted`, `cancelOpsWithRun: Boolean = false`, `nowMs: () -> Long`. | all defaults |
| `tick`, per row (`:188-222`) | Skip a key with `!policy.runsHere(key)`. Skip an `EXTERNALLY_DRIVEN` key always: it is never dispatched here (§3.3). For every other key, `val lease = policy.leaseFor(key)`, and **every row below uses that handle**: an app-local key gets `AlwaysGrantedLease` and takes the right-hand column. The rows the tick reads are current: reading them was a store access (§5.5). | the default policy skips nothing and returns the default lease for every key |
| First sight of a key | `lease.peek(key)`. A state: **prime** from it. `null`: do not prime. **Either way the key is now seen and is dispatched when it is due.** The record was created when the key was registered (R1, R5). | `peek` returns `null`: no priming, today's timing |
| Due by accumulator (`:215`) | The acquire step of §4.4: one critical section reads the row and calls `tryAcquire(…, Cadence(minPeriodMs, initialDelayMs))`. No row: forget the key's timing. `Granted`: launch the run with the grant and that row. `Refused`: **prime** with its `retryInMs`. | no transaction; the row the tick already holds; always `Granted` |
| **Prime** | The accumulator holds **elapsed** time and is compared with a threshold (`:207-215`). So: add the key to `firstFired` if the record has a completion; take `threshold` as the loop will now compute it from the row (`initialDelayMs` before a first run, `minPeriodMs` after); set `accumulatedMs[key] = threshold - retryInMs`. On first sight `retryInMs` is `waitMs` (§4.4). The value may be negative. ⚠ Storing `retryInMs` itself would invert it: a 24 h item with 10 h to go would wait 14 h more. | never reached |
| Dispatch (`:240-244`) | With `cancelOpsWithRun`, dispatch through `withEvaluationScope(<the run's scope>)`, so the operation is a child of the run: cancelling the run cancels it, and the run's job completes only when the operation has stopped. I1 depends on that. | `false`: the operation runs on, as today |
| `run` (`:227-304`) | The heartbeat of §4.4: once immediately before dispatch, then every 10 s until the run's job has completed. It cancels the run on T9 or T15. | not started: `isReal` is false. A row replaced during a run is handled by the generation guard of `ScheduleRunState`, as today |
| End of a run (`:255-303`) | From the completion handler (`:120-124`): the end-of-run step of §4.4, T11 or T10. | the grant's `complete` and `abandon` do nothing; the existing outcome code runs unchanged |
| Row write on completion (`:264-279`, `:354-380`) | With `lease.isReal`, a **periodic** completion does not rewrite the row. See the trap below. One-shots are unchanged. | the row is written as today |
| `ListScheduled` | `ListScheduledImplementation` returns the stored rows as they are (`ListScheduledImplementation.kt:23-28`). It now asks `policy.leaseFor(key).peek(key)` for each and, where there is a record, returns the row with **`attempts` taken from the record**: a copy for the reply, never written back. `attempts` stays what its field says it is, "dispatch attempts so far" (`ScheduleItem.kt:58-60`), and counts runs by every process. | `peek` is `null`: the row as stored |
| New: `suspend fun runDueNow(filter, startByMs, deadlineMs): List<RunReport>` | The wake path (§7.5 step 8): nothing is started after `startByMs`, and what is running at `deadlineMs` is cancelled. Same single-flight map, same lease, no accumulators: a key is run if it `runsHere`, is not `EXTERNALLY_DRIVEN`, the filter admits its wake class, and the acquire step on `policy.leaseFor(key)` grants. A refusal is reported, not retried. | not called |

Why prime the accumulators: they are in memory and count from process start (`:39-43`), so a 24 h
item never fires in a process that lives minutes, and an item with a 10-minute first delay fires in
every app ten minutes after every launch (`SliceSnapshotSchedule.kt:62` says as much). With the
lease state as the persisted "last ran", a period means the same thing in every process. The
accumulator stays, so tests keep running under virtual time.

⚠ **Why a periodic completion stops rewriting the row on iOS.** A completion rebuilds the row from
the copy the run started with (`:368-377`). The generation guard that stops it overwriting a newer
registration is process-local (`ScheduleRunState.kt:22-25`). Across processes, a completion in app B
can therefore write back the constraints app A replaced during the run. The only thing a periodic
completion changes in the row is `attempts`, which moves to the lease file and is put back into
what `ListScheduled` returns, so the field stays observable without a cross-process row write.

### 6.2 `SyncScheduler`: the sync cycle

The registry loop does not run the sync cycle on iOS (§3.2, §3.3), so leasing only
`ScheduleDrainLoop` would leave the one job that matters most running in every process. The lease
is taken inside `SyncScheduler` and nowhere else: around each `tickOnce` for the loop
(`SyncScheduler.kt:722`) and for `drainNow()` (`:786`), and **once around the whole of**
`drainForWake` (§7.6), which runs several cycles under one grant. A wake drain that took the lease
per cycle would be refused by the freshness its own first cycle wrote.

| Tick caused by | Mode (§4.4) | If refused |
|---|---|---|
| The poll interval elapsing | `Cadence(the interval just waited)` | Skip the cycle. Nothing else: what the sibling's cycle wrote reaches this process at its next store access (§5.5). |
| A wake signal: a local save, the first open stream, foreground, an entitlement change (`:713-719`, `:799-826`) | `Demand` | Only a live holder refuses it. Retry after 1 s, up to 5 times, then once per interval. ⚠ **It stays a demand until it is granted.** A refused demand that became a cadence poll would be skipped as fresh, and the block that caused it would wait for this process to be the runner again: the sibling's cache does not hold it. |
| `drainNow()` | `Demand` | Same retry, inside the call. |
| `drainForWake` (§7.5 step 6) | `Cadence(15 min)` for a refresh or processing wake, `Demand` for a push | Not retried in a refresh wake: someone drained recently. A push retries for 5 s (§10.2). |

The loop knows which it is: `wakeSignal.awaitOrTimeout(interval)` (`:745`) returns on a wake or on
the timeout, and the tick that follows takes its mode from that. A cycle completes with no
retry time (§4.4): freshness is each caller's `minIntervalMs`, so a process polling
faster because a stream is open is not held to a sibling's 30 s.

- Key: `sync-drain`, the string `BackgroundSyncScheduler.DEFAULT_DEDUP_KEY` already uses
  (`BackgroundSyncScheduler.kt:86`), so the registered marker (M3) and the cycle share one record.
- ⚠ The cycle is **not** governed by its row: its critical section reads no row, and its heartbeat
  is `renew` without the incarnation check. Syncing does not depend on a schedule row today and
  must not start to; cancelling or re-registering the marker moves its incarnation (R1 to R3) and
  changes what the planner reads, never whether a process may sync or whether a running cycle
  stops.
- A tick that outlasts 10 s is kept by the same heartbeat as any run.
- The cycle reads the outbound queues and the cursor through the store, so it reads them current
  (§5.5): a block a sibling queued a moment ago is in the queue this cycle drains.
- The maintenance hooks run before the enabled guard (`:916-924`) and stay inside the lease: they
  sweep the shared store, so one runner is right for them too.
- ⚠ A **sealed** process (`!syncManager.isEnabled()`, `:926`) releases with `abandon`, never
  `complete`. A sealed Control must not record a cycle that pushed and pulled nothing, or it would
  hold every sibling off for an interval.

**How a second live app sees what the runner pulled.** Today each process pulls for itself and
ingest is idempotent, so a pull is also how a process's resident cache learns of new rows
(`ios-drive-parity.md` §11). With one store the sync cursor is a row in that store
(`SyncManagerImpl.kt:87-92`), so after S0 a second process is already not re-pulling what the first
pulled. It sees those rows through layer 1: its next store access rehydrates it, and the listeners
on the rows it shows fire as they do today (§5.5). The lease does nothing for this and needs to do
nothing. That is why M2 is retired (§13); the cost it worried about is now §5.5's bad case.

### 6.3 Android and desktop

One proxy process each, and they bind nothing new. Invariant I4 is that this changes nothing, and
it holds because every new branch is behind one of three defaults, and layer 1 is not there at all:

| Default | What it keeps as today |
|---|---|
| `AlwaysGrantedLease`: `isReal = false`; `peek` returns `null`; `registrationChanging` does nothing; `tryAcquire` returns `Granted`; its grant's `renew` returns `KEEP`, `complete` and `abandon` do nothing | no file; **no transaction opened to acquire** (the loop's own comment forbids one on a 1 Hz path, `ScheduleDrainLoop.kt:180-183`); no priming, so timing is the in-process accumulator alone; no refusal, so nothing is withheld; no heartbeat, so nothing is cancelled; the row is written on completion |
| `ScheduleRunPolicy.Unrestricted` | no key is `APP_LOCAL` or `EXTERNALLY_DRIVEN`, so Android's loop still dispatches `DrainSync` |
| `cancelOpsWithRun = false` | a stopped loop leaves its operation running |
| Layer 1 | exists only in `SharedReplicaDatabaseLock` and `FileObjectStore`. A single-process platform's cache is its store |

⚠ `null` from the default lease must never read as "could not write, try again later". An earlier
draft's first-sight rule did exactly that, and would have stopped every scheduled item on Android,
desktop, the hub and in every existing test. `ScheduleServiceTest` and `ScheduleRunGuardTest` pass
unchanged; §11.1 adds a test that drives a loop with the defaults and compares its dispatches, tick
for tick, with master's.

## 7. `IosPlatformScheduler` and the wake runner

### 7.1 Kotlin, with a one-line Swift call per app

| | Kotlin in `iosMain` (chosen) | A Swift driver per app (today) |
|---|---|---|
| Copies | one | one per app; three exist and already differ (15 min vs 30 min, different completion logic) |
| Compiled by the gate | yes: `compileTestKotlinIosSimulatorArm64` in `scripts/run-gate.sh`, on macOS | no: only `scripts/ios-smoke.sh` compiles Swift |
| Expiration handling | cancels Kotlin jobs and abandons Kotlin leases directly | must call back into Kotlin and wait |
| Unit tests | plan mapping and runner in `commonTest` / `iosTest` | none |

⚠ **Unverified:** no Kotlin in this repo imports `platform.BackgroundTasks` today. Whether
`BGTaskScheduler.registerForTaskWithIdentifier` behaves from Kotlin/Native as it does from Swift is
the first thing S1b proves, on the simulator build and then with the debugger's simulated launch
(§11.4). If it does not, the fallback is a ten-line Swift shim that forwards the three callbacks
(launch, expiration, completion) to the same Kotlin runner; nothing else in this design changes.

Pieces and where they live:

| Piece | Module | Why there |
|---|---|---|
| `ScheduleRunLease`, `AlwaysGrantedLease`, `RunOutcome`, `AcquireMode`, `RegistrationChange`, `ScheduleRunPolicy`, `registrationFingerprint`, `waitMs`; **the lease state machine and the record's text codec, as pure functions** | `:sync-service-proxy` commonMain | tested on the JVM with a fake clock. The interface is used by `ScheduleDrainLoop`, `SyncScheduler` and the two registry operations, which live here |
| `IosWakePlan`, `SchedulePromise` | `:sync-service-proxy` commonMain, beside `ScheduledWorkPlan.kt` | the same split that made the Android mapping testable (`ScheduledWorkPlan.kt:11-16`) |
| Layer 1 (generation file, catch-up, fence, rule C's wipe and migration sequences) and `SharedContainerLockGate` | `:core-common` iosMain, in `SharedReplicaDatabaseLock` and `FileObjectStore` | where the store is, and where `iosTestCheck` runs |
| `LeaseRecordFile`: the digest file name, reading a record's text, replacing it atomically, deleting it. **It knows no lease type**: text in, text out | `:core-common` iosMain, beside `HlcWatermarkFile` | ⚠ `iosSimulatorArm64Test` is disabled for every `-proxy` module, so file I/O placed there cannot be tested (`HlcWatermarkFile.kt:32-40`) |
| `FileRunLease : ScheduleRunLease`: the adapter. It calls the pure state machine and codec from above and `LeaseRecordFile` from below, and holds no logic of its own | `:control-shared-proxy` iosMain | the first module that sees both. The same shape as `IosSharedHlcWatermarkStore` over `HlcWatermarkFile` |
| `IosPlatformScheduler` | `:control-shared-proxy` iosMain, bound at `PlatformProxyModule.kt:1107` | a thin adapter: plan in, one submission out |
| `RefreshRequests.submit(identifier, earliestBeginMs)`: the one call to `BGTaskScheduler.submit`. No runtime, no store, no Koin | `:control-shared-proxy` iosMain | used by the planner above and by the wake's step 0 in `:tenshu-sdk-ios`, which sits above it |
| `TenshuBackgroundWake`, `BackgroundQuiesce` | `:tenshu-sdk-ios` iosMain | what Swift calls; has `iosTest` |

⚠ **Why `FileRunLease` is not in `:core-common`.** The build has `:sync-service-proxy` →
`:core-proxy` → `:core-common` (`sync-service-proxy/build.gradle.kts:47`,
`core-proxy/build.gradle.kts:57`), and `:core-common` depends on `:core` alone (`:90`). A class in
`:core-common` that implemented an interface from `:sync-service-proxy` would close a cycle. So
the file primitive goes down, the interface and all logic stay up, and the adapter between them is
too thin to need the test it cannot have.

### 7.2 Registration at launch

*Platform:* a launch handler must be registered before the app finishes launching. In a SwiftUI
`App` that is the `App` struct's `init()`, which runs before
`application(_:didFinishLaunchingWithOptions:)` returns. The three drivers already register there
(`DriveIosApp.swift:24`, `ControlIosApp.swift:73`, `FitIosApp.swift:13`).

Each app's `init()` gains one line and loses its driver:

```swift
TenshuBackgroundWake.shared.registerAtLaunch(host: DriveWakeHost.shared)
```

⚠ The handler is registered **before any runtime exists**. Drive installs its runtime lazily
(`DriveLocalRuntime.kt:351-352`), and a background launch has no view to trigger that. So the
handler builds the runtime itself (§7.5 step 4), and `registerAtLaunch` must touch no Koin graph.
It does two things: register the task handlers, and `BackgroundQuiesce.install()` (§5.3).

### 7.3 Identifiers

| App | Refresh identifier in S1 | Note |
|---|---|---|
| Drive | `app.tenshu.drive.refresh` | exists (`drive-ios/project.yml:117-118`) |
| Control | `app.tenshu.control.refresh` | exists (`control-ios/project.yml:88-89`) |
| Fit | `app.tenshu.fit.refresh` | exists (`fit-ios/project.yml:132-136`) |
| Calendar, Contacts, Maps, Photos, Timeline | `app.tenshu.<app>.refresh` | new; each also needs `UIBackgroundModes: [fetch]` |
| Home, Mia | none | neither has a route-1 runtime (no `*LocalRuntime`, no `installRealRuntime` in their sources) |

- ⚠ The existing identifiers are not `<bundle>.refresh`: the bundle id is `app.tenshu.drive.ios`.
  Decision M9 keeps them.
- S2 adds `app.tenshu.<app>.processing` to the same array. `registerAtLaunch` takes the list from
  the host, so S2 adds an entry and a handler, not a second mechanism.
- ⚠⚠ Plist keys live in **`project.yml`**; `Info.plist` is generated and gitignored.

### 7.4 From `ScheduleItem` to one pending request

`register` and `cancel` cannot mint a task per item: identifiers are static. `IosPlatformScheduler`
keeps **one** pending `BGAppRefreshTaskRequest` and moves its `earliestBeginDate`.

```kotlin
data class IosWakePlan(
    val dedupKey: String, val opId: String,
    val wakeClass: WakeClass,          // REFRESH, PROCESSING, FOREGROUND_ONLY
    val periodMs: Long?, val initialDelayMs: Long?,
    val promise: SchedulePromise,
)
fun ScheduleItem.toIosWakePlan(isPunctual: (opId: String) -> Boolean): IosWakePlan
```

| `ScheduleItem` | `wakeClass` | S1 behaviour |
|---|---|---|
| periodic or one-shot; network `ANY` or `UNMETERED`; no power, idle, storage or battery constraint | `REFRESH` | run in a refresh wake |
| any of `CHARGING`, `DEVICE_IDLE`, `STORAGE_NOT_LOW`, `BATTERY_NOT_LOW` | `PROCESSING` | **not** run in a refresh wake; foreground only until S2 |
| network `NONE` with a power or idle constraint (the media index) | `PROCESSING` | same |
| its operation carries `Punctual` | `FOREGROUND_ONLY` | iOS runs no code at an instant; the notification is S3's |

⚠ **Punctuality is read from the operation's schema every time a plan or a wake filter is built,
never remembered from a registration.** The row stores the operation id and not the `punctual`
argument `register` was called with (`ScheduleImplementation.kt:91`; a field for it would be a
reshape, which is why `Punctual` is a trait at all, `Punctual.kt:18-25`). A process launched cold
for a wake receives no `register` call for a row that is already there, and its scheduler's memory
is empty. Planned from the row alone, Calendar's unconstrained `nextReminder` would look like a
`REFRESH` item.

| Who builds it | Where the schema comes from |
|---|---|
| `ScheduleDrainLoop.runDueNow`, for the wake filter | its own `executionContext.schemaProvider`, the one it already reads for the `HeadlessSafe` check (`ScheduleDrainLoop.kt:199-201`) |
| `IosPlatformScheduler`, for the planner | it has none of its own: the interface's KDoc says a platform binding has no schema provider (`PlatformScheduler.kt:34-38`). The iOS platform initialisation, which runs with the `ExecutionContext` as its receiver (`PlatformProxyModule.kt:729-730`), hands it `isPunctual = { opId -> (schemaProvider.getSchema(opId) as? OperationSchema)?.isPunctual() == true }` once the graph is up |
| before that hand-off, or for an operation whose schema is not found | the planner submits the fallback request only (§7.5 step 0), and an unknown operation is classed `FOREGROUND_ONLY`: the loop would refuse to dispatch it anyway |

The `punctual` argument of `register` is still passed and is ignored on iOS.

- `IosPlatformScheduler.register` and `cancel` are hints (§4.4): they ask for a resubmission and
  write nothing. `registeredAtMs` exists before the first run is planned because the `Schedule`
  transaction itself wrote it (R1, R5).
- `earliestBeginDate = max(now + 15 min, min over REFRESH items of their next eligible time)`. The
  planner reads the registry through the store, so it plans from current rows (§5.5), and a key
  with no row is not in the list. Next eligible, per item:

  | Item | Next eligible |
  |---|---|
  | has a lease record | `now + waitMs(record, row.minPeriodMs, row.initialDelayMs, now)` |
  | no record yet | `now + initialDelayMs`, which can only be late |

  `sync-drain` needs no case of its own: its record is `peek`ed and its row gives the period.
  With `sync-drain` registered at 15 min (M3) the result is `now + 15 min` in practice; the
  formula starts to matter with S2's second request. The 15-minute floor is today's Drive and Fit
  value (`BackgroundSync.swift:30`, `FitBackgroundRefresh.swift:30`) and
  `ScheduledRunDeferred.DEFAULT_RETRY_AFTER_MS`.
- ⚠ `UNMETERED` has no `BGAppRefreshTask` equivalent. It is a `REFRESH` item whose promise says the
  constraint is unenforced (§8). `IosNetworkStateProvider` reports unmetered always
  (`PlatformProxyModule.kt:824-826`), so nothing enforces it in process either. S2 owns that.
- **Two callers, one submitter, one request.** Every submission goes through `RefreshRequests`
  (§7.1), which is serialised: one at a time, per identifier, in the process.
  - ⚠ **It does not rely on a second submission replacing the first.** *Platform:* Apple's
    documentation says a request for an identifier that is already pending replaces it; review
    reports the opposite, a rejection for too many pending requests (iOS allows one pending refresh
    request per app). Nothing here has tested either. So `RefreshRequests.submit` is: **submit; if,
    and only if, that is rejected as too many pending, cancel the identifier and submit again.**
    Under the documented behaviour the cancel never runs. Under the other, the newer request still
    wins, which matters: an item registered for 15 minutes must not wait behind a request hours out.
  - **I14 across the pair.** Submit-first means there is no moment with nothing pending unless the
    first submit was refused, and then only between the cancel and the second submit, two
    consecutive calls. A process killed exactly there leaves this app with no request until it next
    enters the background, which submits one. Realm-wide work is not affected: every other app has
    its own request. This is the one stated gap in I14, and it exists only if the documentation is
    wrong.
  - A submission refused for any other reason (the identifier is not permitted, background refresh
    is switched off by the user) is logged and changes nothing: there is no request to keep alive.
  - The **fallback**: `earliestBeginDate = now + 15 min`, nothing else. It needs the identifier and
    a Keychain read for provisioning, and neither a runtime nor the store. It is the first act of
    every wake (§7.5 step 0) and what entering the background does when no runtime is up.
  - The **planner** (`IosPlatformScheduler`), inside the runtime: the formula above. It runs after
    `register` or `cancel` (through one conflated signal, so a burst of registrations at proxy start
    produces one submission, off the main thread), once a wake's runtime is up (§7.5 step 5), and
    on entering the background.
  - The planner can only move the request **later** than the fallback: 15 min is its floor too. So
    the fallback is never too late, and a wake that dies before the planner runs has lost nothing.
- `cancelAll()` calls `BGTaskScheduler.cancel(taskRequestWithIdentifier:)` for this app's ids.
- The request is submitted **unless the app is known to be unprovisioned**. Drive submits only
  when `isProvisioned()` (`BackgroundSync.swift:27-28`), and that answer is wrong while the Keychain
  is locked (§9.3): it says "no" for "cannot tell". So provisioning is asked for as one of three:

  | `host.provisioning()` | When | Submit? |
  |---|---|---|
  | `PROVISIONED` | the key material was read | yes |
  | `UNKNOWN` | protected data is unavailable (`UIApplication.isProtectedDataAvailable` is false), or the read failed for any reason other than "no such item" | **yes** |
  | `NOT_PROVISIONED` | protected data is available and there is no material | no: a wake would have nothing to do |

  ⚠ A delivered request is spent. A wake before first unlock that declined to submit would leave no
  request pending, and background refresh would stay off after the unlock until something else
  submitted one.

### 7.5 One wake, start to finish

`TenshuBackgroundWake.run(reason, budgetMs)`. *Platform:* a refresh task gets roughly 30 s.

**The budget.** Each kind of work has its own slice, so none can starve another across wakes. A
slice that ends early passes its time on; a slice that reaches its deadline is cancelled.

| Time from the start of the wake | What | Guaranteed to |
|---|---|---|
| 0 to 12 s | steps 0 to 5, then the **sync drain** (step 6). Whatever the runtime took to build comes out of this slice | sync |
| to 18 s | the host's **app-local jobs** (step 7): Fit's health import, Drive's backup, Control's requests | the app's own reason to be woken |
| to 20 s | **registry items** may be started (step 8) | realm-wide jobs: at least a 2 s window in which to start one |
| 20 to 25 s | nothing new is started; a registry item that was started may finish | |
| 25 s = `budgetMs` | whatever is still running is cancelled | |
| 25 to 28 s | quiesce: up to 1 s for cancelled runs to stop, then `drainMs = 2 s` | |
| before 30 s | completion reported | |

25 + 1 + 2 = 28 s, inside the grant. ⚠ Without the 12 s deadline the drain would loop until it was
caught up, and since every wake runs it first, a device with a long back-fill would never import a
workout or run a registry item in the background at all.

| # | Step |
|---|---|
| 0 | **Submit the fallback request** (§7.4), unless `host.provisioning()` is `NOT_PROVISIONED`. No runtime, no store, no token: nothing here can fail for a reason below. This is I14. |
| 1 | Set the task's expiration handler to `expire()`. |
| 2 | `BackgroundQuiesce.acquire("wake")` (§5.3). No token: exit, `finished = false`. |
| 3 | `UIApplication.isProtectedDataAvailable` false (§9.3): go to step 9, `finished = false`. This check comes before anything can answer "not provisioned". |
| 4 | `host.ensureRuntime()`, with the loops stopped (§5.3). `NOT_PROVISIONED`: go to step 9, `finished = true`. A failure to build: step 9, `finished = false`. |
| 5 | The planner refines the pending request (§7.4). No rehydrate step: the runtime's first store access catches it up (§5.5). Sealed realm (`!syncManager.isEnabled()`): skip steps 6 and 8, and in step 7 run only jobs that need no keys, of which S1 has none (§10.1). |
| 6 | **Sync**, deadline 12 s: `SyncScheduler.drainForWake(deadline, mode)` (§7.6), `Cadence(15 min)` for a refresh. `Refused(NOT_YET)` is a normal result: a sibling drained recently. Cut off at its deadline: `NOT_FINISHED`, and the next wake continues. |
| 7 | **App-local jobs** (§10.1), deadline 18 s. |
| 8 | **Registry items**: `ScheduleDrainLoop.runDueNow(filter = REFRESH items, startBy = 20 s, deadline = 25 s)`, in order of `waitMs`. It never includes `sync-drain` (§3.3). |
| 9 | Release the token, which quiesces (§5.3 step 4): cancel, up to 1 s for runs to stop and abandon, `closeAndDrain(2 s)`, end the assertion. |
| 10 | Report completion **exactly once**, guarded by an atomic flag shared with `expire()`. |

`expire()` is the expiration path of §5.3 step 5: cancel, `closeAndDrain(2 s)`, end the assertion,
and only then step 10 with `finished = false`, synchronously. A run that had not stopped keeps its
lease until the TTL (§4.7, W8). Completion is never reported before the gate has drained or timed
out. Cancellation is cooperative: a suspended operation stops at its next suspension point, and one
that never suspends is what the drain waits for.

**Every way out of a wake, and the request it leaves pending (I14).**

| Exit | After step | Request pending |
|---|---|---|
| The handler is given a task of the wrong type | before 0 | the fallback is submitted before the task is failed |
| The app is known to be unprovisioned | 0 | none, by design: there is nothing to wake for. Entering the background after Control provisions it submits one |
| No protection token | 2 | the fallback, from step 0 |
| Protected data unavailable (before first unlock) | 3 | the fallback |
| The runtime cannot be built | 4 | the fallback |
| Sealed realm | 5 | the planner's, or the fallback if the planner did not run |
| The handle is fenced mid-wake (§5.5) | 6 to 8 | the planner's. The wake ends `finished = false` |
| Normal completion | 10 | the planner's |
| `expire()` | any | whichever was last submitted; step 0 has always run |
| The process is killed during the wake | any after 0 | the same |

No row depends on the runtime, the store or protection having been obtained.

The same two rules apply here as everywhere: the gate is open only under the token of step 2 (rule
P), and a wake changes container files only through the store and the lease, which follow rule C
and rule A. Step 0 touches neither.

### 7.6 Truthful completion

`success` passed to `setTaskCompleted`, and `WakeResult.finished`, are `true` only if the wake was
not expired **and** every item it started ended `DONE` or `DEFERRED`. An item the lease refused was
not started and does not count against it.

What today's drivers do instead (read):

- All three sleep a fixed time and report `true` whatever happened (`BackgroundSync.swift:44-47`,
  `ControlBackgroundRefresh.swift:58-61`, `FitBackgroundRefresh.swift:59-60`). The Kotlin side is
  `delay(maxWaitSeconds * 1000L)` after kicking the drain (`DriveLocalRuntime.kt:354-361`), which
  the code itself calls "a bounded window, not drain-completion signalling".
- All three can call `setTaskCompleted` twice: the expiration handler calls it, and the cancelled
  work task can still reach its own call (`BackgroundSync.swift:43-52`). *Platform:* the call is
  meant to be made once. Whether the second is harmful was not established.

The sync cycle needs a real answer. `drainNow()` returns `Unit` (`SyncScheduler.kt:786`). S1b adds,
Kotlin-only:

```kotlin
suspend fun drainForWake(deadlineMs: Long, mode: AcquireMode): WakeDrain
class WakeDrain(val outcome: RunOutcome?, val ingested: Boolean)   // outcome null: lease refused
```

It takes the `sync-drain` lease once, in `mode`, and holds it with the heartbeat for the whole
call (§6.2). Under it, it runs cycles until the outbound queue is empty and a pull returned nothing
new, or the deadline or a cancellation arrives. `DONE` only in the first case; a closed gate inside
the cycle (storage, entitlement, schema fence) is `DEFERRED` with no retry delay, which records
nothing but the outcome: a cycle that could not sync is not a fresh one, and the next caller tries.
`DONE` is recorded with `complete`.
Cut off is `NOT_FINISHED`: `abandon` if there is time, the TTL if not, and in neither case a
completion, so the next wake is not told the drain is fresh (I6). A sealed realm never gets here (§7.5 step 5). ⚠ **Not traced:** which existing signal means "the pull is caught up". Candidates are
`SyncHealthFigures.pendingBlockCount` (`SyncHealthHolder.kt:19`) for the push side and the
`caughtUp` input of `ReclamationBarrier` (`ReclamationBarrier.kt:79`) for the pull side. S1b must
pick one and say which.

## 8. Honesty about what iOS cannot promise

`register` returns nothing (`PlatformScheduler.kt:40`) and stays that way. What the binding could
not promise is computed by a pure function and held in memory:

```kotlin
data class SchedulePromise(
    val timing: Timing,                 // AS_REQUESTED, WHEN_OS_ALLOWS, FOREGROUND_ONLY
    val constraints: Enforcement,       // BY_OS, IN_PROCESS, UNENFORCED
    val reasons: Set<Reason>,           // PERIOD_BELOW_FLOOR, EXACT_INSTANT, NEEDS_PROCESSING_TASK,
)                                       // UNMETERED_NOT_EXPRESSIBLE, APP_LOCAL_TO_OTHER_APP
interface SchedulePromises { val promises: StateFlow<Map<String, SchedulePromise>> }
```

| Item on iOS in S1 | `timing` | `constraints` |
|---|---|---|
| `sync-drain`, move replay | `WHEN_OS_ALLOWS` | none asked |
| offline keeper, subscription refresh | `WHEN_OS_ALLOWS` | `UNENFORCED` (unmetered) |
| slice snapshot, declaration backfill, media index | `FOREGROUND_ONLY` until S2 | `UNENFORCED` |
| `nextReminder` in an app other than Calendar | `FOREGROUND_ONLY`, `APP_LOCAL_TO_OTHER_APP` | none |

- `IosPlatformScheduler` implements `SchedulePromises`; `NoOpPlatformScheduler` and
  `WorkManagerScheduler` do not, and a caller that finds no implementation shows nothing.
- A settings screen on iOS is in the same process as its proxy, so it reads the flow through the SDK
  surface (`TenshuBackgroundWake.promises()`), joined to `ListScheduled` by `dedupKey`. No IPC.
- The wording ("runs when iOS allows") is the app's, keyed by the enum
  ([ADR 0016](../adr/0016-localisation.md)). No UI string and no UI type enters a proxy module.

⚠ **House Rule 9: this design reshapes nothing.** No field is added to `ScheduleItem`,
`ScheduleSpec`, `ConstraintSpec` or `RetryPolicy`; no operation gains a parameter; no service
version moves; `SchemaShapeBaseline` is untouched. Every new type is Kotlin-only and never crosses
the wire: the same route `Punctual` (`Punctual.kt:18-25`) and `ScheduledRunDeferred`
(`ScheduledRunDeferred.kt:21-25`) took. `ListScheduled` has no caller in any app today, so nothing
depends on the row carrying this.

## 9. D5: a wake runs headless

### 9.1 What gates what (read)

| Thing | Protection | A background wake on a locked phone |
|---|---|---|
| Shared realm key material (`RealmKeyMaterialBridge`: identity key, volume key, device signing key, owner and device ids, A13 pair) | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (`ControlSessionProvisioner.kt:160-163`, used by `RealmKeyMaterialBridge.kt:77-95`) | readable once the phone has been unlocked once since boot |
| Each app's secret bucket: sealed blobs, plain ids, and the wrap key | the same class (`SecurityFrameworkKeychain.kt:84-85`; `IosSecureStorageDelegate.kt:69`, `:89`, `:103-108`); the user-presence gate exists but `gateSecretBucket` defaults to `false` (`:60`) and production passes nothing (`PlatformProxyModule.kt:326-331`) | readable, same condition |
| Biometric realm-unlock wrapper | `kSecAccessControlUserPresence` over `…WhenPasscodeSetThisDeviceOnly` (`IosBiometricUnlockDelegate.kt:135-138`) | not readable without the user; used only by a passphrase-sealed realm |
| Replica files | no protection class is set anywhere in the code, so the iOS default applies (*inferred:* complete until first user authentication) | readable, same condition |
| Control's "Require unlock" | `AppLockGate.swift:10-12`: "UI ONLY — it never touches key material" | no effect on a wake |

**So a background wake on a locked phone can read the realm key material today, and D5 needs no
accessibility class weakened.** The threat model already records this posture: default-headless
autoload with "iOS `AfterFirstUnlock`" (A1 amendment), and "durable custody while Control is locked
is the point of route 1" (A4, route-1 adoption amendment). **S1 owes no threat-model amendment.**
The invariant S1 must keep is I9: the wake runner dispatches only through `ScheduleDrainLoop`, which
fails closed on an operation without `HeadlessSafe` (`ScheduleDrainLoop.kt:196-205`). App-local wake
jobs are not operations, so each is listed in §10.1 and reviewed as headless by hand. The other
entries of §5.4 run app code that exists today and dispatch nothing new.

### 9.2 The two comments, and which is true

| Source | Says |
|---|---|
| `BackgroundSync.swift:5-8`; `DriveLocalRuntime.kt:339-343`; `ios-drive-parity.md` §15 | "Control's realm stays sealed until the user unlocks; it registers nothing." |
| `ControlBackgroundRefresh.swift:20-24`; `ControlSurface.kt:494-499` | Control's default is headless; a wake autoloads with no user present; only a user who chose a passphrase is sealed. |

The code agrees with the second:

- Control's onboarding defaults to device protection with no passphrase
  (`OnboardingView.swift:150-153`, `:163`), and settings offer no "Encrypt on unlock only" on iOS
  (`SettingsView.swift:116-128`).
- `SyncManagerImpl` autoloads at construction when no unlock wrapper exists
  (`SyncManagerImpl.kt:277-290`).
- Control does register a task (`ControlIosApp.swift:73`).

The first statement was true on 2026-07-17 and is stale. **S1 corrects** `BackgroundSync.swift` (by
deleting it, §12 S1c-1), the KDoc at `DriveLocalRuntime.kt:339-343`, and adds a dated note to
`ios-drive-parity.md` §15. "Drive is the background syncer" becomes "any woken app that takes the
lease".

One case stays sealed and is correct as it is: a user who took the advanced passphrase at
onboarding. Control's own wake then has no keys, takes no realm-wide lease (§6.2, §7.5 step 5), and
posts nothing. Sibling apps still run headless for that user, because they hold the joiner payload
in their own buckets (`FirstPartyLocalRuntime.kt:160-171`); that is the A4 posture above, not
something S1 introduces.

### 9.3 ⚠ Before first unlock

After a reboot and before the first unlock, every item above is unreadable. *Read:* the shared
Keychain reader returns `null` for any status other than success
(`RealmKeyMaterialBridge.kt:171-176`), so `isProvisioned()` (`FirstPartyLocalRuntime.kt:203`)
answers **"not provisioned"** when the truth is "locked". The custody probe reads through the same
class (`:228-229`), so it does not trigger a deprovision, but that is luck, not design.

*Platform:* whether a `BGAppRefreshTask` can fire before first unlock was not established. A
location relaunch (Timeline) and a push (S4) can.

S1 rule: the wake runner checks `UIApplication.isProtectedDataAvailable` before it builds a runtime
(§7.5 step 3). If it is false, the wake ends with `finished = false`, runs nothing, and **never**
reaches a "not provisioned" branch. The same three-way answer decides whether the next request is
submitted (§7.4): "locked" submits. The entries of §5.4 that are not wakes owe the same check
before they read provisioning; Timeline's relaunch is the one that can meet it today.
The lease directory cannot be opened in that state either, which §10.1 treats as fail-closed.

## 10. Interfaces to the other phases

### 10.1 S0: the group container

| Situation | Foreground loops | Background wake |
|---|---|---|
| Group container present | file lease | file lease |
| No group container (simulator without the entitlement, or a device build before S0) | `AlwaysGrantedLease`: each app has its own store, so there is nothing shared to protect, and this is today's behaviour | same; the wake runs for its own store |
| Container present but the lease directory cannot be opened or written | every acquire is `Refused(UNAVAILABLE)`, logged once per process | run nothing realm-wide; `finished = false` |

The choice is made once at assembly: `FileRunLease` if
`appGroupContainerDirectory(TENSHU_APP_GROUP, …)` is non-null (`FileObjectStore.kt:729-733`),
`AlwaysGrantedLease` otherwise. ⚠ It must be the same test `sharedReplicaDirectory` uses
(`:741-742`), for the reason `HlcWatermarkFile.kt:45-51` gives: a lease shared more widely than the
store it describes would let one app's run stand for another app's separate replica. Decision M4.

App-local wake jobs, declared by each app's `WakeHost`:

| App | Job | Needs keys | Stays app-local because |
|---|---|---|---|
| Fit | health import and recorder outbox (`FitSurface.runBackgroundRefresh`) | yes | HealthKit authorisation is Fit's |
| Drive | `DriveBackupSurface.backupNow()`; background `URLSession` reattach and resume | yes | PhotoKit and the session identifier are Drive's |
| Control | `runPendingRequestRefresh` and its alerts | yes | the notifications are Control's (`PlatformProxyModule.kt:301-305`) |
| Calendar | none in S1; background re-arm in S3 | yes | the pending notifications are Calendar's |
| Timeline | none: capture is driven by location relaunches, not by a refresh task (§5.4) | — | — |

### 10.2 S4: the entry point a push wake calls

```kotlin
// :tenshu-sdk-ios iosMain
object TenshuBackgroundWake {
    fun registerAtLaunch(host: WakeHost)
    fun scheduleNext()
    suspend fun run(reason: WakeReason, budgetMs: Long): WakeResult
    fun expire()
    fun promises(): StateFlow<Map<String, SchedulePromise>>
}
enum class WakeReason { REFRESH, PROCESSING, PUSH }
class WakeResult(val finished: Boolean, val newData: Boolean, val status: String)
```

S4's handler for `application(_:didReceiveRemoteNotification:fetchCompletionHandler:)` calls
`run(WakeReason.PUSH, budgetMs = 25_000)` and maps the result: `newData` to `.newData`,
`finished && !newData` to `.noData`, `!finished` to `.failed`.

- A `PUSH` wake runs steps 1 to 6 and 9 to 10 of §7.5, plus in step 7 only the host's app-local
  jobs marked `onPush` (Control's pending-request refresh). It skips step 0, which belongs to the
  refresh task's chain, and the registry items of step 8: a push is a hint to pull. With no
  registry items its slices are sync to 18 s and the `onPush` jobs to 25 s.
- Step 6 runs `drainForWake(deadline, Demand)` (§4.4), so a cycle that completed a moment ago does
  not refuse it. `Refused(HELD)`, because another app is mid-cycle, is retried once a second for
  5 s and then reported as `.noData`; the cycle already running will fetch what the push announced.
- `newData` is `WakeDrain.ingested`: this drain ingested at least one block. It comes from the
  drain's own result; the lease record carries nothing about what a cycle moved.
- The fetch completion handler is called after step 9 has returned (§5.4).
- S4 decides which bundle receives the push. S1 makes every answer work, since any app can run the
  drain.

### 10.3 S2: `BGProcessingTask` and constraints

- A second identifier per app and a second handler calling `run(WakeReason.PROCESSING, …)`;
  `runDueNow` gets a filter for `PROCESSING` items; the budget comes from the task.
- `IosWakePlan.wakeClass` already separates the two, and `SchedulePromise` already reports
  `FOREGROUND_ONLY` for what S2 will move.
- In-process constraint enforcement belongs in `ScheduleDrainLoop.tick`, before the acquire, on
  every platform. It is deliberately not in S1. ⚠ The media index already gates itself through
  `IndexingConditions` and throws `ScheduledRunDeferred`; S2 should read that before adding a
  second gate.
- A processing run can last minutes. The heartbeat (§4.4) is what keeps its lease, and the
  expiration handler is the same `expire()`. ⚠ Its token cannot be a 30-second assertion: S2 must
  say what backs protection for a task that iOS lets run for minutes.

### 10.4 S3: Calendar re-arm

`UNUserNotificationCenter` is per app, which is why only Calendar binds a real presenter
(`PlatformProxyModule.kt:294-311`, `calendar-app.md` CA-8c). So re-arm is an **app-local wake job
in Calendar's `WakeHost`**, never a leased item: another app holding a lease could compute the
schedule and still have nowhere to put it. S1 gives Calendar its refresh identifier and an empty
job list; S3 adds the job. `tenshu/calendar/nextReminder` is `APP_LOCAL` to Calendar in the policy
table from S1a on, which also stops Control dispatching `DeliverDueReminders` into its no-op
presenter.

### 10.5 D6: routines

Stored, listed and edited on iOS, never executed (§3.2, RE-D13). When D6 lifts that,
`tenshu/routine/nextWake` is a `Punctual` one-shot, which S1 classes `FOREGROUND_ONLY` (§7.4), and
the evaluation is a realm-wide leased item: the lease is what answers RE-D13's "each process would
evaluate under one device id". It would be the first realm-wide one-shot, which S1 does not design
(§3.3): re-arming one for a new instant is a replacement (R2), which keeps `lastCompletedAtMs` by
design (§4.4), and a one-shot needs the opposite. Nothing in S1 executes a routine.

## 11. Tests and verification

Nothing in the gate compiles Swift except `scripts/ios-smoke.sh`. Kotlin `iosMain` is compiled by
the iOS lane of `scripts/run-gate.sh` on macOS only; `iosTest` runs only under
`./gradlew iosTestCheck`.

### 11.1 `commonTest` on the JVM, fake clock and fake lease

| For | Under test | Cases |
|---|---|---|
| §4.4 | Lease state machine (pure) | one test per transition R1 to R5 and T1 to T16, each asserting the resulting state and **exactly** the fields its row lists; the record codec round-trips every field, `key` included; no R transition changes `holder`; **T16, one test per row of the "cannot be completed" table**: a `renew` that cannot read or write returns `STOP`, a `registrationChanging` that cannot write fails the operation before the row is touched, a record whose `key` differs is unusable for the key asked for; **W22**; `waitMs` for never-run, run, deferred and clock-set-back records; `Cadence(5 s)` granted 6 s after a sibling's `Cadence(30 s)` cycle; a record last completed 2 h ago under a 24 h row is granted at once under a 15 min row; malformed record decodes as `NO_RECORD`; `registrationFingerprint` changes with each spec field and not with `state` or `attempts` |
| Rule B | `Schedule` / `CancelScheduled` with a recording lease | `registrationChanging` is called inside the transaction and before the row write; no existing row reports `NEW`, a different row `REPLACED`, an identical one `UNCHANGED`, a cancel of an existing row `CANCELLED` and of a missing one nothing; a process's start-up re-registration moves no incarnation; **W6b**: cancel then identical register, then the holder's heartbeat returns `STOP`, and the new registration waits its first delay |
| I1 | State machine, two holders | no sequence of T1 to T16 from two processes yields two grants without a T10, T11 or T12 between them (a small exhaustive search over the pure function); R1, R2 and R3 during `HELD` leave it `HELD` and refuse a second acquirer; a holder at T9 keeps the record `HELD` until it abandons |
| I1 | `ScheduleDrainLoop`, `cancelOpsWithRun` | a cancelled run's `abandon` is written only after its operation has returned; the heartbeat keeps renewing while a cancelled operation is still unwinding |
| I4 | Loops with every default | a loop built with the defaults dispatches at the same ticks as master's for a first-delay item, a periodic item, a deferred item and a one-shot; first sight never withholds a dispatch; **no `Database.transaction` is opened by the acquire path**; no heartbeat coroutine is started; a row replaced during a run is not cancelled; the completion writes the row; `ScheduleServiceTest` and `ScheduleRunGuardTest` unchanged and green |
| I5 | Rule A, with a fake lease that records the lock depth it is called at | `registrationChanging`, `tryAcquire`, `renew`, `complete` and `abandon` are each called inside a transaction, in the same one as the row read; a key with no row is never passed to `tryAcquire`; **W14**: a `CancelScheduled` issued from a second thread while the acquire's critical section is open commits after the grant, and the run's pre-dispatch heartbeat stops it before the operation is dispatched |
| I5, I6 | `ScheduleDrainLoop` + fake lease | a run whose row disappears, or whose incarnation moves, at a heartbeat is cancelled and abandoned, and no completion is recorded (T9, T11b); the dispatched `opId` is the row's from the acquiring critical section; expiry, cancel and a lost lease all leave `lastCompletedAtMs` unchanged |
| I8 | Completions | no completion writes a period; `DEFERRED` writes only `now + retryAfterMs` |
| §6.1 | Priming | a 24 h item fires at the right time in a fresh process; a never-run item with a 10 min first delay registered 9 min ago by another process fires after 1 min, not 10; a 24 h item refused with 10 h to go is next attempted after 10 h, not 14 h and not every tick; `Refused(HELD)` is retried after one heartbeat |
| I11 | `leaseFor`, with a real lease that fails every call | an app-local key owned by this app is dispatched on time, starts no heartbeat and is never passed to the real lease, by `tick`, by `runDueNow`, by `Schedule` and by `CancelScheduled`; **W21**; no code path reaches the real lease except through `leaseFor` (asserted by constructing the loop and both operations with the policy only) |
| §3.3 | Policy | an `APP_LOCAL` key of another app is never dispatched; an `EXTERNALLY_DRIVEN` key is never dispatched by `tick` or `runDueNow`; a periodic completion writes no row when `isReal` |
| §6.1 | `runDueNow` | honours the filter and the deadline; shares single-flight with a concurrent `tick`; a refused key is reported and not retried |
| §6.2 | `SyncScheduler` + fake lease | the cycle reads no row and runs with no `sync-drain` row at all; re-registering or cancelling the `sync-drain` marker during a cycle does not stop it; an interval tick is skipped when a sibling completed inside the interval; a wake-signal tick and `drainNow()` run one second after a completed cycle; a wake-signal tick refused by a live holder retries and then runs, still as `Demand` after the five quick retries; a sealed tick abandons; `drainForWake` takes one grant for several cycles, completes once, and does not complete when cut off; `drainForWake(Cadence(15 min))` is refused one minute after any completed cycle |
| §7.4, §8 | `toIosWakePlan`, `SchedulePromise` | one row per line of the two tables |
| §7.4 | Punctuality | a punctual one-shot already in the registry, in a loop and a planner that never received a `register` call for it, is `FOREGROUND_ONLY` and is not dispatched by `runDueNow`; an operation whose schema is missing is `FOREGROUND_ONLY`; the planner before its `isPunctual` hand-off submits only the fallback |
| §4.4 | Outcomes | **one test per row of "every way a run can end"**, asserting exactly the fields that row names; a 12 h item deferred for 15 min is granted after 15 min and not before; a second deferral does the same; `attempts` is unchanged by a deferral; a `DONE` after a deferral clears the retry time |
| §7.4 | Resubmission | a request is submitted for `PROVISIONED` and for `UNKNOWN`, not for `NOT_PROVISIONED`; a wake with protected data unavailable submits the next request and then ends `finished = false`; the planner never submits earlier than the fallback |
| §7.4 | `RefreshRequests`, with a fake scheduler in each of the two behaviours | "replaces": one submit, no cancel; "rejects": submit, cancel, submit, and the newer `earliestBeginDate` is the one pending; two concurrent callers are serialised; any other refusal is logged and nothing is cancelled |
| Rule D | Descriptors | the generation file is created without replacing an existing one and is only ever written in place (its inode number does not change across writes, a wipe and a migration); a lock file created by two handles at once is one inode; a handle whose lock file or generation file was unlinked or renamed over is fenced at its next entry and runs no block; **W19** |
| I14 | Every exit of §7.5's table, with a recording `RefreshRequests` and no runtime installed | a request is pending after each, and step 0's submission is made before any Koin graph, store or token is touched; **W24** |
| §7.5 | The slices | a drain that never catches up is cut at 12 s and recorded `NOT_FINISHED`, and the app-local job and one registry item still run in the same wake; an app-local job that never returns is cut at 18 s; no registry item starts after 20 s; the whole wake, quiesce included, ends by 28 s on the fake clock |
| §6.1 | `ListScheduled` | on a real lease the returned `attempts` is the record's, and counts runs made through two loops over one store; the stored row is not written; under the default lease the row is returned as stored |
| §3.3 | `ScheduleRunPolicyTest` | every `ONE_SHOT` registration in the codebase is in the iOS table; `Schedule` of an unclassified one-shot on the iOS policy fails and writes no row and no lease record; the default policy refuses nothing |
| §4.7 | The lease walks | W1, W2, W4, W5, W6, W6b, W11, W12, W14 and W15 as scripted tests over the pure state machine and the fake clock, each asserting the transitions its row names |

⚠ Delete each guard once and watch its test go red. A fake lease that always grants passes every
test above.

### 11.2 `iosTest` (`:core-common`, `:tenshu-sdk-ios`), macOS only

| For | Under test | Cases |
|---|---|---|
| I3 | Layer 1, two `FileObjectStore`s on one directory with two `SharedReplicaDatabaseLock`s, standing in for two processes as `SharedReplicaDatabaseLockTest` does | a row written through A is returned by B's next `getObject` with no explicit rehydrate; a row B holds in its cache, rewritten by A, is re-read before B's next access and B's listener fires; a row removed by A is gone from B; **the clobber**: B caches a row, A rewrites it, B saves a derived value, and A's change survives; **W13**: A writes rows under one job, B's unrelated transaction sees them; **W7** and **W17**: A is abandoned mid-transaction after the generation write and before a row write, and B rehydrates and finds the row unchanged |
| I3 | The check | an access with nothing changed reads the 16 bytes and no row (asserted with the store's `readThroughReads` counter and a counting file layer); a nested `withLock` does not read them again; any number of foreign commits before one access cause one rehydrate; a short generation file fences; two `instance` values minted in a row differ; with the two `fstat` checks, an unchanged access makes three small calls and reads no row; the existing `FileObjectStoreRehydrateTest` still passes |
| I9 | Rule C, with a file layer that can stop after any call | **one test per row of §5.5's crash table**: stop the writer after that step, then let a second store take the lock, and assert it rehydrated and is consistent with the disk. In particular: a wipe stopped after the marker and before the generation is **finished** by the second store, which advances the generation itself; a third store that looked before the second also rehydrates; a format wipe stopped mid-way ends wiped **and** stamped; a migration stopped after its commit marker is finished by the second store **with the new version sidecars**, and a later `open` does not migrate again |
| I9 | Order | with a recording file layer, for a wipe, a format wipe and a migration: intent, generation, change, clear, in that order |
| I12 | Rule P, with a fake assertion source | **one test per row of §5.3's table**; in particular **W20**: `acquire` returns `null` on entering the background, and the loops are stopped and the gate closed and drained anyway; at every instant the gate is open iff the app is in the foreground or a token is live |
| I13 | The fence | after a wipe through one handle, a second handle's next read returns absent and its next write throws `ReplicaReplaced` and creates no file; a write that was between two store accesses of one operation when the wipe landed throws on the second; every grant of the fenced process answers `STOP` without touching a file; the wiping handle itself is not fenced; a handle that completes another's intent is; a rebuilt handle on the same directory works; the same after a migration; **W18** end to end: the holder's operation, cancelled or not, adds no row to the wiped store, and a new registration is granted |
| I10 | Paths | a store-less wipe while a second store is open fences that store at its next access; the store-less wipe blocks while another handle holds the store lock; `wipeAllObjects` called directly, with no database lock held by the caller, takes it; two stores opening the same directory at once, one of them migrating, do not interleave; a wipe removes the lease records, and a holder then gets `STOP` |
| I9 | Row writes | with a recording file layer: the generation is written before the first row of a transaction; a read-only critical section writes nothing; **a generation write that returns fewer than 16 bytes throws, leaves `base` where it was and touches no row**, for 0, 8 and 15 bytes, and the same for an `instance` mint in a wipe, whose intent the next handle then completes |
| I7 | Lock order | the lease file layer and the generation file are never touched outside `withLock` |
| §4.2 | `LeaseRecordFile` (the record's codec is tested on the JVM, §11.1) | a record's text round-trips through the file; a malformed file reads as absent; the file name is 70 bytes for a key of 1 byte and of 1,000 bytes (**W23**); a record whose `key` differs from the one asked for is T16; an atomic write survives a `peek` mid-write; an unusable directory yields T16 |
| I2 | `SharedContainerLockGate` | `closeAndDrain` returns `Quiesced` only after an in-flight `withLock` on another thread ends; while closed, an independent descriptor's `tryLock` on `replica.writer.lock` succeeds at every probe (the probe `SharedReplicaDatabaseLockTest` already uses), with a second thread calling `withLock` throughout; that thread proceeds after `open()`; **W9**: an independent descriptor holds the lock, a thread calls `withLock` and waits, the gate closes and reports `Quiesced` at once, the descriptor unlocks, and the waiting thread still holds nothing at every probe until `open()`; a thread that obtains the lock in the instant the gate closes releases it without running its block; `SharedReplicaDatabaseLockTest`'s existing cases still pass with the tried loop; a nested entry inside an in-flight access is not parked; the watermark's tried lock reports unavailable while closed; an off-main entry at a closed gate proceeds when a fake `protect()` grants, and parks when it refuses; a main-thread-style entry with a `protect()` that refuses twice and then grants proceeds on the third try; an access that outlasts the deadline yields `TimedOut` and its label |
| I2 | `BackgroundQuiesce` with a fake assertion source | at every lock entry recorded by the gate, the app is in the foreground or the live-token set is non-empty; the last token's assertion is ended after `closeAndDrain` returns and never before, on the release path and on the expiration path; releasing one of two tokens ends its assertion at once and leaves the gate open; `acquire` returning `null` runs no block; an operation dispatched through the loop and suspended mid-run takes no lock after the last release unless `protect()` grants; after an expiration an off-main `protect()` is refused until an explicit `acquire` |
| §5.4 | Each entry | one test per row that exists today, driving the Kotlin entry point from a background thread with the gate closed: the work completes, and the completion handler stub is called after the drain |
| §7.5 | `TenshuBackgroundWake.run` over a `DriveLocalRuntime` test assembly | not provisioned; sealed; drained (`finished = true`); `expire()` mid-drain (`finished = false`, no completion recorded, the lease `FREE` or left `HELD` for the TTL, never completed) |

⚠ `BGTaskScheduler` itself is not testable there: on the simulator `submit` fails with
`BGTaskSchedulerErrorDomain Code=1` (`ios-drive-parity.md` §15).

### 11.3 Builds

`scripts/run-gate.sh` on macOS, and `scripts/ios-smoke.sh --build-only <app>` for every app whose
Swift or `project.yml` changed. All eight in S1c.

### 11.4 On the device (needs the App Group build)

0. **S0b, first: the store.** On the App Group build, with Drive's real store. (a) Log the time of
   the lazy open's enumeration (208 ms was seen once at 107,368 rows) and of one full
   `rehydrateFromDisk()`, and the number of outermost store accesses per second while scrolling a
   listing: these are what §5.5 infers.
   (b) Create a folder in Drive, switch to Photos: it is there with no relaunch and no pull.
   (c) In Split View, rename a file in Drive while Photos shows it: Photos updates, and a change
   then made in Photos does not undo Drive's. (d) Kill Drive in the middle of a large pull, open
   Control: the log shows one catch-up and Control's lists match the disk. (e) **The bad case:**
   in Split View, start a large pull in one app (a fresh install's back-fill) and scroll a listing
   in the other. Log every catch-up with its duration. This is the measurement that decides §5.6.
1. **The termination rule.** Before S0b's gate: a debug build that takes `replica.writer.lock` and
   holds it, then is sent to the background. Expect a crash report with `0xdead10cc`. This settles
   whether the rule covers `flock`. Then, with the gate: background the app during a large pull twenty
   times; expect no such report and no `[quiesce] TIMED OUT` line. Read the gate's long-hold log
   from the same runs: no store access over 1 s.
2. **One drain per interval.** Drive and Photos in Split View on an iPad, or Drive foreground with
   Photos just backgrounded on the iPhone. Read the log with `devicectl`. Each 30 s shows one
   `[sched-lease] key=sync-drain … completed` and one `… skipped(fresh)` from the other app.
3. **A local save still pushes.** Create a folder in the app that is *not* the last runner. Its
   block reaches the relay within a few seconds, not after an interval.
4. **Crash takeover.** Kill the holder from Xcode mid-cycle. The other app acquires within 30 s.
5. **Simulated wake.** With the app backgrounded under the debugger:
   `e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"app.tenshu.drive.refresh"]`.
   Expect acquire, drain, complete, quiesce, `setTaskCompleted(success: true)` once. In the same
   session, submit twice for one identifier and log the second call's result: this settles whether
   a second submission replaces or is rejected (§7.4).
6. **Simulated expiration.** `_simulateExpirationForTaskWithIdentifier:` during a long pull. Expect
   the gate closed and drained, then `success: false`, in that order in the log; no completion
   recorded, no crash report, and the next wake continuing, at once if the run abandoned and after
   the TTL if it did not.
7. **Second app a minute later.** Simulate a wake in Fit one minute after Drive's finished. Fit
   logs `skipped(fresh)` for `sync-drain` and still runs its health import.
8. **Locked phone.** Lock the phone, simulate a wake. It drains. Reboot, do not unlock, trigger a
   Timeline location relaunch if one can be provoked: the log says "locked", never "not provisioned".
9. **The other entries.** With Timeline terminated, travel far enough for a significant-location
   relaunch: the visit is stored, and the log shows no `[gate] parked without protection` line.
   With Drive terminated mid-upload, let the transfer finish: the journal entry is acknowledged.
   Record a Fit workout with the phone locked for ten minutes: no gap and no crash report.

## 12. Sub-phases

Each row is one or more commits that leave master working. ⚠⚠ **The App Group build must not be
used on a device before S0b is in it.** S0 turns every app's store into one shared store; without
S0b nothing stops one app saving over another's rows, nothing stops iOS killing an app suspended
mid-transaction, and an app on an older schema deletes the store an app on a newer one just
migrated (§5.7).

| | What | Verify | First device test |
|---|---|---|---|
| **S0** | The App Group on device builds (its own change). Merged, **not rolled out**: the maintainer's phone keeps its per-app stores. | S0's own | none yet |
| **S0b-1** | **Layer 1** (§5.5): `replica.generation`, the entry check, advance-before-change, the catch-up, the wipe marker, in `SharedReplicaDatabaseLock` and `FileObjectStore`. **The three paths outside the lock brought in**: `open`, `wipeAllObjects`, `wipeFileObjectStore`. Rule D: the lock file created with `open(O_CREAT)`, the link-count check. The dev write probe removed or rerouted. No behaviour change with one process. | §11.2 I3, I7, I9, I10 | §11.4 step 0 (a) to (e) |
| **S0b-2** | **Gate and quiesce** (§5.3, §5.4): `SharedContainerLockGate`; the store lock changed from a blocking `flock` to the tried loop; `BackgroundQuiesce`, tokens, the expiration path; the entries of §5.4 that exist today wrapped in tokens; scoped dispatch in `ScheduleDrainLoop` behind `cancelOpsWithRun`; a wipe and a sweep chunked under 1 s. Background-launched runtimes start closed with loops stopped. ⚠ **The three existing wake entries** (`DriveLocalRuntime.runBackgroundRefresh`, `FitLocalRuntime.runBackgroundRefresh`, `ControlSurface.runPendingRequestRefresh`) take a token and **start the loops for its duration**: they drain by waking the loop, so with the loops stopped they would do nothing. That exception dies with each driver in S1c. | §11.2 I2, §5.4 | §11.4 steps 1 and 9; a simulated wake of each old driver still drains |
| **S0b-3** | ⚠⚠ **Open: schema migration of a shared store** (§5.7, M12). No mechanism chosen. Whatever is decided has to be in the first App Group build, because an older build cannot be taught afterwards. | to be written with the decision | two apps on different schema versions over one store: neither loses unsynced data, and neither reads rows it cannot parse |
| | ⭐ **From here, and not before M12 is decided and built, the App Group build may be installed.** | | |
| **S1a-1** | `ScheduleRunLease`, `RunOutcome`, `ScheduleRunPolicy`, `registrationFingerprint`, `waitMs`, the pure state machine, `AlwaysGrantedLease`. Loops and the two registry operations (`registrationChanging`, rule B) take them with defaults. No iOS binding, so no behaviour change on any platform. | §11.1; `ScheduleServiceTest` and `ScheduleRunGuardTest` unchanged | none: nothing changes on a device |
| **S1a-2** | `LeaseRecordFile` in `:core-common` iosMain and the `FileRunLease` adapter in `:control-shared-proxy` iosMain (§7.1); bound on iOS when the container exists; `ScheduleDrainLoop` leased under rule A; policy table with `nextReminder` app-local. | §11.1 I5; §11.2 `FileRunLease` | §11.4 step 4 with a registry item |
| **S1a-3** | `SyncScheduler` leased. | §11.1 §6.2 | §11.4 steps 2 and 3 |
| **S1b-1** | `toIosWakePlan`, `SchedulePromise`, `IosPlatformScheduler` bound at `PlatformProxyModule.kt:1107` with an empty identifier list, so it plans and submits nothing. `drainForWake`. `sync-drain` registered on iOS (M3) in the same commit as its `EXTERNALLY_DRIVEN` policy entry, never before it. | §11.1; iOS lane compiles | none: it submits nothing |
| **S1b-2** | `TenshuBackgroundWake` + `WakeHost`; `runDueNow`. Proves the Kotlin/Native `BackgroundTasks` binding. Still called by nobody. | §11.2 §7.5 | none |
| **S1c-1** | **Drive.** `App.init` calls `registerAtLaunch`; `BackgroundSync.swift` deleted; `runBackgroundRefresh` deleted; backup and upload reattach become Drive's app-local jobs. `BackgroundUpload.swift` stays. | smoke build | §11.4 steps 5 and 6 |
| **S1c-2** | **Fit.** `FitBackgroundRefresh.swift` deleted; the health import becomes Fit's app-local job. The EPC fallback branch (`FitBackgroundRefresh.swift:52-58`) moves into `FitWakeHost.ensureRuntime` returning `null` plus the app-local job. | smoke build | §11.4 step 7 |
| **S1c-3** | **Control.** `ControlBackgroundRefresh.swift` deleted; `runPendingRequestRefresh` becomes Control's app-local job, minus its fixed `delay`. | smoke build | a pending request alerts after a simulated wake |
| **S1c-4** | Calendar, Contacts, Maps, Photos, Timeline: identifier, `UIBackgroundModes: [fetch]`, one line in `App.init`. | smoke build ×5 | a simulated wake in one |
| **S1c-5** | Stale text and dead code: `PlatformScheduler.kt:19-20` and `:50-53` (iOS is not "already resident"); `ios-drive-parity.md` §15; the explicit `rehydrateSharedReplica()` calls, redundant since S0b-1; this doc and `ios-background-scheduling.md` get as-built notes. | `scripts/check-doc-refs.py`; smoke build ×8 | §11.4 step 0 (b) again, with the calls gone |

Why this order keeps master working: S0b changes no behaviour for a single process, and the old
drivers keep working through it by the exception in S0b-2's row. Until S1c-1 the three drivers are
untouched, and from S1a-3 on their drains go through the leased `SyncScheduler`: `syncNow` is a
wake signal, so it acquires in `Demand` mode. Each app in S1c swaps driver for runner in one
commit, with its identifier unchanged, so a request submitted by the old build is handled by the
new handler.

What each driver does today and what replaces it:

| Driver | Today (read) | Replaced by | Stays app-local |
|---|---|---|---|
| `BackgroundSync.swift` | registers `app.tenshu.drive.refresh`; arms +15 min if provisioned; on wake: install, rehydrate, `syncNow`, `backupNow`, sleep 15 s, report `true` | runner steps 0–10; the drain is leased and awaited | `backupNow`, `BackgroundUpload` |
| `FitBackgroundRefresh.swift` | registers `app.tenshu.fit.refresh`; arms +15 min; route 1: install, rehydrate, import, sleep 15 s; else EPC auto-connect then import; report `true` | same runner | health import; the EPC fallback |
| `ControlBackgroundRefresh.swift` | registers `app.tenshu.control.refresh`; arms +30 min, unconditionally; on wake: install, start alerts, re-poll, sleep 10 s, report `true` | same runner; Control now also drains when it holds the lease | pending-request refresh and alerts |

## 13. Decisions (maintainer, 2026-10-02)

⭐ **Accepted as recommended: M1 to M9** (v0.2; M2 since retired, M1 and M7 changed in meaning, M11 added and decided, **M12 added and open**: each is marked in its row). The *Recommendation* column is the decision. **M10 is
decided too: keep the gate and the quiesce step either way.** §11.4 step 1 then tells which failure
they prevent, a `0xdead10cc` kill or a sibling hung on a frozen holder's lock, not whether to build
them.

| # | Decision | Options | Recommendation |
|---|---|---|---|
| **M1** | How the lease detects a dead holder | (a) a record with heartbeat 10 s and TTL 30 s, `flock` only as a tried guard; (b) `flock` held for the whole run | **(a).** (b) holds a lock in the shared container for minutes, which is what §5 forbids, and it buys only a faster takeover after a crash. ⚠ **Meaning changed in v0.7:** the record, the heartbeat and the TTL stand, but there is no guard any more. The lease is written inside the store lock (§4.2). |
| **M2** | Lease the foreground sync cycle | (a) lease it, and a skipping process rehydrates when a sibling changed something; (b) lease only wakes and registry items, and let every foreground process keep pulling | ⭐ **Retired by layer 1 in v0.7.** The cycle is leased, unconditionally. The half of (a) that needed deciding, how a skipping process sees a sibling's pull and what that rehydrate costs, is no longer the lease's: the store catches every process up at its next access (§5.5). The cost that gated this decision is now §5.5's bad case, and M11 decides how it is paid. |
| **M3** | Register `sync-drain` as a `ScheduleItem` on iOS, as Android does | yes, 15 min; yes, 1 h (Android's value); no | **Yes, 15 min.** It gives `IosPlatformScheduler` one list to compute `earliestBeginDate` from and puts the drain in `ListScheduled`. 15 min is what Drive and Fit ask for today. |
| **M4** | Lease directory present but unusable | fail open (run unleased); fail closed | **Closed.** The only known cause is protected data being unavailable, where nothing should run. A missing container is a different case and stays open (§10.1). |
| **M5** | How a caller learns an item is best-effort | (a) Kotlin-only `SchedulePromises`, read in process; (b) a new operation returning it | **(a).** No reshape, no version bump. (b) is worth its bump only when an Android or desktop screen needs the answer over IPC. |
| **M6** | Which apps get a refresh task | all eight route-1 apps; only the three that have one | **All eight.** iOS budgets wakes per app from that app's usage, which is D2's whole argument. Cost: `UIBackgroundModes: [fetch]` in five more apps. |
| **M7** | Who owns the quiesce step | S0, or S1 as S1a-0 | **Whichever lands first; S1a-0 by default.** It must exist before the App Group reaches a device build that people use. ⚠ **Meaning changed in v0.7:** it is now its own sub-phase, **S0b**, together with layer 1, and it gates installing the App Group build (§12). "S1a-0 by default" reads "S0b, before the App Group build is used". |
| **M8** | Control's pending-request poll | keep as an app-local wake job until S4; drop now | **Keep.** D4 says the refresh tasks remain the floor under push. |
| **M9** | Identifier names | keep `app.tenshu.<app>.refresh`; move to `<bundle>.refresh` (`app.tenshu.<app>.ios.refresh`) | **Keep.** Renaming orphans a pending request and gains nothing. |
| **M10** | `flock` and the termination rule (§11.4 step 1) come back negative on the device | drop the quiesce step; keep it | ⭐ **Decided 2026-10-02: keep it.** Without the termination rule a suspended app keeps its `flock` for as long as it stays frozen, and every sibling blocks in `SharedReplicaDatabaseLock.lock()`, which waits for ever: an app hung at launch until the watchdog kills it. A kill at least frees the lock. |

| **M11** | What layer 1 does when a process is behind | (a) a change journal from the start, full rehydrate only as the fallback; (b) the generation counter and a full rehydrate; add a journal only if device numbers demand it | ⭐ **Decided 2026-10-02: (b).** Two reasons. A measured number exists: indexing 107,368 rows took 208 ms on the maintainer's iPhone, so a catch-up is about 0.2 s or more, neither negligible nor ruinous. And the journal was where the remaining complexity and the latest review findings sat (truncation against the epoch, the wipe against the epoch), while a counter has one ordering rule. **What reopens it:** §11.4 step 0 (e), two apps active with one pulling heavily, showing the reader stuttering or the pull slowed. The author had recommended (a) in v0.7. |

| **M12** | ⚠⚠ **Open, and a blocker for installing the App Group build (maintainer, 2026-10-02).** What happens when apps on different schema versions share one store | §5.7: (a) refuse to open a newer store and show the update surface; (b) migrate only when every installed app can read the result; (c) a minimum-version marker the older app honours at `open` and at every catch-up; (d) keep today's wipes, made crash-safe | **No recommendation asked for; none given.** The code today does (d) without the safety: an older build deletes a newer store, unlocked (§5.7). ⭐ **Decided 2026-10-03** in [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md) §7 (D5): (a) with (c)'s trigger. |

M3, M4, M5, M6, M8, M9 and M10 are unchanged by the restructure.

## 14. Not found, or contradicted

- **Not found:** any mention of `0xdead10cc`, or of "every session runs its own `SyncScheduler`",
  in `docs/tech-debt.md`. The nearest entry is "`SyncScheduler.backfillContentDeclarations` is
  single-flight per process" (`tech-debt.md:50`).
- **Not found:** a separate `PlatformScheduler` proposal. It is documented in
  `core-background-processing.md` §8 Phase 6–7 and in the interface's own KDoc.
- **Not traced:** where the embedding store lives on iOS (§3.2 note 2); which signal means a pull
  is caught up (§7.6); whether `BGAppRefreshTask` fires before first unlock (§9.3); whether Fit's
  recorder and Timeline's capture write to the shared store while in the background (§5.4);
  how often two processes write ranges of the same file through `IosFileDatabase`, which holds no
  resident copy but has no lock either (§5.5).
- **Found while tracing, and not this document's to fix:** `FileObjectStore.open` runs with no lock
  and can migrate or wipe a store a sibling has open; `wipeAllObjects` and `wipeFileObjectStore`
  run with no lock. §5.5 brings all three in. A newer-schema app migrating under an older sibling
  is S0's lockstep question.
- **Not measured:** `rehydrateFromDisk()` at the recorded store sizes (only the enumeration, once:
  208 ms at 107,368 rows, a figure of the maintainer's that is not in the repository); the
  per-access cost of a store lock entry; how the sync pull batches its commits (§5.5).
- **Contradiction:** `BackgroundSync.swift`, `DriveLocalRuntime.kt:339-343` and
  `ios-drive-parity.md` §15 against `ControlBackgroundRefresh.swift` and the code (§9.2).
- **Contradiction:** `PlatformScheduler.kt:19-20` and `:50-53` say iOS is covered by "the resident
  process's in-process loop". An iOS app that is not on screen is not running.
- **Contradiction:** `PlatformProxyModule.kt:191-193` says `group.app.tenshu.shared` is "declared in
  every shipped app's `.entitlements`". It is in the simulator entitlements only;
  `Drive.device.entitlements:13` and `Control.device.entitlements:13` say "NO App Group".
- **Against D2 as written:** D2 lists the sync drain among the items that "take the lease". On iOS
  it is not an item the registry loop runs (§3.2, §3.3), so the lease has to reach into
  `SyncScheduler` (§6.2, M2, M3). D2's
  intent holds; its mechanism needs that extra step.
- **Against D5 as written:** nothing. D5's caveat ("S1 must confirm Control's Keychain
  accessibility class allows it") is confirmed in §9.1.

## 15. Open review findings, and what happens to §5

### 15.1 Round thirteen, not addressed

Codex on `03be96a31`. All three are in §5 or its sub-phase.

1. **A wake that expires while the app is in the foreground stops that session's work** (§5.3,
   Rule P). The expiry path kills every token, stops the loops and closes the gate although the
   foreground is itself valid protection, and no later foreground transition reopens them.
   Expiration must cancel the wake and keep the gate and loops while the app is foregrounded or
   another live token exists.
2. **A large wipe cannot satisfy both Rule C and the one-second ceiling** (§5.5, §12). Rule C puts
   the intent, the new instance, every deletion and the marker removal under one store lock, and
   §5.3 says a wipe cannot be split; the sub-phase asks for it to be chunked below a second.
   Deleting tens of thousands of files under one hold breaks the ceiling; releasing without a
   resumable marker-and-cursor protocol lets a peer try to complete the whole wipe. No such
   protocol is specified.
3. **A fenced runtime has no way back** (§5.5, §12 S0b-1). After a sibling wipes or migrates the
   store, the fence makes every read through the current graph return absent and every write
   fail, and nothing defines the signal, the owner or the per-app lifecycle that replaces the
   graph. A running app stays empty and unwritable until the process restarts.

### 15.2 Why the loop was stopped

Thirteen rounds: four findings, four, two, four, three, three, three, three, four, three, two, two,
three. Each was real and each fix held, and the next round found a fresh one of the same weight in
another corner of §5: crash ordering, a path outside the lock, a stale descriptor, a fence, a wipe,
a lifecycle. That is what hand-building a multi-process, crash-safe store protocol over one file
per object looks like, and it is not a property of this document that more rounds would cure.

Decided 2026-10-02:

- This document is merged as a **draft**. §3, §4 and §6–§9 stand as the design of the lease and
  the scheduler, to be revisited only where the store design changes what they sit on.
- **§5 becomes its own design**, smaller and reviewed alone.
- **First, a read-only investigation:** whether the shared replica on iOS should be backed by
  SQLite, as the Android and JVM stores already are. It would give cross-process locking, atomic
  transactions and crash recovery from the engine, and a change counter (`PRAGMA data_version`)
  where §5 builds a generation file. What it would not remove: the suspended-lock rule (Apple
  documents `0xdead10cc` for SQLite locks in a shared container, so the gate, the protection
  tokens and quiesce stay), the schema-migration decision (M12), and the lease. Not known, and
  what the investigation is for: why the iOS store is one file per object, whether a SQLite driver
  is available for the iOS targets here, what the resident cache and the lazy index
  (`ios-store-lazy-hydration`) become, and what a backend swap costs.

**Outcome (2026-10-03).** The investigation and a device measurement
([`ios-store-sqlite-spike.md`](ios-store-sqlite-spike.md)) found the driver runs on iOS unchanged
and SQLite faster at everything measured; the maintainer decided the shared replica on iOS is
SQLite. **§5 is superseded by [`ios-shared-store-sqlite.md`](ios-shared-store-sqlite.md)**, which
keeps the gate, the tokens and quiesce, replaces the generation file, rule C and rule D with the
engine's transactions plus an epoch and a sequence in the database, resolves the three findings of
§15.1 (its §4.4, §5.1 and §6), and answers M12 (its §7: no build touches a store stamped newer
than itself; accepted by the maintainer 2026-10-03 as D5).
