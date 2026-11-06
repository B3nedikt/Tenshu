# Synced Folders Phase 2 — Control-side generic ingest engine + app-owned background triggers

- **Status:** **Design sketch**; the Android trigger is built — as-built in §4.1a/§4.1b (2026-10-23). Operationalises the *Background-service story*
  decision recorded in [`milestones.md`](../milestones.md) (2026-06-08)
  for the first ingesting app. **Gated on bgproc Phase 6/7** (the per-platform
  `PlatformScheduler` / Android WorkManager binding,
  [`core-background-processing.md`](core-background-processing.md) §5.2 / §8;
  [`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md) Path B). No code yet.
- **Date:** 2026-06-08
- **Owner:** Benedikt
- **Supersedes:** §§3.3, 3.4, 4.4 and Phase 2 of
  [`drive-synced-folders.md`](drive-synced-folders.md) — those sections describe a
  **Control-owned scheduler with a Control→app reverse "ingest now" trigger**.
  That framing is **withdrawn**: the maintainer decision (`milestones.md`) is
  *capture is app-side, policy is Control-side*, which **kills the reverse
  trigger** (see §1). This doc replaces those sections; the rest of
  `drive-synced-folders.md` (Phase 1 — shipped — the data-plane reuse, the
  device-local cursor, the storage gate, the UI) stands unchanged.
- **Related:** [`drive-synced-folders.md`](drive-synced-folders.md) (Phase 1) ·
  [`core-background-processing.md`](core-background-processing.md) (the
  three-layer model, `HeadlessSafe`, `ScheduleService`, `PlatformScheduler`,
  WorkManager binding) · [`bgproc-scheduling-roles.md`](bgproc-scheduling-roles.md)
  (`ConstraintSpec` enforcement, Path A/B) ·
  [`core-preference-service.md`](core-preference-service.md) (the
  `backgroundSyncConstraints()` resolver this reuses) · ADR 0019 (storage gating)
  · [`milestones.md`](../milestones.md) "Background-service story".

> **What this doc designs.** Three Control-side *generic* pieces — a
> background-ingest **policy** (constraints + storage gate), an enabled-source
> **registry**, and a **management/inspection surface** — plus the background
> **trigger**, whose ownership splits by platform: on **mobile** the app owns it
> (Android `WorkManager` + content-URI; iOS `BGTaskScheduler`) and wakes itself to
> enumerate with its own permission; on **desktop** Control hosts the watcher
> in-process, because Control is the resident process and no per-app grant forces
> capture out (§4.2). It does **not** touch the data plane (per-file encrypt +
> upload is reused verbatim, exactly as Phase 1) and introduces **no new IPC
> direction** (the central design property, §1). Everything generic here is built
> once and reused by Timeline location capture and Health vitals when they land.

---

## 1. The architecture, and the one thing that changed

Phase 1 ([`drive-synced-folders.md`](drive-synced-folders.md), shipped)
delivered the whole user-visible feature *while the app is alive*: an in-process
`ContentObserver`/`WatchService` drives `SyncedFolderController`, which uploads
new media through the existing per-file path. The remaining gap is the OneDrive
gap — **ingest must continue after the app is swiped away / after reboot**, with
no foreground process.

The original proposal closed that gap with a **Control-owned scheduler** that, on
its tick, sent a one-way "ingest now" signal *back* to the app, **starting the
app's process if it was dead** (§§3.3/3.4/4.4, §8.1). That is a new IPC direction
(today IPC is strictly app→Control) plus cross-app process-start.

**The maintainer decision (`milestones.md`) reverses that and kills the reverse
trigger.** The split is now:

- **On mobile, the ingesting app owns capture** — the OS permission
  (`READ_MEDIA_IMAGES`, a SAF grant), the enumeration, **and its own background
  trigger**. The OS only wakes the process that holds the permission; that is how
  every comparable backup app works. So Drive registers its *own* `WorkManager` job
  (Android) / `BGTaskScheduler` task (iOS) and **wakes itself**.
- **Control owns the generic plane** — the constraint/Wi-Fi/charging/**storage**
  policy, the **registry** of which sources are enabled, the
  **management/inspection** surface, and — unchanged — the crypto + relay data
  plane every opened FD already flows through.
- **Desktop is the principled exception (§4.2): Control hosts the watcher
  in-process.** The reasoning below is mobile-specific; on desktop none of it
  holds *and* the residency is inverted — **Control is the resident daemon, Drive
  is not**. So desktop capture lives in Control (no reverse trigger, no app process
  in the loop — Control enumerates *and* uploads in-process, it is already the data
  plane). This is the same decision resolved differently by platform residency,
  exactly as milestones' "each platform requires its own answer" anticipates.

The decisive reasons (full text in [`milestones.md`](../milestones.md)
"Background-service story"; summarised because they constrain this design):

1. **Data plane vs permission plane.** Control is the legitimate *super-app on the
   data plane* — it receives and correlates every app's realm data. It must
   **not** also become the *permission-plane* holder of every OS grant: a
   compromised grant-holder can bug the mic live, pull live GPS, and read
   on-device data the user never put in a realm — a strictly larger blast radius
   than seeing realm contents. Each grant stays in the app that owns it.
2. **Platform reality.** On iOS there is no local Control at all (External Proxy
   Connection — the proxy lives on another device), so enumeration is in-app *by
   force*. On Android, Play reviews sensitive permissions per app against a visible
   feature *in that app* — concentrating them in a control-plane app is a rejection
   risk. Per-app ownership is the approvable, cross-platform shape.
3. **No reverse trigger.** Because each app registers its own OS trigger and wakes
   itself, there is **no** Control→app "ingest now" tick and **no** cross-app
   process-start. Bytes still flow **app→Control** as today. The narrow new IPC
   surface the original proposal accepted is **avoided entirely**.

**The design consequence that shapes the mobile path: Control is a *pull*
authority, never a *push* driver.** Control holds the policy and the registry;
the mobile app *reads* them (over the existing app→Control IPC) each time it wakes
itself and acts on what it reads. Control never calls the app. This is what removes
the new IPC direction — and it relocates *enforcement* to the app's read path (§5),
with Control as the *authority of record*. (On desktop, §4.2, the watcher runs
inside Control, so the same policy + registry are read *in-process* — no IPC, and
Control is both the authority and the enforcer.)

```
            ┌──────────────── Control (generic, app-agnostic) ─────────────────┐
            │  • IngestPolicy   (ConstraintSpec + storage gate)   [authority]   │
            │  • IngestRegistry (which sources, enabled?, status) [authority]   │
            │  • Management/inspection UI ("Background ingest")                  │
            │  • crypto + relay data plane (unchanged)                          │
            └───────▲───────────────────────────────────────────▲──────────────┘
        app→Control │ read policy + enabled-state                │ app→Control
        (pull, on   │ report status      ┌──── upload bytes ─────┘ (unchanged
         each tick) │                    │      (existing op)       data plane)
            ┌───────┴────────────────────┴──────────────────────────────────────┐
   OS wakes │  Drive process  ── owns ──>  WorkManager job + ContentObserver      │
   the app  │  SyncedFolderController.ingestNow()  ── enumerates with its own     │
   (Android │  permission, diffs cursor, opens FDs, uploads via existing path     │
   WM /     └────────────────────────────────────────────────────────────────────┘
   desktop watcher / iOS BGTask)
```

---

## 2. Control-owned piece #1 — the ingest **policy** (reuse, don't rebuild)

The generic constraint/storage policy already exists in code; Phase 2 *consumes*
it rather than inventing a parallel one.

- **Constraints.** `backgroundSyncConstraints()` (`:control-shared`, preference
  Phase 5) already resolves `ControlPreferences.WifiOnly` /
  `MaxMonthlyDataMb` into a `ConstraintSpec` (`core-background-processing.md`
  §3.3). This is the *same realm-wide policy* that gates background sync — so
  "back up over Wi-Fi only" and the offline-keeper's "sync over Wi-Fi only" become
  **one switch**, not two, which is the whole point of "generic, shared across
  every ingesting app."
- **Storage gate.** `storageService.status().hasStorage` (ADR 0019, proposal
  §3.6) — already a hard gate in Phase 1's controller + settings screen. Control is
  the authority; the app re-checks it on each tick.

**Decision — the Phase-1 Drive-local `BackupWifiOnly` / `BackupChargingOnly`
preferences fold into the generic Control policy.** Phase 1 introduced them as
`DrivePreferences` because there was no engine to own them; Phase 2 re-points the
backup constraint to `backgroundSyncConstraints()` (the generic resolver) so the
user sets the network/charging policy *once* in Control and every source obeys it.
Per-source *override* of the global policy is plausible later (a source that may
run on cellular when the rest may not) but is **out of scope for v1** — one global
policy, read by every source. (The device-local *selection* of which buckets to
back up stays a Drive-side `DEVICE` preference, §3.1 of the original proposal —
unchanged; that is capture-side, not policy.)

**Policy is data, read by the app — not pushed.** When Drive's worker fires it
binds to Control and reads the live `ConstraintSpec` + storage status, exactly the
way any client op reads any service today. No new surface.

---

## 3. Control-owned piece #2 — the enabled-source **registry**

The registry is the generic record of *which background-ingest sources exist
across all apps, on this device, and whether they are enabled*. It is what makes
"Control owns which sources are enabled" + the management surface real, and it is
the structure Timeline/Health register into unchanged.

### 3.1 Shape — a synced `IngestSource` composite, not a preference blob

Phase 1's per-bucket selection is a `DEVICE` preference (a `TByteArray` blob —
fine for an opaque list). The registry is different: it is **queryable** (the
management UI lists it), it carries **live status** (last run, items pending,
last error), and it spans **multiple apps**. That is a schema composite synced
through the normal data plane, not a preference blob:

```kotlin
// generic, Control-side schema (module placement: §7)
composite(IngestSource.ID) {
    field(SOURCE_ID,     TString)   // stable per (app, source kind), e.g. "drive.gallery"
    field(APP_ID,        TString)   // owning app — "app.tenshu.drive"
    field(DEVICE_ID,     TString)   // device-scoped: a source is per-device (§3.2)
    field(DISPLAY_NAME,  TString)   // "Photos & Videos" — localised by the app at register time
    field(ENABLED,       TBoolean)  // the central kill switch (§5)
    field(SOURCE_KIND,   TString)   // GALLERY | DOCUMENT_TREE | LOCATION | VITALS …
    field(WATCHED_PATHS, TString, optional = true)  // desktop only: newline-separated raw dirs
                                                     // in unsandboxed development, or opaque security-scoped
                                                     // grants on MAS macOS; empty on mobile.
    // live status, written by the app on each run (§4 mobile / Control's loop on desktop):
    field(LAST_RUN_AT,   TInteger, optional = true)
    field(LAST_STATUS,   TString,  optional = true)  // IDLE | RUNNING | UP_TO_DATE | PAUSED_POLICY | OUT_OF_SPACE | ERROR
    field(PENDING_COUNT, TInteger, optional = true)
    field(LAST_ERROR,    TString,  optional = true)  // typed-error code, redaction-by-construction
}
```

It is **device-scoped but synced** — the same property the original §3.1 calls out
as a free win: another of the user's devices can read this device's source list +
status, which is exactly what a cross-device "manage all my devices' backups" view
in Control reads, for free, with no extra work. The registry is the user's own
metadata in their own realm; Control seeing it is *by design* (the data plane).

### 3.2 Why device-scoped and not realm-scoped

A `MediaStore` bucket id, a SAF tree URI and a desktop path are device-local
identities (original §3.1). The *source* that owns them is therefore per-device:
"Drive gallery on the Pixel" is a different row from "Drive gallery on the laptop,"
each with its own enabled-state and status. The `DEVICE_ID` field carries this;
the rows still sync (read-only from other devices, §3.1).

### 3.3 The ops (all app→Control, the existing direction)

A small generic service — working name **`IngestService`** (Service & Operation
pattern, like `ScheduleService`). Every op is the existing IPC direction:

```
IngestService:
    registerSource(SOURCE_ID, APP_ID, DISPLAY_NAME, SOURCE_KIND) -> IngestSource
        // idempotent upsert keyed by (SOURCE_ID, DEVICE_ID); app calls it while alive
    updateSourceStatus(SOURCE_ID, LAST_STATUS, PENDING_COUNT?, LAST_ERROR?, LAST_RUN_AT?)
        // app reports progress as it runs; drives the management UI live
    setSourceEnabled(SOURCE_ID, enabled)      // central kill switch — written by Control's UI
    isSourceEnabled(SOURCE_ID): Boolean        // read by the app on each tick (§5)
    listSources(): [IngestSource]              // the management UI's data source
    readIngestPolicy(): ConstraintSpec + StorageStatus   // app reads it each tick (§2)
```

`isSourceEnabled` / `readIngestPolicy` are the **app's pull path**;
`registerSource` / `updateSourceStatus` are the **app's push of its own state**;
`setSourceEnabled` / `listSources` back **Control's UI**. None of them is
Control→app — Control never initiates.

---

## 4. App-owned piece — the background trigger (per platform)

Each app owns the OS mechanism that wakes its own process. The app provides the
*worker body*; the constraint→OS mapping is shared machinery (bgproc Phase 6, §7).

### 4.1 Android — the app's own `WorkManager` job + content-URI trigger

Drive (not Control) registers, while it is alive, a `PeriodicWorkRequest` whose:

- **`Constraints`** are built from the policy it just read from Control
  (`ConstraintSpec → setRequiredNetworkType(UNMETERED)` / `setRequiresCharging` —
  the §5.5 mapping table). The OS then only wakes Drive *on policy*, so most
  off-policy ticks never cost a wake at all.
- **content-URI trigger** is added on the gallery collection
  (`addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)`), so
  a new photo wakes Drive promptly rather than only on the periodic floor.
- **`Worker` body** binds to Control, runs the **read-gate-then-ingest** sequence
  (§5), and reports status back via `updateSourceStatus`. The work runs **in the
  Drive process** with Drive's gallery permission — *not* in Control. (Contrast
  bgproc §5.2, where the worker dispatches a *Control* op; here the worker dispatches
  Drive's *own* `ingestNow()`, then uses Control only as the data-plane proxy +
  policy authority, exactly as the foreground app does today.)

`SyncedFolderController.ingestNow()` already exists as the Phase-1 entry stub
(memory: "the Phase-2 entry stub") — Phase 2 fills it and routes the worker into
it. The per-file uploads remain the existing `uploadPlatformFileCancellable` path
(N invocations of the shipped op — no new data surface, §6).

### 4.1a As-built (2026-10-23): automatic backup is WorkManager's; the foreground service is the user's

⚠⚠ **No automatic drain may start a foreground service.** Play's `dataSync` policy allows one for a
transfer the *user* started and names "daily auto backup of all new photos" as the counter-example
(`docs/how-to/play-console-answers.md` §13 D7); Android 15 also caps `dataSync` at 6 h / 24 h. Until
this change `SyncedFolderServiceLauncher` started `SyncedFolderForegroundService` on **every**
idle → backing-up edge — a new photo, a settings write, the WorkManager job itself.

| Path | Trigger | Runs under | Foreground service |
|------|---------|------------|--------------------|
| Automatic, app closed | `BackupWorker`: MediaStore content-URI job (re-armed each run), 6 h periodic floor, continuation job | WorkManager constraints = the user's switches + battery-not-low + storage-not-low (`backupJobConstraints`) | **never** — no `setForeground` either |
| Automatic, app alive | the in-process loop (`ContentObserver`, settings/selection/environment changes) | the drain's own gate | **never** |
| *Back up now* | the user's tap → `SyncedFolderController.backUpNow(ignore)` | the drain's gate, minus what the user confirmed ignoring | yes, `dataSync`, for that run only |

- **The stamp lives in the value.** A user run's frames are `BackupState.BackingUp(userInitiated =
  true)`; the launcher keys on `startsForegroundService(state)` and the service renders
  `foregroundServiceState(state)`, which reads an automatic drain as idle so a service still showing
  the user run's summary stops instead of re-promoting. ⚠ An earlier draft combined `state` with
  the controller's `userRequest` flow; the two propagate independently, and the controller test
  caught the pair showing the user run with **no** request (the request cleared before the
  aggregated state caught up). Don't re-introduce the pair.
- **`userRequest` is identity-cleared.** The trigger loop clears the request it served with
  `compareAndSet`, after `finishRuns`, so a second tap that lands while the first run finishes is
  carried into the next iteration rather than lost. A tap mid-drain promotes that drain (frames are
  stamped per file).
- **One-time override.** When the user's own Wi-Fi-only / charging-only switch is the *only* thing in
  the way (`overridableBlockers`: nothing offered with no network, no storage, Stop or backup off),
  the screen asks ("Use mobile data this once?" / "Back up without charging?" / both in one dialog)
  and the run ignores exactly that. The saved setting is never written; the next automatic drain
  obeys it. ⚠⚠ Wi-Fi-only is enforced **twice** — Drive's gate decides whether to hand a file over,
  Control's metered content gate whether to upload it — so a metered override also calls
  `BackupDataPlane.uploadOverMeteredOnce()` (Drive: `syncNow()` → the scheduler's one-shot
  `forceDrainOnce`) after each drain of the run. Without it the bytes reach Control and then wait
  for Wi-Fi. Charging-only is Drive's gate alone.
- **Why an FGS and not a user-initiated data transfer job (API 34+).** A UIDT job needs
  `RUN_USER_INITIATED_JOBS`, is JobScheduler-only (WorkManager does not expose it), and does not
  exist below API 34 — minSdk is 24, so the FGS path would stay anyway and there would be two. The
  user-started FGS is an allowed `dataSync` use; one mechanism is the smaller surface.
- **The job budget.** `BackupWorker` drains for `BACKUP_JOB_BUDGET` (9 min) via
  `drainInBackground(shouldYield)`, checked at **file boundaries** only, then enqueues
  `tenshu.drive.backup.continue` (`APPEND_OR_REPLACE`, the policy constraints, no content trigger) and
  succeeds. ⚠ Not `Result.retry()`: a retried content-trigger request keeps its trigger and would
  wait for the next photo. An **OS stop** (constraint lost, the 10-min limit) enqueues the same
  continuation, except when the app cancelled it (backup switched off, `REPLACE`). A yielded or
  stopped run stays *open* ("40 of 240", not "Backed up 40"); the next drain re-bases the total on
  its own enumeration (`RunAccumulator.interrupted`) instead of counting the remainder twice.
- ⚠⚠ **Nothing inside a run may `REPLACE` the content trigger.** When the run *is* that request,
  `REPLACE` cancels it: every content-triggered run used to end "cancelled" (stop reason 1, API-37
  emulator). A run re-arms with `APPEND_OR_REPLACE` when it is the content request (asked of WorkManager by id — a tag would miss requests an older build enqueued, which then KEPT themselves and left no trigger armed, seen on the emulator),
  else `KEEP`; only a live policy change in `BackupWorkScheduler` replaces it.
- ⚠⚠ **The scheduler acts on one-shot reads, not on its flow's values.** `observeEnabled()` seeds
  `false` ahead of the stored row, so the scheduler cancelled every backup job at each process start
  — including the one WorkManager had just started the process to run — and its first arm of a
  process `REPLACE`d a running content job. It now reads `readEnabled()`/`readWifiOnly()`/
  `readChargingOnly()` and replaces only when the constraints it armed with actually changed.
- **No notification for automatic runs.** The in-app footer and banner carry progress; a plain
  notification would duplicate them, and an ongoing one is what a foreground service is.
- ⚠⚠ **Tri-state enabled read.** The worker and the scheduler decide with
  `readEnabledOrNull()`: only a *stored* `false` cancels the jobs; "could not read" (identity
  unresolved, Control dead or slow) retries and cancels nothing. `readEnabled()` answers that case
  with `false`, and a WorkManager cold start used to cancel every backup job on it.
- ⚠⚠ **D7 is resolved for Drive only, not end to end.** The bytes leave the device from Control,
  and Control's `ProxyService` promotes itself to a `dataSync` foreground service whenever
  `proxyGateway.inFlightCount` (any client task — Drive's backup uploads included) or
  `BackgroundLiveness.active` (a push backlog) is above zero (`ProxyService.kt:81-87`). So an
  automatic backup no longer starts *Drive's* service but still runs under *Control's*. That is a
  separate change; the review follow-ups this PR left out are listed in `tech-debt.md`
  *"Synced Folders on WorkManager — review follow-ups"*.
- **Unchanged:** iOS (`DriveBackupSurface` keeps `ingestNow()` / `ingestAndAwait()`; `userInitiated`
  defaults to `false` and nothing there reads it) and desktop (`startRegistrationOnly`, Control's
  `FolderWatcher`). `FolderUploadForegroundService` (a folder the user picks) is untouched.

⚠⚠ **Owed — Control's network while Drive runs as a job.** The bytes leave the device from
**Control**, not Drive. Drive's foreground service used to make Drive a foreground client bound to
Control; a WorkManager job grants network to *Drive's* UID only. Control's `ProxyService` promotes
itself for the in-flight upload task (above), which is what gives Control the network today — but
that promotion is refusable from the background, and it is the very `dataSync` use D7 still has to
remove. Whether Control's inline upload gets the network while bound by a job-running Drive with the
screen off, once that is gone, is **unmeasured**
(`tech-debt.md` *"Android blocks Control's network in the background"* is the same question from the
other side). If it does not, the upload is journalled and Control's own scheduler sends it later —
delayed, not lost — but "backed up" on screen would then mean "handed to Control".

### 4.1b Is a stopped run resumable? Verified in the data plane, with one window

Read, not assumed (2026-10-23), from `UploadFileImplementation.uploadFile` and
`SyncObserver.emitFileContentOwned`:

1. Drive's cursor moves only past a file Control answered `Uploaded` / `Skipped`, so anything a stop
   interrupts is re-offered by the next drain (`anOsStopMidFile_losesNothing…` pins it).
2. Inside Control, the bytes are written locally, then the `File` row is saved, then the emitter
   **journals the content intent before any chunk moves**, then uploads inline. A cancel during the
   chunk `PUT`s is free: the journal owns the retry (it re-emits the whole file, not from the last
   chunk — D3 of `drive-upload-stop-and-reclamation.md`).
3. ✅ **The window, closed.** Control saved the row before it journalled the content, so a
   cancel *after* `saveData(fileEntity)` and *before* the journal write (`awaitEmitIdle`, the
   emit-key lock) left a row whose content nothing would upload, and the next drain got
   `AlreadyExists` → `Skipped` and advanced the cursor past it. With the job stopped by the OS
   whenever a constraint goes away, that was a routine path. That span now runs `NonCancellable`
   and ends with `FileContentSyncEmitter.journalFileContent`, so a row never exists without its
   journal entry (`UploadCancelKeepsRowAndJournalTogetherTest`); the inline upload after it stays
   cancellable. `tech-debt.md` keeps the record.

### 4.2 Desktop — Control hosts the watcher in-process (the principled exception)

On desktop the app-owns-capture model **does not fit**, for two reasons that
compound:

1. **Residency is inverted.** The resident process on desktop is the **Control
   daemon** (bgproc §5.3), *not* Drive — Drive-desktop is a Compose UI app the user
   launches, and the macOS integration already treats it as a non-resident IPC
   client. If the watcher lived in Drive, desktop ingest would run **only while the
   Drive window is open** — precisely the Phase-1 limitation Phase 2 exists to
   remove. The "runs without the app open" property requires the watcher to live
   where the residency is: **in Control.**
2. **No permission plane forces it out.** The mobile reasoning (the OS wakes only
   the permission-holder; Play reviews per-app grants; iOS has no local Control) is
   absent on desktop. On Linux/Windows there is no per-app gallery grant at all; a
   resident daemon already runs with the user's full filesystem access. So enumeration
   in Control is **not** a privilege escalation — Control could read those directories
   regardless of this feature.

**So desktop uses Control-hosted capture, in-process — and it is simpler than both
the original design and the mobile path.** Control's resident daemon runs the
`WatchService` loop itself, reads the watched paths + policy + enabled-state from
its own stores **in-process**, enumerates the directories, and uploads via the data
plane it already owns. **There is no reverse trigger and no app process in the
ingest loop** — the thing the original §4.4 needed a Control→app channel for
(waking a dead process) does not arise, because nothing is asleep and nothing
external is enumerating. (Contrast: the *original* "Control owns the scheduler"
pattern still had Control *triggering Drive*; this has Control *being* the ingester.
That distinction is why this is not a revival of the withdrawn reverse trigger.)

**What stays in Drive-desktop:** only the settings UI (the Phase-1
`SyncedFoldersScreen`, which writes the watched-paths selection) and the
`registerSource` call. The desktop selection is carried on the `IngestSource` registry entry (§3), so Control
walks it with **no dependency on `:drive-shared`** and no app-specific preference-key
knowledge. In unsandboxed development and on platforms without per-app filesystem grants,
that value is a newline-separated raw directory list. In a MAS macOS build Drive asks Control
to create a security-scoped bookmark and carries only an opaque grant handle (id + display name);
the bookmark itself stays in Control's container, where Control resolves it, closes the scope
around each scan, and refreshes a stale one under the same id (`desktop-macos-distribution.md`
§4.3.2). The folder-walk logic remains generic and
**Control-side** in the ingest engine, not in Drive — see §7. (Before the §5.3 daemon ships,
desktop falls back to the Phase-1 window-open behaviour, unchanged.)

**macOS caveat (the one place Control holds a capture grant).** On macOS, Drive's
picker request is handled by Control, which creates and later resolves the security-scoped
bookmark in the resident process. Reading
`~/Pictures` / the Photos library trips TCC, and the grant attaches to whichever
bundle does the reading — here, Control. This is acceptable and stays within the
milestones principle: that principle forbids Control becoming the holder of *every*
grant and of *ambient/live* sensors (mic, GPS) whose blast radius dwarfs realm data.
A resident desktop daemon reading user directories is neither — it is
least-privilege *per capability* (Control is the only resident process, so it is the
least-privilege home for a desktop watcher), it is static on-disk data not a live
sensor, and Control + Drive ship as a same-team suite under the MAS sandbox anyway.
Linux/Windows have no such grant, so this is a macOS-only note. Recorded as a
threat-model nuance in §6.

### 4.3 iOS — `BGTaskScheduler`, in-app, by force

iOS has no local Control (External Proxy Connection). The iOS app holds its own
photo permission and enumerates locally, registering a `BGAppRefreshTask` /
`BGProcessingTask` that wakes *the app*. It reads policy + enabled-state from the
*remote* proxy over the EPC. The trigger is iOS-native and app-owned — the same
shape as Android, confirming (not complicating) the decision. Deferred to the iOS
milestone; listed for completeness.

### 4.4 How policy / kill-switch changes reach an app that is closed (mobile only)

On **desktop** this section does not apply: Control reads policy + enabled-state
in-process on every watch event (§4.2), so a toggle takes effect on the next event
immediately. The propagation gap below is purely the mobile WorkManager case.

Because there is no push, a policy change made in Control while Drive is closed
does **not** instantly re-arm Drive's `WorkManager` constraints. Two paths close
the gap, and the residual is accepted:

1. **While the app is alive** it observes the policy + its enabled-state via the
   existing reactive/preference stream (Layer-1 sync keeps synced data fresh while
   any stream is open) and **re-registers** its `WorkManager` job with the new
   `Constraints` / cancels itself if disabled. This is the common case — the user
   toggles a setting and the app is open or opened soon after.
2. **On the next self-triggered tick** the worker's first action is to *read*
   `isSourceEnabled` + `readIngestPolicy` (§5). A source disabled centrally while
   the app was closed therefore **wakes at most once more** (a cheap no-op tick),
   reads `enabled = false`, **cancels its own `WorkManager` job**, and stops. The
   storage gate and Wi-Fi/charging are likewise re-checked here.

**Accepted residual (Google-Photos parity).** A constraint *loosened* in Control
while the app is closed (e.g. Wi-Fi-only turned off) takes effect only when the app
next runs and re-registers — the stale OS constraint is *more* restrictive, never
less, so it can never cause an off-policy upload; it can only *delay* an
on-policy one. The hard, immediate global stop is **device revocation**
(`PlatformScheduler.cancelAll` / WorkManager `cancelAllWork`, bgproc §6.2), which
tears down every job before the device's keys become unusable.

---

## 5. Enforcement lives on the app's read path; Control is the authority

The single most important behavioural rule of this architecture:

> **Every self-triggered tick begins by reading Control's authority — policy +
> enabled-state — and acts on it. Control sets the truth; the app enforces it.**

`ingestNow()` (and the in-process Layer-2 loop) runs this preamble before touching
the gallery. On **mobile** steps 1–3 and 5 are app→Control IPC reads/writes; on
**desktop** they collapse into Control's own in-process reads (no bind — Control
*is* the runner, §4.2), and step 2's "cancel own WorkManager job" is instead "stop
watching this source":

```
1. bind to Control (starts Control if down — the existing app→Control path)
       [desktop: no-op — running inside Control already]
2. enabled = isSourceEnabled(SOURCE_ID)
       if !enabled  -> cancel own WorkManager job; updateSourceStatus(IDLE); return
3. status = readIngestPolicy()                       // ConstraintSpec + StorageStatus
       if !status.hasStorage      -> updateSourceStatus(OUT_OF_SPACE); defer; return
       if !constraintAllows(spec) -> updateSourceStatus(PAUSED_POLICY); defer; return
4. for each watched bucket: listNewItems(sinceCursor) -> upload via existing op
       AlreadyExists -> silent Skip (§3.5 backstop, no user present)
       advance cursor on Uploaded/Skipped; hold + stop bucket on Failed
5. updateSourceStatus(UP_TO_DATE, pendingCount, lastRunAt)
```

This is why "no reverse trigger" costs nothing in correctness: Control's kill
switch and policy are *honoured on read*, at one-tick latency, by the only process
that can act on them anyway (the one holding the permission). The
`constraintAllowsSync(constraint, network)` pure function and `NetworkStateProvider`
seam from **Path A** (`bgproc-scheduling-roles.md` §4, already landed) are reused
verbatim for the in-app check; on Android the WorkManager `Constraints` are the
*first* line (the OS won't even wake off-policy), and this read-path check is the
belt-and-suspenders + the storage/enabled gate WorkManager can't express.

---

## 6. Data plane & threat model

**Data plane — unchanged, as in Phase 1.** Auto-upload is N invocations of the
existing per-file upload op; the relay sees the same ciphertext SyncBlocks as a
manual upload; assets A1–A4 / A9, block opacity, and the Drive↔Control boundary are
untouched (proposal §6 / threat-model §8.12). The registry (`IngestSource`) is the
user's own metadata in their own realm — synced as normal ciphertext blocks; the
relay learns nothing it doesn't already learn from any other synced composite.

**Threat-model amendments this phase makes (House Rule #2 — the edits land with the
code):**

- **Withdraw T-B-02 (the reverse-trigger placeholder).** §8.12 T-B-02 reserves a
  threat line for "Control→app reverse trigger / cross-app process-start (Phase 2
  only)." That mechanism is **cancelled** by the `milestones.md` decision — there is no reverse
  trigger and no cross-app process-start. T-B-02 should be rewritten to *record the
  withdrawal* (the attack surface it guarded against no longer exists; the design
  deliberately keeps IPC strictly app→Control), not filled in. **This corrects a
  now-false statement in the threat model and is done in this doc's worktree** (a
  correction, not a code-coupled amendment).
- **Add T-B-03 — background-clock pattern-of-life.** The threat
  [`milestones.md`](../milestones.md) explicitly names: even though every block is
  ciphertext, *when* ingest happens is observable to the relay (a burst of
  `POST /sync/changes` at 02:00 on Wi-Fi+charging is a pattern-of-life signal —
  "the user got home and put the phone on the charger"). *AD3 (relay sees timing),
  §10 metadata caveats.* **Mitigation:** this is the same A6/§10 metadata exposure
  the existing sync loop already has (the relay always sees push timing); auto-ingest
  *amplifies the regularity* (nightly, on a clock) rather than creating a new
  channel. The `flexPeriodMs` window (`ScheduleSpec`) and the OS scheduler's own
  batching already blur exact times; documenting it as an accepted A6 amplification
  (not a new asset) is the right placement. Revisit if/when timing-decorrelation
  (jitter, cover traffic) is ever sized — out of scope here.
- **Registry metadata note (no new asset).** `IngestSource` rows carry source
  *display names* and *status*, synced across the user's own devices. Same trust
  domain as any other synced composite; `LAST_ERROR` rides the
  redaction-by-construction seam (error-channel §8.11 / A19) so no path/content
  leaks into it. Noted, not a new row.
- **Desktop-only: Control holds the capture grant (§4.2).** On desktop, Control
  hosts the watcher, so on **macOS** the folder/Photos TCC grant attaches to Control
  (the resident data-plane app also becomes the capture-grant holder for this one
  feature). This is the only place the milestones "Control ≠ permission-plane
  god-app" principle bends, and it bends *within* the principle: a resident daemon
  reading on-disk user directories is least-privilege-per-capability (Control is the
  only resident process), not an ambient/live sensor (mic, GPS) whose blast radius
  the principle actually targets, and Control+Drive ship as a same-team sandboxed
  suite. Linux/Windows have no such grant. *AD3 (a compromised Control on desktop
  could read the watched dirs) — but a compromised resident daemon already has full
  user-FS access regardless of this feature, so no new capability is conferred.*
  Accepted; recorded so the macOS packaging work (MAS sandbox / TCC entitlements,
  milestones "Desktop distribution channels") wires the grant to Control knowingly.

T-B-01 (auto-egress consent) **stands** — opt-in, device-local revocable selection,
storage gate, Wi-Fi/charging policy, video opt-in; all still hold and are in fact
*strengthened* by the central kill switch (§5) which T-B-01 didn't have in Phase 1.

---

## 7. Module placement & what's generic vs per-app

The house rule (and the memory `feedback_minimal_existing_changes`) is: add new
content, prefer new modules over restructuring, and don't add a module without a
consumer. Phase 2 has three consumers in view (Drive now; Timeline + Health named
in milestones), which justifies the generic engine being its own home.

| Concern | Generic (Control-side) | Per-app (Drive-side, `:drive-shared`) |
|---|---|---|
| Constraint/storage **policy** | reuse `backgroundSyncConstraints()` + `StorageStatus` (exists) | mobile: reads it on each tick · desktop: Control reads in-process |
| **Registry** `IngestSource` + ops | **new `IngestService`** (+ `-common`/`-client`/`-proxy`, symmetric with `:notification-service` / `:preference-service`) | mobile app calls `registerSource` / `updateSourceStatus`; desktop registers + writes the watched paths onto the entry |
| Management/inspection **UI** | **new** Control screen (§8) | — |
| `ConstraintSpec → WorkManager.Constraints` mapping (**mobile**) | bgproc Phase 6 `PlatformScheduler` — **generalised to be instantiable in an app process** with an app-provided worker body (§9) | Android app instantiates it; supplies `ingestNow()` as the body |
| **Mobile** OS trigger registration | — | Drive owns its `WorkManager` job / `BGTask` |
| **Desktop** watcher + folder-walk | **Control-side, in the ingest engine** — generic `java.nio` walk over the registry entry's watched paths (no `:drive-shared` dep) | — (Drive contributes only the settings UI + `registerSource`) |
| Enumeration + cursor + FD open (**mobile**) | — | `MediaSourceProvider` (exists, Phase 1) — needs the gallery permission, so app-side |

The asymmetry to call out in the PR: **enumeration is app-side on mobile (the
gallery permission forces it) but Control-side on desktop (residency + no per-app
grant — §4.2).** **Decided (open-Q6): the desktop path is a clean, dedicated
Control-side `FolderWatcher`, not a relocation of `DesktopMediaSourceProvider`.** The
Phase-1 `DesktopMediaSourceProvider` (in `:drive-shared`) stays exactly where it is —
it remains Drive-desktop's enumerator for the *settings UI* (listing candidate
directories the user picks). The *runtime* desktop watcher is a separate generic
component in the engine: a `FolderWatcher` that takes the registry entry's watched
paths + the source-kind's file filter (e.g. "image extensions" for the gallery kind)
and runs `WatchService` + the `java.nio` walk in Control's process. No shared
interface couples the two, and `:ingest-service` gains **no** `:drive-shared`
dependency. The small cost — the dir-walk exists in two places (Drive's settings
enumerator + the engine's `FolderWatcher`) — is accepted: they serve different
processes with different lifetimes, and forcing them to share an interface would
re-introduce exactly the app↔engine coupling this split removes.

**Module decision (recommended):** a new generic `:ingest-service` family for the
registry **+ the desktop folder-watcher** (the watcher belongs with the engine now
that it is Control-hosted). It is the platform idiom (a service per cross-cutting
concern) and the multi-app consumer set justifies it. *Conservative fallback:* if
Timeline/Health feel far enough off, fold the registry ops into an existing
Control-side service (e.g. alongside `ScheduleService` in the sync family) and
extract `:ingest-service` when the second consumer arrives — settle when the code
lands. Either way the *shape* (the `IngestSource` composite + the six ops + the
Control-hosted desktop watcher) is what's load-bearing.

**One model point to flag in the PR (carried over from the original §3.3, still
true): an ingest source is an *app-resident* enumerate+upload, not a
Control-resident `HeadlessSafe` op** — because enumeration can't leave the app's
permission context. Under the new model this is even cleaner: the worker body is
purely app-side and never dispatches a Control op for the enumerate step (it uses
Control only as the data-plane proxy), so the `HeadlessSafe` machinery applies to
Control's *own* headless ops (sync-drain), not to ingest.

---

## 8. Control's management / inspection surface

A generic **"Background ingest"** screen in Control (alongside "Realm Devices" /
the Background Sync settings), driven by `listSources()` + the live status writes:

1. **Global policy rows** — "Back up over Wi-Fi only" / "Only while charging"
   (the generic `ControlPreferences` the Background-Sync settings already expose —
   one switch, §2) + the storage-gate banner if `!hasStorage`.
2. **One row per registered `IngestSource`** across all apps — icon + app name +
   display name + live status ("Drive · Photos & Videos — Up to date, 1,203 backed
   up" / "Paused — on cellular" / "Out of space"), and a **central enable toggle**
   (`setSourceEnabled`, the kill switch of §5).
3. **(Free, later) cross-device view** — because the registry is synced (§3.1),
   this screen can show *other devices'* sources read-only ("Laptop · Pictures —
   Up to date") with no extra work; deferred but unblocked.

This is the generic surface every future ingesting app inherits — Timeline and
Health each appear as new rows the moment they `registerSource`, with zero Control
UI work. It deliberately does **not** offer per-source folder selection (that is the
*app's* settings screen, where the gallery permission lives — Drive's Phase-1
`SyncedFoldersScreen`); Control shows the policy, the inventory, and the kill
switch, not the capture detail (proxy/client isolation, memory
`feedback_proxy_client_isolation_ui`).

---

## 9. Phasing (sub-steps within Phase 2)

All gated on **bgproc Phase 6/7** for the Android OS-trigger machinery; the
registry + management UI are buildable + desktop-verifiable *before* that.

- **2a — Registry + policy read + desktop watcher relocation (Control-side,
  desktop-verifiable now).** The `IngestService` (`IngestSource` composite + the six
  ops), `readIngestPolicy` wiring onto the existing resolver, and the §5 read-gate
  preamble. **Relocate the desktop watcher into Control** (§4.2/§7): Control's
  resident loop reads the registry entry's watched paths + policy in-process and
  uploads in-process; Drive-desktop keeps only its settings UI + `registerSource`.
  This is the *complete, end-to-end desktop feature* — full enforcement + registry,
  no Android SDK, fully desktop-testable. Fold the Phase-1 Drive
  `BackupWifiOnly`/`BackupChargingOnly` into the generic policy (§2).
- **2b — Control management/inspection UI (§8).** Generic "Background ingest"
  screen reading `listSources()` + the kill switch. Native + web; desktop-testable.
- **2c — App-owned Android trigger (gated on bgproc Phase 6).** Generalise
  `PlatformScheduler` to be app-instantiable with an app-provided worker body;
  Drive registers its own `WorkManager` job + content-URI trigger; the worker runs
  `ingestNow()`. **Device-gated** (no Android SDK in the build env) — keep the
  worker body's logic (the §5 preamble, constraint mapping) in commonMain
  desktop-tested, exactly as Phase 1 kept the testable logic out of the Android
  actuals.
- **2d — threat-model edits.** Withdraw T-B-02, add T-B-03, registry note (§6) —
  lands with 2a (the registry is the first concrete bit) per House Rule #2.

(iOS `BGTaskScheduler`, §4.3, and the cross-device registry view, §8.3, are
deferred to their owning milestones — iOS and a later Control slice respectively.)

---

## 10. Open questions

1. **Module home for the registry** — dedicated `:ingest-service` vs folded into an
   existing Control-side service until the second consumer arrives (§7). Recommend
   dedicated; settle when code lands.
2. **Per-source constraint override** — v1 is one global policy (§2). A source that
   may run on cellular when others may not (a small-payload Timeline ping?) would
   want a per-source `ConstraintSpec` override layered over the global one. Note,
   don't build.
3. **Status-write chattiness** — `updateSourceStatus` on every file vs debounced /
   terminal-only. Debounce to a low rate (the management UI doesn't need per-file
   granularity); decide at impl. The cursor (per-file progress) stays device-local
   and unsynced regardless (original §3.5).
4. **`PlatformScheduler` generalisation** — does bgproc Phase 6 build it
   Control-only (dispatching Control ops) and Phase 2c forks an app-side worker, or
   is Phase 6 built generic (app-instantiable, app-provided body) from the start?
   Recommend the latter — one constraint→WorkManager mapping, two owners — and
   coordinate the §7 generalisation into the Phase 6 PR. Settle with whoever lands
   Phase 6.
5. **Re-registration trigger granularity (mobile)** — §4.4 re-registers WorkManager
   when the app observes a policy change while alive. Confirm the preference/stream
   the app subscribes to fires on the generic-policy change (it should — `WifiOnly`
   is a synced `ControlPreferences` entry the offline-keeper already observes).
6. **Desktop watcher relocation mechanics (§4.2/§7) — DECIDED.** A clean, dedicated
   Control-side `FolderWatcher` in the ingest engine, **not** a relocation/sharing of
   `DesktopMediaSourceProvider` (which stays Drive-side as the settings-UI
   enumerator). The `FolderWatcher` takes the registry entry's watched paths + the
   source-kind's file filter — the only per-source data it needs from the app — and
   runs `WatchService` + the `java.nio` walk in Control's process, so `:ingest-service`
   carries no `:drive-shared` dependency. The accepted cost is a small dir-walk
   duplication across the two (different processes, different lifetimes); the gain is
   zero app↔engine coupling. See §7.
