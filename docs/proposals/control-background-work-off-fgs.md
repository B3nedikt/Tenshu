# Control background work off the foreground service — user-started work keeps the FGS, everything automatic becomes Control's own jobs

- **Status:** **Decided (option B), measured. Phase 1 shipped; Phase 2a (§5.6: the
  maintenance and index passes as Control's own jobs) built and emulator-verified; Phase 2b (§5.7:
  hand-off, Fit's marks, emitter backoff) follows it.** Measurements §3 taken 2026-10-24 on an
  API 37 emulator.
- **Date:** 2026-10-24
- **Owner:** Benedikt
- **Related:** [`core-background-processing.md`](core-background-processing.md) (§4.6 bulk-drain
  liveness, §5.2 the Android FGS + WorkManager split, Phase L2-LIVE / L2-DRAIN) ·
  [`drive-synced-folders-phase2.md`](drive-synced-folders-phase2.md) §4.1a (Drive's own half of
  the same Play constraint) · [`../how-to/play-console-answers.md`](../how-to/play-console-answers.md)
  · [`../how-to/play-store-declarations.md`](../how-to/play-store-declarations.md) ·
  `tech-debt.md` *"Android blocks Control's network in the background"*.

## 1. Problem

Google Play allows a foreground service (FGS) only for work the user notices and that cannot be
deferred or interrupted; deferrable work belongs in WorkManager. Android 15 also caps a `dataSync`
FGS at 6 h per 24 h, and calls `Service.onTimeout` when the cap is reached.

Control declares one `dataSync` FGS, `ProxyService`. Before this doc it promoted itself whenever
this sum was above zero (`ProxyService.onCreate`):

- `ProxyGateway.inFlightCount`: every in-flight client Layer-2 task, user-started or automatic
  (Drive's automatic backup `uploadForBackup` is one task per file);
- `BackgroundLiveness.active`: the sync push backlog, the content maintenance pass, the media
  index pass, model downloads and map region downloads;
- `TaskRegistry.activeCount`: every registered bulk task.

So Control asked for an FGS for automatic work (sync, maintenance, indexing, automatic uploads),
which Play does not allow, and it had no `onTimeout` handler.

## 2. Decision — option B

1. **The FGS is for user-started work only.** A client marks a request as user-started; the flag
   crosses IPC. Map-region and model downloads (always started from a screen) keep the FGS.
   `HostPairingForegroundService` (a user-started pairing session) is unchanged. `onTimeout` is
   handled.
2. **Everything automatic becomes Control's own WorkManager jobs**: the sync push, the
   maintenance pass, the indexing pass, and automatic client requests arriving while the caller is
   in the background.
3. **Behaviour with an app open does not change.** A bound visible client keeps Control alive and
   networked without an FGS (measured, §3.2).

## 3. Measurements (2026-10-24)

**Device:** a private emulator AVD (`fgs_measure`, deleted afterwards), Android 17 / API 37
(`google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260831.007`), debug builds of Control and Drive
from `origin/master` at `77679e2fa`, pointed at a local relay (dev mode) whose request log was the
network probe. API 34–36 images were not installed, so **only API 37 was measured**. The relay's
request log, `dumpsys netpolicy` (per-UID `blocked` / `allowed` / `effective`), `dumpsys activity
lru` (procState + capabilities), `dumpsys usagestats` and `dumpsys jobscheduler` were sampled
every ~1 s.

### 3.1 The background network block is on this image too

The block the maintainer's OnePlus showed (`tech-debt.md`) reproduces on stock API 37: with Control
cached (`procState=LAST`/`CEM`), `netpolicy` reads `blocked=APP_BACKGROUND, effective=APP_BACKGROUND`
and every relay request fails (`Connect timeout has expired`, `Failed to connect to /10.0.2.2`).
The block engages **about 5 s after** the process drops to a cached state: relay traffic continued
for ~5 s after pressing Home (16:12:24 → last request 16:12:29).

The policy keys on procState: anything below `TOP_SLEEPING` is allowed (`allowed=NOT_IN_BACKGROUND`),
so `TRNB` (transient background, what a process running a regular job gets) and `SVC` are allowed;
cached states are not. A job's own process additionally gets the `N` capability
(`cap=---N---TI`, power-restricted network), which also lifts the **Doze** block.

### 3.2 (a) Control's standby bucket when only Drive is used

| Step | Control bucket | Control procState / network |
|---|---|---|
| Control set to `rare` by hand, Drive killed (16:14:38) | 40 RARE | cached, blocked |
| Drive launched (16:15:09), binds Control | **10 ACTIVE**, `reason=u-si` | `BTOP`, allowed |
| 27 min later, Control never opened | 10 ACTIVE | — |

- **Binding from a foreground app counts as usage.** The rise is `reason=u-si` (usage, system
  interaction): a process that reaches a foreground-class procState (here `BTOP`, bound by a `TOP`
  app) reports a system interaction. It happened twice, both times at the second Drive started.
- The system-interaction hold is 10 min (`mSystemInteractionTimeoutMillis`), but the bucket then
  falls back to the threshold table, not straight to a lower bucket:
  `mElapsedThresholds=[0, 12h, 24h, 48h, 8d]`, `mScreenThresholds=[0, 0, 1h, 2h, 6h]`. Control was
  still ACTIVE 27 min after the last bind with the explicit expiry passed.
  ⚠ The decay **past** that point (WORKING_SET after ~12 h, FREQUENT ~24 h, RARE ~48 h, RESTRICTED
  ~8 days without any Tenshu app in front) is **read from those thresholds, not observed**: the
  image is a Play Store build, so `device_config put app_standby …` is refused without root and the
  thresholds could not be compressed.
- **Implication:** a user who opens any Tenshu client daily keeps Control in ACTIVE or WORKING_SET.
  Control drops to RARE only after about two days with no Tenshu app opened.

**Job deferral by bucket** (`dumpsys jobscheduler` constants, and runs triggered by the debug
`MaintenancePassReceiver`, unplugged):

| Bucket | Regular-job quota (per window) | EJ quota / 24 h | Observed EJ start latency |
|---|---|---|---|
| ACTIVE | 20 min / 1 h | 30 min | ~0.1 s |
| WORKING_SET | 10 min / 4 h | 15 min | — |
| FREQUENT | 10 min / 12 h | 10 min | — |
| RARE | 10 min / 24 h, ≤48 jobs | 10 min | **0.04 s** (16:44:04) |
| RESTRICTED | 10 min / 24 h, ≤10 jobs, 1 session | 5 min | **0.05 s** (16:45:58) |

An expedited job (EJ) starts at once in every bucket while it has quota. In RARE and RESTRICTED, a
regular job waits for its window. The guaranteed run time is 3 min for an EJ
(`runtime_min_ej_guarantee_ms`) and 10 min for a regular job (`runtime_min_guarantee_ms`).

### 3.3 (b) Network when the only thing keeping Control alive is a background client's job

To isolate the case, Control's own jobs were blocked with
`cmd appops set app.tenshu.control.android RUN_ANY_IN_BACKGROUND ignore` (0 `onStartJob` for Control
during the run), both processes were SIGKILLed via `run-as`, and 150 photos arrived in
`DCIM/Camera`. Drive's content-trigger `BackupWorker` started Drive, which bound Control:

| Time | Drive | Control | Control network | Relay PUTs |
|---|---|---|---|---|
| 16:34:06 | `TRNB` (BackupWorker running) | `TRNB` (bound) | allowed (`NOT_IN_BACKGROUND`) | — |
| 16:34:08–13 | job ended → `CEM` | `CEM` | allowed (5 s delay) → blocked at 16:34:13 | 4895 → 5015 |
| 16:34:19–35:03 | `TRNB` (next BackupWorker) | `TRNB` | allowed | 5023 → 6395 |
| 16:35:07 | `CEM` | `CEM` | blocked | — |

All 150 uploaded, with **no FGS** (3353 promotion attempts, all refused, §3.5).

- **A background client's regular job does give Control network**, through procState inheritance
  over the binding. It lasts only while that job runs, plus the ~5 s delay.
- **It does not lift Doze.** The `N` capability is not inherited over a plain binding. Under
  `deviceidle force-idle`, Drive's job did not run at all. In the maintenance window (16:38:59)
  it ran and Control (`TRNB`) uploaded all 60 photos. Back in IDLE the block was
  `DOZE|APP_BACKGROUND`.
- **Control's own job lifts both blocks.** Under deep Doze, Control's own EJ (16:40:25.59) got
  `cap=N`, `allowed=FOREGROUND|NOT_IN_BACKGROUND`, and `SyncScheduler` logged *"inbound pull
  recovered"* 0.4 s later.
  ⚠⚠ When that EJ finished 0.4 s later, the network went straight back to `effective=DOZE` while
  the maintenance pass it had started (3,276 manifests) was still running. That pass lost the
  network halfway. A pass needs a job that lasts as long as the pass.
- **A cached client is not enough.** When Drive was alive but cached (run 1, 16:20:15), its
  in-process `MediaStore` observer pushed 20 uploads into Control. Control stored them locally but
  could not reach the relay until its own expedited drain job started 4 s later (16:20:19.79,
  *"relay reachable again"*).
- **The binding is mutual.** Control binds Drive's `ClientCallbackService`. Whichever process runs
  a job lifts the other (Drive showed `cap=N` during Control's jobs), and killing Drive with
  Control alive restarts Drive at once (*"Start proc … for bound-service ClientCallbackService"*).

### 3.4 (c) Expedited-job quota in practice

What keeps Control networked in the background **today** is mostly an accident: the existing
expedited `DrainSync` backstop (`ExpeditedDrainScheduler`) enqueues on **every rising edge** of
`BackgroundLiveness.active`. During a backup the sync backlog empties after each file and refills
with the next, so it fires once per file:

| Run | Photos | Control EJ runs | EJ debit |
|---|---|---|---|
| run 4 (charging, quota not charged) | 300 | 746 in ~75 s | — |
| run 8 (unplugged, ACTIVE) | 100 | 165 | +5.9 s (4672 → 10531 ms) |

Each run lasts 25–300 ms. The quota cost is small against ACTIVE's 30 min, but it is ~1.6 jobs per
photo, and it only works because the jobs happen to overlap the drain. ⚠ Quota is not charged
while charging (`mIsCharging=true` on the emulator by default), so quota measurements need
`dumpsys battery unplug`.

### 3.5 (d) Is today's promotion refused when a background client triggers it? Yes, in a storm.

`ActivityManager: startForegroundService() not allowed: service …/.server.ProxyService;
[callingPackage: app.tenshu.control.android; … uidState: LAST; … BFGS denied: true; …
code:DENIED; … isBindService:false]`:

| Run | Trigger | Refusals |
|---|---|---|
| run 1 | 20 photos, Drive cached | 371 in 1 s (16:20:15) |
| run 4 | 300 photos | **4980** |
| run 5 | 150 photos, Control jobs suppressed | 3353 |
| run 8 | 100 photos, unplugged | 1958 |
| run 7 | debug maintenance pass under Doze | 21 |

The refusal is caught (`ForegroundPromotion` degrades to bound-only), so nothing crashes. But
`ForegroundPromotion.onCountChanged` **retries on every count change** while degraded. One refused
binder call to `ActivityManager` per task start/finish, thousands per backup, is wasted work and
log flooding. Promotion from a **foreground** client does succeed (`FOREGROUND_SERVICE_START` at
16:13:24 and 16:15:09, when Drive was launched).

### 3.6 Other findings from the same runs

- ⚠⚠ **The scheduled media index pass has no WorkManager job on Android.** Every Control start
  logs `MediaIndexPass -> schedule registration failed, indexing will not run:
  java.lang.IllegalArgumentException: Cannot set backoff criteria on an idle mode job`. WorkManager
  rejects a backoff policy on a `requiresDeviceIdle` request, and the pass sets both.
  - ⚠⚠ **The pass still runs**, despite what the log line says. `ScheduleImplementation` persists the
    `ScheduleItem` *before* `platformScheduler.register` throws, so the in-process
    `ScheduleDrainLoop` dispatches it whenever Control is alive. That loop enforces no constraints.
  - What holds a dispatch back is the pass's own runtime check, `IndexingConditions.allowIndexing()`
    (on power, battery not low, device not in use), which the runner reads before it launches.
  - Until review finding 1 it raised the user-started `MEDIA_INDEX_PASS` key, so a pass the loop ran
    could promote the `dataSync` FGS for hours of automatic work (§5.2, as-built §5.5).
  - ✅ Fixed in Phase 2a (§5.6): an idle-mode item carries no backoff, so the pass has its job, and
    the in-process loop no longer dispatches an item whose constraints it cannot check.
- A maintenance pass started without network (debug receiver while Control was cached) holds its
  single-flight lock for its whole timeout. Later triggers log *"skipped: a pass is already
  running"*.

## 4. What the measurements change in the design

1. **Option B holds.** Nothing measured needs an FGS for automatic work:
   - Control's own job gets the network in every bucket and under Doze (§3.3).
   - An EJ starts promptly even in RESTRICTED (§3.2).
   - The FGS is refused for exactly the automatic cases anyway (§3.5).
2. **"Behaviour with an app open is unchanged" is free.** A visible client gives Control `BTOP`
   and network (§3.2).
3. **A client's job is not a substitute for Control's own.** It gives Control network only while
   it runs, never under Doze, and not at all when the client is merely cached (§3.3). So Control
   enqueues its own push job for any backlog an automatic request leaves behind. It does not hand
   the request itself to a job: the request still runs in the caller's IPC call.
4. **One sync-push job must cover a whole burst**, not one per file (§3.4). The worker drains
   until the backlog is empty, waits a few seconds for more, and only then finishes.
5. **The maintenance pass and the index pass need jobs that last as long as they do** (§3.3, the
   Doze run), with a continuation when the OS stops them. That is Phase 2.
6. **Promotion retries must be throttled** (§3.5). Even user-started work can be refused, for
   example when it starts as the user leaves the screen.
7. **User-started bulk work must not demote between its calls.** Once the user has left, a
   re-promotion is refused. So the FGS lingers briefly after the last user-started call ends
   (§5.2).

## 5. Design

### 5.1 The user-started flag

- **Client:** `userInitiatedWork { … }` (`:core`, `UserInitiatedElement`) marks every Layer-2 call
  inside the block. It is a coroutine-context element, read at the same point as `withTask`'s
  `WithTaskElement`. Callers:
  - Drive's `uploadFolder`, which always starts from a picker;
  - the per-file upload of a Synced Folders run the user started with *Back up now*
    (`BackupState.BackingUp.userInitiated`).

  Automatic backup is never marked.
  - ⚠ A single picked file (`uploadFile`) is a plain `IoOperation`, not a Layer-2 task, so it never
    counted towards the FGS and the mark changes nothing for it.
  - Timeline's Takeout import on Android (`AndroidTimelineImportControl`, from the picker) is marked.
  - Fit's import is marked since Phase 2b (§5.7): a manual Health Connect import and every picked
    Takeout file. Fit's automatic pass is not. Before Phase 1 any registered bulk task held the FGS
    (`TaskRegistry.activeCount`); an unmarked one now runs like automatic work.
  - Control's own **realm-data reset** cannot be marked: it runs in-process from Control's UI and
    never reaches `ProxyGateway`. It holds the user-started `REALM_DATA_RESET` liveness key for
    its duration (`TenshuRealmDataClient`). No other in-process Control operation registered a task.
    Pairing has its own `HostPairingForegroundService`.
- **Wire:** a new appended AIDL method, `callCancellableUserInitiated(request, taskId)`, and
  `BinderIpc.REVISION` 2. The client calls it only when Control answers `ipcRevision() >= 2`, and
  otherwise falls back to `callCancellable` (an older Control promotes every task anyway).
  - ⚠ This is **not** a schema change. No composite moves, so no service bump and no
    `SchemaMigration` (HR #9).
  - Desktop, iOS and web have no FGS, so their transports ignore the flag.
- **Proxy:** `ProxyGateway` records the flag per in-flight entry. It exposes
  `userInitiatedInFlightCount` beside `inFlightCount`; the latter keeps its other duties.
- **Skew** (`core-schema-skew-surface.md` is not involved: nothing here can fence):
  - new client with old Control: works as before;
  - old client with new Control: its calls count as automatic, so Control holds no FGS for them.
    Drive's own *Back up now* FGS still keeps Drive, and through the binding Control (`BFGS`),
    alive and networked while Drive lives.

### 5.2 FGS gating, onTimeout, retry throttle

- **Promotion count.** `ProxyService` promotes on
  `userInitiatedInFlightCount + BackgroundLiveness.userStartedActive`.
  - The FGS-eligible sources are `MAP_REGION_DOWNLOAD`, `MODEL_DOWNLOAD` and `REALM_DATA_RESET`.
    `MEDIA_INDEX_PASS` was one until §5.6.1 moved *Index now* to its own long-running worker. A
    scheduled pass raises `SCHEDULED_MEDIA_INDEX_PASS`, which promotes nothing (§3.6).
  - `SYNC_DRAIN`, `CONTENT_MAINTENANCE_PASS` and `TaskRegistry.activeCount` no longer promote.
  - **A registered bulk task counts once one of its calls arrived marked** (`ProxyGateway` names
    the task id to `TaskRegistry.markUserInitiated`; `TaskRegistry.userInitiatedActiveCount`). So a
    user-started folder upload holds the service across gaps longer than the linger, such as
    `contentHashOf` over a multi-GB video.
- **Linger.** Demotion waits `FGS_LINGER` (15 s) after the count reaches zero, so a user-started
  folder upload or *Back up now* does not demote between files. Without the linger the next
  promotion would be refused once the user has left (§4.7).
- **Retry throttle.** After a refusal, promotion is retried at most once per
  `PROMOTION_RETRY_INTERVAL` (10 s), not on every count change. Going idle clears it (review
  finding 5): a fresh tap seconds after a refused run is a new user action, not a retry.
- **onTimeout.** `onTimeout(startId, fgsType)` (API 35+) and `onTimeout(startId)` (API 34) demote
  at once and stop the started lifecycle. This is the contract: the service must stop within a
  few seconds or the OS crashes it.
  - The machine is then **exhausted**: it makes no promotion attempt until the count has been
    zero and rises again (a fresh user action), and that attempt may still be refused. The OS
    refuses `dataSync` starts until the user brings the app forward.

### 5.3 The sync-push job

`SyncPushWorker` (`:sync-service-proxy` androidMain) is a dedicated WorkManager worker. It calls
`SyncScheduler.drainNowStalled()` and `pushableBacklog()` directly. It is not dispatched through an op id, so no op is added
and the schema does not move.

- **Trigger.** `SyncPushScheduler` (commonMain, JVM-tested) replaces `ExpeditedDrainScheduler`.
  It watches the `SYNC_DRAIN` source only (not the aggregate), and on its rising edge enqueues the
  unique work `sync-push` with **`APPEND_OR_REPLACE`**, coalesced in-process: at most one request
  this process enqueued waits to start.
  - ⚠ Not `KEEP`, and not a rerun flag (review finding 4). The worker reports back in a `finally`,
    before `doWork` returns. WorkManager still reads the work as RUNNING in that window, so a `KEEP`
    enqueue was dropped and the raised source never rose again. Appending queues the request
    behind it.
- **Constraint.** `CONNECTED`: the `SYNC_DRAIN` source only rises when the current network
  permits the push. It folds in the Wi-Fi-only `SyncGate`: metadata always, content only when
  `contentDrainable`.
  - If the job then runs on a metered network, `drainAndPull` still applies the gate itself.
  - Content the gate defers gets its own unique `sync-push-unmetered` job with an `UNMETERED`
    constraint, so it uploads when Wi-Fi returns rather than at the next hourly periodic drain.
- **Expedited only when small**, with `RUN_AS_NON_EXPEDITED_WORK_REQUEST`. "Small" means the
  content a push could send **now** is at most `EXPEDITE_MAX_BYTES` (32 MiB). Content the
  Wi-Fi-only gate holds counts as 0 (`drainablePendingContentBytes`, review finding 3). Counting it
  made a small metadata edit a regular job, which can wait hours in RARE or under Doze.
  - A large push runs as a regular job: a 10 min guarantee against an EJ's 3 min, and no EJ quota
    spent on bulk bytes.
  - ⚠ 32 MiB is about eight phone photos. A larger automatic backup's push becomes a deferrable
    regular job, which in RARE/RESTRICTED waits for the bucket's window. That is deliberate: an
    automatic backup is deferrable, and while the client's own job runs, Control has the network
    (§3.3). Kept at 32 MiB; raising it would spend the 5–10 min low-bucket EJ quota on one burst.
- **Body.** Drain; if a pushable backlog remains, drain again. When it is empty, wait up to
  `LINGER` (5 s) for the source to rise again, then finish. A run is bounded by `MAX_RUN`
  (8 min, under the regular 10 min guarantee) and then re-enqueues itself. Three corrections came
  from the device pass (§5.4):
  - **The linger restarts while a client request is in flight** (`ProxyGateway.inFlightCount`),
    **and the backlog was non-zero within the last 30 s** (`ACTIVITY_WINDOW_MS`).
    - An automatic backup uploads each file's content inside the client's own call, so the push
      backlog reads 0 between files. A run that trusted it alone finished mid-backup.
    - The 30 s window (review finding 9) stops a long Background op that leaves nothing to push (a
      region download, a pairing wait) from holding an idle job for its whole budget.
  - **A stall is the drain's own verdict** (`SyncScheduler.drainNowStalled`: no relay reached, or a
    write refused), never "the backlog did not shrink". During a backup the backlog refills as fast
    as it drains, and the backlog test stopped a healthy run after three drains.
  - **The stall retry (15 min) has its own unique name** (`sync-push-retry`). Under the push's name,
    `KEEP` dropped every fresh backlog behind the delayed retry.
  - **Progress is what the relay accepted** (`SyncPushDrain.sent`), not the backlog's size. It is
    counted per tick in `SyncScheduler.tickAccepted`: blocks, tombstones, and deferred content
    actually **uploaded** (`SyncObserver.deferredContentEmitted`).
    - ⚠ Not the content drain's `onProgress`: that counts *resolved* entries, a failed one included.
      On the emulator a pass where every PUT was answered 500 read as eight minutes of progress.
  - **A drain that neither stalls nor sends pauses 5 s** (review finding 2). One example is a
    content entry failing without flagging the tick (a content-plane 500, "someone already
    emitting"). Looping straight back ran a drain and a relay pull back to back.
    - **Two minutes without sending anything ends the run `STALLED`.**
    - So does a budget that ends without anything sent: `STALLED`, not `MORE`.
    - Only the drain declares a `stalled` tick.
  - **A stall parks the push until its retry** (`SyncPushScheduler.shouldRun`). Measured: 16 s
    after a `STALLED` run, the failing backlog's source fell and rose again, and a fresh job ran
    another 8 minutes, so the retry delay spaced nothing out.
    - While parked, fresh edges enqueue nothing.
    - Any job that is not the retry stands down at once, the unmetered backstop included. The
      retry is marked by `SyncPushWorker.KEY_STALL_RETRY`.
    - A run that sends something un-parks.
- **After the run, the `PostDrainHook`** (central notifications for pending requests) runs, as it
  did for the retired per-edge `DrainSync` (review finding 10).
- **Its notification** is only used on API 24–30, where an expedited job is a short foreground
  service (WorkManager's `SystemForegroundService`).
  - It passes **no** foreground-service type (review finding 8, the same fix in
    `TenshuOperationWorker`). That service's manifest entry declares none, and on API 29–30 a
    type not in the manifest makes `startForeground` throw.
  - A type is mandatory only from API 34, and API 31+ never uses this path.
  - Declaring `dataSync` on WorkManager's service would instead put a second `dataSync` FGS for
    automatic work in front of Play.
  - The channel name and text are string resources (en/de).
- **Continuation.** When the OS stops the job (quota, constraint lost, timeout), WorkManager
  re-runs the same request. The drain is resumable by construction, because the queue only
  dequeues what the relay accepted.
- **Unchanged:** the hourly periodic `DrainSync` (`BackgroundSyncScheduler`) stays as the floor.

### 5.4 Device pass (2026-10-24, API 37 emulator, this branch)

Same setup as §3: a private AVD and a local relay.

| Run | Before (§3) | This branch |
|---|---|---|
| 5 photos, automatic, Drive open | FGS start + stop | no FGS, 2 push jobs, 50 PUTs |
| 120 photos, automatic, both apps in background | 165–746 EJs, thousands of refusals | **0 refusals, no FGS**, 2 push jobs, all 120 uploaded |
| 100 photos, automatic, background, final build | — | **0 refusals, no FGS**. Push job 19:00:37 → 19:01:12 covers the whole backup and outlives Drive's `BackupWorker` (ended 19:01:03); one rerun job follows and finds nothing. All 100 uploaded; Control lost the network only at 19:01:32, after everything was sent. |
| 40 photos, *Back up now* (charging-only overridden), then Home | — | Control FGS **started** at the tap (19:04:24, Drive in front), **held through all 40 files after Drive left the screen**, stopped at 19:05:04, ~14 s after the last file (the linger). 0 refusals. |

The two intermediate builds that produced the §5.3 corrections are not in the table: one stalled
a healthy run, and one parked the push behind the stall retry. In both, the `sync-push-unmetered`
backstop finished the push, about 15 s late.
- ⚠ **That backstop fires whenever Control loses the network in the background**, not only under
  Wi-Fi-only. A blocked app sees no network, so the metered gate reads closed and `deferred` rises.
- It is harmless: one `KEEP` job, waiting for an unmetered network. It does mean "content held by
  Wi-Fi-only" is not the only thing that enqueues it.

### 5.5 Review round 1 (2026-10-25)

Findings from the Phase 1 review and what changed. Each new guard was broken once, and a test failed.
- **1. The scheduled index pass promoted the FGS.**
  - The in-process `ScheduleDrainLoop` runs it despite the failed WorkManager registration (§3.6),
    and it raised the user-started key.
  - Now `MediaIndexRunner` raises `MEDIA_INDEX_PASS` only for a manual pass, and
    `SCHEDULED_MEDIA_INDEX_PASS` (automatic) otherwise.
  - Enforcing the constraints in the in-process loop is not contained (desktop has no other
    driver), so it stays on Phase 2. On Android the pass's own `allowIndexing()` already holds a
    dispatch back unless the device is on power and not in use.
- **2. The spinning run.** No-progress pause, the 2-min no-send stall, parking, and progress =
  accepted writes (§5.3).
- **3. Expedite on drainable bytes only** (§5.3).
- **4. Fresh edges are `APPEND_OR_REPLACE`d and coalesced in-process** (§5.3).
- **5. Idle clears the refusal throttle** (§5.2).
- **6. Control's in-process reset** holds `REALM_DATA_RESET` (§5.1).
- **7. User-started registered bulk tasks count between calls** (§5.2).
- **8. No FGS type in worker notifications.** Strings are now resources (§5.3).
- **9. The client-work linger** needs a backlog seen within 30 s (§5.3).
- **10. The `PostDrainHook`** runs after each push run.

**Device pass** (private API 37 AVD; a local relay behind a proxy that can answer every content PUT
with 500):
- **Finding 1.** A scheduled pass ran in-process with the screen off and on power, with Drive bound
  (`MediaIndexPass -> visited=0 …` at 20:23:15).
  - **0 promotion attempts.**
  - The same build with only the key reverted logged `startForegroundService() not allowed` for
    the pass (20:24:54). So the setup does detect the attempt.
- **Finding 2.**
  - With the first fix (pause + `MORE`→`STALLED` on the backlog test), the run held the network for
    the full 8 min, about 3,000 failing PUTs a minute from the content emitter.
  - 16 s later a fresh edge started another run, which led to parking.
  - Overnight a run chained: content `onProgress` counted failed entries as progress, which led to
    accepted-writes.
  - Final build: the run started ~09:05:55 and ended `STALLED` at 09:07:39 (the 2-min limit). The
    retry is parked under `sync-push-retry` for 15 min. The queued push and the unmetered backstop
    both stood down within milliseconds (09:07:39, 09:07:55). Relay traffic stopped after 09:08.
- **Finding 6.** A reset of a small type on the Data screen finished too fast for the liveness
  flow to promote, so no FGS was observed. The key and `holding` are unit-tested only; a long reset
  is not device-verified.

### 5.6 Phase 2a: the maintenance and index passes as Control's own jobs (as built, 2026-10-25)

Both passes were already registered as WorkManager jobs (`TenshuOperationWorker`); three things kept
them from running under one.

**1. The in-process loop ran them with no job.** `ScheduleDrainLoop` dispatches every persisted
`ScheduleItem` by cadence alone, so on Android it ran an idle+charging pass at Control's startup,
with the screen on and nothing holding the network.
- **Decision: one driver per item.** `PlatformScheduler.ownsDispatch(item)` says whether the OS
  scheduler is an item's **only** driver, and the loop skips those items.
  - `WorkManagerScheduler` owns every item with a constraint the loop cannot check
    (`ScheduledWorkPlan.platformOwned`): charging, device idle, battery or storage not low, unmetered.
  - Not on that list: `CONNECTED`, no network, and a punctual one-shot (that is the alarm's, RE-D5),
    whatever constraints it carries. The calendar and routine one-shots and an unconstrained
    `DrainSync` keep both drivers.
  - ⚠ Punctuality is resolved from the op's schema at both call sites, `Schedule` and the loop, exactly
    as `register` resolves it. Before that fix `ownsDispatch` assumed "not
    punctual", so a punctual one-shot with an owned constraint would have been skipped by the loop,
    and a failed alarm registration would have withdrawn a user's routine or reminder row.
  - Desktop, iOS and the hub bind `NoOpPlatformScheduler`, which owns nothing. There the loop stays
    the only driver, as before. This is why enforcing constraints **in** the loop was rejected
    (§5.5 finding 1): desktop has no other driver.
- ⚠⚠ **Ownership is a function of the item, never of "registered in this process".** The loop reads
  persisted rows from its first tick, before this process's registrations have run.
- ⚠ This moves the subscription refresh, the thumbnail backfill, the slice snapshots and a
  Wi-Fi-only `DrainSync` to WorkManager alone on Android as well. Each declared constraints the
  in-process runs were ignoring. Their cadence is now WorkManager's period, not "also at every
  Control start".
- ⚠⚠ **The offline keeper asks for no network now**. Its evict half (cache
  ceiling, free-disk floor, thumbnail rungs) needs none, and under an `UNMETERED` item owned by
  WorkManager a mostly-cellular device would never have evicted. The download half refuses a metered
  network itself (`selectKeepOfflineDownloads(metered = …)`, fed by the factory's `meteredNetwork`).
- ⚠ Every platform passes its scheduler explicitly (`initSyncService(platformScheduler = …)`,
  `ScheduleDrainLoop`): there is no `NoOp` default for a wiring to forget.
  `ProductionSchedulesOnWorkManagerTest` (Robolectric) registers every production schedule through the
  real `WorkManagerScheduler` and pins each one's ownership.

**2. A failed registration left a schedule nothing runs.** `ScheduleImplementation` persisted the
row and then called `register`, which threw for the index pass.
- **Decision:** when `register` throws for an item the platform owns, the row (and any OS work under
  the key) is withdrawn and the `Schedule` op throws a typed error, which each `register…Schedule`
  helper logs as *"registration failed, X will not run"*. That line is now true.
- An item the loop still drives keeps its row on a failed registration. There the loop is a real
  driver and only app-closed wakes are lost, as before.

**3. The index pass's registration threw.** WorkManager refuses backoff criteria on an idle-mode
job (*"Cannot set backoff criteria on an idle mode job"*). `toWorkPlan` drops the backoff for every
idle-mode item, so a deferred pass's `Result.retry()` waits for the next idle window.

**And the job must stop the op.** `TenshuOperationWorker`'s dispatcher used plain `executeAsync`,
which starts an async op on the session's scope and only awaits it. A job the OS stopped therefore
abandoned the pass rather than stopping it: the pass ran on with no job and no network, and kept its
single-flight lock, so the job's continuation was answered *"a pass is already running"*.
`dispatchScheduledOp` runs the op as the worker's child, so the pass unwinds with the job and
WorkManager's rerun is the continuation.
- ⚠⚠ **And it still ends with its session**. The child scope took the op out of its
  proxy session's teardown: a session torn down under it (a client unregisters,
  `ProxyService.onDestroy`) left the op writing through a destroyed context, whose saves emit no
  `SyncBlock`. The op is now cancelled when either ends, and a session that ended under it answers
  the worker `ScheduledRunDeferred`, so the job retries in a live session.
- **Cold wake, measured** (API 37, `run-as kill` then a network-gated one-shot). JobScheduler
  started Control at 21:59:04.20. The worker ran at 05.049, 30 ms before the session's init
  installed the dispatcher (05.079), and answered retry; WorkManager ran it again 38 s later and the
  op ran. So a cold wake does reach the op, and no session needs minting. The worker now waits up to
  10 s for the dispatcher (`ScheduledOpDispatcherHolder.awaitDispatch`) instead of spending a retry.
- The content backfill raises no liveness key any more (`CONTENT_MAINTENANCE_PASS` is gone).
- The debug `MaintenancePassReceiver` enqueues a one-shot `CONNECTED` job
  (`WorkManagerScheduler.enqueueOneShotOp`) instead of running the pass in a detached scope.
- ⚠⚠ **A continuation starts the backfill from the beginning.** It is idempotent, not
  cursor-resumable: the walk revalidates its descriptions against the emit stamps it read before the
  walk (`ContentDeclarationBackfill.EmitGuard`), and those are per process. The index pass resumes
  for real: an already-indexed photo costs a stamp read. Two changes bound the cost:
  - **New devices never run it.** A realm this build creates, recovers or joins is stamped
    `contentDescribedFromGenesis` (`SyncManagerImpl.initialize` / `initializeWithKeys`): its content
    was described as it was uploaded, so the pass reads as complete. After the 2026-10 reset that is
    every realm. Only a device upgraded in place, with a pre-seam realm, ever runs it.
  - **Manifests resolve four at a time** (`FETCH_CONCURRENCY`), not one per round trip.
  - ⚠ The ceiling in photos, for that remaining case: a photo carries about five content objects
    (the file and its thumbnail rungs). Serially, at the phone's measured 12 manifests/s, a 30-minute
    job covered about 21,000 manifests, ≈ 4,000 photos. Four wide, it is up to four times that;
    beyond it the pass never completes (`tech-debt.md`).

#### 5.6.1 *Index now* as a WorkManager long-running worker (the maintainer's decision, 2026-10-28)

*Index now* runs as `ManualIndexWorker`, a long-running worker with its own foreground notification,
not under Control's `ProxyService`: Google's idiomatic home for user-started long work.
- **Type:** `mediaProcessing` on API 35+ (`FOREGROUND_SERVICE_MEDIA_PROCESSING`), `dataSync` below.
  Both are declared on WorkManager's `SystemForegroundService` by a manifest merge. The expedited
  push and scheduled-op workers pass no type, which stays valid against that declaration.
- **Notification:** *"Indexing photos — 1,240 of 8,500"* (DE *"Fotos werden indexiert — 1.240 von
  8.500"*), numbers in the device's locale, and *"Indexing photos…"* while the library walk has no
  total yet. A determinate progress bar, and a **Stop** action (`createCancelPendingIntent`).
- **Stopping** (Stop, the OS, the 6-hour type limit) cancels the worker, and
  `MediaIndexRunner.runManualToCompletion` cancels the pass with it. It ends at its next photo; the
  stamps keep what it did, and the next idle+charging pass continues.
- **Constraints:** battery not low only (`inference-service.md` §34). Not expedited.
- `StartMediaIndexPass` still answers its refusals (paused, no model, no consent, not ready) at the
  tap, then hands the pass to the platform's `ManualIndexLauncher`. Desktop and iOS have none and
  start it in-process, as before. `MEDIA_INDEX_PASS` left `USER_STARTED_SOURCES`.
- **Device pass** (API 37 AVD, both models installed, 256 photos): the tap enqueued the work at
  22:19:45 and the worker started 1.2 s later. `SystemForegroundService` ran with
  `types=0x00002000` (mediaProcessing) on the `tenshu_media_index` channel. The notification read
  *"Indexing photos — 51 of 256"* → *59* → *64* → *87*, with the bar at the same figures. Stop in
  the shade at 22:21:35: WorkManager cancelled the work (reason 1), the foreground service and the
  notification were gone, and Control's CPU fell to 0.0%.

**Guards, each broken once with a test failing:**
- `PlatformOwnedScheduleTest`: the loop's skip, the withdrawal on a failed owned registration, the
  child dispatch, the session-teardown link (S1), and the installed dispatcher.
- `ScheduledWorkPlanTest` and `ProductionSchedulesOnWorkManagerTest`: the idle-mode backoff drop (the
  real WorkManager throws), and punctuality in the production `ownsDispatch` (S5).
- `ContentBackfillWiringTest` and `ContentDeclarationBackfillTest`: the genesis stamp, its read, and
  the four-wide fetch (S2).
- `MediaIndexRunnerTest` and `ManualIndexWorkerTest`: Stop cancelling the manual pass, and the
  foreground type per API level.

**Device pass** (private API 37 AVD, local relay behind a logging proxy, 150 photos backed up by
Drive → 900 content references; 150 ms added to every content `GET` to stretch the pass):
- **Registration.** No *"registration failed"* line on any start. WorkManager now holds the index
  pass's job: charging + idle + battery not low, no network, 12 h.
- **The loop leaves the index pass alone.** Cold start with the screen off and on AC power, which is
  when the loop used to run it (§5.5 finding 1): no pass in 2 min. The pass's own job, force-run,
  ran it at once (`MediaIndexPass -> visited=0 … noBackend=150`, no model installed), and the job
  ended with it.
- **The maintenance pass under its job, from the background.** Control cached and blocked
  (`effective=APP_BACKGROUND`), Drive stopped. The debug receiver enqueued the job at 12:23:36. The
  job started at once, Control went `TRNB` and networked (`allowed=NOT_IN_BACKGROUND`) in all
  127 one-second samples while it ran. It resolved about 5 manifests a second (7 content `GET`s).
- **Stopped mid-pass.** `cmd jobscheduler timeout` at 12:24:59.394 (`onStopJob`). The pass logged
  *"pass stopped before it finished"* 3 ms later, and WorkManager's rerun started a fresh pass
  50 ms after that. The rerun walked all 900 again and ended *"described 750 contents … recorded
  complete"* at 12:27:44, and its job ended with it. Control was back to cached and blocked within
  seconds.

### 5.7 Phase 2b: the background hand-off, the emitter's backoff, Fit's marks (as built, 2026-10-25)

Measured first, on the same private AVD and local relay as §5.6. The proxy in front of the relay
could answer every content `PUT` with 500, or delay each one by 20 s. A 49 MB photo (25 chunks)
then uploads for minutes.

**What a background hand-off actually looks like.** Drive's BackupWorker job was denied
(`RUN_ANY_IN_BACKGROUND ignore`) and stopped once the photo's upload was under way:
- The job ended after 0.3 s. Drive's **in-process loop** then drove the upload, on the client's
  call, with no job behind it (`tech-debt.md`, *Synced Folders on WorkManager*, follow-up 1).
- ⚠⚠ Control, cached with that call outstanding, was **killed by the OS** about a second after the
  network went: *"Kill app due to repeated failure to freeze binder … excessive binder traffic during
  cached"* (exit reason `EXCESSIVE_RESOURCE_USAGE`). A client handing off bulk work can therefore cost
  Control its process, not just its network.
- So the hand-off is only as good as **a Control job already queued** when that happens. The journal
  is written before the first byte moves (`drive-upload-progress.md` P4), so the next Control job
  resumes the upload from its landed chunks. In both runs Control's own push job finished the bytes.
- What delayed it was the push's own state: a stall's 15-minute park (§5.3). The plane had healed
  and Control's in-process loop had drained a whole backlog, but only a push run ended a park. A
  parked push queued no job, so nothing restarted the killed Control until the retry, eight minutes
  later.

**Changes:**
1. **Progress is any chunk the relay accepts** (`SyncObserver.contentChunksLandedTotal`, read by
   `SyncScheduler.drainNowStalled`). The drain answers an entry a client is uploading inline
   "someone already emitting". A job that counted only its own sends read one long upload as two
   minutes of nothing sent, ended `STALLED` and took Control's network with it.
   - ⚠ **But a run's continuation needs something finished** (`SyncPushDrain.completed`: a block, a
     tombstone, a whole file). A file that changes under every attempt re-sends its chunks each time
     and never completes; counting the chunks chained `MORE` runs for ever. A run that finished
     nothing in its 8-minute budget ends `STALLED`.
2. **A park ends as soon as a file's content lands, on any path**
   (`SyncScheduler.contentProgressTotal`, from `SyncObserver.contentEmitsLandedTotal`: the drain's
   uploads and a client's inline ones). ⚠⚠ Content only: with only the content plane
   failing, metadata blocks keep landing, and counting them un-parked the push into another failing
   run every 2–3 minutes. A park a metadata outage caused ends by time, or when content lands.
3. **While parked, a watcher looks every 10 s** (`SyncPushScheduler.watchThePark`, under the
   scheduler's job). The `SYNC_DRAIN` source stays raised during a park, so ending the park by
   progress alone produced no edge. On progress the watcher queues the push for the backlog already
   waiting. ⚠ On either end — progress or time — it registers the backstop if content is still held
   for Wi-Fi, and so does the stall retry itself (`shouldRun(isStallRetry = true)`) when this process
   is gone: `deferred` stays raised through a park, so no new edge would bring the backstop back, and
   held content waited for the hourly drain.
4. **The deferred-content backstop is not registered while parked.** A blocked app sees no network,
   so `deferred` rose each time Control dropped back to cached, including after every backstop job
   that stood down because of the park. Measured: a job every ~16 s for the whole park, against a
   RARE bucket's 48 jobs a day.
   - ⚠ The park is in memory: a new `SyncPushScheduler` (a Control restart) starts un-parked, and its
     first edge gets a job. The queued retry still runs.
5. **Per-entry backoff in the content drain** (`ContentEmitBackoff`): an entry whose content `PUT`
   the relay refused waits `30 s × 2^(n-1)`, capped at 15 min, with equal jitter.
   - Any upload that lands ends every wait, inline ones included: a healed plane does not leave a
     backlog sitting out its waits.
   - The user's *upload over cellular* override never waits.
   - ⚠ Only the **plane** failing is backed off (`isContentPlaneOutage`): a `5xx`, a `429`, a
     transport failure. Bytes that changed under the stream, a holder that moved on, someone else
     emitting, a `507` (which stops the pass and has its own scheduler backoff), a `402`/`401`
     (their own backoffs) and a `4xx` about the object are not. ⚠ One breaker serves every realm,
     which is why the classification matters: a lapsed shared realm's `402`s used to hold the
     personal realm's uploads.
   - In memory by decision: a restart costs one attempt per entry.
   - ⚠⚠ **And a plane-level breaker** (added after the merged-tree device pass): after
     `PLANE_TRIP` (5) refused writes in a row, on any path, with nothing landing, the whole plane
     waits on the same schedule, counted in trips. The drain skips every entry and an inline upload
     only journals. Per-entry waits bound only the *repeats*: on a 1,095-entry journal the first
     attempts alone were 3,458 failing `PUT`s (drain only) or about 9,000 (with Drive's inline
     uploads) before the push stalled. A refused write is recorded where it happens
     (`emitFileContentNow`), so inline failures count.
   - ⚠⚠ The check is read for **every** entry. It used to be skipped once something had landed in
     the pass, so against a relay failing most `PUT`s (one lands, then a run of `503`s) the drain
     sent the rest of the journal anyway. A landing already clears every wait.
   - ⚠⚠ **Half-open on a recovery signal** (`ContentEmitBackoff.halfOpen`): a user-started call
     (`ProxyGateway.onUserInitiatedTask` → `SyncScheduler.retryContentPlaneNow`), the network coming
     back (the reconnect edge), the app coming to the front (`notifyForegrounded`, today the web
     runtime's only), and *Sync now*. Only a landing ended the wait before, and nothing could land
     while it waited, so after a long outage recovery took 7.5–15 min. Half-open lets the next
     write through and keeps the trip count: one more refusal trips it again, at the next, longer
     wait.
   - While the plane waits, its content is **not pushable** (`SyncScheduler.contentDrainable`): a
     push job no longer runs ~2 min for nothing and parks. A run that ends `IDLE` with the plane
     waiting queues one run for when the wait ends (`SyncPushScheduler.PLANE_WORK_NAME`), because
     the backlog never emptied and no edge would come.
   - **The banner says so**: `SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING` (a new value of the
     existing string, so no reshape) becomes `SyncBannerState.Syncing.relayRetrying` in Drive —
     *"Uploading 0 of 12 · server errors, retrying"* instead of an unexplained *"0 of 12"*.
6. **Fit's import is marked** `userInitiatedWork { }`: a manual import (`startImport(automatic =
   false)`) and every file import. The automatic pass is not marked.

⚠ **Not changed:** an automatic request still runs in its caller's IPC call (§4.3). A push over
`EXPEDITE_MAX_BYTES` is still a regular job, which may wait for the bucket and JobScheduler's
flexibility window.

**Guards, each broken once with a test failing** (`ContentEmitBackoffTest`,
`SyncPushInlineProgressTest`, `SyncPushSchedulerTest`, `DeferredIntentSurvivesAMoveTest`,
`ImportUserInitiatedTest`, `FileImportUserInitiatedTest`, `SyncBannerTest`):
- the per-entry skip (pinned with fewer than `PLANE_TRIP` refusals, so the breaker cannot mask it),
  the reset on an inline landing and on a drained one, and the `force` bypass;
- the outage classification (`5xx`/`429`/transport only) and that a `507`, `402`, `401` or `413`
  neither trips the plane nor makes an entry wait;
- the plane breaker: the trip, the drain's per-entry check after an early landing, the inline gate,
  the failure recorded at the write, and half-open;
- a waiting plane out of the pushable backlog, reported as retrying, and the banner's reason;
- inline chunks as progress but not as a completion, the observer's chunk and emit counts, and a
  chunks-only run ending `STALLED`;
- the un-park on completed content only, the park watcher, the backstop held while parked and back on
  a park ended by time or progress or by the retry running, and the plane retry after an `IDLE` run;
- Fit's manual mark and its file-import mark.
- ⚠ Not pinned by a test: that bytes changing under the stream do not count. It holds by
  construction — `failed()` is called at the two `PUT`-failure sites only.

**Device pass.** Run back to back on one AVD: Phase 2a's build as *before*, this branch as *after*.
- **The storm.** 20 photos (100 content entries), every content `PUT` answered 500, both apps in the
  background.
  - Before: 6,045 failing `PUT`s in about 2.5 min (peak about 4,300 a minute), until the push stalled
    and parked.
  - After: 501 in 5 min. About 200 in the first minute, then tens a minute as the waits grow.
  - After the stall, before vs after: a backstop job every ~16 s vs none.
  - **Re-run on the tree merged with master (2026-10-28), 219 photos (1,095 entries).** The
    per-entry backoff alone gave about 9,000 failing `PUT`s in 3.5 min with Drive's inline uploads,
    and 3,458 in 2.5 min from the drain alone after a Control restart. With the plane breaker the
    same journal cost **15 in 4 min** (drain only). Once the plane healed, the whole journal uploaded within about 30 s.
    ⚠ That 30 s held after a **short** outage, where the plane's wait was still short. After a long
    one the wait is up to 15 min, which the half-open triggers above now cut short.
  - **After the Phase 2b review fixes** (2026-10-28, 20 new photos = 100 entries, every content `PUT`
    500, Drive in the background): 25 failing `PUT`s in 4 min, five per breaker opening. Each push
    job ended in ~10 s, the content not being pushable while the plane waited, and queued its plane
    retry. Drive's Files tab read *"Uploading 1 of 100 · server errors, retrying"*. Healed with no
    half-open trigger: the wait ended ~1.5 min later and all 200 `PUT`s landed within ~20 s.
- **The hand-off after a stall.** The 49 MB photo, 20 s per `PUT`, Drive's job denied.
  - Without the watcher (this branch's first build): the cached Control was killed at 13:31:00
    with 2 chunks landed. Nothing restarted it until the stall retry at 13:39:01, which then
    finished the upload.
  - The Phase 2a build showed the same wait from a different side. The push stayed parked from an
    earlier stall after the in-process loop had drained that whole backlog, and the next upload
    resumed only under the retry, 6.5 min after the client's job ended.
  - After: the first chunk landed at 13:51:56 while parked, and Control's push job started at
    13:52:06, one poll later. It held Control alive and networked, in all 447 one-second samples the monitor took. Control
    was not killed this time. The push ran as three job runs, the budget's `MORE` continuation
    included, and finished at 14:14:35: the photo and the 25 entries left waiting by the stall
    (168 `PUT`s at 20 s each).

## 6. Phases

- **Phase 1 (this PR):** §5.1 the flag (client element, AIDL method, gateway count), §5.2 gating,
  linger, throttle and `onTimeout`, §5.3 the sync-push job, and the Play declaration docs.
- **Phase 2a (§5.6):**
  - ✅ the maintenance pass as a periodic job held for the pass, with a continuation when stopped;
  - ✅ the media index pass as an idle+charging periodic job with a continuation. This also fixes the
    registration bug in §3.6.
  - ✅ *Index now* as a WorkManager long-running worker, `mediaProcessing` on API 35+ (§5.6.1).
- **Phase 2b (§5.7):**
  - ✅ the background client hand-off. Not as first written ("the request runs under a Control job"):
    the request still runs in the caller's call, and a Control job is queued whenever the push has
    a backlog, a stall's park included, so Control's own job finishes the bytes when the caller's job
    ends.
  - ✅ Fit's import marked (Control's reset was done in Phase 1).
  - ✅ per-entry backoff in the content emitter.
  - Re-measure §3.3 on a real phone (the OnePlus, ColorOS) and on API 34–36 when images are
    available.

## 7. Not verified

- The smoke gate and the device pass ran on emulators only. No phone has run this branch.
- Only API 37 (emulator) was measured. ColorOS adds its own background restrictions, and none of
  §3 has been repeated on a phone.
- The bucket decay past 27 min (§3.2) is read from the thresholds, not observed.
- Phase 2a: the continuation was triggered with `cmd jobscheduler timeout`, not by the real
  10-minute limit, and the index pass's continuation is unit-tested only (with no model installed
  a pass takes half a second). No Doze run of the maintenance job: PR 1 measured that Control's own
  job lifts Doze (§3.3), but a regular job does not start under Doze outside a maintenance window.
- Phase 2b:
  - The hand-off was measured only with Drive's job denied by appops, and the OS kill (*failure to
    freeze binder*) was seen once.
  - The storm A/B ran with Control alive throughout. Fit's marks are unit-tested, not
    device-verified.
- The 6 h `dataSync` cap and `onTimeout` firing were not reproduced, which would take 6 h of FGS
  time. The handler is unit-tested against the state machine only.
