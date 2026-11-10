# Tenshu Platform: Tech Debt & Known Issues

This document tracks known issues, workarounds, and areas needing improvement. Items here are not
architectural conventions — they are things that should be fixed or revisited when time allows.

---


## Platform / client wiring

### ⚠ Share-link bytes are not charged to the owner's quota — D7 was decided, never built (2026-11-06)

`drive-folder-link-sharing.md` **D7** says shared bytes count against the owner's quota. The relay does
not do it: `POST /share` and `PUT /share/{id}/chunk/{n}` check a live entitlement and the 8 MiB chunk
cap, nothing else, and `share_record` holds no byte length. So an entitled Free owner can store far
past 5 GiB as shares. Bounded since 2026-11-06 only by the relay-wide `STORAGE_CEILING_GIB` (threat
model **T-R-14**), which charges each declared chunk at the 8 MiB cap.
**Fix:** store a byte length per share (relay migration), add the owner's share bytes to the
`putWithinQuota` sum and check it at share create, then report it in `/sync/content/usage`. ~1–2 days
incl. client copy for a refused share.

### ⚠ "Connect an app" leaves its EPC invite in the shared Keychain after the sheet closes (2026-11-05)

Control-on-iOS's "Connect an app" host (`EpcAppPairingView`) writes its live `tenshu://epc/pair#…`
invite into the shared `epc-session` Keychain item (`ControlSurface.publishEpcInvite`). Dismissing
the sheet calls `model.stop()` → `IosEpcPairingController.stop()`, which cancels the host job and
resets the UI state but **never clears the item**. The invite stays there until the next
`clearDriveSession()`, which runs only on reset, forget or wipe.

- **Why it is not urgent:** no shipping app reads the item (Fit's `ControlAutoConnect` is gone with
  the co-resident EPC handoff). Every remaining EPC consumer runs the SAS ceremony. The invite is A8-class and one-shot: once
  the host stops, a stale one only fails the handshake. The threat model's retired "Co-resident EPC
  auto-connect" note covers this.
- **The one live effect is in DEV_HARNESS:** Drive's `RootView` falls back to
  `ControlSessionStore.current()` and treats **any** non-empty item as a connected dev stub. A leftover
  invite therefore forwards a DEV_HARNESS Drive into Files over the fakes.
- **Left as is on purpose:** the maintainer asked that "Connect an app" stay unchanged when the
  handoff was retired.
- **Follow-up:** either clear the item in `stop()` (`ControlSessionProvisioner.clear()`), or drop the
  shared-Keychain write entirely. Its only reader is Drive's DEV_HARNESS `-epcAutoConnectFromControl`.

### ⚠ A continued task's store token is unverified on a phone (2026-11-05; was *S0b-4 owes a continued task's token*)

In code (B.23): `SharedReplicaLifecycle.protectTask` is a token whose assertion is the running
`BGTask` (admission reads the task's time, not `backgroundTimeRemaining`; expired from the
`expirationHandler`), and Photos' continued *Index now* takes it. **Files:** `ReplicaProtection.kt`
(`acquireTask`), `SharedReplicaPlatform.kt`, `SharedReplicaEntries.kt`
(`SharedReplicaTaskProtection`), `PhotosLocalRuntime.kt`.

⚠ **What remains is the phone** (`ios-shared-store-sqlite.md` B.23.5): the simulator has no
continued-processing scheduler, so nobody has seen a continued pass write in the background, nor
confirmed **no `0xdead10cc`** after an expiry, nor how long iOS lets the `expirationHandler` block (up to
the 2 s drain plus the closes). ⚠⚠ That last one is a **hard gate** (B.23.5 item 3): under a task, admission keeps no margin before the expiry, so the 2 s drain is the whole margin for ~1.3 s of uninterruptible work (B.23 (441)). ⚠ PR-6's `BGProcessingTask` is not built; when it is, it takes the same
token, never `withProtection` (an entry's `beginBackgroundTask` assertion dies within seconds).

### ⚠⚠ The desktop bridge deadlocked under a Finder thumbnail burst — handlers still block (2026-10-27)

`NativeBridgeServer` handlers `runBlocking` around reads that hop to `Dispatchers.IO`, so they run on
an elastic `Dispatchers.IO.limitedParallelism(64)` view whose threads do not count against IO's cap
(KDoc, *Threading*); on plain `Dispatchers.IO`, 64 in-flight `thumbnail` calls held every IO thread
and the File Provider deadlocked for good. `thumbnail` is capped at 8 concurrent and 20 s, answering
`unavailable` past it. Pinned by
`NativeBridgeServerTest.aFullHouseOfBlockingHandlersStillReachesDispatchersIo`.

⚠ **Still owed:** `BridgeHandler` is non-suspend, so every in-flight request holds a thread. A
full view now only *queues* (no deadlock), but a slow `fetchContents`/`readContents` burst can still
delay `cancelTransfer` behind it. The real fix is a `suspend` `BridgeHandler` and no `runBlocking`.

⚠ **The thumbnail cap of 8 does not protect the other methods.** A `thumbnail` waiting for a permit
still holds a view slot, so a burst can fill all 64 (8 working, 56 waiting up to 20 s each) and a
`ping`, tray call, `fetchContents` or `cancelTransfer` queues behind them. The 20 s bound counts only
the permit queue, not time queued for a view slot before the handler starts, and a read that ignores
cancellation (a wedged store lock) keeps its permit past it. Fix: give `thumbnail` its own view, or
go `suspend` as above.

⚠ **A timed-out thumbnail marks the whole Finder domain unreachable.** The extension maps
`unavailable` to `.serverUnreachable` and calls `Unreachable.mark()`
(`TenshuFileProviderExtension.swift`), so the system stops asking for anything until the poll's
`ping` succeeds — and that `ping` queues behind the same thumbnail waiters. Relay-pull failures
already did this; the timeout adds a load-driven source of it during exactly the burst it targets.
Fix: a `busy` code the extension maps, for thumbnails, to a per-item error.

### A JS fetch failure escaped the content emit as an op failure — a streamed body read is still outside the seam (2026-10-28)

ktor-client-js reports a refused `fetch` (offline, DNS, CORS) as `kotlin.Error`, not an
`Exception`. `TransportFailureMapping` (`:tenshu-sdk-js`), installed on both `HttpClient(Js)`
instances, turns any non-`Exception` thrown by the send into an `IOException`, so the common
`catch (e: Exception)` in the `Rest*` clients holds on JS. ⚠ Do not widen common code to
`Throwable` instead: on the JVM that swallows `OutOfMemoryError`. Pinned by
`ContentEmitFetchFailureTest` and `TransportFailureMappingTest`.

⚠ Still uncovered: a failure while a streamed response body is read (after the send) does not pass
the seam. Only `getChunk` reads a body that way on the content plane.

### The web runtime's `ExternalPropertyWriter` fan-out omitted workouts — device pass owed (2026-10-28)

The JS runtime's fan-out (`JsRuntime.kt`) includes `WorkoutStreamsPropertyWriter` beside
`FileBytesPropertyWriter`, as native does; `WorkoutStreamsOnWebTest` pins both directions.
⚠ Still owed: the device pass against a real relay.

⚠ Native `RecordWorkout` has the retry gap the web import now closes: a re-record mints a fresh
`contentId` and never calls `discardSupersededContent`, so the superseded intent waits for the drain,
which publishes it again when the same bytes sit at the same path. Fixed for web in
`Fit.importActivityFile`; the op itself is the right home.

### ⚠ A content PUT's 401 re-enqueues with no backoff, so a refused device hammers the relay (2026-10-29)

`BackgroundUploadManager.onCompleted` (tenshu-sdk-ios-drive) treats every 401/403 as *"the baked-in
JWT expired before `nsurlsessiond` ran the transfer"* and hands the blob straight back to the
background session with fresh headers. When the 401 is permanent — the relay does not know the
device at all — that is a hot loop: on 2026-10-29 a store-screenshot simulator whose realm the dev
relay had refused (426, the relay was behind the release cut's protocol reset) sent **~140 content
PUTs a second** for five minutes, ~40k 401s, and Drive's capsule read *"Uploading…"* the whole time
(`store-screenshots.md` §3). A phone in that state would do the same against production, spend the
relay's per-IP auth-failure budget for everyone behind its NAT (`JwtAuth`, *"NAT is the known
weakness"*) and drain its battery. **Owed:** re-enqueue a 401 once per fresh token, then leave it
journalled for `resumeIncomplete` with a backoff, as every other failure already is.

### Nothing prunes `RoutineRun` rows, so Home's activity feed grows without bound (2026-10-28)

`RoutineRun`'s KDoc (RE-D10) says the executing device prunes run rows by tombstone, keeping the newest `RETAIN_ROWS` (200) or `RETAIN_MS` (30 days), and `DeleteRoutine` leaves a deleted routine's runs to "age out on their own retention". No code does it: nothing outside `RoutineRun.kt` references either constant, and `:routine-service-proxy` deletes only `Routine` rows. Every firing is a synced `Eager` row, so the realm, every replica and native Home's activity feed (which reads the whole relation, `ClientRoutineSource.runs()`) grow with the firing rate. The web reads every run through a windowed chain that fuses as top-N (`HomeBook.kt` rule 4; `HomeWebContractTest.everyRunRead_fusesAsTopN` dispatch-counts it): a feed page, one newest run per routine for `lastRunAtMs`, and a 32-run head for the observe forms' change signal. So what a tab materialises, sorts and sends is bounded. ⚠ **What stays unbounded on the web:** each of those reads is still one thin pass over every run, because there is no ordered index (`core-ordered-window-fusion.md` F3); `automations()` makes one such pass per routine, and every store write to the relation re-runs the 32-run head while an observe form is open. Native and the realm are not bounded at all. Fix: the pruning pass the KDoc describes, run by the executing device after it writes a run.

### ⚠ Narrowed — `HostPairingMonitor`'s and the pending-requests feed's scopes still have no exception handler (2026-10-30)

**The common trigger is fixed.** Installing Control's graph over iOS's not-ready stand-in (protected data unavailable at launch, a sibling replacing the store: Q35/Q40) or a fenced handle (`ReplicaReplaced`) throws out of whichever call mints the in-process session: `initFileService` saves "/" over a store that refuses writes, `ClientRegistry.mintSession` runs `runInitialization()` uncaught, and `SessionAwareLocalTaskExecutor.executor()` rethrows. The init-time first callers on these scopes no longer let it out: the monitor's `resumeActivePairingSession` and the feed's `pollGrants` catch and log, and a stream whose start throws is a `Failure` that `TList.flow` retries (`DefaultInteractorProvider`; `ios-shared-store-sqlite.md` B.16.15, follow-up).

**What is left.** Neither scope has a `CoroutineExceptionHandler`, so any *other* non-cancellation throw on them (the monitor's approve / deny / start launches, the feed's `refresh`) still aborts a K/N process and crashes a JVM one. Those run after the session exists, where ops answer `TResult`s, so nothing known throws there; a handler (log and drop, as `serviceScope` has) would change crash behaviour on every platform, which is why it was not added.

### ⚠⚠ S4: a write under a sibling's id that no live writer in the test made (2026-11-05)

`SyncActivitySharedReplicaTest.aSiblingsLaterPull_rewritesItsBeatInPlace_andStillLightsAMountedSurface`, about 1 in 12–36 loaded class runs: Photos opens the replica and reads Control's rows as a fresh **raise**. The own row and the beat both say `true`, stamped about 40 ms after Control's settle. Control's own view lacks the write until Control rehydrates from disk, and Control's writer's flag is `false`. So the write went through a store instance other than Control's, under Control's id.

What is ruled out:
- `drainNow`/scheduler ticks on Control's writer.
- A second `SyncCatchUp` keyed to Control created in the same test (an instance-creation registry showed exactly one).
- Any catch-up row written through Photos' store (a per-store write ring showed none).

Printing from the write path hides it; a silent creation registry does not. Still open: whether an earlier test's graph, or another store opened over the test's directory, is the writer.

The test treats this as **inconclusive**: it prints the full diagnostic (both views, the view after Control's rehydrate, the writer flag, the disk rows) and returns early, with "S4 OPEN". It does not retry. Every other assertion stays strict. Details: `ios-sync-activity-indicator.md` §12.7.

⚠ Also seen while chasing it, twice in about 336 runs, and only under builds that instrumented `FileObjectStore.storeObject` (the file store, deleted 2026-11-05; the test now runs over `TestReplicas`): "Photos' reader counts Control's fresh beat" and "a reader joining the surface's stream counts the in-place beat", each still unlit after 10 s. The suspect is the joiner hang below (these reads join the surface's stream), but that is not confirmed.

### ⚠ A joiner that reuses a stream entry just before it closes hangs (2026-10-30)

`DefaultInteractorProvider.allocateOrReuseStreamInteractor` hands an existing, not-yet-closed entry to a second subscriber of the same chain, and that subscriber registers its callback later, outside the provider's lock. If the entry's last consumer calls `endStream` (or an abandoned start is dropped, `abandonIfUnreferenced`) in between, `StreamInteractorImpl.registerCallback` meets `if (isClosed && !isEnded) return` and returns with nothing registered and nothing replayed: the joiner's `TList.flow` waits for ever. Predates B.16.15's follow-up; that follow-up makes it easier to hit, because a Failure-seeded entry is closed within one round trip of being made. Fix: have the reuse take its reference under the provider's lock (or have `registerCallback` report a closed entry so the caller allocates afresh).

### ⚠⚠ A session whose initialisation threw stays published, half set up (2026-10-30)

`ClientRegistry.mintSession` publishes the session **before** `runInitialization()` (deliberately: the init re-enters the lookup, see its KDoc), and `runInitialization` sets `initializationRan` before calling the hook. So when the hook throws part-way — `initFileService`'s first write over iOS's stand-in or a fenced store, or any `init<X>Service()` — the session stays published and is handed to every later caller, with `initFileService` and every service initialised after it **never set up**, and nothing retries them. ⚠ Swallowing the mint's throw (the schema announcer's catch, and since B.16.15's follow-up the pairing monitor's, the grant poll's, and a stream start's `Failure`) means the process now **keeps running on that degraded session**. On iOS's stand-in that is harmless, because the graph is rebuilt. On Android it is not: before, an uncaught throw on one of those scopes crashed Control (it installs no default handler), and the restart minted a fresh session, so the crash was the recovery; now Control runs on until something else restarts it. Nothing known throws there off iOS, so this is latent. Fix: on a throwing `runInitialization`, unpublish (compare-and-remove) and destroy the session so the next caller mints again, with care for the re-entrant lookup the publish-first order exists for.

### ⚠ A stopped content backfill starts again from zero, so a large corpus may never finish (2026-10-25)

Since `control-background-work-off-fgs.md` §5.6 the declaration backfill runs only under its own
WorkManager job, and a job the OS stops (the 10-minute limit; about 30 min while charging, where no
quota is charged; idle ending) stops the pass with it. WorkManager's rerun is the continuation, but the
pass is idempotent rather than resumable: it walks and fetches every manifest again, and latches its
marker only on a complete walk. The walk is not checkpointed because its race check
(`ContentDeclarationBackfill.EmitGuard`) compares against emit stamps read before the walk, and those
are per process.
- **Measured rates:** about 12 manifests/s on the maintainer's phone against production (2026-10-01:
  ~2,200 in 3 min), about 5/s on the emulator with 150 ms added per request.
- **Bound:** manifests resolve four at a time, so a 30-minute job covers up to ≈ 16,000 photos (about
  five content objects — the file and its rungs — per photo). A realm this build created, recovered or
  joined is stamped described-from-genesis, so the pass never runs on it; after the 2026-10 reset that
  is every realm. What is left is a device upgraded in place over a pre-seam realm of more than that
  many photos, which never completes and refetches everything each run.
- **Fix when it bites:** checkpoint per describe batch. Revalidate each batch against the stamps
  before recording it done, and persist the described content ids with the process's identity, so a
  rerun in another process re-checks what it inherited.

### Synced Folders on WorkManager — review follow-ups (2026-10-24)

Raised in review of the WorkManager backup move and left out of it (the maintainer scoped it to the
tri-state enabled read and the row/journal window). Line numbers are as of that change.

1. **The in-process loop drains in the background.** `SyncedFolderController.kt:255` (the trigger
   loop, started unconditionally by `DriveApplication`) drains with no budget in processes WorkManager
   started, and races the worker for `drainMutex`.
   ⚠ **Measured 2026-10-25** (`control-background-work-off-fgs.md` §5.7): with its BackupWorker
   denied, Drive's job ended after 0.3 s and the loop went on uploading a 49 MB photo with no job.
   Control, cached with that call outstanding, was then **killed by the OS** (*"failure to freeze
   binder … excessive binder traffic during cached"*). Control's own push job finishes the bytes from
   the journal; the client's call fails. Skip automatic loop drains while the process is not
   foreground; yield a running one at the next file and enqueue `CONTINUE_WORK`. Start the worker's
   9-min deadline after the lock is held (`BackupWorker.kt:89`, `drainInBackground` `:450`).
2. **Metered override scope.** `SyncedFolderController.kt:496` calls `uploadOverMeteredOnce()` only
   after the whole drain, and `forceDrainOnce` drains the realm's whole deferred backlog; an unconsumed
   request can also fire at a later unrelated tick (`SyncScheduler.forceDrainRequested`). Kick per
   N files/MB, give the request a TTL, and scope it or say so in the copy (`strings.xml:422`).
3. **Back up now waits behind a running job** up to 9 min, then starts its FGS from the background.
   The worker's yield should include `controller.userRequest.value != null`.
4. **The banner freezes** at `BackingUp(n of m)` with a Stop action after a yield or OS stop
   (`SyncedFolderController.kt:1009`). Publish a non-in-flight state (e.g. a pause reason "continues
   in the background").
5. **Re-arm on every exit.** `BackupWorker.kt:123` re-arms only on the normal path; a non-OS
   `CancellationException` skips it. Re-arm in a `NonCancellable` finally except after an app cancel;
   skip the `APPEND_OR_REPLACE` (`:126`) when a successor is already `ENQUEUED`/`BLOCKED`, or retries
   grow the chain.
6. **The scheduler's REPLACE** (`BackupWorker.kt:163`) still cancels a running content job mid-file on
   a live policy change. Replace only when no content request is `RUNNING`.
7. **Include videos** does not re-arm the content trigger: `BackupWorkScheduler.kt:44` does not
   observe `observeVideos()`, and `armedWith` does not track it.
8. **A tap can do nothing:** `overridableBlockersNow` (`SyncedFolderController.kt:415`) awaits
   `storageStatus().first()` unbounded (`SyncedFoldersViewModel.kt:131`), and `runDrainGated`
   (`:481`) drops a tap that lands before settings load.
9. **Nits:** `onTimeout` → `stopSelf()` on `SyncedFolderForegroundService` (Android 15's 6 h
   `dataSync` limit; before `onDestroy`, `:80`); guard `stopReason` (`BackupWorker.kt:99`) by API 31;
   match the dialog copy to the setting labels (EN "Use Wi-Fi only for background data" / "Only while
   charging", DE „Hintergrunddaten nur über WLAN“ / „Nur beim Laden“); say in phase-2 §4.1a that
   automatic triggers coalesced into a user run's iteration share its override.

### Native Timeline place merge / unmerge and trip-suggestion accept are wrong in three ways (2026-10-24)

The web read model gets these right (`tenshu-sdk-js/.../Timeline.kt`); natively they are **not
fixed**:
- **Unmerge is a silent no-op.** `PlaceDetailViewModel.unmerge()`
  (`timeline-shared-logic/.../ui/screens/place/PlaceDetailViewModel.kt:96-97`) calls
  `repository.unmergePlace(placeId)` with the screen's own place — the survivor, the only place
  whose detail shows a merge count. The survivor has no merge row; `unmergePlace`
  (`timeline-shared-data/.../TimelineProjectionRepository.kt:925`) writes a blank `PlaceMerge` row
  for it anyway, which changes nothing and syncs. Unmerge needs a folded place's id, which the
  detail never exposes.
- **A stale id overwrites an existing merge.** `mergePlaces` (`TimelineProjectionRepository.kt:893`,
  `minOf(a, b)` at :896) works on the raw ids: with `F → R` already recorded, `mergePlaces(F, b)`
  records `F → b` and silently drops `F → R`. Resolve both to `PlaceMerge.resolveRoot` first and
  merge root into root (what the web does).
- **Accept / dismiss can act on a different trip than the card showed.** `tripSuggestion()`
  (`TimelineProjectionRepository.kt:475`) calls `currentSuggestion(…, roles)`, but
  `acceptTripSuggestion()` (:957) and `dismissTripSuggestion()` (:950) call it **without** `roles`,
  so a confirmed `home` (PR-4) is ignored and the suggester can pick another cluster. Accept then
  materialises a trip the user never saw. Pass `roles`, or take the suggestion id as the web does.


### No test seam can fail a single relation read through the real client (2026-10-23)

Fixing `TenshuContactsRepository.sourceRowsFor` (a failed read folded to "no records", so a save
wrote a duplicate `manual:` record and export said "nothing to export") needed a test that fails
ONE `ContactSource` read on the real path. None of the three obvious seams reaches it:
- a wrapping `RequestChain` and a wrapping `TenshuEnvironment.taskExecutor` are never called,
  because `getAll()` resolves its executor from the entity's own context (the proxy's);
- clearing `sessionAnnouncement.announcedVector` does not refuse the read in a `TestProxy` either.

So the fix shipped verified by the existing success-path suite only. A real seam (a failure hook on
the proxy's executor, or an `ObjectStore` test double that can fail reads of one type) would let
every repository's error path be tested the same way; most `fold(onFailure = { emptyList() })`
reads in the `*-shared-data` repositories are untested for exactly this reason.

### iOS device backups: what the app sandboxes still carry (2026-10-23, rewritten 2026-11-05)

The original entry — the App-Group replica, health data included, went to iCloud Backup — is
**resolved in code** (`ios-shared-store-sqlite.md` B.21 D3/D3b, on in every build since B.22):
`replica/` is excluded at every open and read back, and the store is refused without it;
`files/` (file contents, but also workout route/HR streams and contact photos), `upload-staging/`
and `sync-hlc/` best effort (logged, retried at the next open); threat
model T-C-04 records it. The privacy policy (version `2026-11-05`, "sensitive-data") says so, and
lists what is still backed up. ⚖️ The 5.1.3 question (HealthKit data in iCloud) is answered by it:
HealthKit-imported data reaches only the excluded store.

**What remains open** (all per-app, outside the App Group, so D3/D3b never reach them):
- Fit's `workout_outbox` (`IosRecordedWorkoutOutbox`) and `recording.journal` (`IosRecordingJournal`)
  in Application Support: a recorded, hand-entered or Timeline-promoted workout's times, route,
  steps and strap heart rate, until it is saved — held for as long as Control has no realm
  (`FitProjectionRepository.drainPendingWorkouts`). Not HealthKit data, but Art. 9 health data.
- Timeline's `move_journal` (`IosOpenMoveJournal`, Application Support) and the Wi-Fi
  network→place map (`IosWifiFingerprintStore`, `NSUserDefaults`: SSID + BSSID → placeId), which
  is durable, not transient.
- Calendar's mirror digests and the settings flags in `NSUserDefaults` (no user content).
- Excluding the two Fit files and the Timeline journal would cost one `ContainerAtRest`-style call
  per directory; the Wi-Fi map would need to leave `NSUserDefaults`. If any of these changes, the
  policy's second backup paragraph changes with it (and the date moves).
- ⚠ Unverified on a device: that the read-back exclusion is what backup honours (B.21 device step
  Q0 (h)), and that no pre-release `objects/` or app-local Documents replica survives on a test
  phone: the 1.0.1 re-cut deleted `LegacyFileStoreReset` and the app-local reclaim, so such
  a leftover is no longer removed by any build (and is backed up). Wipe those phones by hand.

### Expired verification codes and sessions are never purged (2026-10-23)

`email_verification` and `session` rows are deleted only when presented (expired or exhausted) or replaced by a new code (`PostgresCommerceStore.kt`, the only two DELETEs); an e-mail that requests a code and never comes back, or a session never presented again, stays forever. Validity (15 min / 30 days sliding) is enforced; retention is not. The privacy policy states validity and retention separately for this reason. Owed: a periodic purge of expired rows.

### A database restore does not re-apply erasures (2026-10-28)

Restoring the relay or commerce database from a snapshot older than an erasure brings erased personal
data back, and nothing re-erases it automatically:

- **Relay.** Erased rows return, and so does the realm. The `erased_realm` row that blocks re-creation
  does **not** return. Commerce's closure sweep re-posts every closed account forever, so it re-erases
  owners within one sweep, **as long as commerce was not restored too**. An owner that commerce no
  longer posts cannot be erased at all: the only erase path is `/sync/account/closure` with a
  commerce-signed record, and there is no operator CLI or admin route. Between `up -d relay` and that
  sweep, the relay serves the resurrected data.
- **Commerce.** An older dump brings back the email and sessions of accounts erased after it, and
  forgets closures. **No operator tool** re-erases an account (next entry). Self-service deletions
  after the dump are recorded nowhere that survives losing the live database.

The procedures and their manual steps are in `docs/how-to/back-up-the-relay-database.md` and
`back-up-the-commerce-database.md` (*Re-applying erasures after a restore*), and
`docs/policy/erasure-request-runbook.md`. **Owed:**
1. An append-only **erasure ledger outside both databases**, written by `eraseAccount` and
   `AccountErasure`: account id, owner id, closed / erased time, nothing else. For example, a daily
   object in the backup bucket. **S.**
2. An operator **re-apply command** that reads the ledger and re-runs erasure on both planes: commerce
   via the `eraseAccount` SQL, the relay via a minted closure record or a loopback ops route like
   `/ops/content-sweep`. **M.**
3. Optionally, keep the restored relay dark until (2) has run. **S**, once (2) exists.

⚖️ Under the maintainer-as-counsel checklist (`docs/policy/privacy-review-checklist.md`, item 6) this
is an open item for the launch review: the ICO's "beyond use" guidance accepts erased data lingering
in backups only if it is never put back into use.

### No operator path erases a commerce account (2026-10-28)

`eraseAccount` is reachable only through `/commerce/account/delete/confirm`, which needs the code
mailed to the account's address. The admin console has `close` and `reopen`, and `close` keeps the
email. That is enough for the normal erasure request: the runbook points the user to `/account/delete`,
which mails the code. It is not enough for a case without a working inbox (a child's
account the notice promises to delete, an address the person lost), or for re-applying erasures after
a restore (previous entry). It is also likely moot today: the console is probably off in production
(next entry, `ADMIN_TOTP_SECRET`). Owed: `/admin/accounts/erase` (two factors, audit line by id
prefix, calls `eraseAccount` then `SubscriptionCanceller`). **S.**

### The production commerce admin console is reachable only via `docker exec` (2026-10-24; trimmed 2026-10-30)

⚠ `commerce-service/docker-compose.yml` must forward every key `CommerceConfig` reads (a dropped `WEB_APP_ORIGIN` made production refuse every browser call); `ComposeEnvPassthroughTest` in `:commerce-service` fails when a new `getenv(...)` has no compose line. Add the compose line in the same change as the read.

What is open: with `ADMIN_TOTP_SECRET` and `ADMIN_BIND_HOST`/`ADMIN_PORT` now forwarded, the production console can be turned on with two factors, but it binds `127.0.0.1` inside the container and its port is not published. So it is reachable only from inside the container (`docker exec`): the container runs no sshd, and a host tunnel would need `ADMIN_BIND_HOST=0.0.0.0` plus the container IP. The runbook section that describes a two-factor production console (`docs/how-to/deploy-commerce-digitalocean.md` §"The admin console needs two factors") does not say how to reach it. ⚠ The box's `deploy.env` already carries an `ADMIN_TOTP_SECRET` line from cloud-init that used to be ignored. It now reaches the container, and a value that is not valid base32 stops commerce from starting, so check it before the first redeploy with this compose.

### A full disk crashes an iOS app instead of failing the write (2026-10-23)

On a full disk, the iOS apps crash with an uncaught `NSFileHandleOperationException` ("`-[NSConcreteFileHandle writeData:]`: No space left on device"), thrown from `IosFileDatabase.writeBytesRange` (`core-common/src/iosMain/.../proxy/IosFileDatabase.kt:179`, `writeData:`) under `FileDatabase.writeBytesRange` ← `FileContentManifestApplier.apply` (`sync-service-proxy`). The ObjC exception cannot be caught by Kotlin, so a sync pull aborts the process instead of failing the write. Seen in Photos during a landing-screenshot run with the host disk at 99%. Not fixed.

### A sync-call reply has no out-of-band path, and an oversize one reads as "Control died" (2026-10-23)

What is left of *"A list stream outgrows one Binder transaction, and Fit's workout detail stops
loading"*, whose stream half is fixed (`core-client-resubscription.md` §4.8, R9): Control no longer
reads a too-large transaction as a dead connection, never writes a token whose descriptor did not go
out, and sends a stream message over 128 KiB through `IClientInterface.callbackShared` in
`SharedMemory` — Fit's full-history walk loads every workout again.

The **reply** of `IProxyInterface.call` / `callCancellable` / `callCancellableRead` is still one
`byte[]` in one transaction. Byte payloads inside it go out of band (`tokenize`), but rows and
strings do not, so a `.getAll()` over enough rows still overflows — the `allLabels()` entry below is
the known instance. ⚠ And it fails under the wrong name: the reply is what the kernel refuses, so the
**caller** sees `FAILED_TRANSACTION` on its own small request parcel, which Android raises as
`DeadObjectException` ("Transaction failed on small parcel; remote process probably died").
`asNotConnected` maps that to *"Proxy died mid-call"*, and `RemoteBinaryTaskExecutor` reports it as
`CallOutcome.NotConnected` — a reachability engine is told Control is gone while it is fine. An
oversize **request** (app → Control, `TransactionTooLargeException`) reaches the same
`NotConnected`, under its own message. Neither tears the connection down (`ServiceConnection` owns
that state), so this is mislabelling, not the R9 wedge.

Fix, when a read actually needs it: the same shape as R9 in the other direction — an
`IProxyInterface` method, appended and gated on `ipcRevision()`, whose reply is a `SharedMemory`
region (AIDL can return one) for a reply over the inline limit; plus, client-side, a
`DeadObjectException` from a binder that still answers `isBinderAlive` mapped to a transport
failure rather than `NotConnected`. Below API 27 an oversize stream message still fails (as its
stream's typed end), since there is no `SharedMemory`.

### ⚠ A contact card whose *text* is over `ROW_MAX` is still one row the iOS store refuses (2026-10-31)

Contacts v2 (`contacts-app.md` §9.10) moves only `PHOTO` lines to the content plane — the
maintainer's "move only big photos out". A card whose bulk is a giant `NOTE`, `LOGO`, `SOUND` or
`KEY` is stored whole, so past 512 KiB it is `RowTooLarge` on iOS on every attempt (F6s), and the
256 KiB card budget does not reach it. **Recommendation:** widen the movable set in
`ContactPhotoContent` to the binary properties (`LOGO`, `SOUND`, `KEY`) under the same rule — the
mechanism is property-agnostic, only the name test changes, and it owes no bump (the placeholder
already exists) — and refuse at import, with a typed error, a card whose remaining text alone is over
`ROW_MAX`. Q0 (w) on a real phone says whether any user has one.

### ⚠⚠ Three contact deletes are device-local: unlink, clear photo, and merge's source person (2026-10-31)

`SyncObserver.onDataDeleted` emits nothing by design — a delete op owes its own tombstone
(`DeleteWorkout`'s note). `DeletePerson` emits one, but `UnlinkTenshuIdentity`, `ClearPersonAvatar`,
and `MergePersons`' delete of the source `Person` and its `PersonAvatar` emit none, so each comes
back on every other device at the next pull. **Recommendation:** its own PR — take
`tombstoneEmitterHolder` from `ContactProxyComponents` in all three and emit beside each `deleteData`, with a two-device system test per
op that asserts the row is gone on the peer.

### A contact edit drops vCard types the label model does not know (2026-10-22)

`ContactCard.withLabel` (`contact-service-common`) rewrites each TEL/EMAIL's `TYPE`s on every save
of the contact: it keeps the kind types in `NOT_A_LABEL` (`PREF`, `INTERNET`, `VOICE`, `X400`) and
the one label the editor shows, and drops the rest. So an imported `TEL;TYPE=WORK;TYPE=FAX` becomes
a plain work phone after any edit, even of the note — `FAX`, `CELL` beside another label, `TEXT`,
`MSG` and `PAGER` are lost. And a custom label the user types as "voice", "internet" or "pref" is
written as that kind type and reads back as no label. The fix is a model that carries a property's
kind types separately from its label (and a label never collides with one).
### The sync cursor rows bypassed the `DatabaseLock` — fixed for `SyncManagerImpl`, two sweepers still unlocked (2026-10-02)

No `ObjectStore` is thread-safe on its own: `InMemoryObjectStore` is a plain map and
`SqliteObjectStore` keeps an access-ordered `LinkedHashMap` cache, which even a *read* mutates
(iOS's SQLite `SharedReplicaStore` is the exception: each outermost access is one transaction under
its own lock). Each relies on being called under the `DatabaseLock`, which `DatabaseImpl` guarantees
for everything that goes through it. Anything that reaches the store directly must take the lock
itself. Unlocked, a write races every op on the device; the observed symptom was a row read as absent
while another thread's insert resized the map (an upload refused `No folder at /`).

`SyncManagerImpl` takes the store's lock (`storeLock`) for every cursor-row access; all three
`PlatformProxyModule`s pass `get<DatabaseLock>()`. **Guards:**
- `CursorRowsUnderStoreLockTest` — each accessor, by name, reaches the store only inside the lock.
- `SyncManagerStoreLockWiringTest` — ⚠ the default is a no-op lock, so a module that forgets
  `storeLock =` works and races again; this reads all three call sites.
- `LockCheckedObjectStore` — the system-test harness's store records any row access made without the
  lock and `TestSystem.shutdown` fails on it.

⚠ The race is rare by construction (an unlocked insert must cross a resize threshold while a locked
read lands inside it), so it reads as a flake, and a change that adds or removes one init-time row
can switch it on or off.

**Still open:**
- ⚠⚠ **`OrphanedFileSweeper.sweepOnce` reaches the store unlocked.** Its hook (`addMaintenanceHook`,
  all three `PlatformProxyModule`s) calls it bare: it reads its cursor row, runs `scanRowsOfType` and
  writes the cursor back with no `DatabaseLock`. On iOS's SQLite store each direct call is its own
  transaction, so this is safe there; it still reaches the JVM `SqliteObjectStore`'s cache. Holding
  the lock for a pass means holding it across up to `EXAMINE_PAGE` (8,000) row reads on every tick
  until the sweep finishes — a decision about paging under the lock, not a one-line wrap.
- ⚠ **`RetiredBlockSweeper.sweepOnce` is the same, apart from its keep-set.** The keep-set reads
  (`invalidateCached` + `readDurable` of each queue row) run under `database.transaction`; the deletes
  go through `database`/`deleteRetiredBlock`. `loadRetiredBlockSweepCursor`, `scanRowsOfType` and
  `storeRetiredBlockSweepCursor` are unlocked. Same paging decision as above.
- `initFileService` spends its one-shot repair on the store directly; whether that is under the lock
  is **unaudited** — the harness hands `initFileService` no store, so `LockCheckedObjectStore` never
  sees it.
- `SyncManagerImpl.wipeLocalStore` calls the suspending `wipeAllObjects()` outside the lock, and
  `DatabaseImpl.isEmpty()` reads the store without it.
- ⚠ Unmeasured: whether an unlocked insert racing a locked one can drop an entry (only a transient
  miss was observed), and what production saw on the SQLite store.
- ⚠ **A wipe that lands mid-tick leaves the cursor above zero over an empty store.** Forced in the
  harness: the tick wrote the old cursor straight back (`max(since, served)`, `pullMergedLog`), the
  state A1 calls unrepresentable. The joiner still refilled because the wipe also dropped the
  backfill floor and `backfillOnce` walks down from the tip — ⚠ a rescue that needs the merged lane;
  the two-lane fallback has no backward walk. In production this is `ForgetThisDevice` only, which
  never calls `SyncScheduler.stopAndAwait` before wiping. The harness wipes with the scheduler stopped
  (`TestClient.wipeStoreWhileNotRunning`); production does not.

### Calendar seeds its default calendar before the first sync (2026-10-22)

`ensureDefaultCalendar` runs at launch on every platform — Compose's `App` (`LaunchedEffect`) and,
and iOS's `CalendarLocalRuntime.install()` — and nothing makes it wait for this device's
first sync, because no cheap "replica caught up" signal reaches the client. What bounds the
damage: it writes nothing when the store holds **any** calendar row, writes `DefaultCalendarId`
only while it is unset, and writes nothing when the default names a calendar **other than
`personal`** not here yet. ⚠ The window left is a store holding **no calendar rows** while the
default is blank **or `personal`** — a freshly paired device before its first pull, and for every
realm the app seeded the synced default *is* `personal`, so this is the common case. (The
`personal` pass-through is deliberate: it finishes a seed whose calendar write was refused, which
the store cannot tell apart from "still syncing".) The seed then saves a Personal row (fixed id,
name "Personal", the palette blue, `sensitive = false`) and possibly the default, with a later HLC
than the realm's, so last-writer-wins resets a Personal the user renamed, recoloured or marked
"Hide details on the lock screen" on another device, or a default they moved. On iOS route 1 the
replica is Control's, which has usually synced by then, so the window is narrower there, not
closed. The fix is a "first pull done" signal the seed can await (or seeding only from a user
action).

### iOS ignores schedule network constraints, so a subscription refresh can use cellular (2026-10-22)

A schedule's `ConstraintSpec.network` is enforced only by Android's `WorkManagerScheduler`. iOS
route 1 dispatches schedules from the in-process `ScheduleDrainLoop`, which stores constraints
but does not enforce them, and `IosNetworkStateProvider.current()` always answers `UNMETERED`
anyway. So Calendar's subscription refresh (`NETWORK_UNMETERED`, every 6 h) and every other
"unmetered only" schedule, such as the offline keeper, run on cellular data on iPhone. The iOS
subscribe copy and the support pages therefore say "about every 6 hours" with no "on Wi-Fi";
Android keeps it. The fix is metered detection (NWPathMonitor) in `IosNetworkStateProvider`, plus
the drain loop consulting it; when that lands, restore "on Wi-Fi" in the iOS copy.

### Desktop Control's unlock and re-auth prompt is a separate system sheet from another process (2026-10-14)

On macOS the soft app lock and the five Ph-reauth gates shell out to `applock-helper`
(`control-desktop/macos/KeychainHelper/main.swift`), whose `LAContext` sheet belongs to *that*
process, not to Control. Two symptoms followed (routes 1 and 2 below now cover the soft lock). **The sheet is a second dialog**, outside Control's
window — users asked for it in-window. **It steals focus**: when the helper exits activation does
not come back, so Control drops behind other windows and looks crashed; `DesktopAppLock.authenticate`
and `Main.kt` each re-raise to paper over it. The sheet's *wording* is fixed: it used to read
"applock-helper is trying to Confirm it's you to open Control.", and an embedded `__info_plist`
(`CFBundleName` = Tenshu Control) plus the `*_system_reason` strings now make it "Tenshu Control is
trying to verify it's you."

Three routes, cheapest first:
1. **In-process `LAContext`** — BUILT (2026-10-15): `MacLocalAuthentication` (`:macos-helpers`)
   over the FFM bridge, preferred by `DesktopAppLock`, helper kept as the fallback; the bundle's
   `CFBundleDisplayName` names the sheet. Still a sheet. Packaged-build pass 2026-10-15: the
   request came from `TenshuControl` itself, titled "Tenshu Control", Success, window stayed in
   front. ⚠ That pass ran **with** the raise, and the dialog is drawn by `coreautha` (a system
   process), so it does not show the raise is unneeded — both workarounds stay until a pass
   without them says otherwise.
2. **Embedded `LAAuthenticationView`** — BUILT for the **soft lock** (2026-10-15):
   `MacEmbeddedAuthentication` (`:macos-helpers`) adds the view to the content view of the
   `NSWindow` found by title + width, over a Compose box in `AppLockGate` (desktop), and evaluates
   the biometrics policy on its context; AppKit calls hop to the main thread via
   `performSelectorOnMainThread:`. Packaged-build pass 2026-10-15: the mark draws in the window,
   above Skia, and unlocks. ⚠⚠ **No password route of its own** (native probe, same day): a wrong
   finger just keeps it waiting, five end the call with `authenticationFailed` and **lock Touch ID**
   until the login password is entered. So the gate shows *Use password* (route 1's sheet) and falls
   through to the sheet on any Failure/Unavailable — remove that and a locked sensor is a lockout.
   ⚠ The view's position arrives from Compose's thread while the view is created on another, so a
   slot reported before the view exists must be kept (`SlotFollower`): dropping it left the
   fingerprint over the title and buttons (reported 2026-10-16).
   ⚠ macOS cancels the check while Control is not the active app, so it runs only while the window
   is focused and re-asks on refocus (`LockPromptFlow`); falling back to an *Unlock* button there
   made users press a button just to get the fingerprint back (2026-10-22).
   ⚠ The five **Ph-reauth** gates still use the sheet: they have no Compose surface to reserve a
   slot in. ⚠ An Apple forum thread reports a macOS 27 beta where binding the view fails every
   evaluation with -1007 (said fixed in beta 3) — re-test on 27.
3. **The JVM-less macOS re-platform** (`milestones.md` *Desktop distribution channels*, fallback):
   a native app gets route 2 trivially. ⚠ It is a gated fallback, never a reason on its own — do
   not wait for it.

### iOS's preview cache is a plain map read from a thread pool (2026-10-10)

`InMemoryPreviewCache` — bound as the `PreviewCache` on iOS and desktop — keeps its entries in a
`mutableMapOf` with no lock, on the strength of a KDoc line: *"the proxy serialises operation
execution per `ExecutionContext`"*. On iOS route 1 that is not so. A synchronous operation runs on
the thread that calls it, and the callers of `GetPreview` are a pool: `PhotosSurface.tilePreview`
and `DrivePreviewSurface` hop to `Dispatchers.Default` per tile, so a grid's worth of reads `get`
and `put` the same `HashMap` side by side. The shape is the cursor-row race above — a lookup inside
another thread's resize — and the symptom would be a tile that comes back blank once and is fine on
the next read, which nobody would report. ⚠ Read from the code during the Photos off-main audit
(`photos-ios.md` §8.22.2), **not observed failing**. ⚠ The same audit found `TenshuEnvironment`'s
service map in the same state and fixed it (`ConcurrentMemo`); this one holds thousands of byte
arrays and wants a lock or a bounded cache, not a copy per insert — it has no bound at all today.

### Android blocks Control's network in the background, and the foreground service does not cover sync or maintenance (2026-10-01)

**Measured on the maintainer's OnePlus (ColorOS, Android 15), not inferred.** `dumpsys netpolicy` for
Control's UID reads `blocked=APP_BACKGROUND`: the OS denies it the network unless its process is in the
foreground or a foreground app is bound to it (`procState=TOP`/`BTOP` ⇒ `effective=NONE`; with the
launcher on top, or the screen locked, `allowed=NONE`). While blocked, every request fails at name
resolution: `Unable to resolve host "relay.tenshu.app": No address associated with hostname`. ⚠ A test
connection from `adb shell` succeeds, because that is another UID — which is how this reads as "flaky
Wi-Fi". Symptoms: sync flipping between *offline* and *recovered* with whichever app is in front, and
maintenance passes whose fetches go unanswered.

**How background work gets the network now**
([`control-background-work-off-fgs.md`](proposals/control-background-work-off-fgs.md) §3, measured on a
stock API 37 emulator): a WorkManager job **does** get the network — Control's own job even under Doze,
and a background client's job lends Control network over the binding while it runs (not under Doze).
The sync push runs as Control's own job (Phase 1 there), and the maintenance pass runs only under its
own WorkManager job, dispatched as the job's child, so the job is held for the whole pass and a stop
unwinds it; WorkManager's rerun continues a stopped pass (Phase 2a, §5.6).

**Why the foreground service does not cover it.** `ProxyService` promotes itself while a combined
liveness count is above zero (`ForegroundTaskLiveness` over `livenessCount`): live **client tasks**,
and `BackgroundLiveness.active` keys — the push drain raises one while a permitted push backlog
waits, and a content-maintenance pass raises `CONTENT_MAINTENANCE_PASS` for its duration. The **pull**
half of the cycle, and a drain with an empty queue, raise nothing. A promotion with no foreground
client bound may be **refused** by the OS, and the code then degrades to bound-only on purpose (the
refusal used to be a fatal crash loop); degraded is background.

`SyncScheduler.backfillContentDeclarations` is single-flight per process (a second caller prints
`skipped: a pass is already running in this process`), and after `MAX_UNANSWERED_IN_A_ROW` (10)
manifest fetches with no answer the backfill prints `STOPPED after K of C manifests …` and declares
nothing.

**Owed:**
- ⚠⚠ The job-held passes are **not measured on a phone, nor under Doze** (`control-background-work-off-fgs.md` §7).
- ⚠⚠ **A pass that starts with no foreground client bound** is unverified on a phone: the scheduled
  pass on a phone lying idle, which is when it is meant to run.
- Measure how often the drain's own promotion is refused on this phone.
- ⚠ Whether **ordinary background sync** on this phone is starved the same way is unmeasured, and worth
  measuring: the Mac and iPhone hold 197 of the phone's 1,474 file rows, and why is still open
  (`content-plane-cr2-quorumless.md` §12.1).

### `initSyncService` is at a size D8 will not dex — one more local broke every Android build (2026-09-25)

`initSyncService` (`SyncProxyInitialization.kt`) is at the size where D8 crashes dexing the file
(`ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 52`): adding **one** `val` local
broke every Android build of master, with no construct at fault. Locals are kept out (construct at
the call, read back off the owner; `successorDeclarer` as a named top-level builder instead of nested
suspend lambdas).

⚠⚠ **Invisible to every JVM gate**: `testAndroidHostTest` compiles Android Kotlin but never dexes, and
a `commonMain`-only change owes no smoke run by House Rule #8's wording. Only
`scripts/android-smoke.sh` — or `./gradlew :control:mergeLibDexDebug` (~20 s) — sees it.

**Owed:** split `initSyncService` into phase functions so the next addition is not a coin-flip, and
consider a dex check in `run-gate.sh`. Until then: never grow it; add helpers outside it.

### iOS minted no thumbnail class at upload for nine days — the photos it missed are not repaired (2026-09-25)

From 2026-09-16 for nine days, **every upload on iOS stored no thumbnail class**:
`ThumbnailClassMinter.renderStill` threw a `ClassCastException` assigning `Render.Bytes` to a local
Kotlin/Native had typed as its initializer, `Render.Declined`, and the catch-all made it
`Render.Failed` silently. `UploadMintsThumbnailClassesTest` (tenshu-sdk-ios-drive, iosTest) pins the
real upload path on K/N. The uploading iPhone draws from its local original and looks fine; every
other store shows a grey tile.

⚠⚠ **The files uploaded in that window stay thumbnail-less.** `LIST` and `LARGE` are minted at upload
only and are never in a wanted set, so the backfill (§15.2 of `drive-synced-thumbnails.md`) will not
repair them; `XLARGE`/`XXLARGE` are repaired only where a device declares them. A repair needs the
device holding the original — the iPhone that uploaded — to re-mint every class for its own uploads
since 2026-09-16. Not built.

⚠ The trap generalises: in commonMain suspend code, never reassign a local initialised with a sealed
`object` — compute the answer as an expression.


### Every production box exposes `:22` to the whole internet, with long-lived keys and no CA (2026-09-24)

`ssh_allowed_cidrs` defaults to `["0.0.0.0/0"]` in `deploy/scaleway-relay`, `-commerce` and `-apex`;
no cloud-init in `deploy/` touches `sshd_config`; and `grep -rl "TrustedUserCAKeys\|ssh-keygen -s"
deploy/ scripts/` returns nothing. Access to the relay, the commerce issuer and the apex box is a
long-lived SSH key reachable from any address on earth.

⚠ **Not a runbook fix.** Scaleway injects Project SSH keys **at first boot only** — there is no
`ssh_key_ids` argument (a variable that looked like the knob granted nothing and is gone). So a
mistake while tightening this is recovered by instance rebuild or console VNC, not by another
`apply`, and on the relay a rebuild is an outage procedure (`deploy/scaleway-relay/README.md`).

⭐ **The pattern to copy is already written**, in the dev-lab module `deploy/scaleway-dev/`
(runbook `docs/how-to/deploy-dev-lab-scaleway.md` §3b). An optional `ssh_ca_public_key` that cloud-init installs as
`TrustedUserCAKeys`, so short-lived OpenSSH certificates replace long-lived keys — no daemon, no
overlay network, no third party, and every existing script keeps working. Verified there against a
real sshd in a container, including that a failed `sshd -t` is loud rather than a silently skipped
reload.

**Options, cheapest first.** They compose; none excludes another.

1. **Add the CA** to the three modules, exactly as the dev lab does. Purely additive — existing
   Project keys keep working, so there is no lock-out risk at all. This is the one to do first.
2. **`PasswordAuthentication no` / `PermitRootLogin prohibit-password`** explicitly rather than
   relying on the image's defaults. ⚠ Verify against the running box before changing it blind.
3. **Narrow `ssh_allowed_cidrs`** to known addresses. ⚠ Only workable for a human on a stable
   address — useless for CI or a cloud agent, whose egress is neither stable nor knowable.
4. **Take `:22` off the public internet entirely**, behind a self-hosted mesh. That is DL-3's
   decision for the dev lab and should not be made for production as a side effect of it.

**Stakes.** The relay holds ciphertext only (House Rule #1), so a shell there is not a plaintext
break — but it is root on the box that serves every client, holds the device-key JWT signing
material's trust anchor, and runs the entitlement gate. Commerce is worse: `ISSUER_SEED`, the key
that mints "paid", lives in its `deploy.env`.

**Sized:** option 1 is ~30 minutes for all three in the templates. ⚠⚠ Getting it onto the running
boxes is a separate step, and an earlier version of this entry had it wrong: a `user_data` change
does **not** replace the instance (it plans `1 to change`, in place, and cloud-init never re-reads
it — measured on the dev lab 2026-10-12 and on the relay 2026-10-13). So either rebuild with
`-replace`, which on the relay is an outage procedure, or install the CA by hand, which is what
the dev lab did on 2026-10-13 with no restart of anything but sshd: copy the public key to
`/etc/ssh/tenshu_ca.pub` (0644 root), add `TrustedUserCAKeys`, `sshd -t`, reload. Existing keys
keep working throughout. ⚠ A certificate is no use to a cloud agent: its sandbox cannot open port
22 at all (`ops-dev-lab.md` §10.8).

### Two concurrent fetches of one file's bytes can promote a corrupt original (2026-09-18)

`FileContentManifestApplier` reassembles a file's chunks into a **fixed** temp path,
`"$path.tenshu-partial"`, derived only from the destination — and deletes it on entry. Two resolves
of the same `File.Data` therefore share one buffer: the second entrant truncates the first's work,
the first keeps writing at its own offsets (`writeBytesRange` is `RandomAccessFile.seek().write()`,
so it recreates the file with a sparse zero prefix), and both then pass their own integrity checks,
because `offset` and `wholeFileHasher` are computed from the **in-memory plaintext each one
decrypted**, never read back from disk. Either may `moveFile` its zero-prefixed temp onto the real
path.

⚠⚠ **The corruption is then believed indefinitely.** Presence is a stat: `hasLocalData` reports true,
`EnsureThumbnailClass` short-circuits on `fileSize != null`, and D-17 makes that device render its
viewer previews *from* the damaged file. A render that then fails writes the `.declined` sentinel,
marking the class permanently un-mintable there.

⚠ **Pre-existing, but newly reachable from an ordinary gesture.** Nothing used to fire two concurrent
resolves for one file. X-4's mint-on-view does: swipe away from a photograph mid-download and back,
and the second dwell starts another because the first has not landed bytes yet (the client coroutine
is cancelled; the proxy-side work is not — a plain `IoOperation` call is never stopped by its caller,
by design, and this one fetches and writes bytes so it must not opt into `cancellableReads`). The op
has no dedupe or single-flight of its own either.

**Fix belongs in the applier, not the caller:** a per-content lock or a unique temp name, and an
integrity gate read back from what was written rather than from what was decrypted. A single-flight
in `EnsureThumbnailClassImplementation` would narrow the window this phase opened without closing the
defect for the offline keeper, sharing, or a plain open.

### A cancelled read still runs in Control unless it is wrapped — what cancellable reads left open (2026-10-01)

`core-background-processing.md` §6.2.1 made a read stoppable: inside `cancellableReads { }` a cancelled
caller now stops Control's walk within a row, on Android, desktop and in-process. ⚠⚠ It is
**opt-in**, because a plain call that its caller abandons has always completed in Control and writes
rely on it — so every read that is not wrapped still behaves as before. Open:

- **Adoption beyond paging.** Only `:core-paging`'s two sources are wrapped. Known abandoners left:
  Photos `CollectionsViewModel`'s bounded reads (its `retry()` cancels client-side only — another
  agent owns that file), `feedCaptureTimes()`'s whole-library `getAll`, and any `viewModelScope`
  read on a screen that can close mid-load. Wrap the *read*, never a block that also writes.
  ⚠ `EnsureThumbnailClass`'s mint-on-view (the concurrent-fetch entry above) must **not** be
  wrapped: it fetches and writes bytes.
- **Not on EPC** (iOS third-party / remote proxy): `EpcTaskTransport` keeps the default
  `callCancellableRead = call`, so the read is served and not stopped.
- **A teardown does not trip the checkpoint**, by decision: a session's death cancels the request
  job (suspended steps unwind, as before) but the synchronous walk runs on, because a mid-walk abort
  there would also stop a write's chain half-way. Revisit only with a reason to.
- **Walks that bypass the row seams** are not checkpointed: anything reading
  `executionContext.database` directly instead of `ExecutionContext.getArrayData`/`resolveList`
  (the store's own eager materialisation *is* checkpointed, via `getArrayData(…, checkpoint)`), and
  the serializer's own materialisation of lazy rows (it runs after the last checkpoint). None is
  known to be slow today; a new one would show as a cancelled read that still takes its full time.
- **Parked cancels** for reads that had already finished stay in the gateway's bounded set (256,
  oldest evicted). Harmless — ids are never reused — but on desktop each holds its closed socket.
- **Paging turns a cancellation into `LoadResult.Error`**: both sources' `catch (e: Exception)` also
  catches `CancellationException`. Pre-existing (a cancelled Binder call threw the same way once it
  returned); cancellable reads only make it arrive sooner. Superseded generations are discarded, so
  it has not been seen to render — but rethrow it if it ever does (a `runCatching` around a suspend
  call swallowing the caller's cancellation is the same defect).
- **Device-verified: no.** The JVM tests drive the real gateway with a blocking stand-in for the
  Binder transaction; the emulator smoke gate covers start-up only. The measurement to repeat is
  §37's: the abandoned first-generation `page.throw` should now arrive within ~one row of its cancel.

### Content repair declines every shared file — it reads `File.Field.PATH` (2026-10-02)

`REPAIRABLE_PROPERTY_IDS` lists `SharedFile.DATA_PROPERTY_ID`, but both repairs
(`ContentRepairHolder.bindLocalBytes`: the reader-side re-emit and the backfill's ensure) find the
bytes through `File.Field.PATH`, which a `SharedFile` row does not have. Its bytes are at the
`/.shared/…` path `FileBytesPropertyWriter.resolveExternalPath` answers. So a shared file whose
content the relay lost, or whose manifest never landed, is declined on a device that holds it.
A decline is "this device cannot help", never a wrong upload.

- **Not new:** the lookup moved unchanged out of `initSyncService`; desktop has had it since ST-3f.
- **Fix:** resolve the path with `externalPropertyWriter().resolveExternalPath(context, holder,
  propertyId)` in `bindLocalBytes`, with a test that a `SharedFile` holder is repaired. ⚠ The
  upload must then reach the **shared realm's** relay and key, which the entry below says a
  tombstone does not; check the emit's realm routing before enabling it.

### A manifest-less file MOVED during the backfill pass is skipped by that pass, which may latch (2026-10-02)

The declaration backfill hands a content with no manifest on the relay to the device's repair, by the
holder its walk read. If the file is moved between the walk and that call, the old holder's row is
gone, `localBytesContentEnsure` reads no path and answers "this device cannot help", and nothing
keeps the pass open: a declined repair is the ordinary answer for ~3,000 never-emitted thumbnail
entries per pass, so it cannot count against completeness. The successor declare
recreates the edge, not the manifest.

- **Needs all of:** a file whose manifest never landed, moved in the ten minutes a pass runs, on a
  pass with nothing else left open. Not observed.
- **Until fixed:** such a file stays unreadable on other devices until the next backfill format bump.
- **Fix:** let the repair tell "row gone" from "no local bytes" (for `File.Data` only: a `SharedFile`
  never has a path, see the entry above) and count the first as a failed repair, so the next pass walks
  the successor. ⚠ Moot once B3's re-stamp pass replaces the backfill (`content-plane-cr2-quorumless.md` §12.2).

### A shared-realm tombstone has no path to its own relay (2026-09-17)

Blocks route per realm: a shared realm's outbound queue is
`PENDING_BLOCKS::<realmId>` and `SyncScheduler.drainSharedRealms` pushes it by calling **that
realm's** `SyncRelay` directly, *"not the DI `PushBlock` op, which is bound to the personal relay"*.

Tombstones have none of that. `PENDING_TOMBSTONES` is a single un-partitioned array, and
`PushTombstoneImplementation` holds one injected `SyncRelay` — the personal one. The relay refuses a
mismatch outright: `403 tombstone.ownerId does not match authenticated owner`.

⇒ a tombstone for a shared-realm entity is stamped with the **personal** `ownerId` so that it drains
at all, which means it reaches the personal stream rather than the shared realm's. The shared
realm's peers never see it, and the deleted row lives on there. ⚠ Stamping the entity's realm
instead is worse, not better: the push then 403s on every tick for ever, the block is never removed,
and `pendingTombstoneCount() == 0` — the "has this device actually left" signal `ForgetThisDevice`
waits on — can never be satisfied.

**The fix is a per-realm tombstone path**, mirroring `drainSharedRealms`: partition the queue by
owner and push each partition against its own realm's relay. Sized as its own slice; the gap is named
at both sites (`SyncObserver.enqueueTombstone`, `SyncScheduler.drainPendingTombstones`).

⚠ Pre-existing, not introduced by the EI-1 tombstone-naming work — before it, the same blocks were
stamped personal and reached the same wrong stream.

### A signal-only stream evaluates a full projection per change, where a row-hash diff would do (2026-09-15)

**Files**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/StreamRegistry.kt`
(`setDatabaseListener`'s `onUpdate`), `core-paging/.../PagingUtil.kt` (`pagingSource`'s
`invalidation` hook), `drive-shared-logic/.../TenshuFilesRepository.kt` (`listingRow`).

A change observer exists to answer one bit — *did anything a row renders change?* — and today it
answers it by **evaluating the consumer's whole projection** over the whole collection and diffing
the materialised rows. Drive's sorted listing must observe the projected chain rather than the bare
receiver, because a `File` read bare is a composite that diffs by `path` alone and an in-place
overwrite would signal nothing (`drive-sorted-folder-paging.md` §3 B5). That is correct, and it is
now **the dominant per-open cost**: measured on a OnePlus over a 618-row folder, the observer's
subscribe was ~18 % of Control's samples (≈1.1 s CPU) against 3.4 % for all the fused page loads
combined, and it re-runs on every write to the folder — O(N) per file during a backup into an open
folder.

⭐ **The engine already stores what the diff needs.** `ObjectStore.DbEntry` holds a row's fields
*and* its properties, so a signal-only stream (`Stream.Params.SIGNAL_ONLY`) could diff **stored-row
hashes** — no deserialisation, no relation resolution, no projection — and emit the same one bit.
Two things fall out: the *bare* receiver becomes property-aware for free, which is the exact gap
B5's projected observer works around, and the `invalidation` hook added for Drive could retire.

⚠ Not free to get right: a property resolved *through* a relation on another row (`Folder.FileCount`
counts the subfolder's array, not the folder's own entry) is not in the row's own bytes, so a hash
diff alone would miss it — the nested refs a projection discovers are also what registers those
arrays for invalidation. So this is "diff cheaply, but keep discovering refs", not "delete the
projection". Sized: a slice of its own in `:core-common`, with `ExecutionContextStreamTest`'s diff
cases as the parity guard.

### Two identical observers in one session share a stream id — closing one kills the other (2026-09-15)

**Files**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/request/LocalRequestChain.kt`
and `TaskRequestChain.kt` (`createStreamId`), `StreamRegistry.setDatabaseListener` /
`removeDatabaseListener`.

`createStreamId()` derives a stream's id from **`getCallstack().hashCode()`**, and the callstack's
DTOs hash by value (`OperationDto` is a data class, `TListDto` overrides `hashCode`). So two
collectors in the same client session that build the *same* chain — two `changes()` observers of
one folder listing, say — register with the proxy under **one** id. `StreamRegistry` keys
`streamIdToUpdateCallback` and `streamIdToRef` by that id: the second registration overwrites the
first's callback, and whichever collector ends its stream first calls `removeDatabaseListener(id)`,
which drops the registration the other still depends on. The survivor gets no error and no `End`
— its signals just stop.

**Found by** `SortedFolderPagingIntegrationTest.continuousWritesDuringAScroll_…` in its first
draft: a timing collector beside a `boundedSnapshot` of the same sorted chain saw generations at
3, 19 and 526 ms and then nothing for the rest of a 4 s write burst — the snapshot had settled at
~500 ms and closed, taking the watcher's registration with it. One collector, and the same test
passes. (`docs/proposals/drive-sorted-folder-paging.md` §10.6.)

⚠ **Not test-only.** Any two screens paging the same chain at once in one session do this in
production — the Files screen and the move picker on the same folder are the obvious pair, and
each keeps its `Pager` alive under `cachedIn`. Whichever leaves first silences the other's
invalidation until it is rebuilt for another reason. It has the exact symptom shape
`drive-folder-listing-consistency.md` was written for, and would read as one more "listing went
stale" report.

**Fix**: a per-session counter (or a random id) in `createStreamId`, not a content hash — nothing
on the proxy side depends on the id being derivable from the chain (it is carried in
`Stream.Params.STREAM_ID`). Sized: small, `:core-common` only, plus a `StreamRegistry`-level test
that two registrations of one chain survive the other's removal.

### `StreamRegistry`'s per-array bookkeeping is shared by streams that hold different locks (2026-10-07)

**Files**: `core-common/.../proxy/StreamRegistry.kt` — `refToStreamListeners`, `refToStreamCount`,
`refToListenerHandle`, `streamIdToNestedRefs`.

**Not fixed.** `onUpdate` runs under a *per-stream* mutex
and calls `registerRefForStream` / `unregisterRefForStream`, which mutate plain `mutableMapOf`s
keyed by **array** — shared by every stream of the session. `triggerUpdate` reads the same maps
from whichever thread performed the write, and `setDatabaseListener` / `removeDatabaseListener`
from request threads. The captured trace shows two workers inside register and unregister for the
same array hash in the same millisecond.

The reference collector itself is thread-confined (`ThreadConfinedSlot`, pinned by
`ReferenceCollectorConfinementTest`), so a race here needs two streams that legitimately share a
nested array changing their reference sets at once — rare but not excluded. The failure: a stream that
stops hearing an array — or a corrupted `HashMap`.

⚠ **Not a one-line lock.** `registerRefForStream` calls `database.setArrayListener` (takes the
store lock) and `triggerUpdate` is called *from* an array listener, i.e. under the store lock, so a
registry lock taken in both places inverts. Sized: small, `:core-common` only — copy-on-write maps
as `ObserverRegistry` and `ClientRegistry` already use, or a registry lock that is never held
across a `database` call — plus a JVM concurrency test in the style of
`ClientRegistryConcurrencyTest`.

### Thumbnail backfill sits behind the quota gate — an over-quota realm cannot give an existing file a missing class (2026-09-14)

Found when it stalled M4's motion-class device pass halfway (`photos-video-support.md` §14): nothing
minted, and the absence looked like an encoder that had stopped working. The mechanism is read off
the code, not inferred from the symptom.

**Both routes to a thumbnail class sit behind the same condition.**

| Route | Gate | Effect on a subscribed, over-quota realm |
|---|---|---|
| Upload | `UploadFileImplementation.kt:209` — `ensureWithinQuota(fileSize)` throws `InsufficientStorageError` **before** any render (stills at :281/:286/:297, motion at :492), and before any local write or `File` save | the upload is refused whole: no file exists, so nothing is left missing a class |
| Backfill (W-1/W-2) | `BackfillThumbnailClassesImplementation.kt:197` — `ensureWithinQuota(capFor(class))`, per class | the repair path is closed too |

⚠⚠ **The backfill is the part that matters.** It exists to give an **existing** file the classes it
lacks, most often a class introduced after the file was uploaded (`MOTION`, M4, is exactly that),
and on an over-quota realm that repair is closed. ⚠ The quota gate does **not** create such files:
a refused upload stops before anything is written, so it leaves nothing behind for the backfill.
The files that need repair got that way for other reasons, and the gate only keeps them that way.
⚠ Its gate is **deliberate**, and its KDoc says so
("conservative on purpose, so a realm at the edge of its quota stops before the render"), charging
the class's **cap** rather than its likely size. This is a tension to resolve, not a bug to revert.

⚠ **The scale is the argument for looking again.** The caps are `LIST` 64 KB, `LARGE` 48 KB,
`XLARGE` 256 KB, `MOTION` 96 KB — against a quota measured in gigabytes, for bytes a device that
already holds the original can always render.

⚠ A replace is gated the same way, on its full new size (next entry), so on an over-quota realm no
path mints a class for an existing file.

**How visible each refusal is, and the backfill's is barely visible at all.**

- **Upload** is not silent: `InsufficientStorageError` is typed and thrown to the caller.
- ⚠⚠ **Backfill is silent.** `BackfillThumbnailClassesImplementation.hasRoomFor` catches the quota
  refusal (and every other non-cancellation throwable) and returns `false`. `ThumbnailClassBackfill.run`
  records `stoppedBy = "quota"`, logs one outcome line, and the operation **returns successfully**.
  No typed error reaches Drive; that log line is the only trace.
- ⚠ **The banner covers only some of it.** `SyncBanner.StorageFull` follows `StorageStatusModel.isFull`
  = `hasStorage && (relayRejected || remainingBytes <= 0L)`, where `relayRejected` is the relay's own
  refusal latch (`SyncHealthStatus.CONNECTIVITY_STORAGE_FULL`, joined in by `TenshuFilesRepository`).
  So a realm the **relay** has already refused does show the banner. What stays bannerless is the
  **local preflight** refusal that comes first: the gate charges a class **cap** on top of what is
  already used and queued, so a realm with room left but less than one cap (under 96 KB for `MOTION`),
  and no relay rejection yet, is refused every backfill with no banner at all.

So during a bulk drain "previews stopped appearing" is all anyone sees, which is exactly how it
consumed half a device pass before anyone looked at the quota.

**Worth considering, none of it decided:**

- Mint **locally** and defer only the content-plane emit until quota frees — the classes are local
  blobs before they are relay bytes, and a device that holds the original can always render them.
- Or exempt derivatives under some floor from the gate, on the grounds that a realm which cannot
  afford 96 KB cannot afford anything.
- Or, at minimum, make "no classes because over quota" distinguishable from "the renderer failed":
  surface the backfill's `stoppedBy = "quota"` beyond the log. It is the cheapest of the three, and the
  one that would have saved the device pass.

### A replace skipped the quota gate on a false premise — the overwrite rules that keep it closed (2026-09-15)

An overwrite grows usage: it mints a fresh random `contentId` and emits every chunk under a fresh CEK
and nonce (refs are `blob-<sha256(ciphertext)>`, so nothing dedups), and the superseded content is
freed only later by the relay's sweep (`content-plane-cr2-b6-spec.md` §7). So `UploadFileImplementation`
holds these, each pinned by `ReplaceQuotaGateTest` / `UploadOverwriteReclamationTest`:

- ⚠⚠ An overwrite is gated on its full new size, exactly like an upload.
- ⚠ An overwrite whose whole-file hash equals `File.ContentHash` (with a `ContentId`, and the same
  bytes already in this device's store) returns before the gate: same `contentId`, no emit, no history
  row. The local clause compares bytes, never sizes: a row synced from a peer names the new content
  while the local file may be the previous version at the same length, and an overwrite from that
  device is how the new bytes land.
- ⚠⚠ It also refuses that shortcut while `/.unsent-content/<contentId>` exists (written before
  `saveData`, removed after the hand-off to the emitter returns), so a write interrupted in that window
  takes the full write on retry.
- ⚠⚠ Keeping the id trusts the relay to still hold it, so the no-op calls
  `FileContentSyncEmitter.ensureContentOnRelay`: it fetches the manifest and checks every chunk
  (`ContentBlobStore.hasChunk`; on iOS a staged background upload counts), skips only when all are
  present, and otherwise re-emits under the same `contentId`. ⚠ The manifest alone is not proof:
  reclamation deletes chunks before the manifest. A re-PUT ref replaces a stale manifest on every relay
  store (`ContentBlobReplacementContractTest`).
- ⚠ An overwrite of a file whose previous version is still journalled is not charged for both: the
  gate subtracts that entry's bytes (`queuedContentBytesFor`, clamped to the per-tick queue figure).

**Deliberate cost.** A subscribed realm at its limit cannot save a *changed* file, even one that
shrinks it: the new bytes land before the old ones are freed, and that is what the relay would refuse.

### An upload interrupted before its content is journalled is never uploaded (2026-09-15)

**Where:** `UploadFileImplementation`. It writes the bytes, saves the `File` row, waits (bounded) in
`awaitEmitIdle()`, and only then journals the content (`FileContentSyncEmitter.journalFileContent`),
the durable write that makes an upload survive a crash. The span from `saveData` to that journal
entry runs `NonCancellable`, so a cancellation — Stop, an OS stop of a backup job, a wire cancel —
can no longer split them (`UploadCancelKeepsRowAndJournalTogetherTest`; `drive-synced-folders-phase2.md`
§4.1b).

⚠ **A process death** in that window still leaves a row that names content no journal entry will ever
upload. A peer that received the row has an `OnDemand` fetch that cannot succeed. Re-running the same
`UploadFile` throws `AlreadyExists`, so the one caller-side retry does not reach it. An overwrite of
that file does: identical bytes take the full write because of the marker below, and changed bytes
always did.

**What exists:** the window is recorded. `/.unsent-content/<contentId>` is present in the local
`FileDatabase` from before `saveData` until the hand-off returns. A startup pass that finds a marker
whose `contentId` a `File` row still names could re-drive the emit for that path. Nothing does that
yet, and nothing removes a marker whose file was deleted before it cleared (1 byte each).

### A recovery phrase the device cannot decode — what is left (2026-09-15)

`BIP39_ENGLISH_WORDLIST` is the published `bip-0039/english.txt` (pinned by `Bip39WordlistTest`); the
pre-2026-09-15 list had drifted (threat model §11.2 records what that cost and why no migration was
written), so every phrase minted before then is void. What matters now is the **shape of the
failure**, which is the same for any future wordlist change. An old-list phrase throws
`ChecksumMismatch` (or `UnknownWord`) from `Bip39.decode` for all but ~0.25 %:

| path | what a phrase this build cannot decode does |
|---|---|
| `RecoveryClaim` / web `Recovery.claim` | typed `invalidMnemonic` → "that isn't a valid phrase" (pre-validates before `initialize`) |
| passphrase / biometric unlock | `UnlockLocal` / `UnlockBiometric` raise `RecoveryPhraseUnreadableError`, distinct from a wrong credential (`false`). `UnlockUnreadablePhraseTest` |
| `setUpLocalUnlock` / `setUpUnlock` / `setUpBiometricUnlock` | refuse via `readRoundTrippablePayload`; the plaintext survives the refusal. `WrapRefusesUnopenablePayloadTest` |
| `initialize` | derive, persist, seat — a derivation failure writes nothing. `InitializeAtomicityTest` |
| ⚠ startup auto-load (`SyncManagerImpl` init) | the constructor returns normally; the throw goes to the thread's uncaught handler on `Dispatchers.Default`. On desktop JVM that prints and the proxy continues (observed). On Android `RuntimeInit`'s handler kills the process — reasoned, **not** observed |
| ⚠ a phrase that still decodes (~0.25 %) | no error at all: a different `ownerId`, so the device is a stranger to its own realm |

⚠⚠ **An unreadable stored phrase is not a wrong credential — never collapse it into `false`.**
`false` tells the user to retype; for a stored phrase the passphrase was right and retrying can never
work. Hence a distinct typed error (with copy in `strings.xml`, `CATALOGUE` and iOS's
`ErrorCopySurface`). ⚠ It is **fieldless on purpose**: the remedy is the same for `UnknownWord`,
`InvalidLength` and `ChecksumMismatch`.

⚠ **The last row has no detection at all**, and cannot get one from the checksum: those phrases are
valid under both lists and mean different keys. A phrase-provenance marker (a stored wordlist digest,
checked where the phrase is read) is the only thing that would catch it — design it *before* the next
wordlist change.

⚠⚠ **The class is invisible to `:sync-service-proxy`'s test suite by construction**: the suite wires
`BootstrapSyncCryptoManager`, whose `deriveKeys` never calls `Bip39.decode`. A test that drives an
undecodable phrase through *derivation* needs the real crypto manager and cannot live in
`commonTest` (`InitializeAtomicityTest` fails derivation directly instead). The *wrap* gate is the
exception: `SyncManagerImpl` calls `Bip39.decode` itself, so `WrapRefusesUnopenablePayloadTest` sees
a real undecodable phrase under the stub.

⚠ All five BIP-39 lengths remain wrappable — rejecting anything but 24 words would lock existing
short-phrase realms out of unlock setup (next entry).

⚠⚠ **`initialize`'s two boundaries pull in opposite directions; do not collapse them.** Derivation
must come before the writes, or an undecodable phrase is persisted before anything notices. But the
in-memory seating must come *after* them, because `derivedKeys` is what `isEnabled()` reads and both
`CreateRealmImplementation` and `RecoveryClaimImplementation` guard their `initialize` call with
`if (!syncManager.isEnabled())` — seat first and a `storeSecret` failure (Keystore error, disk write)
leaves the manager claiming custody it never wrote down, with the retry skipping initialization.

### A phrase shorter than 24 words crashes passkey registration on two of three paths (2026-09-14)

`MasterKeyWrapper.wrap`'s entropy overload `require`s exactly `ENTROPY_SIZE = 32` bytes
(`MasterKeyWrapper.kt:97`). BIP-39 admits 12/15/18/21/24-word phrases, which `Bip39.decode`
accepts, yielding 16/20/24/28/32. The three web setters of `JsRuntime.currentEntropy` disagree
about what to do with the short ones:

| path | seats short entropy? | result for a short-phrase realm |
|---|---|---|
| `Recovery.claim` | **no** | `revealPhrase` → `unavailable`; passkey write stays v1 |
| `Session.unlockWithPhrase` | yes, unchecked | reveal **succeeds**, then `registerPasskey` **throws** |
| `Realm.create` | yes, unchecked | same |

⚠⚠ **The throw is the bad part, and it lands after the WebAuthn credential is already persisted.**
`wrapMasterKeyForCurrentRealm` picks the entropy overload whenever `currentEntropy != null`, so the
`require` fails mid-registration: the authenticator has the credential, the blob was never written.
Declining to seat (what `claim` does) degrades to the v1 fallback instead.

⚠ **Not fixed on the other two paths, and it is a product call rather than an oversight.** Adding
the same guard to `unlockWithPhrase` would stop the crash but *remove* working phrase reveal from
any short-phrase realm that unlocks today — trading a late crash for an immediate regression. The
alternatives are to widen the wrapper to carry 16/20/24/28-byte entropy (a v3 payload, so a format
change), or to reject non-24-word phrases at realm creation and leave existing ones alone. Pick one
before touching the setters; the current split behaviour is at least documented at each site.

⚠ Reachability: every **production** caller of `Bip39.generateMnemonic()` takes its 32-byte default
(`NewUserScreens.tsx:72`, `DevPage.tsx:44`), so a short-phrase realm is one brought in from
elsewhere — but `generateMnemonic(entropySize)` accepts a smaller value, so it is not structurally
impossible.

### Web's "Lock now" does not lock the realm — A1 stays resident (2026-09-14)

Three separate gaps, one shape, and they want fixing together — a partial answer to any of them
leaves the phrase or the master key reachable after the user has been told the device is locked.

`Session.clear({})` is what both Lock-now callers dispatch
(`web/apex/src/landing/SignedInBanner.tsx`, `web/apex/src/control/DeviceMenu.tsx`): it clears the
session store and routes the UI to `/lock`.

**1. ⚠⚠ It never dispatches `LockRealm`.** There is no call site for it anywhere in
`:tenshu-sdk-js` or `web/`. `SyncManagerImpl.lock()` is what actually nulls `derivedKeys`,
`currentMasterKeyPair`, `currentOwnerId`, `realmEncryptionKeyPair` and the session recovery
phrase — so after web's "Lock now" the master identity (A1) and the working sync keys stay
resident in the proxy-side manager, and only the UI has moved. `docs/sync/threat-model.md`
describes a user-initiated lock as returning the device to the *sealed* state; on web it does not.

⚠ **Why this needs care rather than a one-liner.** Dispatching `LockRealm` would also null
`derivedKeys`, and web's unlock path (`Session.unlock` → `unwrapMasterKeyForCurrentRealm`) reseats
the **master keypair** only — `reseatMasterKeyPair`, not `initializeWithKeys`. Compare
`Session.unlockWithPhrase`, which needs both. So a lock that really cleared the derived keys may
leave a PRF unlock with sync still disabled: a security gap traded for a can't-sync-after-unlock
bug. Establish what the unlock path restores before changing what the lock path destroys, with the
`:tenshu-sdk-js` suite actually running.

**2. ⚠⚠ Nothing clears `JsRuntime.currentEntropy`, on any path.** The web-side BIP-39 entropy has
four setters (`Realm.create`, `Session.unlockWithPhrase`, `Recovery.claim`, the v2-blob PRF unlock)
and no clear, so it survives both "Lock now" and "Disconnect this browser" for the life of the page
and `Recovery.revealPhrase` keeps working after either. `ForgetThisDevice` nulls the in-memory keys
proxy-side and structurally cannot reach this field, which is web-side only.

**3. ⚠⚠ And a clear would reach only the initiating tab.** Each tab of the origin builds its own
`JsRuntime` singleton (`docs/web/architecture.md` §"Cross-tab"); only the session store beneath is
shared, via IndexedDB. So clearing in tab A leaves tab B's copy resident, and the control shell
(`web/apex/src/control/ControlShell.tsx`) already mounted in tab B re-checks `Session.detect()` on
mount but not afterwards — it can stay on the reveal screen and produce the phrase after the lock.
Closing this needs the clear broadcast to peer tabs (a `BroadcastChannel` on the origin, each tab
erasing its own heap state) or peer tabs forced to reload.

⚠ **Do not land a one-line `currentEntropy = null` in `Session.clear` on its own.** It addresses only
part 2, in one tab, while parts 1 and 3 leave A1 resident and the phrase reachable elsewhere, and it
reads as a sealed state the code does not reach. These are one piece of work, not three one-liners.

### A delete frees the deleting device's disk and no one else's — a peer keeps the plaintext for ever (2026-09-06)

**Measured on hardware, not inferred.** Delete a file from device A; every peer that had downloaded
it keeps the full plaintext on disk, with no row pointing at it and nothing that will ever collect
it. Only the device that issued the delete frees its own space.

**The device pass.** Deleted one file from desktop Control, watched the phone (its own store, via
`run-as`):

| | before | after |
|---|---|---|
| `tenshu/file` rows | 949 | **948** |
| the target's row | present | **gone** |
| `files/Trash/8d903f90-…-IMG-20260902-WA0000.jpg` | 33,928 B | **33,928 B** |
| plaintext with no row, whole device | **0** | **1** |

⚠ The row going is what makes this conclusive rather than sync lag: the tombstone arrived and
applied. The bytes simply were not part of what it applied.

**Why.** Three passes, none of which owns the case:

- `SyncProcessor.processDeleteBlock` applies an ingested tombstone as `deleteData(dataId,
  SYNC_INGEST)` — the row. It holds no `FileDatabase` and touches no bytes.
- `DeletedFolderSubtreeReaper` (the `onDataDeleted` observer) *does* drop bytes, but only for the
  deleted entity's **children** — `fileDatabase?.deleteFile(childId…)` inside the child loop. A leaf
  file has no children, so it returns at `if (children.isEmpty()) return`. ⭐ Folder deletes are
  therefore fine; the gap is the **root** of the deleted subtree, which holds bytes exactly when it
  is a file — i.e. the ordinary "delete one photo".
- `OrphanedFileSweeper` collects **rows** whose ancestors are absent. Its constructor takes
  `executionContext` and `objectStore`; there is no `FileDatabase` in it, so orphaned *bytes* are
  outside what it can even see.

**Scale.** This is device space, not relay space, and it lands on every peer that had fetched the
file. A big clear-out on a laptop frees the laptop and leaves a phone that kept the Drive locally
exactly as full as it was. ⚠ It is also invisible to `GET /sync/usage`, which reports the content
plane.

⚠⚠ **A second trigger, and it is already firing: `MoveFile`.** A move retires the old path through
`DataIdMigrator`, which emits a **real tombstone**, so on a peer a move and a delete are the same
event. Nothing on the ingest side renames the peer's local plaintext — `fileDatabase.moveFile` runs
only on the initiating device — and `MoveFileImplementation`'s own comment says the peer re-fetches
by `contentId` "straight to the new path". So every move already strands the peer's old-path bytes
in exactly this way. The device pass above used a delete; the move case is a code read.

#### The forward collector — built, off

`OrphanedBytesCandidates` + `OrphanedBytesPolicy` (`:file-service`), `IngestedDeleteRecorder` +
`OrphanedBytesSweep` (`:file-service-proxy`), riding the sync tick, off behind
`ORPHANED_BYTES_SWEEP_ENABLED = false`.

⭐ The recorder needs no lookup: a `File`'s `DataId` **is** its path, so an ingested delete can be
recorded from the id alone, as an observer beside `DeletedFolderSubtreeReaper`. ⚠ `SYNC_INGEST`
only: a local delete drops its own bytes and a local move renames them.

What constrains any design here:

1. ⚠⚠ **Relocation is not implementable.** "Does a live row elsewhere carry this `contentId`?" cannot
   be asked of orphaned bytes: `contentId` is a **stable random id on the entity**, not a hash, so once
   the row is gone nothing links the bytes to any content. The move case is delete-and-re-download.
2. ⚠⚠ **An upload writes bytes before its row**, so a user's own in-flight upload looks stranded.
   `OrphanedBytesPolicy.DEFAULT_SETTLE_MS` (one hour) bounds that window; it bounds a **local**
   operation and expires into the safe direction.
3. ⚠⚠ **"No row" is not "orphan".** On one real device **9,195 of 10,141** stored files were
   `/.thumbnails/` and `/.previews/` — no `File` row by design, along with cached map regions,
   inference models and `/.health/streams/`. `isReservedNamespace` leans on the convention
   `FileBytesPropertyWriter.thumbnailPath` states: a reserved `/.…` prefix cannot collide with a
   personal `"/…"` path.
4. ⚠ On the initiating device `MoveFileImplementation` calls `fileDatabase.moveFile(source, target)`
   *before* `DataIdMigrator` deletes the old row — the ordering is load-bearing; preserve it.
5. ⚠ Never resurrect the 0-byte trap: an empty write is not an absence (`fileSize` → `0L`, not
   `null`), which reads as "downloaded" and wedges the OnDemand fetch off permanently. Use
   `deleteFile`, never a zero-length write.

⚠ **Forward-only** (maintainer's call): bytes stranded before the recorder existed have no candidate.
Collecting those needs an enumerable `FileDatabase` — which **no platform has** — so a backfill is a
four-platform storage change, not a sweep.

⚠⚠ Still owed before the flip: a device pass that reads the candidate list back and checks its
verdicts against what is actually on disk. This is the only pass in the project that deletes a
user's *local* bytes without the user having asked for that file to go.

⚠⚠ **Test harnesses must pass a real `fileDatabase` to `initFileService()`** (as `FileTestProxy`
does, mirroring production's `get<FileDatabase>()`): with none, `DeletedFolderSubtreeReaper`
registers a null one and every test measures its no-op branch while the suite stays green.
`TombstoneLocalBytesTest` pins the leaf (leaks), the folder (cascades) and the local delete (frees).


### W7's streaming download never ran — the web resolver forwarded only `resolve` — the rules it left (2026-09-01)

`JsRuntime`'s `OnDemandResolution` wrapper forwards all four methods to `OnDemandResolutionImpl`.
⚠⚠ `OnDemandResolution` gives `openContentStream` / `readContentRange` / `closeContentStream`
permissive defaults (`null` / `null` / `false`) so test fakes compile, so a forwarding wrapper that
overrides only `resolve` silently disables W7's windowed download **and** `<video>` streaming (S6b)
with no error — both fell back or 500'd until the wrapper forwarded them.

⚠⚠ **A `T?` web surface is correct only when null has exactly one meaning.** Once a surface is
declared `Promise<T?>`, a failure has nowhere to go but `null`. Surfaces that can both legitimately
have nothing *and* fail answer `{ ok: true, … } | { ok: false, error: { kind, message } }`
(`internal/JsResult.kt`): `ContentStream.openStream`, `FileSystem.getData` / `getPreview` /
`details`, `SharedFiles.readSharedFile` / `shareFolder`, `Timeline.moment`; `Realm.ownerId` and
`SyncHealth.status()` are genuinely single-meaning and stay nullable.

⚠ **`client.call<T>` is a cast, not a check.** Both apps typecheck clean against a changed Kotlin
surface while the TS wrappers still declare the old type, so update the wrapper annotations in the
same commit as the surface.

**Still open:**
- ⚠⚠ `SharedFiles.shareFolder` is a multi-step write (create realm, fan the folder in, mount it back)
  whose steps are **not undone**: a failure after step 1 leaves a shared realm on the relay.
  `error.kind` (`realm` | `share` | `mount`) makes the partial state visible; nothing cleans it up.
- `errorResult` is still copy-pasted privately in `Devices.kt`, `Recovery.kt` and `Pairing.kt`;
  fold them into `internal/JsResult.kt`.

### An iOS crash reports `abort()` and nothing else — and no iOS app keeps a log (2026-08-25)

⚠⚠ **An undeclared Kotlin exception at the ObjC boundary is undiagnosable from the crash report.**
`RootView.swift` calls `DriveReadSurface.shared.installLocalRuntime()` bare, and the Kotlin function
declares no `@Throws`, so any throw is trapped by `Kotlin_ObjCExport_trapOnUndeclaredException` into
`abort()` with the message and stack discarded. Five `DriveIos` crashes on one phone (2026-08-25)
bought exactly one fact: something in `install()` threw.

`DriveLocalRuntime.install()` catches, `println`s the exception class, message and stack, and
**rethrows** — the abort stays (a runtime that failed to install must not look healthy: Drive would
render an empty, writable-looking Files screen over no realm). ⚠ Not reproduced and not fixed: a cold
launch is clean, and `install()` returns early once installed. Throw candidates inside it:
`assemble(RealmKeyMaterialBridge.read(), …)`, `koin.get<BackgroundUploadManager>()`,
`DriveBackupSurface.start(…)`.

⚠⚠ **The same bare shape exists on the other iOS local runtimes** (Calendar, Contacts, Fit, Maps,
Timeline); only Drive logs before the abort.

⚠⚠⚠ **The wider gap: iOS has no post-mortem log at all.** No iOS app framework (`tenshu-sdk-ios-*`)
depends on `:diagnostics` (it is installed only by `timeline-android` and the web runtime), so on iOS
the façade is a no-op. The *only* way to read an iOS failure is a console attached at the moment it
happens (`xcrun devicectl device process launch --console <bundle-id>`), so any failure a user hits
alone is unreadable. Wiring an iOS `DiagnosticSink` that survives a crash is the real fix and is
unstarted.

### An iWork document cannot be uploaded — `.key` is a *directory* (2026-08-27)

`.key` (and `.pages`, `.numbers`, `.rtfd`, `.band`, `.imovieproj`, `.fcpbundle`, `.logicx`,
`.xcodeproj` via Files) is a package — a directory — and `NSData.dataWithContentsOfFile` returns nil
for one, so `IosFilesRepositoryPlatformDelegate.readFileBytes` fails. `TenshuFilesRepository.guardingSourceRead`
turns that into an ordinary `RepositoryError` ("upload failed") instead of an abort; ⚠⚠ it rethrows
`CancellationException`, or a user's upload STOP would report as a failure
(`UploadSourceReadFailureTest` pins both halves). ⭐ A coroutine abort (`processUnhandledException`) is
diagnosable from the `.ips`'s `lastExceptionBacktrace`; a `Kotlin_ObjCExport_trapOnUndeclaredException`
one is not (entry above).

**Still open: the upload itself.**

- **iOS** should flatten packages in Swift `stageForUpload` via `NSFileCoordinator` reading with
  `.forUploading`, which produces a zip snapshot and is the API built for exactly this. It would
  also fault in iCloud dataless placeholders, which iOS currently handles nowhere.
- **Desktop has the same hole.** `onUploadFile` uses a plain `FileDialog.LOAD` (`Main.kt`), and
  on macOS that is `NSOpenPanel` with `treatsFilePackagesAsDirectories` at its default `false`, so
  a `.key` is offered as a **selectable file** and its directory path reaches `readBytes()`. No
  Foundation from the JVM, so this one needs a `java.util.zip` twin.
- ⚠ **Detection must be structural, never by extension.** The same `.key` extension has two on-disk
  forms — the package, and a flat single file from an export/download. Use `isPackage` / `isDirectory`.
- ⚠ **Undecided, and it needs a device round-trip:** whether the archive uploads as `Foo.key` (the
  `.forUploading` zip nests the package as a top-level entry, so Keynote likely cannot open it) or
  as `Foo.key.zip` (honest and round-trips, but renames the file under the user). Both platforms
  must pick the *same* answer or one document lands under two names depending on the device.
- Android and web are N/A — SAF hands back a stream and `<input type=file>` cannot select a package.

⚠ **`guardingSourceRead` is a STOPGAP, not the design.** It duplicates, in app code, what
`LocalTaskExecutor` already does at platform level. The platform's copy misses this call only because
the read happens while evaluating the op's **arguments**, before dispatch; Android is covered
because its read happens *inside* `AndroidFileUploadImplementation`. The fix is to move the read into
the op, at which point this guard is deleted rather than relocated. Do not copy the pattern into other
repositories.

⚠ **If the desktop TCC probe comes back "denied", do NOT build FFM `SCM_RIGHTS` fd passing.** That
work lands in `desktopMain`, which the milestones.md JVM-less fallback ("make macOS a second
Apple-native target alongside iOS") would replace with `macosMain` wholesale — and a native
in-process app is its own responsible TCC subject, so the cross-process question dissolves exactly
as the camera-TCC SIGKILL does (`desktop-qr-scanning.md` §9). Leaving desktop on the byte-array path
with this guard is the cheaper wrong-looking answer, and the right one.

**Unrelated but found alongside:** nothing ever deletes the staged temp copies. `stageForUpload`
creates a per-upload directory under `tmp/` and there is no `removeItem` anywhere in
`FilesView.swift`, so every upload leaves a full duplicate until iOS reaps it.



### An op module outside `fileServiceCommonModule` is invisible to the shape baseline (2026-08-28)

⚠⚠ **Read before adding any new operation module.** `SchemaShapeBaselineEnforcementTest` builds its
registry from `fileServiceCommonModule`. Ops registered by a *separate* module are outside its graph
entirely, with two silent consequences:

- **Their positional layout is pinned by nothing.** Renaming all four by-reference upload/replace ops
  (`AndroidFileUpload` → `UploadSourceFile`, etc.) while they lived in an Android-only
  `androidCommonModule` came back **green** from the test that exists to object to exactly that. This
  is HR #9's *"outside the baseline's graph until then"* caveat.
- **The registry attributes them to no service** (`-` in the baseline) unless that module calls
  `service(FileService.Schema)`. So the next reshape of one of their params would have demanded a
  `TENSHU_STORAGE_FORMAT_VERSION` bump — **a wipe of every store** — rather than a service bump.

Those four are registered in `fileServiceCommonModule`, which pins them and attributes them to
`tenshu/file/service`. ⚠ *"My change reddened an unrelated test"* is often **exposure**, not
causation: that fold-in reddened a latent test bug, and the way to tell is a single-variable A/B
under the same load, not isolated reruns.

### A call can beat the re-announce after a Control restart: `unannouncedSession` on the new session (2026-10-28)

After a Control restart a caller woken by the reconnect can still hold the previous session's
announce, and the proxy's reject-if-silent gate (§6.5) refuses its call with
`tenshu/result/error/unannouncedSession`. Two layers close it:
- **Resend once (every ordering, both transports).** When an ordinary call comes back
  `unannouncedSession`, `RemoteBinaryTaskExecutor.roundTrip` re-arms the preflight, announces, and
  resends that call once; a second refusal goes to the caller. The resent refusal is not reported as
  a `CallOutcome`, so reachability engines do not flash, and an epoch counter makes callers refused by
  the same stale session share one re-announce.
- **Binder order.** `BinderTaskTransport.onServiceConnected` runs `onReconnected` after
  `isConnected = true` and before `connectionSignal.complete`. UDS keeps its order (its callback must
  stay outside `connectLock`); the resend covers it.

Pinned by `RemoteBinaryTaskExecutorReannounceTest` and
`BinderTaskTransportRebindTest.aReconnectReArmsBeforeItWakesTheWaiters`.

⚠⚠ The resend is safe only because the gate refuses **before** any op runs. `UnannouncedSessionError`
and `schemaSessionGate` both say so in their KDoc: never return this error after an op has executed.

iOS route 1 is unaffected (in-process calls; the gate applies only at the serialized entry,
`executeAsyncFromBytes`). Third-party iOS over EPC goes through `RemoteBinaryTaskExecutor`, and there
⚠ the resend is the **only** re-arm: `EpcTaskTransport` does not forward `setOnReconnected` (next
entry).



### EPC never reports a reconnect: no re-armed announce, no stream replay (2026-10-28)

`EpcTaskTransport` (iOS third-party / remote proxy) keeps `TaskTransport`'s no-op
`setOnReconnected`. So after the proxy restarts, `RemoteBinaryTaskExecutor` neither re-arms the
announce nor replays its live streams (`core-client-resubscription.md` lists EPC as "yes, latent").
The announce now recovers on the first refused call, which is resent once (entry above). The streams
do not recover: every stream the client held stays subscribed to nothing. No first-party app rides
EPC since the route-1 migration, so nothing ships with this today. It needs the Binder/UDS treatment
if one ever does.

### Removing an operation has no compatibility window — the announce gate admits the client anyway (2026-09-05)

⚠⚠ **A version range is not an operation range**, and nothing today closes the gap between them.
`AnnounceSessionImplementation` refuses a session on `announced > current` or `announced < floor`,
where the floor derives from the registered migration chains. So a service bump that ships an
**empty** migration step leaves the floor low and keeps admitting older clients — correctly, at the
*schema* level. But an operation that was **renamed or removed** in that same bump simply is not
there any more, and the admitted client calls it and gets `unsupportedOperation` for a wire tag the
proxy has never heard of.

It has happened: a file-service bump renamed `tenshu/file/system/android/fileUpload/bg` →
`sourceFileUpload/bg` with an empty migration step, a Drive build four days older than the Control
beside it stayed admitted, and **every backup upload failed for three days** on a real phone with
`SchemaSkew` silent throughout — there was no skew to report (`drive-synced-folders.md` §8c). The
shape baseline now pins such ops (*An op module outside `fileServiceCommonModule`…*) and the drain no longer swallows the error; **this
gate is still open**, and it is the one that would have caught it first.

⭐ **Option 1 declined (2026-10-28).** When the announce envelope was reshaped in place before the
Play reset (`core-schema-evolution.md` Appendix A.4), the op-id set was deliberately left out:
`core-version-compatibility.md` §4.5/§4.6 settled on O6 + a floor bump for removals (D6). Option 3 is
the operative answer until VC-1; option 2 remains the opt-in.

Options, none costed:

- Have the announce reply carry the proxy's **operation** ids, not only service versions, and
  refuse — or at least warn — when a client's compiled set names one the proxy lacks. The vector
  already crosses; this is a second, larger vector.
- Keep a removed op id registered as an explicit **tombstone** for one release, answering with a
  typed "this operation moved to X" instead of a wire-tag miss. Cheap, and it turns the failure
  into something a reader can act on.
- Treat an op rename as owing a **floor** bump, so the old client is refused and the schema-skew
  banner (SK-1…SK-4) does the telling. Correct, blunt, and the only option that needs no new
  mechanism — but it fences a device rather than degrading one surface.

⚠ Whatever the answer, the diagnostic entry point is worth writing down here:
`adb logcat -d | grep "IPC Client -> Failure"` prints the receiver, the operation id, the typed
error id **and** its message. It is the one Tenshu-internal signal that reaches logcat unaided, and
it named this in one grep after an hour of reading the object store by hand.

### Three iOS apps stopped compiling and no gate noticed — the Swift surface is unguarded (2026-09-01)

On 2026-09-01 three of nine iOS apps failed to build, each a Kotlin change whose Swift call site was
left behind: `control-ios` (`hostApprovalError` became typed, only `error` got a mapper), `drive-ios`
(`errorMessage` became `errorCopyKey`), and `home-ios` (the mock household's
`moveDeviceUp`/`removeDevice`/`addDevice` were deleted from `DevicesViewModel`; `DevicesView` stays
read-only until permit-join pairing is wired). All three are fixed; the cause is not.

⚠⚠ **Nothing in any gate compiles Swift.** `iosKotlinCheck` compiles `iosMain` *Kotlin* and stays green
while the Swift surface drifts arbitrarily far. `scripts/ios-smoke.sh` is the only thing that
assembles a fresh XCFramework and builds Swift, and it is not part of `run-gate.sh`. So a Kotlin
rename passes every gate, and the Swift break waits for whoever next runs the smoke.

⚠⚠ **Stale XCFrameworks actively hide it, including from a careful check.** A direct `xcodebuild`
reuses whatever framework is lying around and compiles happily against the *old* ABI: the same tree
passed in one checkout and failed in another, the only difference being framework freshness. A "the
Swift build passed" claim means nothing unless the framework was assembled in the same run.

⭐ **A *deliberate* Kotlin deletion is the most dangerous kind** (the `home-ios` case): the author has
no reason to look at Swift and no gate tells them to.

Options, in rough cost order: run `ios-smoke.sh --build-only` in the gate (correct, but it assembles
the XCFramework, so it is slow); run it on a schedule; or accept the drift and check before releases.
That trade is a maintainer decision, deliberately not made here.

### Re-rooted sleepers survive their admitter's removal (S1 residual)

S1 refuses any admission chain that still runs through a removed device. But P4's automatic re-root
(`AdmissionMigrationObserver.maybeMint`) mints a depth-1 `[S ← A1]` cert for **any** device that
announces a key and has a `Device` row, so a sleeper S admitted by a malicious D is flattened off D's
chain, and removing D no longer touches it. The client's subtree strip cannot catch it either: it walks
`admittedByDeviceId`, which S writes itself. S keeps minting power until removed individually.

⚖️ **Open design question, not a bug to patch.** Refusing the re-root for a device whose recorded
admitter was removed is not enough (the field is self-written) and, done as stated, would contradict
§7b.2 of `admission-revocation-names-the-capability.md`, where an A1 re-mint is the designed way back
for legitimately revoked subtree members. Options: an A1-signed lineage record at admission that S
cannot rewrite; making the re-root an owner decision rather than automatic; or surfacing "admitted by
a removed device" in Manage devices. Threat model: §7d + B1 + F5b + S1 sub-amendment, item 3.

### Signatures outside the tagged family: the entitlement record and one JWT header for every token

Neither is a live confusion today; both are the shape the B1 blocker had. `EntitlementSignature.message` (`identity-service`) is an untagged bare concatenation
`ownerId ‖ publisherId ‖ productId ‖ validFrom ‖ validUntil` under the publisher/commerce issuer key,
and every JWT this codebase mints — device-key JWTs (`Jwt.kt`) and commerce entitlement tokens
(`EntitlementToken.kt`) — uses the identical header `{"alg":"EdDSA","typ":"JWT"}`, so the token kind is
told apart only by claims and by which key verifies. Fix when next touched: tag + length-prefix the
entitlement record (`lengthPrefixedSignedMessage`), and give each token kind its own `typ`
(e.g. `tenshu-device+jwt`, `tenshu-entitlement+jwt`) checked by its decoder. Both are wire changes
(free only at a reset).

### `PhotosModelDownloadTest > photosOwnClientReadsTheDeviceIdAndTheRoster…` — FIXED: a late stream subscriber's first emission was a stale snapshot (2026-10-14 → 2026-11-05)

**Was:** red ~2–4 in 20 runs of the class on a simulator, always at the post-write consent read, with
one emission seen: `realm=false, device=true` over a stored realm yes. **A product defect, not the
test**: by `TList.flow` (`:core`), so every platform's reactive reads.

**Mechanism, captured** (a diagnostic build, prints on every stream subscribe and every
`realmSettings` emission): the realm half's chain, `realmSettings.flow()`, was already held open by
another collector in the same Photos graph, so the test's subscribe **joined** that interactor
(same callstack ⇒ same stream id) and was caught up from its replay buffer
(`core-client-resubscription.md` R3). The buffer is a log: the snapshot from when the stream *first*
subscribed — before the write, no consent row — then each row change since. `TList.flow` emitted
after **every** replayed element, so the joiner's first emission was the pre-write snapshot:
`realm=false`, a state the store had left. `combine` paired it with the device half's `true`, and
`.first { it.single().device }` took it.

**Fix:** a joiner's catch-up is handed over as **one batch** (`StreamCallback.catchUp`, called by
`StreamInteractorImpl.registerCallback` when another callback already holds the stream), and
`TList.flow` folds it into **one** emission (`Stream.kt`). Live elements stay one emission each — the
first subscriber's too — so `@defer`'s metadata-first frame (snapshot, then its patch;
`FileDetailsDeferredPreview*Test`) is untouched. Pinned by
`StreamLateSubscriberTest.aLateSubscribersFirstEmissionIsTheBuffersState` (red without: the joiner's
emissions are `[{}, {alpha}, {alpha, beta}]`). ⚠ **Narrowed, not closed:** the first emission is
current as of the interactor's buffer, not of the store — a committed write still on its way through
the element queue (`forwardStreamElementsInOrder`) reaches the joiner after, as its own emission.
**Measured on one simulator, same procedure** (the
class's test binary run directly with `simctl spawn`, one run after another): 4 in 20 before (load ~7–12);
0 in 50 after (load ~7 and ~30–50). A diagnostic build that only fixed the entry below still went red
5 in 80, which is how that one was ruled out as the cause.

**User impact before the fix:** a re-subscribed read (the Models screen re-subscribes `consents()`
after **every** consent press) briefly showed the state from when the shared stream first opened —
a switch flicking back to an old answer, in either direction. ⚠ **No consent decision read it:** the
proxy gates search and indexing on `hasInferenceConsent`, a direct store read (absent ⇒ NO). The view
model uses the screen state for the optimistic switch's roll-back, the after-download offer (which
writes only on the user's yes), and — the one write it steers — **Turn off everywhere**, which picks
the peers to clear from `otherDevices` (`readForDevice` → `deviceSettings.flow()`, the same replay):
a stale frame there could skip clearing a peer's yes. Tiny (the realm no is still written last, and
absent ⇒ NO), and closed by the same fix.

### A re-subscription is handed the ended subscription's still-queued elements — FIXED (2026-11-05 → 2026-11-06)

**Files**: `DefaultInteractorProvider.kt` (`dispatchElement`), `StreamElementReceipts.kt`,
`StreamElementForwarding.kt` (the in-process queue), `RemoteBinaryTaskExecutor.kt` (the IPC push
lane), `StreamRegistry.kt` (the proxy side). Found while diagnosing the entry above; **not** that
flake's cause.

**Mechanism.** Stream ids are callstack hashes, so the same chain subscribed again reuses the id.
Every delivery path queues the proxy's elements in front of one consumer that can lag: in process
`forwardStreamElementsInOrder`, over Binder / UDS / EPC the executor's push lane. `dispatchElement`
routed by stream id alone. So a subscription that **ended** with its snapshot still queued left it
addressed to the id, and the next subscription of the chain was handed it ahead of its own. The
result was a stale first emission, and for a row deleted in between, a row that **stays**
(`TList.flow` merges; a fresh snapshot never removes the rows it lacks).

**Fix: a receipt stamp, process-local, no hop.** Each element is stamped from one process-wide
counter (`StreamElementReceipts`) where the transport hands it over, on the sending/arrival thread,
before the queue. The consumer carries the stamp to the provider as a coroutine-context element
(`StreamElementReceipt`), so the apps' push handlers keep their signature. The provider records the
counter when it registers an interactor and drops an element received at or before that. The rule is
causal: an interactor's own elements can only follow its start, and the start is sent after
registration. So nothing crosses the wire, no stored or wire shape changes (no HR 9 bump), and
subscribing waits on nothing.

**The proxy side: an update still running when its stream ends.** `triggerUpdate` launches the
evaluation on `sessionScope`, and `EndStream` neither waits for it nor stops it. An update that
evaluated a row before `EndStream` could send it after the client had registered the next
subscription of the id. That element was produced after the registration, so it passed the stamp, and
a row deleted in between stayed. `StreamRegistry` now gives each registration a token, and an update
checks it is still the live registration after evaluating and before any send or state write. So
`EndStream` stays instant and never waits on an evaluation.

The withdrawn barrier (the provider waited for the queue to drain past an ended id) added a consumer
hop to subscribe, which turned `RealRepositoryReadTest.theLibraryWatchVouches…` red. That test is
green with this fix.

- **Tests:** `StreamResubscribeStaleBacklogTest` (`:core-common` desktopTest, in process, end to
  end: the consumer alone held via `InProcessProxy`'s `streamScope` seam) and
  `RemoteBinaryTaskExecutorPushOrderTest.aFramesReceiptIsTakenOnArrival…` (the IPC lane stamps on
  arrival, not in the consumer). Both are red with the fix reverted. The proxy side is pinned by
  `StreamRegistryTest.anUpdateStillRunningWhenItsStreamEndsSendsNothing`, red without the token.
- ⚠ **The filter fails open.** An element with no receipt is routed by id alone, and the receipt
  reaches the provider only through the coroutine context. So a push handler that adds a launch or a
  hop between the queue's consumer and `dispatchElement` loses it, and the bug comes back silently.
  `RemoteBinaryTaskExecutorPushOrderTest.theSharedPushHandlerDeliversTheReceiptToTheDispatcher`
  pins the shared `wireClientPushDispatch`. ⚠ The apps' inline handler copies (Contacts, Home, Mia,
  Photos `PlatformClientModule`s, `CoreCommonModule`, `RemoteConsumeStack`) have no such test; each
  calls `dispatchElement` directly today.
- `resubscribeAll` moves an entry's `registeredAt` forward before it re-sends the start. Whatever
  arrived before that point is the dead proxy's. The executor's lane rotation already drops those
  frames; this makes the provider's filter agree, for any transport.
- **IPC transports were affected the same way**, through the push lane's consumer lag, and are
  covered by the same stamp. Binder's `callback` is a synchronous transaction, and UDS and EPC
  deliver in order, so what the proxy had **already sent** before the `EndStream` reply has been
  received, and stamped, by the time that round trip returns. ⚠ That is a fact about the transport,
  not the producers: an update still running on the proxy can send *after* the reply. The
  registration token above is what stops that.
- ⚠⚠ **Closed 2026-11-06: a re-subscribe that overlaps the previous subscription's end.** The
  provider replaced a closed interactor at once, before its `EndStream` had reached the proxy, so two
  collectors of one chain overlapping (one ending while the other subscribes: a screen transition)
  raced that end there. **(b)** `ListStreamEndImplementation` removes the listener **by id**, so an
  end processed after the new start removed the *new* subscription's listener: its snapshot arrived,
  its updates never did — a silently frozen list. **(a)** Until the proxy processed the end, the old
  registration was the live one, so what it sent after the client's new registration passed both the
  stamp and the token, and could carry a row the new snapshot does not account for.
  **Fix, process-local, no wire change:** `StreamInteractorImpl` records its own `EndStream` round
  trip (`pendingEnd`, set with `isClosed` by the two paths that send one). A new subscription that
  replaces a closed entry whose end is still in flight waits for it before sending its start
  (`DefaultInteractorProvider.awaitPredecessorEnd`), and its entry drops every element
  (`registeredAt = Long.MAX_VALUE`) until the wait ends, then is stamped afresh. A normal subscribe
  finds no pending end and neither suspends nor gains a hop. The wait is bounded (5 s, then the start
  goes as before, with a `println` from `DefaultInteractorProvider`, and that end is forgotten so it
  costs the id one wait, not one per later subscribe), released by an end that fails, and cancellable
  (the cancelled-start path). Pending ends are kept per id (`closingEnds`), not only on the replaced
  entry, so a waiter that is cancelled (and sends its own end at once) does not release the next
  subscriber before the end it was waiting on. ⚠ A waiter whose own interactor was ended meanwhile (a
  joiner reused the not-yet-closed entry and left) must not send its start, which would land after its
  own end as an orphan listener: it subscribes afresh instead. `resubscribeAll` skips a waiter (no
  initial result yet), so it is never replayed early. ⚠ The 5 s bound runs on `Dispatchers.Default`'s
  real clock: under `runTest` the end's round trip runs on a real dispatcher, the scheduler goes idle,
  and a virtual-time timeout fired at once, so an overlap test written that way went green with the
  start ahead of the end. The
  alternative — a registration generation on `EndStream` so the proxy ignores an end that is not the
  live registration's — closes (b) too but is a wire reshape (HR 9 bump and migration), and leaves (a).
  - **Tests:** `StreamResubscribeOverlapTest` (`:core-common` desktopTest). (b) in process end to end
    (the real `ListStreamEndImplementation` blocked), and (b) and (a) over the real
    `RemoteBinaryTaskExecutor` push lane against a fake proxy that keeps one listener per id and
    removes it by id on an end. All three are red with the wait disabled; (a) is also red with only
    the `Long.MAX_VALUE` stamp reverted. Liveness, each red under its own mutation: an end that never
    returns (and does not tax the next two subscribes), a waiter ended by a joiner while it waits, a
    waiter cancelled between two overlaps, `resubscribeAll` during a wait, the bound under `runTest`;
    plus an end that fails. The reproductions are latched on the start reaching the proxy or the
    provider's `overlapWaits` counter, never on a timing window.
  - ⚠ The wait trusts what the stamp already trusts: by the time an `EndStream` round trip returns,
    everything the proxy sent before processing it has been received and stamped (in process and
    over Binder / UDS / EPC). A transport that reorders a push behind a later reply would break both.

### `PairingVmFlowSystemTest > offerFlow_…desktopLandsPaired` — seen ONCE, never reproduced, cause UNKNOWN (2026-08-28)

**Observed once**, then not again in **9 further full-gate runs** (1 in 10 across two trees; the
first three runs' 1-in-3 was a small-sample artifact).

```
Timed out waiting for the paired terminal on the desktop; last state =
PairingWizardUiState(mode=Show, …, offerUri=tenshu://offer?…, offerSas=null, offerPaired=false,
                     step=ScanQr, …)
```

The phone reached its terminal (the preceding `awaitState` passed); the **desktop never observed the
pairing** inside `awaitState`'s 20 s budget. The offerer polled `getOfferStatus` every 2 s, so ~10
polls fit that budget — this is **not** timeout pressure. Either the status never flipped for that
hash, or every poll in the window failed.

⚠ Module-level load (`:system-test-utils:test` alone) never reproduced it — it needs full-gate
contention. ⚠⚠ **And instrumentation may mask it:** `println` in the poll loop perturbs the very
timing under suspicion, so 0 reds after instrumenting is **not** proof the first was noise. A quieter
probe (counters accumulated in memory, read after the fact, no I/O in the loop) is the next attempt.

⚠⚠ **No mechanism is recorded, deliberately.** One red in ten runs with no reproduction supports no
claim about cause — including "flake".

⚠ When reading the `last state` above: the poll that swallowed every failure (`getOrNull() ?: continue`)
no longer exists — `OfferPairingMonitor` gives the offerer a deadline and surfaces failures — and the
class's teardown now cancels-and-joins every view-model scope before `system.shutdown()`. The
sighting predates both and was inside the test body, not the teardown, so it stays unexplained.

### A packaged desktop build talks to commerce on `localhost:8081` — signed-bundle pass owed

Desktop's commerce origin (minter, publisher client, token client) and its billing-mock defaults fall
back to the build-time `RelayConfig` (`generateProxyRelayConfig`, registered on `desktopMain` as on
Android and iOS), not to `http://localhost:8081`. `TENSHU_COMMERCE_URL` / `-Dtenshu.commerceUrl` stay
on top and stay honoured in release — desktop is not an app-store binary, so it does not take iOS's
`ProxyOriginSelection` debug-only guard.

⚠ **Owed: a packaged, Finder-launched bundle reaching real commerce.** That
`:control-shared-proxy:compileKotlinDesktop` resolves `RelayConfig` proves the generator reaches
`desktopMain`; no local run proves the original symptom gone. It wants a signed bundle (an ad-hoc one
is refused by the peer-credential gate — see "A locally rebuilt desktop bundle is ad-hoc signed").
⚠⚠ Do not read a `:control-desktop:run` sign-in as evidence that desktop sign-in works as shipped:
that shell exports `TENSHU_COMMERCE_URL`, a Finder launch has no environment.

⚠ The desktop **relay** origin is deliberately its own literal rather than `RelayConfig.RELAY_URL`: it
already defaults to production, and touching it would be a zero-behaviour-change edit to the most
load-bearing string in the app. `RELAY_URL` stays androidMain-only.

### The IPC surfaces authenticate a peer but never authorize it — six items from a read of both transports (2026-08-21)

**A read of both IPC paths end to end** — Android Binder (`core-aidl`, `ProxyService`,
`BinderTaskTransport`, `AndroidClientConnection`) and desktop UDS (`UnixSocketServer` /
`UnixSocketTaskTransport`, `PeerCredentialGate`, `MacPeerAttestation`, `NativeBridgeServer`) —
plus the `ProxyGateway` / `ClientRegistry` pair behind both.

The shared shape: **both platforms establish *who* a peer is and neither uses that identity to decide
*what* it may do.** `ProxyGateway.call` hands whatever id it gets to
`ClientRegistry.getExecutionContext` and executes; `AppGrant` is written and rendered but read nowhere
in the dispatch path (`resolveAppId`'s one non-test call site, `AppRegistrationTrigger`, gates
*registration*).

**Closed, with their pins** — items #1–#4 and #6:

1. *Any installed Android app could drive the proxy and forge callbacks into any client.* Closed by
   threat model **T-C-19** (`ClientCallbackGuard`: callbacks only from Control's uid) and **T-C-20**
   (`ProxyService.admittedCallerPackage`: a first-party package name **and** a first-party signer from
   `FirstPartySigning`, checked in code because Play's per-app signing keys rule out a `signature`
   permission and `knownSigner` needs API 31; the reverse check applies to Control's own identity).
   ⚠⚠ **`hasSigningCertificate`, never a comparison of current signatures** — on Android 17 a Play
   install reports Play's ML-DSA certificate as current and the pinned `deployment_cert` only as a past
   signer. ⚠ A refused first-party app drops its binding and waits 60 s (left bound it retried ~17
   calls/s). ⚠ A wrong pin fails closed: `FirstPartyCallerCoverageTest` ties the table to
   `BundledAppCatalogue`, `FirstPartySigningTest` to `docs/how-to/android-release.md`.
2. *`onUnbind` drained every session; the FD cache was shared and caller-keyed.* `onUnbind` releases
   only the ids this service served (`ProxyGateway.unregisterClient`), and
   `ParcelFileDescriptorCache` has a sender-scoped `putFrom`/`getFrom`/`removeFrom` keyed
   `<clientId>\u0000<caller key>`. ⚠ NUL is the separator because a uid-resolved package name cannot
   contain one. Pins: `ParcelFileDescriptorCacheKeyTest`,
   `ClientRegistryTest.unregisterClient_leavesEveryOtherSessionAlive`.
3. *Read loops allocated a buffer sized by an untrusted header.* `StreamFramer.MAX_FRAME_BYTES`
   (256 MiB, a sanity bound — desktop serialises byte payloads inline) is checked in all three loops,
   and `unpackTagged` validates its id length. ⚠⚠ Sending `Int.MAX_VALUE` and asserting a close passes
   **without** the fix (the `OutOfMemoryError` kills the handler thread, which also closes);
   `UnixSocketFrameLimitTest` sends an over-cap length against an injected small cap instead.
4. *Nothing set `700` on the socket directory.* `SocketDirectory` tightens it on server start and the
   client checks it before every connect, graded: another user can write → refuse; world-writable but
   sticky → warn (⚠⚠ every fixture and `TENSHU_IPC_DIR` run lives in a shared temp dir); merely
   traversable `0755` → warn, the daemon repairs it on start. ⚠
   `startingTheServer_tightensTheDirectoryItBindsIn` pins the **wiring**, not the helper.
6. *The threat model contradicted itself about the desktop gate.* A20, `desktop-ipc.md` §3/§6.2 and
   T-C-15 now agree on the enforcing posture and its carve-outs.

#### 5. `AppGrant` is a UI artefact — make it a decision

The grant model exists end to end except at the point it would matter: `SetAppGrant` writes rows,
Control renders them, and no dispatch path consults them. Until it does, "which permissions an app
holds" is a claim the system displays rather than one it enforces, on both platforms. The work is
to consult the grant in the executor's serialized-transport entry against the session's resolved
`appId`. On Android an unrecognised caller no longer gets a session, so every session this would
check belongs to a first-party app (threat model T-C-20, residual (b)).

#### Adjacent findings from the same read, recorded so they are not lost

- **Peer attestation is macOS-only.** `desktop-ipc.md` §3 describes `SO_PEERCRED` (Linux) and
  `GetNamedPipeClientProcessId` (Windows); the implementation is `MacPeerAttestation` via
  `LOCAL_PEERTOKEN`. Elsewhere every peer resolves `Unattested`, so a packaged build would reject
  everything and a dev run admits everything — `control-desktop/build.gradle.kts` sets
  `-Dtenshu.release=false` for `run`, so **the gate is meant to be fully open on every development
  machine** (but see "`:control-desktop:run` drops its own JVM flags"). Shipping is macOS-only today,
  so this is a doc-accuracy and dev-posture issue rather than a shipped hole.
- **The bridge admits a peer and then forgets who it was.** `NativeBridgeServer.serveClient` ignores
  the `PeerCredential` on the grounds that it serves only the tray agent, but the gate admits any
  identifier in `FirstPartyDesktopPeers` — so any signed first-party bundle can call `readContents` /
  `createFile` / `deleteItem`. Least-privilege gap now that the gate enforces.
- **A second connection from the same identity silently kills the first.**
  `remoteConnections[clientId]` (`UnixSocketServer`) is keyed by attested appId and
  `getExecutionContext` unregisters on identity change, so a second instance of a signed app
  displaces the first's callback socket and destroys its session; either disconnect then drops the
  shared `attestedClientIds` entry.

**What is genuinely solid, so a fix does not undo it**: the audit-token route in `PeerAuditToken`
(no PID reuse, no `SecStaticCode` TOCTOU), `identifier → teams` keying against cross-publisher
impersonation, unattested peers failing closed into a *unique anonymous* id rather than a fallback
identity, and the `NonCancellable` write+take in `UnixSocketTaskTransport.call`.

### Typed reasons: four error types use a `TString` + constants object where `TConstant` fits (2026-08-29)

**What.** `InvalidParameter`, `OpenTargetUnavailable`, `SemanticSearchUnavailable` and
`DeviceSyncStatus` all declare `string(Fields.REASON)` and pair it with an object of kebab-case
constants. ADR 0016 anchor #4 asks for user-facing failures to travel "as an **enum-ish schema**",
and the platform already has that shape — `TConstant`, as used by `SyncStrategy`, `ConflictStrategy`
and `FileCategory`. `PairingFailedError` was written the `TString` way to match its neighbours and
converted to `TConstant` in the same PR (`core-error-copy-catalogue.md` EC-4); the other four were
left alone.

**Why it matters, concretely.** Three things a `TString` reason cannot give:

1. **Exhaustiveness.** Every render site is `when (reason.get()) { … else -> generic }`. The `else`
   is mandatory, so adding a reason silently degrades to the fallback sentence instead of breaking
   the build. For `InvalidParameter.Reasons.ALREADY_CONSUMED` that is not academic — its KDoc says
   the UI must be **loud** about it (a redemption the user did not perform means someone else
   answered their invite link, threat model T-CI-01), and a client that predates the constant shows
   a generic failure instead.
2. **Copy keys cannot derive per reason.** `errorCopyKeyForId` yields one key per error *type*, so a
   `TString`-reason error needs a hand-written `when` over reason strings in **every** renderer to
   get distinct copy — which is exactly the duplication the catalogue exists to remove. Each
   `TConstant` member owns a schema id, so the key can derive from it directly.
3. **Anything can be passed.** The set is closed by convention, not by the type system.

**Two things that are NOT the reason to hold back, and one that is.**

- ⚠ *"Per-constant registration is ceremony"* is **engine debt, not an argument.**
  `TConstantRo` is already generic (one class, parameterised by schema) and a constant's "Dto" is
  the singleton itself, so nothing needs generating — what remains is one `constant(...)` line per
  member. `PairingFailure` shows the fix: expose an `entries` list and have the module registration
  walk it. A `constants(entries)` DSL helper would remove the per-member line entirely, without
  reflection (`KClass.sealedSubclasses` is JVM-only and this is KMP). **That helper is the actual
  debt item here.**
- ⚠ *"Adding a member is a schema change"* is **true, and harder-failing than a string.** A client
  that has not been rebuilt cannot parse the new id — `SchemaRegistry` throws *"No constant factory
  registered for type"* — so it fails hard where a `TString` reason would have degraded softly.
  `SchemaShapeBaseline` pins every registered constant by id (E2j-1b, `constantShapes()`), so a new
  or removed member fails `SchemaShapeBaselineEnforcementTest` at build time; ⚠ that makes the
  *change* loud, it cannot make an un-rebuilt client parse a new id.
- ⭐ **Service maturity is the real criterion, and it argues for deliberate inconsistency.** Where a
  service is settled and a new failure would ship with a new app version anyway, `TConstant` costs
  nothing and buys all three properties above — pairing is the clear case (device-to-device inside
  one user's fleet, and House Rule 9 moves clients in lockstep). Where a service is still churning,
  a `TString` reason lets the set grow without a wire event, at the price of soft degradation.
  **Do not convert the remaining four as a consistency sweep**; convert one when its service
  stabilises, and prefer `TConstant` for new error types in settled services.

**Sized.** `PairingFailedError` was ~1 h including the module registration and the exhaustive
`when`s it enabled. `InvalidParameter` is the big one — `:core`, ten call sites across five
services — and should wait for the criterion above rather than be done for tidiness. ⚠ One trap
found doing it: a companion holding an eager `entries` list deadlocks class init, because each
member's `schema` reads the companion's `Schema` back. `by lazy` is load-bearing there; see
`PairingFailure`'s KDoc.

### Six of the nine bundled apps under-declare what they dispatch — 39 operations (2026-09-03)

`BundledAppManifestSource` is what Control's **Apps → &lt;app&gt; → Declared access** screen renders,
and it has drifted from the code in six of nine bundled apps. Photos is **FIXED**; the rest are open.

| App | declares | dispatches but does not declare |
|---|---|---|
| ~~Photos~~ | ~~1~~ → 13 | ~~12~~ ✅ fixed |
| **Calendar** | 3 | **10** — event/occurrence/reminder/subscription deletes, `saveEventOverride`, `saveEventSource`, `saveReminder`, `saveSubscription`, `setReminderState`, `refreshSubscriptions` |
| **Drive** | 6 | **10** — `shareLink`, `revokeShareLink`, `evictFileBytes`, `hasLocalData`, `listPlacements`, `listSharedFolder`, `mountSharedRealm`, `readSharedFile`, `replaceFile`, `shareFolderIntoRealm` |
| **Timeline** | 10 | **5** — `createFolder`, `hasLocalData`, `recordPlaceMerge`, `recordPlaceNetworkFingerprint`, `recordPlaceRole` |
| **Mia** | 1 | **1** — `confirmMiaAction` |
| **Fit** | 6 | **1** — `recordHealthSample` |
| Maps / Home / Contacts | 3 / 1 / 2 | 0 ✅ |

⚠⚠ **This is a disclosure defect, not a functional one, and the distinction was verified rather
than assumed.** Control's own screen says *"Declared by the app. Tenshu does not currently restrict
what an installed app can do."* `AppPermission` appears in exactly two files — its declaration and
this manifest — and nothing reads it as a gate. On a device, Control's Recent Events shows *"Added
folder: /Trash"* from a `createFolder` Photos never declared: the call went through.

⚠⚠ **`services` on the Apps screen is derived from these entries**, so a missing permission does not
merely drop a line — it can drop a whole capability. Photos' screen showed only `file/service` while
the app also drove `photo/service` (albums, favourites, trash) and `inference/service` (semantic
search).

⚠⚠ **Nothing was guarding this and the existing test shape cannot.**
`BundledAppTablesTest.fitManifest_declaresOnlyTheOpsFitActuallyDispatches` — whose name promises a
dispatch cross-check — compares the manifest against a **hardcoded set**. It catches a permission
added without a caller and nothing else, and only for Fit; the other eight apps
had no case at all. Fit's test passes today while Fit under-declares `recordHealthSample`.

⚠ Under-declaration has no automated guard, and a pinning test cannot be one: knowing what an app
dispatches means reading its call sites. The sweep was done with a script that greps each app's
modules for `.<opLeaf>(` against every id declared in a file containing `= operation(`, then
confirms each hit at the call site by hand. It over-reports (a same-named local helper) and
under-reports (an op reached through a differently-named client wrapper, as Home reaches
`issueDeviceCommand` via `setDevicePower`) — so the numbers above are *confirmed* hits, not the
script's raw output.

⚠ **Drive has no destructive-gesture test**, found by accident: a mutant that flipped
`deleteFile` from `RequiresExplicitConfirmation` to `RequiresUserGesture` survived, because the
mutation string matched Drive's entry before Photos' and no test covers Drive's gestures.
`photosDestructiveOpsRequireExplicitConfirmation` is the shape the others want.

**Next:** Calendar and Drive, then Timeline, Mia and Fit. Each entry needs a `humanLabel` — the
sentence a user reads when deciding what an app may do — plus a `capabilityClass` and
`gesture`; the Photos manifest is the worked example of the house style (writes `Picker` +
`RequiresUserGesture`, reads `Ambient` + `None`, destructive `RequiresExplicitConfirmation`).

### A failed child move leaves a partially-moved folder that cannot be retried (2026-09-16)

**Files**: `file-service-proxy/src/commonMain/kotlin/org/tenshu/file/impl/MoveFileImplementation.kt`
(`validateMove`'s duplicate-target guard, the folder branch's child cascade, the destination
`saveData` and the `DataIdMigrator.migrate` that retires the source).

A move writes the **target row before it removes the source**, and a folder move does that
recursively, committing as it goes. The cascade's comment says a retry is safe — *"The caller sees the
Failure and a retry folds: every re-move is content-addressed metadata (§8.5)"*.

⚠⚠ **It is not safe for a folder, and not for a single file either.** `saveData(fileEntity)` creates
the target, and `DataIdMigrator.migrate` — which deletes the source — runs **after** it, with
`rekeyHistory` and the history-row save in between. A throw anywhere in that window leaves **both
paths present**, so the retry hits the duplicate-target precondition and answers `AlreadyExistsError`.

⚠⚠ **The abort path is an exception, not the `Failure` the comment describes.** `moveFile` never
*produces* a `TResult.Failure`: the preconditions `throw TenshuException`, the request helper converts
a nested `Failure` into a throw, and the only value returned is `TResult.Success`. So the
`if (childResult is TResult.Failure)` guard has **no base producer**, and the partial tree is produced
by an exception unwinding *after* the destination was saved. ⚠ That decides where the regression test
goes: a test that makes a child return `Failure` exercises a path production never takes.

⚠ **The folder case is the same defect, wider.** After a partial cascade the destination folder and
some children exist at both paths — a tree split across two parents and an operation that refuses to
run again. ⚠⚠ **And not every straggler can be moved by hand:** a child that threw after
`removeFromArray` but before its destination save is listed under *neither* parent.

**What keeps it out of ordinary use today, and what does not.** Every `FileDatabase.moveFile`
(including `AndroidFileDatabaseImpl`) returns early on an absent source, before deleting an existing
destination — so moving a never-downloaded file no longer throws mid-move, and a retry cannot delete
the only copy. The CR-2 B6 D3 local-bytes refusal (`MoveNeedsLocalBytesError`) and the incomplete-
subtree refusal run **once, before the first mutation**, as whole-subtree preflights, so no
precondition refuses one child mid-cascade. ⚠ Both *avoid* the window; neither closes the
storage-failure window that remains, at either granularity.

⇒ The general fix is to make a re-move **resumable**, and the marker has to be an explicit **move
intent**, not an identity comparison. ⚠⚠ *"Accept a target whose identity matches the source"* cannot
be written: `file-content-addressing.md` §3.3.1 kept **path** identity, so source and target carry
different `DataId`s; `fileId` is frozen and slated for removal; `contentId` is deliberately *shared*
by copies, so it would mistake a copy for a partial move; and a folder has no content identity.

⇒ Persist a move intent — `(sourcePath, targetPath, opId)` — **before** the first mutation, and let
**both** preconditions consult it. The constraints any design must meet:

- ⚠⚠ **Bound to the row it created, not just to the two paths.** Two concurrent moves onto one target
  can both pass the absence check; recovery that sees *"target present, source present"* would otherwise
  retire its source in favour of **someone else's row**. The op records its `opId` against the target
  and retires only a target still carrying that claim — ⚠⚠ **atomically with the retirement** (a
  conditional write, or one transaction): stamp-then-read is a TOCTOU.
- ⚠⚠ **Reserved before anything is detached.** A conditional retire stops the loser from deleting its
  source, but that source is already detached from its parent — the invisible state again. The intent
  claims `(targetPath, opId)` with a conditional create, and a move that loses fails before its first
  mutation.
- ⚠⚠ **The source is claimed too.** Two moves of the *same* source to *different* targets both pass
  every target-side check and both destinations survive — one file becomes two. The intent claims
  `(sourcePath, opId)` conditionally. Content identity cannot stand in for the stamp: copies share a
  `contentId` (`file-content-addressing.md` §3.2).
- ⚠⚠ **Acquired by every writer to a path, not merely checked.** `UploadFileImplementation`,
  `CreateFolderImplementation` and `DeleteFileImplementation` consult no reservation; checking is the
  same TOCTOU one layer up. Each writer **holds** the claim across its own write and fails before
  mutating if it cannot acquire it.
- ⚠⚠ **A folder move claims the source subtree as a path prefix, up front.** Claiming enumerated
  descendants misses `/source/new.txt` created after preflight (which `ParentFilesRelationMaintainer`
  then appends to `/source`), and claiming each child's target just before it moves is too late. ⚠⚠
  **The whole set is acquired as one act, in a canonical (sorted) order** — individually taken claims
  deadlock on `/a → /b/new-a` racing `/b → /a/new-b`, and a partial set is rolled back by the same
  recovery that rolls back a partial move.
- ⚠⚠ **Not a field on the `File` row.** `File` is a service-owned positional composite, so a new
  stored-and-synced field is a file-service version bump plus a `SchemaMigration` — a recovery fix that
  is itself store- and peer-breaking. The claim lives in an **operation-bound local sidecar**.
- ⚠⚠ **A lock on ONE device only.** Two devices can each reserve one path locally and both retire
  their sources; `SyncProcessor.resolveConflictAndSave` picks a winner without consulting any sidecar.
  That cross-device collision is a conflict-resolution question in the family of
  `file-content-addressing.md` §B, out of scope here — do not sell the fix as distributed mutual
  exclusion.

⚠⚠ **The first mutation is earlier than either save:** `removeFromArray` detaches the source from its
old parent's `Folder.FILES` *before* the destination is written. So there are three post-throw states,
and the third is invisible to a retry of the parent operation:

| state after the throw | today's retry | with the intent |
|---|---|---|
| target present, source present (throw before `retire`) | `AlreadyExistsError` at the duplicate-target guard | resumable: finish the move |
| target present, source **absent** (throw inside `retire`, after `deleteData(oldDataId)`, before the tombstone emit + marker clear) | *"Source file not found"* at the source lookup, which runs **first** | recognised as already-moved: emit the missing tombstone, clear the marker, done |
| **target absent, source detached** (throw after `removeFromArray`, before the destination save) | ⚠⚠ **nothing to retry** — the parent's next cascade no longer sees the child, and a top-level retry can retire the source folder around a child it cannot see | the intent names the child, so recovery re-attaches or re-moves it |

⚠⚠ **A fourth state decides whether replay is safe:** the plaintext rename succeeds and `saveData` then
throws. Rows look like the third state, but **the only local bytes are now at the target path**.
Replay is safe only because `moveFile` no-ops on an absent source; recovery must not treat "no bytes
at the source" as "nothing was moved". Recovery is driven by the record, not by re-enumeration.

⭐ **Half of the second state is already solved — do not duplicate it.** `DataIdMigrator.migrate`
persists a **`RekeyMigrationMarker`** before `retire`, and `initSyncService` runs
`resumeIncompleteMigrations()` on start (`DataIdMigratorJournalTest`). What does not recover is the
**same-session retry**. Two homes for the intent, and they do not cost the same:

| option | owes | blast radius |
|---|---|---|
| **a new local-only sidecar** | a `TENSHU_STORAGE_FORMAT_VERSION` bump **plus a registered core migration step** — ⚠⚠ omitting the step once made the derived core floor equal the current version and **wiped every store** | ⭐ no `ServiceSchema` move, so **nothing is fenced** |
| **extending `RekeyMigrationMarker`** | the same, **plus** a `SyncService` version bump (it reshapes a service-owned stored row) | ⚠⚠ the epoch writer **pull-fences every not-yet-updated device**, realm-wide |

⇒ prefer the sidecar unless one journal genuinely outweighs fencing every peer.

⚠⚠ **Extending that marker naively is a data-loss bug.** `resumeIncompleteMigrations()` treats a
surviving marker as proof the replacement is durable and calls `retire()`. A marker written **before**
the target exists would delete the source with no replacement. ⇒ the record needs two states —
*intent* and *replacement durable* (the only one `retire()` may run from) — or recovery must verify the
destination itself. ⚠⚠ **And two states cannot tell a throw just before the move-history save from one
just after it:** replaying mints a second `MovedFileModification`/`RenamedFileModification`, because
its key includes a fresh clock timestamp. ⭐ Key those effects on the move's `opId` so replay is
idempotent by construction, rather than growing a state per side effect.

## Control UI

### Start-at-login: launchd's disabled flag outlives the plist — the rules the fix left

On the LaunchAgent path (`DesktopStartAtLoginManager` without an SMAppService login item: dev
and Developer ID builds), autostart is decided by launchd's per-user *disabled* database (`launchctl print-disabled
gui/$(id -u) | grep tenshu`), not by the plist: `unload -w` and System Settings → Login Items both
write the flag, and a disabled label never starts however correct the plist looks.
`DesktopStartAtLoginManager.enable()` clears it with `LaunchAgentManager.enable()`, and the toggle
renders `isAutostartEnabled()` (plist exists **and** not disabled); its KDoc and `parseDisabledState`'s
tests carry the launchd facts (enable is not load; match the **quoted** label).

⚠ The **tray** label (`app.tenshu.control.tray`) is deliberately left disabled when start-at-login
is turned back on: the daemon spawns the tray itself, and resurrecting that legacy agent makes
`Main.ensureTrayRunning()` skip forever (`if (forTray().isInstalled()) return`).

⚠ Control deliberately does not undo a disable set *outside* the app (System Settings):
re-enabling silently would override the user's choice. It needs a visible prompt if ever wanted.

Owed: the launchd round-trip has no automated check — `install`/`uninstall` are on-device only.

### Desktop Control has no single-instance guard other than `ControlInstanceGuard` — never move it below the store or the sockets

`ControlInstanceGuard` (`control-desktop/.../ControlInstanceGuard.kt`) runs first in `main()`, before
the tray, Koin, the store and the sockets. It takes `control.instance.lock` (an exclusive
`FileChannel.tryLock` at the data root, held for the life of the process) and probes `ipc.sock` for a
pre-guard Control. A refused copy asks the running one to show its window over `bridge.sock` and
exits 0. Trigger and cross-channel rules: `desktop-macos-distribution.md` §2.11.

⚠⚠ **The guard is the only thing that keeps a second writer out.** `UnixSocketServer.start` and
`NativeBridgeServer` still delete a live socket file and rebind. `objects.db.lock` still only
*reports* contention. `local-store.properties` is still rewritten whole by either process. Never
move the guard below anything that opens one of them. `FileLockModelStoreOwnership` stays as
defence in depth for the model store.

---

### "Disconnect from Tenshu Storage" is unreachable for anyone without a local passphrase (2026-08-07)

The dead end itself is closed: `DisconnectConfirmViewModel` reads `UnlockStatus.hasPassphrase` and asks
for a passphrase only where one exists (failing closed to asking when the status cannot be read);
`DisconnectConfirmViewModelTest` pins it. What is still open:

- **Biometric confirmation is not wired.** The proposal wanted *confirm-by-biometric*, and
  `BiometricUnlockDelegate` exists on desktop and Android. ⚠ On desktop it is conditional — the Touch ID
  keychain helper only embeds under `-Ptenshu.withKeychainHelper` (`macos-signing.md` §3b), so
  `isAvailable()` is false on an ordinary build. Adding it is an improvement on top of the
  no-factor path, never a substitute for it.
- **`BiometricUnlockDelegate`'s class KDoc contradicts its `isAvailable()` KDoc.** The class says
  "Desktop has no platform biometric primitive today, so its impl returns `isAvailable=false`"; the
  method says the desktop impl shells out to the Swift keychain helper. One of the two is out of date.
- **There is no *Lock now* on desktop or Android.** `SyncRepository.lock()`'s KDoc says it "Backs a
  user-initiated *Lock now*", but the only caller is `control-ios/Sources/SettingsView.swift`. The
  Compose UI has no lock affordance, so re-sealing a realm on demand is not possible on those
  platforms — which also makes the locked-realm code paths hard to exercise by hand.

---

## Build & Versioning

### `:control-desktop:run` drops its own JVM flags — developer mode is always off on a dev run (2026-09-09)

⚠ **Fixed for `:control-desktop` (2026-11-10):** the dev flags now go in through a `doFirst` on the run
task, and `-Dskiko.renderApi=METAL` is macOS-only (it never reached the JVM before, and is no renderer
on Windows). Found when Control's strict gate rejected the standalone Windows tray agent
(`desktop-windows-port.md` §3.9) on a dev run. ⚠ **Still affected: the other nine `*-desktop` apps**,
which set `-Dtenshu.release=false` (and an unconditional METAL) the same broken way — so a dev-run
app's own `ControlServerCheck` still never gets developer mode. Same fix, one app at a time, each
checked against its dev-run pairing.

`control-desktop/build.gradle.kts` sets `-Dskiko.renderApi=METAL` and `-Dtenshu.release=false` only in
a `tasks.withType<JavaExec> { if (name == "run" …) }` block, and they **never reach the JVM** that
`./gradlew :control-desktop:run` starts (`ps -ww -o command=`). `PEER_GATE_JVM_ARGS` and
`-Dtenshu.qr.impl` (given `-Ptenshu.qr.impl`) survive only because
`compose.desktop.application { jvmArgs += … }` declares them too. Mechanism (CMP 1.12.1):
`ConfigureJvmApplicationKt.configureRunTask` calls `JavaExec.setJvmArgs(app.jvmArgs)`, which
**replaces** the list. That it runs after the script's block is inferred from the `ps` output.

⚠⚠ So `Main.kt`'s `developerMode = System.getProperty("tenshu.release") == "false"` is always
`false` under `:run`. The dev-seed entitlement minter and the developer bypass of the strict
peer-credential policy are both off exactly when they are meant to be on. That is a plausible cause of
the "endless spinner, no tray" dev-run symptom. The METAL flag (a Skiko `UnsatisfiedLinkError` fix on
macOS) is missing too.

⚠ **Fix with care.** The obvious move, onto `compose.desktop.application.jvmArgs`, also feeds the
**packaged** app, which must never carry `tenshu.release=false`. Add the flags to the `run` task
*after* Compose configures it (for example in a `doFirst` on that task), and verify with `ps`. Making it apply
switches developer mode **on** for dev runs, which changes the IPC peer gate's behaviour, so that
needs its own check.

### Every iOS app links every app's Kotlin — one umbrella framework for ten apps (2026-10-14) — uploads owed

**Files**: each `tenshu-sdk-ios-<app>/build.gradle.kts` (its `export(...)` list), the root build's
"iOS app frameworks" block, every `*-ios/project.yml`, `scripts/ios-archive.sh` (the sensitive-framework
allowlist).

**Proposal**: [`ios-per-app-frameworks.md`](proposals/ios-per-app-frameworks.md) — option A, one
framework per app, built through P5: every app links its own `:tenshu-sdk-ios-<app>`, the umbrella
`:tenshu-sdk-ios` is gone, no app carries a generic *"this app does not use …"* purpose string, and
`ios-archive.sh` fails an archive whose sensitive frameworks leave its allowlist. In debug builds only
Fit (HealthKit, CoreLocation, CoreBluetooth, CoreMotion) and Timeline (CoreLocation) load any of the
five sensitive frameworks; Drive keeps Photos and AVFoundation.

⚠ **Owed**: one TestFlight upload each of Control and Drive with no ITMS-90683 (the proof of done, D7),
and a first upload of each of the other eight. ⚠ Each app's privacy manifest was audited against the
binary it linked at the time; Photos' predates its own framework and may over-declare (`UserDefaults`
for Timeline's controls, boot time for Fit's clock) — not re-audited.

### The iOS release build links a framework no test has run (2026-10-13)

**Files**: `scripts/ios-build-sdk.sh`, `ios-release/release.yml` (`AppFramework`).

`scripts/ios-archive.sh` links the **release** Kotlin framework, built for the first time on
2026-10-13. Every gate in this repo — `iosTestCheck`, `ios-smoke.sh`, CI — builds and runs the
debug one. So the optimised code that would reach a TestFlight tester is compiled by nothing but
the archive itself, and run by nothing at all.

- ⚠ **Nothing compiles it between archives.** A change that breaks only the release link (the
  whole-program optimiser has its own failure modes) is found on release day.
- ⚠ **Its heap is a measured guess.** Both slices at `-Xmx5g` ran out of memory; one slice at
  `-Xmx10g` links in about five minutes. Nobody has looked for the real floor, and the framework
  grows.
- The everyday project's Release configuration links the release framework since each archivable
  app moved to its own (`AppFramework`, `ios-per-app-frameworks.md` P2/P3); the second archive spec
  that used to be the only release path is gone.

The work: a CI job that runs `scripts/ios-build-sdk.sh` with `TENSHU_SDK_VARIANT=release`, and a
run of the simulator test suite against the release binary.

### Installing an older build over a newer store WIPES the whole store, silently (2026-09-27)

**File**: `core-common/.../schema/migration/StoreSchemaMigrator.kt` (the `from > current` branch).

When any one service's recorded version is higher than the running build declares, the migrator
prints *"store written by newer code; wiping"* and calls `wipeAndStampCurrent()`. Nothing is shown
to the user, and nothing asks.

**Observed on the maintainer's OnePlus, 2026-09-27.** IN-6 F6a moved the inference service
to v2. A master build without F6a was then installed over it, the store recorded inference v2 against
a declared v1, and the **entire** local store was wiped because of one service's slot. Realm rows came
back from the relay (1,344 files), so it looked harmless. It was not: everything device-local was
lost, including face templates (`faces.log` restarted from empty), and anything not yet on the relay
would have gone with it.

⚠⚠ **Why it keeps happening:** the normal development loop is to install whatever branch is being
tested, and branches carry different service versions. Any session that bumps a service makes every
older build a store-wiper for that phone. The iOS twin is recorded in memory as "one service slot
behind wipes the whole store".

**Options, not yet decided:**
- **Refuse, don't wipe.** An older build that finds a newer store stops at a clear screen ("This
  data was written by a newer version of Tenshu — update to continue") and leaves the store alone.
  The same posture the relay's 426 gate and the announce fence already take.
- **Wipe only what the old code cannot read:** that service's rows, not the whole store.
- **At minimum, a log line that survives:** today it is a `println` that nothing collects.

⚠ Refusing is the safer default. A wipe is irreversible and a refusal is not, and what a user loses
in a wipe (unsynced content, device-local derived data) is exactly what the relay cannot give back.


### A locally rebuilt desktop bundle is ad-hoc signed, which silently kills the menu-bar tray (2026-08-01)

**Files**: `gradle/desktop-app-signing.gradle.kts` (`signDesktopApp`, `packageDesktopApp`),
`control-desktop/build.gradle.kts` (`packageControlApp`), `control-desktop/src/jvmMain/.../Main.kt`
(`ensureTrayRunning`).

**Symptom**: an ad-hoc-signed bundle is refused by the desktop IPC peer-credential gate
(`desktop-ipc-client-identity.md` P0) on every connection. For the tray that reads as "the tray is
gone"; for any other app, because an unattested peer resolves to no app and registration is a
deliberate no-op (D3), as **an endless loading spinner plus a missing tray entry**. The signature never
enters the symptom.

```
[ipc] peer-credential gate: rejected — peer claimed 'tray-agent' but is not signed by FAYT82GWD7
```

⚠⚠ **Nothing about the symptom points at signing.** The rejection goes to Control's **stdout**, which
is discarded when the app is launched from Finder or `open -a` — invisible unless you run
`Contents/MacOS/TenshuControl` directly.

**What is in place**: a shared `packageDesktopApp` in `gradle/desktop-app-signing.gradle.kts`, registered
in every `*-desktop` module, chains `embed*` → `signDesktopApp` → a Team-ID verdict, and **hard-fails**
when signing is unconfigured; `packageControlApp` is a thin layer over it (tray + QR assertions,
keychain opt-in). ⚠ The asymmetry that makes this safe: `signDesktopApp` alone still only *warns* and
self-skips, so a plain `createDistributable` is unaffected. ⚠ The unconfigured path cannot be exercised
by `-P` (blank values fall through to `local.properties`), so testing it means removing the keys.

⚠ **`./gradlew :<app>-desktop:run` can never be attested**, structurally: the process is the Gradle
JDK's `java`, signed `net.java.openjdk.java` / team `JCDTMS22B4`. Any desktop app that needs app
identity (registration, grants, tray) must be run as the packaged, signed `.app`. A dev-mode escape
hatch for `run` is unbuilt (the accept-list is compiled in *on purpose*, so it cannot be a property).

⚠ **Do not "fix" a missing tray by installing the tray LaunchAgent** (`app.tenshu.control.tray`). That
agent is legacy — the running daemon spawns the tray — and Settings' *Start at login* actively
**uninstalls** it; while its plist exists `ensureTrayRunning()` skips permanently
(`if (forTray().isInstalled()) return`).

⚠ **Adjacent trap**: running `signDesktopApp` *on its own* lets `createDistributable` regenerate the
bundle **without** `Contents/Helpers/` (the `embed*` tasks run only under the umbrella), and it still
reports `Signed … → TeamIdentifier=…` — just `(0 nested)` instead of `(3 nested)`. Always build via
`packageControlApp`.

**Still open, cheapest first:**
1. **Derive the identity from the team id** — one `security find-identity -v -p codesigning` lookup
   matching `OU=<teamId>` — so `tenshu.teamId` alone suffices. Today `signDesktopApp` needs both
   `tenshu.teamId` *and* `tenshu.signingIdentity`, though the second is redundant.
2. **Supervise the tray.** `ensureTrayRunning()` runs once at startup (it replaces a dead or misplaced
   tray then, via `TrayLiveness`), so a tray that exits later leaves the menu bar empty until Control
   restarts. Re-spawn on child exit (`Process.onExit()`), or at least retry once.
3. **Route the gate rejection somewhere that survives a Finder launch** rather than `println`
   (`core-logging-facade.md`).

### The JS and iOS lanes had no local gate — `run-gate.sh` runs both, and what that still does not cover

`scripts/run-gate.sh` runs `desktopTest jvmTest test testAndroidHostTest`, then the repo-wide
`compileTestKotlinJs` and `compileTestKotlinIosSimulatorArm64`, both with `--continue`. Without the
lanes a green gate never implied a green CI: CI's `./gradlew check` builds every target of every
module.

⚠⚠ **`iosKotlinCheck` is not the lane check.** It depends on the `:tenshu-sdk-ios-*` modules'
`compileKotlinIosSimulatorArm64` plus `:ui-core`, so it compiles whatever the SDK graphs happen to
reach. Its KDoc says so — *"the app frameworks are a heuristic, not a proof"* — and **a module with iOS
targets that no iOS binary links is compiled nowhere** by it. `:photos-shared-logic` fell through that
gap with a JVM-only `toSortedMap` in `commonMain`.

⚠⚠ **The recurring defect class: a JVM-only stdlib extension called from `commonMain`**
(`MutableMap.putIfAbsent`, `toSortedMap`), which compiles for JVM/Android and nothing else. Other
lane-only breaks seen: a class implementing a function type (illegal on JS), test names containing a
comma inside backticks (rejected by K/N), and no JS time-zone database.

⚠⚠ **`--continue` is load-bearing.** Gradle stops at the first failing task, so each break hides the
next; five once read as one.

⚠ **`@js-joda/timezone` trap**: declaring the npm dependency is not enough — nothing `require`s it, so
webpack never bundles it. It needs a source file that imports the module, and that file's
apparently-unused top-level property is what keeps the import alive.

⚠⚠ **UP-TO-DATE is not a lie for the lanes.** An up-to-date *test* task certifies nothing; an
up-to-date *compile* task has a successful output for exactly these inputs. So the lanes deliberately
have no executed-task floor — do not add one by analogy.

⚠ **Once, outside the run loop.** `scripts/run-gate.sh 3` runs three test passes but compiles the lanes
once.

⚠⚠ **Off macOS the iOS lane prints "NOT VERIFIED" instead of passing.** K/N will not cross-compile an
Apple target, so every task would be SKIPPED under a `BUILD SUCCESSFUL`. It does not fail the gate
(that would make it unrunnable on Linux agents). ⚠ A run from a Linux container therefore verifies the
JS lane and not the iOS one; say that rather than citing "the gate".

⚠ `TENSHU_GATE_SKIP_LANES=1` exists for a tests-only pass and prints what it did not verify. Still
uncovered by anything here: **Swift** (`scripts/ios-smoke.sh --build-only <app>`) and the iOS *test*
suite (`iosTestCheck`).

### `iosTestCheck` — no HTTPS works inside the K/N simulator test harness

⚠⚠ **No HTTPS works at all inside the K/N simulator test harness.** `apple.com` and `letsencrypt.org`
fail with `NSURLErrorDomain -1202`, the tiles host with `-1200`/`errSSLFatalAlert` (`-9802`), while host
`curl` reaches all three. ⚠ Root cause unknown and **not** a stale trust store — that would be
`-9813 errSSLUnknownRootCert`. **Any iOS simulator test that needs the network is unrunnable until this
is understood**, which constrains what may be asserted there.

⭐ **Telling unrelated reds apart in `iosTestCheck`** (the method that separated three causes once):
run the same task on a clean checkout at the branch's base to clear your diff; run a single test alone
to separate a deterministic defect from cross-test interference; then instrument the *swallowed*
throwable, because a catch-all error code hides the one fact that names the bug.

### CI blocked by GitHub Actions billing — ⚠⚠⚠ INTERMITTENT since 2026-09-01, not simply dark

⚠ **Scope (2026-11-09, maintainer):** this is about the **internal** repository only. The public repository,
where PRs are opened, runs on GitHub's free public-repo runners — Windows included, so the Windows lane
(`.github/workflows/windows.yml`, `desktop-windows-port.md` W1) does run there.

⚠⚠ **Originally: no CI lane ran between 2026-06-30 and 2026-08-31.** `CI` (`android.yml`),
`iOS` (`ios.yml`) and `Web CI` (`web.yml`) all failed within ~10 s carrying the identical GitHub
annotation:

> The job was not started because recent account payments have failed or your spending limit
> needs to be increased.

⚠⚠⚠ **Amended 2026-09-01 — it now flickers, and a cached answer is worse than no answer.** The
lanes ran for about four hours (12:39 → 15:07, genuine 5–15 minute jobs that found real breaks —
see the JS and iOS lanes entry above), and were **billing-dark again by 16:26**, all three workflows back to 3–5 s
with zero steps. The plausible mechanism is the spending limit: the lanes return, burn the
allowance on real runs, and stop.

⭐ **Read the DURATION on the run in front of you, every time.** It is the cheapest discriminator
and it works in both directions: a 2–5 s "failure" with an empty `.jobs[].steps` never started; a
5–15 minute one is a real compile or test run whose result is yours to own. Never carry a
conclusion about CI liveness across a session — on 2026-09-01 a PR body justified skipping the
local gate on the grounds that "CI's `check` is a superset and it is alive", and the lanes went
dark twenty minutes later, leaving that PR with no verification at all.

**The trap: a red tick currently carries no information.** Merging a red PR is routine right
now, which is precisely what makes a *genuine* regression dangerous — it looks identical to the
billing block from the PR page. Read the annotation before concluding anything:

```bash
gh run view <run-id> --log-failed
gh api repos/B3nedikt/Drive/check-runs/<job-id>/annotations -q '.[0].message'
```

The billing message means the job **never started**. Anything else is real, and is yours.

**So the gates are local.** On anything touching `commonMain`, run **`scripts/run-gate.sh`** —
not a hand-typed task list. ⚠⚠ The obvious hand-typed list
(`./gradlew desktopTest jvmTest test` + `./gradlew iosSimulatorArm64Test`) under-runs in both halves,
so it goes green:

- **It omits `testAndroidHostTest`**, which is *not reachable from `test`*. Omitting it hid
  **219 test classes** and let a widened AIDL break `master`. `scripts/run-gate.sh` is
  `desktopTest jvmTest test testAndroidHostTest` plus the thing a hand-typed list cannot do: it
  clears results and asserts tasks actually ran, because ⚠⚠ **re-running the gate reports
  BUILD SUCCESSFUL having executed ZERO test tasks** (all UP-TO-DATE).
- **`iosSimulatorArm64Test` is one target, not the iOS gate.** `AGENTS.md` prescribes
  `./gradlew iosKotlinCheck` (the `iosMain` the SDK graphs reach — see the lanes entry above) and `./gradlew iosTestCheck`, with
  `scripts/ios-smoke.sh --build-only <app>` as the only thing in this repo that compiles **Swift**.
  ⚠ All three are macOS-only and now *refuse* elsewhere rather than lying — a Linux run used to
  report BUILD SUCCESSFUL having compiled nothing.

⚠⚠ **And `./gradlew test` on its own is not a gate at all** — it matches **zero**
KMP test tasks (see `AGENTS.md` § Build & test), so it goes green while every `jvmTest` and
`desktopTest` in the repo is unrun. That is ~1246 tasks instead of ~1549. Two concrete things this has already
cost, both latent on `master` behind the dark lanes:

- 12 `:fit-shared-data` iOS tests sat red 2026-07-25 → 07-28 (the K/N checked-downcast NPE) behind a gate that was green-by-absence.
- A 3-arg `PairingUri.offerUri` call in `PairingWizardViewModelTest` against a 5-arg signature,
  plus a missing `FakeFilesRepository.openStreamingContent` override in `:tenshu-sdk-ios` —
  both found only when someone compiled the targets by hand
  ([`sync-pairing-inverted-direction.md`](proposals/sync-pairing-inverted-direction.md)).

**Restoring billing retires this entry and unblocks two others** — marking `ios.yml` as a
required status check in branch protection (`ios.yml` header), and the proposed `androidMain`
compile gate below. Every other guardrail on this page in the "verify locally" shape exists
only because this is broken, which makes it the highest-leverage fix here.

### Adding WorkManager to `:fit-android` breaks that app's resource link — cause unknown (2026-08-15)

**Symptom**: one line — `implementation(libs.androidx.work.runtime)` in `fit-android/build.gradle.kts`
— and `:fit-android:processDebugResources` fails:

```
ERROR: …/work-runtime-2.11.2/AndroidManifest.xml:39: AAPT: error: resource
  bool/enable_system_job_service_default (aka app.tenshu.fit.android:bool/…) not found.
ERROR: …/play-services-basement-18.4.0/AndroidManifest.xml:6: AAPT: error: resource
  integer/google_play_services_version (aka app.tenshu.fit.android:integer/…) not found.
```

Remove the line and the app builds. **`:timeline-android` declares the same dependency, from the
same version catalog alias, and builds fine** — its capture watchdog has used it since 2026-07.

⚠ **The second error is the interesting one.** `play-services-basement` is already on Fit's graph
(MapLibre / Health Connect) and links fine without the WorkManager line; it only fails to resolve
*its own* manifest resource once WorkManager is added. So this reads less like "WorkManager's AAR is
broken" and more like the app's whole library-resource merge falling over — which would make the
missing bools a symptom rather than the cause. Not diagnosed.

**Workaround in place**: `RecorderWatchdog` (`fit-app.md` §13.5e) uses `AlarmManager` +
a `BroadcastReceiver` instead. That is arguably the better fit anyway — the recorder needs a
~2-minute cadence and `PeriodicWorkRequest` has a 15-minute floor — so nothing is currently blocked.

**Why it is still debt**: the next person who reaches for WorkManager in Fit (a retry queue for the
§9.13 outbox, a scheduled import) will hit the same wall and have to rediscover it. Leads not yet
tried: this module's unusual `configurations.all { resolutionStrategy { force(material3…) } }`
*inside* its `android {}` block; its `<uses-permission … tools:node="remove">` manifest surgery;
and a plain `./gradlew :fit-android:processDebugResources --info` diff against `:timeline-android`.
⚠ Sized as an afternoon, not a rabbit hole — but only if someone actually needs WorkManager there.

### No lint layer exists — Detekt is deferred, and four rules are queued behind it (2026-08-10)

**Files**: none. That is the entry.

⚠⚠ **Detekt is not in this project.** No plugin, no `detekt.yml`, no catalog entry — verified
against `origin/master`'s committed tree, not a working copy. Several docs speak of "the Detekt
rule" as though a module exists to hang it on; it does not, and
`core-logging-facade.md` §Lint-backstop says so plainly — its rule *"rides the same deferred-Detekt
slot as the i18n [one] … when the Detekt module lands."*

⚠ A related attribution to correct, because it has been repeated: the "Detekt +
`eslint-plugin-i18next` … deferred at n=1" line lived in **CLAUDE.md's summary row** for the
localisation track. **ADR 0016 does not mention Detekt at all.** That row was removed 2026-08-11,
so this entry is now the only home for the deferral — including the `eslint-plugin-i18next` half,
which was never recorded anywhere else.

**What is queued behind the missing module** — four rules, none of them the reason to build it on
its own, all of them cheap once it exists:

1. **i18n** — user-facing strings must go through `stringResource(…)` / `t(…)`; on the web,
   `eslint-plugin-i18next` is the intended rule. [ADR 0016](adr/0016-localisation.md) states the
   convention and `agent.md` §19 the authoring rules; nothing enforces either.
2. **Raw `println` / platform logging** — `core-logging-facade.md` §Lint backstop.
3. **Statement-position calls** of the flagged family — this file, § "`TResult`-returning ops are
   silently discardable".
4. **Forbidden imports** for spellings retired in the 2026-08-10 deprecation sweep
   (`kotlinx.datetime.Instant`, `dayOfMonth`, `monthNumber`, `runComposeUiTest`,
   `createComposeRule`, `TabRow`, `by getting`, `js(IR)`, the `org.jetbrains…preview.Preview`
   namespace).

**The evidence that (4) is not hypothetical.** The sweep cleared nine deprecation classes on
2026-08-10. Within hours **two files reintroduced `kotlinx.datetime.Instant`** —
`DetectedWorkoutPromoter.kt` and `DetectionSupersessionTest.kt` — written against a `master` that
predated the migration by a few hours. Both were corrected. ⚠ Neither author did anything wrong: nothing in the build says the spelling is retired, and
a deprecation warning does not fire for `kotlinx.datetime.Instant` because it is a *typealias* to
the very type it recommends.

**Decision: still deferred, deliberately.** The cost is standing up Detekt across ~130 KMP modules
and choosing a baseline for an existing codebase — not writing four rules. Several of these were
consciously taken as "convention now, lint later" at n=1 and that call has not been invalidated.
⚠ What *has* changed is that the queue reached four and one of them now has a measured
regression rate rather than a hypothetical one.

**Cheaper interim for (4) specifically, if the drift recurs**: the retired spellings are all
*import lines*, so a grep in `scripts/android-smoke.sh` — which already asserts schema lockstep —
would catch them for a few lines and no new tooling. Less principled than Detekt, proportionate to
one file drifting back in a day.

**Revisit when**: a second rule becomes load-bearing rather than nice-to-have (the i18n one will,
at n=2 — see [ADR 0016](adr/0016-localisation.md) *When to revisit* #1, the ICU trigger), or the
retired-spelling drift recurs after this correction.

### Photos and Calendar navigated with a `when`, not the shared nav host — device pass owed

Both now run on `KmpNavHost` (Photos inside `AdaptiveNavScaffold`). Pins: `PhotosNavRoundTripTest` and
`CalendarNavRoundTripTest`, both falsified against the plain `when` they replaced. ⚠⚠ Calendar's
`…KeepsTheSameCalendarViewModel` case is **not** falsified by that swap — without a host every screen
shares the ambient `ViewModelStore` — and says so in its KDoc; it pins the nav host's contract.

⚠ **Owed: a device pass.** Neither migration has had one; both are compile- and test-verified only.

⚠⚠ **"Scroll a list, open a detail, come back, it is at the top" has had THREE unrelated causes in this
repo**, each reported in almost identical words, each fix useless against the other two:

| # | Cause | Surface | Fix |
|---|---|---|---|
| 1 | `AdaptiveNavScaffold` rebuilt its whole content subtree when the chrome hid, killing the nav host, its `ViewModelStore`s and its saved state on every push and pop | Fit History | `ui-adaptive-navigation.md` §8 |
| 2 | The paged flow was collected **without `cachedIn`**, so leaving composition dropped every page; `rememberSaveable` then restored index 41 into a ten-row list and `LazyColumn` clamped it | Drive, Fit | `core-paging-scroll-restoration.md` §2 |
| 3 | **No saveable-state registry at all** — a destination `when` tears the screen out of composition, and every `rememberSaveable` (including `rememberLazyGridState`'s) with it | Photos, Calendar | the shared nav host |

⚠⚠ **So the diagnosis is not "we know this one".** Establish which half is missing first: is the
*data* still there when you come back, or the *scroll position*? #2 loses the data and keeps the
scroll; #3 keeps the data and loses the scroll.

⚠⚠ **Routes carry ids; the payload stays in something that outlives the entry.** A route is the map
key both `KmpNavHost` actuals use for an entry's `ViewModelStore` and saveable-state slot, so a domain
value in it makes screen identity value equality over every field. Photos looks a smart album up in
`AlbumsViewModel` by id and holds one shell-side snapshot for the viewer; Calendar holds one shell-side
`openedOccurrence`, because a reminder tap and a cross-app open routinely name an occurrence *outside*
the loaded window and in a hidden calendar, so no id lookup could serve them.

⭐ **Calendar deliberately ships no `AdaptiveNavScaffold`.** A bar or rail switches between peers and
Calendar has one top-level surface; `CalendarPane` gates its own 220 dp sidebar at `Large` rather than
`Expanded` on the same width argument. **Revisit the day Calendar grows a second peer surface**, when
promoting *Calendars* from a pushed screen to a tab is the honest move; ⚠ it would then duplicate the
sidebar's *Manage* button at `Large`.

⭐ **Photos and Calendar are also the pair that ship no `composeResources` catalogue** (see the plurals
entry below): the two newest apps picked up the shared conventions late — worth a checklist for the
next new app.

### Plurals: the CMP constraint that justified the workaround is stale, and two apps have no catalogue at all (2026-08-31)

**Files**: `photos-shared-ui`, `calendar-shared-ui` (no `composeResources` at all); the
`*_one` / `*_many` string pairs in `drive-shared-ui`, `contacts-shared-ui`, `fit-shared-ui`,
`timeline-shared-ui`; `drive-android` + `control` `res/values/strings.xml` (the only real
`<plurals>`).

**Noticed as** Photos' Albums tab rendering **"1 photos"**. That is the visible edge of two
separate gaps, and neither is really a plural bug.

⚠⚠ **The decision is not missing.** [ADR 0016](adr/0016-localisation.md) anchor #3 already settles
it — *"ICU MessageFormat is the string-content format from day one"* — and names this exact case as
the reason: *"plural/gender/number formatting needs structural information that bare strings lose.
Cheap to do now, painful to retrofit."* The ADR's *When to revisit* #1 (n = 2, the ICU trigger) is
the scheduled moment. So this entry is about **drift from that decision**, not a gap in it.

**What actually exists today:**

| Surface | Plural handling |
|---|---|
| Android-native (2 sites: `AndroidNewFolderAnnouncer`, `ProxyService` notifications) | Real `<plurals>` + `getQuantityString`. Correct. |
| 7 CMP modules with a catalogue | Manual `*_one` / `*_many` string pair, selected by a hand-written `if (count == 1)` at each call site |
| `photos-shared-ui`, `calendar-shared-ui` | **No catalogue at all** — every user-facing string is an English literal in Kotlin |

⭐ **The finding worth acting on: the justification for the manual pairs is out of date.** The
catalogues carry a comment reading *"the project has no `<plurals>` in a composeResources catalogue
and ADR 0016 defers the ICU one at n = 1"*, and `contacts-shared-ui` copied it from
`drive-shared-ui` verbatim (*"Two strings rather than a plural resource, following
drive-shared-ui"*). The first half was true under **CMP 1.7.3**. It is not true now:
**CMP 1.12.0 ships `pluralStringResource(resource, quantity, vararg formatArgs)` plus
`plural/CLDRPluralRuleLists.kt`** — verified in the resolved `components-resources-1.12.0` sources,
not inferred from release notes. The capability arrived with
[`cmp-version-upgrade.md`](proposals/cmp-version-upgrade.md) and nothing revisited the workaround
it obsoleted. ⚠ A stale constraint that reads as a project convention gets **copied forward** — it
already has, once.

⚠⚠ **Why the manual pairs are not merely inelegant.** `if (count == 1)` hard-codes a *two-form*
language. CLDR gives Polish and Russian three, Arabic six. At n = 2 every one of these call sites
is a branch that has to be deleted rather than a string that has to be translated — which is
precisely the retrofit cost anchor #3 was written to avoid.

⚠ **Photos and Calendar are a different problem wearing the same symptom.** Both ship no
`composeResources`, and both say so deliberately in an `App.kt` KDoc: *"Half-localising one banner
would hide the app's convention rather than advance it — the i18n rollout moves them together or
not at all."* That reasoning is sound and this entry does not dispute it. But it means **"1 photos"
cannot be fixed by a plural resource**, because there is no catalogue to put one in. The two apps
are simply outside the Phase-2b sweep that covered `:control-shared` and `:drive-shared`.

**Decision: still deferred, and the deferral is still right at n = 1.** Nothing here is user-visible
beyond the grammar of a subtitle. What has changed is that one of the deferral's two stated reasons
has evaporated, so the next person to reach for the workaround should know the API exists.

**Sizing, if picked up:**
- *Cheapest, and worth doing on its own*: correct the stale comment in the four catalogues so it
  stops propagating — the constraint is gone, only the n = 1 deferral remains. An hour.
- *Medium*: convert the existing `*_one`/`*_many` pairs to `<plurals>` + `pluralStringResource` and
  delete the `if (count == 1)` branches. Mechanical, no new tooling, removes the retrofit debt at
  its source.
- *Larger, and its own slice*: give Photos and Calendar catalogues. This is an i18n-coverage task,
  not a plural one, and it belongs with the ADR 0016 rollout rather than with a subtitle fix.

**Revisit when**: the ADR's n = 2 trigger fires, or someone touches one of the `*_one`/`*_many`
sites for another reason — converting the one in front of you is cheaper than a sweep. See also the
i18n lint rule queued in the Detekt entry above, which would catch the Photos/Calendar literals if
that module ever lands.

### The Android smoke gate's packaging step fails ~1 run in 3, in a different app each time — two captured causes: the disk is full, or the heap is; `df -h` first

**Symptom**: `scripts/android-smoke.sh` fails during the **build**, never during the launch or the
assertions:

```
Execution failed for task ':drive-android:packageDebug'
> A failure occurred while executing
  com.android.build.gradle.tasks.PackageAndroidArtifact$IncrementalSplitterRunnable
```

⚠⚠ **The module is not the variable — the task is.** Filing this as "`:drive-android` is flaky"
would send the next person to read Drive's build file, which has nothing to do with it. Five
different app modules have produced it; what they share is AGP's packaging step running for
**eight APKs in one invocation, with the AVD resident**.

⚠ **This is NOT the "full gate is flaky under parallel load" entry below.** That one is *tests*;
this one is a *build task*, under the smoke script, with no test involved. The shared word "flaky"
is exactly what would merge two unrelated investigations.

**Captured causes** (the script now runs `--stacktrace` and tees to
`build/smoke/android-smoke-build.log`, override `TENSHU_SMOKE_BUILD_LOG`, so the `Caused by:` under
the worker wrapper survives):

| Task | `Caused by:` | Machine state |
|---|---|---|
| `:timeline-android:packageDebug` | `IOException: No space left on device` at `zipflinger.ZipWriter.write` | **417 MiB free of 460 GiB**; five daemons, ~11 GB of worktrees |
| `:home-android` + `:maps-android:packageDebug`, one invocation | `OutOfMemoryError` at `ApkFlinger.close` | — |
| `:home-android:packageDebug` | `OutOfMemoryError: Java heap space` at `zipflinger.Compressor.deflate` ← `NoCopyByteArrayOutputStream.<init>` | **9.1 GiB free** (disk ruled out); one daemon, AVD resident, 680 MB RAM free |
| `:maps-android:mergeExtDexDebug` | `OutOfMemoryError: Java heap space` in R8 `DexMergingTaskDelegate` | 11 GiB free, run straight after a full gate (load ~8) |

⚠⚠ **Two distinct causes produce this identical symptom.** `df -h` (with `vm_stat`) is how you
tell them apart in one command, not how you close the case. Check free disk and free memory
before reading a build file. ⚠ A hypothesis that would unify them, untested: swap lives on the
volume that fills, so a full disk also stops the heap growing.

⚠⚠ **Retry once; a second failure on the same task is not this.** And a green retry is not
evidence the first failure was spurious: the APKs that already packaged are UP-TO-DATE on the
retry, so the failing task runs nearly alone, and a disk-full run's discarded output frees a few
hundred MB — both are what a resource failure predicts. A second failure on the same task wants
`df`, `vm_stat` and the log, not a re-run. ⚠ The retry **overwrites** the log; copy it aside
first. ⚠ CI runs `./gradlew check`, which skips assembly, so `packageDebug` runs nowhere but this
script: if the script does not keep the log, nobody does.

**What allocates**: zipflinger deflates each entry into a byte array sized by its *uncompressed*
length, so peak heap tracks the largest entries. Every APK carries `androidx.sqlite:sqlite-bundled`'s
`.so` for four ABIs (inherited from `:core-common`'s `jvmMain`), and no module sets `abiFilters`
for debug. ⚠ The cheapest lever is `abiFilters` restricted to the emulator's ABI for the **debug**
build type only — narrowing release ABIs is a shipping decision, not a build-flake one. Do it as an
experiment: measure the rate over ~10 runs before and after and record both here. Open whether the
ceiling that matters is the worker's heap or the daemon's `org.gradle.jvmargs`. ⚠ Do not lower
`org.gradle.jvmargs` to fit more daemons (`gradle.properties` explains why).

**Disk hygiene**: a worktree costs ~1–3 GB of build output and nothing shows its size.
⚠ `git worktree remove` fails with *"Directory not empty"* when a build directory is present;
`rm -rf` then `git worktree prune` is what actually reclaims it.

**Ruled out — do not re-tread**: shared packaging state. Each app has its own `namespace` and
output directories; the one shared file, `~/.android/debug.keystore`, is only read.

⚠⚠ **The cost is not the two minutes a retry takes.** A gate that cries wolf trains everyone to
re-run it reflexively, which is exactly how a *real* packaging regression gets waved through.

### CMP Version Pin — lifted; the rules it left behind

**File**: `gradle/libs.versions.toml` (`jetbrains-compose`, with `compose-bom`).

There is no CMP pin. ⚠ **Bump `jetbrains-compose` and `compose-bom` together**, or the Android and
multiplatform runtimes split. ⚠ **Do not re-introduce the `force()`/`exclude()` resolutionStrategy
blocks or the `fixComposeResourcesForAndroid` Sync tasks** — they were the 1.7.3 workaround, and at
1.9.x+ they strip the real `androidx.compose.*` classes. As-built in
[`cmp-version-upgrade.md`](proposals/cmp-version-upgrade.md).

⚠⚠ **The `Preview` import and its dependency are one change, and the obvious replacement is the
wrong one.** `compose.components.uiToolingPreview` ships *only* the `org.jetbrains.…` annotation,
and it is that artifact's `Preview` that carries the deprecation, so flipping imports without
swapping the dependency (or vice versa) leaves the warning in place. The dependency is the
**JetBrains** `org.jetbrains.compose.ui:ui-tooling-preview` — what `compose.preview` resolves to —
**not** `androidx.compose.ui:ui-tooling-preview`, despite the deprecation message naming the
`androidx` *annotation namespace*: the androidx artifact publishes no iOS/js/wasm targets, so it
cannot back a `commonMain` preview. It compiles on Android and then breaks every other target.
There is no duplicate-class risk, because the JetBrains artifact's android variant ships zero
files and simply depends on the androidx one.

**Still open**: three `:control-shared` desktop tests are on the v1 `runComposeUiTest`
(`CheckoutStorePurchaseScreenTest`, `ForgetDeviceDialogTest`, `RecoveryPhraseScreenTest`); the rest
of the repo is on v2. ⚠ The move is **behavioural**: v2 defaults to `StandardTestDispatcher`, so
its signature failure is "test times out", which is also the gate-flake signature (entry below).
And `LocalClipboardManager` — see the next entry.

### `LocalClipboardManager` is deprecated with no portable replacement — suppressed on purpose (2026-08-10)

**Files**: 7 sites across `control-shared`, `contacts-shared-ui`, `timeline-shared-ui`,
`drive-shared-ui` (all now carry `@Suppress("DEPRECATION")`).

Compose deprecates `LocalClipboardManager` for `LocalClipboard`. ⚠⚠ **This is not a rename, and
it cannot be done from `commonMain` today.** The replacement `Clipboard` exposes only
`suspend getClipEntry()` / `suspend setClipEntry(...)`, and `ClipEntry` is a wrapper around the
**native** clip object (Android `ClipData`, desktop AWT `Transferable`) with **no portable
text accessor**. Verified against **both** CMP 1.11.1 and 1.12.0-beta03 — `ClipEntry` is
identical in the two, and the deprecated `ClipboardManager` still ships. So the gap is not
closing next release.

Retiring it therefore means (a) a new expect/actual seam converting text ↔ `ClipEntry` per
platform, and (b) making 7 call sites async. That is a design slice, not a cleanup — and it has
a real open question, since `drive-shared-ui` already owns a `SensitiveClipboard` expect/actual
seam created for exactly this class of gap (the Compose API can't reach
`ClipDescription.EXTRA_IS_SENSITIVE`). Any seam should decide whether it subsumes that one.

**Decision: suppress and wait.** The deprecation states **no removal version**, three of the
seven sites were already suppressed by earlier authors (this only makes that choice uniform and
greppable), and hand-rolling plumbing androidx may yet obsolete is the worse bet. ⚠ The reads
are the harder half — one site (`MnemonicGrid`'s paste) calls `getText()`, and extracting text
from a `ClipEntry` is precisely what has no common API.

**Revisit when**: CMP ships a common text↔`ClipEntry` helper, or a removal date is announced, or
something independently needs a multiplatform clipboard seam (at which point fold this in).

### The documented gate compiles no iOS code — Kotlin *or* Swift — the Kotlin half is closed; Swift is still open

**Kotlin**: `scripts/run-gate.sh` runs the iOS lane (`compileTestKotlinIosSimulatorArm64`), which
compiles every module's `iosMain` **and** iOS test source sets, and `./gradlew iosKotlinCheck`
covers what the SDK graph reaches. The test half matters: a `commonTest` call can resolve to a
*different declaration* per platform — `MutableList.replaceAll` binds to `java.util.List.replaceAll`
on the JVM and to `kotlin.collections.replaceAll` (`@ExperimentalNativeApi`) on K/N — so it is green
on every JVM gate and fails only for Kotlin/Native. ⚠ Opting in is not a fix from `commonTest`:
`kotlin.experimental.ExperimentalNativeApi` does not exist on the JVM. Use common stdlib instead.
⚠⚠ Off macOS the lane prints *"iOS lane NOT VERIFIED"* rather than passing — K/N cannot
cross-compile an Apple target.

**Swift — still open.** Only `scripts/ios-smoke.sh --build-only <app>` compiles Swift, and it costs
an XCFramework assembly plus an `xcodebuild` per app — too slow to fold into the default gate, and
it needs Xcode, so it cannot be the one command everyone runs. A `*UiState` field removed in Kotlin
leaves the Swift that reads it uncompilable behind a green gate. ⚠ The rule is documented in
`AGENTS.md`'s *Build & test* block instead, which makes it a **claim**: a change that removes a
field from a shared `*UiState` still relies on someone remembering. A cheap mechanical check would
have to know which Kotlin symbols each Swift file references, which is what the compiler does — so
the honest options are CI (blocked, see the billing entry above) or a pre-push hook. Neither is in
place.

### The full gate is flaky under parallel load — a different test fails each run

A full gate (`scripts/run-gate.sh`) goes red on one to three tests that pass alone, and the
identities rotate between runs. Over twenty members have been recorded, and ⚠⚠ **most of the ones
diagnosed were not load at all**: a forked A4 signing identity (`DeviceSigningKeyConcurrencyTest`),
a tombstone deleting a row its device had just written, a lost listing update, a dropped stream
`End`, a paging settle flag clobbered by an unordered second writer, a resumed region download
publishing 0 before its resume point, a server that returned before binding its socket, the hub's
cadence override never reaching its scheduler. So a red here is a lead, not noise. ⚠⚠ CI is
intermittently billing-blocked (entry above), so the local gate is the gate.

**Still open**, with where each stands:

| Test | Module | State |
|---|---|---|
| `FilesPagingIntegrationTest` hang (three methods) | `:drive-shared-ui` | ~8 % of gates; § *The hang, narrowed to one seam* below |
| `ContactHandshakeSystemTest` `COMPLETED` vs `REDEEMED` | `:system-test-utils` | own entry; three of four routes ruled out structurally. ⚠ **2026-11-01: red 2/10 on master** (macOS, `--rerun`, load < 12) — about 1 in 5, far above the earlier 3/23; worth re-diagnosing |
| `SharesPage.test.tsx` (~2/8) | `web/apex` | own entry; the forced-slow-revoke hypothesis was tested and **falsified**, mechanism unknown |
| `anAbsentThumbnailIsNotRemembered_becauseItMayArriveLater` | `:photos-shared-ui` | a **reporter** (`UncaughtExceptionsBeforeTest`); own entry — ⚠⚠ the obvious mechanism was tested and does not hold |
| `RetiredBlockDisposalTest` / `MultiRealmPushDrainTest` (`Entry not found`, "sanity" preconditions) | `:sync-service-proxy` | watch. Both fixtures build with `startBackgroundLoops = false` (P2c); `RetiredBlockDisposalTest`'s *"sanity: queueing must have persisted the block"* reproduces 2/2 under the old shape, but `Entry not found` was reproduced in no arm. § "11 test fixtures stopped their scheduler with `stop()`" |
| `SyncLaneTest > bulkSaves_collapseToCeilOfCapBlocks` | `:sync-service-proxy` | the trailing `assertEquals(0, journalDeltas().size)` (*expected:<0> but was:<1>*) after `awaitEmitIdle()` — a drain-completion assumption, not a wrong count; 1/3 gates once, mechanism unread |
| `MultiProxySyncIntegrationTest` (reporter shape) | `:drive-shared-ui` | swept (P3) but never tied to a named leaker; watch |
| `PairingVmFlowSystemTest > offerFlow_…` | `:system-test-utils` | seen once; its swallowing poll is now `OfferPairingMonitor` and its teardown joins every scope (P0) |
| `DelegatedAdmissionSystemTest > delegatedAdmission_fullLifecycle_admitThenSubtreeRevoke` | `:system-test-utils` | once under load (*"removal must master-sign revocations for B AND its lineage subtree"*), green on rerun and alone; interleaving never forced |
| `FileDetailScreenTest > testTwoUploadsShowCorrectDetailImage` | `:drive-shared-ui` | `ComposeTimeoutException` from a 1 000 ms `waitUntil` inside a 4–6 s test — the tightest margin in the population |
| `S3BlobStoreRealS3ContractTest` / `S3BlobStoreContractTest` (test varies) | `:sync-relay-server` | ⚠ always **transport-level** (403, read timeout, invalid HTTP response) out of the in-JVM S3Mock, never an assertion. Frequent in a **cold worktree**, seen once warm. ⚠ A red here on a new worktree is not evidence about your change until a primary-checkout baseline has run. ⚠ The 1 000-key batching is pinned by the fake's `deleteAll_issuesOneRequestPerThousandKeys_ratherThanOnePerKey`, not at the S3Mock layer: S3Mock accepts an oversized `DeleteObjects`, so it could catch nothing there |
| `SyncCursorStoreDesyncSystemTest` | `:system-test-utils` | 60 s `advanceUntil` timeout on waits that take 0.5–2 s — a **hang**, not slowness. Joiner polls at 100 ms, so a lost wake cannot explain it; a probe on `SyncScheduler`'s loop saw it never stall, throw or die across six gates |
| `SyncMetadataOperationTest > testSavingSyncableEntityGeneratesBlock` | `:sync-service-proxy` | once; green in three forced module reruns |

Also open, found by this work: `UnixSocketTaskTransport.openConnection` swallows a failed *first*
connect, and the reconnect loop is armed only by the death of an established connection, so a
client that binds before Control is up stays unconnected with nothing retrying — a design question
for `core-client-resubscription.md`'s open items.

**Closed members cited by id** (each by a forced pair: deterministic red without the fix, green
with it, same forcing both arms):

- **P0** — `PairingVmFlowSystemTest` leaked a 6 s `watchForUnfundedRealm` timer on an uncancelled
  `viewModelScope`; it fired against the next test's closed Koin graph and was replayed into
  `TestClientGraphResolutionTest` / `TestSystemSmokeTest`. The class now cancels-and-joins every
  scope it built before `system.shutdown()`.
- **P0b** — a derived-view upsert inside `database.transaction` is synchronous and not cancellable,
  so `TestSystem.shutdown`'s bounded join abandons it alive and its next lookup casts null.
  `guardDerivedViewTick` wraps the whole tick in all three derived-view collectors; pinned by
  `DerivedViewStragglerTest`. ⚠ Raising the 2 s cap is not the fix — a bounded join can always
  abandon a non-cancellable section. ⚠⚠ Guarding only `database.transaction` is too narrow: the
  row construction one line earlier reads the same graph.
- **P1a** — `WorkoutStreamsOnDemandSystemTest`: the host pushes the Workout block one request before
  its manifest block (entity-before-bytes, ADR 0015); the detail read is re-issued inside
  `advanceUntil`.
- **P1b** — `StreamLateSubscriberTest` awaits the asserted set, and its timeout fails with the last
  observed set.
- **P1c** — `HomeStorageTileSystemTest`: the publisher's heartbeat is 1.5 s under the reader's 3 s
  `staleAfterMs` (production's ratio).
- **P2a / P2c** — hand-stepped fixtures build with `initSyncService(startBackgroundLoops = false)`:
  a loop started then `stopAndAwait`-ed ticks once on `start()` before the cancel lands.
  `SyncHealthReportingTest`, `BackfillCatchUpTest` and `MergedLaneFallbackTest` went red under
  that forcing.
- **P3** — `:drive-shared-ui`'s 33 classes own every view model, controller scope and Compose
  `ViewModelStore` they build (`:viewmodel-test-utils`, `docs/testing.md` §11); with the join
  skipped the guard fails 196 of 413 tests.
- `FileDetailsDeferredPreviewIntegrationTest` — two independent reorderings with one message:
  per-element `scope.launch` dispatch (fixed by `forwardStreamElementsInOrder`, pinned by
  `FileDetailsDeferredPreviewForcedInterleavingTest` and `RemoteBinaryTaskExecutorPushOrderTest`;
  lane lifecycle by `RemoteBinaryTaskExecutorRebindTest` / `…LaneLifecycleTest`) and
  `StreamInteractorImpl` delivering outside its mutex (pinned by
  `StreamInteractorImplDeliveryOrderTest`). The constraints are on `StreamElementForwarding.kt`.
- `DefaultRegionDownloaderTest` — ⚠⚠ the deschedule in `resumedProgressStartsFromTheStagedBytesNotZero`
  is **load-bearing**: `progress` is a conflated StateFlow, and without it the assertion stops
  testing anything. The test's comments carry the rule.
- `UnixSocketServer.start()` blocks until bound, pinned by `UnixSocketServerBindContractTest`
  (fails 5/5 against an unbound start with no forcing).

**The method — each rule below was learned by getting it wrong:**

- **Re-run the failing test alone, then on clean master.** Two commands, and the only thing between
  this entry and a waved-through regression. ⚠⚠ Three outcomes, not two: passes alone → likely
  the population; fails alone → real; **deterministic only at gate scope** (fails repeatedly in the
  gate, passes in its module) → a regression whose trigger is load, settled only by an A/B of the
  change at gate scope. ⚠ Some members reproduce in isolation under **CPU burners** — try those
  first (minutes a loop), gates only when they come up empty.
- ⚠⚠ **A rate is corroboration; the forced-interleaving pair is the evidence.** Four consecutive
  greens arise by luck 41 % of the time at a 20 % true rate; two of this entry's conclusions were
  drawn from arms of 3 and 10 runs and refuted by more. `scripts/gate-report.py --compare A B`
  prints the Fisher exact p beside the two rates for this reason. ⚠ "Green serially ⇒ not your
  change" is unsound.
- ⚠⚠ **Reporter, not victim.** `UncaughtExceptionsBeforeTest` says the failing test did not fail:
  kotlinx-test's global `ExceptionCollector` replays an earlier test's leaked throw into the next
  `runTest`. Re-running the named test proves nothing; find the test that **leaks** (grep the module
  for `setMain` users and scopes built and abandoned) and cancel-**and-join** — a bare `cancel()`
  returns while a hop is still unwinding. ⚠⚠ The collector is armed only by the **first `runTest`
  in the JVM** (in `:system-test-utils`, `ElementRequestObjectRegistrationTest`), and Gradle's class
  order in a filtered run is not alphabetical, so a `--tests` pair experiment must carry its own
  enabler and reporter inside the leaking class or it is green by construction.
- ⚠ **When a symptom is unattributable, assert the invariant** — `vmTest`'s "no owned scope still
  active" guard fails deterministically where forcing the async throw never did.
- ⚠⚠ **Measure the wait before raising a timeout.** A wait that takes 24 ms and fails at 8 s is a
  dropped signal, not a slow machine; this entry was tempted by the bigger number four times.
  `TestSystem.advanceUntil` reports `elapsed / budget` for that.
- ⚠ **Wait on the value you assert.** `Zigbee2MqttBridgeTest`, `DefaultRegionDownloaderTest` and
  `FitPipelineIntegrationTest` all awaited one signal and asserted another.
- ⚠ An identical failure *value* across runs is not evidence of determinism, and a varying one
  (an HLC stamp) is not evidence of separate bugs.
- ⚠ When a change is inert on the happy path, log its divergent branches and grep the failing run
  before spending gates on an A/B — code that never ran cannot be the cause.
- ⚠ A suspected flake with a state-carrying mechanism gets looped before it is filed here: the
  delete-and-reupload "flake" was data loss that passed a single isolated run.
- ⚠⚠ **Evidence traps**: a task you made pass with a module run is UP-TO-DATE in the next gate, so
  `--rerun` and confirm it executed; stale XMLs under `build/test-results` read as results;
  `case.find("failure") or case.find("error")` reports zero failures because a childless
  `<failure>` element is falsy; `./gradlew … ; echo "EXIT=$?"` reports the echo's status.
  `scripts/gate-run.sh` (`docs/testing.md` §7) handles all four, and the root build prints test
  failures with `exceptionFormat = FULL` + `showCauses` — ⚠ the half that carries the original
  throw inside an `UncaughtExceptionsBeforeTest`.

#### The hang, narrowed to one seam — cause NOT yet proven

`FilesPagingIntegrationTest` (`folderRow_carriesChildCount_withoutProjectingChildren`,
`pagedListingIsMetadataOnly_previewLoadsPerRow`, `pagedListingReturnsUploadedFiles`) times out at 30 s
in `boundedSnapshot`, ~8 % of gates (8 in 102), once at module scope (1 in 8, against master's
0 in 28 — inconclusive). Three methods, one shape: the hang belongs to the pipeline, not a fixture.
⚠ The 30 s answer is **not** a bigger number: the call takes 0.09 s.

| Measurement | Result | What it rules out |
|---|---|---|
| `PagingData` generations, ms from subscribe | `[1, 7]`, `[2, 8]`, `[1, 10]`, `[0, 3]` — then 30 s of silence | an invalidation loop |
| `load()` enter / `getAll` begin / returned | every load returned a page | a stuck read |
| threads at timeout | every dispatcher worker parked, no `load` on any stack | a busy loop |

So the page loads and the differ never settles: the hang is in the presentation / load-state path.

❌ **The second generation is not the trigger — do not spend gates on it again.** Holding the first
refresh open so the subscribe tick lands mid-refresh gives two generations and settles 3/3; the
probe's healthy control at the hang site shows the same. Any theory has to explain why that shape
sometimes does not settle. ⚠ Do not suppress or delay that subscribe tick: `debounceExceptFirst`
passes it through undebounced on purpose, because debouncing it re-anchored a user who scrolled
within 400 ms of opening a folder (`IndexOutOfBoundsException` out of `SnapshotLoader.awaitLoad`).

**The instrument**: `PagingSettleProbe` + `FilesPagingSettleProbeTest` record a timestamped timeline
of generations, `CombinedLoadStates` and `PagingDataEvent`s and print it on timeout. It is wired into
`FilesPagingIntegrationTest` at the two sites that hung first; the others keep `boundedSnapshot` so
the real `asSnapshot` path stays in the population. ⚠ The probe has never caught a hang. Arm a
newly-hit method rather than converting the whole class — if the presenter path does not hang,
converting every site hides the bug. ⚠ `FilesPagingIntegrationTest`'s `collectorScope` keeps its bare
`cancel()` on purpose: a join there turns a caught hang into a hung test. Whether the defect is ours
or paging 3.5.0's is undetermined.
### The hub's sync cadence override never reached its scheduler — `it` shadowed inside a Koin definition

**File**: `hub/src/main/kotlin/app/tenshu/hub/HubRuntime.kt` (`assemble`), read by
`control-shared-proxy/src/desktopMain/kotlin/app/tenshu/control/di/PlatformProxyModule.kt`
(`getOrNull<Long>(named("syncPollingIntervalMs")) ?: 30000`).

⚠⚠ **Inside a Koin definition lambda the implicit `it` is the definition's `ParametersHolder`, not
an enclosing `let`'s value.** `syncIntervalMs?.let { single(named("syncPollingIntervalMs")) { it } }`
registered a `ParametersHolder`, the desktop hook's `getOrNull<Long>` answered null, and every hub —
tests, `--sync-interval-ms`, and the HD-D2 bridged fast-poll default (`DEVICE_FAST_POLL_INTERVAL_MS`)
— ran at 30 s. Name the parameter: `let { interval -> single(…) { interval } }`.
`HubRuntimeAssemblyTest.syncIntervalOverride_reachesTheSchedulerAsALong` pins the resolved *type*.

⚠ The symptom — a bare 30 s `advanceUntil` timeout, rare, green in isolation and on re-run — is
indistinguishable from a load flake from the outside. Print `elapsed / budget` on each wait, and what
each scheduler was constructed with, before calling one flaky.

### Two 30-second budgets raced, so nine hub waits could only fail namelessly

**Files**: `hub/src/main/kotlin/app/tenshu/hub/HubPairingCli.kt`; the `pairViaOfferFlow` helper in
the `:hub` system tests. **Tests**: `HubPairingCliTest`.

The helper's waits are asymmetric to the CLI's: 10 s for the `Offer URI:` line names an arming stall,
45 s against the CLI's 30 s names a poll stall, and each raises an `AssertionError` carrying the CLI's
transcript. `HubPairingCli` runs both entry points under a real deadline, so a wedged relay re-arms
instead of hanging, and the expiry message names which half ran out (arming or approval). That
deadline follows `runTest`'s virtual clock, which `TimeSource.Monotonic` did not — the reason
`HubPairingCliTest` runs in under a second.

⚠ **There is no `--timeout` flag.** `HubOptions` has no timeout field and `Main` builds
`HubPairingCli(runtime.client, invertQr = …)`, so production always runs the parameter's 5-minute
default; the budget is settable only from code and tests.

⚠⚠ **Still open — the deadline bounds the WAIT, not the operation.** The pairing ops are ordinary
`IoOperation`s, so `ExecutionContext` runs each as `scope.async { … }.await()` on `sessionScope`, a
child of the session rather than of the caller. Cancelling the await orphans the call: a wedged relay
leaks one in-flight request per re-arm window, and a late `getOfferStatus` can still install keys —
that op's documented contract — after the CLI reported `TimedOut`, leaving the hub paired while
`Main` polls a freshly minted offer. The fix needs the caller-bound evaluation scope
`CancellableLocalTaskExecutor.executeAsyncCancellable` opens, which `DefaultInteractorProvider`
selects only for a `withTask`-tagged call — a sync-lane change (`core-task-composite.md` §5). The
`PairingOps` seam's fake bypasses that executor and cannot verify it; it needs a system test against
a wedged relay.

⚠ **The shape is worth grepping for elsewhere**: a caller's timeout equal to the callee's own turns
every overrun into a nameless `TimeoutCancellationException`. And a diagnostic that names a failure
mode is worthless if an earlier, un-named wait catches that mode first.

### A device's state report could erase the outcome of its own command

**File**: `hub/src/main/kotlin/app/tenshu/hub/DeviceBridgeHost.kt` (`onBridgeState`,
`handleCommand`, `publishState`).

Two coroutines write the same `DeviceState` row: the bridge's state stream and the command executor.
⚠⚠ In both writers the gate's decision, the read of `lastCommandInfo` and the save are one step under
`publishLock`. Without it a state report that read the *previous* command saves after the outcome and
erases the echoed command id; nothing repairs it (later reports are `NoChange` until the 1 h
heartbeat), and the user's row sits on *pending*, then says **unconfirmed**, for a command that
succeeded. Pinned by
`HomeDeviceControlSystemTest.aStateReportRacingItsOwnCommandDoesNotEraseTheEchoedOutcome`, which parks
the report between its snapshot and its save (red without the lock). The test's waits go through
`awaitStep`, which names the step and dumps the light, the executed count and the echoed row, so a
gate sighting is attributable from the log alone.

⚠ **Not checked**: `Zigbee2MqttBridge` reports state over MQTT after a command is published and
returns no `newState` from `execute` — the same two writers with a wider gap between them. The lock
covers it by construction; no test forces that bridge's ordering.

### A session that lost the mint race kept the whole sync runtime alive — `ClientRegistry.getExecutionContext` minted twice

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/ClientRegistry.kt`
(`getExecutionContext` / `mintSession` / `systemContext`).

⚠⚠ The in-process client (`SessionAwareLocalTaskExecutor`) calls `getExecutionContext` from whichever
thread runs the op, so two first calls race. The publish is a `putIfAbsent` (CAS over the
copy-on-write map); a losing mint cancels its scope and is torn down **before** any initialisation, and
a loser uses the winner only when the session identities match (a session carries its transport
connection). "Publish before init" (the re-entrant case) and "publish atomically" (the concurrent case)
are different fixes for different interleavings — removing either brings back an orphaned session
that runs the sync runtime and that `shutdownAndJoin` cannot reach. Pinned by `ClientRegistryConcurrencyTest`
(`concurrentFirstCalls_forOneClientId_shareOneSession_andTheLoserIsTornDown`,
`concurrentFirstCalls_withDifferentIdentities_neverShareASession`, and the two displaced-winner cases).
A regression shows up after a `shutdown()` as a flood of `DataFactory` NPEs from
`ScheduleDrainLoop.tick` and `ClosedScopeException` from `SyncScheduler.reportSyncHealth` — loops still
ticking against a closed Koin app.

⚠ **Left open, inert while `mintSystemSession` is off** (the "Known gap" KDoc in `ClientRegistry`): a
caller that loses the mint race for the *system* session is handed the winner before the winner's
initialisation has finished, so its client session's init can race the system session's for
`SyncRuntimeHolder`. The fix is a loser that awaits the winner's init, which needs a blocking wait
`commonMain` has no primitive for (JS is single-threaded and never races, so an `expect` yield is the
shape). ⚠ Do this before enabling P7.

⚠⚠ **A diagnostic trap, unresolved**: a test that calls `TestSystem.shutdown()` **mid-body** (the two
backup/restart tests, the routine-event test) loses Gradle's capture of BOTH stdout and stderr from
`relayApp.stop()` to the end of that test method — `println` and `System.err.println` vanish,
`finally` still runs, the next method captures normally, and the XML's `<system-out>` is truncated the
same way. Print to a file, or read the test's own side effects, when the wait you are instrumenting
sits after a relay stop. Cause not pinned (Ktor 3.5.2 `TestApplication.stop()` is the last thing seen).

### ⚠⚠ A hung test does not go red — it hangs the GATE, forever

A test body with an **unbounded wait on a stream that never completes** — `asSnapshot()` over a live
folder listener, `first { }` on an endless flow — parks every dispatcher thread and the gate stalls
indefinitely (one capture: 4.2 s of CPU in 794 s of wall clock, all four `DefaultDispatcher-worker`
threads parked). ⚠⚠ **And once someone kills it, it reads green**: a killed worker records its
in-flight case as **SKIPPED**, so what is left on disk carries `failures="0"`. `scripts/gate-harvest.py`
flags the shape (a build that failed while nothing went red).

⚠⚠ **Adding a deadline where there is none is not the timeout-widening this file warns against.** It
turns an un-diagnosable infinite gate into a red test with a stack; it does not make a hanging test
pass. `FilesPagingIntegrationTest`'s `boundedSnapshot` carries the idiom and the "do not raise this"
rule: the margin is ~300× a measured 0.09 s, so if it fires that is a dropped signal, not a slow box.

⚠ **The listing-never-settles hang itself is still undiagnosed.** The three call sites that have hung
(two in a gate, `pagedListingReturnsUploadedFiles` at module scope) drive a `PagingSettleProbe`
(`settledItemsWithTimeline`) so the next hang prints the differ's load-state timeline; the rest keep
`boundedSnapshot`, so the real `asSnapshot` path stays exercised. The two known ways a Tenshu listing
never settles were both product bugs — the subscribe-window write loss (next entry) and
`TenshuPagingPresenter`'s clobbered `_refreshSettled` latch — so a third occurrence of that shape is
the first thing to check, not a timing story.

**Not swept, deliberately**: ~220 `= runBlocking` test bodies repo-wide. Grep for the shape that
hangs (an unbounded wait on a never-completing stream), not for `runBlocking`.

#### ⚠⚠⚠ A repeat gate run executes ZERO tests and reports BUILD SUCCESSFUL

A second `./gradlew desktopTest jvmTest test` on an unchanged tree finds every test task UP-TO-DATE,
executes **none**, and prints `BUILD SUCCESSFUL` under an unchanged `actionable tasks` line — so "the
gate went red, let me re-run it" returns green by construction, and so does "I fixed the module, now
let me re-check the gate". `scripts/run-gate.sh` deletes every `build/test-results` dir first and
refuses to report success unless at least 50 test tasks actually executed; use it whenever a green
has to mean something. ⚠ A claim of *"I re-ran the gate and it passed"* is worthless unless the
executed-task count was checked.

### `FilesPagingIntegrationTest` — a write during the subscribe window was lost

The defect is fixed: `pagingSource` re-reads on the observer's first `StreamSignal.Subscribed`
(`changes(emitOnSubscribe = true)`), and ⚠ the initial load still fires at once — gating it on
registration would turn a stale screen into a permanently blank one whenever an observer fails to
register (`PagingUtil.reloadSignals` KDoc). What the same gate-flake investigation left open:

- ⚠ **`SortToggleIntegrationTest`'s `name descending` step — an undiagnosed hang.** The assertion wraps
  a `withTimeout`, so a failure means the reorder never arrived, not a wrong sort; the step normally
  takes ~62 ms of its 10 000 (161×). 0/9 under CPU burners — it needs whole-gate load. Its fixture was
  fixed separately so all four asserted orders are distinct; that does not close the hang.
- ⚠ **If `FilesScreenIntegrationTest.testFileUploadConflictShowsDialog` fires, treat it as informative,
  not as load.** `ComposeUiTest.waitUntil` advances a *virtual* frame clock but bounds itself on *real*
  `System.nanoTime` (default 1 000 ms), so load can shrink the budget — but its three waits measured
  413 / 322 / 158 ms under 6 burners on a 4-core box. Widening it would muffle the next real failure;
  the real fix, if that path ever gets slow, is to await the upload's `TaskHandle`, then `waitForIdle()`.
- ⚠ **A leaked coroutine fails an unrelated test, and the report names the innocent one.**
  `kotlinx-coroutines-test` installs `ExceptionCollector` as a JVM-global `CoroutineExceptionHandler`
  service, so an uncaught throw from a coroutine leaked by a *finished* test is replayed into the next
  `runTest` as `UncaughtExceptionsBeforeTest`; `Dispatchers.Main is used concurrently with setting it`
  is the same leak through `setMain` (`docs/testing.md` §11.1). `--info` shows the leaked exception in
  the worker output; the test logged just before it is the polluter. A test that overrides a DI factory
  can do the same to its neighbour — check what the *preceding* test bound.
- **`TestProxy`'s `coroutineScope` still defaults to an unmanaged `CoroutineScope(Dispatchers.Default)`.**
  A bare `TestProxy` starts no loops; `initSyncService` starts `SyncScheduler` and the 1 s
  `ScheduleDrainLoop`, and every fixture that calls it rides a `TestScope` or calls
  `TestProxy.shutdown()`. Making the parameter non-defaulted would stop the next leak being written by
  accident, at the cost of touching every site at once. ⚠ A grep-derived site list for this was ~40 %
  wrong (helpers already scoped outside the grep window, matches in comments); prefer an edit the
  compiler can reject — `coroutineScope = this` only compiles inside a `runTest`.
- ⚠ A test that steps the scheduler by hand (`tickOnce`) must `stop()` both `SyncScheduler` and
  `ScheduleDrainLoop` right after `initSyncService`, or the live loops race the stepping (pattern:
  `SchemaPullFenceTest`).

**Reproducing in a fresh sandbox**: the build cannot configure without JDK **17** and **25**
toolchains (`jvmToolchain(17)` in the service modules, `FFM_JDK = 25` resolved eagerly in
`control-desktop` / `drive-desktop` / `macos-helpers`), and Gradle's foojay auto-download is blocked
behind most egress proxies. `apt-get install -y openjdk-17-jdk-headless openjdk-25-jdk-headless` is
enough.

### A source-scanning test that Gradle cannot see the inputs of

**File**: `core-common/src/desktopTest/.../PhaseL2CancellationTest.kt`
(`no_proxy_operation_implementation_detaches_onto_session_or_service_scope`).

⚠⚠ The test walks other modules' `src/commonMain` **as text**, and those files are not inputs of
`:core-common:desktopTest`. A change elsewhere leaves the task UP-TO-DATE and the scan never re-runs —
it goes stale exactly when it has something to say, and a failing scan has sat on `master` through
three green gates this way. ⚠ After changing a proxy module, run it with `--rerun-tasks`, or through
`scripts/run-gate.sh` (which deletes test results first). The scan strips comments before matching, so
KDoc that quotes the forbidden `sessionScope.launch { … }` pattern does not trip it.

**Open**: declare the scanned trees as task inputs, or move discipline scans to a build-level check
that owns its inputs. A build-config change with its own blast radius, and the same shape applies to
any source-scanning test — `SchemaShapeBaselineEnforcementTest` is the one to check next.

### Fire-and-forget launches in proxy init — the collectors still die silently

**Files**: `control-shared-proxy/.../di/ProxyModule.kt` (the backstop).
**Regression test**: `core-common/.../SessionScopeExceptionContainmentTest.kt`.

⚠ The proxy's service scope carries a `CoroutineExceptionHandler`, and every session scope inherits it
(`ClientRegistry.mintSession` builds `parent.coroutineContext + SupervisorJob(...)`), so an uncaught
throw in a `sessionScope.launch` is a named line rather than — on Android — a dead Control. Keep it:
without it any fire-and-forget init task (`registerOfflineKeeperSchedule()` is one) can kill the
process.

⚠⚠ **The backstop contains the crash, NOT the loss.** These sites are overwhelmingly
`scope.launch { someFlow.collect { … } }`. A throw inside the collect kills that collector **for
the rest of the session** — the row it publishes silently stops updating, and nothing restarts it.
That is the same silent-death family as the client-side subscription bugs in
`core-client-resubscription.md`, this time on the proxy side. The handler makes it *findable*; it
does not make it *recover*.

**The population, for whoever hardens them** (each is `scope.launch { … .collect { … } }` with no
`try`/`catch`, publishing a row a UI reads):

| site | what stops updating if it throws once |
|---|---|
| `SyncHealthInitialization.kt:41` | `SyncHealthStatus` — Drive's sync banner |
| `ContentTransferInitialization.kt:51,114` | content-transfer + upload-progress figures |
| `EntitlementWriter.kt:130,157,180` | entitlement redemption / minting |
| `EntitlementStorageReader.kt:125,178` | storage figures behind the quota UI |

**Already correct, and the shape to copy**: `SyncProxyInitialization.kt:576` wraps its body in
`runCatching { … }.onFailure { println("SyncProxy -> lane-journal replay failed …") }`.

⚠ Not fixed here, deliberately: hardening nine collectors is a change with its own design question
(catch-and-log, or catch-and-restart — a collector that dies on a transient store error arguably
*should* resume), and it should not ride along with a one-line containment fix.

⚠ Also noted, not changed: `serviceScope`'s `Job` is a plain one, not a `SupervisorJob`, so a
direct child's failure would cancel every session. No production code launches directly on it
today — only `InProcessProxy` (a harness) does — which is why this is a note rather than a fix.

### The zero-state sweep — the rest of the family

⚠⚠ **A surface that renders a zero-state must know whether it is loading, failed, or genuinely
empty — and a repository that folds `TResult.Failure` into `emptyList()` destroys that before any UI
branch can see it.** Drive's reads return `Flow<ListLoadState<T>>` (`:app-list-state`) at the
repository boundary, UI states carry a `ListLoadPhase` beside the plain list (SwiftUI reads those
lists straight off the shared view models), and Drive's banner has `SyncBannerState.Disconnected` —
⚠ any UI state derived only from proxy-read rows cannot otherwise tell "unreachable" from "not derived
yet". The paged listing has a *Try again* (`LazyPagingItems.retry()`), because Paging never retries an
error; reactive `flow()` reads retry themselves, so they get none. ⚠ A later failure keeps rows already
delivered on screen; only the phase changes.

**Still conflating**:

- `getFiles()` folds to empty — it feeds only iOS diagnostics and tests, no zero-state.
- **Fit**: `CategoryDetailViewModel` flattens at the last mile (its chart has no failure affordance),
  and the sleep / samples / medical / nutrition / cycle reads, whose `*State()` halves exist privately
  in `FitProjectionRepository` and need a surface that wants them.
- **Timeline / Maps** per-list zero-states. An unreachable Control is reported app-wide by the
  `ControlReachability` banner, but a read that fails while the proxy is reachable (an announce-gate
  refusal, a `NotFound`, a decode error) still renders as empty — the case that made Fit's Home and
  Browse say "No activity yet" and "Not set up" before they carried `ListLoadState`.
- **Home and Mia** separate loading from empty (`state.loaded`) but gate on `…isEmpty()` over a partly
  mock-backed data layer (`MockAutomationsRepository`, `HomeMockData`); the honest fix is bound up
  with making those repositories real.

⚠ Windowing a multi-tenant relation needs a predicate, not a global `take(N)`: Fit's `sampleHistory`
is `searchAll { metric equalTo … }.sortByDescending { time }.take(366)` (`fit-read-scaling.md`) — a
global window would hide a sparsely-sampled metric behind a densely-sampled one.

### A queued Device tombstone can revoke a device that has since been re-admitted

**Files**: `sync-relay-server/.../auth/DeviceRegistry.kt` (`revokeIfNotStale`),
`sync-relay-server/.../routing/SyncRouting.kt` (`POST /sync/push-tombstone`),
`sync-service-proxy/.../SyncScheduler.kt` (`drainPendingTombstones`).

A `Device`-targeted tombstone carrying `revokedDeviceId` deletes that device's relay registration, and
a retirement authored while a device is offline, unfunded (`402`) or unregistered (`401`) stays queued
across re-pairings, relay wipes and account changes — so it can land after a re-admission and silently
un-pair a device that has just paired (observed with an iOS identity that survived a reset in the
Keychain). Each stamped admission records the HLC its admitting client authored it at
(`device.admitted_hlc_*`, `admittedAtHlc`), and `DeviceRegistry.revokeIfNotStale` refuses a strictly
older `Device` tombstone. The tombstone is still stored and served, so peers converge as before, and
an absent stamp still revokes. Threat model T-K-02.

⚠ **Not closed for every route**: `recovery-claim` (`RecoveryRouting`) and the shared-realm
`membership-grant` register the device without an admission stamp, so a stale retirement still revokes
a device admitted through either.

⚠⚠ **Do not "fix" this by ignoring `revokedDeviceId` on the relay.** That revocation is a real
security control (T-K-02): without it a revoked device keeps posting to the mailbox until the tombstone
propagates everywhere. The bug class is a missing *ordering* check, not the revocation.

⚠ A re-admitted A1 holder repairs its own registration (`SyncScheduler.recoverRegistration`) only when
unlocked, since the repair signs with the master identity; a sealed device stays revoked and reports
`CONNECTIVITY_DEVICE_UNRECOGNIZED` until it is unlocked.

### ⚠⚠ A packaged desktop app is refused its own App Group container until it asks for it natively

**File**: `core-common/.../transport/AppGroupContainerClaim.kt`, `macos-helpers/src/main/swift/appgroup-claim.swift`,
`gradle/desktop-app-signing.gradle.kts` (`embedAppGroupClaim`).

macOS protects `~/Library/Group Containers/<TEAM>.app.tenshu.shared`. A development-signed app with
no provisioning profile gets in only after it has asked for the container through
`containerURL(forSecurityApplicationGroupIdentifier:)`. The JVM opens the path directly and never
asks, so until then every `open`/`mkdir` there fails with `EPERM`. ⚠⚠ On macOS 27.2 there is **no
dialog**: `tccd` answers `kTCCServiceSystemPolicyAppDataDetailed` with `authValue=0` at once, and the
symptom is Control dying at start (`SQLiteException: Error code: 14` opening `objects.db`), the apps
showing *"Control not running"*, and Photos losing every write (`FileNotFoundException`).
`tccutil reset SystemPolicyAppData <id>` changes nothing, because there is no stored decision.

**How it is lifted.** Every packaged app embeds `Contents/Helpers/appgroup-claim` (a small Swift tool)
and runs it once per process from `SocketPathResolver.appGroupContainer()`, the single path every
container access goes through. It is a helper, not an FFM call, because eight of the ten apps bundle
Java 21 (FFM is final only in 22). A dev run has no bundle and skips it. A packaged app without the
helper logs `AppGroupContainerClaim: … is missing`.

- ⚠ The grant goes to the process that **launched** the caller, not to the caller's own identity: a
  helper run from a shell gave the *shell* access, and Control's grant did not unblock Photos. So every
  app has to make the call itself or through a child process it spawns.
- ⚠ A provisioning profile is **not** needed. Two native stubs signed `app.tenshu.control` /
  `app.tenshu.photos` with no profile were enough.
- ⚠ `security find-identity` can list **two** certificates under one identity name, and
  `codesign --sign "<name>"` then fails as *ambiguous*. Sign by SHA-1
  (`-Ptenshu.signingIdentity=<hash>`) or remove the duplicate.

### A signed desktop build and an unsigned dev run have different data roots (2026-10-04)

**File**: `core-common/.../storage/DesktopAppPaths.kt`, `gradle/desktop-app-signing.gradle.kts`.

The root used to be a function of the launching environment (`TENSHU_TEAM_ID` exported or not), with
no record of which one was used last. It is now a function of **how the bundle was signed**:
`signDesktopApp` writes `-Dtenshu.ipc.teamId=<TEAM>` into the launcher cfg, so a packaged app always
resolves `~/Library/Group Containers/<TEAM>.app.tenshu.shared` — the only directory the sandboxed
Drive File Provider can reach. ⚠ The two roots are **fully separate**: the first-launch adoption
of `~/.tenshu` into the container (`LegacyDesktopRoot`, 2026-10-04) was removed before the
production reset, so a packaged build ignores `~/.tenshu` entirely and data never moves either way.

What is still true and still bites:

- ⚠⚠ **Every desktop app must be repackaged together.** A pinned Control and a client packaged
  before the pin look for `ipc.sock` in different directories; it presents as "the app is running
  but nothing connects".
- ⚠ **`./gradlew :x-desktop:run` is a separate root** (`~/.tenshu`) unless `TENSHU_TEAM_ID` is
  exported — and exporting it makes a *dev* run open the packaged app's container directly.
- ⚠ **Changing teams strands the data**: the container is named after the team, and nothing adopts
  another team's container.
- ⚠ Do **not** "fix" any of this by removing the App Group branch — it is load-bearing for the File
  Provider extension.

### ⚠⚠ "Pairing is broken" was schema skew — check `.serviceversions` FIRST (2026-08-24)

Reported symptom: after pairing a phone and a desktop into a fresh realm, the **phone's device list
showed four devices and the desktop's showed only itself**. It reads exactly like a pairing or
reactivity bug. It was neither.

The two devices were built from **different commits**. Parallel sessions land schema bumps
continuously, and a client reading blocks written by newer code is **fenced** — `SyncScheduler`'s
`pullAllowed` carries a `!schemaFenced` term, so a fenced device pulls **nothing, silently**. Nothing
on either screen says so.

Measured: desktop `identity=1 app/service=1 mia=3`, phone `identity=2 app/service=2 mia=4`. Rebuilding
the desktop flipped the skew the other way (`calendar` 5-vs-4, `file` 8-vs-7) because master had moved
again. Rebuilding **both from one commit** removed it, and the realm went from 11 blocks to 31 — the
phone, which had authored **zero**, immediately pushed 12.

### The check that should come first

```bash
# desktop (SQLite)                          # iOS (a file in the container)
sqlite3 objects.db 'SELECT * FROM schema_service_versions'
xcrun devicectl device copy from … --source Documents   # then read objects/.serviceversions
```

Diff them. Any differing shared service explains a silent one-way sync, and no amount of staring at
queues, gates or the relay will.

⚠⚠ **This cost a long afternoon.** The trail went relay → stores → outbound queues → `StorageSyncGate`
→ the entitlement → `pullAllowed`, and only the last step surfaced the fence. `HR #9` already says
clients move across a bump **in lockstep**; nothing *enforces* lockstep between two of a developer's
own devices, and a fenced device is still *reachable* (`core-schema-skew-surface.md`). Treat *any*
asymmetric-sync report as skew until the versions are compared.

### The post-Ph1 surface sweep — five defects, and where the class actually ended

When a security **default** moves, surfaces built against the old one go stale in ways no gate
catches — every one compiles and renders. `control-keep-device-unlocked.md` Ph1 moved the default from
*passphrase-floor* to *headless* (and iOS has no hard mode), and one afternoon of ordinary use found
six such defects, all fixed: the recovery phrase unreachable on iOS by any route; "Unlock with Face ID"
and "Lock now" offered on a headless realm, where they can only fail; the notification permission
requested at first launch (the prompt is one-shot); and two setup strings — one ⚠⚠ telling users the
unlock passphrase is "the only way back into your data" when the recovery phrase is.

The sweep then checked four dimensions and found everything else correct or correctly gated:
user-facing copy asserting a passphrase exists; controls gated on hardware rather than realm state;
placeholders and "coming soon" that outlived their phase; comments promising a future phase would
wire something already shipped. ⚠ The class is a burst caused by one default moving, not a standing
pattern: re-run those four checks when a *default* moves again, not speculatively.

### ⚠⚠ Don't "clean up" the admission epoch — three separate traps sit on it (2026-08-21)

The realm epoch looks like vestigial bookkeeping and is not. It has exactly **three** couplings, and
each has already produced or nearly produced an incident. Full reasoning in
[`admission-revocation-names-the-capability.md`](proposals/admission-revocation-names-the-capability.md)
§7b.1, §7e, §7g; this is the short "before you touch it" list.

**Where it is connected — the whole surface, in three places:**

| | |
|---|---|
| Derived | `SyncProxyInitialization.kt:495` — `tombstoneCountForType(Device.ID)`, i.e. a **count of locally-observed Device tombstones**, floored by the persisted `seededRealmEpoch` |
| Stamped | `Device.admittedAtEpoch`, written from `syncManager.currentRealmEpoch` at 6 sites (`CreateRealm`, `ApprovePairingOffer`, `ApprovePairingRequest`, `RecoveryClaim`, `PairingKeyInstaller`, `SyncProxyInitialization`) |
| Read | `SyncProcessor.isStaleDeviceBlock()` (`SyncProcessor.kt:665`) — **T-K-02**, drops a full-entity `Device` block whose stamped epoch is strictly below the receiver's. This is the *only* consumer. |

**Trap 1 — the seed is not cosmetic.** `seededRealmEpoch` is persisted (F5a); while it was in-memory a
restart dropped it, so the next admission minted *below* the revocation naming it: a live **403** on
an ordinary pairing. ⚠ It cannot be deleted "because F1′ made revocations key-scoped" — it also
feeds `Device.admittedAtEpoch`, which every peer enforces at merge (§7e).

**Trap 2 — the `strips()` epoch fallback cannot be removed (F5b, §7g).** Measured in production:
**7 of 7** admission revocations are keyless and **3 of 4** live devices are named by one. F0
captures a key at *admission* and never retroactively keys revocations already written, so this
population never drains. Removing the fallback fails **open** (a pre-F0 revoked device can admit
again — T-K-01 restored); the set-membership alternative fails **closed** (those 3 devices could
never admit a new device again). Both directions are pinned by guards in `KeyScopedRevocationTest`
— if you are here because one went red, that is the guard working.

**Trap 3 — ⚠ HLC is not a drop-in for it (F5c).** The epoch is *not* a clock: it counts an
**observed event class** — `tombstoneCountForType(Device.ID)`, the number of *distinct `Device`
entities a tombstone has been seen for*, i.e. **removed devices** (plain local deletes are excluded
on purpose; a delete is not a revocation). Ignorance therefore keeps it low and stale writes get
dropped — **fail-closed**. An HLC advances from physical time regardless of what the writer has
seen, so `hlc >= revocationHlc` does **not** imply "had seen the revocation". Swapping it in
converts a knowledge test into a recency test and inverts the failure direction in exactly the race
T-K-02 guards.

⭐ What a `Device`-tombstone **HLC max** would buy is narrower than "replace the epoch": the count is
a *cardinality*, not a *position*. A device that merged tombstones 1 and 3 reports **2** — numerically
identical to a device that merged 1 and 2, despite different knowledge. A max names the position.

⚠⚠ **It would not remove `seededRealmEpoch`, and an earlier revision of this entry claimed it
would — that was wrong.** The seed does not exist because a count is fragile to re-observation (it
is not: it is keyed on distinct entities and rebuilt from the persisted store, so re-merging the
same tombstone cannot inflate it). It exists because a device can legitimately **know** its position
without **holding** the evidence: a freshly paired device has zero tombstones locally and fetches the
epoch from the relay (`PairingKeyInstaller`), recovery does the same (`RecoveryClaim`), and the
revoker seeds `epoch + 1` before its own tombstone has drained (`RevokeDeviceImplementation`). A max
would need that same out-of-band channel, so Trap 1 survives the change intact.

⚠ And it is a wire change to `Device` (House Rule #9) with a mixed-fleet window — a design slice,
not a cleanup.

⚠ Not an adversary control either way: `admittedAtEpoch` is authenticated but not *proven*, and
future-epoch blocks are accepted on purpose. T-K-02 is a **race** guard.

### The host↔joiner pairing flow has four silent stalls — observed end-to-end (2026-08-17)

**Files**: `sync-service-proxy/.../impl/GetPendingPairingRequestsImplementation.kt`,
`control-shared-logic/.../ui/screens/sync/PairingWizardViewModel.kt`,
`control-shared/.../ui/screens/sync/PairingWizardScreen.kt`,
`control-shared/.../lifecycle/DesktopControlSettings.kt`.

**Source**: a real desktop↔iPhone re-pairing attempt after a relay wipe, with the relay log and both
clients observed simultaneously. None of these is caused by the relay-amnesia work of the same day;
they are what that work's on-device testing walked into. All four share one shape — **a state the
software knows about and the user cannot see** — which is why they are recorded together.

**The observed sequence.** Desktop (joiner) scans the iPhone's (host) code:

```
17:52:20  200 OK: POST - /sync/pairing/request      ← joiner's request lands in the mailbox
          200 OK: GET  - /sync/pairing/requests ×N  ← host fetches it, authenticated, every 2s
          404 Not Found: GET - /sync/pairing/response ← joiner waits… forever
```
The host **never surfaces an approval prompt** and logs nothing; the iPhone sits on its QR screen
while the joiner spins on *"Confirm on your other device"*.

1. **A non-matching pairing request is discarded in silence.** `GetPendingPairingRequestsImplementation`
   keeps only requests whose `pairingSecretHash` equals `activePairingContext.secret`, and drops the
   rest with no log, no counter and nothing on either screen — as does the `activeContext == null`
   branch, which discards *every* request. A host that is visibly showing a QR therefore ignores a
   request sitting in front of it. ⚠ In the observed case the host had a live context (its QR was on
   screen), so this was the **mismatch** branch: the joiner scanned a code whose secret the host had
   since replaced. Nothing tells the user their code is stale — which, with the 2-minute session
   window, is easy to hit. Cheapest honest fix: log the drop with both hashes, and surface
   "a device tried to pair with an old code — show a new one" on the host.
2. **`isLoading` survives navigation, so one abandoned attempt disables the next.** `confirmPairing`
   sets it true and only clears it on completion; neither `onSelectScan()` nor the scan handler
   resets it. After stall (1) the user returns to Scan, re-scans, and the *Name this device* Continue
   button is `enabled = name.isNotBlank() && !isLoading` → permanently disabled, labelled
   "Registering…". Indistinguishable from a frozen screen, and unrecoverable without restarting the
   app. ⚠ Two lines to fix, and it is what turned one stall into "the app is broken".
3. **The joiner's wait has no timeout and no cancel.** `GET /sync/pairing/response` polls
   indefinitely when approval never comes (the host may have filtered the request, denied out of
   band, or gone away). The host session self-destructs after 2 minutes; the joiner's does not, so
   the two sides disagree about whether the ceremony is still alive.
4. **The desktop's "Close to menu bar" setting does not persist.**
   `~/.tenshu/control.settings.properties` contained only `startAtLoginDefaultApplied=true`, with no
   `closeToTray` key and a timestamp hours older than the user's toggle — so `setCloseToTray` never
   landed. The read defaults to `true` (hide to tray), yet closing the window quit the app and took
   the tray icon with it, which the default alone cannot explain. ⚠ Two candidates, and they need
   separating before either is fixed: the write silently failing, or writer and reader resolving
   `DesktopAppPaths.sharedFile(...)` to **different roots** (`$TENSHU_DATA_DIR` → App-Group
   container → `~/.tenshu`), which would also mean the packaged app and a terminal-launched one
   disagree about every desktop setting.

⚠⚠ **Do not fix (1) by surfacing every request.** The SAS is the ceremony's MITM defence and the
`mapNotNull` that drops SAS-underivable requests is deliberate (see the KDoc there) — the fix is to
make the *silence* visible, not to admit unverifiable peers.

### The pairing bring-up can fail its relay poll — one observation, NOT reproduced

**File**: `system-test-utils/.../SharedRealmFileReadSystemTest.kt` (`pairHostAndJoiner`),
`sync-service-proxy/.../impl/GetPendingPairingRequestsImplementation.kt`.

⚠ **An open lead, not a diagnosis.** Once, in 1 of 16 runs, `getPendingPairingRequests()` returned a
`TResult.Failure` inside `pairHostAndJoiner`. The op returns Success-with-empty-list both for "no
realm-bound owner" and for "no requests", so the `Failure` was neither an empty result nor a
pairing-logic race. Two paths produce one:

1. **The relay poll failing** — `syncRelay.getPairingRequests(ownerId)`, which the op deliberately
   refuses to swallow (a 401 because the relay lost this device's registration must not look identical
   to no pending requests). `PairingPollFailureSystemTest` pins that distinction — relay up and no
   joiner ⇒ an empty `Success`, relay stopped underneath the host ⇒ a typed `Failure` — so it is not
   "simplified" away later.
2. **The per-request enrichment throwing.** Each request's name and SAS are now derived independently
   and an underivable one is dropped and logged (`MalformedPairingRequestSystemTest`, threat model
   T-P-13: `POST /sync/pairing/request` is necessarily unauthenticated). The harness's joiners send
   well-formed keys, so that was not this trigger.

**Not reproduced** across ~130 pairings: the test alone ×24; the whole `:system-test-utils` + `:hub`
suites ×8 under 4 CPU burners; 80 pairings looped inside one JVM with a fresh `TestSystem` each; 30
run concurrently (5 at a time × 6 rounds) with every previous system left alive. That rules out
repetition, accumulated per-JVM state, simultaneous pairings and background load from live neighbours.
⚠ §`:system-test-utils` — Residual Suite-Ordering Flake is a *bucket of distinct issues*, not one bug.

`pairHostAndJoiner` asserts and prints every step (`bootstrapAsHost`, `startPairing`,
`initiatePairing`, the poll with its typed error, the pending count, `approvePairingRequest`), so the
next occurrence names its step. **Look first** at the relay response behind the poll (a 401 points at
device registration / `KnownDevicesStore` vs the JWT the host presents), then at what ran alongside it
in the same JVM.

### SKIE ⨯ Kotlin version pin

**File**: root `build.gradle.kts` ("iOS app frameworks" block) + `gradle/libs.versions.toml`
(`skie = "0.10.15"`, `kotlin = "2.4.20"`)

Every iOS app framework (`:tenshu-sdk-ios-<app>`) applies **SKIE** (Touchlab), from the root build, to
render the exported Kotlin surface as idiomatic Swift (`Flow`→`AsyncSequence`,
`suspend`→`async`/`await`, sealed→enum) — proposal §9.5. SKIE is a **Kotlin-compiler-version-coupled**
plugin (the same fragility class the libsodium cinterop was hand-rolled to avoid): each SKIE release
supports a specific set of Kotlin patch versions. Headroom today is **zero** — SKIE 0.10.15 covers
Kotlin 2.4.20, the repo is on 2.4.20, and 2.4.21 is not covered.

**Therefore: gate every Kotlin bump on SKIE support.** ⚠ Read the exact list in SKIE's
`gradle.properties` (`versionSupport.kotlin`, e.g. `2.4.0(2.4.10), 2.4.20`), not the changelog's
"supports 2.4.x" headline (<https://skie.touchlab.co/changelog>). If no SKIE release covers the target
Kotlin, hold the bump or temporarily drop SKIE (see below). A bump SKIE allows still owes its own pass:
K/N compile + link, the SKIE-generated Swift surface (`scripts/ios-smoke.sh`), and wasm/js.

Lock-in is **low**: SKIE applies only at the `:tenshu-sdk-ios-<app>` frameworks and touches no shared logic. If it ever
blocks a critical Kotlin upgrade, it can be removed (delete the plugin + the `skie {}` block) — the
framework still builds, Swift consumers just get the raw K/N-generated Obj-C interop (no
`AsyncSequence`/enum sugar) until SKIE catches up. Analytics upload is disabled (`skie { analytics
{ enabled.set(false) } }`) — required for the zero-trust posture (House Rule #1).

**Revisit when**: bumping Kotlin (mandatory check), or if SKIE is dropped for a different bridge.

### Drive-on-iOS XCUITests silently depend on simulator hygiene

**Files**: `drive-ios/UITests/SharingClusterUITests.swift`, `drive-ios/Sources/RootView.swift`.

`RootView` routes on `DriveLocalRuntime.isProvisioned()` *before* the `#if DEV_HARNESS`
dev-stub fallback. A simulator carrying leftover route-1 key material (from any earlier
Control-on-sim run) therefore installs the real, empty in-process runtime, the
`FakeFilesRepository` seed is never reached, and every seeded-data test fails with
`Seeded Photos folder missing` — a failure that reads like a UI regression and is not one.
The invite test fails identically.

Workaround: run the suite on a fresh simulator (`xcrun simctl create`), not a reused one.
Fix worth making: have the harness prefer the stub path when `DRIVE_DEV_PROVISION_CONTROL=1`
is set, so the suite stops depending on the state of whatever sim it lands on.

### 12 Obj-C name collisions sit unmanaged in the shipped iOS framework

**Files**: each `tenshu-sdk-ios-<app>/build.gradle.kts` (the `export(...)` list) and the root
`iosAppFrameworkBaseExports`, and every `*-shared-logic` module they export.

Kotlin/Native resolves an Obj-C name clash by appending `_` to whichever **fully-qualified name sorts
later**. Nothing warns. So adding one `export(...)` can silently **rename a type a Swift shell already
ships** — exporting `:maps-shared-logic` into the shared framework once renamed the shipped
`app.tenshu.timeline.model.Place` → `Place_` (maps sorts before timeline). Per-app frameworks
(`ios-per-app-frameworks.md` P4/P5) removed every cross-app clash; what remains is 3 or 4 per
framework, inside the base exports and Drive's own (`CoreCommonModuleKt`, `Get`,
`NativeReferenceDataDto`, `DrivePreferences`). They work only because each shell references whichever
name it was handed: ⚠ the mapping is **positional on sort order**, so it can flip under an unrelated
package rename or module addition, and a same-shaped clash would compile and be wrong.

**Rule (D12, `ios-fit-timeline-maps-parity.md`)**: the **newcomer** takes the qualified name via
`@kotlin.native.ObjCName`, never the incumbent. ⚠ A nested member's `ObjCName` is *appended to its
parent's* — annotate the member `"Loaded"`, not `"MapsPlaceDetailUiStateLoaded"`. Before exporting a
module run `scripts/check-objc-clashes.py --add :<module>` (exit 1 on a clash involving it); it is a
lexical scan that misses nested classes, so `xcodebuild` **every** iOS shell afterwards
(`scripts/ios-smoke.sh --build-only`), not just the one you touched. ⚠ When reading the generated
header, check its mtime first — a cached `BUILD SUCCESSFUL in 9s` can serve a stale one.

**Open**: the existing clashes are unmanaged (renaming one now would itself break a shell), and the
script is opt-in — nothing in a gate runs it.

### `androidMain` Source Sets Are Never Compiled in CI

**Files**: any `*/src/androidMain/...` — concretely the Android-only operation impls under
`file-service-proxy/src/androidMain/.../proxy/file/` (`AndroidFileUploadImplementation`,
`AndroidFileReplaceImplementation`, `AndroidGetPreviewImplementation`) and
`sync-service-proxy/src/androidMain/.../SyncWorker.kt`.

`desktopTest` / `jvmTest` never compile a module's `androidMain` source set, so a change to a
`commonMain` interface that every implementor must follow compiles clean and passes the JVM/desktop
tests while silently leaving `androidMain` impls broken. `.github/workflows/android.yml` runs
`./gradlew check`, which does compile them, but CI runs only intermittently
([CI blocked by GitHub Actions billing](#ci-blocked-by-github-actions-billing)); locally the smoke gate
(`scripts/android-smoke.sh`) builds only the app graphs it installs. It has bitten across **two**
modules at once — the error-channel Shape A flip of `OperationImplementation.execute(...)` from
`TResult<T>` to `T` broke `:file-service-proxy`'s three Android impls and `:sync-service-proxy`'s
`SyncWorker`, and the first repair stopped at the first module. Hence: **sweep *all* modules**, not the
obvious one.

**Mitigation**: when changing a `commonMain` `OperationImplementation` (or any `expect`/interface with
platform-specific implementors), compile **every** `androidMain` source set in the same change.
`compileAndroidMain` needs only the Android SDK, not a device:

```bash
# enumerate the modules that have an androidMain, then compile them all
mods=$(find . -type d -path '*/src/androidMain' -not -path '*/.claude/*' \
  | sed -E 's#/src/androidMain##; s#^\./##' | sort -u \
  | sed 's#^#:#; s#$#:compileAndroidMain#')
./gradlew $mods
```

**Proposed fix**: a local compile-only Android lane in `scripts/run-gate.sh` — `compileAndroidMain`
(and `compileDebugKotlinAndroid` where applicable) across the proxy/service modules, the same shape as
its JS and iOS lanes.

The platform-lane traps that sit beside this one:

  ⚠⚠ **The iOS lane is local-only too.** `.github/workflows/ios.yml` compiles + runs
  `iosSimulatorArm64Test` on a macOS runner, but it is subject to the same CI outage: run
  `./gradlew iosSimulatorArm64Test` locally on anything touching `commonMain`. 12 `:fit-shared-data`
  iOS tests once sat red for three days with the gate green-by-absence.

  ⚠⚠ **Never pin a generic type argument to a non-null type when the value may be null on a
  Kotlin/Native path.** The JVM erases generics, so `foo<Data>(…)` returning `null` is a silent
  no-op there; K/N emits a *checked* `kotlin.native.internal.downcast` and throws
  `NullPointerException` with a **null message** — which `LocalTaskExecutor`'s catch-all renders
  as a bare `tenshu/core/error/internal`. The archetype: `ExecutionContext.execute`'s async
  branch dispatched `implementation.execute<Data>(…)`, so **every void async op failed on iOS
  route-1** while passing on JVM/Android; the writes never landed and the reads that followed
  were *correctly* empty, which presents as a sync bug and is not one. Fixed by `execute<Data?>`.
  The cast was latent for a long time and only materialised when a refactor wrapped the call in a
  `try/catch`, so "I only added error handling" is not a reason to skip the iOS run.

  ⚠ **A read layer that folds a failure into an empty collection must name the throwable
  first.** Rendering empty is usually right (a dead proxy must not blank-screen the app) but it
  is never a *diagnosis*: empty is indistinguishable, to the screen and to a test, from "the
  store holds nothing" — which is what let the K/N NPE above read as a sync bug for three days.
  Two rules where a `.catch`/`runCatching` guards a **transport** read: rethrow
  `CancellationException` (Flow's `catch` does not exempt it, so swallowing it breaks structured
  concurrency), and log the cause before folding. Audited and closed 2026-07-28 across
  `FitProjectionRepository`, `PlaceProjectionRepository` (Maps), `ClientDeviceSource` +
  `ClientHubStatusSource` (Home). ⚠ The Home pair was the sharp case: empty is that
  repository's *fall-back-to-mock* signal, so a failed read rendered plausible mock devices
  rather than nothing at all. Deliberately **not** in this class and left alone: per-row
  field-shape tolerances on an already-successful read (`TimelineProjectionRepository`'s
  `sourcesOf`/`membershipOf` — "field absent on an old peer" is designed forward-compat), local
  parse/decode fallbacks (`MvtDecoder`, `DefaultRegionStore`), and the VM-level
  `.catch { /* keep last-known */ }` sites, which preserve state instead of fabricating an empty.

  ✅ **Swept repo-wide 2026-09-22, and the guard now has one home.** The 2026-07-28 pass covered
  the read layer; the same defect was everywhere a `runCatching` wrapped a **suspend** call, which
  is a wider set than a transport read — ~130 sites, from the reclamation notes a delete files to
  a view model's `onFailure` banner. `:core-common`'s `util/CancellationGuards.kt` carries the two
  shapes: `runCatchingCancellable` (a drop-in `runCatching` that rethrows `CancellationException`)
  and `bestEffort(what) { … }` (absorbs and *names* a failure, rethrows a cancellation) for the
  sites that discard the `Result`. ⚠⚠ **Neither is a blanket replacement for `runCatching`**: around
  a block that cannot suspend there is no cancellation to catch, and ~270 such sites were audited
  and deliberately left alone. ⚠ Two deliberate teardown guards are also untouched, because they
  already run under `NonCancellable` and there is nothing there to rethrow: `BulkWriteTask` and
  `RealmDataClient`'s `task.cancel()`, and `SqliteObjectStore.close()`.
  ⚠⚠ **Nothing enforces the rule, and new violations land at roughly one a week.** Twelve new
  `runCatching` sites reached `commonMain` in the eight days after the sweep and two of them were
  in scope (`SyncObserver.declareResolvedHolder`, `ModelsViewModel.observeCoverage`) — both caught
  only by re-auditing the delta at merge time, not by any gate. Audit the diff when you rebase.

### Dev relay must run from the fat jar, not Gradle `:run`

**File**: `sync-relay-server/build.gradle.kts` (the `ktor { fatJar { … } }` block)

Running the dev relay via `./gradlew :sync-relay-server:run` leaves it consuming the **project-dependency
jars on disk** (`core-common/build/libs/core-common-desktop.jar`, etc.) rather than a self-contained
artifact. If any shared module is **recompiled while the relay JVM is alive** — e.g. launching
`:control-desktop:run`, or any Gradle invocation after a `git` checkout that touched `:core-common` —
Gradle rewrites those jars underneath the running relay. The JVM's cached central-directory view of the
overwritten jar goes stale, so **classes not yet lazily loaded** fail with `NoClassDefFoundError` at
first use. Observed 2026-06-24 during the first on-device web-pairing pass: `/sync/pairing/requests`
returned `500` (`NoClassDefFoundError: …/TListDto`, then `…/Token$ByteArrayToken`) — but only once the
pairing mailbox was **non-empty**, because empty-list serialization never reaches those classes. Restarting
the relay "fixed" it only until the next shared-module rebuild.

**Workaround (use this for any multi-process on-device session)**: build once and run the self-contained
artifact, which is immune to later rebuilds:

```bash
./gradlew :sync-relay-server:buildFatJar
DEV_MODE=true java -jar sync-relay-server/build/libs/sync-relay-server-all.jar   # JVM 21
```

⚠ `DEV_MODE=true` is required here: `RelayConfig.fromEnv` fails closed (unset = no `X-Dev-Owner`
bypass, `relay-metadata-minimisation.md` D13), and only the Gradle `:run` task supplies it for you.

(The same hazard applies in reverse to `:control-desktop:run` and any other long-lived Gradle `:run`
process sharing `:core-common`; they just hit it less often because they load most classes eagerly at
startup.)

**Proposed fix**: document the fat-jar invocation as *the* way to run the dev relay (a `how-to` or a
`./scripts/run-relay.sh` wrapper), and/or have `:run` depend on an exploded/shaded runtime so it doesn't
read jars that other tasks overwrite. Low effort; pure dev-ergonomics — production already deploys the
fat jar.

**Revisit when**: anyone runs the relay locally alongside Control/Drive desktop, or hits an
inexplicable relay `NoClassDefFoundError`/`500` mid-session.
### Control's UI thread can starve behind the store lock on a large realm (2026-07-29)

One ANR observed on device: input dispatch timed out on Control's own `MainActivity` while Fit
(133 % CPU) and Timeline (46 %) hammered the proxy over a ~10.5k-workout store. Control itself was
**below 3 % CPU with its main thread in `futex_wait`** — blocked on a lock, not computing — with
RSS ≈ 500 MB and ~109 MB swapped. Prime suspect is `DatabaseImpl`'s single `lock.withLock`:
`addToArray` rewrites a relation's entire member-hash list per add (O(N) on a 10k-member array) and
`getArrayData` materialises under the same lock, so a burst of client reads can convoy the UI
thread behind them. Not reproduced under a deliberate three-app stress, so it needs load plus
memory pressure; treat as a scaling smell, not a hang. Related: `bounded-store-cache.md`.

⚠⚠ **Read/write separation was tried and reverted — the substrate is not thread-safe (2026-07-29).**
The diagnosis above points at shared readers: the long critical sections here are *reads*
(`getArrayData` materialises a whole relation under the lock, and every reactive re-evaluation goes
through it), while writes are short. A `ReentrantReadWriteLock` behind `DatabaseLock`
(`withReadLock`/`withWriteLock`, each `DatabaseImpl` method classified, the hash → `DataId` cache
moved to an independent guard because *reads populate it* and there is no lock upgrade) passed the
full JVM suite, the concurrency tests, and the emulator smoke gate.

On device it **SIGSEGV'd within a minute**: `libsqliteJni.so` … `nativePrepare`, fault address
holding ASCII — native memory corruption. `SqliteObjectStore` uses an `androidx.sqlite` bundled
connection, which is **not thread-safe**, and it was relying on `DatabaseImpl`'s exclusive lock to
serialise every access. Letting two readers in at once is enough to corrupt it.

So the ordering is: **make the store layer safe for concurrent readers first, then split the lock.**
The likely shape is an internal mutex inside `SqliteObjectStore` around connection use (or a
per-thread/pooled read connection), which still buys the win — the expensive part of a read is the
deserialisation above `getObject`, which would then run in parallel while only the SQLite calls
serialise. Point-in-time consistency is unaffected either way, since a writer still excludes all
readers. ⚠ Assume nothing else below `DatabaseImpl` is thread-safe until checked: `DbEntryMapper`
and the resident caches were all implicitly protected by the same exclusive lock.

⚠⚠ **An ANR in Control is self-obscuring.** ART crashed inside its own unwinder while dumping the
process (`DumpNativeStack` → `GetOatQuickMethodHeader` → `Runtime::Abort`), so the dropbox trace
contains every *other* process and **not Control's own stack** — the ANR presents as a native
SIGABRT with no Java frames. When diagnosing one, read the header instead: `dumpsys dropbox --print
data_app_anr` still carries the CPU-usage breakdown, RSS/swap, and the per-thread *waiting channels*
(`futex_wait` vs running), which is what identified this as contention rather than compute.

### Every client session runs its own `SyncScheduler`, and they all write the same single-row status relations (2026-08-02)

**One scheduler per process, by election** — `SyncRuntimeHolder` (`drive-upload-progress.md` §7),
pinned by `SyncRuntimeElectionTest`; the election runs under a lock, because sessions rebinding at
once after a Control restart otherwise each build a runtime. What remains is §7's *"the runtime still dies
with the session that elected it"*: no platform passes `processScope` to `initSyncService`, so when
the electing app disconnects no scheduler runs until the next session elects a new one — and the
surviving sessions' observers still hold the DEAD runtime's `wakeSignal`, `emitKeyLocks` and
`tombstoneCascades`, so their enqueues stop waking the new scheduler and their emits no longer
contend with its drain (the duplicate-upload class, reopened for those sessions).

The single-row derived relations the runtime publishes — `SyncHealthStatus`, `SchemaFenceStatus`
(E2d-2) and `ContentTransferStatus` (`sync-metered-plane-split.md` §8) — are last-writer-wins, and a
**freshly constructed holder starts at `SyncHealthFigures()` defaults** (`online = true`,
`deferred = false`, counts 0), which `initSyncHealth` publishes immediately on collect. Each
re-election therefore overwrites a correct verdict until the new runtime's first tick corrects it.

⚠ Treat every single-row derived signal as **advisory**, and do not build a consumer that assumes
monotonic or coherent transitions on one. Any new derived row inherits this.

⚠ Diagnosing a flickering banner: `DeferredStateLog` prints once per instance per *transition*, so
repeated identical lines with no intervening "cleared" mean distinct runtime instances.

### ⚠ A `:core` version bump with no registered migration chain wipes every store (2026-08-02)

`coreReadableFloor()` returns `currentVersion` when **no** `schemaMigration(CORE_SERVICE_ID, …)` is
registered, so a store recorded at the previous version falls outside `[floor, current]` and both
`SqliteObjectStore` and the iOS `SharedReplicaStore` delete everything. (The iOS store binds the
registry's chains, `RegistryReplicaMigrationSteps`, and floors each version where its chain reaches,
exactly as the JVM does — `ios-shared-store-sqlite.md` B.24 — so the rule below holds on iOS too.)
At the 1.0.1 baseline there is **no chain registered anywhere** in the tree: every version is 1.

**Register an empty `schemaMigration(CORE_SERVICE_ID, fromVersion = <previous>)` step alongside any
core bump that does not genuinely reshape stored rows.** The floor then walks back one version, the
recorded version is reachable, and the store migrates instead of wiping. Cheap, and arguably the
default. ⚠ Only for bumps that *don't* reshape — a real reshape still needs real migration steps.
The worked example is pre-release fmt 19: it added a local-only collection, reshaped nothing, and
still wiped every device — ~1 h of desktop re-sync, repeated iOS launch-watchdog kills, and a long
false-alarm investigation into a file that was never actually lost.

⚠⚠ **A `:core` step that reshapes rows is honoured on iOS and not on the JVM** (found 2026-11-05,
B.24). `coreReadableFloor()` reads the core chain, but `StoreSchemaMigrator.migrateStoreAtOpen` walks
`serviceSchemas()` only, which `:core` is not in: on `SqliteObjectStore` (and JS's
`InMemoryStoreMigration`) a `schemaMigration(CORE_SERVICE_ID, …)` with `CompositeMigration`s lowers
the floor, the store is judged reachable, `user_version` is re-stamped, and **no row is rewritten**:
old-shaped core rows read by new code. The iOS chunked migrator addresses `:core` like any service
and does run them. Harmless while every core step is empty (the only kind registered so far); the
first core step that reshapes a row owes the JVM walk a core pass, and a test that a core row is
rewritten on `SqliteObjectStore`.
⭐ *Guarded by `CoreStepsReshapeNothingTest` (`:system-test-utils`) fails while any core step
over the real graph carries `migrations` or `droppedProperties`, and names this entry.*

### ⚠ iOS replica: a failing migration step wipes on its 3rd counted run, silently until then (2026-11-05)

A step that throws on a stored row is counted once a run, and at the third (`migrationRowAttempts`,
`ios-shared-store-sqlite.md` B.3 (41)/(53), B.24 (450)) the job becomes a wipe, taking the phone's
unpushed outbound changes with it. The JVM never wipes for this; it fails the open on every launch.
Accepted as a trade (B.24 (450)). **Owed: a diagnostic signal at count 1** (the run that counts first),
so the defect is visible before the third run destroys the tail, not only in the wipe's log line.

### ⚠ iOS replica: a retired service leaves every iOS store on "update the app" (2026-11-05)

`classify` (`ReplicaClassification.kt`, `factsOf`, ~line 200) counts a service **recorded** in
`schema_service_versions` but **unknown** to this build as "above this build": `Skew`, refused, the
update surface. The JVM ignores such a service (`StoreSchemaMigrator` walks declared services only;
`StoreSchemaMigrations.readableFloorOf` floors an unknown one at 0). So after release, a build that
**retires a service** (drops its `ServiceSchema`) leaves every iOS store that ever stamped it fenced
`Skew` for good, with no newer build to update to. Before the first retirement ships, iOS needs a
rule for a recorded-but-retired service (a tombstone list of retired ids the build declares, so
`classify` can tell "retired" from "newer"), or the retiring build must keep declaring the service.

### Two backup buckets, one key: the isolation is filed, not enforced (2026-09-14) — FIX PREPARED 2026-10-23, NOT APPLIED

**What happens.** The relay's snapshots and the commerce database's dumps go to different Scaleway
buckets — `tenshu-relay-db-backups` / `tenshu-relay-blobs-mirror` and `tenshu-commerce-db-backups` —
because commerce dumps carry account, session and billing rows. ⚠⚠ **It does not currently buy that.**
Every bucket is in one Project, and the *same* admin key pair is in both boxes' `deploy.env` (as
`BACKUP_S3_*`, and on the relay box also as the content plane's `S3_*`). A compromised box can delete
every snapshot, every dump and the mirror. Treat them as one blast radius until the fix below is
**applied** — do not cite the split as a mitigation before then.

**The fix, prepared (2026-10-23):** [`deploy/scaleway-backup-access`](../deploy/scaleway-backup-access)
mints one IAM application + key per job (`relay-backup`, `commerce-backup`, `blob-mirror`) and one for
the relay's content plane (`relay-content`), plus bucket policies naming only the admins and the writing
job. The rollout is the ordered, checked runbook in
[`back-up-the-relay-database.md`](how-to/back-up-the-relay-database.md#applying-the-backup-hardening).
It ships with the second region (nl-ams, Object-Locked snapshot copies) and the One Zone mirror.

⚠⚠ **What Scaleway can and cannot express** — the design rests on it, and the 2026-09-14 version of this
entry had the bucket-policy half backwards ("restricting one bucket means denying that principal on
every other bucket"):

- IAM permission sets scope to a **Project**. `ObjectStorageObjectsWrite` without `…Delete` is
  expressible, so the two backup writers get **no delete set at all** — they cannot delete anything in
  the Project, whatever else is true.
- A bucket policy **narrows** IAM (a principal needs both), and a bucket with a policy is closed to every
  principal it does not name (Organization owner excepted). `Deny` is a no-op under policy version
  `2023-04-17`, so the module writes only `Allow`s, and the decaying "deny everyone on every other bucket"
  invariant is not needed. ⚠ Read from documentation summaries on 2026-10-23, not from the pages; the
  runbook's `probe-keys.py` is the live verification.
- `s3:PutObject` cannot tell an overwrite from a new write. *Write but never destroy* needs versioning +
  Object Lock, which is why the nl-ams snapshot buckets are created locked; the existing fr-par buckets
  cannot be (lock is creation-time) and stay overwritable by their writer.

**What stays open after the runbook:**

- ⚠ **Unpoliced buckets are IAM-only.** Until step 12 polices the content bucket, every key reaches it
  with its project-wide sets — the commerce key could overwrite blobs, the mirror key delete them.
- ⚠ **The relay-box split is nominal until step 10** rotates the content plane off the admin key: that
  key in `S3_*` reaches everything.
- Pruning the nl-ams blob mirror has no box-side path (its key withholds delete there by design) —
  open decision D3 in the how-to.
- A fully compromised relay box can still delete content blobs (the sweep needs it) and the fr-par
  mirror, and overwrite fr-par snapshots and the nl-ams mirror. It cannot remove an nl-ams snapshot
  version within 30 days or touch commerce's buckets.

### The content mirror never deletes, but it does overwrite — a bad write reaches it within a week (2026-09-14)

> ⭐ **A variant of fix 1 is built.** On a size change `mirror-blobs.py` first saves
> the mirror's copy to `_tenshu-mirror/prior/<key>` — once; the first prior is never replaced — then
> overwrites and warns (`SIZE CHANGED`, exit 0); if the prior cannot be saved it does not overwrite
> (exit 9). Snapshot and content keys are indistinguishable by key (all `<ownerId>/blob-<hex>`), so
> both take this path. ⚠ What remains: the mirror's **main** key now follows a corrupt write within a
> week; recovery is from `prior/` (`back-up-the-relay-database.md`, *Restoring from the mirror*), and
> a same-length corrupt overwrite is still invisible to a size compare.

**What happens.** `deploy/scaleway-relay-backups/mirror-blobs.py` keeps `tenshu-relay-blobs-mirror` as
an "additive-only" copy of the content plane, run weekly by `tenshu-blob-mirror.timer` (Sundays 04:40
UTC). It guards against one failure, a **delete**: keys the source loses are kept. It does not guard
against an **overwrite**. A key is copied whenever the mirror's size differs from the source's
(`todo = [k … if mirror.get(k) != n]`), and `CopyObject` replaces the mirror's object in place, with no
versioning on the bucket. So a key rewritten with bytes of a different length has its previous
contents in **neither** bucket after the next run.

⚠⚠ **The premise that made this look safe is not enforced.** `deploy/scaleway-relay-blobs/main.tf`
skips versioning because a re-PUT "writes identical bytes to the same key". Two things break that:

- **A legitimate re-seal.** Slice snapshots use a deterministic ref,
  `sha256("snapshot:$realmId:$relationId:$sliceKey")`, so a re-seal overwrites the same key with new
  bytes (`ContentInventory.kt`). That one is benign: a joiner replays the log.
- **A corrupting write.** The content `PUT` overwrites unconditionally (`BlobStore.put`,
  `SqliteContentBlobStore.put`), and the relay checks no digest against the ref. It only rejects
  bodies below one AEAD envelope. So a truncated or wrong body re-PUT under an **existing**
  chunk's ref replaces the good object at the source, and the mirror copies it over its own good copy
  on the next Sunday. Within a week, the only copy that could have repaired it is gone too.

⚠ A same-length overwrite does the opposite, **silently**: the sizes match, nothing is copied, and the
mirror keeps the older bytes with no report. That is accidentally protective against corruption, but
it means a restore from the mirror returns stale objects with nothing marking them as stale.

**Why it was left.** The mirror was built for the reclamation path (`dropRefs`, candidate → veto →
reclaim), the loss mode that exists today. Overwrite needs a client or relay bug that has not been
observed on a chunk ref.

**Fix.** In increasing strength:

1. **Never overwrite in the mirror.** Copy only keys the mirror lacks. Report size mismatches, or
   copy the new bytes to a side key such as `conflicts/<key>/<date>`, instead of replacing. This
   matches what "additive-only" already promises. The cost: mirrored slice snapshots go stale, which
   is harmless.
2. **Lag the overwrite.** Replace a mirrored key only once the source object's `LastModified` is
   older than N days. That gives a detection window before a bad write propagates. ⚠ It still loses
   the old bytes once the window passes, so it buys time, not protection.
3. **Versioning on the mirror bucket only**, plus a noncurrent-version expiry so re-sealed snapshots
   cannot grow it without bound. ⚠ Terraform cannot express that rule here. The Scaleway provider's
   `expiration` block makes S3 reject the request, which is why the backup bucket's retention goes
   through `set-lifecycle.py` out of band (see `deploy/scaleway-relay-backups/main.tf`). A
   noncurrent-version rule would have to go the same way, and whether Scaleway honours one is
   **unverified**. Object Lock is the stronger form of the same idea, and the bucket has to be
   created with it.

⚠ The root cause is on the relay: a content `PUT` onto an existing ref should be first-write-wins with
a size or digest check, the way share chunks already are (`SqliteShareStore`:
`if (!blobs.exists(key))`). Slice snapshots are the one writer that needs overwrite semantics, so they
would need their own path first. That is a relay protocol change, not a mirror fix, and it touches
the content plane's data shape: House Rule #2 applies.


### A rebuilt relay instance serves an empty realm, with no backups, from the moment it boots (2026-09-14)

**What happens.** `deploy/scaleway-relay`'s cloud-init renders `/opt/tenshu/deploy.env` from eight
Terraform variables, then runs `compose up -d`. Production's file carries eleven more keys, added by
hand: `S3_*` (the content plane), `BACKUP_S3_*` and `BLOB_MIRROR_BUCKET`. The backup and mirror scripts
and timers are also installed by hand. A replaced `scaleway_instance_server.relay` therefore starts on
the empty data-volume blob directory, on the production IP, before anyone can intervene. The old
instance is destroyed first, because there is no `create_before_destroy` and the IP and volume attach to
one server. Those eleven values exist only on the root disk being destroyed.

**Why it matters.** `/health` answers `{"status":"ok","durable":true}` in that state (the 2026-09-13
note in the module README), so nothing alarms, and every client sees an empty realm.

**Fix options.** (a) Express the keys as sensitive Terraform variables rendered by cloud-init. That puts
content-plane and backup credentials in local tfvars/state, like commerce's `ISSUER_SEED` already is.
(b) Gate startup: cloud-init refuses `compose up` unless `S3_*` is present, so a rebuild comes up
stopped, not wrong. (c) Install the backup and mirror units from cloud-init. (b) is the cheapest
protection and composes with either of the others. Until then the README's rebuild procedure is the
guard.

## Core Architecture & Type System

### `TResult`-returning ops are silently discardable — repo-wide audit of dropped results (2026-07-18)

`RemoteBinaryTaskExecutor` deliberately folds transport failures into `TResult.Failure` (a
launch-crash guard), the op extensions return that `TResult`, and Kotlin emits no unused-result
warning, so **every bare call compiles as if it succeeded** — the class behind the visit-loss bug
(the durable visit outbox). Audit script: `scripts/tresult-audit.py` (statement-position call-site
scan + chaining/binding classification; re-runnable, hand review required).

The shared primitive is `TResult.orThrow(what)` (`:core` `Error.kt`). The high-blast-radius sites
use it: `HealthImportWriter` (all five record ops, through `writeEach`), `FitProjectionRepository.addWorkout`
(`RecordViewModel.finish` catches; the engine resets to Idle only after a successful save, so finish
retries), and `MoveFileImplementation`'s folder cascade (a failed child aborts visibly).
`RevokeDeviceImplementation.revokeAdmission` is *deliberate best-effort* (the tombstone is the primary
revocation; the strip re-fires on the next A1-holder removal) and logs each per-member failure.

**Still discarded** (each a bare call whose Failure — e.g. "Proxy not connected" — vanishes):

*Silent data loss:*
- **`control-shared` desktop `FolderWatcher.uploadFile` + `updateSourceStatus`** — synced-folders
  auto-backup drops uploads silently when the proxy is unavailable.
- `timeline-shared-data/TimelineProjectionRepository:~165 recordTrip` (accepting a trip
  suggestion), `MapsPlaceSeeder.recordPlace`, `PlaceLabelEnricher.recordPlace` (known residual —
  blank-only fill self-retries), `SyncedFolderController.registerSource`, the preference writers
  (`BackupPreferences` / `RecoveryAffordancePreferences` / `BackgroundIngestManager.setSourceEnabled`).

*Security-relevant (an action the user believes took effect):*
- **`control-shared-logic/TenshuAppRegistry.setAppGrant` (×2) + `removeApp`** — app-permission
  grants/removals silently not persisted.
- Share revocation: `ShareManager.revoke` carries a KDoc *deliberately* folding the result away
  (the reactive projection converges on success) — but on total failure the local flip never
  happened either and nothing surfaces; `SharedScreenViewModel.revokeShareLink` same.
  `SyncDashboardViewModel.revokeDevice` / `approvePairingRequest` / `createRealm` likewise.

*Correctness (partial operations):*
- `contact-service-proxy/RedeemContactInviteImplementation.postResponse` (redeem ACK never
  reaching the relay → counterpart never completes), grant/bootstrap relay calls
  (`MemberSelfBindObserver.admitMember`, `SharedRealmBootstrapper.createRealm`/`admit`,
  `SharedRealmGrantManager.admitDevice`/`dropGrant`, `PairingCompletion.ackPairingResponse`,
  `DenyPairingOfferImplementation.postOfferResponse`) — some plausibly best-effort by design,
  none marked as such.

*Lower stakes*: UX-feedback drops (`FileActionsViewModel`/`SelectionViewModel.deleteFile` — the
adjacent **`RepositoryResult` family** has the same disease; region deletes;
`declinePendingGrant`; biometric cleanup; pairing stop/start), JS-SDK fire-and-forget teardowns
(`Pairing.stopPairing`, `ContentStream.close`), smokes/seeders.

**False-positive classes** (so a re-run doesn't re-litigate): relay-server `pushBlock` /
`pushTombstone` / `putChunk` and `FileDatabase.moveFile`/`deleteFile` are **Unit-returning**
same-named cousins; stdlib collisions (`lock`, `await`, `execute` on `java.sql.Statement`);
expression-position calls (`when`-branch executors, `getRecoveryPhrase() != null`).

**Open**: (1) `orThrow` the remaining sites above; (2) **lint** — a Detekt rule flagging
statement-position calls of `TResult`-returning functions (the durable fix; localisation lint
precedent applies); (3) an explicit fire-and-forget marker (`.ignoreResult()`) so genuine
best-effort sites are visible; (4) the `RepositoryResult` family. Flipping the op extensions to the
error-channel throwing `execute(): T` shape would retire the class entirely, at the largest churn.

### The Bootstrap Problem & Meta-Recursion

**File**: `core/src/commonMain/kotlin/org/tenshu/core/schema/Dsl.kt`

The platform uses a self-describing type system where schemas are themselves `Composite` objects.
This creates "bootstrap" circular dependencies: a schema needs to know its own field indices to
return its own ID, but determining indices requires looking at the schema hierarchy.

Current symptoms:

* `StackOverflowError` during `LazyComposite` materialization.
* Fragile `try-catch` blocks in `Dsl.kt` to bypass field lookups during early initialization.
* Manual filtering of metadata fields (`SCHEMA_ID`, `PARENT`, etc.) in domain object schemas to
  prevent index mismatches.

**Proposed Fix**:

* Move core metadata (`id`, `parent`) to constructor properties in `LazyComposite` to provide a "
  fast-path" that doesn't require index-based `getField` lookups.
* Ensure `CompositeSchema.values` always contains a complete, flattened list of all fields in the
  hierarchy during materialization to ensure index stability.

### Syncable Composites Can't Carry `typeVar` Value Fields

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/db/TenshuProtocolObjectDeserializer.kt`
(lines 56–129).

`TenshuProtocolObjectDeserializer` classifies each field via
`field.isValueField` / `field.isCompositeField` / `field.isParameterized`,
all of which inspect `field.type.baseSchema`. For a `typeVar("V")`-typed
field, `field.type` is a `TypeVariable` (not a `GenericType`), so
`baseSchema` is null and the classification falls through to
`error("Unknown field type")`.

This means parameterised composites whose type parameter appears in a stored
field can't be `Syncable` — Maybe.Some/TResult.Success are transient so
they don't hit it, but a hypothetical `Syncable Foo<V>` with `field(VALUE,
typeVar("V"))` will throw at read time.

**Encountered** during `preference-service` Phase 1+2 — the proposal's
`RealmSetting<V>` / `DeviceSetting<V>` were collapsed to non-parameterised
markers, and the stored `value` switched to `TByteArray` with
`PreferenceAdapter<T>` handling the typed encoding/decoding (per the inline
comments on `RealmSetting.Companion.Schema` + the design note in
`core-preference-service.md` §14 follow-up).

**Proposed fix:** Extend the deserializer to substitute typeVar field types
against the entity's parameterisation at read time (mirrors the
substitution `CompositeRo.resolveField` does on the RO side — Phase 3.3 of the
graduated `core-composite-schema-typing` proposal; ⚠ the doc is gone and
[ADR 0005](adr/0005-composite-schema-typing.md) is its home, but the ADR keeps only
the decision, not that phase numbering). With this landed, the preference-service
storage can move back to the `RealmSetting<V>` / `DeviceSetting<V>` shape
the proposal describes, and other Syncable-with-typeVar composites become
possible.

### Auto-Register Composite Request-Object Factories

**Files**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SchemaRegistry.kt`;
all Composite-returning Ros under `*-service-client/src/commonMain/kotlin/.../impl/` (e.g.
`sync-service-client`'s `InitiatePairingRo`, `GetSyncStatusRo`, `RecoveryClaimRo`,
`CreateRealmRo`).

`SchemaRegistry.requestObjectFactory(type)` looks up an RO factory keyed on a schema's type.
Only **primitives** (`TString`, `TBoolean`, `TByteArray`, …) actually register one. For
**Composite** return types the `composite(interface, schema, factory)` DSL registers a
separate Composite factory; the two factories live in the same registry but speak past each
other. As a result every Composite-returning Ro must lie to `createRequestObject` — pass
`Data.Schema.toType()` + `<Data>` and trust that the Composite path materialises the real
type at observation time. Passing the actual schema (e.g. `PairingInitiation.Schema.toType()`)
throws `IllegalStateException: No request object factory registered for type: …` at runtime,
not at registration. This bit a real production crash on `InitiatePairingRo` (confirm-pairing crashed the host);
discoverable today only by reading neighbouring Ros or hitting the crash.

**Proposed Fix**: when `composite(interface, schema, factory)` runs in the module DSL, also
register a generic request-object factory keyed on that Composite's type that delegates to
the existing Composite path. After the fix, `createRequestObject<PairingInitiation>(
PairingInitiation.Schema.toType(), …)` would "just work" and the `<Data>` cast in every
Composite-returning Ro becomes migrate-able. Additive change — existing call sites keep
compiling — so the migration can be incremental.

**Cost**: ~1 day. Need to understand the two factory tables in `SchemaRegistry`, write the
bridging adapter, and verify the wire format and observation behaviour are unchanged on the
already-working Ros.

**Revisit when**: next time someone is touching `SchemaRegistry`'s factory wiring for an
adjacent reason, or when a third Composite-returning Op is added in a single PR (i.e. the
footgun starts paying compounding tax).

### Index-Based DTO Fragility

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/impl/CompositeDto.kt`

`CompositeDto` currently uses a `List<Data?>` and index-based lookups derived from `schema.values`.
This is extremely sensitive to schema changes, inheritance order, and registration timing in the
`SchemaRegistry`.

**Proposed Fix (Short-Term)**:

* Move `CompositeDto` to use an internal `Map<String, Data>` for storage. This decouples the runtime
  object state from the schema's field ordering, making the system significantly more robust
  against "Field not found" errors. The binary transport layer will still use indices for
  efficiency, but internal logic will use ID-based maps.

**Proposed Fix (Long-Term)**:

* Implement **Schema-First Code Generation**: Move to a system where the Schema is the primary
  source of truth. Use a code generator (KSP or a custom tool) to produce the Domain Interfaces,
  DTOs, ROs, and DSL extension functions automatically.
    * **Hybrid Static-Dynamic Access**: Generated classes will provide "Fast Paths" (hardcoded
      IDs/indices) for core fields defined in the base schema, while falling back to a dynamic
      Map-based system for properties and traits added by other modules at runtime.
    * **Cross-Module Robustness**: This ensures 100% parity for core logic while preserving the
      platform's ability to dynamically extend data across module boundaries without triggering
      bootstrap recursion or index-mismatch errors.

## Callstack Execution & Laziness

---

### Proxy Implementations Instantiate `TListDto` Directly

The three operation implementations (`ListDropImplementation`, `ListTakeImplementation`,
`SelectImplementation`) now use `list.toTList(executionContext.context)` instead of
constructing `TListDto` directly. Remaining violations live in lower-level paths and
are deferred:

- `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/ExecutionContext.kt`
  — stream payload wrapping inside `setDatabaseListener`. Uses `TListDto` to build
  `StreamElementValueDto` payloads; moving to the factory requires verifying that all
  stream-transport consumers tolerate non-DTO `TList` implementations.
- `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/db/DatabaseImpl.kt`,
  `TenshuProtocolObjectDeserializer.kt`, `SchemaRegistry.kt` — lower-level DB / parser
  / registry paths where direct DTO construction may be intentional (performance-
  sensitive, single-owner, outside the proxy/operation boundary). Audit before
  touching.

These deserve a dedicated pass with a clearer contract on when `toTList(context)` is
preferred over direct DTO construction.

---

## Executor Abstraction: Hide `ArrayDatabaseReference` from Operation Implementations

**Files**: `core-common/.../proxy/EvaluateCallstack.kt`, `ExecutionContext.kt` (`resolveList`),
`proxy/executor/OperationImplementation.kt`, `DatabaseReference.kt`, `ArrayDatabaseReference.kt`.

### Current state

- `EvaluateCallstack` materialises a `Sequence<Data>` to a `TList` before dispatch
  (`(currentData as Sequence<Data>).toList().toTList(...)`, on both the operation and the reference
  path), so no `ProxyOperationImplementation` receives a raw `Sequence` or `ArrayDatabaseReference`
  as `caller` — except stream ops, which are classified by the schema trait (`protocol.isStream()`)
  and keep the reference, because `ListStreamImplementation` registers change listeners on it via
  `ExecutionContext.setDatabaseListener`. Any refactor must preserve that direct access.
- Every list operation (Search, Filter, SearchAll, Select, Take, Drop) normalises its caller through
  `ExecutionContext.resolveList`.
- `ArrayDatabaseReference` is a minimal `Data, DatabaseReference` (no `TList`/`List` stubs);
  `DatabaseReference` (single method `getOperationContext()`) has two implementors,
  `ArrayDatabaseReference` and `EntityDatabaseReference`.

### Open

- **Thread A1 — narrow `caller: Any?` to `caller: Data?`** in `ProxyOperationImplementation.execute`
  and `PureOperationImplementation.execute`. Every production path already passes `null` or a
  `Data`, so this is paperwork; the existing `caller as? TList<*>` checks keep compiling.
- **Thread A4 — seal `DatabaseReference`** (or delete it and check the two concrete types). It exists
  only so `EvaluateCallstack` can tell "DB-backed value, switch to deferred mode" from plain `Data`.
  Sealing is cheaper and the recommendation unless another reference kind is foreseen.
- **Thread B — DB-level laziness was traded for signature uniformity.** The pre-dispatch
  `.toList()` streams every row before any operation runs, so `SearchImplementation`'s
  `firstOrNull` short-circuits over an already-loaded list. If DB-backed arrays grow beyond a few
  hundred rows: (B1) a `LazyTList<Data>` — a real `TList` over a `Sequence`, iterator-first,
  index access walks or throws, a documented lazy type rather than a type-lie; (B2) wrap instead of
  materialising at the two `toTList` sites; (B3) teach `resolveList`'s `is TList<*>` branch to return
  a `LazyTList`'s underlying `Sequence` instead of round-tripping through a `List`. Only worth doing
  if lazy iteration is a real requirement; otherwise Thread A is the full cleanup.

### Trade-off worth flagging

Hiding the reference from non-stream executors closes the door on *executor-level* DB pushdown
(e.g. Filter translating its predicate to a DB query). That's intentional — predicate pushdown
belongs in a query-planner stage that rewrites the deferred `executionStack` before dispatch,
alongside `EvaluateCallstack`, not duplicated across Filter/Search/SearchAll/Take/Drop. The stream
trait is the extension point for any future op that genuinely needs reference access.

---

## Database Deserializer: Eager Deep Recursion + Projection-Blind Reads

**File**: `core-common/.../proxy/db/TenshuProtocolObjectDeserializer.kt` (`deSerialize`,
`deserializeArray`), read entry `DatabaseImpl.getData` / `getArrayData`. Design and status:
[`core-deserializer-projection-pushdown.md`](proposals/core-deserializer-projection-pushdown.md).

`deSerialize` materialises a stored object's graph **eagerly, to indefinite depth**: list fields
come back as a lazy `ArrayDatabaseReference` when loading an entity, but composite scalar fields and
composite relations (`dbEntry.properties`) recurse unconditionally. The fix is projection
pushdown: `captureProjection` in `EvaluateCallstack` captures a leading `select`'s selector set and
threads a `Projection` into `getArrayData`, the deserializer skips fields outside it, and
`CompositeDatabaseReference` (a `Data` node, **not** a `DatabaseReference`; resolves on first use and
caches nothing, so House-Rule-6 safe) backs nested deferral. Pinned by `CaptureProjectionTest` and
the projection cases in `TenshuProtocolObjectDeserializerTest`.

**Open** (the proposal's status line is authoritative): measure the over-fetch reduction on a real
listing surface (HomeScreen recent-files, every File row of which paid its full composite-relation
graph); on-device verification; single-entity reads, which capture no projection from the chain;
and `filter`/`sortBy` before `select`, which falls back to a full load (a query-planner concern).

⚠⚠ **Composite deferral must stay projection-gated — never unconditional.** Deferring every
composite scalar field on every entity load turned `:sync-service-proxy:jvmTest` red with
`ClassCastException`s (`CompositeDatabaseReference cannot be cast to ScheduleSpec / ConstraintSpec /
HybridLogicalClock`): real schemas (`ScheduleItem`, `DeleteBlock`) carry composite scalar fields read
by typed accessors (`getField() as T`) **directly on a loaded entity, not through a `select`** — ROs,
repositories, mergers, the sync processor. Only the projection proves a field won't be read, so
`projection == null` (every non-`select` read) must keep loading everything. That suite is the
canary. (`EntityDatabaseReference` is not a lazy node either: it tags an already-materialised entity
with its `dataId`.)

---

## Relation Lifecycle Policies Are Hand-Written and Entangled With Membership

**Files**: `file-service-proxy/.../FileServiceProxyInitialization.kt`
(`FileModificationRelationMaintainer.enforceRetention`, `cascadeDeleteHistory`),
`sync-service-common/.../DataIdMigrator.kt` (a second cascade),
`docs/proposals/core-error-channel.md` §4.4 (a second retention consumer, unbuilt).

`FileModificationRelationMaintainer` carries three unrelated jobs in one observer: relation
**membership**, a **retention cap** (`FileModification.RETENTION_CAP` — trim the oldest
non-`Created` rows on a locally-originated write), and a **tombstone cascade** (deleting a
`File` deletes its history). `core-relation-system.md` R2 extracts the membership half onto the
generic mechanism; the other two stay hand-written **because nothing generalises them**, and
that is the debt.

They are not one-offs:

- **Cascade has two implementations already** — the one above and
  `DataIdMigrator.cascadeDeleteHistory(sourcePath)` (content-addressing Ph2 3b).
- **Retention has a second consumer designed but unbuilt**: `core-error-channel.md` §4.4 /
  Phase 5b wants a 500-event ring buffer **plus** a 30-day eviction sweep over `ErrorEvent`,
  which is the same policy with an added time bound. Notifications are a plausible third.

⚖️ **Now newly generalisable, and that is what changed.** Both policies are *about a relation*,
and until R1/R2 the system had no declared relations to hang them on. With
`serviceDataRelation(id, type, order, chainReadable)` established as the declaration site (and
`order` as the precedent for a code-side policy that never reaches the wire), a
`retention = …` / `onParentDeleted = …` sibling is a natural extension rather than a new concept.

⚠⚠ **The subtlety that makes this a design job, not a refactor: the two policies have OPPOSITE
propagation semantics, despite both being "delete rows from a relation".**

- **Cascade must NOT propagate.** The parent's single tombstone already crosses the wire; each
  replica re-derives the child deletions locally. Emitting a tombstone per cascaded child would
  turn one delete into a storm (`SyncObserver.onDataDeleted` is deliberately a no-op for exactly
  this reason).
- **Retention MUST propagate.** A trim is *not* derivable from anything a peer sees, so it emits
  a per-row tombstone — and only the originating device trims (`origin == WriteOrigin.USER`),
  because if every replica trimmed independently the tombstone traffic would multiply by the
  device count.

A generic mechanism that gets this backwards is worse than the hand-written code.

**Design notes for whoever picks this up** (informed by the ordering discussion in
`core-relation-system.md` §11.1):

- The eviction key should be a **field reference**, not a comparator — same argument as the sort
  key: data can be indexed and shipped across the module graph to JS/EPC, code cannot.
- The pin (`Created` is a floor record that must never be trimmed) should likewise be data —
  a set of pinned subtype ids, or "keep the N oldest" — not a predicate lambda.
- Needs both bounds: `maxCount` (file history) and `maxAge` (logging's 30-day sweep).
- ⚠ Interacts with the entry below: evicting "the oldest" without a maintained key order is an
  O(n) scan, and each trimmed row is another full-row array rewrite.

⚖️ **The design is written up:**
[`core-relation-lifecycle-policies.md`](proposals/core-relation-lifecycle-policies.md) (Draft
v0.1) — the propagation asymmetry, the proposed `onParentDeleted` / `retention` declarations,
five open questions, and phasing L1–L3. It also records a payoff found while writing it: both
policies currently scan the **realm-wide** `MODIFICATIONS` array and filter by `fileId`, when
`File.FileHistory` already holds exactly the rows they want — so declaring them on the
entity-scoped relation turns a per-write full-log scan into a bounded one.

**Priority**: low until the logging Phase 5b work is actually scheduled — that is the forcing
consumer, and building the generalisation before it would be speculative (n=1 dressed as n=2).
⚖️ Exception: **L1 (cascade) is independently shippable** — it is the clean half, it has no
unbuilt consumer, and it is genuinely derivable from the relation declaration.

---

## Singleton Status Rows Are Modelled as `TList` Relations

**Files**: `sync-service/.../SchemaFenceStatus.kt` + `SyncHealthStatus.kt`,
`storage-service/.../StorageStatus.kt` (declarations in each service's `Schema`).

`SchemaFenceStatus`, `SyncHealthStatus` and `StorageStatus` are each **exactly one row**, held in
a `TList.typeOf(…)` service-data relation and deduped by a constant key — `SchemaFenceStatus`
says it plainly: *"keyed on the constant `SINGLETON_KEY` so the relation upsert replaces rather
than appends"* (`SINGLETON_KEY = "current"`). They are singletons wearing a collection costume.

Harmless today, and it surfaced only from an odd angle: they are 3 of the 5 relations that opt
out of `maintained` (`core-relation-system.md` R2b), which makes that flag look far emptier than
it is. Of the five, only `HealthService.LATEST_SAMPLES` (a rollup) is genuinely *a relation that
deliberately isn't maintained*; `FileService.Relations.ROOT` is the single-entity form, and these
three are singletons.

**Fix, if ever**: the single-entity `serviceDataRelation` form already exists (`ROOT` uses it —
`type = Folder.Schema.toType()` plus a concrete `dataId`), so these three could move to a
`Maybe<T>`-shaped declaration rather than a list. That would leave `maintained` with one
legitimate user and make the model say what it means.

**Priority**: very low — cosmetic until something needs to distinguish "a relation with one
member" from "a singleton". Recorded because it is invisible unless you go counting unmaintained
relations, and because it quietly makes machinery look more used than it is.

---

## Relation Arrays Are Full-Row Rewrites — Every Membership Change Is O(n)

**Files**: `core-common/.../proxy/db/DatabaseImpl.kt` (`addToArray`, `removeFromArray`,
`getArrayMemberHashes`), `core-common/.../proxy/db/SqliteObjectStore.kt`
(`LARGE_ROW_THRESHOLD_BYTES`).

A relation array is stored as **one row** — `DbEntry(type = TList.ID, dataIdHash = <arrayHash>,
fields = [LongValue(memberHash)…])`. So `addToArray` and `removeFromArray` each `getObject` the
whole row, rebuild the member list, and `storeObject` **the entire row back**. There is no
incremental append or remove.

Consequences:

- **Every membership change is O(n) in the relation's size**, regardless of what changed. Adding
  one file to a folder rewrites the folder's whole member list.
- **An n-member bulk build is O(n²) bytes of array-row writes.** An n-file folder upload writes
  ~Σi member hashes. Bounded folders are fine; this is a scaling ceiling, not a present bug.
- **Above ~1000 members the row leaves the cheap tier.** `LARGE_ROW_THRESHOLD_BYTES` is 8 KiB
  (≈1k hashes), past which the row takes the pinned-until-commit + point-SELECT large-row path
  (`bounded-store-cache.md` P2) — so the rewrites land on the expensive tier exactly when they
  are biggest.

✅ **The constant-factor half is FIXED (R2b, 2026-07-29).** Every maintainer used to do
`transaction { removeFromArray(...); addToArray(...) }` on *every* save, so a re-save that
changed nothing about membership still paid two full-row rewrites — and re-saves dominate a
synced realm (sync pulling this device's own block back, `size`/`lastModified` updates, OnDemand
byte materialisation, thumbnail resolution). The generic maintainer now converges **probe-first**
for every relation, measured at the `ObjectStore.storeObject` layer
(`RelationMaintainerWriteCountTest`):

| array-row writes | before | after |
|---|---|---|
| re-save of an existing member | 2 | **0** |
| a genuinely new member | 2 | **1** |
| repair of a duplicated member | 2 | 2 |

⚠ Not a factor claim: the bounded write mailbox dedupes by row hash, so the remove-write and
add-write inside one transaction may already collapse to a single *physical* write. What the
mailbox cannot collapse is two writes into zero. The defensible claim is the **zero-write steady
state**, which is what the table measures.

**The actual fix is still unbuilt.** An incremental array representation — an append/remove
journal compacted periodically, or chunked member pages so a change rewrites one page rather than
the whole list. That is what removes the O(n)-per-change and with it the O(n²) bulk build; R2b
deliberately did not touch the complexity class.

**Priority**: low. Nothing is broken at today's sizes and the cheap half has landed. Revisit the
incremental representation when a relation in the 10k+ range appears — the same trigger as the
paged-listings entry above, which is the *read*-side half of this same representation.

---

## `TMap` Is Registered and Wire-Supported, but Nothing Persists One — the Store Has No Handling for It

**Files**: `core/.../collection/TMap.kt`, `core-common/.../impl/collection/TMapDto.kt`,
`core-common/.../module/CoreCommonModule.kt` (the registration),
`core-common/.../proxy/db/DbEntryMapper.kt` (the absence).

`TMap<K : Data, V : Data> : Composite, Map<K, V>` is a first-class type: registered in
`CoreCommonModule` with an `interfaceClass`, a `dataSchema` and a `dtoFactory`, understood by
`BinaryParser`, and reached by `EvaluateCallstack`'s recursive walker for nested shapes like
`TList<TMap<K, V>>`. It is used inside `EntityDto` for the DTO's own field/property maps.

⚠ **The walker is not checked end to end.**
For *materialized* runtime data `actualReceiverType` returns the schema's **unparameterized** `Type`,
and an `ArrayDatabaseReference` preserves only the element schema id — so a nested map's `K` and `V`
are simply absent. `matchesReceiver` accepts a matching base whenever the actual parameter list is
empty, so an operation expecting `TList<TMap<K, V>>` can dispatch for incompatible key/value types.
⇒ recursive-walker support, plus a runtime parameter-information gap.

⚠ **Schemas do declare maps.** `Entity.Schema` declares **both** `FIELDS`
and `PROPERTIES` with the `map(...)` DSL (`core/.../entity/Entity.kt:78,83`). Those avoid map
persistence only because `DbEntryMapper.serialize` substitutes the wrapped **domain** schema when its
input is an `Entity` — a special case, not an absence. Read the claim as *no ordinary persisted
domain schema declares one*, or a reader will mistake an existing declaration for a new one.

⚠⚠ **The first persisted `TMap` is a KNOWN serialization failure, not merely unverified.**
`TMap.Schema` declares `typeParams` and `fields` and **no `traits`**,
so it carries no `DataIdProvider`.

⚠ **The exact failure — pin this one, not the `writeValuesToDb` error.** For a `TMap` nested in a persisted parent the
outer composite arm *passes its fallback down*, so `writeValuesToDb`'s
`?: error("No DataIdProvider found for … and no fallback provided.")` is **not** reached. Processing
`TMap.PAIRS` instead enters the **`TListDto`** arm, whose `getDataId(data)` is wrapped in a catch
that throws `Cannot determine parent ID for list tenshu/core/map/pairs`
(`DbEntryMapper.kt:591`). Supply that identity and the same question arrives again for every
provider-less `TPair`.

⇒ the open questions — what a map entry's field refs would be, whether they round-trip, whether two
maps under one parent collide — are the questions that follow *after* storage support exists. They
are not what stops the first adoption; the missing identities are, deterministically. Scope the
work as **adding map storage support**, not as investigating whether the existing path happens to
hold.

**Contrast with the entry above, because the difference is the useful part.** A relation array is
one row of member hashes with **each member its own row**. So membership changes are O(n) full-row
rewrites — no incremental append or remove — while a change to one member's *content* rewrites only
that member's row.

⚠⚠ **A map is NOT missing that split — do not build parallel map-row machinery.** `TMap.PAIRS` is already a `TList<TPair<K, V>>`, so the
existing `TListDto` arm would write one array row of hashes and recursively serialise each pair as
its own member row: the row/member separation is inherited, not absent. ⇒ the fix is **completing
the existing list-backed representation** — supplying the parent and pair identities that arm needs
— not building parallel map-row machinery beside it.

⚠⚠ **What it inherits is not whole-row replacement, and calling the gap "mutation APIs" understates
it.** Production supplies `getEntry`, so the `TListDto` branch **unions** the existing refs with the
new ones — the deliberate only-grow merge the mapper documents, which exists so a fresh child with an
empty list field cannot erase side-channel `addToArray` entries. Applied to a map that means an
ordinary `Database.saveData` **cannot change or remove a key**: the obsolete pair simply survives
alongside the new one. ⚠ And giving `TMap` a `DataIdProvider` makes the composite branch skip
re-serialising an existing map row at all, so the update is dropped rather than merged.

⇒ the completion therefore owes **replacement/diff semantics for ordinary saves** — deciding what
`saveData` of a changed map means when the storage layer's rule is only-grow — *before* it owes any
`addToMap`/`removeFromMap`. Side-channel APIs would otherwise be the only way to mutate a map
correctly, which is a contract no caller can be expected to infer.

⚠⚠ **And the READ side fails for a different reason, so identities alone would produce rows that
cannot round-trip.** Each pair row stores only `TPair.ID`, so `DbEntryMapper.deSerialize` reloads
`TPair.Schema` — where `FIRST` and `SECOND` are declared as unresolved `typeVar("A")`/`typeVar("B")`.
Those are neither value, parameterized, constant nor composite fields, so deserialisation falls
through to `error("Unknown field type")` (`DbEntryMapper.kt:252`). ⇒ completion also requires
**preserving or substituting the concrete `K`/`V` types** — self-describing pair slots, or a
dedicated codec. Supplying identities without this writes rows nothing can read back, which is the
one outcome worse than refusing to write them.

**Where this came up**: 2026-09-22, choosing the shape of the `(contentId, propertyId)`-keyed
content-emit journal (the D3 track). The decision was to keep one row per intent with a
derived string key, and layer map-shaped *access* on top if the ergonomics are wanted — storage
stays row-shaped. Three existing lessons pushed the same way: per-entry resume state
(`landedChunkHashes`) wants an isolated writer, `clearingAnIntentDeletesItsRow_notJustItsArrayMembership`
pins row deletion after 8 orphaned rows on a real phone read exactly like stuck uploads, and
`file.reclamation.candidates` — a genuine whole-value store — has the recorded trap that an empty
read proves nothing because "absent" and "never written" are indistinguishable.

**Priority**: low. Nothing **in use** is broken and nothing is blocked — no persisted schema
declares an ordinary `TMap` today, so the failure above is latent rather than live. Worth a decision
before the first persisted `TMap`, not before that, and there are **two** options:

- **complete the list-backed representation** — supply the parent and pair identities the existing
  `TListDto` arm needs, **preserve or substitute the concrete `K`/`V` types so a pair row can be read
  back**, **define what an ordinary `saveData` of a changed map means against the only-grow merge**,
  and then add the element-level `addToMap`/`removeFromMap` that array membership already has; or
- **record that persisted maps are out of scope**, and keyed state uses relations (which is what the
  content-emit journal decision did).

⚠ **"Verify and document the generic-composite path" is NOT a third option.** That reads as a standalone remedy, and it cannot be: the path
throws deterministically at `TMap.PAIRS`, so verification can only confirm the failure this entry
already records. Left in the list it would let the task be closed by writing down what is now
written down, with persistence no more usable than before. ⇒ verification is a **step inside** the
first option, after the path works — never a substitute for it.

---


## OnDemandMaterialiser: Doesn't Clear Placeholders for Entity Types With No Payload

**Files**:

* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/OnDemandMaterialiser.kt`
* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SelectImplementation.kt`
* `file-service/src/commonMain/kotlin/org/tenshu/core/file/File.kt`

`SelectImplementation` writes a `PendingOnDemandProperty` placeholder for any
`Syncable(OnDemand)` relation whose value can't be resolved locally.
`OnDemandMaterialiser` walks emitted entities and substitutes placeholders
with the resolver's value — but per its doc-comment, "a property that fails
to resolve stays as a placeholder. `PendingOnDemandProperty` round-trips across IPC, so that's wire-safe;
the downstream consumer sees the marker and can detect the gap." The
intent: marker survives so the consumer can render "loading" state.

The latent issue: a placeholder also survives when the entity type
*structurally* has no payload to fetch — not because the fetch failed
transiently, but because the value doesn't exist by design. The
production trigger is `Folder` inheriting `File.Relation.Data` from
`File.Schema`: a folder has no bytes, but `SelectImplementation` writes
the placeholder anyway (the relation is declared on `File.Schema`, which
`Folder` is a subtype of), and `OnDemandResolutionImpl.resolve(...)`
always "fails" for the folder because there are no bytes on the relay.
The placeholder leaks all the way to `FileMapper.map` where the
reified `Entity<out File>.data: TByteArray?` getter would do a call-site
unchecked cast that crashes with
`ClassCastException: PendingOnDemandPropertyDto cannot be cast to TByteArray`.

The `File` accessors avoid that crash with a safe cast (`as? TByteArray`:
the placeholder collapses to `null`, which the consumer already treats as
"remote / not yet downloaded"). That's defensive — every
consumer of every `Syncable(OnDemand)` accessor would need the same
treatment to stay safe.

**Proposed Fix** (the upstream cleanup):

* Teach `OnDemandMaterialiser` to recognise the "no payload by design"
  case and clear the placeholder rather than leave it in. Candidate
  signal: the resolver returns `TResult.Success(value = null)` (or a
  dedicated `NoPayload` marker) for entities whose schema declares the
  OnDemand property but instances of this type never carry it.
  Alternative: extend the `Syncable(OnDemand)` trait with an
  `appliesTo(typeId)` predicate so the placeholder isn't even written
  by `SelectImplementation` for types where the property is
  structurally absent.
* Once placeholders no longer leak by design, the defensive `as?
  TByteArray` / `as? FileCategory` casts on `Entity<out File>` accessors
  can stay (cheap, explicit) or be reverted (back to the original
  reified-cast shape) — call site author's choice.

The defensive call-site cast is the safer fix for now; the materialiser
cleanup is a more invasive change to documented semantics that other
consumers may rely on for "loading state" rendering. Worth revisiting
the next time the OnDemand machinery is touched.

---

## Non-Syncable Dynamic Properties Deadlock When Projected Into a Streamed Listing

**Files**:

* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SelectImplementation.kt`
* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/StreamRegistry.kt`
* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/OnDemandMaterialiser.kt`
* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/ReferenceResolver.kt`

A `dynamicProperty(...)` declared **without** a `Syncable` trait — i.e. a
purely proxy-computed, device-local value (`fileSize`-style presence, a derived
count, anything that must never replicate) — **cannot currently be projected via
`relations(...)` into a streamed/paged listing select**. Doing so hangs the
listing flow forever. Verified concretely: declaring `File.HasLocalData`
(`dynamicProperty<File, TBoolean>(traits = emptyList()) { hasLocalData() }`) and
adding `relations(File.HasLocalData)` to `getFilesPaged`'s select deadlocked
`FilesPagingIntegrationTest` (test parked in `runBlocking` 568s; thread-dumped, no
active resolver thread — a stuck await, not a busy loop).

**Root cause** — the placeholder/materialiser path is hard-gated on
`Syncable(OnDemand)`:

* `SelectImplementation.buildPendingMarker` returns `null` for any relation whose
  schema carries no `Syncable` trait (and again unless `strategy == OnDemand`), so
  **no `PendingOnDemandProperty` is written** for a non-syncable dynamic property.
* But in the streamed path, resolution runs under `StreamRegistry`'s `updateMutex`
  / `collectingReferences` with `evaluateCallstack(allowDeferring = false)`. When
  the inline resolution can't complete synchronously it has no placeholder to fall
  back to and no materialiser to fire it later (the `OnDemandMaterialiser` only
  knows how to resolve `Syncable(OnDemand)` placeholders), so the window's emission
  never completes and the flow blocks.

Net: a dynamic property is safe to project in a streamed listing only if the
window can resolve it **synchronously** — either it is `Syncable(OnDemand)` (a
`PendingOnDemandProperty` placeholder is written and the materialiser fires it
later) or its resolver returns synchronously without deferring. The deadlock
above is the **non-syncable, compute-on-access** case (`File.HasLocalData`), which
has neither escape hatch.

List thumbnails are now a **stored synced value** (`File.Thumbnail`, a `TByteArray`
value property — `drive-synced-thumbnails.md` Phase 1, shipped): generated once on
the uploading device and `set` on the entity, so it rides `File`'s eager block to
every device and resolves even where the bytes are not (an OnDemand / online-only
file). `getFilesPaged` projects `File.Thumbnail` and `FileMapper` prefers it,
falling back to the *computed* `File.Preview` where bytes are local (and for files
predating the property). This closed the "preview only shows on the device that
holds the bytes" gap — see `SyncedThumbnailSystemTest` (host renders, byte-less
joiner resolves the thumbnail).

The compute-on-read `File.Preview` is still projected as the fallback. It's safe
inline: its impls (`Jvm`/`AndroidGetPreviewImplementation`) are synchronous
`SyncOperationImplementation`s that **return null on a local miss** (no bytes yet),
so the window resolves without deferring — verified by `FilesPagingIntegrationTest`.
⚠ A `Syncable` trait on a dynamic/chain-computed property is **inert**: it is
recomputed on read and never *written*, so `SyncObserver` never emits it — which is
exactly why the thumbnail had to become a stored value (proposal §2.1). ⚠ A
`GetPreview` impl must return null on a missing `GetData` result, never throw: one
failing row fails the *whole* window → empty Files list. `File.Data` stays
excluded (`OnDemand` + a per-window full-byte fetch). The remaining gap is still
the **non-syncable compute-on-access** case (e.g. `HasLocalData` presence below).

**Why it matters / the consumer that hit it**: Drive's offline "presence" glyph
(`drive-offline-online.md` §3). The shipped v2 resolves presence with a **per-row
op** (`HasLocalData` + a bounded `Semaphore`, one IPC per visible row) precisely
*because* the cleaner design — a projectable `File.HasLocalData` dynamic property
folded into the page fetch (zero extra round-trips) — deadlocks here. The op-per-row
version is correct but does N per-row IPCs the property version would have avoided.

**Proposed fix** (sized; touches the frozen stream/resolution seam — coordinate,
do not race it against sync work):

* Add a notion of an **inline / eagerly-resolved** dynamic property — e.g. a trait
  (`InlineResolved`) or a flag on `dynamicProperty(...)` — that `SelectImplementation`
  resolves to a concrete value at select time and, on a local miss, **omits** (or
  writes a `null`) rather than leaving an unmaterialisable placeholder. The
  contract: such a property is computed locally, never deferred, never synced.
* Ensure the streamed path (`StreamRegistry` window resolution +
  `OnDemandMaterialiser`) treats a non-syncable inline property as "already final"
  so it doesn't wait on a materialiser that will never run.
* Guardrail: at registration, reject projecting a non-syncable dynamic property in
  a streamed listing *unless* it carries the inline marker, so this fails loud
  instead of hanging.

Until then: **non-syncable computed values must be served by a dedicated op called
per-row (bounded), not by a dynamic property projected in the listing.** The
abandoned property-in-listing rework for Drive presence is preserved as a stash +
`/tmp/offline-v2-property-approach.patch` for whoever picks this up.

---

## Request DSL & Async Execution

### Interactor Pattern Migration — Largely Landed (April 2026)

The unified sealed `Interactor<T>` hierarchy + `DefaultInteractorProvider` (dispatch by
terminal-operation trait) landed; per-operation DSL migration is essentially complete
([ADR 0012](adr/0012-interactor-pattern.md), authoring ref [`schema-dsl.md`](schema-dsl.md) §5).

**House rule (guardrail):** an operation declaring `IoOperation` must also declare
`TerminalOperation` — `Dsl.kt:operation()` enforces it at schema construction; an inline
non-terminal IO op otherwise leaks `AsyncReferenceDto` through sync entry points like
`resolveDynamicProperty` (`GetData`/`GetPreview` are `Sync` for this reason).

**Open** (design items from §9 of the graduated `task-interactor` proposal; ⚠ the doc is gone
and [ADR 0012](adr/0012-interactor-pattern.md) is its home, but these items did **not** graduate
with it — its *When to revisit* list is a different three): progress reporting for long-running IO,
and backpressure for `BackgroundInteractor`. (`EvaluateCallstack` classifies stream ops by the
schema trait, `protocol.isStream()`, not by id.)

### Operation Implementations Lack Granular Error Handling

**Files**: `*Implementation.kt` (e.g., `UploadFileImplementation.kt`,
`CreateFolderImplementation.kt`)

Most `OperationImplementation` classes currently lack internal `try-catch` blocks and rely on the
global safety net in `ProxyGateway.call`. While this prevents crashes by returning a `GenericError`
to the client, it lacks granularity:

- Operations cannot perform specific cleanups on failure.
- Error messages are often generic ("Unknown error") or raw exception messages.
- The system cannot distinguish between transient errors (retryable) and logic errors.

**Proposed Fix**: Audit implementations and add operation-specific error handling where appropriate,
converting raw exceptions into domain-specific protocol errors (e.g., `InsufficentStorageError`).

### Synchronous Callstack Evaluation and Cancellation

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/EvaluateCallstack.kt`

`evaluateCallstack` is a synchronous loop. While it is called from within coroutines (via
`LocalTaskExecutor`), it does not naturally hit coroutine suspension points. We rely on the
`if (e is CancellationException) throw e` check in the caller's catch blocks to propagate
cancellation, but the loop itself won't stop mid-execution unless:

1. An operation implementation is `suspend` and hits a suspension point.
2. An implementation or internal helper explicitly calls `sessionScope.ensureActive()`.

**Proposed Fix**: Add explicit cancellation checks (e.g.,
`executionContext.sessionScope.ensureActive()`) inside the `while` loop of `evaluateCallstack` to
ensure that extremely long callstacks or tight loops can be terminated immediately when a client
unregisters.

### `executeAsync` Exception Handling Ambiguity

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/LocalTaskExecutor.kt`

In `executeAsync`, the `asyncCall.await()` suspension point is technically outside the immediate
`try-catch` block that wraps `evaluateCallstack`. While `ProxyGateway` provides a final safety net,
this structure is fragile:

- Exceptions from `await()` (like network timeouts or async implementation bugs) bypass the granular
  logging/error wrapping in `LocalTaskExecutor`.
- The `if (e is CancellationException) throw e` check must always remain the first line of any
  proxy-side catch block to ensure `unregisterAll` releases Binder/socket threads immediately.

**Proposed Fix**: Refactor the `executeAsync` loop to wrap the entire per-iteration logic (including
`await()`) in a consistent `try-catch` that properly distinguishes between cancellation and protocol
failures.

### Device Naming Conflict: User-Defined vs. Hardware/Platform Name

**Files**: `identity-service/.../DeviceHardware.kt`, `sync-service-proxy/.../DeviceHardwarePublisher.kt`,
`deviceDisplayLabel` (Kotlin) / `formatDeviceLabel` (web).

`Device.name` is the user's label; each device publishes `DeviceHardware(deviceId, hardwareName)`
about itself (`getPlatformHardwareName()`), and every roster joins the two through one helper —
`deviceDisplayLabel` / `formatDeviceLabel` — as "Work Laptop · Mac OS X". `RevokeDevice` (hence
`ForgetThisDevice`) tombstones the hardware row together with the device's `DeviceSchemaVersion`.
⚠ Never fold it into a `Device.hardwareName` field: `Device` has two writers (admitter and
self-publish) merged whole-entity LWW, and only the device knows its hardware, so the field would be
erased whenever the admitter's write won on HLC.

**Still open:** there is **no rename op**, so the pairing screen's "you can rename it later"
promises something nothing implements; and the approval prompt on the admitting device shows the
joiner's label alone, because its hardware row only exists after it has joined.

### `FileUploader` Side-Channel — Generalisation Opportunities

**Files**: `core/src/commonMain/kotlin/org/tenshu/core/FileUploader.kt`,
`core-common/src/commonMain/kotlin/org/tenshu/core/common/transport/TransportFileUploader.kt`,
`file-service-client/src/commonMain/.../UploadSourceFileRo.kt`,
`core-common/src/commonMain/kotlin/org/tenshu/core/common/transport/TaskTransport.kt`.

The `FileUploader` `TComponent` (April 2026) replaced the misplaced `TaskExecutor.sendFile` /
`SendFileRequestChain` plumbing with a clean side-channel mirroring `StreamInteractorProvider`
(transport-level binary push, surfaced as a `TContext`-resolved component, called by ROs
before they execute their chain). It works today for `UploadSourceFileRo` against
`BinderTaskTransport`. Three follow-ups are worth tracking:

**1. Schema-level `File` (or `Blob`) parameter type with auto-push.** Today every Operation
RO that needs to push a file calls `context.fileUploader().upload(uri)` by hand before
constructing its chain. The moment a *second* operation needs the same plumbing (iOS upload,
attachment-bearing notifications, encrypted-file push), the right move is a schema trait —
e.g. `RequiresFileUpload` — paired with a parameter convention (a dedicated `File` schema
type, or by-name discovery on a `Params.URI`-style constant).
`DefaultInteractorProvider.buildTaskInteractor`
inspects the terminal operation's schema, pulls the URI param out of the chain, and calls
`fileUploader().upload(...)` automatically. The RO becomes purely declarative — same shape as
every other `OperationRo`. Not worth doing for a single caller; clearly the right move at two.

**2. Bidirectional `SideChannel<Inbound, Outbound>` abstraction.** Streams (inbound,
proxy → client, via `streamElementHandler` and `StreamInteractorProvider`) and file uploads
(outbound, client → proxy, via `FileUploader`) are both instances of "out-of-band data
alongside the request stream". A unified sealed `SideChannel<I, O>` hierarchy with
`InboundSideChannel<I>` / `OutboundSideChannel<O>` variants would generalise both. YAGNI today
— two side channels with two clean shapes are easier to read than one abstraction over both —
but the moment a third side channel appears (progress reporting for long uploads, server-push
notifications, log streaming), this is the consolidation move.

**3. Desktop file upload.** `UnixSocketTaskTransport.sendFile` is a no-op today; desktop file
upload doesn't exist end-to-end yet. When it does, the change lands in the transport (the
UDS protocol gains a binary blob frame), not in `FileUploader` or `TransportFileUploader`.
The wrapper is correctly platform-agnostic; only the wire layer is missing.

### `File.CreatedAt` from the source file's timestamp

**Files**: `file-service-proxy/.../impl/UploadFileImplementation.kt` (+ each platform's
`FilesRepositoryPlatformDelegate`, `core/.../files/util/ContentProviderUtil.kt`).

`File.CreatedAt` (and `File.ContentModifiedAt`, the date shown as "Modified") is seeded from the
source's last-modified time, read **client-side** and passed via the optional `SOURCE_MODIFIED_AT`
op param (default `null` = `now`); best-effort, a failed read falls back to `now`. Android tries
MediaStore `DATE_TAKEN` → SAF `COLUMN_LAST_MODIFIED` → `DATE_MODIFIED`, because the Photo Picker's
redacted copy makes the proxy-side `Os.fstat` stamp the *import* time (`fstat` is the fallback).
`LastModifiedAt` stays the import `now` (the honest "date added" the activity feed keys on). Still
open — the sort key, and overwrites, which have no source-date parameter — is listed in
`file-service-history.md` §3.4.

**⚠ Decision — source *mtime*, not EXIF, read client-side (holds on every platform).** EXIF
`DateTimeOriginal` is deliberately not parsed here; the true photo *capture* date belongs to the
Photos work as a `PhotoMetadata`/`capturedAt` trait (a `.pdf`/`.txt` has no capture date), not the
generic `File.CreatedAt`. The proxy cannot see the source's date, so the read stays in the client.

### `FileModification` Identity Uses Wall-Clock ms — Same-Millisecond Collision

**File**: `file-service/src/commonMain/kotlin/org/tenshu/core/file/FileModification.kt`
(`compositeKey(fileId, deviceId, timestamp) = "$fileId|$deviceId|$timestamp"`, the
`FileModificationDataIdProvider` key).

**Problem**: a history row's DataId is keyed by `compositeKey`, whose `timestamp` is a
**wall-clock millisecond** (`clock().nowEpochMillis()` at the emitting op), **not** the HLC.
The schema KDoc claims "`(deviceId, timestamp)` is globally unique (HLC is monotonic per
device)" — but that's the HLC, and the field actually stored is wall-clock ms. So two
modifications of the **same file on the same device within the same millisecond** produce the
**same DataId** → the second silently overwrites the first (a lost history event). Ordinary
user pacing (uploads/renames seconds apart) never hits this; scripted/batch ops and fast tests
do.

**How it surfaced (content-addressing §3.3.4 3b)**: before the history re-key, a move
kept a file's prior rows at the *old* path (different `fileId`) so they never shared a
`compositeKey` with the move's own new row. The re-key brings all rows to `targetPath`, so a
prior row re-keyed into the same millisecond as the move's new modification now collides. It
showed up as a **nondeterministic full-suite failure** (`renameWithinParent_emitsRenamedModification`
+ `move_reKeysHistory_soItSurvivesAtTheNewPath` passed in isolation, collided under load). The
3b workaround: **stamp the move's own new row LAST** (after the re-key) so the newest event wins
the collision — the same "keep newest" degradation a move has today — plus a `FakeClock` advanced
between writes in the affected tests. This masks the symptom for `MoveFile`; it does **not** fix
the underlying key.

**Proper fix (sized: small, one schema tweak + migration-free rollout)**: make the row identity
collision-free — append a per-device disambiguator to the key, most naturally the **HLC counter**
(so `compositeKey = "$fileId|$deviceId|$hlc"` where `hlc` is `physicalTime.logicalCounter`,
matching the KDoc's original intent). Pre-release, no back-compat is owed (content-addressing
Phase 0 reset stance), so this is a straight field-semantics change to the timestamp/key source,
not a migration. Once landed, the 3b "stamp new row last" ordering + the test `FakeClock`
advances become belt-and-suspenders rather than load-bearing.

**Priority**: low. The collision is only reachable via same-device same-ms events on one file;
`MoveFile` is defended by the stamp-last ordering. Worth doing before any other producer starts
emitting multiple history rows for one file in a tight loop, or before the history subsystem
grows a second re-keying consumer.

### Cross-Process Byte Payloads Use Content-Derived IDs (md5 / content-as-id)

**Files**:
`core-common/src/androidMain/kotlin/org/tenshu/core/common/client/InMemoryByteArrayDto.kt`
(`uniqueId = md5Hash(content)`),
`core-common/src/androidMain/kotlin/org/tenshu/core/common/client/SharedMemoryByteArrayDto.kt`
(`uniqueId = rawValue as ByteArray` — content-as-id on the SharedMemory-passthrough path),
`core-common/src/androidMain/kotlin/org/tenshu/core/common/proxy/AndroidClientConnection.kt`
(`send` — the `InMemoryByteArray` / `SharedMemoryByteArrayDto` branch + `Token.ByteArrayToken(uniqueId)`),
`core-common/src/androidMain/kotlin/org/tenshu/core/common/transport/BinderTaskTransport.kt`
(`receiveSharedMemory` / `receiveFile` → cache `put`),
`core/src/androidMain/kotlin/org/tenshu/files/util/SharedMemoryCache.kt` +
`ParcelFileDescriptorCache.kt` (close-on-overwrite),
`core-common/src/commonMain/kotlin/org/tenshu/core/common/util/BinaryCacheKey.kt` (the
crash mitigation), `drive-shared/src/commonMain/kotlin/app/tenshu/drive/data/FileMapper.kt`
(the defensive miss backstop).

Proxy → client byte payloads (a file's `data` and `preview`) are shuttled over the
Control ⇄ Drive Binder boundary as `SharedMemory` / `ParcelFileDescriptor` and cached
client-side keyed by an "id". That id is **content-derived**:
`InMemoryByteArrayDto.uniqueId = md5Hash(content)`; on the SharedMemory-passthrough path
(`AndroidClientConnection.send` when the value is already a `SharedMemoryByteArrayDto`) the
id is effectively the **entire content bytes**. Contrast the `FileByteArray.fileId` path,
which is a short, path-derived, stable handle.

**Safe, not correct.** The ids are keyed by a single injective `ByteArray.toBinaryCacheKey()`
(hex), used symmetrically on the put and get sides, plus a narrow `FileMapper` cache-miss →
null-preview backstop. ⚠ Never key them by `String(byte[])`: it is non-injective for arbitrary
binary, so distinct ids collide and `SharedMemoryCache.put`'s close-on-overwrite frees a
still-referenced handle — a `"... not found in cache"` process crash. Residual debt:

- **Content-derived, not a stable short handle.** You must have the full content to even
  compute the key (defeats lazy/streamed retrieval); and on the SM-passthrough path the
  whole payload is *both* serialised into the token stream *and* held as a `Map` key —
  multi-MB keys, O(n) hash/equality, memory pressure (the crash log literally printed a
  whole WebP as the "id").
- **Content-addressing side effects.** Two distinct files with byte-identical content share
  one cache entry; `put`'s close-on-overwrite still closes the displaced handle (only on
  *genuine* content-identical collisions now, but it remains a latent footgun). md5 is also collision-bearing in
  principle. `equals`/`hashCode` on the DTOs are by `uniqueId`, so content-addressed
  identity leaks into equality semantics and interacts with `FileMapper`'s separate
  path-based image-skip optimisation.

**Proposed Fix** (sized, cross-cutting, deferred): give cross-process byte payloads a short
*stable* id — e.g. file path + a payload-role discriminator (`data` vs `preview`) + a
version token (mtime / dataId) — instead of `md5Hash(content)` / content-as-id. Small
stable keys, no content-identical-sharing hazard, no need for the full payload to form the
key. Touches `InMemoryByteArrayDto.uniqueId`, the serialisation branch in
`AndroidClientConnection.send`, the `Token.ByteArrayToken` payload, and the file-service
value/DTO factory wiring (`SourceFileServiceCommonModule`). Proxy and client must change
together (it is a wire token). Care: DTO `equals`/`hashCode` semantics change — check the
interplay with `FileMapper`'s path-based image cache and any dedup expectations. Not a
quick fix; correctly deferred — the present behaviour is safe, just not optimal.

### File Previews Are Regenerated From the Full File on Every Access (Phase 1 cache landed; Phase 2 GC + `ImageRepository` bounding open)

**Files**:
`file-service/src/commonMain/kotlin/org/tenshu/core/file/File.kt` (`File.Relation.Preview`
— a `dynamicPropertyRelation`),
`file-service-proxy/src/jvmMain/kotlin/org/tenshu/file/proxy/jvm/JvmGetPreviewImplementation.kt`
(+ the Android `GetPreview` impl, + `iosMain/.../ios/IosGetPreviewImplementation.kt`),
`core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/PreviewCache.kt` +
`InMemoryPreviewCache.kt` + `androidMain/.../AndroidPreviewCache.kt` (the Phase 1 cache),
`drive-shared/src/commonMain/kotlin/app/tenshu/drive/data/FileMapper.kt`,
`drive-shared/src/commonMain/kotlin/app/tenshu/drive/data/ImageRepository.kt`.

**iOS** has `IosGetPreviewImplementation` (a K/N twin of the JVM/Android impls: `UIImage`
downscale, PDFKit page-0, `AVAssetImageGenerator` video poster) + the Swift per-row loader
(`DrivePreviewSurface.loadRowThumbnail`), paired with `InMemoryPreviewCache` like desktop
([`ios-drive-parity.md`](proposals/ios-drive-parity.md) §P4e + as-built §21; on-device render
verification is in the P7 matrix, §19.2). ⚠ The *common* `fileServiceProxyImplementationFactory` has
**no** `GetPreview` arm — a platform wiring only it generates no thumbnail at upload and renders
none. The follow-ups below apply to every platform.

**Phase 1** — a content-keyed server-side cache on Control:
`PreviewCache.get(contentHash, w, h)` / `put(...)`, keyed by
`sha256(sourceBytes) || w x h`. Both `GetPreview` impls (JVM + Android) consult the
cache first; on miss they run the existing decode/scale/encode path and persist the
result. Android backend is `<filesDir>/.previews/<sha256-hex>__<w>x<h>` (persistent
across process restart; survives the Binder watchdog kill that motivated the slice);
desktop pairs a durable `JvmFileDatabase` with a still-in-memory
`InMemoryPreviewCache` — so desktop, like iOS, keeps its source bytes across a restart and
recomputes only the derivative. Per-entry
hard cap 256 KiB (defends against an oversized payload). `NoOpPreviewCache` is the
default for callers that haven't opted in (system-test-utils stays unchanged).
House Rule #1 unaffected — cache lives entirely on Control's local disk, alongside
the source bytes; T-C-06 (plaintext in caches / thumbnails) is the existing
threat-model class.

**Outstanding (Phase 2+ / cross-cutting follow-ups):**

- **Phase 2 GC.** Orphans linger today — when `ReplaceFile` overwrites a file, the
  preview keyed on the OLD content hash is no longer reachable, but the entry isn't
  swept. Bounded by realm churn × per-entry cap (small), so Phase 1 ships safely
  without it; Phase 2 will add a sweep keyed by the live `File` set's content
  hashes. Same shape as the P3a → P3b split in [ADR 0017](adr/0017-file-content-two-plane-channel.md).

- **`InMemoryByteArrayDto.uniqueId` md5 → sha256 migration.** The cache uses sha256
  for new code; the existing cross-process byte-id (md5 of content, see
  "Cross-process byte payloads use content-derived ids" entry above) still
  needs the deferred wire-token redesign. The two efforts are independent — the
  cache doesn't unlock the migration, but new code that needs a content primitive
  should consistently pick sha256.

- **`FileMapper` flow re-emit hot path.** `FileMapper.map` still calls
  `file.preview?.get()` for every image on every DB-change re-emission. The cache
  makes each call cheap (one `sha256` of source + map/file lookup), but the
  shoulder-tap pattern remains; an LRU on the client side (`ImageRepository`
  currently caches *full-res `data`* bitmaps, not previews) would close the loop.

- **Two-plane placement ([`sync/design.md` §2.7](sync/design.md#27-file-content-two-plane-channel)).**
  Phase 1 is purely local. The longer-term question — whether the manifest (metadata
  plane) carries a tiny preview ref so list views never touch file bodies — remains
  open and rides the §7.1 manifest decision.


### `SqliteObjectStore` Residency — Metadata-at-Scale and the JS Twin Remain

The JVM store is bounded on both halves ([`bounded-store-cache.md`](proposals/bounded-store-cache.md)
as-built): a bounded write mailbox (512, blocking send, dedupe-by-hash, writer on a dedicated
thread) and C-lite size-tiered retention (rows ≤ 8 KiB resident; larger rows pinned only until their
write commits, then point-`SELECT` on a dedicated WAL read connection; a resident large-hash `Set`
keeps absent probes O(1)). That closes the thumbnail residency ceiling (~8k–12k files) by
construction; bulk payloads go to the content plane (`Workout.Streams` is the first instance), and
bulk writes ride the task-scoped sync lane (`core-task-composite.md`; the TS-6 tail is deferred,
`core-background-processing.md` §10). What remains open:

- **Metadata-at-scale end states.** Full-LRU (the C spike) or no-app-cache
  (SQLite page cache) if *metadata* itself outgrows RAM (~1M+ entities).
- ⚠ **The JS twin (`IndexedDbObjectStore`) is deliberately forked**: it cannot do
  synchronous read-through, so it still bulk-loads everything resident — and for the same reason
  it **refuses the delivery-log orphan sweep** (the safe direction; `core-local-store-orphan-gc.md`
  §7.1), so a web store keeps every block row it ever saw. Needs its own answer if the web runtime
  ever bulk-imports.

### The iOS shared replica keeps the whole realm (2026-08-02)

Every route-1 iOS app shares one replica that holds the **entire realm**, because each runtime
registers every service module: Drive's held ~107k rows, among them ~10.4k Fit workouts plus its
siblings' other rows, which Drive never reads (measured 2026-08-02). Disk, sync bandwidth and battery pay for it. Design pass, no decisions:
[`ios-route1-replica-scope.md`](proposals/ios-route1-replica-scope.md). ⚠ The launch-time half
(the file store hydrating every row at open, a `0x8BADF00D` watchdog kill at ~59k rows) is gone:
that store was deleted on 2026-11-05 (`ios-shared-store-sqlite.md` B.22.9); the SQLite store
keeps no row cache.

### `StreamDispatcher` / `StreamInteractorImpl` Should Be `internal` to `:core-common`

**Files**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/StreamDispatcher.kt`,
`core-common/src/commonMain/kotlin/org/tenshu/core/common/StreamInteractorImpl.kt`,
`file-service-client/src/commonTest/kotlin/org/tenshu/core/common/FileServiceUnitTest.kt`
(`flowOperation_CreatesCorrectFlow`).

`StreamDispatcher` was moved from `:core` to `:core-common` (April 2026) as the
implementation contract between `DefaultInteractorProvider` and `StreamInteractorImpl`.
It is conceptually `internal` — no consumer outside `:core-common` should construct or
drive it — but is currently public because `FileServiceUnitTest` constructs a
`StreamInteractorImpl` directly and calls `setInitialResult` / `dispatchElement` on it
to simulate stream emission. Kotlin disallows a public class with an `internal`
supertype, so both classes have to stay public until the test is refactored.

**Proposed Fix**: rewrite `flowOperation_CreatesCorrectFlow` to drive the dispatcher
through the public `DefaultInteractorProvider.dispatchElement(...)` API instead of
hand-constructing a `StreamInteractorImpl`. The test would then exercise the same
end-to-end path the production code uses, which is also a quality improvement —
constructing the implementation type by hand bypasses the find-or-reuse and
allocate-or-reuse logic that real flows depend on. After the refactor, mark both
`StreamInteractorImpl` and `StreamDispatcher` `internal` to `:core-common`.

---

## Time & Clocks

### `Instant` — Value Type vs. Composite Design Question

**Files**: `core/src/commonMain/kotlin/org/tenshu/core/time/Instant.kt`.

`Instant` is a `TInteger`-extending value type with a `toEpochMilliseconds()` method on
the interface. HLC, by contrast, is a `Composite` with explicit fields. The
"interface-method-on-a-value-type" shape was the reason `UnixInstantRo` used to throw —
ROs can't honestly implement a method that reads a value, only the chain that fetches
it. The RO has been removed; the broader question of whether `Instant` should be a
`Composite` (matching HLC) or stay a value type with no instance methods (just
extensions like `Instant.toKotlinInstant()`) is left for a future cleanup. No urgency;
flag if a third "value type that wants methods" appears.

---

## Sync Decoupling: Channel-Based SyncBlock Generation

Built as Facet A of [ADR 0015](adr/0015-loop-db-stream-unification.md): `SyncObserver.onDataSaved`
only `trySend`s onto an UNLIMITED channel, and one consumer coroutine does the serialize + AEAD + HLC
tick + `pendingBlocks` enqueue; `awaitEmitIdle()` is the drain-and-join point. ⚠ Deliberately left
(ADR 0015 decision 6 / When-to-revisit #1–#2): the HLC CAS loop stays, because only the *outbound*
path is single-writer and ingest still advances the clock; and the consumer runs on `sessionScope`,
not a service tier, until a sessionless emit path exists.

---

## Proxy Background Lifecycle: Loops, Database Updates & Stream Management Are Entangled

**Files**: `sync-service-proxy/.../SyncProxyInitialization.kt`, `SyncScheduler.kt`,
`ScheduleDrainLoop.kt`; `control-shared/.../data/ConstraintSyncGate.kt`, `di/ProxyModule.kt` +
`PlatformProxyModule.kt`; `core-common/.../proxy/ExecutionContext.kt` (`sessionScope`,
`runInitialization()`, `destroy()`), `ClientRegistry.kt`; `core-common/.../util/PagingUtil.kt`
(`TList.pagingSource`'s invalidation observer).

**What holds** ([ADR 0014](adr/0014-proxy-session-lifecycle-teardown.md)): the background loops
(`SyncScheduler`'s poll loop, `ScheduleDrainLoop`, `ConstraintSyncGate`'s constraint observer) run
on the session's owned `sessionScope`, and teardown is a deterministic *suspend* cancel-**and-join**
before `destroy()` (`shutdownAndJoin`; pinned by `SyncProxyLifecycleTest` and
`ExecutionContextQuiesceTest`). `ClientRegistry`'s session maps are copy-on-write
`AtomicReference`s (`ClientRegistryConcurrencyTest`).

⚠⚠ **A teardown path is only as good as the set of sessions it can still reach.** Anything that
registers a session must do so *before* running work that can re-enter the registry.
`ExecutionContext`'s initialization hook starts the background wiring, which re-enters
`getExecutionContext(clientId)` (the in-process `SessionAwareLocalTaskExecutor` session provider,
the schema announce, any client-side stream the wiring opens). Run from the constructor, each
re-entry found no session, minted another, and overwrote the previous in `clients` — three sessions
per proxy start, two never quiesced, their loops ticking for the process lifetime. So
`ClientRegistry` calls the idempotent `runInitialization()` straight after `clients.update(...)`;
pinned by `ClientRegistryTest.getExecutionContext_reentrantLookupDuringInitialization_reusesTheSameSession`.

⚠ **A wandering test failure in `:control-shared` / `:drive-shared-ui` is a leaked coroutine, not
flakiness.** Loops that outlive `stopKoin()` find the Koin-backed `TContext.components` map empty
and throw `NullPointerException: null cannot be cast to non-null type DataFactory/SchemaProvider`
(or `ClosedScopeException`) on `Dispatchers.Default` workers; Gradle attributes an uncaught
background-thread exception to **whichever test happens to be running** — including
`kotlinx.coroutines.test`'s `UncaughtExceptionsBeforeTest`. Look for the leak; do not retry the
victim.

### The deeper entanglement

What remains open: **the loops, the database-update path, and stream management have no shared
lifecycle boundary.** Stream observers (`StreamRegistry`, the reactive `.flow()` the paging
invalidation observer collects, relation maintainers) and the synchronous
`saveData → ObserverRegistry` fan-out each manage their own subscription lifetime ad-hoc, and each
facet (sync block generation, array listeners, schedule drain, constraint gate, paging
invalidation) invents its own loop + scope + teardown instead of sharing one — so "is this coroutine
still allowed to touch the context?" has no single answer.

### Proposed direction

Every background loop / observer launches on the one owned session scope (or a dedicated child),
with the single deterministic teardown (stop producers → drain → destroy) as the only way out, and
the loop / database-update / stream-management plumbing unified with the channel-based sync
decoupling and the `WriteOrigin` / event-driven-listener `Database` surface rather than
re-invented per consumer.

**When to pick up**: when the proxy lifecycle is next touched (e.g. bgproc Phase 6
`PlatformScheduler`, a sessionless emit path), or if the teardown-race NPE starts failing CI
non-intermittently. Must be verified on **all** targets including Android / JS, since the scopes
live in per-platform DI.

---

## Sync, Pairing & Recovery

### Takedown audit rows have a 3-year retention and no purge (2026-10-29)

The relay's `share_takedown` audit rows (acceptable-use §3) are kept **3 years from the takedown**, then
deleted (maintainer decision 2026-10-29). Nothing deletes them yet: the table's append-only triggers
refuse `DELETE`, by design (`share_takedown_no_delete`, relay migration 4). ⚠ The table also **retires the
`shareId`** (`SqliteShareStore` refuses `POST /share` for any id with a takedown row), so deleting a row
would let the sharer re-publish the reported URL. **Fix:** a new relay migration step that replaces the
triggers so a scheduled sweep can, for rows whose `taken_down_at` (epoch **milliseconds**) is older than
3 years, blank `owner_id` and `operator_note` (or move the bare `share_id` to a retired-id table and
delete the row). The bare random id, unlinked from its owner, stays to keep the URL retired.
**Size:** small. **Due:** before 2029-10, when the first rows fall due.
See [`take-down-a-share-link.md`](how-to/take-down-a-share-link.md).

### An undescribed content is never freed on delete, and stays charged (2026-10-23)

The describe that tells the relay a content's refs is best-effort and does not self-heal
(`sync-service-proxy/.../SyncObserver.kt:2806-2807`, `:2829-2837`); only the backfill re-describes,
and it latches once per `ContentBackfillMarker.BACKFILL_FORMAT` (`ContentBackfillMarker.kt:106`).
When such a file is deleted, `free()` queues only `content_ref` rows
(`sync-relay-server/.../storage/SqliteContentFreedStore.kt:43-56`), so it is marked freed with
nothing queued and its blobs stay; `heldBytesIn` joins through `content_ref` (`:204-221`), so they
stay charged as well. A leak, never a loss. Policy §4.4 discloses it.
**Fix direction:** free by the content's derivable manifest when no description exists, or re-arm
the backfill whenever the relay reports a content with an edge and no description.

### One undescribed content pauses byte deletion for its whole account, with no age bound (2026-10-23)

`ContentReclaimJob.drain` returns without deleting while any content of the owner is edge-only with
a stored manifest (`edgeOnlyWithManifestIn`, `ContentReclaimJob.kt:337-351`; early return
`:183-187`). That was meant as a declaration in flight, but it is also the **persistent** state of the
entry above, so one file can hold every later drain for its owner indefinitely. The owners left to
drain are kept only in memory (`pendingDrain`, `ContentSweepJob.kt:35-37`, `:109`), so after a
restart a paused owner waits for its next free. Visible only as `pausedOnEdgeOnly` in the sweep log.
**Fix direction:** age-bound the pause (an edge-only content older than N hours stops pausing), and
persist the pending-drain set or derive it from `content_free_queue`.

### A folder delete never drops its files' change records; deletion notifications keep the path (2026-10-23)

The tombstone GC iterates tombstones (`MailboxStore.kt:183-205`) and drops bodies whose target hash
equals the tombstone's (`SqliteSyncMailbox.kt:342-355`). A folder delete emits one tombstone, so the
change records of the files deleted through it, and the parent's listing blocks, are never dropped.
`DeleteFileImplementation` also saves a synced `File`/`FolderDeletedNotification` carrying the deleted
**path** (`file-service-proxy/.../DeleteFileImplementation.kt:106-122`), which nothing drops. E4
compaction would cover both and never runs. Policy §4.4 discloses it.
**Fix direction:** carry the cascade's holder hashes into the tombstone GC (the push already sends
them for edges, `SyncRouting.kt:477-496`), and give deletion notifications a retention.

### Deleting a file does not revoke its share links (2026-10-23)

A share link stores its own ciphertext (`file-service-proxy/.../share/ShareLinkImplementation.kt:229`
→ `SqliteShareStore.putChunk`), and nothing on the delete path revokes it. The share store collects a
share only 30 days after revoke or expiry (`SqliteShareStore.kt:109-157`), so a non-expiring link
keeps a deleted file's content stored **and viewable** indefinitely. Policy §4.4 discloses it.
**Fix direction:** revoke (or offer to revoke) a file's links in `DeleteFile`, or show them in the
delete confirmation.

### The join-flow approve can race `stopPairing`'s key zeroing (2026-10-02) — by reading, not reproduced

`SyncManagerImpl.stopPairing` zeroes the context's ephemeral private key in place;
`ApprovePairingRequestImplementation` reads the context and then runs the key agreement on that
same array, with no lock between them. A `stopPairing` landing in that window (deny or expiry on
another thread, microseconds wide, not relay-triggerable) would have the agreement run on a zeroed
scalar — a value anyone holding the joiner's public key can compute. Pre-dates the T-P-14 change
and is untouched by it. The fix is for the context to hand out the key under the same guard that
zeroes it.

### ⚠⚠ A `Folder.FILES` list row referenced a member row that does not exist — mechanism ESTABLISHED (two silently-dropped writer batches) and FIXED; the trigger is open (2026-09-13)

**Files**: `core-common/src/jvmMain/…/db/SqliteObjectStore.kt` (`commitWithRetry`, `open`),
`sync-service-proxy/src/commonTest/…/FolderFilesDanglingMemberTest.kt`,
`core-common/src/desktopTest/…/SqliteObjectStoreCommitFailureTest.kt`, `scripts/store-relation-audit.py`,
`scripts/DataIdHashSearch.java`. **Full write-up:**
[`proposals/drive-folder-files-dangling-member.md`](proposals/drive-folder-files-dangling-member.md).

A desktop `Folder.FILES` list row held two hashes with no row. The mechanism is proven (doc §7.1):
`SqliteObjectStore`'s writer had no `busy_timeout`, and a batch whose `COMMIT` failed was rolled
back and dropped with no log line, so a later batch's list row named members that never reached
disk. The writer now has a busy timeout, a bounded in-place retry with a `WARN` per attempt, a loud
drop as the floor, and an advisory `objects.db.lock` that reports a second opener at open. ⚠ Every
such `WARN` (retry, drop, contended lock, core-version wipe) is also persisted in **`objects.db.log`
beside the store**: under launchd the Control daemon's stdout goes nowhere. ⚠ Process death is
**not** a route to this shape — a kill loses a suffix of the FIFO mailbox and every writer stores a
member before the list naming it (pinned both sides).

⚠ **The trigger is open**: the interleaving (fail, commit, fail within one page) points at a
**competing writer taking short transactions** on the file — a lone WAL-mode process cannot `BUSY`
itself, and a second in-process store is excluded by code. Routine candidates are a second Control
process on the same store (`control-desktop/Main.kt`: *"no single-instance guard yet"*;
⚠ `reinstall-all-apps.sh`'s `pkill -f /Applications/Tenshu` does not reach a dev-run Control) or a
`sqlite3` shell mid-write. The original instance's writer is not recoverable (doc §7.2 (3)); next
time, the `WARN` names the SQLite error and `fs_usage` on `objects.db` during a re-seed names every
opener.

⚠ **Adjacent, open — a member learned only through its parent's block can never be deleted by its
tombstone.** The create-only nested write in `DbEntryMapper.writeValuesToDb` mints member rows through
`saveEntry` with no observer, so `TombstoneRegistry` never indexes them and `processDeleteBlock`'s
`resolveDataId` answers null. The row lingers as a zombie (the *safe* side of the invariant above —
`aTombstoneForAMemberKnownOnlyThroughItsParentLeavesNoDanglingReference` pins that much). The fix
is to index nested-created members (`rememberDataId` per member after a merge save is the cheap
shape).

### ⚠⚠ Pairing sweep 2026-08-28 — one real gap, two threat-model overclaims, one question

A sweep of the pairing implementation against `docs/sync/threat-model.md` §8.1's T-P-* entries.
**The cryptographic shape holds up**, and what it found is closed: the pending-request mailbox is
bounded (`PairingRequestInbox`, oldest-first eviction, `PairingRequestMailboxBoundTest`; threat model
**T-P-15**), `POST /sync/pairing/request` is rate-limited per IP
(`PAIRING_REQUEST_RATE_LIMIT_PER_HOUR`), T-P-05 states only the preventions that exist, T-P-07's
entropy reads 122 bits, and the offer flow shows the SAS before approval
(`sync-pairing-scanned-announce.md`; ⚠ the announce is best-effort, and without it the comparison is
post-hoc). ⚠ The lesson from the mailbox bound: it was first pinned by a
test that flooded **one** owner, the shape the fix was written for and not the shape of the hole (a
fresh `ownerId` per request grew the outer map).

What is still owed:
- ⏳ **Not re-audited: `POST /sync/pairing/hub-offer`**, the other credential-less pairing write. It
  carries no `rateLimit(` wrapper (`SyncRouting.kt`).
- ⚠ Not verified in this sweep: the hub flow (T-P-09..T-P-12) and the EPC pairing path.

### ⚠ A device's identity is a client-minted random UUID that nothing binds to its key (2026-08-28)

**Why this is written down.** Chasing the joiner wedge below, the phone turned up in the roster
**twice** under two ids for one physical device. That is not a bug in any one code path — it falls
out of what identity *is* here, so the question is worth stating once rather than re-deriving it at
each occurrence.

**What decides identity today.** `SyncManagerImpl.kt:50`:

```kotlin
override val currentDeviceId: String = storageDelegate.getString(Keys.DEVICE_ID) ?: run {
    val newId = generateId()                       // Uuid.random() — a plain random v4
    storageDelegate.storeString(Keys.DEVICE_ID, newId)
    newId
}
```

A **client-minted random UUID**, created lazily on first read. The relay neither assigns nor
validates it — it accepts whatever `(deviceId, publicKey)` pair arrives under a valid master
delegation. The A4 signing keypair is minted separately and stored via `storeSecret`. ⚠⚠ **Nothing
binds the id to the key**, and the two live in different buckets whose durability differs per
platform:

| | device id (`storeString`) | A4 key (`storeSecret`) |
|---|---|---|
| Android | `tenshu_plain_storage.xml` — plaintext prefs | `tenshu_secret_store.xml`, AES-GCM under a non-exportable AndroidKeyStore key |
| iOS | Keychain, `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY` | Keychain, AEAD-sealed under a wrap key |
| desktop | a plaintext `.properties` file | `MasterKeyStore` |
| web / JS | IndexedDB | **`storeSecret` *is* `storeString`** — no separation at all |

⚠ **Checked, so nobody re-derives it:** the split is *not* currently a desync vector on Android —
every app manifest sets `allowBackup="false"` and a data-clear takes both files, so id and key share
a fate. On **iOS** both survive an app delete (which is why a reinstall keeps the identity —
[[ios-reset-uninstall-not]] territory). On **desktop** they are two independent files with
independent lifetimes, which is the one platform where losing one without the other looks plausible.

**How comparable systems avoid this.** Signal assigns device ids **server-side** — primary is device
1, each linked device is handed its id during provisioning. Two properties follow that this design
lacks: a client cannot invent an id the server does not know, and the server's device list is *the
same list* that gates access and renders in the UI. Here those are two lists — the relay's
`KnownDevicesStore` (authoritative for access) and the synced `Device` row (descriptive, for UI) —
which is the split *"`Device` rows have no regeneration path"* (below) is also about. ⚠ Note a
relay-assigned id would **not** breach House Rule 1: the relay already stores device rows with
public keys, and that is metadata, not decrypted user data.

**Three ways out, cheapest first — undecided, and the decision belongs with the maintainer.**

1. **Derive the device id from the A4 public key** (a hash of it). Id and key then cannot
   desynchronise on any platform *by construction*: keep the key → keep the id; lose the key → you
   genuinely are a new device and the id says so. No wire change; the relay still sees
   `(id, publicKey)`. ⚠ Costs a migration — existing devices carry random ids that do not match
   their key hash, so either grandfather them (which blunts the benefit) or accept a one-time
   re-admission of every device. Pre-release that is close to free ([[prerelease-migration-cost]]).
2. **Leave identity as-is** and rely on `ReadmitDevice` as the recovery path. Duplicate rows stay
   possible whenever local state is replaced.
3. **Relay-assigned ids.** Closest to Signal and the strongest guarantee, but a substantially
   bigger protocol and pairing change.

⚠ **Prevention and recovery are not alternatives.** `ReadmitDevice` (shipped 2026-08-28) is
*recovery* and is needed under all three; options 1 and 3 are *prevention* and would stop the
duplicate-row class arising at all.


### ⚠⚠ A joiner that loses its relay registration is wedged permanently — both repair paths are closed (2026-08-27)

**Source:** diagnosed on a real Android phone (OnePlus `CPH2581`) against the live relay, entirely
from the device's own store + `shared_prefs`. This is the field case for item 3 of *"A Device the
Relay No Longer Recognises — Remaining Surface After the 2026-08-17 Fix"* (below), which calls a
joiner's `401` terminal.

**Observed.** Drive showed `SyncBannerState.DeviceUnrecognized` continuously for hours, while the
phone appeared — **twice** — in the device list on desktop and on iOS. Its `tenshu_secret_store.xml`
held exactly `sync_device_signing_key`, `sync_joiner_payload`, `sync_owner_id` — **no
`sync_recovery_phrase`, no master key**, i.e. a joiner by construction — under a `sync_device_id` the
roster did not hold.

⭐ **Read the sync cursor, not the prefs, to tell "never synced" from "lost its identity later".**
`Keys.LAST_SYNC_TIMESTAMP` is retired — `lastSyncTimestamp` reads `objectStore.loadSyncCursor()`,
a reserved store row — so an absent pref is true of every device, and `realmRelayRegistered = true`
follows automatically on every healthy joiner. Neither is a signature of this state.

```bash
# reserved row: hash = Long.MIN_VALUE, type `tenshu/sync/cursor`
sqlite3 objects.db "select hex(data) from objects where hash=-9223372036854775808;"
# layout: u32 typeLen | type | u64 hash | u32 fieldCount | u8 kind=2 | i64 value
```

⚠ The `getRecoveryPhrase` failures in such a device's error log are not repair attempts: they are
`AdmissionMigrationObserver.maybeSelfAnnounce`'s host-vs-joiner discriminator, run once per Control
session. They prove the device is a joiner and nothing about how often it tried to repair.

**How the identity is lost.** Settings → **Forget this device** (`ForgetThisDeviceImplementation`):
its step 1 is a self-revoke that `runCatching { … }.getOrElse { LEFTOVER_ROW }`s — *a failed
retirement never blocks the wipe* — so the store is dropped and `DEVICE_ID` (a `CUSTODY_SLOT`) purged
while the old `Device` row stays in the realm. On a stranded device that push **always** fails, so
forgetting it deterministically yields two rows for one phone. A pairing handshake leaves
`sync_device_id` unchanged, so a re-pair never re-mints an id.

**Root cause — no non-A1 path to a relay registration exists.**
`SyncScheduler.recoverRegistration()` returns at `registrar.masterIdentity() ?: return` (the repair
re-asserts genesis, which needs A1), and `RealmRegistrar.ensureRegistered()` is owner-only end to end
(`syncManager.currentMasterKeyPair ?: return null`). `HostPairingMonitor.maybeReregisterDevice` and
`ReregisterDevice` are A1-gated too. ⇒ every request `401`s, `RejectionBackoff` blocks, the banner is
raised, and no code path on the joiner clears it. ⚠ Clearing the `realmRelayRegistered` latch, or
treating `realmRelayRegistered && lastSyncTimestamp == 0` as unproven, fixes nothing — the next line
is the owner-only return.

**Consequence.** Queued work survives but is stranded (that phone held 4 `pendingContentEmit` rows
and 4 pending `sync/block`s), so *"clear app data and start over"*, the obvious user move, silently
discards it (queues are client-side).

**What recovers it today.**
- ⭐ **Re-pairing from a device that still holds its id reunifies the row**:
  `ApprovePairingRequestImplementation` builds the row from the `PairingRequest` payload and `Device`
  is keyed by `deviceId`, so the approve is an upsert. The rule is about **role, not direction**:

  > A stranded device must be the **joiner/offerer**. It can be neither the **host**
  > (`GET /sync/pairing/requests` is JWT-gated) nor the **approver**
  > (`/sync/pairing/offer-response` verifies against the responder's *registered* A4 key).

  `PairingWizardViewModel` enforces this: on `deviceUnrecognized` it tears down the host session
  and switches to the offer role.
- **`ReadmitDevice`**, an action on `DeviceItem` (Compose) and the iOS roster row, offered on every
  authorised peer row: any A1-holder mints the delegation from the synced `Device.publicKey` and
  `POST /sync/admit-device`s, with no contact with the stranded device. ⭐ Safe to offer
  speculatively because `RealmRegistrar.admitDevice` is idempotent and grants no new authority, and a
  *revoked* device cannot appear (revocation deletes the row whose `publicKey` the call signs over).
  ⚠ It must stay an explicit, named user action, never automatic.

⚠⚠ **Readmission cannot be made automatic: a stranded device cannot report that it is stranded.**
Saying so needs the sync channel the relay is refusing, and from the A1-holder's side a stale
`deviceLastSeen` is indistinguishable from a device switched off. The human is the transport, which
is why the banner has to name the other device rather than offer a button.

**Still open.**
- A user whose only other device is itself a joiner has **no recovery at all** — both exits need an
  A1-holder.
- ⚠⚠ **Deferred, and NOT a drop-in — joiner self-admission via its own A33 chain.** After a relay
  data wipe the relay holds **no revocations**, so a revoked joiner could re-admit itself, whereas
  wipe-recovery deliberately funnels through A1. Owes a threat-model amendment (A26/A27/A33, HR #2)
  before any code.
- **The copy.** The banner reads as advice the user can act on alone; on this device it is an
  instruction to go and use a *different* device, and it should steer the user away from "Forget this
  device", which converts a clean re-pair into a permanent duplicate.

**Diagnostic recipe** (debug builds; no root, no probe build):

```bash
adb -s <serial> shell run-as app.tenshu.control.android cat shared_prefs/tenshu_plain_storage.xml
adb -s <serial> shell run-as app.tenshu.control.android cat shared_prefs/tenshu_secret_store.xml \
  | grep -oE 'name="[^"]+"'          # key NAMES only — never dump the values
```

⚠ `adb logcat` shows nothing for this: the sync loop `println`s only on a repair *attempt*, and on a
joiner the attempt returns before printing. The store and `shared_prefs` are the evidence.

⚠ **A duplicate `Device` row is also what a failed join leaves**: the host authors the joiner's row
and posts its response before the joiner decrypts anything (*"The joiner's decrypt fails its MAC
check"*, below). Before re-pairing a wedged device, confirm its Control build is current
(`adb shell dumpsys package app.tenshu.control.android | grep -i lastUpdateTime`).

See also: *"`Device` rows have no regeneration path"* (below) for the authoritative-vs-descriptive
framing this sits on.

### An interrupted "Forget this device" on an A1-holding iOS device silently undoes itself (2026-10-06)

**Source:** read-only code trace, 2026-10-06, for `ios-shared-store-sqlite.md` §5.1; not reproduced
on a device. The maintainer accepted the window; this is the follow-up.

`ControlSurface.wipeDeviceState()` retires the device's own row first (`retireThisDeviceRow`:
`RevokeDevice` on its own id, then a poll of up to 8 s for the tombstone to drain,
`ControlSurface.kt:1516-1546`) and purges custody last (`:1475-1476`). A kill between the two
leaves the row retired at the relay (`SyncRouting.kt:492-528` → `DeviceRegistry.revokeIfNotStale`,
which deletes the registration and writes the removal ledger) and the identity in the Keychain.
At the next launch `landingStateKey()` still finds an owner id (`ControlSurface.kt:679-681`), the
`401` reaches `recoverRegistration()` → `reregisterThisDevice`, and `POST /sync/admit-device`
admits the same device id without consulting the removal ledger (`SyncScheduler.kt:1354-1374`,
`RealmRouting.kt:185-258`; deliberate, threat model §5 *Realised — device re-registration* (b)).
Control republishes the route-1 bridge (`ControlIosApp.swift:351`, `ensureDriveProvisioned`). So
the forget is undone with no word to the user. *Inferred:* if the store was already wiped, the
device re-creates its own `Device` row and reappears in peers' rosters; the exact peer-side
outcome was not determined.

Repeating the forget completes it (every step is `runCatching` or non-throwing). ⚠ **More
reachable once every wipe is chunked** (D13): the wipe sits between the two steps and takes
seconds, where it took milliseconds.

**Candidate fix (small):** a durable "forget in progress" latch, written before the retirement and
cleared after the custody purge; a launch that finds it finishes the forget instead of
re-admitting.

### A removed pairing-joiner on iOS gets no explanation (2026-10-06)

**Source:** the same trace. Reached by an interrupted forget (above) on a device with no A1, and
(*inferred*, the same relay state) by an ordinary removal from another device.

The relay answers `401 "Invalid token"` for an unregistered device (`JwtAuth.kt:117-136`) and the
joiner cannot repair itself (*"A joiner that loses its relay registration is wedged
permanently"*, above). Both pairing routes then refuse its retired id with a terminal
`403 device-removed` (`SyncRouting.kt:815-823`, `:1004-1015`), and a peer's `ReadmitDevice` fails
because the row is gone (`ReadmitDeviceImplementation.kt:71-84`). On iOS nothing says so: Control
opens to its normal tabs or lock screen and looks signed in while nothing syncs; the only copy is
inside the pairing sheet (`PairingView.swift:904`, `:955-957`), and it says "Pair this device
again", which is the step the `403` refuses. The remedy that works, "Forget this device" then
pair again, is never named.

**Candidate fix:** a "this device was removed — forget it to rejoin" surface in Control-iOS, driven
by the existing `CONNECTIVITY_DEVICE_UNRECOGNIZED` health reason (`SyncHealthStatus.kt:132`) or
the terminal `403`. Related: *"a `Device` row that outlives the identity it describes"* (the
mirror image, below).

### ⚠⚠ A failed `revokeDevice` leaves the roster asserting a device the relay has already stopped accepting — and records nothing about why (2026-08-27)

**Source:** same 2026-08-27 investigation as *"A joiner that loses its relay registration"* (above). The desktop recorded a
`revokeDevice` failure at 12:29:26Z; the revoked-looking phone remained in every device list, and
a second `Device` row for the same physical phone appeared alongside it.

**Observed.** Two `tenshu/identity/device` rows, same `name` (`"OnePlus CPH2581"`), different ids —
the old identity and the new one, **neither of them tombstoned** — both rows were still present in
the store, and a tombstoned `Device` row is deleted from it. Desktop and iOS both render this as two
live devices.

**Why the row survives.** `RevokeDeviceImplementation` is a multi-step, non-atomic sequence, and
its **first** step is a precondition that throws:

```kotlin
executionContext.database.getData(deviceDataId)
    ?: throw TenshuException(GenericError.create(…, "Local Device entity not found for $deviceId; nothing to revoke"))
```

Everything that would actually remove the device — `removeFromArray`, `deleteData`, the
`DeleteBlock` tombstone (which is also what carries `revokedDeviceId` to the relay so it can drop
the `KnownDevicesStore` registration), and the best-effort admission strip — comes *after* it. So a
throw here is a **complete no-op**, and any throw further down leaves a partial state: the relay
strip is explicitly best-effort, but steps 1–3 are not transactional with it.

⚠⚠ **The failure is undiagnosable after the fact.** `ErrorEventRecorderImpl.kt:58` keeps
`internalDetail` **only** when the error is an `InternalError`:
`(error as? InternalError)?.internalDetail?.get()`. `RevokeDeviceImplementation` throws a
**`GenericError`**, so the recorded `ErrorEvent` carries the operation id and nothing else — the
message naming *which* device and *why* is discarded at the recorder. Confirmed by decoding the
row: the blob holds only the event id, error type, operation id, author device, app, version and
OS. ⇒ We know a revoke failed; we cannot tell what it was aiming at.

**Consequence.** The roster **over-reports**: presence in the device list is read by users (and was
read here) as "this device is fine", when the authoritative state — the relay's
`KnownDevicesStore` — may already have dropped it. That is the same authoritative-vs-descriptive
drift as *"`Device` rows have no regeneration path"* (below), in the opposite direction: that entry
covers the roster going **empty** while access still works; this one covers a row **persisting**
after access is gone. ⚠ The existing entry's closing warning — *"a device list is **not** evidence
that pairing failed — check the relay's `device` table"* — has a converse that is now also
demonstrated: a device list is not evidence that pairing **succeeded**.

**State of the fix.** The precondition throws a typed `NotFoundError`, so a failed revoke no longer
logs as the contentless `tenshu/result/error/generic`; and a missing local row no longer makes the
operation a no-op — the tombstone is keyed by the entity-id hash, so the eviction proceeds (degrading
to the epoch rule, no captured A33 key) provided `wasRealmMember` finds independent local evidence
(`AdmissionKeyAnnouncement`, `AdmissionCertGrant` or `DeviceLastSeen`). ⚠⚠ Deliberately **not**
unconditional: `currentRealmEpoch` is the `Device`-tombstone count, so a tombstone for an id that
names no member raises the epoch for *every* device and supersedes admission capabilities.

**Still open:**
- ⚠ The **target device id is not recorded** — freetext survives only as
  `InternalError.internalDetail` — so "which device" is unanswerable from the log alone. Needs
  structured error fields on `ErrorEvent`, a bigger, separate change.
- ⚠ **The fully-wiped-store case** in *"`Device` rows have no regeneration path"* is not closed —
  those evidence rows live in the same store, and the UI has nothing to list either.
- ⏳ Surface a failed revoke in Control's UI. It fails into an error row the user never opens, and
  the device stays in the list looking healthy. Folds naturally into item 1 of *"A Device the Relay
  No Longer Recognises — Remaining Surface"* (the Sync-dashboard row).
- ⏳ Steps 1–3 are not recoverable as a unit; a throw between them leaves a partial state. A durable
  "revocation attempted for X" marker the next A1-holder removal re-drives would close it — the relay
  strip already relies on exactly that re-drive.

⚠ **Not established:** the `revokeDevice` call recorded at 12:29:26Z cannot be tied to this phone —
the error event does not record its target. The defects here are the no-op-on-throw, the discarded
detail and the stale row; the trigger is inferred.

### The iOS test suite is real, green, and run by nothing (2026-08-26)

⭐ **The cross-platform crypto vectors this project needs exist.**
`core-common/src/iosTest/.../LibsodiumCryptoBackendsTest` pins the iOS backends against **JVM
vectors**: X25519 shared secret, Ed25519 derive/sign, Argon2id with the production salt, and
`aead_decryptsJvmProducedBlob` — the iOS backend decrypting a blob a JVM produced.

⚠⚠ **No gate runs them on its own.** They run under `./gradlew iosTestCheck` (an enumerated aggregate
over the modules with iOS tests, refusing off macOS), which is deliberately **not** in
`scripts/run-gate.sh` — that gate must stay runnable on Linux, where every iOS task is silently
skipped — and the `ios.yml` CI lane that runs `iosSimulatorArm64Test` is dark (*"CI blocked by GitHub
Actions billing"*). An iOS-vs-JVM divergence is caught only when someone runs `iosTestCheck` by hand.
Its first run found two compile/crash defects that had sat unnoticed for two months.

⚠⚠ **The flow level is a harder gap, and it is deliberate.** `PairingVmFlowSystemTest`
(`:system-test-utils`) is a real two-party pairing test — real proxies, in-process relay, real
`PairingWizardViewModel`s — but **both parties are JVM**, so it cannot see an iOS-host↔JVM-joiner
divergence. And iOS tests are **disabled by policy for every `-proxy` and `-test-utils` module**
except `:inference-service-proxy` (`build.gradle.kts`: *"proxy-side code that does NOT run in the iOS
app under proxy-mediated EPC"*) — a comment written before Control-on-iOS put the whole proxy on the
device under route 1. That exclusion covers `PairingCompletion`, `PairingAdmission` and
`PairingKeyBlob`, so **a K/N-vs-JVM behavioural gap in the pairing flow is untestable by
construction**. Lifting it for the pairing path is the slice that would catch this class.

---

### ⚠⚠ Contacts-on-iOS: the row is WRITTEN but never linked into its collection (2026-09-06)

**Cause, closed:** service-relation membership is written by `RelationMaintainer`, a data observer
installed by each `init<X>Service()`, and `ClientRegistry.mintSession` publishes a session's
`ExecutionContext` **before** `runInitialization()` (load-bearing: initialisation re-enters
`getExecutionContext`). A save served in that window was stored but never added to its relation
array, and nothing re-observes a completed save — so the row was invisible **forever** (seen as
`ContactsLocalRuntimeTest.aWrittenContactIsReadBack` failing ~7 in 10 whole-class runs: the person
on disk, its `tenshu/core/list` empty). `mintSession` now calls `installRelationMaintainer()` before
`publish`. ⚠ Any service relation could lose a member this way; in the harnesses only `Person` was
observed to (the root folder's no-maintainer write has no parent, so no membership to lose).

⚠⚠ **Rows already lost are not repaired — DECIDED 2026-09-06: no repair.** The maintainer's call,
taken with the trade explicit. A reconcile-on-read or a one-shot rebuild in the shape of
`OrphanedFileSweeper`'s repair marker is buildable (`RelationRegistry.declaredFor` +
`ObjectStore.scanRowsOfType` give the candidates and the bounded scan), and was not judged worth its
cost against a residue nobody could size. If a user ever reports a contact they created that never
appeared, this is the explanation — the row is on disk, absent from its relation, and only a rebuild
would surface it.

⚠⚠ **Instrumenting a race like this moves it.** Printlns, or code that runs only inside the failure
path, shifted the rate and sometimes *which test* failed. Measure a rate before and after any change
under the same condition ([[rate-needs-same-condition]]).

### `runCatching { getFieldValue<T>(ref).get() }` is a SEGFAULT on Kotlin/Native (2026-08-26)

⚠⚠ **Never read an *optional* schema field off an `Entity` with this idiom:**

```kotlin
runCatching { data.getFieldValue<TString>(ref).get() }.getOrNull()
```

`Entity.getFieldValue` is `fun <T> getFieldValue(reference: String): T` and every implementation
ends in an **unchecked cast of a map lookup**, so an absent optional yields **null in a slot the
caller declared non-null**. On the JVM the cast is erased and the `NullPointerException` from `.get()`
lands in `runCatching` — the idiom reads as correct and its tests pass. On Kotlin/Native the same
`.get()` dereferences `0x0`: `EXC_BAD_ACCESS` / **`SIGSEGV`, which no `catch` can intercept** (seen
as `DataSourceReadTest` crashing the iOS test binary at `optionalLong` → `TInteger#get`). Under route
1 that code runs inside the iOS apps, so it crashes the app.

**Use `Entity.optionalFieldValue<T>(ref)?.get()`** (`core/.../entity/OptionalFieldValue.kt`), which
asks for a nullable `T` so the `?.` is a real check; keep a `runCatching` around it only for a
*genuine* throw. ⚠ Nothing enforces this — the JVM gate is green either way and only `iosTestCheck`
can see the crash — so a new call site of the old idiom on an optional field is a latent iOS crash.

### The joiner's decrypt fails its MAC check — CAUSE FOUND + FIXED (2026-08-26)

**What it was.** `InitiatePairing` minted a fresh ephemeral keypair on every call, while the relay's
blob mailbox (`Map<pairingSecret, blob>`) drops its entry only on a successful ack and the joiner's
SAS is a snapshot of the first initiate. A second initiate under one secret (a double-tapped
Continue, a back-and-retry) left the joiner holding a key that could not open the blob the host had
posted, while both screens still agreed on the code — and failing identically on every retry.
`InitiatePairing` now reuses the live handshake's keypair; pinned by
`InitiatePairingImplementationTest.aSecondInitiateUnderOneSecret_reusesTheKeypairTheHostEncryptedTo`.
Confirmed on an iPhone ↔ Android pair. The crypto was excluded by `LibsodiumCryptoBackendsTest`
(`docs/testing.md` §8).

⚠⚠ **A host terminal saying "Device added" is not evidence about the joiner.** The host writes its
peer `Device` row and posts its response *before* the joiner decrypts anything, so the two halves fail
independently and the host's screen cannot see it. A real join is visible only in the **joiner's
store**: a `tenshu/identity/device` row, a `realmSchemaEpoch` and the sync cursors, written by
`installReceivedPairingPayload` only after the decrypt succeeds. ⚠ Error events from a failed attempt
stay in the store and do not clear.

⚠ **Still open — the user-facing half.** `PairingWizardViewModel.awaitHandshake` maps every joiner
non-success to the generic `PairingError.Unexpected(ErrorCopyKey.UNEXPECTED)` — no cause, no retry —
where the scan path has a typed `PairingScanError` with per-case copy. Sized as **Track D5** in
[`enforcement-followup-tracks.md`](proposals/enforcement-followup-tracks.md).

### A realm nobody funded is write-dead, and the flow that opts you into it never says so (2026-08-26)

A realm with **no storage plan** has every write refused by the relay with `402
entitlement_required`, correctly, and the push queues grow behind it (108 blocks on one desktop).
Drive's `SyncBanner.evaluate` renders `LocalOnly` (*"No storage subscription — files are
local-only"*) and Control's Services screen reports `planKey = "none"`, both true. The join flow
itself now says so: after a terminal, the pairing wizard reads the proxy's health row once (after a
settle) and swaps the success body for the `unfunded_realm_*` copy with a *Set up storage* CTA, on
the joiner, the offerer and the host (Tracks D1/D2 of
[`enforcement-followup-tracks.md`](proposals/enforcement-followup-tracks.md)).

**Still open:**
1. **`LocalOnly` is calm, and the 402 storm is invisible.** The banner names the *cause* but not the
   *consequence*: nothing says writes are being refused or that blocks are queued behind a wall only
   a purchase can move. And the banner renders on `FilesScreen` alone, so Home and Shared show
   nothing at all.
2. On iOS the CTA only dismisses the wizard (Settings → Services is one tap away); a direct deep-link
   is owed.
3. ⚠ The push queues are unbounded — blocks retried indefinitely against a wall that cannot come
   down without a purchase.

⭐ `EntitlementRecovery.Outcome.Fetched(applied = 0, usable = false)` is an authoritative "this owner
has no record" in one round-trip (`fetchEntityBlocks` bypasses the cursor). (The relay also
distinguishes `entitlement_required` from `entitlement_invalid`; `RestSyncRelay` folds both into
`ENTITLEMENT_REJECTION_MARKER`.)

### The scalar half of the merge two-shape read is hand-rolled in seven places, and the copies have drifted (2026-08-25)

**Files**: `place-service-proxy/.../PlaceMerger.kt`, `PlaceMergeMerger.kt`, `PlaceRoleMerger.kt`,
`PlaceNetworkFingerprintMerger.kt`, **`PlaceReconciler.kt`**; `calendar-service-proxy/.../CalendarMergers.kt`;
`sync-service-proxy/.../RealmSchemaEpochMerger.kt`. The helper they should share would live next
to `resolveListField` in `sync-service-common/.../SemanticMerger.kt`.

⚠⚠ **`PlaceReconciler` is not a merger**, and it carries a verbatim copy of all three helpers
(`string` / `int` / `timestamp`). The pattern has already escaped the merger family, which is the
argument for a shared helper rather than a merger-local base class.

[ADR 0024](adr/0024-merger-field-resolution.md) settled how a merger reads a **collection** field:
`resolveListField`, never `getField`, because `merge`'s two sides arrive in different
representations (`existing` often lazy database references, `incoming` materialised off the wire).
The same asymmetry applies to **scalar** fields, and there is no shared helper for that — so
seven files each re-derive the two-arm dispatch:

```kotlin
private fun string(data: Data, fieldId: String): String = when (data) {
    is Entity<*> -> data.getFieldValue<TString>(fieldId).get()
    is Composite -> data.getField<TString>(fieldId).get()
    else -> ""                                  // ← and here they stop agreeing
}
```

⚠⚠ **The `else` arm — the shape that is neither — is handled four different ways, and two of them
fail silently into a value the merge then acts on.** Same unrepresentable input, four outcomes:

| Copy | `else` yields |
|---|---|
| `string(...)` in the four place mergers **and `PlaceReconciler`** | `""` |
| `int(...)` in `PlaceMerger` and `PlaceReconciler` | `0L` |
| every `timestamp(...)` helper | `error(...)` — throws |
| `CalendarMergers`, `RealmSchemaEpochMerger` | `null` |

`""` and `0L` are the dangerous two: a key that collapses to `""` merges two distinct rows
together, and a timestamp-ish comparison against `0L` picks the wrong side. Neither throws, so
neither is visible. ⚠ The `error(...)` copies are not obviously right either — a merger runs on
the sync ingest path, so throwing there fails an ingest rather than one row.

⚠ **This is not six sloppy copies and one good one.** `RealmSchemaEpochMerger.vectorOf` is the
most careful: it adds a typed `is RealmSchemaEpoch ->` fast path ahead of the two arms, uses
`getOptionalField` on the `Composite` side because the field is genuinely optional (§16 of
`agent.md` — `getField` throws on an optional field, `getOptionalField` does not), and returns
`null` on unreadable bytes with a stated reason: *"so a corrupt side can never erase the good
one."* Its KDoc says it "mirrors `FolderMerger`'s two-shape accessor discipline". Each author
re-derived the discipline and made a *defensible but different* call about the fallback, because
there is nowhere for that decision to live once.

**Not known to be causing a bug.** Every copy is correct for the shapes its own type actually
takes today, and no call site is known to pass a `Data` that is neither an `Entity` nor a
`Composite` — ⚠ though that was not exhaustively proven, and it is precisely what the silent
arms would hide. The cost is that the next author re-derives this an eighth time — as
`PlaceReconciler` already did outside the merger family — and that the failure mode when a
shape does change is silent in five of the seven.

**Sized**: a `resolveField`-style sibling in `SemanticMerger.kt` covering the scalar case, one
decision about the `else` arm recorded in its KDoc (⚠ that decision is the actual work — the
extraction is mechanical), then seven call-site migrations. Half a day, plus whatever the
`else` question costs to settle. ⚠ `PlaceReconciler` is the reason to put it in
`SemanticMerger.kt` rather than behind the `SemanticMerger` interface: a non-merger already
needs it. ⚠ Do **not** fold `RealmSchemaEpochMerger`'s typed fast path away in the process; it
exists because `RealmSchemaEpoch` is normally saved as a plain composite.

**Revisit when**: a seventh merger needs the scalar dispatch, or
[`sync-merge-strategies.md`](proposals/sync-merge-strategies.md) is picked up — its declarative
per-field strategies would need this exact resolution internally, so the two should be designed
together rather than the helper being extracted and then immediately re-homed.

### ⚠ 11 test fixtures stopped their scheduler with `stop()` (2026-08-18)

⚠⚠ **A `:sync-service-proxy` fixture that steps ticks by hand must build the proxy with
`initSyncService(startBackgroundLoops = false)`** (production never passes it). Two races, both
proven by forced pairs:
- `SyncScheduler.start()` ticks **immediately**, before any delay, so starting the loops and then
  calling `stopAndAwait()` races the loop's *first* tick — and the join makes an already-begun tick
  **finish**, not not-happen. Whatever it does (`ensureRegistered → createRealm`, a pull, a backfill
  page, draining a block the test queued) lands before the test's first hand-stepped `tickOnce()`.
  Proven for `BackfillCatchUpTest` and `MergedLaneFallbackTest` (every exact call count off by one
  tick), and for `RetiredBlockDisposalTest` with the loop running through the body (`sanity: queueing
  must have persisted the block`).
- `stop()` is `syncJob?.cancel()`, and ⚠⚠ **`cancel()` is a request, not an event**: a loop already
  inside `tickOnce` runs on to its next suspension point. `stopAndAwait()` (on `SyncScheduler` and
  `ScheduleDrainLoop`) cancels *and* joins; its KDoc records why "stopped" and "quiet" are different
  claims — production code that stops the scheduler and then wipes, resets or tears down a realm
  races an in-flight tick unless it waits there.

Every hand-stepping fixture in `sync-service-proxy/src/commonTest` is on the flag;
`git grep 'stopAndAwait()' -- sync-service-proxy/src/commonTest` matches only a comment.
⚠ **What is not shown:** that either race produced `IllegalStateException: Entry not found` (the
`MultiRealmPushDrainTest` / `RetiredBlockDisposalTest` gate sightings) — that message was reproduced
in no arm. On `Dispatchers.Default` a drain and an enqueue overlapping inside the unsynchronised
`InMemoryObjectStore` is the reading those victim rows carry; the shape that could produce it is gone
from every fixture, and the rows are not marked fixed on that.

### ⚠ Two admission routes still register **unstamped**, so a stale tombstone can still revoke through them (2026-08-18)

**Files**: `sync-service-proxy/.../impl/RecoveryClaimImplementation.kt`,
`sync-service-proxy/.../SharedRealmDeviceAdmitter.kt` (`membership-grant`),
`sync-relay-server/.../auth/DeviceRegistry.kt` (`revokeIfNotStale`),
`sync-service-proxy/.../AdmissionStamps.kt`.

A queued `Device` tombstone that could not be pushed for hours used to revoke a device that had
since been legitimately re-admitted — delivery order is not authorship order. The relay now records
the HLC an admission was authored at and refuses a *strictly older* tombstone (threat model T-K-02,
amended 2026-08-18).

⚠ **The guard is only as wide as the routes that carry a stamp.** Four do —
`createRealm`, `admitDevice`, `postPairingResponse`, `postOfferResponse`. **`recovery-claim` and
the shared-realm `membership-grant` do not**: they register a device with no `admittedAtHlc`, which
stores `NULL`, which reads as *unknown* ⇒ revoke unconditionally. That is deliberately today's
behaviour rather than a half-guard — a stamp applied to some routes and silently missing on others
is harder to reason about than one that is uniformly absent — but it means a device admitted via
recovery or a membership grant is still exposed to the original defect.
⚠ **A third unstamped caller, found 2026-10-28:** `SharedRealmDeviceAdmitter` (the shared-realm owner
admitting a member's device) calls `admitDevice` **without** `admittedAtHlc`, so `/sync/admit-device` is a
stamped route only for the primary realm. This is why the relay still treats an absent stamp as
"unknown" there — the reset's legacy-wire cleanup could not make it required.

**Fix**: thread `syncManager.admissionStamp()` through both call sites, exactly as the other four
do, and extend `RelayPersistenceTest` with the two cases. Cheap; the reason it is not done here is
scope, not difficulty.

⚠ Note for whoever picks this up: `admitted_hlc_*` is **cleared** when a device re-registers
without a stamp, so adding a stamp to one of these routes is a strict improvement and never
regresses a stamp another route set.

### ⚠⚠ `PendingBlockRestamper` threw on a dangling queue member and **killed the whole tick** — sync silently dead on iOS route 1 (2026-08-13; ✅ no longer fatal, ⚠ cause still unconfirmed)

**Files**: `sync-service-proxy/.../PendingBlockRestamper.kt` (`readQueue`),
`sync-service-proxy/.../SyncScheduler.kt` (`tickOnce`, `runOneShot`),
`sync-service-proxy/.../RetiredBlockSweeper.kt` (`queuedBlockHashes`),
`core-common/.../db/DatabaseImpl.kt` (`getArrayData`, `removeDanglingArrayMembers`).

**What happened.** A real iPhone logged 53 copies of `IllegalStateException: Entry not found` from
`DatabaseImpl.getArrayData` under `PendingBlockRestamper` in ~20 s: a pending-block queue held a ref
to a block row that no longer existed. Because the restamper ran first in `tickOnce` and its one-shot
latched only at the end, every tick died before the push, the pull and `reportSyncHealth()` — sync
permanently dead while the UI showed the last health value.

**What defends now:**
- `getArrayData` / `getArrayDataWindow` **skip** a dangling member and log
  `WARN DatabaseImpl: relation … skipped N member(s)` (a short relation is the store admitting it
  lost rows). ⚠ The array *itself* absent still reads empty, and a missing **nested child** of a
  member still throws — a silent hole inside an entity is a different risk.
- `Database.removeDanglingArrayMembers(reference)` drops references whose objects are provably
  absent; `SyncScheduler` runs it once per process over both personal queues, ahead of every reader.
  ⚠ It can never discard an unsent write (it removes only what a read would skip), and it writes
  `WriteOrigin.DERIVED`, never `USER` — a `USER` write would broadcast a rewritten relation to fix a
  row only this device lost.
- The restamper degrades member-wise (`PendingBlockRestamperTest.aDanglingQueueMemberIsSkippedAndTheRestOfTheQueueIsStillRepaired`,
  which also asserts the one-shot is consumed).
- `runOneShot(name) { … }` wraps the pre-drain one-shots so a throw cannot abort `tickOnce`.
  ⚠ Unpinned (no injectable throwing one-shot), and stdout-only.

⚠⚠ **Hardening readers one at a time loses to a store that has lost rows** — registry, then
restamper, then drain each threw on the same members in turn. That is why the skip lives in
`getArrayData` itself.

⚠ **Still open: which producer leaves the dangling reference.** The repair removes the damage, not
its cause, so a device can acquire fresh ones. Candidates:
1. **Cross-process cache staleness** on iOS route 1 (several apps over one replica, each with its own
   resident cache). ⚠ iOS now runs the SQLite `SharedReplicaStore`, which keeps no row cache
   (`ios-shared-store-sqlite.md` B.22.9), so this can no longer arise there; a dangling member on a
   current build points at (2) or a new cause.
2. **A keep-set gap** — another relation that can hold a block ref and is missing from
   `RetiredBlockSweeper.queuedBlockHashes` (the 2026-08-04 `RECEIVED_TOMBSTONES` bug again, with a
   different relation).
3. For the JVM store, a silently dropped `COMMIT` — closed (retry, then a loud drop,
   [`drive-folder-files-dangling-member.md`](proposals/drive-folder-files-dangling-member.md) §6.1).

**How to discriminate** (needs the device): copy the store out and decode the queue array **on
disk**; if it references the missing hash, it is (2).

⚠⚠ **Grep logcat by process, not by string.** An Android buffer holds many
`Failed to find entry 'classes.dex': Entry not found` lines from unrelated apps' ART dex loaders;
filter with `grep -i tenshu` before believing a count in either direction. ⚠ And `Entry not found` is
not "a known test flake": the same message is a live sync outage on a device.

### `Device` rows have no regeneration path — a store wipe empties the realm roster **and breaks revocation** (2026-07-27)

**Files**: `identity-service/.../Device.kt`, `identity-service-proxy/.../IdentityProxyInitialization.kt`
(`DeviceRelationMaintainer`), `identity-service-proxy/.../executor/GetDevicesImplementation.kt`,
`sync-service-proxy/.../impl/RevokeDeviceImplementation.kt`, `sync-service-proxy/.../SyncProxyInitialization.kt`
(the realm-epoch provider), `core-common/src/jvmMain/.../db/SqliteObjectStore.kt` (the core-version wipe gate).

**Observed**: desktop Control's "Realm devices" list rendered empty. The desktop store held **zero**
`tenshu/identity/device` rows — *not even its own* — while sync itself was demonstrably healthy (the
phone mirrored the desktop's files exactly). Forensics: no row in the store predated 2026-07-26 18:13,
i.e. the store had self-wiped at that instant on the fmt v14→v15 core-version mismatch.

**Root cause.** Realm membership has two representations that are written together **once**, at pairing,
and thereafter drift independently forever:

1. **Authoritative** — the A4 device keypair, the derived realm keys, and the relay's `KnownDevicesStore`
   registration. This is what actually grants access. It survives a store wipe (keys live in
   `shared_prefs` / `secrets.enc`, not in `objects.db`).
2. **Descriptive** — the synced `Device` row, which drives the roster UI, revocation targeting, and
   `admittedAtEpoch`. This lives in the object store and is wiped with it.

**(1) cannot regenerate (2).** `Device.create` has exactly five non-test call sites — `CreateRealm`
(genesis), `RecoveryClaim`, `PairingKeyInstaller` (joiner self-publish), `ApprovePairingRequest` and
`ApprovePairingOffer` — and none re-runs spontaneously. `DeviceRelationMaintainer` only maintains the
relation *from existing rows*; there is no self-heal. Because HR #9 moves all clients in lockstep,
**every replica's store is wiped at the same instant**, and a `SchemaMigration` cannot help (a migration
reshapes rows that exist; it cannot resurrect rows nothing retained).

⚠ **Precise failure condition (corrected 2026-07-27 after the §"Recovery audit" below — the original
wording of this entry overstated it).** A local store wipe **alone is recoverable**: the sync cursor is
itself a row in the store, so it is wiped too and the next pull runs `since=0`, re-fetching the retained
history and re-materialising every `Syncable` row. What actually kills the data is a store wipe **plus
absent relay history**. Relay history is durable for *live* rows — `MailboxStore.gcSweep` is
**tombstone-driven only** (it iterates `tombstoneIndex(ownerId)` and drops bodies solely for targets that
were deleted, after quorum-ack + retention), so a never-deleted `Device` row's block is not GC'd. The two
things that do remove it: an **operator relay-data wipe** (what fired here — the 2026-07-26 E2 deploy)
and, prospectively, **E4 compaction**, whose whole purpose is to advance a floor and drop old history.

**Consequences, in severity order:**

- ⚠⚠ **Device revocation is broken.** `RevokeDeviceImplementation` looks up the local `Device` entity
  first and bails with `"Local Device entity not found for $deviceId; nothing to revoke"`. With an empty
  roster **nothing can be revoked** — a lost/stolen device cannot be evicted from the UI. This is a
  security-management capability that is silently unavailable, discoverable only at the worst moment.
  ⚠ **The local-row requirement is weaker than it looks** (read 2026-07-30): the two steps that
  actually revoke — the tombstone carrying `revokedDeviceId` (the relay strips the device's
  `KnownDevicesStore` registration on receipt) and the master-signed `AdmissionRevocation` POST —
  need only the **deviceId string**. The existence check is an explicit UX guard in its own words
  ("keeps revoke observably idempotent … instead of silently emitting tombstones for ids the caller
  mistyped"), not a correctness precondition. So the *mechanism* is one guard away from working
  id-only; what is genuinely missing is **the id to target**, since with every row gone nothing
  local knows the lost device's id — which is precisely why tier 2's durable-home / relay-endpoint
  decision has to be settled *first*. ⚠⚠ Downgrading the guard alone would look like a fix while
  leaving the user no id to type.
- ⚠⚠ **The realm epoch silently resets to 0.** `initSyncService` derives it as
  `tombstoneRegistry.tombstoneCountForType(Device.ID)` — the count of `Device` tombstones. Tombstones
  live in the store, so a wipe resets the epoch to genesis. T-K-02 (rejecting blocks from a device
  admitted before a rotation) is therefore degraded after any wipe, independently of the roster.
  ⚠ **Unowned.** Tier 1 does not touch this (a re-minted row stamps `admittedAtEpoch =
  currentRealmEpoch`, i.e. 0), and **none of the `core-relation-system.md` §9.6 levers address it
  either** — they are about rows having producers, and this is a separate derivation. Closing it
  means deriving the epoch from something that survives a wipe, or persisting it outside the
  tombstone count. Smallest item on this entry's list and currently nobody's.
- **The roster UI is empty**, and the read maps a failed result to `emptyList()`
  (`TenshuSyncRepository.devices`), so even a hard failure renders as "no devices" with no error
  surfaced. ⚠ Still true now that the roster is a live relation read — that removed the
  *stale-cache* half of the problem (an unrefreshed roster used to be indistinguishable from an
  empty one, which made Control's Home banner tell multi-device realms their data was unsynced),
  but a failed read is still reported as zero devices. The banner itself no longer misreads it:
  `RecoveryAffordanceViewModel` treats zero as *unknown* rather than *one device*.

**Two fields are genuinely unrecoverable** and must not be guessed:
- `admittedByDeviceId` (admission lineage) — `RevokeDevice` walks it to revoke the transitive admission
  subtree (A33 delegated authority), so a re-derived roster with empty lineage revokes *less* than it
  should. Empty is nonetheless **correct** for an A1-rooted (genesis/owner) device, which by design has
  no lineage parent.
- the original `admittedAtEpoch` — and since the local epoch view itself resets to 0 after a wipe (above),
  stamping "current" is not a faithful reconstruction either.

**Fix, two tiers:**
1. **Self-heal the own row** (shipped alongside this entry) — `reconcileOwnDeviceRow` in
   `SyncProxyInitialization`, mirroring the existing `reconcileStaleSyncCursor` precedent, run at
   `initSyncService` (after the observers are registered, so the repaired row emits) and again on the
   scheduler's locked→enabled edge. Guarded: **real realm membership** (`currentRealmContext()` —
   owner id + derived keys), row genuinely absent, and **no tombstone for this device's own id**
   (else a revoked device would resurrect itself). Restores the roster and makes devices revocable.
   ⚠ **Gating on `!isLocked` is wrong and was a real bug** — an *unpaired* device also reports
   unlocked (no wrapper ⇒ `isSetUp = false`), so it self-asserted `isAuthorized = true` before ever
   joining; that phantom row then raced the admitter's real row under whole-entity LWW and erased
   `admittedByDeviceId`, silently shrinking the revoke subtree. `DelegatedAdmissionSystemTest`
   caught it; `unpairedDevice_neverManufacturesMembership` is the regression guard.

   ⚖️ **How much this actually buys — more than "restores my own row" (assessed 2026-07-30).** The
   re-mint goes through `saveData`, so it **emits like any other row**: every device that runs
   re-mints itself and pushes it, and every peer's `RelationMaintainer` picks it up. So after a
   wipe-everything event **the roster reconstitutes itself across the fleet** as devices come up —
   no operator action, no re-pairing. ⚠ Do not confuse this with the *other* self-healing mechanism
   in this area: the maintainer converges the relation **array** from element rows on every save
   (so a polluted index fixes itself) but **cannot resurrect rows**. Only `reconcileOwnDeviceRow`
   creates one.

   **What is left after tier 1, in severity order:**
   1. ⚠⚠ **A device that never runs again is never re-minted, so it can never be revoked** — and
      that is the lost/stolen case by construction, i.e. exactly the one you need. This, not the
      roster, is the residual problem.
   2. `admittedByDeviceId = ""` on every re-minted row ⇒ the A33 revoke subtree collapses to
      single-device revocations; a delegated admitter's children stop being swept (Q3: accepted).
   3. The epoch reset (above) — unowned.
   4. A long-offline device is absent from the roster until it wakes. Cosmetic, but it also holds
      the E4 floor back (ADR 0007 roster gating).

   ⚖️ **Verdict: the roster/UI half is now largely self-healing; what remains is a silent
   security-management degradation** (1 and 3). That matches `core-relation-system.md` §7's
   "E4-compaction prerequisite, not a standing bug" — with the caveat that #1 is sharp whenever a
   history-loss event does occur.
2. **The real fix — ⚠ NOT the relation system** (corrected 2026-07-30; the earlier wording here said
   "or land relation membership as a synced primitive", which
   [`core-relation-system.md`](proposals/core-relation-system.md)'s own design pass has since
   contradicted). That doc's §9.5/§9.6 audit is explicit: `Device` membership is *derived*, R3 forbids
   curating a derived relation (§9.3, "if a derivation exists, derive"), `RelationMember` rows would
   ride the same ephemeral block log and die in the same wipe (§9.4), and even R4's `RelationSnapshot`
   restores *membership* while this entry's problem is the missing **elements** — so §9.5 answers the
   revocation row with a flat "No … the tier-1 `reconcileOwnDeviceRow` self-heal remains the answer".
   ⚖️ Do not schedule the relation system as this entry's fix. What §9.6 ranks instead:
   - **L1 (the lever) — close the core-version store wipe.** HR #9 lockstep is what manufactures the
     "every replica simultaneously" premise, and E2b already built the machinery to migrate instead of
     wipe; the core version is the one gate still calling `wipeAndStampCurrent()`. Post-release we
     cannot ship a wipe at all, so this has an owner regardless — and closing it dissolves the premise.
   - **L3's invariant — every durable row needs a producer or a durable home.** The own row now has a
     producer (tier 1 above). **Peers' `Device` rows have neither**, and that is the concrete unbuilt
     piece. ⚠ The obvious candidate — an endpoint over the relay's `KnownDevicesStore.deviceIds(ownerId)`
     — is *unsettled inside that doc*: §9.6 L3 names it, while §12 rejects it (as §5d) as a real trust
     shift, the relay becoming the source of truth for realm topology rather than an observer of it.
     Resolve that before building it.
   - Admission lineage (`admittedByDeviceId`) is **accepted as unfixable** — §6 Q3, no surviving
     witness; it re-establishes only at the next pairing.

   ⚠ `DeviceRelationMaintainer`'s KDoc still points at `core-relation-system.md` as "the proper fix".
   That is true for what the maintainer *is* (one declared mechanism instead of thirty hand-written
   ones — R1/R2/R2b shipped) and **not** for this entry's regeneration gap. Two different problems that
   the same doc reference has been conflating.

⚠ Note this is **not** retired by schema evolution. E2 replaces the wipe with a migration chain for
*per-service* changes, but §7.5 keeps `TENSHU_STORAGE_FORMAT_VERSION` and its lockstep discipline, the
as-built store seam keeps `PRAGMA user_version` mismatch as a wipe, and the iOS/JS twins keep the wipe
unconditionally. Core bumps get rarer post-release, not impossible — and the same permanent loss follows
from cleared app data, store corruption, or a below-floor wipe.

#### The mirror image — a `Device` row that outlives the identity it describes (2026-08-09)

**Observed**: Control → Settings → Devices listed the same iPhone **twice** — two rows, both named
`iPhone` (the name is `getPlatformDeviceName()`, so same-model devices are visually identical), with
different `deviceId`s and different A4 public keys. One was live (Control + Drive + Fit all shared it —
route-1's hand-off was working); the other had authored **zero** writes in any of the three replicas.
It was a previous Control-iOS provisioning whose row outlived the custody bucket that minted it.

Same two-representation split as above, opposite direction: the entry above is *descriptive lost,
authoritative lives*; this is **authoritative lost, descriptive lives**. Nothing reaps a `Device` row
whose identity is gone — `Device.create` has five call sites and there is no counterpart that deletes.

⚠ **Not iOS-specific, and there is a fixed precedent in-tree.** `DesktopSecureStorageDelegate`'s KDoc
records the identical failure on desktop: `InMemorySecureStorageDelegate` minted a fresh `DEVICE_ID`
every launch while the persistent `objects.db` kept the old rows, so the roster *"accumulated one
duplicate desktop entry per restart"*. It was closed by making the identity **durable** — never by
reaping the orphans it had already minted, which is the hole that remains.

⚠ **Not `core-relation-system.md` either** — the same conflation this entry already warns about, in a
new direction. That doc is about relation *membership* not being a synced primitive (rows invisible or
lost). An orphan is the opposite: the entity exists, the relation correctly contains it, every
maintainer is doing its job; the row is simply true about nothing. It belongs here.

**Precise production condition: identity destroyed while the store SURVIVES.** Enumerated, because
three of the four combinations are harmless and the difference is not obvious:

| event | identity | store | result |
|---|---|---|---|
| iOS **uninstall** | **survives** (Keychain outlives the container) | dropped | same identity, empty store → the self-heal above re-creates **the same** row. No orphan. |
| core-version / fmt **store wipe** | survives | wiped | as above — no orphan (this is the entry above's case) |
| Erase All Content, cleared app data | gone | gone | both halves leave together → no orphan |
| **`wipeDeviceState`** / "Forget this device" | **purged** | **survived** until the SQLite store (it never touched `FileObjectStore`); ⚠ since 2026-11-05 iOS wipes the store first (`wipeThenPurgeCustody`) | the old `Device` row lives on in the **realm**, so it re-syncs from the relay into the new store: new identity beside it ⇒ **ORPHAN** either way (A below is what retires it) |

⇒ **Uninstall is not the risk, and no exit hook needs to catch it.** Android and desktop keep both
halves in one app data dir and have **no `wipeDeviceState` twin at all**, so clearing app data takes
both; their visible symptom is the *empty* roster, not a duplicate. iOS is the live case only because
its Keychain outlives the container — which is precisely why "Forget this device" had to exist.

**What closes it, in two halves (both built):**

- **A. Self-revoke on the way out.** `wipeDeviceState` runs `RevokeDevice(currentDeviceId)` on itself
  while it still holds A1/A4, which removes the row, deletes the entity and emits the `DeleteBlock`
  carrying `revokedDeviceId` that makes the relay drop the registration (T-K-02).
  ⚠⚠ **The ordering is the load-bearing part, and "emit first" is not enough**: `TombstoneEmitter.emit`
  only appends to `PENDING_TOMBSTONES`, and the push JWT is signed with the A4 key the wipe is about to
  delete — so the purge waits (bounded) on `PENDING_TOMBSTONES` being empty. On timeout (offline,
  relay down) it **does not block the wipe** — the user is often wiping *because* something is
  broken — and says so, naming the remedy ("remove it from another device").
- **B. `DeviceLastSeen`** — a local-only row written from the author id every ingested block's HLC
  already carries, shown as a last-seen line on peer rows (not on this device's own). Needed because
  A covers only paths that run our code: a **team/cert change** makes Keychain items inaccessible
  while `devicectl install` preserves the container, and an **unencrypted iPhone backup** restores
  the container without the Keychain. ⚠ It must stay a *displayed* signal, never automatic reaping:
  an offline device and a dead identity are indistinguishable from the outside, and ADR 0007 makes an
  absent device hold the compaction floor rather than be presumed gone.

Control on Android, desktop and iOS can revoke a peer's row from the roster (the tombstone must come
from a replica that holds the row).

**Still open:**
- ⚠ **A's timeout branch is unverified on hardware** — the wipe purging with a named warning when the
  tombstone cannot be pushed. Reaching it needs a wipe performed while the relay is unreachable, which
  no gate here can stage. (The interrupted-forget case is its own entry above.)
- ⚠ The first revocation in a realm ticks `currentRealmEpoch` 0 → 1, after which
  `SyncProcessor.isStaleDeviceBlock` drops incoming full-entity `Device` blocks stamped
  `admittedAtEpoch = 0` — designed T-K-02 behaviour, but a peer that later self-heals its row at epoch
  0 has it rejected by epoch-1 peers. A self-revoke while the identity is alive costs one epoch tick
  at a moment of the user's choosing, instead of one per stale row discovered later.

### No end-to-end retention / quota model — P1 + P2 + P3 BUILT (P3 LIVE since 2026-10-24, ⚖️ counsel review owed) (2026-08-04)

> **Design doc: [`docs/proposals/retention-quota-model.md`](proposals/retention-quota-model.md)**
> (Draft v0.5) — the three-tier taxonomy (ephemeral log / durable content / local cache) +
> the three faces below as phases P1–P4, with concrete surface names and open questions Q1–Q6.
> The summary below stays as the tech-debt index entry; the doc is canonical.

The system has **no coherent story for "how much data is kept, where, and when it is reclaimed."**
Retention decisions were made ad hoc per subsystem, and one of them (content-plane time-GC) was
outright wrong for durable data — fixed defensively, but the general model is missing. A complete
design pass is owed. The pieces:

- **Relay content plane — reclamation model (partly fixed).** The content plane holds *durable
  user data*, not an ephemeral log, so a *time-since-last-touch* retention sweep reaps live data
  (a backup opened after two years → gone). The relay's `ContentBlobStore.gcSweep` was wired live
  with a 30-day untouched window — a latent data-loss bug (content is `OnDemand`; the relay may be
  the only durable copy). **Fixed 2026-07-18** by defaulting `RelayConfig.CONTENT_RETENTION_WINDOW_MS`
  to `Long.MAX_VALUE` (GC off; `file-content-addressing.md` §4.2). What's **still owed**: the
  *correct* reclamation mechanism — **account-lifecycle-driven, keyed on `ownerId`** (owner over
  quota / entitlement lapse → after a grace period reclaim that owner's content). HR#1 blocks a
  manifest refcount (the relay can't read which blobs a delete orphans), so deleted-file blobs
  linger *counted against quota* until account-lapse reclaim. This ties into the commerce/entitlement
  plane (`commerce-billing-plane.md`) — probably its own slice. **⚠ Partly closed 2026-08-04 (P3a):** the
  mechanism now exists — `ContentBlobRegistry.reclaimOwner(ownerId)` on both backends,
  `retention-quota-model.md` §3.1 — but **nothing calls it**. **P3b-1 (same day, §3.2)** added
  what *authorises* a reclaim: `AccountClosureClaims` (commerce-signed, verified against the
  existing issuer key) + `AccountClosureReclaimer` (grace → reclaim), both off by default.
  **P3b-2 (same day, §3.3) closed the loop:** a commerce closure state (`AccountStatus.Closed` +
  `closedAt`, Postgres migration v3), `AccountClosureMinter`, a repeating `AccountClosureDelivery`
  sweep, and the relay's `POST /sync/account/closure`. ⚠⚠ **The path is complete and still deletes
  nothing:** it needs `ACCOUNT_CLOSURE_RELAY_URL` (commerce) **and** `ACCOUNT_CLOSURE_RECLAIM`
  (relay), both unset, and the route is not even registered while the relay flag is off.
  **The ⚖️ clause is drafted (2026-08-04):** `acceptable-use-and-limits.md` **§4** (retention table,
  a published 30-day closure grace, irreversibility, the never-deletes list, erasure = closure +
  operational-store deletion) with `TOS_VERSION`/`PRIVACY_VERSION` bumped to `2026-08-04`.
  **Still owed:** counsel sign-off; a decision on **notice to existing accounts** (acceptance is
  recorded once at registration — there is **no re-acceptance flow**, so earlier accounts hold
  `2026-06-01`); a UI that closes an account (the store API exists, the admin console is
  read-only); and an end-to-end pass against a live pair. ⚠ The published grace is now a **floor**
  on `ACCOUNT_CLOSURE_GRACE_MS`.
- **Client local cache — "max local data" ceiling — MECHANISM BUILT 2026-08-03, SETTING NOT
  EXPOSED.** There used to be no bound at all on how much decrypted plaintext a device caches.
  The offline keeper's sweep now enforces two (`retention-quota-model.md` §4.11): a
  `MinFreeSpaceBytes` free-disk floor (1 GiB, **on by default**) and a `MaxLocalBytes` ceiling,
  evicting unpinned content coldest-first and never touching `KEEP_OFFLINE` files.
  ⚠ **The ceiling ships at `0` = unlimited** — a default-on limit would silently delete cached
  content on upgrade, so it is opt-in; the **settings screen landed 2026-08-03** (Drive → Sync
  preferences → *Cache size limit*, §4.12) and is how a user turns it on. ⚠ It bounds the
  **cache**, not total footprint: pinned content is outside it (counting content that cannot be
  evicted makes the setting unsatisfiable — D6 as corrected), and the copy says so. The iOS twin landed the same day (a `Picker` over the same shared view-model state), so
  **Face B is complete on Android, desktop and iOS**; what is still owed is only the visual pass on
  iOS, which needs a provisioned session. Distinct from
  the resident-set work (`bounded-store-cache.md` bounds the metadata store's *RAM*; this bounds
  the content cache's *disk*).
- **Quota UX — running-out warnings.** ✅ **Largely built** — `QuotaSoftLimitBanner` warns before
  the ceiling (wired in Control's `App.kt`, threshold + dismissal in `ControlPreferences`), and
  `SyncBanner.StorageFull` reports the over-quota state with figures on the Drive side, from
  relay-reported usage. `retention-quota-model.md` §7 records P1b-1 as implemented. What remains is
  polish rather than the mechanism: over-quota still *also* surfaces as a `402` on the content PUT
  (`RestContentBlobStore` → `ENTITLEMENT_REJECTION_MARKER`).

These are three faces of one missing model (durable vs cached vs quota-bounded). The content-GC fix
closes the acute data-loss hole; the rest is a **design pass**, not yet scheduled.

### BIP-39 Wordlist — English-Only

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/util/Bip39Wordlist.kt`

The recovery-code wordlist is BIP-39 English. The standard defines additional language wordlists (
Japanese, Korean, Spanish, Chinese (simplified/traditional), French, Italian, Czech, Portuguese),
and users in those locales will reasonably expect to see words in their own language.

**Proposed Fix**:

- Decide deliberately: either remain English-only and document why (international standard,
  hardware-wallet compatibility), or add per-locale wordlists with a stable selection rule (locale
  at signup; never auto-switch on a paired device).
- If we go multi-locale, store the selected wordlist identifier alongside the encrypted backup blob
  so `decode()` knows which list to use; otherwise the user could be locked out by a locale change.

Tracked in [`docs/sync/threat-model.md` §11a](sync/threat-model.md).

### External Proxy Connection — No Formal Spec or Threat Model

**Files**: would-be `docs/sync/external-pairing-flow.md` (does not exist),
[`docs/sync/threat-model.md`](sync/threat-model.md) (does not cover this surface),
[`docs/whitepaper.adoc`](whitepaper.adoc) (only forum where it's described, in prose).

The whitepaper now distinguishes two pairing surfaces — *realm device pairing* and *app-to-proxy
pairing* — but only the first has a formal spec (`pairing-flow.md`) and threat-model coverage. The
second is the load-bearing protocol for the iOS Drive Client milestone (and any other platform where
the External Proxy Connection pattern is used) and exists today only as descriptive paragraphs.

Open design items inside this:

- The exact handshake (which curve, which AEAD, how the SAS is derived).
- Integration with platform attestation (iOS App Attest, Android Play Integrity / hardware-backed
  keys).
- How permission verification through Tenshu Rei is wired into the proxy's authorisation decisions (
  which Rei layers, what the proxy fetches, how it caches).
- Replay protection over a public-internet channel (the realm-device handshake gets physical
  proximity; this one doesn't).
- Revocation: the user can already terminate the pairing in the proxy, but the threat model needs to
  formalise what that does to in-flight requests.

**Proposed Fix**:

- Write `docs/sync/external-pairing-flow.md` modelled on `pairing-flow.md`.
- Extend `threat-model.md` with an §8.x section ("External Pairing Surface") covering the threats
  above, plus the attestation trust assumption (a new TA?).
- Until both exist, the iOS Drive Client milestone in [`docs/milestones.md`](milestones.md) cannot
  move past the "future apps" list.

### Tenshu Rei — No Threat Model

**Files**: [`docs/whitepaper.adoc`](whitepaper.adoc) (only place it's described).

The whitepaper describes a six-layer distributed-ledger protocol family with smart contracts (RPL /
RIL / REL plus three higher layers), used as the platform's chain of trust for app distribution,
code-signing verification, in-app-purchase ownership, and cross-app-store interoperability. None of
this currently has a threat model.

This matters because Rei is on the trust path for several listed milestones — the iOS Drive Client (
permission verification), the Browser Extension (publication verification for browser apps), the App
Console & Tenshu Store (publication / issue / evaluation flows). Until Rei has formal security
analysis, we cannot honestly claim the security guarantees those flows depend on.

**Proposed Fix**:

- Decide scope: write a Rei threat model now (large, but unblocks several milestones) or explicitly
  defer with a written statement of which Rei-dependent milestones are blocked on it.
- A first cut should at minimum cover: trust-anchor compromise (root developer-identity compromise),
  publication-replay attacks, evaluator collusion on the REL, and the privacy properties of the
  higher layers (cross-store interoperability without leaking purchase history).

### Cloud-only / Device-side Eviction — Deferred

**Source:** decision #3 of the May-2026 open-design-decisions walkthrough (deleted
2026-08-13): *defer cloud-only / evict-but-keep*.

Designing user-facing "evict locally, keep on relay" semantics is sized but not started.
The protocol already supports it via the `LazyBlock` / eager-block split per
[`sync-relay-api.md`](proposals/sync-relay-api.md) §3.1; the missing piece is UX/policy
(per-folder cloud-only flag, eviction policy, re-fetch UI). Revisit when there's a
concrete archive use case (large photo libraries on phones is the canonical one).

### ⚠⚠ The ordered log is unbilled, uncapped and unrated — quota meters only the content plane (2026-08-22)

`bytesUsed(ownerId)` is literally `SELECT SUM(byte_len) FROM content_blob WHERE owner_id = ?`, and the
only call site of `putWithinQuota` is the content-blob `PUT` in `ContentRouting.kt`. Nothing else an
owner can write to the relay is counted against their entitlement.

**Measured on the production relay, 2026-08-22:**

| Plane | Size | Billed? |
|---|---|---|
| Ordered log (`sync_block` envelopes) | **2 640 KiB**, 756 rows | **no** |
| Tombstones (`delete_block`) | 1.8 KiB, 6 rows | **no** |
| Content blobs (`content_blob`) | **0.1 KiB**, 1 blob | yes |

⚠ The realm holds **~36 500×** more unbilled than billed bytes. `sync_block` is ~3 MB of the SQLite
file's pages; the entire metered surface is one blob.

**Rate, from the same data** — 756 blocks over a 4.0-day span across 4 devices, averaging 3 576 B per
block: **≈164 KiB per device-day → ≈58 MiB per device-year**, ≈234 MiB/year for this realm.
⚠ Treat as an order of magnitude, not a consumer figure: this is a *development* realm with heavy
churn over a short window, reset days earlier. It is closer to an upper bound for an active device
than to a typical one.

**Gap 1 — a client that ignores its quota is not stopped by anything.** On `/sync/push` there is no
byte metering (`507` exists only on the content plane), **no per-request `PayloadTooLarge` cap** (one
exists for rendezvous frames, share chunks, invite blobs and content blobs — not for blocks), and **no
rate limit** (rate limiting covers realm genesis and auth failures only). The single gate is
`requireEntitlement`, which checks that *a* live owner-bound credential exists — and **a free-tier
token satisfies it**, because issuance is uniform. So any authenticated device can push blocks of any
size at any rate, forever, unbilled.

**Gap 2 — the "drawer phone" amplifies it, and that is not a coincidence.** The log cannot shrink while
a device on the device list never acknowledges: `MailboxStore.gcSweep`'s quorum is recomputed against
the whole device set for *every* tombstone, so one idle device stalls collection realm-wide. Unbounded
growth and stalled reclamation therefore have the *same* trigger — an account with a device nobody
turns on. See the tombstone/quorum mechanics in [ADR 0006](adr/0006-sync-tombstones.md).

**⚠⚠ Do NOT "fix" this by byte-gating `/sync/push`.** Two separate things break:

1. It would stall CRDT convergence — a rejected metadata write is not a deferred write, it is a
   divergent replica.
2. It would destroy the property that currently answers *"does the entitlement still sync when the user
   is out of quota?"* — **it does, and that is structural, not a special case.** The two gates are
   deliberately different: `requireEntitlement` → `402` checks only that a credential *exists* and
   applies to all writes; the byte quota → `507` is enforced *only* at the content-blob `PUT`, with
   `resolveEntitlement` carrying `quotaBytes` through and the accounting living in the content store.
   An over-quota owner therefore cannot upload new file bytes but their realm keeps converging, and the
   synced `Entitlement` record keeps flowing. Metering the log would close that door.

The shape of a real fix is **measure + cap + rate-limit**, not *reject ordinary writes*: count log
bytes per owner (so the number exists at all), cap per-request block size, rate-limit the push path,
and decide separately — and last — whether log bytes should count toward the sold quota at all.

⚠ **Client backoff is not a substitute for a server-side limit, and vice versa.** The storms that
actually happened were fixed client-side by `RejectionBackoff`, which protects against a *stuck but
well-behaved* client. A server-side rate limit is the only thing that constrains a client which does
not run that backoff — an old build, or a hostile one. Different threat models; you need both.

### The two invariants any such work must not break

**1. Never byte-gate `POST /sync/push`.** Beyond stalling CRDT convergence, this is what makes
"the entitlement still syncs when the user is out of quota" true, and it is true *structurally*: the
`402` gate asks only whether a credential exists, and the `507` byte gate is applied **only** at the
content-blob `PUT`. Metering the log closes that door.

**2. Never entitlement-gate `POST /sync/fetch-entity-blocks`.** It calls `requireOwnerId` and
deliberately **not** `requireEntitlement`, which is what breaks the credential circle: a device whose
only credential source is the *synced* `Entitlement` record uses this ungated route
(`EntitlementRecovery`, fired before each backoff probe) to fetch that one row directly, bypassing
both the cursor and the bulk backlog. ⚠⚠ Without it the failure is a **13-hour write-dead device**,
measured 2026-08-04: a wiped iPhone's renewed entitlement sat ~354 000 seq ahead of its cursor while
every write was refused. ⚠ It also presents as *"Setting up your storage"*, not as an error — so a
uniform "gate every route" tightening would look correct in review and silently reproduce it.

⚠ A read-path rate limit (a reasonable response to the egress concern) must therefore carry a floor
or an exemption for this route. It is cheap to exempt: `EntitlementRecovery` inherits the backoff
window, so it costs at most one request per ~2 minutes.

⭐ Priority note: reads cannot create stored bytes and are JWT-scoped to the owner's own realm, so
read abuse is a **cost/DoS** concern, not a billing-integrity or data one. The per-request block cap
and the push rate limit are the higher-value work — those are where the current answer is genuinely
"nothing stops you".

**⚠ Not verified, and it is the one that could bite:** the *lapsed*-entitlement case (`402`, not `507`)
does pause all writes. Reads stay ungated, backoff is bounded (`RejectionBackoff`, the fix for the 402
storm), and a free-tier token suffices — but `SyncProcessor` also forwards a **synced** `Entitlement`
record as `X-Entitlement-Proof` via `EntitlementRecordCredentialProvider`. If a device's only credential
source is that synced record, and sync is `402`-blocked, the recovery is circular.
`second-device-entitlement-enforcement.md` is the doc that should settle it; this was not traced.

### A Device the Relay No Longer Recognises — Remaining Surface After the 2026-08-17 Fix

**Source:** diagnosed on a real iPhone against the live relay, 2026-08-17. Recovery + the Drive
banner shipped in the same pass; these are the parts deliberately left out. See
`docs/sync/design.md` §"Recovering from relay amnesia" for what *does* happen now.

What shipped: `SyncScheduler.recoverRegistration` (A1-gated repair at a `RejectionBackoff` probing
boundary), the offer-flow approve self-heal, `SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED` +
Drive's `SyncBannerState.DeviceUnrecognized`, and a `401` backoff so a rejected device stops
emitting ~8k requests/hour.

What did not, in rough order of value:

1. **Control has no connectivity/health surface at all.** ⚠⚠ The pairing screen shows **no**
   unrecognised-device banner. The chain
   `GetPendingPairingRequests ERROR_DEVICE_UNRECOGNIZED` → `HostPairingMonitor.deviceUnrecognized`
   → `PairingWizardUiState.hostDeviceUnrecognized` is intact, but **nothing reads that field** and
   nothing references `add_device_not_recognized`; both are dead leftovers, by decision —
   `PairingWizardScreen`'s comment records that the VM switches the tab to the OFFER role instead
   of showing a dialog. ⚠ Deleting `hostDeviceUnrecognized` from a shared `*UiState` owes the Swift
   build.
   ⚠ Still missing: the **Sync dashboard** itself. `SyncDashboardUiState` carries devices, policies
   and pairing state — nothing about whether *this* device is accepted by the relay — so a user who
   never opens the pairing screen still sees nothing. The natural shape is the existing sync-health
   row plus a "re-pair this device" action, plumbed into `control-shared-logic` and mirrored in
   `control-ios`' SwiftUI (⚠ a shared `*UiState` change owes the Swift build).
   ⚠⚠ **And the device list itself omits members silently.** Observed 2026-08-17 immediately after a
   successful re-pair: the desktop appeared in the iPhone's list but not the reverse, and both
   screens were right. The approving device *authors* the new device's row locally
   (`ApprovePairingOffer` → `saveData`), while the reverse direction has to **travel** — and with the
   relay's `sync_block` table wiped and every push answered `402 entitlement_required`, the iPhone's
   own `Device` row could not leave it. So the list cannot distinguish *"no other devices"* from
   *"can't receive them yet"*, and says neither. Same family as the rest of this entry: the surface
   that would fix it is the connectivity row above, and a list that knows it may be incomplete should
   say so rather than render a confident half-truth. ⚠ Note this also means a device list is **not**
   evidence that pairing failed — check the relay's `device` table before believing it.
2. **No "you were removed from this account" UX.** The banner copy covers revocation and relay
   data-loss with one sentence because the client cannot tell them apart (the relay refuses to say —
   see the design doc). If revocation is ever to read differently from amnesia, the *realm* has to
   say so (a synced tombstone the device can still read), not the relay's `401`.
3. **A joiner's 401 is terminal and only says so in a banner.** It holds no A1, so it cannot repair
   itself; the offered action ("Open Control") is inert on desktop, where `StorageSetupLauncher` is a
   v1 no-op — the same limitation `LocalOnly`/`Locked` already carry.
   ⚠⚠ **Confirmed in the field 2026-08-27, and worse than stated here** — the `realmRelayRegistered`
   latch closes the *second* repair path too, so the state is permanent, not merely un-self-healing.
   See *"A joiner that loses its relay registration is wedged permanently"* at the top of this
   section.
4. **`ReregisterDevice`'s own KDoc still describes the in-memory-relay era.** Harmless, but it now
   understates where the repair fires from.

### Entitlement Enforcement — Client Sends Doomed Writes on Record-vs-Token Divergence

**Source:** on-device verification of `ENTITLEMENT_ENFORCE=true` against the live relay
(2026-07-10). See [`commerce-billing-plane.md`](proposals/commerce-billing-plane.md) §8 +
[`storage-entitlement-sync.md`](proposals/storage-entitlement-sync.md).

> **Reproduced with teeth (2026-07-10).** A **paired** second device (phone) inherited the realm's
> storage record (`hasStorage=true`) but had no commerce session, so it could fetch no token and
> emitted a **402 storm** — ~1401 rejected `POST /sync/push` in 4 min (~6/sec, **no backoff**) — while
> the UI spun "Syncing your changes…" forever with **no sign-in path** (it reads storage as active).
> The design + fix for the paired-device case is now its own proposal:
> [`second-device-entitlement-enforcement.md`](proposals/second-device-entitlement-enforcement.md)
> (recommends the relay also accept the already-synced signed `Entitlement` record as an
> `X-Entitlement` credential, hybrid with the token; **that proposal's P0 = the 402 backoff +
> "needs attention" state in improvement 1 below**).

Enforcement is two-layered and mostly avoids wasted requests: an un-provisioned device is
**Local-only** and never attempts to sync (`FolderWatcher.kt`: `if (!storage.hasStorage) return`;
the sync scheduler is gated the same way), and `CachingEntitlementTokenProvider` refreshes the
bearer token *before* its `exp` (minus a skew). So an honest client rarely hits the relay's
`402`.

The seam: the **visible client gate is on the synced `StorageStatus` record** (`hasStorage`),
but the relay's hot-path credential is the **session-gated token** (`POST /commerce/entitlement/token`).
These can diverge — a device can have `hasStorage = true` yet be unable to fetch a token *right
now* (commerce/session unreachable, or no device-session flow at all: `EntitlementTokenClient`'s
`CommerceEntitlementTokenClient` still returns `null` in the "Phase 3a" note). In that window the
client sends a header-less `POST /sync/push` → `402 entitlement_required` → retry-after-fetch. The
relay `402` is thus a real reactive path, not purely theoretical (also reachable via entitlement
lapse/revocation between refreshes, or clock skew vs. the refresh window).

> **⚠⚠ `RejectionBackoff` bounds how often a *pass* runs, not the fan-out inside one.** A pass
> therefore stops at the first queue-wide rejection (a 402 is scoped to the owner's entitlement, a
> 401 to the device's identity, so every remaining block is refused for the identical reason) —
> `drainPendingBlocks`, `drainExemptBlocks` and per-realm in `drainSharedRealms`. Pinned by
> `SyncHealthReportingTest.aQueueWideRejection_stopsThePass_…` (12 → 1 request) plus its complement,
> `aPerBlockFailure_stillTriesTheRestOfTheQueue`, so it cannot decay into "stop on any failure".
> ⚠ Still open here: **`drainSharedRealms` has no backoff of its own.** A shared realm's entitlement
> belongs to that realm's owner, so the personal instance is the wrong one to consult; it now refuses
> to fan out, but a *per-realm* backoff is undesigned, and nothing rate-limits how often that drain
> retries a doomed realm.

Sized improvements (proportionate — none blocking):
1. **Gate/back-off the push loop on *token* availability, not only `hasStorage`.** When the token
   provider returns `null` persistently, suspend pushes with backoff instead of attempt-then-402
   each cycle. (The token contract says it "retries after a successful fetch," which avoids a tight
   loop, but the write still goes out once and `402`s per cycle; no explicit `402` backoff was found
   on the push path — verify.)
2. **Distinguish "Local-only (never set up)" from "storage lapsed — re-authenticate"** in the UI, so
   a token/session outage is actionable rather than looking identical to a fresh device.
3. **Pre-flight the token on app resume** so the first post-resume write already carries a header.

⚠ **Do not "optimise away" the relay check.** `requireEntitlement` on `POST /sync/push` /
content-blob `PUT` / share+grant drops (`EntitlementEnforcement.kt`) is the *security boundary*
(zero-trust: a malicious/buggy client can push without self-gating), so it must verify every write
regardless. The client-side gate is an optimisation + UX; the relay `402` stays. The goal is only
to make the honest client rarely trigger it.

### Selective Delivery (Recipient Hints in Block Envelope) — Considered, Rejected

**Source:** decision #4 of the May-2026 open-design-decisions walkthrough (deleted
2026-08-13): *no selective-delivery (Flavour C) for now*.

Adding recipient hints to block envelopes for relay-side per-recipient delivery
filtering was considered and rejected. The eager/lazy split + volume-key encryption
already covers most bandwidth needs; per-recipient hints would leak who-talks-to-whom
metadata to the relay (an A6 expansion the threat model doesn't accept lightly).
Revisit only if measured bandwidth cost becomes a real complaint.

### Public-Key Rotation Without Re-Pairing — Deferred

**Source:** decision #20 of the May-2026 open-design-decisions walkthrough (deleted
2026-08-13): *defer public-key rotation; track as open question*.

A device that wants to rotate its A4 signing key (lost laptop, prophylactic rotation)
has no path today. The natural shape — a `Device { signingKey = X' }` entity update
signed by the *old* key, with the relay updating its known-keys map from the synced
block — has a circular trust problem (the relay has to update the map *before* it can
verify subsequent JWTs from the new key). Sized separately when there's a concrete
rotation need.
⚠ Since workstream B (`admission-revocation-names-the-capability.md` §7i) the device id is
**derived from the key**, so a rotation cannot keep the id: it is a new device, admitted again, and
the old id's history stays under the old id.

### Pairing Handshake State — In-Memory Only

**File:** [
`sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncManagerImpl.kt`](../sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/SyncManagerImpl.kt)
(`pendingHandshakes`).

Device B's pairing flow stores the ephemeral keypair + host realm `ownerId` in a
plain `mutableMapOf<String, HandshakeContext>` on `SyncManagerImpl`. If the proxy
process dies between `InitiatePairing` and the first successful `GetPairingStatus`
poll, the handshake state is gone — the `pairingSecret` the relay still holds maps
to nothing on Device B, so the user has to restart the wizard from scratch (and
Device A's pending pairing-request entry on the relay is now orphaned until TTL).

Not a security issue (the ephemeral key is unrecoverable, which is the right
fail-mode) and rare in practice (the polling window is short), but a real UX
footgun on platforms where the proxy can be killed independently of the UI
process — Android Binder + service-bound Drive being the obvious case.

**Proposed Fix**:

- Persist `pendingHandshakes` via `SecureStorageDelegate` (the keypair is sensitive
  and must be cleared on `clearHandshake` / TTL).
- Or, equivalently, persist enough material that `GetPairingStatus` can re-derive
  the ephemeral key — though re-derivation requires another secret to seed it,
  which just moves the persistence problem.
- Either way, gate behind a TTL so a long-orphaned handshake doesn't linger.

### `SecureStorageDelegate` — Split + Drop Deprecated `EncryptedSharedPreferences`

**Source:** [`core-preference-service.md`](proposals/core-preference-service.md) §10.4.
**Files:** `core-common/.../delegate/{SecureStorageDelegate,AndroidSecureStorageDelegate,
InMemorySecureStorageDelegate}.kt`, `tenshu-sdk-js/.../IndexedDbSecureStorageDelegate.kt`,
`SyncManagerImpl.kt` (call sites move).

The Android `EncryptedSharedPreferences` backend is **gone (LANDED):** secrets are sealed with a
non-extractable AndroidKeyStore AES-GCM key, `IV‖ciphertext` blobs in a plain `tenshu_secret_store`
SharedPreferences file (Control now excludes it from Auto Backup / device transfer, closing the
restore-time `EncryptedSharedPreferences.create` crash); the deprecated `androidx.security:
security-crypto` dep is removed; no legacy migration (backward compat out of scope).

**Still open — the structural split.** `SecureStorageDelegate` still conflates two jobs (master-key-
grade secrets vs plain per-device strings) behind one interface. Split into three abstractions so
"secret-grade or runtime-state?" becomes a compile-time type choice:

| Abstraction | Stores | Android backend |
|---|---|---|
| `MasterKeyStore` (rename of `SecureStorageDelegate` → `storeSecret`/`getSecret`/`remove`) | `RECOVERY_PHRASE`, `UNLOCK_WRAPPER`, `VOLUME_KEY` | AndroidKeyStore seal landed; remaining = rename + move blob store SharedPreferences → `DataStore<Preferences>` |
| `LocalStore` (new) | non-syncable state (`DEVICE_ID`, `LAST_SYNC_TIMESTAMP`) + ephemera. Per-device *user-meaningful* prefs belong in `DeviceSetting<V>`, not here | plain `DataStore<Preferences>` |
| `PreferenceService` (see `core-preference-service.md`) | synced user prefs (Wi-Fi-only, offline files, theme) | Realm DB |

⚠ **The Keystore can't hold the secrets directly** — Keystore key material is non-extractable, but we
store read-back-as-plaintext secrets (BIP-39 phrase, wrappers, ids), so the two-layer shape (one
non-extractable AES-GCM key encrypting per-value blobs in a readable store) is unavoidable. Decoupled
from the preference-service proposal — either order. Remaining migration is the `SharedPreferences` →
`DataStore` blob move (`IndexedDbSecureStorageDelegate` already separates these at the JS layer). Open
Q: does `LocalStore` need a Flow surface? (a minimal `get/put/remove` may suffice.)

### Desktop `SecureStorageDelegate` — Persist Master-Key Secrets (Encrypted-at-Rest)

**Source:** [`desktop-masterkeystore.md`](proposals/desktop-masterkeystore.md) §8 (pairing
investigation, 2026-06-04). **Files:** `control-shared/src/desktopMain/.../data/
{DesktopSecureStorageDelegate,DesktopMasterKeyStore}.kt`.

Desktop used to run `InMemorySecureStorageDelegate` against a persistent store → fresh `DEVICE_ID` +
lost keys every restart (duplicate device rows, no realm resume). **Landed:**
`DesktopSecureStorageDelegate` persists the non-secret `LocalStore` keys, and **macOS**
`DesktopMasterKeyStore` persists the master-key secrets **encrypted at rest** (`secrets.enc`,
AES-256-GCM, wrapping key in the macOS login Keychain via the `security` CLI) — so macOS desktop no
longer re-pairs after restart. Threat-model A21 + T-C-17. (Not the *shared-realm* master keys in
`tenshu-rei.md`.)

**What's still missing:**
- **Windows / Linux** credential-store backends (macOS-only today → `NoWrappingKeyStore` fallback ⇒
  in-memory ⇒ re-pair on restart). DPAPI / libsecret = proposal Phase 3.
- **macOS Touch ID gate (Phase 2)** — seam + signing rig landed + biometric confirmed on-device;
  **outstanding:** the embedded *installed*-app Touch ID round-trip, Developer-ID + notarization
  signing for distribution, and folding the Phase-1 at-rest store behind the gate.
- The `SecureStorageDelegate → MasterKeyStore` rename + `isPersistent()` surface — tracked by the
  sibling "SecureStorageDelegate — Split…" entry above.

### macOS Tray (`TrayAgent`) — Thin the Swift Surface to a Dumb Renderer

**File:** `control-desktop/macos/TrayAgent/main.swift` (`TrayModel`).

**Decision (2026-06-04):** the menu-bar tray *stays Swift* — it's the right tool at a clean
process boundary. The native APIs it leans on (`NSStatusItem`, `NSPopover`/`NSPanel`,
`NSWorkspace`, `NSImage`) are Objective-C, and the popover is SwiftUI; none are plain-C, so
Kotlin/Native cinterop wouldn't be a lightweight shim — it would mean standing up a `macosArm64`
executable target (build weight the project has deliberately kept out, cf. the iOS honesty-stubs)
*and* rewriting the SwiftUI popover in raw AppKit, since SwiftUI has no ObjC surface K/N can reach.
The only thing a K/N rewrite would dedupe is the ~40-line frame protocol in `BridgeClient.swift`,
which is small and stable.

**The actual debt** isn't the language — it's that *presentation logic* currently lives in Swift
and should be pushed daemon-side so the agent shrinks toward "draw the menu, forward the click":

- `TrayModel.notificationRow(_:)` maps notification `kind` → English strings
  (`"file_added"` → `"Added \(name)"`, etc.).
- `TrayModel.syncLabel(_:)` interprets sync `state` → `"Synced"`/`"Locked"`/`"Sync off"`.

These are a **localisation island** in Swift — the `// this surface owns its own strings, ADR 0016`
comment is a deliberate-but-debatable call that fights ADR 0016's "strings authored via
`stringResource(…)`/`t(…)`" rule. Pushing the composed display strings into the bridge payload
(e.g. `recentNotifications` returns a ready-to-render `text`; `getSyncState` returns a `label`)
moves the strings back to Kotlin, lets them flow through the normal localisation pipeline, and
leaves the Swift agent as a near-stateless renderer.

**Why deferred:** purely a refactor — current behaviour is correct (English at n=1, matching the
rest of the app). Worth doing when (a) tray strings need a second locale, or (b) the next
substantive tray feature touches `TrayModel` anyway. Not worth a standalone PR before either.

**Revisit when:** localisation reaches n=2 (the island becomes a real gap), or the bridge gains a
typed/structured notification surface the tray could render generically.

---

## Web / Kotlin-to-JavaScript Toolchain

Concrete engineering tasks the web work surfaces but doesn't itself solve. Captured here so they
get sized rather than buried as forever-doc-only items in the architecture doc's
[§12.4](web/architecture.md).

### Kotlin-side Bundle Splitting

**Files**: `tenshu-sdk-js/build.gradle.kts`, `web/sdk/src/_bundle.ts`,
[`docs/web/architecture.md`](web/architecture.md) §12.11.

**Status**: TS-side `exports` map landed (`@tenshu/sdk/core`, `/identity`, `/notifications`,
`/files`); the contract is published. `:tenshu-sdk-js` declares `useEsModules()` so webpack
does ESM-aware tree-shaking across the inter-module compileSync output, knocking ~28 KB off
the apex bundle (533 → 505 KB). **Real per-service splitting is still pending** — apex is
still over Vite's 500 KB warning threshold.

**Problem**: `:tenshu-sdk-js`'s `js(IR) { browser(); binaries.executable(); useEsModules() }`
still produces a single webpack UMD bundle. The bundle's IIFE initializes the entire
`app.tenshu.sdk` namespace as a side effect, so Vite/Rolldown can't slice through it on
the consumer side — every per-service entry point pulls in `:core`, `:core-common`, every
feature service, the kotlinx-coroutines runtime, the in-process runtime infrastructure,
and `DefaultSyncCryptoManager`. A third-party app that only needs `Notifications.observe`
still pays for everything.

**What was tried**: pointing `_bundle.ts` directly at the per-module ESM output
(`tenshu-sdk-js/build/compileSync/.../Drive-tenshu-sdk-js.mjs`) so Vite/Rolldown bundles
the per-module ES klibs and tree-shakes across them. Prototyped on the bundle-splitting
branch and reverted. Findings:

- It does enable per-service slicing — apex importing only `@tenshu/sdk/core` produced a
  19 KB smaller bundle than importing all four services. Real but small.
- The Rolldown-bundled output is ~20 KB *larger* than the webpack output for the same
  inputs (527 KB vs 505 KB on apex's full-import case), so the slicing-on net is roughly
  a wash for apps that use everything. Rolldown lacks the Kotlin-aware optimizations
  webpack's `kotlin-web-helpers` plugins do.
- The Kotlin .mjs output uses bare-specifier imports for npm deps (`@noble/hashes`)
  which live outside Vite's resolution scope. Worked around with a Vite `resolve.alias`
  + duplicating the dep in apex's `package.json`. More moving parts for no net win.

**Proposed paths forward** (either lands real slicing without the regressions above):

- **Multiple Gradle webpack outputs**: split `:tenshu-sdk-js` into per-service modules
  (`:tenshu-sdk-js-core`, `-identity`, `-files`, `-notifications`). Each produces its own
  webpack UMD bundle keeping the Kotlin-aware optimizations. Shared core (`:core`,
  `:core-common`, kotlinx-coroutines, schema walker) extracts via webpack `splitChunks`
  into a vendor chunk so it doesn't duplicate. `_bundle.ts` becomes per-service. Cleanest
  separation; the most build-pipeline work.
- **Lazy-load `JsRuntime`**: the bulk of what `core` pulls in is the runtime
  (`SchemaRegistry`, `TraitResolverImpl`, `PureOperationRunnerImplementation`,
  `DefaultSyncCryptoManager`). If `JsRuntime` is constructed via dynamic import instead
  of a top-level singleton, Rolldown can dead-code-eliminate it for apps that don't call
  `runtimeStatus`. Smaller scope; only helps apps that don't construct an in-process
  runtime (most third-party apps that go through postMessage to the apex).

**Testing**: a Vite build that imports only `@tenshu/sdk/notifications` should produce a
bundle materially smaller than one importing `@tenshu/sdk/files`. Add a CI check that asserts
per-entry-point bundle-size budgets once the work lands.

### Dukat Output Quality

**Files**: would-be `:tenshu-sdk-js` module's TypeScript declarations.

**Problem**: Dukat (the JetBrains tool that generates `.d.ts` from Kotlin sources) produces
TypeScript bindings that are correct but not idiomatic. `TString` becomes a class with `.get()`
rather than a JavaScript `string`; `TList<T>` becomes a class with `.toList()` rather than a
JavaScript array; `TResult.Success` becomes a typed sealed-class discriminant rather than the
`{ ok: true, value: T }` shape TypeScript developers expect. Third-party developers consuming
the raw Dukat output will hate it.

**Proposed Fix**:

- Hand-written TypeScript adapter layer on top of the Dukat-generated bindings, exposed as the
  *public* `@tenshu/sdk` API. Adapter converts between the Kotlin-idiomatic shapes and
  JavaScript-idiomatic shapes.
- Adapter lives in `web/sdk-adapter/` (or a similar workspace member) and is bundled into
  `@tenshu/sdk` rather than published separately.
- Maintain the adapter as the SDK API evolves; treat the auto-generated Dukat output as an
  implementation detail.
- Audit: every public `@tenshu/sdk` symbol should have a hand-written adapter signature; nothing
  raw from Dukat should be directly exported.

### The web entitlement reader verifies nothing (2026-10-12)

`FirstPartyPlugins.kt` calls `initEntitlementStorageReader` without `verifyRecord`, and the
parameter's `null` default means "accept": on the web, figures and `hasStorage` are derived from
whatever `Entitlement` row is stored, whoever signed it. Android, desktop and iOS pass
`SyncCryptoManager::verifyEd25519` through `initEntitlementStorage`; the web never did, and the
reader's own comment records it as the historical behaviour.

**What it costs.** `entitlement-trust-anchor-distribution.md` D2 says `hasStorage` fails closed
over the locally verified record. On the web it does not: a forged or foreign-plane record opens
the local sync gate and shows a plan. The relay still re-verifies every write against its own key,
so nothing is *stored* on a forged record — the exposure is what the browser displays and attempts.

**Why it is a change of its own.** Wiring the
verifier flips the production web client to fail-closed on a path that has never run in a browser:
if the JS Ed25519 backend disagrees with the JVM one on any real record, the apex stops syncing.
That wants its own change, proven against the dev lab's web client first (which is what the lab is
for) — a `jsTest` pinning the backend against a commerce-signed record, then a browser pass on
`tenshu.xyz` with and without the issuer compiled in.

**Fix shape.** Pass `verifyRecord` (`publicKeysFor(publisherId).any { verify(...) }`) and
`reportUnverified`, exactly as `initEntitlementStorage` does. ⚠ The apex's local-trial mode mints
with `DEV_SEED` under `tenshu.dev`, which is in the built-in set, so it keeps working.

### WebAuthn PRF — Graceful Degradation Surface

**Files**: [`web/apex/src/lib/webauthn.ts`](../web/apex/src/lib/webauthn.ts),
[`tenshu-sdk-js/.../Session.kt`](../tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/Session.kt),
[`docs/web/roadmap.md`](web/roadmap.md) Phase 5.

**Problem**: Phase 5's PRF-extension wrap path (landed 2026-05-09) wraps
the realm master keypair (asset A1) under the WebAuthn `prf` extension's
output. Authenticators that don't implement PRF — older platform
authenticators, some hardware keys — silently omit the field. The SDK
treats this as graceful degradation: `Session.registerPasskey` skips
the wrap; `Session.unlock` mints an opaque session token without
unwrapping the master keypair (which therefore stays unloaded in
`SyncManagerImpl.currentMasterKeyPair`). The user can still "unlock"
in the apex's UX sense (session token is valid, control plane loads),
but any operation that needs the master key (pairing a new device,
re-encrypting after revocation, etc.) fails downstream rather than at
unlock.

**Proposed Fix**:

- Surface PRF-support state to the apex: extend the `Session.unlock`
  result with a `masterKeyLoaded: boolean` flag (or surface it via
  `Session.detect` once unlocked) so `LockedFlow` / control-plane
  views can show a "this browser hasn't fully unlocked your keys"
  banner with a CTA to "use recovery phrase to unlock fully on
  this browser".
- Decide product behaviour for the no-PRF case: warn at registration
  time ("this device can't securely save your unlock key — you'll
  need your recovery phrase each time"), or lean on the recovery
  fallback as the only path on those browsers. Cross-browser sweep
  (5.6) gates the data we need to size this.
- Multi-realm-per-browser support is a separate follow-up;
  `Session.unlock` currently uses
  `IndexedDbMasterKeyStore.singleEntryOrNull()` and won't disambiguate
  multiple wrapped blobs.

**Why deferred**: The wrap path is correctly opt-in: a no-PRF browser
gets exactly the security profile it had pre-Phase-5 (opaque token,
master key re-derived from recovery phrase). Surfacing the difference
to the user is UX polish, not a security regression. Cross-browser
testing (5.6) will inform the copy.

### Raster OG Image for Apex Landing

**Files**: [`web/apex/public/og.svg`](../web/apex/public/og.svg),
[`web/apex/index.html`](../web/apex/index.html).

**Problem**: The apex landing's Open Graph + Twitter card meta tags
point at `og.svg` — an SVG hero card with the wordmark + tagline.
Slack, Discord, iMessage, Mastodon, and Bluesky render it correctly,
but **X (Twitter) and LinkedIn require a raster format** (PNG/JPG)
and skip the image when shared. Until that's fixed, links posted to
those two platforms fall back to text-only previews.

**Why deferred**: Producing a raster card today means picking an SVG
→ PNG converter (`@resvg/resvg-js`, `sharp`, headless Chromium, etc.)
and wiring a build step — wasted effort given the placeholder card is
visually thin and will be redesigned anyway when the brand round
lands. The SVG ships the right meta tags + sizing (`og:image:width`
1200, `og:image:height` 630) so swapping the underlying file for a
PNG is a one-line change once the design exists.

**Proposed Fix (do this with the design round)**:

- Replace `og.svg` with `og.png` (1200×630, designed by the brand
  workstream).
- Update the four `og:image` / `twitter:image` references in
  [`index.html`](../web/apex/index.html) and the
  `og:image:type` from `image/svg+xml` to `image/png`.
- If the brand assets exist as SVG sources, add a one-shot
  `pnpm exec` script to render PNG via `@resvg/resvg-js` so the
  source-of-truth stays in vector form. Optional — manual export from
  the design tool is fine for a placeholder-replacement.

### TypeScript Interop Ergonomics

**Files**: future hand-written adapter layer.

**Problem**: Even with a clean adapter, calling Kotlin from TypeScript has friction.
Coroutines map to Promises but with caveats (cancellation semantics differ). Sealed classes
don't have a perfect TypeScript representation (discriminated unions are close but not the
same). Generic types from Kotlin (`TList<File>`) lose information when crossing the JS boundary
unless the adapter explicitly preserves it.

**Proposed Fix**:

- Establish a small style guide for the adapter layer covering each of these cases. Document
  it in `web/sdk-adapter/CONVENTIONS.md` (or similar) so new SDK additions follow the same
  pattern.
- Coroutines → Promises: cancellation is best-effort; document that `AbortSignal`-style
  cancellation isn't guaranteed unless the underlying `IoOperation` supports it (per
  [ADR 0012](adr/0012-interactor-pattern.md)'s cooperative-only stance).
- Sealed classes → discriminated unions: hand-written `type X = { kind: 'A', ... } | ...`
  matching the Kotlin sealed hierarchy. Explicit, readable, but requires manual sync.
- Generics: prefer wrapper types in TypeScript that retain type parameters (`TenshuList<File>`)
  rather than collapsing to native arrays.

### `:tenshu-sdk-js` Module — Phases 1–5 Landed; Bundle Splitting + Publish Pipeline Pending

**Files**: `tenshu-sdk-js/`, `settings.gradle.kts`,
[`docs/web/architecture.md`](web/architecture.md) §12.6.1,
[`docs/web/roadmap.md`](web/roadmap.md).

**Status (landed):** `:tenshu-sdk-js` (`js(IR)`, `api`-depends on the 15 in-scope KMP modules)
assembles the npm-ready webpack bundle consumed by `web/sdk/` (`@tenshu/sdk`, a hand-written TS
adapter over `native.d.ts`). The `@JsExport` surface uses the schema-walker pattern
(`Composite.toJs()`) for domain types; the in-process `JsRuntime` (mirrors `CoreCommonModule`) wires
the full client surface over `IndexedDb*` delegates, so `FileSystem.*` / `Notifications.observe` /
`Realm.create` / `Pairing.*` / `Recovery.claim` are real proxy-backed ops. Phases 1–5 (incl. crypto
A–D + WebAuthn verify + PRF wrap/unwrap) + per-service `exports` map (TS half) landed. See
`web/architecture.md` §§12.6.1/12.9–12.12.

**Still pending** (per [`roadmap.md`](web/roadmap.md)):

- **Phase 3 — request DSL helpers**.
- **Kotlin-side bundle splitting** (separate "Kotlin-side bundle splitting" entry above) —
  per-service Webpack outputs so consumers of `@tenshu/sdk/notifications` don't pull in
  `:file-service-proxy` etc.
- **Phase 5.6 — cross-browser WebAuthn sweep**: Phase 5 verify-only + Phase 5.7 PRF
  wrap/unwrap shipped Chrome-first; Safari (desktop + iOS), Firefox, hardware-key
  matrix, and PRF capability detection edge cases are the remaining work.
- **Bundle-Size Strategy / per-service `exports` map** (TS half landed; Kotlin-side
  splitting is the gating piece).
- **Dukat-generated `.d.ts`** (separate entry "Dukat Output Quality").
- **Phase T — JS test infrastructure**: `compileTestKotlinJs` is currently disabled in
  `:core-common`, `:file-service-common`, `:file-service-client`,
  `:notification-service-common`, because their `commonTest` sources import test fixtures
  (`TestProxy`, `FileTestProxy`, `NotificationTestProxy`) that live in `nonJsMain`. Folding
  those test sources into a `nonJsTest` intermediate source set is the proper fix and lands
  with the broader testing infrastructure work.
- **Publish target**: a private npm registry pipeline; currently the bundle is build-only.
- **CI**: web/apex CI shipped (`.github/workflows/web.yml`) — builds the
  `:tenshu-sdk-js` bundle, then runs apex lint + typecheck + vitest + build.
  Tag-triggered SDK publish is the still-missing piece.

---

### `JsRuntime` SyncPlugin Eagerly Constructs the Relay for Fall-Through Op Ids

**Files**: `tenshu-sdk-js/src/jsMain/kotlin/app/tenshu/sdk/internal/FirstPartyPlugins.kt`
(`SyncPlugin.operationFactory`), `…/internal/JsRuntime.kt` (`syncRelayLazy`, `configure`),
`tenshu-sdk-js/src/jsTest/kotlin/app/tenshu/sdk/SdkRuntimeTest.kt`.

`SyncPlugin.operationFactory` returns `OperationImplementationFactory { id ->
syncProxyImplementationFactory(id, ctx.syncRelay, …) }`. Because `ctx.syncRelay` is a
positional argument, it is **evaluated whenever the lambda runs for any id that reaches it**
— not only for ids the factory actually handles. `operationImplementationFactory` walks the
plugin chain with `firstNotNullOfOrNull { it(id) }`, so any op id that no earlier plugin
(file / notification / identity) claims falls through to the sync arm, fires the
`syncRelayLazy`, and constructs `RestSyncRelay` — even when the factory then returns `null`
for that id, and even for sync ops that only need `syncManager` (`GetSyncStatus`,
`StartPairing`, `GetRecoveryPhrase`, …). The header comment in `JsRuntime.kt` claims "the
relay's lazy stays unfired until a real sync op dispatches"; that holds for *claimed* file/
notification ops (sync is ordered last) but **not** for fall-through ids or relay-less sync
ops.

The effect in tests: the runtime can boot already sync-enabled (a prior run leaves a
`RECOVERY_PHRASE` in IndexedDB → `SyncManagerImpl` auto-loads it → `isEnabled()` true), the
`SyncScheduler`'s first tick dispatches a relay op, and the lazy is constructed under whatever URL
is current. Since `JsRuntime.configure(...)` rejects a *later* URL change ("configure once before
the chain is built"), a first construction under the production default `https://relay.tenshu.app`
breaks `RealmTest`'s `configure(localhost:9)` and fires the test runtime at the production relay.
`SdkRuntimeTest` **neutralises the effect** by pinning the relay to an unreachable sink in
`@BeforeTest` before any sync-touching call — it does **not** remove the eager-construction seam.

**Proposed fix (sized, small)**: thread the relay into `syncProxyImplementationFactory` as a
provider (`relay: () -> SyncRelay`, or a nullable `relayProvider` consulted only inside the
branches that need it) so the lazy is touched only for ids that actually use it. That makes
the `JsRuntime.kt` comment literally true, removes the fall-through and relay-less-sync-op
firing, and lets `SdkRuntimeTest`'s sink pin become belt-and-suspenders rather than
load-bearing. The signature is shared by production / system-test wirings, so it's a
cross-caller change — additive default (`relayProvider = { relay }`) keeps existing callers
compiling.

**Adjacent cleanup**: `Realm.create` persists `RECOVERY_PHRASE` to IndexedDB and nothing in
the JS test suite purges it, so the suite is stateful across runs (the booted-already-enabled
condition above). A `Session.clear({purgeRealm: true})` teardown on the realm-creating tests,
or a one-time purge at suite start, would make the suite deterministic regardless of the relay
seam.

**Revisit when**: touching `JsRuntime` op-dispatch / plugin wiring, or if the sink-pin proves
insufficient (e.g. a future test that builds the executionContext without extending
`SdkRuntimeTest`).

---

### TypeScript 7 Is Held Back by `typescript-eslint`

**Files**: `web/apex/package.json`, `web/drive/package.json`.

**Status**: TypeScript is pinned at `~6.0.3` in both web apps. Every other direct dependency
in the `web/` workspace sits at its current release as of 2026-08-25; TypeScript is the sole
exception, and the pin is deliberate.

**Problem**: TypeScript 7 (the Go-native port) has been `latest` on npm since 2026-07-08, but
`typescript-eslint` declares `typescript: ">=4.8.4 <6.1.0"` as a peer range and has not
widened it — not in `8.68.0` (`latest`), and not in the `8.68.1-alpha.3` canary. Bumping
TypeScript alone therefore breaks `pnpm lint` on a peer conflict rather than on a type error,
so the two have to move together. `6.0.3` is the newest stable 6.x, so the current pin is
already at the ceiling of what the lint toolchain accepts — there is no intermediate step to
take.

**Why it is not just "wait"**: the jump is not a normal major. TS 7 is a reimplementation, and
the ecosystem's type-aware lint rules depend on the TypeScript compiler API surface, so the
`typescript-eslint` side is a port rather than a version-range widening. Expect the
compatible release to arrive well after TS 7 itself did.

**Revisit when**: `typescript-eslint` publishes a release whose `typescript` peer range admits
7.x. At that point bump both in one commit and re-run each app's `tsc -b`, `eslint .` and
`vitest run` — the type-checker change is the risk, the lint plugin is just the gate.

---

## Drive App — UX

### Synced Folders: a global pause makes every folder toggle silently do nothing (2026-09-25)

**Files**: `drive-shared-ui/.../screens/settings/SyncedFoldersScreen.kt` (status row ~L290-320),
`SyncedFolderController` (gate order: enabled → pausedByUser → storage → network → charging).

**Observed on the maintainer's OnePlus:** WhatsApp Images switched on, off and on again, and never
synced. The cause was the device-wide pause (`drive.backup.pausedByUser = true`, ST-2b), set
at some earlier point. A folder toggle does not clear it, and `BackupWorker` ran and returned
SUCCESS within a second, uploading nothing.

⚠ **The only sign is one status line at the bottom of the screen** ("Paused — you stopped
backup" + Resume), away from the toggles the user is actually touching. Nothing near a folder's
switch says "this will not run". The maintainer's words: *"I feel like this switch should not be
hidden at the bottom."*

**Fix, sized small:** when a folder is switched on while `pausedByUser` is true, either offer
"Backups are paused — resume?" inline, or show the paused state at the top of the screen beside
the folder list. ⚠ Do not auto-resume: the pause is the user's own act, and a folder toggle is not
consent to undo it.

### Synced Folders: "Up to date" is also shown when backup is DISABLED (2026-09-25)

**File**: `SyncedFoldersScreen.kt` `backupStatusText` — `BackupState.Disabled` and
`BackupState.UpToDate` share one branch and one string.

A user who switched backup off reads "Up to date" and reasonably concludes their photos are safe.
Found while diagnosing the entry above, where "Up to date" was one of the candidate readings and
could not be told apart. **Fix:** a distinct string for `Disabled` ("Backup is off"), plus a
screen test pinning that the two states render differently.

## Drive App Performance

### Paged Listings — Sorted Chains + Signal-Only Streams Don't Fully Window-Fuse

**Files**: `core-paging/.../PagingUtil.kt`, `core-common/.../proxy/db/DatabaseImpl.kt`
(`getArrayData`), `core-common/.../proxy/StreamRegistry.kt` (`setDatabaseListener`).

The 2k-folder ANR was fixed (2026-07-03/04): the per-row `select` projection moved after the paging
window; a `drop`/`take` prefix on a raw array fuses into `getArrayDataWindow` (page loads are
O(pageSize)); signal-only invalidation streams skip the full-list emission; and
`search { key equalTo }` short-circuits to a content-addressed point read. **Remaining debt:**

- **Sorted chains don't window-fuse.** The events feed
  (`notifications.sortByDescending{ts}.toPagingData`) has `SortBy` ahead of
  the window, so its page loads still pay a full read + sort. Inherent (a
  correct sorted window needs the full order) unless the notifications array
  is maintained timestamp-ordered at write time — then the sort becomes a
  no-op the fold could skip through.
- **Signal-only streams still evaluate once per fire and on subscribe.** The
  initial evaluation is load-bearing (nested-ref discovery + the
  `streamIdToItems` diff baseline — without it the first change re-emits
  everything as "new"). A true no-eval signal mode needs (a) ref discovery
  without materialisation and (b) diffless triggering via a payload-free
  `StreamElement.Invalidated` element; with Tracks 1–2 shrinking the eval to
  point reads + one final array materialisation, it hasn't been worth the
  protocol addition yet.

**Priority**: medium — folders in the hundreds are now fast; revisit
when 10k+ listings appear or the proxy shows deserialisation hotspots.

---

## Fit App

### Active Minutes never derived from Health Connect (Home ring reads 0) — age and file-import wiring outstanding

**Files**: `fit-shared-data/src/androidMain/kotlin/app/tenshu/fit/data/HealthConnectImporter.kt`
(`maxHeartRate`), `FileHealthImporter`, `fit-shared-data/.../ActiveMinutes.kt`.

Health Connect has no "active minutes" record, so `DailyAggregate.activeMinutes` comes from a
per-field merge of a steps-based WHO-moderate proxy (`estimateActiveMinutes`) and
`ActiveMinutes.hrZoneMinutes` (WHO moderate+vigorous from the day's HR series, one int/day at
import); pinned by `FitProjectionTest` and `ActiveMinutesTest`. Two gaps, both low priority:
(a) **real per-user age** — max HR uses `DEFAULT_AGE` (30 → 190 bpm) because `FitProfile.birthDate`
is an in-session default; wire a persisted profile's age into `HealthConnectImporter.maxHeartRate`.
(b) **desktop/file import** — `FileHealthImporter` (TCX/GPX) does not call
`ActiveMinutes.hrZoneMinutes` for its HR streams, so file-imported days rely on the steps proxy.

### Content-plane blobs are never GC'd when their entity goes away — 3933 blobs vs 1167 rows

**Files**: `health-service-proxy/src/commonMain/kotlin/org/tenshu/health/proxy/executor/RecordWorkoutImplementation.kt`
(writes `/.health/streams/{workoutId}`), `health-service-proxy/.../WorkoutStreamsPropertyWriter.kt`,
plus whatever deletes `Workout` rows (`DeleteRealmData`, or a re-import that re-keys ids).

Measured on a real device (2026-07-29): Control's `files/.health/streams/` held **3933 blob files**
against **1167 `tenshu/health/workout` rows** in `objects.db`. `DeleteWorkoutImplementation` now
unlinks the stream blob alongside the row, but that is the only cascade: nothing sweeps blobs
already orphaned, and a path that removes `Workout` rows without that op (`DeleteRealmData`, a
re-import that re-keys ids — not verified individually) leaves its blob behind. The relay-side
content GC is a separate mechanism.

Generalising needs a decision before code: (a) **delete-time cascade** for every `OnDemand`
property — the entity delete resolves its external-property paths and unlinks them, which needs the
property→path mapping to be enumerable (today only the writer knows it); or (b) **mark-and-sweep** — a maintenance pass that lists
`/.health/streams/` and unlinks orphans with no owning row, scheduled off the idle+charging
`PlatformScheduler` like compaction. (b) is more robust across crash windows and generalises to every
`OnDemand` property, so it likely belongs with the broader retention work
(`retention-quota-model.md`, tier 3 "local cache") rather than in the health service.

**Priority**: medium — unbounded local disk growth proportional to churn, no correctness impact
(orphans are unreferenced, so they never resurface as data). Bounded in practice by how often entities
are deleted, which a full-history re-import makes non-trivial.

---

## Timeline App

### With the place-name opt-in off, transit stops lose the offline tile tier too (2026-10-17)

**Files**: `timeline-shared-data/.../geocode/PlaceGeocodingEgress.kt`, the `NearestTransitStop` op
(`:map-region-service`). The egress gate refuses the whole `nearestTransitStop` call when
the opt-in is off, so the proxy's tile tier, which involves no third party, is skipped along with
the Photon fallback. Asking for "tiles only" needs a new `NearestTransitStop` parameter: a
`MapRegionService` version bump + `SchemaMigration` and a lockstep move of Control and `:hub`
(House Rule 9). `place-geocoding.md` §5.1, follow-up (a).

### iOS route-1 never shows the place-name toggle, so its transit legs carry no stop names (2026-10-17)

**Files**: `tenshu-sdk-ios-timeline/.../TimelineSurface.kt` (`geocodingAvailable()`),
`timeline-ios/Sources/SettingsView.swift`. `geocodingAvailable()` is true only with an installed
enricher, which route-1 does not build, so the opt-in can never be turned on there and the egress
gate refuses every stop lookup. Nothing private is lost (the names came only from Photon, which the
gate now keeps closed without the opt-in), but iOS shows no transit stop names until the toggle is surfaced.
`place-geocoding.md` §5.1, follow-up (b).

### Visits captured before Control finishes onboarding — the place writes still discard their results

**Files**: `timeline-shared-data/.../capture/CapturedVisitWriter.kt` + `CapturedVisitOutbox` +
`VisitOutboxStore` (+ `AndroidVisitOutboxStore`), `TimelineImportWriter.kt`,
`geocode/PlaceLabelEnricher.kt`, `TimelineProjectionRepository.renamePlace`.

A captured visit exists only in the detector's memory, so its write path must not trust a
`TResult` it never read (`RemoteBinaryTaskExecutor` folds "proxy not connected" into a
`Failure`). `CapturedVisitWriter` journals the `ImportedTimeline` in a durable app-private outbox
**before** the ops (A35 posture), never throws into the engine (30 s `withTimeout` — ⚠ catch
`TimeoutCancellationException` *before* the `CancellationException` rethrow), drains oldest-first
and replays on app start; `TimelineImportWriter` `orThrow`s so failures surface.

**Residual**: the enricher's and `renamePlace`'s own `recordPlace` results are still discarded
(blank-only fill retries naturally; a rename lost to a dying proxy shows stale UI) — cosmetic next
to the visit path; folds into the `TResult`-discard audit (`RepositoryResult` family).

### Eager-synced `Place` rows never converged phone→desktop while `Moment` rows did

**Files**: `place-service/.../Place.kt` (schema declares `Syncable(SyncStrategy.Eager)`),
`timeline-shared-data/.../TimelineImportWriter.kt` (writes `Place` + `Moment` together),
sync emission/apply path in `:sync-service-proxy`.

**Observed** (2026-07-17, while investigating the phone's "Place not found" regression): the
phone's Control store held both `tenshu/place/place` and `tenshu/timeline/moment` rows (the
Places tab rendered real per-place visit counts), but the desktop Control store
(`~/.tenshu/app.tenshu.control.desktop/objects.db`) held **25 `tenshu/timeline/moment` rows
(cap-visit ids spanning Jul 13–17) and ZERO `tenshu/place/place` rows**. Both types are written
by the same `TimelineImportWriter` call (`recordPlace` + `recordMoment` per captured visit), and
`Place` is `Syncable(Eager)`, so partial convergence means one type's blocks were either never
emitted, never delivered, or dropped on apply. `placeServiceCommonModule` was registered in
Control's proxy DI before any of this data existed (2026-07-02), so a plain
missing-schema wire-registration gap desktop-side doesn't obviously explain it.

**Candidate mechanisms** (undiagnosed):
1. Apply-side drop on the desktop: block arrives but fails to apply (merger not registered for
   `Place`? — the ingestion-track note "mergers not prod-registered" is adjacent) and is acked
   anyway, so it never retries.
2. Emit-side gap on the phone: same family as *"locked-save emits NO SyncBlock + never
   re-emits"* — though moments written in the same call did converge, which this doesn't
   explain.
3. Something specific to `Place`'s upsert identity (`PlaceDataIdProvider` content-addressed
   key) diverging between emit and apply.

**Caveat**: the phone-side copy (the primary evidence) was destroyed by the format-v2 Phase-0
reset on 2026-07-17, so diagnosis needs a fresh repro rather than forensics.

**Repro/diagnosis**: with desktop Control running and paired, capture (or import) a visit on
another device → check the desktop store for the `tenshu/place/place` row alongside the
`tenshu/timeline/moment` row (`sqlite3 objects.db "select type,count(*) from objects group by
type"`). If the place is missing again, instrument which of emit / relay-delivery / apply drops
it.

**Priority**: medium — a silently non-converging `Syncable(Eager)` type undermines the sync
contract itself, and Timeline's place history is exactly the data whose only other copy is the
capture device (as the wipe just demonstrated). Cheap to repro now that capture + geocoding are
live on current builds.

---

## Photos App

### Photos: a preview read can deliver 0 bytes while Control is busy (2026-10-01)

Seen on the maintainer's phone: a face cover's preview came back as an EMPTY byte array — not `null` —
for a photo whose `xlarge` rung (55 KB) was on disk, while Control was busy right after a re-index.
`PreviewCache` cached it and the circle stayed blank until Photos restarted. ⭐ Mitigated (`photos-app.md` §36): empty bytes are treated as no answer everywhere a preview is read or cached, so
the next draw asks again. ⚠ **Still open: why the transfer delivered nothing.** Suspects: the Android
blob hand-off (`ClientConnection.tokenize`, SHM/PFD) under load, or the relation select resolving
before the rung's bytes are readable. A repro needs a busy Control and a cold `PreviewCache`.

### Collections: one lost reply wedges the tab until Photos restarts (2026-09-30) — ⚠ MITIGATED, cause open

Seen on the maintainer's phone: after a reinstall, the Collections tab spun for 8+ minutes while
Control sat idle and the feed tab loaded fine; a Photos restart loaded it in 8 s. One of the reads in
`CollectionsViewModel.refresh()` never returned, and `inFlight` then dropped every later refresh.
⭐ **Mitigated:** the refresh is bounded (`REFRESH_TIMEOUT_MS`, 2 min — the whole-library
walk measured 8 s on 1,345 photos), ends as an error with *Try again*, releases `inFlight` in a
`finally`, and logs `CollectionsViewModel -> refresh timed out … in <read>`. ⚠ **Still open: why a
reply is lost at all** — the next occurrence's log line names the read; start there. Desktop recovers
too since desktop IPC correlates calls (`desktop-ipc.md` §2.2) — ⚠ except against an older desktop
Control, see the next entry. Related: `stream-terminals-audit`, `core-client-resubscription.md`.

### Desktop IPC: one lost reply wedges the whole connection (2026-09-30) — ⚠ only against an older Control

`UnixSocketTaskTransport` correlates plain calls with a transport-level call id
(`TYPE_CORRELATED_REQUEST`/`_RESPONSE`, not a task id), negotiated per connection by a hello: calls
run concurrently, a caller's own timeout frees it, and a late reply for an abandoned id is dropped
(`docs/protocol/desktop-ipc.md` §2.2; pinned against the real server by `UnixSocketLostReplyTest`).
⚠ **Residual:** a new app talking to a desktop Control that predates the hello falls back to the
old serialised path — one request in flight behind `callLock`, waiting in `NonCancellable` — so a
reply the proxy never writes still queues every later call from that app until that Control is
updated. Removing the fallback needs a
decision on the oldest supported desktop Control, not code.

### People: a non-human face can form a person (2026-09-29)

The tiled two-stage detector (`photos-people-and-pets.md` §12.14) finds faces on cats, dolls,
drawings and the occasional pastry. Two photos of the same cat cluster into a group and People shows
it as a person. ⚠ The detector's score cannot separate them (a cat at 0.58–0.72, a real person at
0.50–0.65). ⚠⚠ Nor can the embedder's raw norm: the `FaceModel.minFaceNorm` gate (§12.15) was
perfect on the test set's originals but, on the real library indexed from previews, rejected 26 real
faces with 22 non-faces, so it is off — on previews it cannot work at any threshold (real
10.67–14.95 vs non-human 11.07–14.95). Next: a human/non-human classifier that does not track image
quality, a gate on original-sourced faces only (little effect on a library indexed from previews),
or letting the user hide a group. Pets proper are P1, a different feature.

### People on desktop: no face detector — MediaPipe has no JVM build (2026-09-25)

**Files**: `control-shared-proxy/src/desktopMain/.../di/PlatformProxyModule.kt` (no face backend),
`control-shared/src/desktopMain/.../ui/BuildFeatures.desktop.kt` (`faceClusteringRunsHere = false`).

People runs on Android only. The detector half is MediaPipe Face Landmarker, and `tasks-vision` is an
Android AAR, so desktop has no way to run it. Desktop therefore hides the face switch, filters the
`onnx+mediapipe` catalog entry out, and answers `faceGroups` with `NO_BACKEND`.

**The slice (phase F2d in `photos-people-and-pets.md` §6):**
1. Convert the `.task` bundle's detector and mesh models (TFLite) to ONNX, and show the converted
   mesh's landmarks match MediaPipe's on the same photographs. §3.3's rule applies: a different
   landmark source changes crops, hence templates.
2. A desktop face backend over ONNX Runtime (the JVM jar is already linked), reusing `FaceMesh`,
   `alignFace` and the embedder path.
3. A second catalog entry with runtime `onnx`, the ONNX detector and the same AuraFace embedder,
   then a republish.
4. ⚠⚠ **Re-measure the calibration** with the desktop detector (§10 Q2: a different pipeline is owed
   its own number) before flipping `faceClusteringRunsHere` on desktop. The F2m library lives only
   on the phone, so the owner decides whether it may be copied to the Mac.

Deferred 2026-09-25: Android first, desktop when desktop People becomes a priority.

### The feed's density re-anchor drifted ~a year — seen ONCE, not reproduced (2026-09-17)

**Observed once** on the maintainer's OnePlus, master `5308e431d`, minutes after all ten apps were
reinstalled — so Control's proxy was cold and its first reads slow.

Scrolled to **26 January 2025**, switched Day → Month. One second later the feed read *January 2025*
— the re-anchor had worked — and by the next sample it sat at **January 2026**, where it stayed.
The drift is toward the **top** of the library (newer), roughly a year of photographs.

| Attempt | State | Switch | Result |
|---|---|---|---|
| 1 | cold — minutes after a ten-app reinstall | Day → Month | **drifted to January 2026** |
| 2 | warm | Day → Month | held January 2025, 20 s |
| 3 | warm | Mosaic → Day | held 26 January 2025, 15 s |
| 4 | cold start, two rapid switches | Mosaic → Day → Month | held January 2025, 16 s |

Sampled with `uiautomator dump` every ~2 s, reading the topmost date on screen.

⚠⚠ **No mechanism is claimed.** One sighting with no reproduction supports none — the rule the
*"`PairingVmFlowSystemTest > offerFlow_…desktopLandsPaired`"* entry states, and for the same reason. What follows is a place to
start looking, not a diagnosis.

A candidate worth *testing* rather than believing: the feed's re-anchor (`PhotosFeedBinding`,
`photos-app.md` §32.2) re-tries across the next `MAX_REANCHOR_PASSES` (4) snapshots and then stops.
On a cold proxy the jump refresh it waits for could land after that budget, leaving the grid at
whatever its LINE index pointed to — and a line index does not name a fixed photograph across a
reload, because the placeholder cut holds `columns` photographs per line while a loaded month holds
fewer (a header line, a partial last row). That would drift *toward the top*, which is the direction
seen. It does not explain why the first sample was correct.

⚠ **The maintainer's call (2026-09-17): wait for it to recur rather than fix it on that hypothesis.**
A time budget in place of the pass count is a one-line change and would be untestable against a
defect nobody can reproduce.

**If it recurs, capture before changing anything:** the date trajectory through the switch
(`uiautomator dump` every ~2 s from the tap). The question to answer first is whether the re-anchor
stopped early or whether something moved the grid after it finished.

⚠⚠ **A bare `adb logcat` answers neither.** Nothing on this path logs: `KeysetPagingSource`,
`TenshuPhotosRepository.feedPaging` and `PhotosFeedScreen` print nothing, and the only printers in
`:file-service-proxy` are the backfill and the sweeper. So a page arrival and a re-anchor pass are
both invisible, and correlating them needs markers added **first** — a `println` per pass in the
re-anchor loop (pass number, `isLoaded(photo)`, the line it scrolled to) and one per `load()` in the
paging source. ⚠ On a device that means `println`, not a logger: Tenshu's Android apps have no
logcat tags, and `System.out` is what shows up. ⚠⚠ And instrumenting a timing-dependent defect can
mask it — the *"`PairingVmFlowSystemTest > offerFlow_…desktopLandsPaired`"* entry measured 0/7 after adding prints to the loop
under suspicion, which is why a quiet probe (counters read after the fact) is worth more than I/O
inside the loop.

**Priority**: low — the feed lands near the right place rather than at it, and only across a density
change. Nothing is lost and a scroll corrects it.

---

## Testing

### Three modules are excluded from the JS test line — eight failures nobody has looked at (2026-09-23)

`scripts/run-gate.sh` runs the Kotlin/JS **test** line, but `JS_TEST_EXCLUSIONS` skips three
modules. They were already red the first time the lane ever ran, because nothing had been running them:

- `basemap-core`: `BasemapStyleTest.roadLabelsGateRoadClassOnCameraZoomNotTileZoom`
- `inference-service-proxy`: `DefaultFaceTemplateStoreTest.theBoxSurvivesAReopen`,
  `ModelDownloadProgressTest.theStreamKeepsWaitingWhileADownloadIsActuallyInFlight`
- `routine-service-proxy`: five of the nine `RoutineMultiDeviceTest` tests:
  `only_the_claimed_executor_fires`, `the_hub_wins_the_claim_over_a_desktop`,
  `a_peer_fires_after_the_grace_window_when_the_primary_never_did`,
  `the_fallback_stands_down_once_the_primarys_receipt_exists`,
  `a_race_inside_the_grace_window_collapses_onto_one_row`

All of them fail under `[js, browser, ChromeHeadless]`. ⚠ They are **not** known to be JS defects. They may be tests
that assume a JVM-shaped environment. Nobody has diagnosed them.

⚠⚠ **An exclusion list is a latch.** The gate prints the list on every run, but nothing forces
anyone to shorten it. Take a module off `JS_TEST_EXCLUSIONS` as soon as it goes green. The lane's own
motivating case: `GetPreview` was registered and unimplemented on JS for months, so every browser upload
silently minted no thumbnail. A JS test line is what catches that class, so an excluded module is
not covered against it. A per-platform op-coverage guard in `jsTest` is worth deriving against the
working lane.

### `TombstoneDrainRealmGuardTest` flake — the strict `getEntry` question it raised (2026-09-24)

The flake itself (`Entry not found … while reading array` in `SyncScheduler.drainPendingTombstones`)
was a read racing an unlocked `InMemoryObjectStore` resize under `NoOpDatabaseLock`; `TestProxy` now
defaults to a reentrant `TestDatabaseLock`, pinned by `TestProxyDefaultLockTest`.

⚠ **The production question that outlives the flake.** A missing *member row* is skipped and counted
(`DbEntryMapper.deserializeArray` resolves members through `getMemberEntry`, and
`DatabaseImpl.reportMissingMembers` counts them). The strict `getEntry` still **throws** for the array's
own row and for a member's nested composite child, and a `DeleteBlock`'s `hlc` is one. If a real store
can lose that child row, the whole tombstone drain aborts for the tick.

### No system test covers two files sharing one content (2026-09-22)

The content lifecycle harness owed three tests. Quota → 507 → recover exists
(`ContentLifecycleSystemTest.aFullQuotaRefusesWith507_parksTheUpload_andItLandsOnceSpaceIsFreed`).
**Two references to one content** does not: *delete one, the bytes survive; delete both, they go*.

**No longer blocked; the test is just unwritten.** There is no copy op, and `contentId` is minted per
upload (`UploadFileImplementation`), so the only shipped way to get two live rows on one `contentId` is a
divergent rename (`A: /x→/y`, `B: /x→/z`) or a rename against an edit. Both need two devices that each
act **before seeing** the other's change. The harness can now arrange that:
`TestSystem.client(storageGate = …)` holds a device's pushes while it keeps editing locally.
`ConcurrentMoveVsDeleteSystemTest` shows the pattern: close both gates, `drainNow()` on each, edit,
then reopen. ⚠ Closing a gate is not the same as standing on a closed one, hence the `drainNow()`.
The issue's old warning, that a divergent rename could alias one reference handle (`content-plane-refcount.md`
§6e), no longer applies: B6 retired the handles, and each holder now pushes its own edge.

Relay unit tests (`ContentSweepRoutingTest`, `ContentFreedRoutingTest`, `ContentClockTest`) cover the
edge counting itself. What is missing is the end-to-end case through real clients.

### The move-vs-delete byte outcome *within T* is asserted nowhere (2026-10-29)

CR-2 B's promise (`content-plane-cr2-quorumless.md` §10.7) has two halves. A move pushed **within T**
of a concurrent delete keeps its content. One pushed **after T** gets `content_freed`. The after-T
half is pinned: `FreedContentResolutionSystemTest` (REVIVE from the mover's plaintext, or a
change-lost notice on both devices) and `ContentSweepSystemTest`. The within-T half, **in both
ingest orderings and with the bytes checked**, is not.

`ConcurrentMoveVsDeleteSystemTest` pins only the metadata outcome. Its KDoc still says the byte half
is "owed before B5 is armed", but B5 is armed. The natural home is that class: once the move lands,
assert that the relay still holds the content in both orderings. When it exists, update that KDoc.

### Nothing detects an app dispatching an operation it never declared (2026-09-21)

⚠⚠ **`BundledAppTablesTest`'s per-app permission tests read as coverage for this and are not.**
`fitManifest_declaresOnlyTheOpsFitActuallyDispatches` and its Photos twin assert
`setOf(<literals>) == manifest.permissions`. That catches *over*-declaring, and catches a
declaration being **removed** — but an app that starts dispatching a new operation and declares
nothing leaves **both sides unchanged**, so the test passes. The name says "only the ops it actually
dispatches"; nothing verifies the "actually dispatches" half.

Measured cost so far: `tenshu/file/getStoredThumbnail` was dispatched **undeclared by Drive, Photos
and Timeline** for the whole of `drive-synced-thumbnails.md` and `drive-xxlarge-thumbnail-rung.md` —
every `File.Thumbnail*` projection goes through it — and `tenshu/file/system/hasLocalData` the same
in Timeline until X-6. Found by an adversarial review, not by a gate.

⚠ **It is an honesty defect, not a functional one** ([[new-op-permission-entry]]): nothing gates
`AppPermission`, and Control's own UI says *"Tenshu does not currently restrict what an installed
app can do."* What breaks is the Apps screen — *Services used* is derived from these entries, so a
missing permission can drop a **whole service** from what the user is shown.

**Sized.** The tractable fix is a source-derived test, for which `web/apex`'s `webCiPaths.test.ts`
is the precedent (it derives CI path filters from `build.gradle.kts` rather than freezing them):
scan each app module for the dispatch shapes — `relations(File.Thumbnail*)`, `fileSystem.<op>(`,
`<service>.<op>(` — and assert each resolves to a declared `operationId`. ⚠ Fragile against
indirection, so it would catch the common direct case and no more. Worth it only if this recurs a
third time; until then the honest statement is that **the permission axis has no gate at all**.

### Idle build processes hold the machine's RAM — the dev box kernel-panicked (2026-10-14)

The 24 GiB dev Mac panicked at 15:56 with `watchdog timeout: no checkins from watchdogd`, and the
panic log's compressor line says why: **"100% of segments limit (BAD) with 15 swapfiles and LOW swap
space"**. Resident at the time: a `-Xmx10g` Gradle daemon (8.7 GB, the release link in
`scripts/ios-build-sdk.sh`), the headless emulator `android-smoke.sh` had booted and left up (7.3 GB),
three ordinary daemons (6.0 / 5.0 / 2.8 GB), two Kotlin daemons, Android Studio (6.1 GB) and Chrome
(5.3 GB). That is ~46 GB on 24. The data volume was at 96 %, so swap could not grow
(`build-concurrency-multipliers` in the maintainer's notes: check `df -h` together with `vm_stat`).

⚠⚠ **The gate lock was not the gap.** `run-gate.sh`/smoke/ios-smoke were serialised as designed.
The memory belonged to processes that **outlive** a build: the emulator, idle daemons at their
high-water heap, and a Kotlin daemon that by default stays up 7200 s after its last compile, on
`ParallelGC`, which never returns heap to the OS.

Current settings:
- `android-smoke.sh` stops the emulator it booted on exit (`TENSHU_SMOKE_KEEP_EMULATOR=1` keeps it).
- `org.gradle.daemon.idletimeout` is 10 min.
- Gradle daemons get `-XX:G1PeriodicGCInterval=60000`; the Kotlin daemon gets
  `autoshutdownIdleSeconds=600` (`kotlin.daemon.options`). `-Xmx` is the wrong lever.
- ⚠ **Open:** the Kotlin daemon still runs `-XX:+UseParallelGC`, which never uncommits. Moving it to
  G1 needs a compile to prove the plugin drops its ParallelGC default when another GC is given —
  both flags together are a hard JVM error that would break every build.

⚠ A changed `org.gradle.jvmargs` makes daemons from un-rebased worktrees **incompatible** with
rebased ones, so after any change there are two daemon populations. Rebase long-lived worktrees.

⚠ Open: a one-off heavy build should use `--no-daemon` rather than leave an unreusable daemon behind
(the `-Xmx10g` release link in `scripts/ios-build-sdk.sh`). Android Studio's own daemon follows these same
properties, but the IDE itself (6–8 GB) is outside any gate.

### The gate flakes under concurrent load — a *different* timing-sensitive test each run (2026-09-19)

⚠⚠ **A red `run-gate.sh` under load is not evidence your change broke something.** Measured while
landing X-5, whose only Kotlin was in `:tenshu-sdk-js` — a **leaf module nothing depends on** — plus
one test-only file, so none of the failures below was reachable from the diff:

| load avg | runs | result |
|---|---|---|
| 8–14 | 3 | four distinct reds, **none repeating** |
| ~5 | 1 | all green, 1668 test classes |

The four, each green when re-run alone:

- `core-paging :: rowKey_concurrentPrependAndAppend_carryNoRowTwiceBetweenThem` — since fixed
  (the test's `MockTList` shared one window between both loads);
- `sync-service-proxy :: aBlockRejectionStopsTheTombstoneDrainInTheSameTick_unauthorized`;
- `drive-shared-ui :: folderCreatedWhileListingIsOpenAppearsWithoutRenavigating` — Compose 5 s
  timeout;
- `hub :: tappingTheSwitchMovesTheLightAndTheOutcomeResolvesTheRow` — 30 s timeout.

⚠ All four are **timeout- or ordering-shaped**, which is the signature: CPU contention, not a
defect. A second Claude session building in another worktree is enough to reach load 14 on this
machine.

**How to apply.** Before assuming a red is yours: (1) re-run the named test alone; (2) check whether
your diff can even reach that module — dependency *direction* settles it faster than a re-run;
(3) if the machine is loaded, re-run the gate when it is quiet and compare. ⚠ Do **not** conclude
"flake" from a single passing re-run alone — that is equally consistent with a real race your change
introduced. The load comparison is what makes it evidence.

### `iosKotlinCheck` was a no-op off macOS — it now refuses there (2026-08-24)

**Files**: `build.gradle.kts` (`iosKotlinCheck`), `AGENTS.md` *Build & test*.

Kotlin/Native refuses Apple targets from Linux, so every `compileKotlinIosSimulatorArm64` in the
umbrella's graph is skipped (`onlyIf 'Cross compilation should be supported on host' is false`), and a
task whose only job is to depend on them reports **BUILD SUCCESSFUL** having compiled nothing.
`--rerun-tasks` does not help; a skipped task is skipped again.

⚠⚠ **`iosKotlinCheck` therefore fails off macOS instead of passing** ("cannot verify anything on
Linux"). Never turn that back into a skip: a green iOS gate on Linux carries **zero** information,
and every Claude Code remote session runs in a Linux container. `scripts/run-gate.sh` guards its iOS
lane by `uname` for the same reason. `.github/workflows/ios.yml` runs on `macos-latest` and never
invokes this task.

**Still open.** A Linux contributor still cannot typecheck `iosMain`; they can only *tell* that they
cannot. Closing that needs the iOS compile in CI on every PR, not only on the iOS workflow's own
triggers.

### Entitlement enforcement — a bearer token is final; issuer key rotation has one key slot (2026-07-26)

**Files**: `storage-service-proxy/.../EntitlementStorageReader.kt` (`refreshFigures`),
`storage-service-proxy/.../EntitlementRecordCredentialProvider.kt`,
`sync-relay-server/.../routing/EntitlementEnforcement.kt`,
`sync-relay-server/.../auth/RelayEntitlementVerifier.kt`.

⚠⚠ **`refreshFigures` replaces the credential only on a non-null read**; the sole caller that may
clear it is the explicit-delete observer (`refreshFigures(clearRecordOnMiss = true)`). Mirroring every
read into the sink let a transient null owner (lock/unlock, mid-initialisation) wipe a good
credential and 402 every push. Safe because the record is self-validating (signed, owner-bound,
windowed) and the relay re-checks all three on every write. Pinned by
`EntitlementRecordCredentialWiringTest` (`transientlyUnreadableOwner_doesNotWipeAnAlreadyValidCredential`,
`explicitEntitlementDelete_clearsTheCredential`), `EntitlementProofRoundTripTest` (client encoder vs the
real `RelayEntitlementVerifier`) and `EntitlementEnforcementSystemTest` (harness wired as production:
nothing presented ⇒ refused; owner's record ⇒ accepted; a paired joiner's synced record ⇒ accepted).
The relay logs the specific cause on all five refusal paths while the client gets the content-free code.

⚠ **Decided: "a bearer token is final" stays.** `resolveEntitlement` does not fall through to
`X-Entitlement-Proof` when a token fails. Presenting a token you know is bad is a **client** bug, and
keeping it fatal is what makes it surface. The "stale token shadows a valid proof" scenario has never
been observed. The one case where a correct client holds an unusable token is issuer **key rotation**
(`UnknownKey`, A29), which is meant to be survived with an overlap window, not a fallthrough.

⚠ **Open, for whenever a rotation is scheduled**: `RelayEntitlementVerifier.fromConfig` populates
`publicKeysByKeyId` with a *single* entry, because `RelayConfig` carries one pubkey
(`entitlementIssuerPublicKeyB64` + `entitlementIssuerKeyId`). Add a previous key there — do not weaken
the gate. Client-side complement: don't present a known-expired token — refresh it through the record
renewal path (T-CB-08) or omit it.

### A tombstone deleted a row this device had just written — the "ghost file" data loss (2026-08-25)

**Files**: `sync-service-proxy/.../SyncProcessor.kt` (`processDeleteBlock`, `resolveConflictAndSave`),
`sync-service-proxy/.../TombstoneRegistry.kt` (`recordLocalWrite`, `localWriteHlc`),
`sync-service-common/.../LocalWriteMetadata.kt`. The permanent statement of the rule is
`docs/sync/design.md` §2.6 *"Ingest-time guard"*; guards are in `SyncProcessorTombstoneTest`
(`aTombstoneDoesNotDeleteALocallyRecreatedEntity`, `…LocalRecreateThatFollowedAnIngest`,
`…LocallyEditedEntity`, `aTombstoneNewerThanTheLocalWriteStillDeletes`) and
`LocalDeleteIngestRaceTest` (a local delete and its observer fanout, and ingest's gate-through-save,
each run in one `database.transaction`).

The tombstone guard compares against `max(ingested SyncMetadata, LocalWriteMetadata)`, because
`SyncMetadata` is written by ingest alone and a `DataId` is path-derived, so a recreated path is the
same entity to a tombstone. Constraints that outlive the fix:

- ⚠⚠ **`SyncMetadata`'s absence is load-bearing** — `resolveConflictAndSave` reads a missing row as
  "anything incoming is newer", which `SliceSnapshotProducer.applySlice` relies on. Do not stamp local
  writes into it, and do not sweep it on delete; that is why the local record is a separate sidecar.
- ⚠⚠ **Do not filter self-authored tombstones.** A device that wiped its store rehydrates from its own
  history; without its own tombstones it resurrects everything it ever deleted.
- ⚠ `localEventHlc` **ticks** rather than reading `current()`, which is `(0, 0)` until something ticks.
- ⚠ `LocalWriteMetadata` is **durable**: `SyncScheduler.pullSyncLogBefore` re-serves tombstones the
  device has already seen after a restart.
- ⚠⚠ **The `Syncable` gate assumes every tombstoned type is syncable.** All seven production
  `TombstoneEmitter` sites (`File`, `FileModification`, `Device`, `Workout`, `HealthImportRun`,
  `DataIdMigrator`, `DeleteRealmDataImplementation`) target a syncable type. A new emit site on a
  non-syncable type would silently un-protect it — give the type `Syncable` or drop the gate. Pinned as a
  pair (`aLocalWriteToASyncableEntity_stampsTheRecord` + `aLocalWriteToANonSyncableRow_stampsNothing`).

Cost: one point read + one sidecar write per local write of a syncable entity. If it shows up in a
bulk-import profile (Photos backup, Takeout): drop the read and rely on `tick()`'s monotonicity, or
fold the HLC into the `ResolvedDataId` row (a House Rule #9 reshape).

**Residual, unaudited**: `SyncProcessor.attachPropertyAndSave` (property-targeted blocks) has no
tombstone gate. A property block for a deleted entity is dropped only because `onDataDeleted` also
drops the `idHash → DataId` reverse-index entry it needs — an accident, not a check.

### `:control-desktop:createReleaseDistributable` is broken — ProGuard cannot read JDK-25 bytecode (2026-07-26)

**Symptom**: `./gradlew :control-desktop:createReleaseDistributable` fails in `proguardReleaseJars`:

```
Can't process class [app/tenshu/control/ComposableSingletons$MainKt.class]
  (Unsupported version number [69.0] (maximum 68.65535, Java null))
```

**Cause** — not a dependency, *our own* jar. `control-desktop` pins `jvmToolchain(FFM_JDK)` with
`FFM_JDK = 25`, deliberately: jpackage embeds that JDK as the app's runtime and the macOS camera
scanner calls `java.lang.foreign`, final only in 22+ (`desktop-qr-scanning.md` §13 P0). So the app's
classes are class-file version 69, and ProGuard 7.7.0 (via `proguard-core` 9.1.10) reads at most 68
(Java 24). Every release-path task inherits this — including `signControlAppForAppGroup`, which
`dependsOn("createReleaseDistributable")`.

**Not currently blocking** because the *development* install path is a different task:
`:control-desktop:packageControlApp` (`createDistributable` + the tray/QR/image-decode helper
embeds) works fine, and that is what produces an installable `TenshuControl.app` today. ⚠ Do not
substitute a bare `createDistributable` — it yields an app that launches with **no tray and no
helpers**, and (with no `tenshu.signingIdentity` set) an **adhoc** signature, which changes the code
identity the login-Keychain ACL is bound to. Reproduce the installed identity with
`codesign --force --deep --sign "<the Apple Development identity>"` if signing outside Gradle.

**Fix when release packaging is actually needed** (pick one): wait for a ProGuard release that reads
Java 25; drop ProGuard for this app (`buildTypes.release.proguard { isEnabled.set(false) }`) — it is
size optimisation, not correctness; or emit ≤24 bytecode while still *bundling* the 25 runtime
(compile target and jpackage runtime need not match — `:macos-helpers` already does exactly this,
compiling against 25's API while emitting JVM-17).

### The app graph's `single`s are cached in the Module, not the container (2026-08-23)

**Files**: `control-shared-proxy/.../di/ProxyModule.kt` (`val proxyModule`),
`control-shared-proxy/desktopMain/.../di/PlatformProxyModule.kt` (`val platformProxyModule`),
`drive-shared-ui/desktopMain/.../di/PlatformClientModule.kt`, `drive-shared-ui/.../di/DomainModule.kt`

Found while moving the Drive integration suite off `GlobalContext` (`AppIntegrationKoin`).
Koin caches a `single`'s instance inside the **`Module` object**, not inside the `Koin`
container. Four of the six modules `appIntegrationModules` loads are top-level
`val module { … }`, so two containers built from them are *not* two graphs: measured, `koinApplication` A and B handed out
the **same** `ClientRegistry`, `SchemaRegistry` and `TenshuClient`, while `FilesRepository`
(from `filesRepositoryModule()`, a *function*) differed. Controlled against `buildProxyModule()`
— fresh Module per call — which gives two distinct `SchemaRegistry` instances. So the sharing
tracks Module identity, not container identity.

⚠ **Sequential use is correct today** and nothing in the suite is wrong because of this:
`close()`/`stopKoin()` drops the cached instances, so each start gets a fresh graph. The bug is
latent, and it bites exactly whoever tries to make integration tests concurrent — two live
containers would silently share one proxy, and the *second* one's `stop()` would drop the shared
instance factory out from under the first, leaving its loops running and unreferenced. That is
the leak-into-the-next-test shape this whole section exists for.

⚠⚠ This is why the move off the global slot does **not** by itself make the suite
parallelisable within a JVM, and why `AppIntegrationKoinIsolationTest` deliberately has no
"two containers are separate worlds" case. Separate *forks* (separate JVMs) are unaffected.

**Fix shape**: give the other four the treatment `proxyModule` already has — a
`buildXModule(): Module` function with the `val` kept as a call to it — then have
`appIntegrationModules` call the builders. Four one-line production changes; the builders
already exist for two of the four, which suggests someone hit an adjacent version of this
before.

### `TestClient` Counterpart Not Yet Introduced

**Files**: `core-test-utils/src/commonMain/kotlin/org/tenshu/core/common/TestProxy.kt`,
`drive-shared/src/desktopTest/.../integration/utils/AppIntegrationTestUtils.kt`
(`InProcessTaskTransport`).

`TestProxy` (the test-side simulator for the proxy process — owns its own database,
`SchemaRegistry`, and `ProxyGateway`) is in place and is the standard fixture for operation
tests (see [`docs/testing.md`](testing.md) §3). The matching `TestClient` (test-side simulator
for an app process, owning a `TenshuClient` and talking to a `TestProxy` via a mocked
`TaskTransport`) does **not** yet exist as a first-class abstraction. App-integration tests
construct the equivalent inline via `startAppIntegrationKoin(...)` plus
`InProcessTaskTransport`.

**Proposed Fix**: extract the implicit "client side" of the integration-test setup into a
`TestClient` class in `:core-test-utils`, parallel to `TestProxy`. Benefits: makes the
client/proxy boundary in tests explicit, lets cross-process bugs (e.g. a serializer
round-trip mismatch) be reproduced in a unit-style test rather than only in an integration
test. Worth doing when the next set of multi-client tests lands (e.g. multi-device sync
scenarios beyond `MultiProxySyncIntegrationTest`).

### `FilesScreenTest` — Boilerplate Duplication

**File**: `drive-shared/src/desktopTest/.../FilesScreenTest.kt`

Every test method manually creates its own `ViewModelStore`, `ViewModelStoreOwner`, and
`CompositionLocalProvider` block. This setup is identical across all three tests and should be
extracted into a shared helper (e.g., `runFilesScreenTest { ... }`), so that a change to the
test harness only needs to happen in one place.

### `FilesScreenTest` — Missing and Weak Assertions

**File**: `drive-shared/src/desktopTest/.../FilesScreenTest.kt`

* `clickingFolderNavigates`: Performs a click on "Documents" but has no assertions. The
  `showFolder` callback is a no-op `{ }`, so there is no way to verify navigation was triggered.
  The test would pass even if the click did nothing. Fix by capturing the callback and asserting
  it was called with the correct path.

* `createFolderDialogWorks`: Submits a folder name but does not verify the dialog was dismissed
  afterwards or that the repository received the correct `createFolder` call. The mock repository
  always returns `Success`, so asserting the outcome would be straightforward.

### `FilesScreenTest` — Silent Mock `TenshuClient`

**File**: `drive-shared/src/desktopTest/.../FilesScreenTest.kt`

The mock `TenshuClient` uses `error("Not implemented")` for both `taskExecutor` and
`requestChainFactory`. This is intentional for UI tests (which should never hit the transport
layer), but it is undocumented. If a future UI change accidentally triggers the executor, the
test will crash with a confusing `IllegalStateException` rather than a clear failure message.
Consider replacing with an explicit `fail("UI test should not invoke the task executor")` or
adding a comment explaining the assumption.

### Stream Listener Only Observes Initial Array, Not Nested References

**File**: `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/ExecutionContext.kt`
(`setDatabaseListener`)

`setDatabaseListener` registers a single DB listener on the `ArrayDatabaseReference` passed at
stream-setup time. On each data change event it re-runs the `executionStack` via
`evaluateCallstack`, which may produce a *different* `ArrayDatabaseReference` (e.g. a nested list
field accessed on an entity resolved during re-evaluation). No listener is attached to that
nested reference, so mutations to nested arrays never trigger the stream.

**Symptom**: `LocalStreamingIntegrationTest.localStreaming_NestedSubfolderFlowEmitsUpdates` fails.
The test subscribes to `/A/B`'s files stream, then uploads a file into `/A/B` — the upload
correctly writes to the nested array's DB storage, but the stream's root listener (bound to
`/`'s files when the flow was first evaluated) doesn't fire.

**Proposed Fix**: After re-evaluation inside `setDatabaseListener`'s `onUpdate`, if the produced
result contains an `ArrayDatabaseReference` different from the stream's original, register an
additional listener on it and track it for cleanup in `removeDatabaseListener`. Alternatively,
redesign the listener mechanism so that the `executionStack` is walked statically at setup time
to discover *all* reachable `ArrayDatabaseReference`s, and listeners are installed on each.

### `:system-test-utils:test` — Residual Suite-Ordering Flake: `ContactHandshakeSystemTest` `COMPLETED` vs `REDEEMED`

**File**: `system-test-utils/src/test/kotlin/org/tenshu/systemtest/ContactHandshakeSystemTest.kt`.
Every other `:system-test-utils` victim is owned by § "The full gate is flaky under parallel load",
which has a mechanism for each; this is the one still undiagnosed.

`ContactHandshakeSystemTest.fullMutualHandshake_overLiveRelay_withRealCrypto` fails under full-gate
load — **not** a timeout but `ComparisonFailure: expected:<COMPLETED> but was:<REDEEMED>` at
`ContactHandshakeSystemTest.kt:113`, Alice's own invite row read back one state stale.
**Not diagnosed**, and the two obvious explanations are already dead: `COMPLETED` is
written *synchronously inside* `CompleteContactHandshakeImplementation` — the op the test
already awaited three assertions earlier — so it is not a missing `advanceUntil`; and
`ContactInvite`'s DataId derives from `inviteId` (`stringDataId(fieldRef = INVITE_ID)`),
so `updateStatus` overwrites its row rather than leaving a stale duplicate for
`firstOrNull` to pick. That leaves a stale *read* or a later write-back of `REDEEMED`.
⭐⭐ **Three of the four ways to get there are ruled out by structure** (read from the code, not
re-run — an idle-box A/B proves nothing, see below):
1. **Two `InviteResponsePickup.checkOnce()` passes cannot overlap.** The pickup rides
   `SyncScheduler`'s cycle hooks, which run *inside* `tickOnce`, and every production entry point
   to a tick holds `tickMutex` (the `while (isActive)` loop and `drainNow`). So the snapshot
   `inviteStore.all()` takes *before* the `pendingResponses()` relay round trip cannot be written
   back by a second pickup racing the completion op. ⚠ `tickOnce` itself does **not** take the
   mutex, so a caller that invokes it directly bypasses this — `TestSystem.advanceUntil` does not
   (it only polls a predicate; `:system-test-utils` never calls `tickOnce`/`drainNow`).
2. **A later pickup pass that snapshots the row while it is `REDEEMED` does not rewrite it.** The
   write is guarded by `row.status == PENDING || row.claimedFingerprint != claimedFingerprint`
   (`InviteResponsePickup.kt`), and the earlier flip stored that same fingerprint — so the guard is
   false on both clauses and no `updateStatus` runs. This is the shape the guard exists for.
3. **The failing read is not a client replica, so it is not IPC lag.**
   `TestClient.executionContext` is `clientRegistry.getExecutionContext(deviceId)` and the test
   wiring's own client stack resolves `getExecutionContext(platformPackageName)` — where
   `platformPackageName` *is* the `deviceId` (`SystemTestPlatformModule.kt:106`) — with
   `sessionIdentity` null on both sides, matching `ProxyGateway.call`'s default. Same session, same
   `ExecutionContext`, same proxy-side `Database` that `CompleteContactHandshake` writes through.
   ⚠ This narrows "stale read" rather than killing it: what it leaves is a **per-context
   materialisation cache** (House Rule 6 — `SchemaRegistry` owns per-context caching) serving a
   stale array to `getArrayData`, which would be load-dependent if its invalidation is observer-
   driven. That is the unexamined half, together with a genuine later write-back from a path other
   than the pickup.
⚠ It is not the stream-terminal fix nor the create-only `DbEntryMapper` change: measured over 46
runs it fails with either reverted.
⚠⚠ **Methodological note worth more than the finding**: the first 5 runs were 3-red and a
later 10-vs-10 A/B was **0-red in both arms** — the second batch measured nothing, because
it ran on a quiet box. Six CPU burners did **not** reproduce it either (0/8). What did was
running immediately after a full gate, i.e. whole-machine memory and daemon pressure.
**An A/B of a
load-dependent flake on an idle machine is not evidence of anything.**

## Materialised Entities Lose Their Concrete-Type Binding

**Files**:

* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/OnDemandMaterialiser.kt`
* `core-common/src/commonMain/kotlin/org/tenshu/core/common/proxy/SchemaRegistry.kt`
* `drive-shared/src/commonMain/kotlin/app/tenshu/drive/data/TenshuFilesRepository.kt`

`OnDemandMaterialiser.materialiseEntityPlaceholders(…)` constructs the
materialised entity via `dataFactory().createEntity<Composite>(…)` — *always*
parameterised as `Composite`, regardless of what the original entity's
schema-typeId resolved to (File, Folder, Notification, …). At runtime the
returned object is an `EntityDto<Composite>` that implements `Entity<*>` and
`Composite` but **does not** implement the concrete data interface
(`File`, `Folder`, …) the schema represents.

Practical consequence — every caller that wants to read a concrete-type
extension off a materialised entity has to do an unchecked cast at the
extraction site:

```kotlin
// TenshuFilesRepository.getRemoteFile:
val file = fileSystem.root.files
    .select { fields(File.Field.PATH); relations(File.Relation.DataProperty) }
    .search<File> { this.path equalTo path }
    .get()             // returns TResult<File?>
    .getOrNull()       // File? — but runtime instance is EntityDto<Composite>
@Suppress("UNCHECKED_CAST")
val entity = file as? Entity<out File>   // ← this cast
val bytes = entity?.data?.get()
```

The intuitive fix — `.search<Entity<File>> { … }` — does **not** work either:
`SchemaRegistry.requestObjectFactory(…)` has no factory registered for
`tenshu/core/entity`, so the chain throws
`IllegalStateException: No request object factory registered for type:
tenshu/core/entity` at chain-build time. The engine accepts concrete data
types (`File`, `Folder`, …) but not the generic `Entity` wrapper.

**Why this is a smell, not just a casting nuisance:** every call site that
needs a typed materialised entity has the same shape of cast, with the same
`@Suppress("UNCHECKED_CAST")`. The compiler can't help, and the runtime can't
tell you *which* concrete type the entity actually carries without inspecting
its `type.get()` schema id.

**Related — redundant `<T>` on `.search`.** The chain DSL's
`inline fun <reified T : Data> TList<*>.search(predicate: T.() -> TBoolean):
Maybe<T>` star-projects its receiver, so the upstream element type is
discarded — every caller spells out `.search<File> { … }` even when the
receiver is `TList<File>` and the explicit type param adds no information.
If search's receiver were `TList<E : Data>` with `<T : E = E>` defaulting to
the upstream element type (and a separate narrowing overload kept for the
genuine subtype-narrowing case, e.g. `root.files.search<Folder>`), the
redundant param disappears. The static-typing fix is independent of (and
complementary to) the runtime materialisation fix above: doing both lets a
caller write
`.search { this.path equalTo path }.get().fold(onSuccess = { file -> … })`
with no type param and no cast.

**Proposed approach** (when this becomes worth chasing):

1. Teach `OnDemandMaterialiser` (and `dataFactory().createEntity(…)`) to
   resolve the appropriate Kotlin-side wrapper from the schema-typeId at
   runtime — a registry keyed by typeId returning a constructor that takes
   `(fields, relations)` and produces an instance implementing the concrete
   interface (`File`, `Folder`, …). The registry already exists for
   request-object factories; widen the same mechanism.
2. Alternatively, register a `tenshu/core/entity` request-object factory
   that dispatches dynamically on the inner schema-typeId — this is the
   "dynamic dispatch" shape that lets `.search<Entity<T>>` work uniformly.

Either fix lets call sites drop the cast and pulls the dispatch into the
engine where it belongs.

**Priority**: medium. The cast is annoying, not unsound (the `as?` returns
null on a genuine mismatch), but every consumer of `Maybe.get()` on a
materialised entity grows the same `@Suppress` boilerplate. Worth bundling
with the next pass on `OnDemandMaterialiser` / `dataFactory`.

## Standard Tenshu JSON Representation (deferred)

**Surfaced**: 2026-06-02, building the macOS native bridge
([`desktop-macos-integration.md`](proposals/desktop-macos-integration.md) Phase 0).

The platform's canonical wire format is `BinarySerializer` framing (documented
in [`docs/protocol/desktop-ipc.md`](protocol/desktop-ipc.md) §2). It is compact
and self-describing, but it is *binary* and reimplementing the codec on a foreign
runtime (Swift, a shell script, a future third-party integrator) is a large,
fragile job. The macOS bridge sidesteps this with a **minimal, private JSON
envelope** (`NativeBridgeServer`, desktop-ipc.md §6.2): hand-rolled per-method
`Map`/`JsonElement` shapes, versioned only by `PROTOCOL_VERSION`. That is the
right call for one small surface, but it is the **second** place we've reached for
JSON ad hoc (the relay's REST surface is the first), and there will be more —
anywhere a non-KMP peer needs to read Tenshu data and the binary codec is
overkill or unavailable.

**The debt**: there is no *standard* JSON representation of Tenshu schema types
the way there is a standard binary one. Each JSON surface invents its own shapes,
so they drift, can't share validation, and don't get the schema DSL's guarantees
(field presence, trait membership, type ids).

**Proposed direction** (when a third JSON surface appears — the n=3 trigger):
derive a canonical JSON encoding from the schema DSL, symmetric with
`BinarySerializer` — a `JsonSerializer`/`JsonParser` pair driven by the same
`SchemaProvider`/`DataFactory` metamodel, emitting type-tagged JSON objects. The
native bridge's envelope stays as the thin transport frame; its `params`/`result`
bodies would then be *schema-typed* JSON rather than hand-built maps. This also
gives the `@tenshu/sdk` JS bridge and the relay a shared, validated JSON contract.

**Priority**: low until the third JSON consumer lands. The bridge's hand-rolled
maps are fine at n=1 (two methods); revisit before the surface grows past a
handful of methods or a second native consumer (the File Provider in Phase 2)
starts depending on richer payload shapes.

### `sync-service-proxy`'s 76 `println`s never reach a durable log

**Files**: `sync-service-proxy/src/commonMain/kotlin/org/tenshu/sync/proxy/` — 76 call sites,
25 of them in `SyncScheduler.kt`.

W9 (`drive-web-parity.md`) gave the web a durable diagnostic sink, and it works — but almost
nothing routes into it. The lines that actually diagnose a broken device live here as bare
`println`s: *"reporting this device offline"*, *"the relay answered but does not recognise this
device"*, *"push rejected (402 entitlement_required)"*. On 2026-08-28 those three were the entire
story of a browser that had been silently dead for hours, and they were only visible because a
console happened to be open.

Routing them is one dependency (`:diagnostics` has **no** production dependencies, so it costs
nothing transitively) plus 76 mechanical edits — and the mechanical part is not the problem.

⚠⚠ **Decide this first: does `Diagnostics` tee to the console, or do call sites do both?**
The facade today only *persists* — `fun i(...) = sink.append(...)`. So a straight
`println` → `Diagnostics.i` conversion **removes** the console line that Android and desktop
developers read now, and this file already records that logcat is unavailable in some setups,
which makes `println` the fallback of last resort. Three options, none obviously right:
teeing inside the facade (simplest, but every platform's console gets every line), teeing in the
sink (per-platform choice, more wiring), or leaving both calls at the call site (explicit, and
76 places to forget one).

⚠ Not web-only. Native gets durable logs out of this wherever a sink is installed, so the value
is cross-platform even though W9 surfaced it.

⭐ One data point (2026-09-14): `SqliteObjectStore` took the third option for its four lines —
`println` **and** a `FileDiagnosticSink` of its own at `objects.db.log`, in one `warn(code, message)`
helper — because no proxy process installs the global façade and the store's evidence belongs
beside the store. That does not settle the question above for the 76; it only shows the "both at
the call site" shape costs one helper when the call sites are few.

### `VideoPreviewViewModelTest` left its load running across its own `resetMain()` (2026-09-07)

`VideoPreviewViewModel`'s `init` load hops to `withContext(fileWriteContext) { writePreviewTempFile(...) }`
and resumes onto `viewModelScope`'s `Main`. On a real dispatcher that resumption outlives the test and
can collide with the next test's `setMain` (`Dispatchers.Main is used concurrently with setting it`).
The hop's context is a constructor parameter (`fileWriteContext`, default `Dispatchers.Default`) and
the tests pass `StandardTestDispatcher(testScheduler)`, so `advanceUntilIdle()` completes it in-body.

⚠ **Three sibling view models carry the identical hazard and are untouched**: `PdfPreviewViewModel`
(two hops), `OpenExternallyViewModel`, `ImagePreviewViewModel`. They have **no tests** in
`drive-shared-ui/src/desktopTest/.../preview/` today, so nothing leaks; adding the parameter with
no test to use it would be dead configurability. Add it in the same commit as their first test,
not before.

⚠⚠ **Before bridging a test to real threads, check whether the component actually forces you to.**
`TenshuPagingPresenter` and `Pager.flow` are fully injectable, so `TenshuPagingPresenterTest` runs on
virtual time and its races are deleted rather than waited out. Ktor's `MockEngine` is not: it delivers
the response body on its own dispatchers, so `ModelDownloaderTest` keeps the real pool for a flow the
client fills and uses the test scheduler only for an ordering claim between test-owned coroutines.

### `PreviewCacheTest` reports someone else's leak — and the obvious leaker is ruled out (2026-09-07)

> ⭐ **The structural answer to this whole family is written up as
> [`proposals/testing-leaked-scope-guard.md`](proposals/testing-leaked-scope-guard.md)** (v0.1,
> design only): a shared fixture that clears the view models, plus a teardown census that fails the
> test which *did* the leaking rather than the one that reports it. Sized there — **134 test files
> construct a view model, 10 clear it, 94 also `setMain`** — and its crux is spiked: a
> `DebugProbes` census at JVM exit returns **149 attributable leak sites in `:drive-shared-ui`
> alone**, named down to the view-model constructor (`SelectionViewModel.<init>` ×41,
> `FilesScreenViewModel.<init>` ×30, …), at no measurable cost — which is why the doc proposes a
> **ratchet against a baseline**, not a gate.

`:photos-shared-ui :: anAbsentThumbnailIsNotRemembered_becauseItMayArriveLater` went red on a full
gate with `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest`. That is P0's shape: the test is a
**reporter**, and the defect is a coroutine some earlier class leaked.

⭐ **What narrows it.** `PreviewCacheTest` is the **only** class in `:photos-shared-ui`'s desktop
suite that uses kotlinx `runTest` — so it is both the sole reporter and the sole point where the
collector's backlog is replayed. Every other class is a compose test; exactly three of them
(`FeedScrubberTest`, `PhotosFeedScreenTest`, `PhotosNavRoundTripTest`) `Dispatchers.setMain(...)` in
`@BeforeTest`, `resetMain()` in `@AfterTest`, and build a real `PhotosFeedViewModel` inside a
`remember { }` — so its `viewModelScope` is **never cancelled**, and that scope carries
`cachedIn(viewModelScope)`, a `viewModelScope.launch`, and a
`stateIn(viewModelScope, WhileSubscribed(5_000), …)`.

⚠⚠ **The mechanism that fits all of that was tested on 2026-09-07 and does NOT hold.** The
hypothesis — a leaked `WhileSubscribed(5_000)` timer firing after `resetMain()` has torn its Main
dispatcher down, throwing uncaught in a `StandaloneCoroutine` — predicts a report whenever the
reporter runs at least 5 s after a leaker. Forced by spinning **8 s in `PreviewCacheTest`'s own
`@BeforeTest`**, which put it last in the suite with all three candidate leakers finished before
it: **green**, nothing collected.

⚠ **Two earlier forcing attempts measured nothing and the reason is worth keeping**: spinning in a
*leaker's* teardown changed its completion time and therefore its position, putting the leaker
*after* the reporter. Gradle's class order here is **not** alphabetical and it is **not** stable
under a forcing that changes how long a class takes — check the order (result-XML mtimes) before
reading anything into a green.

Also not reproduced unforced: **0 red in 10** whole-module runs under six CPU burners.

⭐⭐ **2026-09-07 — the leak population this flake needed is gone, though the flake itself is still
not *proven* fixed.** The three compose classes' view models are now cancelled at teardown, and the
module's exit census went from **199 live coroutines to 1** — `PhotosFeedViewModel.<init>` accounted for 110 of them and now accounts for
none. ⚠ This is not a red arm turned green: the flake was never reproduced (0/10 under burners), so
what can honestly be claimed is that its enabler is removed, not that it is closed. Leave this row
open until a gate run says otherwise.
⚠ The `TestViewModelOwner` that produced those numbers cancelled without joining and is gone;
`OwnedViewModels` (`:viewmodel-test-utils`, cancel **and** join) replaced it, so the census figures
above measure the population, not the current helper.

Next step if it recurs: instrument rather than hypothesise — capture the failing run's stderr (the
route that root-caused P0b) instead of deriving another mechanism.

### `SharesPage.test.tsx` — suite-ordering flake (~2 in 8 gate runs)

> ⚠ **Hypothesis recorded 2026-09-02, not tested** (out of the P0–P2 pass's scope): RTL `cleanup`
> *is* registered (`src/test/setup.ts`) and the mocks are `mockReset()` in `beforeEach`, so the
> ordering coupling is not a missing teardown. What fits both observed facts — the *revoke* test's
> `waitFor` timing out under gate load, and the *cancel* test then reporting `expected "vi.fn()" to
> not be called at all, but actually been called 1 times` — is the revoke test's click handler
> still in flight when its 1 s default `waitFor` gives up, so `revokeMock` is invoked *after* the
> next test's `beforeEach` reset it. The pair to run: a forced slow revoke (an awaited `setTimeout`
> in the mocked call) should fail both tests together deterministically; awaiting the handler's
> settled state before the test returns should pass under the same forcing.
>
> ⭐⭐ **RUN 2026-09-04 — and the hypothesis is FALSIFIED. Do not spend a second pass on it.**
> The prescribed forcing (`revokeMock.mockImplementation(() => new Promise(r => setTimeout(() =>
> r(true), 2500)))`, everything else untouched) gives **1 failed / 4 passed, 3 runs out of 3**: the
> *revoke* test fails at `await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2))` — and the
> *cancel* test **passes**, every time. The two do not fail together, so a slow revoke is not what
> the gate saw, and the pair's second arm (awaiting the handler's settled state) is moot: there is
> nothing for it to fix.
>
> ⭐ **Why it cannot be, from the code rather than the run.** `confirmRevoke` evaluates
> `Shares.revoke(target.shareId)` *before* its first `await` (`SharesPage.tsx`), so
> `await user.click('Yes, revoke')` having returned already means `revokeMock` was invoked inside
> the revoke test's own window. Delaying the returned promise can only defer the `finally`'s
> `load()` — i.e. it leaks a **`listMock`** call into the next test, never a `revokeMock` one.
> No forcing of the revoke path can produce the cancel test's symptom; whatever calls `revokeMock`
> during the cancel test is invoked from the cancel test's *own* click sequence or from outside
> this file, and that is where the next pass should look.
>
> ⚠ **Unforced re-measurement is inconclusive here and the reason is environmental, not a
> judgement call**: 0 SharesPage failures in **12** consecutive `pnpm -F apex test` runs
> (2026-09-04) — but that is not the gate and could not be, because `scripts/run-web-gate.sh`
> cannot run in the Claude-web container at all. `kotlinNpmInstall` fails there
> (`codeload.github.com` → **403 Forbidden** from the agent proxy, fetching Kotlin's karma fork),
> so `:tenshu-sdk-js` never produces `build/dist/js/productionExecutable/tenshu-sdk-js.js`, the
> `sdk` lane cannot run, and the **9 apex test files that import the SDK bundle fail to load**
> (`ControlShell`, `AccountRecoverySetupPage`, `RecoveryCaptureScreens`, `mockOnboarding`,
> `proxyGateway`, `LockedFlow`, `OnboardingFlow`, `RecoveryFlow`, `NewUserScreens`). 43 of 52
> files ran, so even the load profile is not the gate's. Measure this row on a machine that can
> build the bundle, or not at all.

**File**: `web/apex/src/control/shares/SharesPage.test.tsx` — the two tests
"revokes a share after the confirm dialog and reloads the list" and "can cancel the confirm
dialog without revoking".

Both fail together with `expected "vi.fn()" to not be called at all, but actually been called 1
times` — a mock observing a call from a *sibling* test, i.e. state leaking between tests rather
than a defect in the page. Observed twice in eight `scripts/run-web-gate.sh` runs on 2026-08-28,
on branches that touch nothing related to shares.

⚠ It does not reproduce under `pnpm -F apex test` alone: both times it appeared under the gate
and both times an isolated re-run was green. An isolated re-run therefore does not discharge it —
measure under the gate or not at all (`docs/tech-debt.md` has the same caution for the JVM gate).

---

### Device-local derived stores: nothing tests the wipe hook, and nothing calls `remove` (2026-09-23)

Two gaps; both apply equally to the shipped `EmbeddingStore` and to `DefaultFaceTemplateStore`.

**1. The realm-reset hook's *contents* are untested on every platform.** `onLocalStoreWiped` is a
lambda inside each `PlatformProxyModule` (`androidMain`/`desktopMain`/`iosMain`), so what it clears
is platform DI code with no unit coverage. `DiscardDropsTheStoreTest` covers the *mechanism* — that
the hook runs, after the rows, and cannot fail the wipe — and nothing covers *what it does*.

⚠⚠ **That is precisely how IN-6's face store got missed** — wired without being added to the hook,
so a reset or re-pair would have left the previous realm's **biometric** templates on disk. That
instance is fixed; the *class* of defect is not. A new device-local derived store is invisible to every
gate we have.

⭐ The shape of a fix: a registry of derived stores that the hook iterates, so adding one to the DI
graph adds it to the wipe by construction rather than by review. Until then, **adding a device-local
derived store means editing three platform hooks by hand.**

**2. Neither derived store is invalidated when an indexed file changes.** No production code calls
`EmbeddingStore.remove` or `FaceTemplateStore.remove` — verified by grep across the tree. A file
replaced at the same path stays `visited` and keeps the old row, so a replaced photograph keeps the
previous one's vector, and (once faces index) the previous person's template — which still affects
clustering, centroids, `minFaces` and group ordering even though the Photos join later drops a
missing path.

⚠ Latent rather than live today: no device has a face backend and `FACE_CLUSTERING` is not in
`OfferedConsents`, so nothing indexes faces yet. ⚠⚠ It stops being latent the moment either
changes, and the fix belongs with the index pass — reconcile the store against the media source per
pass, or wire file replace/move/delete invalidation — not with the stores themselves.

---

### Kotlin/Native answers `x is DataObject` wrongly in one shape (Kotlin 2.4.10, 2026-10-13) — first face FIXED in 2.4.20; the crash face unverified

Kotlin 2.4.20 answers the original shape right; `TheOriginalTypeTestShapeTest` (commonTest) asserts
the right answer on both targets. ⚠ **Only the first face is proven fixed.** The crash face below is
pinned by a test that asserts the right behaviour, so it cannot say whether 2.4.20 fixed it. Keep its rule.

`ios-on-device-inference.md` §14.18.10. In a function that assigns a `val` from a `when` and
then, inside an inline lambda whose inliner wraps it in `synchronized { try { … } finally { … } }`,
assigns `flag = value is Sealed.TheDataObject`, Kotlin/Native answers **true for every
value**. The JVM is right. It made every read of the model download journal look failed, on
the simulator only.

- **Workaround rule:** decide in the `when` branch that makes the value, or test the value
  before the lambda. Seven neighbouring shapes are right on both targets (`==`, the test
  outside the lambda, a bare `synchronized`, no `try`/`finally`, the value made by another
  function, …), so this is not "never use `is` on an object".
- **Pinned:** `inference-service-proxy` `IsOnADataObjectAfterAWhenTest` (the neighbours, both
  targets) and `TheOriginalTypeTestShapeTest` (the original shape, both targets, right answer).
- **Not done:** the cause in the compiler; a report upstream; a search of the whole repo for
  the shape (this track's main sources were grepped for a one-line `{ x = y is T }` lambda:
  none).
- ⚠⚠ **A third face, 2026-10-13, and this one is a crash.** `ModelActionResult` is a sealed
  interface with `data object Accepted` and `data class Refused(why)`. Inside
  `viewModelScope.launch`: `val answer = runCatchingCancellable { suspendCall() }
  .getOrDefault(Refused(Unnamed))` (an inline try/catch returning `Result`), then the first
  in-place type test **against the default's own class**: `if (answer is Refused) answer.why`,
  or `(answer as? Refused)?.why` inside an inline `update {}`. With `Accepted` the test passed
  and `.why` was read off the object: a segmentation fault on Kotlin/Native, right on the JVM.
  The same family: a value merged from inlined try code, typed by one operand's concrete class.
  - **Rule:** never type-test, in place, a value produced by an inline `try` / `getOrDefault` /
    `getOrElse` whose default is a concrete sibling of the other possible values. Hand it to
    another function first (`ModelsViewModel.whyRefused`).
  - Pinned by `tenshu-sdk-ios` `ModelsViewModelOnNativeTest`, which asserts the **right**
    behaviour (it cannot assert a crash), so it will not say when the compiler is fixed.
  - ⚠⚠ **No view model of `:control-shared-logic` has ever run on Kotlin/Native apart from that
    case**: the module has only `desktopTest`, so this class of fault is invisible there.
  - Nearest relatives, read and not run for this (no exact hit):
    `photos-shared-logic/.../duplicates/DuplicatesViewModel.kt:362-386` (runs on shipped Photos
    iOS; its object branch is tested before `is Failed`; has commonTest coverage),
    `fit-shared-data/.../AutoDetectBootstrap.kt:109-114`,
    `calendar-service-proxy/.../SubscriptionRefresher.kt:62-89`.

---

### The Core ML model takes ~2.58 GB on an iPhone: one size nobody explained, one experiment owed (2026-10-13) — OPEN

Accepted for now by the maintainer; recorded to be measured, not built
(`proposals/ios-on-device-inference.md` §3.4).

- **Unexplained.** Archives plus compiled towers hold ~2.58 GB (the towers and their first-open
  space ~1.84 GB) against the plan's computed 1.41 GB; Android's download is ~383 MB. Owed: why
  (fp32 constants kept? weights held twice? a specialisation per compute unit?). None checked.
- **Owed measurement.** Compress the weights (8-bit linear quantisation or palettisation via
  `coremltools`) and read: download size, `.mlmodelc` size, retrieval agreement against fp32
  on the existing gate (`scripts/inference/retrieval-gate.py`; fp16 today: 35 of 36; the uint8 ONNX pair: 23 of 36), label agreement
  (`scripts/inference/label-eval.py`), latency on the phone (5.9 ms a vision predict in the
  spike; 33 ms a photo end to end). An option only if quality and speed hold. A new export is
  new digests, a catalog entry, a preflight probe record and a re-published model.
- **D14 stays open:** keeping the archive costs 743 MB steady state; dropping it makes every
  recompile, including after an iOS update, a 743 MB download.

### PIO-7: a load the process does not survive is counted — the rule's calls are the maintainer's (2026-10-14) — OPEN

`ios-on-device-inference.md` §14.5 item 0, D12. Built and tested on a simulated kill (the disk
ignores every write from the moment of the load); **no real jetsam ran**. Settled 2026-10-14:
bound 2; a load the scene left under is transient (`SceneLeft`); the compile is bracketed; a fail
of interrupted loads alone has one *Try again*. Open: a phone must show that the scene's write
lands before iOS suspends Photos. ⭐ The backend's own opens after `Ready` (`warmText`, the index
pass) are bracketed since 5e-1 (§14.19.3), in a file per tower. ⚠ Open, the maintainer's: *Try
again* has no cap on presses (§14.18.16 item 10).

### Control's Models screen on Android and desktop: eight things read and not fixed (2026-10-13) — OPEN

Found by reading, for `ios-on-device-inference.md` §14.18.11's table. The rest of that table
was fixed in 5d-2a (§14.18.13).

1. **"Installed" is shown with nothing confirming the model loads.** There is no readiness
   off iOS: `OnnxInferenceBackend.supports` is *located and not rejected*, and a session that
   will not open is found at the first image. The row says installed throughout.
2. **A consent that cannot be read shows as off.** `PreferenceService.readOnce` answers the
   preference's default for a failed read. A consent that is on and unreadable is shown off,
   and a press writes. The cure is a read that can say it failed, in
   `:preference-service-client`. ⚠ Narrower since the hardening before PIO-7 5e: the window
   *before the first read* is closed (`ModelsUiState.consentsRead`: the setters refuse and the
   switches are disabled until a read lands); a read that lands with the default is still
   believed.
3. **One download at a time, because the progress stream cannot tell two apart.** The client
   keys a stream by its call site, before the model id is on the chain (*Two identical
   observers in one session share a stream id*), so a second subscription is handed the first
   one's stream. The ledger refuses a second download and uses a tick only for its own model.
   Lifting it needs the stream's identity to include the model.
   ⚠ **A ledger call can end while the proxy's download goes on**: a call cancelled by somebody
   else ends as *didn't finish* and frees the slot, while the downloader's job runs on its own
   scope. The model may still land, and a second download or a removal is accepted meanwhile.
   The cure is to ask `isDownloading` before releasing the claim.
   ⚠ **A download that never ends holds every Download button for the process** (no cancel
   here). Bounded in practice by the HTTP client's connect and socket timeouts; a server that
   trickles, or a blocked store thread, is not. The cure is a stall bound or the cancel.
   ⚠ **A removal that never returns holds its row at *Removing…* for the process**
   (`ModelsLedger.delete`, and `OnDeviceModelsRepository.delete` on iOS): the claim is not
   bounded, on purpose, because the operation is not stopped by its caller giving up and a
   released row would take a download beside a removal still running. The caller is told *still
   removing* after 60 s, and that is all. A blocked store thread is the way to get there. The
   cure is the same as above: an operation that can be stopped, or one that reports it is alive.
4. **A download cannot be stopped there.** The in-process downloader has no cancel and there
   is no operation for one, so `ControlModelsCapabilities.cancel` is false and the screen draws
   no stop. A model being updated can therefore not be removed until the update has ended.
5. **A missing catalog or an unknown model, when no screen was waiting, is said under the row
   as *didn't finish*.** The row has no sentence of their own for them.
6. **`SyncRepository.devices` still reads a failed roster read as an empty list**
   (`TenshuSyncRepository.kt`, `?: emptyList()`). The Models screen no longer reads the roster
   through it (`ClientModelsDevices`); its other callers do.
7. **A failed re-read of what is installed blanks the list**, where a failed catalog keeps it
   as stale. `ModelsAvailability` has no *installed, as last read*.
8. **How a download ended is the process's memory.** A relaunch shows the row with nothing
   under it. Nothing journals an in-process download, so nothing could say more.

---

### The iOS model download and prepare: sixteen things found and not fixed (2026-10-11) — OPEN

`ios-on-device-inference.md` §14.11–§14.16 (PIO-7 PR-5, slice 5c-1). Behind Photos' build flag,
which is off, except where a line says otherwise.

1. ⚠⚠ **The model CDN answers a `Range` request to the text archive with `200` and the whole
   body, sometimes** (§14.12). Seen twice in a row from `curl` over HTTP/2 (a 1 000-byte range,
   and one past the end, each answered with 555 540 480 bytes) and not minutes later from a JDK
   client over HTTP/1.1. The archive is over the edge's cache limit and is always served from
   the bucket (`cf-cache-status: BYPASS`). **Cause not known.** Both downloaders survive it: the
   in-process one restarts the artifact from zero, the iOS one never sends a range of its own
   and checks the delivered file whole. The cost is the text tower again, 555 MB, each time.
   ⚠ What a background `URLSession` does when its *own* resume is answered `200` is not
   established. **Not behind the flag:** it is the published bucket's behaviour, and the
   `onnx` entries are simply small enough to be cached.
2. **A full disk reaches the wire as `TRANSPORT_FAILED`.** The pre-download check refuses with
   a typed exception carrying the bytes needed and free; `DownloadModelImplementation`'s
   catch-all maps it to the retryable bucket with its sentence. So do an unreadable download
   journal, a missing transport and unknown free space. `ModelUnavailable.Reasons` has no
   entry for any of them; the options are in §14.14 item 1. Same family as item 2 of the entry
   below.
3. **Staging left by a FAILED download has no reclaimer but another download.** A transfer
   that fails, or delivers the wrong bytes, leaves the artifacts that did arrive staged in
   `inference/models/` (up to ~743 MB on iOS, ~383 MB on Android), on purpose: the retry
   fetches only the rest. If no retry comes, a later download of the same model replaces them
   role by role, and nothing else does: `DefaultModelStore` never lists its directory (item 4
   of the entry below). ⚠ Narrower than this entry first said. On iOS a **cancelled**
   download and a model **deleted** under its update drop their staging with the record, and
   a newer entry drops the staging of a role it no longer has (§14.11, *What becomes of a
   download's bytes*); what is left is the failure with no retry, and a journal of another
   build's shape, whose roles cannot be read. Off iOS the in-process downloader has no cancel
   and keeps its staging on every failure.
4. **No resume data.** On iOS a transfer the system reports as failed starts its artifact
   again from zero; only the system's own retries inside a transfer resume. Using
   `downloadTask(withResumeData:)` means journalling the data, and makes item 1's `200` the
   app's to handle.
5. **A catalog entry that moves without its bytes: nobody asks, and off iOS it costs a full
   download.** When a signed catalog changes `modelVersion`, `labelSetVersion` or `licence`
   and leaves the artifacts alone, the installed record is stale until `download` is called
   again. ① Off iOS no screen offers that call: `ModelInventory.stateOf` reads an installed
   older version as *installed*. (Photos' screen offers *Update* through `ModelSetupJoin`.) ② When it is called, the iOS downloader fetches
   nothing and rewrites the record (§14.11); the shared `DefaultModelDownloader` (Android,
   desktop) fetches all ~383 MB again before the store adopts the files it already had and
   writes the same record [C, not run]. Right in the end, wasteful on the way.
6. **Off iOS, a model deleted under its own running download comes back.** `DeleteModel`
   removes the record; an in-process `DefaultModelDownloader` job for the same model (an
   update, or a re-download) is not cancelled and commits when it ends [C, not run]. On iOS
   the removal ends the download first (`withoutDownloadOf`, §14.11's matrix); the shared
   downloader keeps the default, which does nothing. It cannot outlive the process, and the
   Models screen shows the download as busy while it runs, which is what has hidden it.
7. **No operation cancels a model download.** `BackgroundModelDownloader.cancel` exists and is
   reachable only in-process; `DeleteModel` on a model that is downloading and not installed
   answers `NOT_INSTALLED` and the download goes on. 5d's screen needs a cancel for it, which
   is an operation (a schema change) or an in-process call from Photos.
8. **`HttpModelCatalogSource.load` swallows a cancellation.** Its fetch is inside
   `runCatching`, so a `CancellationException` becomes *no catalog*. The iOS downloader no
   longer cancels that fetch for any reason (a caller that stops waiting stops nothing,
   §14.11); the in-process downloader on Android and desktop does, and a cancelled download
   there can report *catalog unavailable* in place of its cancellation [C, not run]. **Not
   behind the flag.** The fix is one line (rethrow it) and a case that cancels a fetch.
9. **A background transfer that speaks for nothing runs to its end.** The ledger gives a ref
   one holder. A second task for the same ref (an earlier process's, seen beside a holder)
   is known, speaks for nothing, and its file is discarded when it arrives; nothing cancels
   it, and the sweep cannot: it sees refs, and the ref is named and held. Up to 555 MB
   fetched for nobody. Reaching it needs two processes to have started the same ref, which
   the ledger refuses within one process and a relaunch avoids by listing first.
10. **A model transfer that fails while Photos is in the background is not reported as that
   failure.** Nothing is settled in the background (§14.11), so the failure is found at the
   next foreground as a transfer that is not there, and started again. A download that keeps
   failing in the background therefore never says so; it restarts each time Photos is opened.
11. **A build that stops offering the model keeps what the enabled build stored.** Its
   transfers are cancelled and its delivered files deleted (§14.11); the download journal, up
   to ~743 MB of staging and an installed model stay under `inference/`, read by nothing, until
   a build offers the model again. Reclaiming them needs the owned stores, which a
   not-admitted graph does not bind; a sweep by path would also take what makes re-enabling
   cheap. Reached by turning the build flag off after it shipped on, or by a static gate that
   a later build tightens.
12. **The prepare step's budget is one ratio for the whole entry** (5c-2, §14.17.5). The
   download's free-space check scales the measured prepare bytes by size, and the towers do
   not prepare in proportion to it (3.0 × and 2.3 × their archives): an update of the vision
   tower alone is asked for ~91 MB too little. Inside a 256 MiB margin that is itself a
   placeholder. The fix is to hand the budget the roles, with per-tower figures from a phone.
13. **A probe that fails does not say how** (5c-2, §14.17.4). `PreflightFinding.Failed` covers
   *would not load* and *answered wrongly*; the prepare step recompiles once for either, and
   a recompile cannot cure the second.
14. **Nothing joins the prepare step to a tower Core ML really compiles.** The preparer runs
   over a fake cache on the JVM and the simulator, the real cache runs alone on the simulator,
   and one graph-level case ends at the real cache refusing a fixture archive.
15. **A failed delete of an installed archive is surfaced nowhere.** A removal and an update
   declare the old archives and leave their deletion to the next process's sweep
   (`DefaultModelStore.sweep`), which calls `deleteFile` for each, looks at no result, and
   then drops the list. An archive that could not be deleted (up to 743 MB) is named by
   nothing afterwards. **Not behind the flag**: it is the shared store. The compiled towers
   one layer up answer for their deletes since 5c-2 (§14.17).
16. **A second Core ML model would be downloaded, budgeted with SigLIP's figures, and never
   prepared.** The iOS graph offers every `coreml` catalog entry; the prepare step, readiness,
   the preflight and the verdict file are about the pinned SigLIP pair alone (§14.17's
   table). A second entry installs and then sits unprepared, and its free-space check uses
   SigLIP's measured prepare ratio. What is scoped since 5c-2: the removal and commit hooks
   name their model, readiness's writer stands down only for the pinned one, and the sweep
   keeps whatever any install names. One model exists in the catalog today.

---

### The model store's read contract: four things it leaves open (2026-10-11) — OPEN

`DefaultModelStore` no longer answers, writes or deletes from an index it could not read
(`ios-on-device-inference.md` §13.7). Found on the way and **not** fixed:

1. **No reset for an index that is JSON and not this build's record.** The store refuses every
   read and write on it for the life of every process, until a build that reads the record. A
   deliberate reset needs an operation, which is a schema change, or a Settings action; neither
   exists. ⚠ Only bytes that are not JSON at all are written over by the next commit.
2. **The Models screen says it in one sentence, with no reason.** A failed `InstalledModels`,
   bytes that are no index among them, is *Couldn't read what's installed on this device*,
   with *Try again* and no row (5d-2a). It cannot say which of the two it was:
   `ModelUnavailable.Reasons` has no entry for a store that cannot be read, so both arrive as
   `TRANSPORT_FAILED`. Adding one is a wire-vocabulary change plus the screen's sentence.
3. ⚠⚠ **Desktop's `local-store.properties` is rewritten in place**, not written beside and
   renamed (`DesktopSecureStorageDelegate.persist`) [C]. A death part-way leaves a shorter
   file. If the index key is lost and the garbage key survives, the next start sees *no index*
   and a list of declared paths, and the sweep deletes those models' bytes: an absent key
   cannot be told from a first launch. The same file holds `DEVICE_ID`. On Android a
   preferences file the framework cannot parse is loaded as an empty map for every key [I:
   from memory of AOSP, not run]; there both keys go together, so nothing is swept, and every
   installed model's bytes are orphaned.
4. **Nothing reclaims a `.model` file that no record and no declaration names.** The store
   never lists its directory. After case 3, or after a commit over bytes that were not JSON,
   models installed more than one process ago stay on disk, several hundred MB each, with no
   route to remove them but clearing the app's data.

---

### `SEMANTIC_RELEVANCE_FLOOR` is a carried-over cosine, never measured on the pinned checkpoint (2026-10-11)

`photosLike` drops a photograph below cosine **0.09149529**, written
`PhotosLikeImplementation.SEMANTIC_RELEVANCE_FLOOR = 0.061245844`. Nobody measured that number. It
is where `p ≥ 0.10` fell under the `-224` checkpoint's score constants, which
`VisionModel.PinnedSigLip` carried by mistake until 2026-10-11; when the constants were corrected
to the pinned checkpoint's (110.995995, −12.885268) the floor was restated so that no search result
changed. `0.10` itself was the middle of a band read on the `-224` checkpoint.

**Owed:** a floor measured on `-256-multilingual`, on labelled queries. A true `p ≥ 0.10` there is
cosine ≥ 0.0963, stricter than today; `models.md` §3.9.4 has 17 of 36 queries clearing nothing at
the current cut, several with a correct top-1 at cosine 0.06–0.09, so the direction is not obvious.
⚠ Not a stored-shape change: the probability is computed per query and never written.

⚠ The constants cannot be checked in a gate (no weights). `PinnedSigLipCalibrationPinTest` holds
them beside their provenance; `label-eval.py evaluate --source` re-reads the bytes. Detail:
`models.md` §3.10.8, `inference-service.md` §35.1.

---

### `MediaLabel` is keyed by file path, and a move silently rekeys the file but not the label (2026-09-24)

`MediaLabel.labelKey` is `"$file/$capability/$label"` (since IN-3a), `MoveFileImplementation` does not touch the
inference service (grep: no `MediaLabel` reference anywhere in `:file-service-proxy`), and the
Photos join matches labels to files by exact path.

So an ordinary move or rename **detaches every label on that photograph**. Two consequences, and
the second is the worse one:

1. The photograph drops out of whatever collection the label put it in — a Things card, and now a
   person's card.
2. ⚠⚠ If another photograph later occupies the old path, it **inherits** those labels. For a
   model's tag that is a wrong answer; for **a person's name** (IN-6 F3b) it is someone else's
   name on a stranger's photograph, in synced data, with no model involved to blame.

⭐ IN-6 raises the stakes without creating the defect. A tag is re-derivable — the next index pass
corrects it — while a name is user-authored and nothing regenerates it, so the damage is permanent
until the user notices and retypes it.

⚠ Latent today: no device indexes faces (`FACE_CLUSTERING` is absent from `OfferedConsents`) and
the tagger's own labels are the only ones that exist. It stops being latent with F4.

**The fix is platform-level, not a patch in Photos.** `file-content-addressing.md` §3.3.4 already
describes the generic `DataId`→ref-migration primitive this needs — a move rewrites the references
that point at the old id. Doing it inside the inference service instead (listen for moves, rewrite
`labelKey`) would leave every other path-keyed derived row with the same hole.

---

### `allLabels()` reads every `MediaLabel` in one Binder reply, and a failed read looks like an empty library (2026-09-24)

`TenshuPhotosRepository.allLabels()` consumes its projection through one `.getAll()`, and strings and entity rows do not move through the
SharedMemory byte tokenizer — so the reply is a single Binder transaction with a ~1 MB ceiling.

Two things make that worse than a slow read:

1. **The failure is silent and inverted.** `.getOrNull()` turns an over-sized reply into `null`, which
   becomes an **empty** label list. Every derived section that reads labels — Things, and now People —
   then renders as though the user has none.
2. ⚠⚠ **It fires right after a successful save.** `nameGroup` re-reads the library on purpose, so the
   first thing a user sees after naming a heavily photographed person can be that person, and every
   other named person, **disappearing** — with the save having genuinely succeeded.

⭐ IN-6 raises the reachable row count rather than creating the limit: naming writes one row per
photograph per person, so a single well-photographed person can add tens of thousands where the
tagger adds a handful per file. The write path is bounded (`NAME_CALL_BYTE_BUDGET`, chunked by
estimated encoded size); **the read path is not**.

**The fix is a paged or bounded label read, and it is not a patch in Photos.** Capping with `.take(n)`
the way `searchByLabel` does is wrong here: a cap silently drops labels, and dropped labels are
people vanishing rather than a short list. What is needed is a cursor over `MediaLabel` — the same
shape `:core-paging` gives the file feed — or a capability-filtered read so each consumer pays only
for the rows it uses.

⚠ Latent until F4: no device indexes faces (`FACE_CLUSTERING` is absent from `OfferedConsents`), so
today the only labels are the tagger's and the volume stays small.

### Pairing: an epoch rotation between two approves fails the second

**Size:** S. **Found:** 2026-10-28. `PairingContext.admissionFor` mints the join
admission once per pin and caches it, so a realm-epoch rotation (a device removed) between two
approves in one pairing session leaves the cache stale and the re-approve fails until a new
pairing session starts. Rare, and nothing is lost: the user restarts pairing. Fix: key the cache
by the epoch too, or re-mint when the epoch moved. Client-only, can land any time.
