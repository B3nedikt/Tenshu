# Drive Upload Progress — "Uploading 3 of 12" instead of a permanent "Syncing…"

**Status:** Draft v0.8 (2026-09-05). **P1** (§6) · **P3 partial** (§7) · **P2** (§8) · **P4a** (§10) · **P6** (§11) · **P5** (§12) · **P7 mechanism built + OFF** (§13) · **P8** (§14 banner + §14.1 Control's notification + §14.2 iOS, which never had the defect + §14.3 run-level figures, device-verified) · **P9** (§14.4 — the byte figure, gallery source only; §14.3's first open finding). Remaining: enabling P7 (needs the platform-init split, §13); P4b — which §11 substantially devalued; the other three media providers' sizes (§14.4 *Not covered*).

Follow-on to [`sync-metered-plane-split.md`](sync-metered-plane-split.md), which made the metered
gate hold *file content* rather than the metadata plane and, in doing so, created the durable
per-file queue this proposal reports on.

---

## 0. Why

Reported: *"the app just always displays the 'Syncing your changes…' banner permanently, which is
obviously not super useful"*.

Two distinct causes, worth separating because only the first is banner logic:

1. **Wrong input.** `SyncBanner` rendered `Syncing` when
   `SyncHealthStatusModel.pendingBlockCount > 0` — the **realm-wide metadata queue**. Every app's
   `Syncable` writes land there (Timeline moments, Fit workouts, Maps places, preferences, device
   rows), so on a seven-app device it is approximately never zero. The banner was reporting
   plumbing the user has no stake in, cannot verify, and cannot act on.

   This is the same category error the plane split corrected for `Deferred`, which used to read the
   same field and so claimed *files* were waiting on a device whose files had all uploaded
   (`sync-metered-plane-split.md` §0). P1 is its other half.

2. **Racing writers.** `initSyncService` runs per client session, so every connected app publishes
   into the same single-row `SyncHealthStatus`, and a freshly built `SyncHealthHolder` writes its
   defaults before its first tick corrects them. The row can therefore read non-zero when nothing
   is queued at all. See `docs/tech-debt.md` *"Every client session runs its own SyncScheduler"* —
   fixing (1) alone may leave a residual flicker (P3).

## 1. The property worth defending

> **No banner means nothing is happening.**

A status banner that is always up is strictly worse than none: it trains the user to ignore the
one strip of UI reserved for telling them something is wrong. Everything below follows from
restoring that property and then making the "something is happening" case *specific*.

Metadata sync is deliberately **invisible** from here on: kilobytes, never gated since the plane
split, with nothing for the user to do about it. The banner speaks only about file transfers.

## 2. What the plane split made possible

`PendingContentEmit` is a durable, proxy-side journal of files awaiting upload, and
`SyncObserver.drainDeferredContent` already walks it and clears entries one at a time. So
`x of y` needs no new tracking mechanism: `y` is the journal size when the pass begins, `x` is how
many have cleared.

Two supporting facts, both verified:

- Control's foreground-service notification already exposes a `refreshNotification(taskCount)` hook
  rendering an Android `<plurals>` (`ProxyService.kt`), so P5 is a text change rather than new
  plumbing. ⚠ Note the asymmetry with `drive-shared-ui`, whose CMP `composeResources` have **no**
  plurals mechanism at all (`sync-metered-plane-split.md` §9e).
- `BackgroundTask` **deliberately carries no progress** — "the generic task API models lifecycle +
  lease only; progress is a domain concern" (its own KDoc). Putting progress on
  `ContentTransferStatus` agrees with that decision rather than reopening it.

## 3. The gap P1 leaves open, deliberately

The journal only holds content the gate **deferred**. On Wi-Fi the gate is open, so
`emitFileContent` uploads inline and journals nothing — meaning a large folder upload on Wi-Fi
shows no banner.

That is acceptable for P1 because Drive already renders its own per-file progress for that flow
(`FolderUploadController` → `FolderUploadUi`, client-orchestrated, with the conflict prompts). It
is not acceptable forever, and closing it properly is P4.

## 4. Phases

- **P1 — the banner stops lying about idleness.** `Syncing` keys on files queued for upload rather
  than `pendingBlockCount`; no files ⇒ no banner. Pure Drive-side decision change; no wire, schema,
  format or proxy change. **SHIPPED — see §6.**
- **P2 — the numbers.** Carry the pass total + completed (and bytes) on `ContentTransferStatus`;
  publish from the drain, throttled (~500 ms, always emitting the first and last update) so a
  500-file pass is not 500 store transactions each waking every observing client. Copy folds the
  reason into one line — *"Uploading 3 of 12 · 45 MB left"* / *"Waiting for Wi-Fi — 3 of 12 · 45 MB
  left"* — rather than splitting into separate states (decided 2026-08-02). ⚠ Costs a
  `TENSHU_STORAGE_FORMAT_VERSION` bump: `ContentTransferStatus` is a positional composite crossing
  the Drive↔Control IPC boundary, so it cannot grow fields silently. **SHIPPED — see §8.**
- **P3 — one scheduler per proxy process** (the tech-debt entry). Sequenced here rather than first:
  P1 does not need it, but a *counter* jitters far more visibly than a boolean under racing
  writers, and N schedulers also drain the same journal concurrently — so the same file uploads
  several times. Do this before P2 lands in front of users. **PARTIALLY SHIPPED — see §7.**
- **P4 — always journal, then drain.** Route every content emit through the journal, draining
  immediately when the gate is open. Same latency and ordering (and the entity-before-bytes
  invariant is unaffected), but the journal becomes the single source of truth for "what is
  uploading", so Wi-Fi uploads gain progress too. The larger prize is robustness: today a
  mid-upload crash leaves a file local-only with no record of the intent, and a failed chunk `PUT`
  aborts the whole emit and relies on the caller retrying — journal-first makes uploads crash-safe
  and resumable. ⚠ The only phase that changes upload semantics. **P4a SHIPPED — see §10;
  P4b (the async drain that yields a batch denominator) remains.**
- **P5 — foreground-service notification** — feed the same figures through `refreshNotification`. **SHIPPED — see §12.**
- **P7 — a client-independent `ExecutionContext`** (added 2026-08-02, out of P3's scope). The one
  thing standing between P3's *at most one live* scheduler and a genuinely *stable* one. The
  runtime's lifetime is bounded by the context it was built on (§7), so the scheduler can only
  outlive client sessions once there is a context that does: not tied to a `ClientConnection`,
  minted at registry start, destroyed only at proxy shutdown. A `:core-common` change —
  `ExecutionContext` requires a `ClientConnection` today, so it needs an internal/no-op connection
  plus a decision on how `unregisterClient` / `quiesceSessions` treat that session. Deliberately
  **not** bundled with a UI feature: it changes shared proxy machinery that five hosts (Android,
  desktop, iOS, JS, system tests) depend on, and deserves its own review. Until it lands, P3's
  re-election is the correct shape and P2's counter can jitter during session churn.
- **P6 — reconcile with `FolderUploadController`.** Two progress surfaces will contradict each
  other. Decide whether the client-side controller keeps owning the active picker flow (it also
  drives conflict resolution and cancellation) while the banner owns background and deferred
  transfers, or whether it becomes a consumer of P2's figures. **SHIPPED — see §11.**
- **P8 — reconcile with the auto-backup drain** (added 2026-08-14, from a user report). P2's
  denominator is only fixed *within* a pass, and an auto-backup puts a producer on the same queue
  the uploader is draining — so the denominator grows between passes. The drain knows the honest
  total up front; the banner should use it. **SHIPPED — Drive's banner §14, Control's foreground
  notification §14.1 (which stands down instead, and records why the registry-fed design §14
  proposed does not work); §14.3 corrects "per drain" to "per run" after the device pass, and
  carries two open findings (the byte figure, the permission-grant trigger).**
- **P9 — the byte figure** (added 2026-09-05, from a user report). P8 fixed the count and left the
  bytes reading the file in flight, because the two come from different loops. The run knows the
  bytes it has not yet handed off; the journal knows the bytes handed off but not yet uploaded; the
  banner adds them, and falls back to today's figure when a source cannot size its items cheaply.
  **SHIPPED for the gallery source — see §14.4;** the other three providers are a line each and are
  deliberately not done.

## 5. Decisions

- **D1 — one line, reason folded in** (2026-08-02). Not separate "waiting" and "uploading" states
  with different copy: the user is being told *how much is left*, and *why it is not moving* is a
  qualifier on that, not a different subject.
- **D2 — files and bytes** (2026-08-02). Both. Files are what the user recognises; bytes are what
  makes the wait legible. `pendingBytes` already rides `ContentTransferStatus`, so the second is
  free.
- **D3 — progress lives on the domain row**, not on `BackgroundTask` — its KDoc already reserved
  that boundary (§2).

## 6. As-built — P1 (2026-08-02)

One condition changed, plus the doc comments that explain why. `Syncing` now reads
`content.pendingFileCount > 0`; `pendingBlockCount` reaches the banner nowhere.

- `SyncHealthStatusModel.pendingBlockCount` and `.isSyncing` are **kept on the model** — the former
  for diagnostics, the latter for a future inline spinner — but neither drives a banner state now.
- ⚠ Absent content signal (proxy has not published a row yet) falls back to **no banner**, never to
  a banner conjured from the block count. Pinned by
  `metadataBacklogWithNoContentSignalYet_showsNoBanner`.
- Tests: `metadataBacklogAlone_showsNoBanner` states the reported bug directly — 42 blocks queued,
  no files transferring, render nothing. Three existing tests keyed `Syncing` off `pending = N` and
  were re-pointed at the content signal.
- No wire, schema, storage-format or proxy change; Drive-side only.

**Verified on device (CPH2581).** Before: an idle phone on unmetered Wi-Fi, nothing transferring,
showing *"Syncing your changes…"* — the reported bug, reproduced. After: same state, no banner, file
list intact.

⚠ **Two unrelated hazards surfaced during that pass, both worth knowing:**

- **Announce skew the format assertion does not cover.** Installing only Drive from this branch
  (post-E4 master, `SyncService` v3) against the phone's older Control (v2) produced
  `TenshuSchemaAnnounce: app.tenshu.drive admitted=false [tenshu/sync/service announced=3 floor=1
  current=2]` and an **empty file list**. Both sides were at storage format 19, so the smoke gate's
  fmt assertion would have passed — it is the *service* version that skewed. Reinstall Control.
- ⚠⚠ **The client resubscription fix does not cover the paged file listing.** After Control was
  replaced, Drive (same PID) re-announced and was admitted — so R1's reconnect hook fired — yet the
  listing stayed **empty** until Drive itself was restarted, with Control's PID unchanged. That is
  the exact signature in `core-client-resubscription.md` / the resubscription memory, which R1 was
  believed to have closed. The announce replays; the Pager flow does not recover. Belongs to that
  proposal, not this one.

## 7. As-built — P3, partial (2026-08-02)

**Shipped: the election.** `initSyncService` now splits into a per-session half and a process half.
A `SyncRuntimeHolder` carries the one runtime; the second and later client sessions build **only
their own observers** and reuse the shared scheduler, processor, registries and status writers.

⚠ **The observers could not move**, and that is the whole reason this is a split rather than a
hoist: `ExecutionContext.observerRegistry` is a per-context default, not a shared instance, so a
`DataObserver` only sees saves made through its own context. One process-wide `SyncObserver` would
silently stop observing six of seven apps' writes. Everything else in that 416-line function was
process work that was only per-session because it was bundled alongside them.

Six tests pin it (`SyncRuntimeElectionTest`), including the counterpart claim that each session
still gets its **own** observer, and that omitting the holder preserves the previous behaviour
exactly — so the ~15 existing test call sites and the JS runtime are unaffected.

### ⚠ Not finished: the runtime still dies with the session that elected it

The runtime runs on its electing session's scope. When that client disconnects, the scheduler stops
process-wide — strictly worse than the N-schedulers behaviour it replaces — so `SyncRuntime.isAlive()`
re-elects at the next session init. That bounds the damage to **at most one live scheduler at any
moment** (the previous behaviour was N concurrent), which is the correctness win, but it is not the
intended "one, stable".

⚠⚠ **That bound was false until 2026-10-30: the election itself raced.** `initSyncService` checked
`SyncRuntimeHolder.runtime` at its top and published it ~500 lines later, with no lock between, and
sessions do not arrive one at a time — when Control restarts, every running app rebinds at once, each
on its own binder thread. Every session that checked before the first published built its own
runtime. **Measured (phone trace, 2026-10-30): five schedulers ticking in one Control process** after
a restart with five apps open. Each tick-end reset the shared pass figures, so a forced *Upload over
cellular* read *Waiting for Wi-Fi* for its whole upload; each also held its own emit-key table, so on
Wi-Fi the drains could upload one parked file more than once. Fixed by holding
`SyncRuntimeHolder.electionLock` across the whole check → build → publish
(`SyncRuntimeElectionConcurrencyTest`: 6 sessions at once, 6 runtimes before, 1 after).

**Measured on device (CPH2581):** opening Control + four apps produced **3 elections and 3 reuses**.
The reuses prove the holder is shared; the re-elections prove the electing session keeps dying —
the same session churn recorded in `sync-metered-plane-split.md` §9e. Each re-election rebuilds the
runtime and re-runs the one-shot startup repairs; those are individually idempotent
(`publishIfChanged`, `resumeIncompleteMigrations`, `reconcileOwnDeviceRow`), so the churn is
wasteful rather than harmful.

**What is blocking the last step: nobody owns a process-lifetime scope.** A first attempt gave the
runtime `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, which is an **orphan** — nothing
cancels it, so in `control-shared:desktopTest` the `initSyncHealth` collector outlived its
`ExecutionContext` and NPE'd on a destroyed `schemaProvider`. Parenting it to `ClientRegistry`'s
`serviceScope` does not help either: `shutdown()` deliberately cancels each *session* scope and the
fallback detach scope, but **not** the injected `serviceScope` — the registry does not own it.

### ⚠⚠ The scope-ownership fix was attempted and does NOT work — the blocker is the *context*

Two ownership fixes were tried and both fail for the same underlying reason.

1. An **unowned** process scope (`SupervisorJob() + Dispatchers.Default`) leaks: nothing cancels
   it, so `control-shared:desktopTest` had the `initSyncHealth` collector outlive its
   `ExecutionContext` and NPE on a destroyed `schemaProvider`.
2. A **proxy-lifetime owner** outside the registry (a `ProxyLifecycle` single cancelled from
   `ProxyGateway.shutdown`) does not help either — and would move that same crash from tests into
   production.

The reason is not scope ownership at all. `ExecutionContext.destroy()` clears the stream and
observer registries and destroys the client connection, and `ClientRegistry.quiesceSessions`
destroys every session's context while deliberately keeping the registry open for new ones. The
teardown KDoc beneath `destroy()` names this exact hazard, and names the culprit:

> *"Cancelling [sessionScope] alone is not enough — cancellation is cooperative and asynchronous,
> so a tick already in flight (e.g. **the sync scheduler** or the constraint-gate collector, which
> do synchronous, non-suspending context access like `dataFactory()` between suspension points)
> can still touch a half-disposed context."*

So **the runtime's lifetime is bounded by the lifetime of the `ExecutionContext` it was built on**,
and a process-lifetime scope over a session-lifetime context is the documented bug, not the fix.
Giving the scheduler a longer life requires giving it a **client-independent `ExecutionContext`** —
one not tied to any `clientConnection`, minted at registry start and destroyed only at proxy
shutdown. That is the real remaining work, and it is a `:core-common` change: `ExecutionContext`
currently requires a `ClientConnection`, so it means an internal/no-op connection plus a decision
about how `unregisterClient` / `quiesceSessions` treat that session.

**Until then, re-election is the correct shape, not a workaround.** It keeps the runtime's lifetime
equal to its context's — which is the invariant the architecture actually requires — and bounds the
process to one live scheduler. The cost is a rebuild whenever the electing session goes away, and
the one-shot repairs it re-runs are individually idempotent by design.

## 8. As-built — P2 (2026-08-02)

*"Uploading 3 of 12 · 45 Mb left"*, verified on device.

### ⚠⚠ The figures went on a NEW composite, not onto `ContentTransferStatus`

The plan said to add `passTotal` / `passCompleted` to `ContentTransferStatus` and pay a format bump.
That was tried, and the **E2j-1 shape baseline correctly rejected it**, demanding a `SyncService`
version bump for the reshape.

That bump would have been actively harmful: the per-service version is the *data* version, so
raising it makes the epoch writer pull-fence every not-yet-updated device (`core-schema-evolution.md`
§7.2) — over a purely local, never-synced UI row. That is precisely the reasoning E2d-2 recorded
when it declined to bump for `SchemaFenceStatus`.

So P2 follows the documented escape hatch instead — **a new composite id, never new fields** — and
the figures live on their own single-row `ContentUploadProgress`. The trade:

| | reshape `ContentTransferStatus` | new `ContentUploadProgress` |
|---|---|---|
| `SyncService` version | bump ⇒ **peers pull-fenced** over a local row | untouched |
| `TENSHU_STORAGE_FORMAT_VERSION` | bump ⇒ **store wipe for users** | untouched |
| cost | — | one more single-row relation |

The two rows are recombined in `TenshuFilesRepository.contentTransfer()` so the split never reaches
the UI: the banner still sees one `ContentTransferModel`.

### The rest

- **`drainDeferredContent(force, onProgress)`** reports `(completed, total)` — once before the first
  upload so the denominator appears immediately, then once per completion. Unthrottled by design;
  the caller owns the cadence.
- **`SyncScheduler` throttles** to one publish per 500 ms, with pass edges (first and last) always
  going through — each publish is a store transaction that wakes every observing client, so a
  500-file pass must not be 500 of them.
- ⚠ **The denominator is the pass total, not the live queue.** The queue counts *down* as files
  finish, so using it would render "3 of 9", then "4 of 8" — a progress indicator whose denominator
  chases its numerator. `total == 0` (older Control, or between passes) falls back to the queue size
  so the banner degrades to a plain count rather than "3 of 0".
- ⚠ **Progress counts *resolved* entries, not successful ones.** A dead intent dropped because its
  file is gone has left the queue; a bar that stalls on work which can never succeed is worse than
  one that moves past it.
- ⚠ **A pass in flight outranks `Deferred`.** During a forced *"Upload over cellular"* drain the
  metered gate is still closed, so the proxy still reports `deferred = true` while bytes are moving
  — without this the banner would say "waiting for Wi-Fi" about an upload the user just started.
- **`formatByteSize`** extracted from `FileMapper` (which now delegates) so the banner's "7.7 Kb"
  matches the file rows above it; two renderings of the same number on one screen reads as a bug.
- Copy is one line with the reason folded in (D1): *"Uploading 1 of 1 · 7.7 Kb left"* /
  *"Waiting for Wi-Fi — 1 file (7.7 Kb) still to upload. Everything else is up to date."*

**Verified on device (CPH2581):** metered Wi-Fi, one file deferred → the waiting copy with bytes;
tap *Upload over cellular* → **"Uploading 1 of 1 · 7.7 Kb left"** with the gate still closed; then
idle with no banner. Unit coverage for the parts a screenshot cannot pin: fixed denominator,
queue-size fallback, dead-intent counting, pass-outranks-deferred, and the drain's callback
sequence.

⚠ **Inherits P3's residual jitter**: the counter is published by whichever runtime is currently
elected, so a re-election mid-pass restarts the numbers. P7 removes it.

## 10. As-built — P4a (2026-08-02)

**Journal first, always** — not only when the metered gate is closed. `emitFileContent` writes the
durable `PendingContentEmit` before a single byte moves, uploads inline exactly as before when the
gate is open, and clears the intent only once the bytes land.

### What that buys

- **Crash safety.** A process death mid-upload used to leave the file local-only with *no record it
  was ever meant to sync* — `UploadFile` had already returned, so nothing retried, ever. The intent
  now outlives the crash and the next drain finishes it.
- **Failure recovery.** A failed chunk `PUT` aborts the emit, and that used to rely on the *op's
  caller* retrying the whole operation. The journal owns the retry now, so a transient relay
  failure self-heals.
- **Coverage.** The journal is the single source of truth for "what is uploading", so the banner's
  figures cover Wi-Fi uploads and not just deferred ones.

### What is deliberately unchanged

Latency and ordering. When the gate is open the upload still happens **inline**, so `UploadFile`
returns with the bytes already on the content plane, the entity-before-bytes invariant holds
unchanged, and every caller that reads content straight after an upload behaves identically. The
journal is a safety net *under* the existing path, not a queue *in front of* it. That is why the
full gate passed unmodified — no system test needed an added await.

### ⚠ The half that did NOT land: the batch denominator on Wi-Fi

An inline upload journals, uploads and clears **within one call**, so the queue never holds more
than the file in flight. A twelve-file folder upload on Wi-Fi therefore reports *"1 of 1"* twelve
times rather than *"3 of 12"* — the banner shows that something is uploading, not how far through
the batch it is. Pinned by `inlineUploadsDoNotAccumulateIntoABatch` so the limit is a stated
property rather than a surprise.

**P4b** is what closes it: let the emits accumulate and be drained as one pass. That changes
`UploadFile`'s contract — it would return before the bytes are up — which is a real semantic change
for every caller and every system test that reads content immediately after an upload
(`TaskBatchedUploadSystemTest`'s byte-identical `OnDemand` assertion is the sharp one). Splitting it
out keeps the crash-safety win landable on its own; bundling them would have put a contract change
behind a progress-bar feature.

Note the user-facing folder-upload flow already has per-file progress from Drive's own
`FolderUploadController`, so P4b is about the *background* batch case, not the picker flow — which
is also what P6 has to reconcile.

## 11. As-built — P6 (2026-08-02)

**Decision: the folder-upload bar owns the story while it is on screen; the banner steps aside.**
Not the other way round, and not a merge of the two.

P4a made the conflict real rather than theoretical. `FolderUploadStatusBar` renders globally from
`App.kt`, the sync banner renders inside the Files screen — so during a picker-initiated folder
upload both were on screen, narrating the *same* operation with *different* numbers: the bar with
the real batch denominator ("3 of 12"), the banner with whatever single file was in flight
("1 of 1", because an inline upload journals and clears within one call — §10).

The bar wins because it knows more: it has the batch denominator, and it carries the conflict
prompt, the cloud pre-flight and cancel. The banner cannot express any of that.

### ⚠ Suppression is scoped to the *transfer* story

`Deferred` still shows, and that exception is the important half. On a metered network the folder
bar runs to "12 of 12" — its walk finished, metadata written, content journalled — while **not one
byte has left the device**. Suppressing "Waiting for Wi-Fi" there would hide the reason for exactly
the situation this whole track exists to make legible, so it would be the worst possible outcome.

`Locked`, `NeedsUpdate`, `LocalOnly` and `NeedsAttention` are likewise untouched: each is a reason
the upload the bar is narrating *cannot finish*, which is more useful during an upload, not less.

### Shape

The rule lives in `SyncBanner.evaluate(..., folderUploadActive)` — the pure decision function — so
it is testable without a UI, in keeping with the rest of the banner logic. The ViewModel takes the
flag as a trailing defaulted `Flow<Boolean>` (same pattern as `displayPreferences`), so the iOS
surface and the test Koin that construct it positionally are unaffected. The DI graph maps it from
the app-scoped `FolderUploadController.state`, which outlives the ViewModel — so a screen re-entered
mid-upload still suppresses correctly.

Drive-side only: no wire, schema, storage-format or proxy change.

## 12. As-built — P5 (2026-08-03)

The ongoing foreground-service notification now reads *"Uploading 3 of 12"* while a pass is
running, and falls back to the existing task-count plural when none is.

- **The decision is Android-free.** `foregroundNotificationContent(taskCount, passTotal,
  passCompleted)` picks between the two messages and is unit-tested on the JVM, following the
  reason `ForegroundPromotion` and `ForegroundTaskLiveness` already give for staying import-free: a
  `Service` cannot be tested there, but the *choice* is the interesting part. `ProxyService` only
  renders what it returns.
- **Upload outranks the task count** for the same reason the banner rework did: *"2 background
  tasks"* is true and useless, where *"Uploading 3 of 12"* is something the user can connect to what
  they just did.
- ⚠ **A second collector was needed, not just a richer string.** The existing refresh fires on
  *task-count* changes, and a pass does not change the count — a twelve-file drain is one count the
  whole way through, so the text would have frozen at "1 of 12". `ProxyService` therefore observes
  the holder directly, `distinctUntilChanged` on the pass pair (the holder emits every scheduler
  tick).
- ⚠ **Re-posts only while promoted.** Notifying otherwise would put up a standalone notification
  with no foreground service behind it — the hazard `ForegroundPromotion.onCountChanged` already
  guards against, so the collector asks the retained promotion instance rather than assuming.
- `SyncHealthHolder` became a DI single on Android purely so the service can observe it;
  `initSyncService` would otherwise construct its own and nothing outside the sync graph could see
  the figures.

Android-only, and no wire, schema or storage-format change.

⚠ **Not verified on hardware.** The notification needs a promoted foreground service *and* a live
upload pass at the same time, which the emulator will not produce naturally; the decision function
is unit-covered and the rendering is a `getString`. Worth a look during the next real-device pass.
## 13. P7 — the mechanism, and a measured blocker (2026-08-03)

The client-independent `ExecutionContext` is **built, tested and off**. Landing it dormant rather
than red, because the interesting output of this attempt is the measurement.

### Built

`ClientRegistry` can mint a **system session**: one `ExecutionContext` belonging to no client, over
a no-op `SystemClientConnection`, minted before any client session (so P3's election lands on it),
kept **out of the `clients` map** so `unregisterClient` / `quiesceSessions` cannot reach it, and
destroyed only by `shutdown` / `shutdownAndJoin`. Tests pin all three lifetime guarantees.

It is opt-in per host (`mintSystemSession`, default off), so the JS runtime, the system-test harness
and every plain test registry are byte-for-byte unchanged.

⚠ Publish-before-initialise is load-bearing here too — the first attempt recursed to a
`StackOverflowError` (`systemContext()` → initialise → the hook re-enters `getExecutionContext` →
`systemContext()`), the same ordering the existing `clients.update`-before-`runInitialization`
comment documents for client sessions.

### The measured blocker

With `mintSystemSession = true` on Control, `control-shared:desktopTest` fails **~2 tests per run,
a different two each run**. With it off, the same suite passes **cleanly twice in a row**. That A/B
is the whole finding: this is *not* the pre-existing "flaky under parallel load" entry, it is the
system session tipping it.

The cause is that the system session runs `initialization` like any other, so Control ends up
running **two copies of every per-session subsystem** — entitlement collectors, storage readers,
notification publishers and so on. (The scheduler itself is fine: `SyncRuntimeHolder` dedupes it,
which is what P3 bought.)

### What it actually needs

The platform initialisation has to split in two:

- a **data-plane** half the system session runs — the sync runtime plus every service whose
  *relation maintainers* must fire on ingest writes, because those observers are registered **per
  context** (the same constraint P3 hit) and the runtime applies inbound blocks through its own
  context;
- a **client-facing** half it must not run — app registration, the commerce/publisher clients, the
  UI-facing storage readers.

That is a refactor across three platform modules, and a wrong split fails *silently* (a missing
merger or maintainer just stops maintaining), so it is not something to bundle behind a progress
feature. It wants its own slice with its own review.

Until it lands, **P3's re-election remains the correct shape** — the runtime's lifetime equals its
context's, which is the invariant the architecture requires — and the residual cost is the counter
jitter §8 records.

## 14. As-built — P8: a running backup owns the denominator (2026-08-14)

**Reported:** *"When uploading the count, e.g. 20 of 400, the 400 still jumps sometimes e.g. to
500. It arrives at the correct number after some time though. I assume this is a technical
restriction of Android?"* — during a WhatsApp-images auto-backup.

It is not an Android restriction. It is P2's denominator meeting an auto-backup.

### Why it grows

`SyncObserver.drainDeferredContent` snapshots the journal when a pass begins and reports that size
as `total` for the whole pass, which is what §8 wanted: a denominator that does not chase its
numerator. But **fixed per pass is not fixed per upload run.** During an auto-backup two loops share
the journal:

- Drive's `SyncedFolderController` drain imports media items — local write plus a journalled content
  intent (§10's journal-first) — bounded by local IO;
- the proxy's scheduler uploads them, bounded by the network.

The importer wins, so the queue **grows under the uploader**, and each new pass snapshots a bigger
number: 400, then 500. It settles only when the drain finishes and the queue has one consumer again.
Between passes it is worse still, because the `passTotal == 0` fallback (§8) reports the *live*
queue size — precisely the figure that is growing.

⚠ The two other "N of M" surfaces were never affected, which is why this looked platform-specific:
Synced Folders' own row and Drive's backup FGS notification both read `BackupState.BackingUp`,
whose total comes from the drain.

### The fix

`SyncedFolderController` enumerates every selected bucket of every source **before** it uploads
anything (`drainSourceInner`'s `perBucket` snapshot), so its `total` is known up front and never
moves. That figure now reaches `SyncBanner.evaluate` as a trailing defaulted
`backupProgress: BackupProgress?`, mapped in the DI graph from the app-scoped controller's `state`
— the same shape P6 used for `folderUploadActive`, for the same reasons (§11 *Shape*).

### ⚠ It substitutes the *figures*, not the priority

P6 could stand the transfer state **down** entirely, because `FolderUploadStatusBar` was on screen
saying the same thing. A backup drain has no bar in the file list, so suppressing here would trade a
jumping denominator for no feedback at all. So the branch order is untouched: `Deferred`, `Offline`,
`Locked`, `NeedsUpdate`, `LocalOnly` and `NeedsAttention` still outrank a running backup exactly as
before — a drain busily importing into a queue the metered gate is holding must still read *"waiting
for Wi-Fi"*, which names the cause where *"Uploading 60 of 400"* would be a lie about parked bytes.
One branch is genuinely new, at the bottom: a drain whose queue is momentarily empty keeps the banner
up rather than blinking it off between files.

⚠ `completed` counts items **handed off**, not bytes landed, so it leads the actual upload — at the
end of a drain the banner can read "400 of 400" with bytes still in flight. `bytesLeft` covers that,
and when the drain ends the banner falls back to the pass figures, which by then have no producer
racing them.

### Not covered

- **Control's foreground notification** (§12) — its own half, §14.1 below.
- **iOS**, where `DriveReadSurface` constructs `FilesScreenViewModel` positionally and passes no
  backup signal; its drain lives in `DriveBackupSurface`'s own controller instance. ⚠ Left that way
  on purpose — §14.2: the platform never had the defect, because it renders no banner at all.

Drive-side only: no wire, schema, storage-format or proxy change. Guarded by six cases in
`SyncBannerTest` — all six verified red with the substitution removed, including the growing-queue
one, which compares two evaluations of the *same* drain across a 400→500 pass step.

## 14.1. As-built — P8, Control's notification (2026-08-15)

Same defect, second surface: `ProxyService`'s foreground notification renders the same pass figures
(§12), so it jumped in exactly the same way. **It now stands down while an app is driving a bulk
ingest**, rather than being taught the drain's numbers.

### ⚠⚠ The follow-up this doc proposed first was wrong

§14 suggested feeding the notification "from the ingest registry rather than from
`ContentUploadProgress`", because Drive already reports `IngestSource.STATUS_RUNNING` with a
drain-derived `pendingCount`. Checked before building, and it does not work:

- `IngestSource` is **`Syncable(SyncStrategy.Eager)`** — live progress through it would push a sync
  block **per photo**, to the relay and every peer device. `SyncedFolderController` reports only when
  the status *code* changes (`distinctUntilChangedBy { it.status }`) precisely to avoid that, so
  `pendingCount` is frozen at whatever the run started with and is useless as a numerator.
- The relation is synced, so it also carries **other devices'** rows. A backup running on the user's
  tablet would otherwise silence this phone's notification.

The registry carries *that* a run is happening, never *how far along* it is. That turns out to be the
only fact worth carrying.

### The decision: the surface that knows more owns the story

During a backup the user already has an honest notification — Drive's own FGS anchor renders
`backupNotificationContent`, i.e. *"Backing up 21 of 400"* from `BackupState.BackingUp`. Control was
posting a second notification narrating the **same** operation with a **different**, moving number.
That is P6's situation (§11) one process up, and it resolves the same way: the surface with the real
denominator keeps the story, the other steps aside. Standing down costs nothing here — unlike the
banner (§14), where suppression would have left the file list with no feedback at all, Drive's
notification is already on screen.

`foregroundNotificationContent` therefore takes an `ingestRunning` flag and falls back to its
pre-P5 liveness text while it is set. Everything outside a bulk ingest is unchanged: an ordinary
folder upload still reads *"Uploading 3 of 12"*.

### The signal: an op-driven, device-local registry

`IngestRunRegistry` (`:ingest-service-proxy`) is an in-memory `StateFlow<Set<String>>` of source keys
currently reporting RUNNING, updated by `UpdateSourceStatusImplementation` after its write and read by
`ProxyService`. A DI single on Android; other hosts pass nothing and are byte-for-byte unchanged.

⚠ Updating from the **op** rather than the row is what makes it device-scoped for free: a row
arriving in a `SyncBlock` never runs an op, so everything the registry sees was reported by an app on
*this* device. It also costs no traffic — the op already fires exactly on status changes.

⚠ The flag is folded into the notification's *re-post* trigger, not only read when one is built: a run
starting or ending changes the message while the pass figures may not move at all.

### Two accepted gaps

- **A proxy restart mid-run** forgets the run (in-memory by design), so the notification shows its own
  pass figures again until the app reports its next status change. A durable row would fix it and is
  not worth a schema bump for one run's worth of the pre-existing behaviour.
- **Drive being killed mid-drain** (an OEM kill) leaves the run marked. It self-heals rather than
  sticking: the per-source status flow re-emits from `Disabled` on Drive's next start, which reports
  IDLE and releases the signal.

Guarded by five cases — two in `ForegroundNotificationContentTest` (stand-down and hand-back), three
in `IngestServiceTest` (tracked until any other status, per-source so one finishing does not clear
another, and no run recorded when the op no-ops on an unregistered source). All five verified red with
the change removed. No wire, schema, storage-format or relay change.

## 14.2. As-built — P8, iOS (2026-08-15)

**iOS never had this defect**, and the reason is worth recording, because it is the same principle
§14 and §14.1 arrive at from the other direction.

Checked before writing anything:

- `FilesView.swift` reads exactly `pagedFiles`, `sort`, `setSort`, `viewMode`, `setViewMode` off the
  bound `FilesScreenViewModel`. **`syncBanner` is never read** — iOS renders no sync banner at all,
  so the queue-derived denominator has no surface to jump on.
- Nothing in `tenshu-sdk-ios` reads `passTotal` / `ContentUploadProgress` / `contentTransfer()`, and
  no Swift view shows an upload count. The only backup progress iOS shows is
  `DriveBackupSurface.observeStatusText()` — *"Backing up 21 of 400…"* — mapped straight from
  `BackupState.BackingUp`, i.e. **the drain's own figures**, which is exactly what §14 changed the
  Android banner to use.

So the platform that started from the drain's state was right all along; the two that started from
the upload queue had to be corrected. Three surfaces, one rule: *the loop that knows the total owns
the number.*

### ⚠ No wiring was added, deliberately

The obvious tidy-up — hand `DriveReadSurface` a `backupProgress` flow so the parameter is "correct
on every host" — was written and then **removed** (maintainer's call, 2026-08-15). It would have
been code with no consumer: nothing on iOS collects `syncBanner`, so nothing would ever have
evaluated it, and dead wiring is exactly what rots into a wrong answer later. `FilesScreenViewModel`
defaults the parameter to `null`, which on a host with no banner is the honest value.

**If you are the one adding an iOS sync banner:** read §14 first, and take the figures from
`SyncedFolderController.state` (what `DriveBackupSurface.observeStatusText` already maps), *not*
from the proxy's pass total. `DriveBackupSurface` carries a pointer to this section for that reason.

### ⚠ A latent hazard fixed next door

`observeStatusText()` was `(controller?.state ?: flowOf(BackupState.Disabled))`: the field is read
once, at *call* time, so a subscriber arriving before `DriveBackupSurface.start()` would be pinned to
`Disabled` — a permanently "Off" backup footer — for the process's lifetime. Installation and the
first Settings render are ordered in practice, which is why it never showed. It now resolves the
controller through a `MutableStateFlow<SyncedFolderController?>`, so subscription order stops
mattering. This is the one behavioural change on iOS in the whole of P8.

No wire, schema, storage-format, proxy or Swift change; `iosKotlinCheck` and the Drive Swift build
(`scripts/ios-smoke.sh --build-only drive`) both cover it.

## 14.3. As-built — P8, the device pass and the *run* (2026-08-15)

§14 and §14.1 shipped against unit guards. This is what a phone said.

### What the device confirmed

With 100 photos arriving in a folder Drive was watching, the banner read *"Uploading 2 of 20 · …"*
through *"Uploading 19 of 20"* — **the denominator did not move for the whole pass**, and Drive's own
Synced-Folders status ran `Backing up 7 of 80 … 80 of 80` monotonically. All 180 test files reached
`/Backup/<device>/…`, and the terminal "Up to date" was accurate. The mechanism §14 targets — a
denominator chasing a queue the drain is filling — is fixed.

### ⚠⚠ What it also exposed: a drain is not a run

An earlier sample caught three readings in eighteen seconds:

```
Backing up 5 of 16…   →   Backing up 26 of 30…   →   Backing up 4 of 22…
```

Not one drain changing its mind — `queryNewItems` has no LIMIT and `drainSourceInner` snapshots once,
so within a drain the total cannot move. These were **three successive drains**. The engine starts a
fresh drain per trigger, and media arriving in a trickle (a WhatsApp conversation, a gallery
finishing its indexing, a `MediaStore` reveal) produces a burst of them. Each total was honest; the
thing the user was watching — *this backup* — had no total at all.

So §14 had turned "fixed per pass" into "fixed per drain", when what the user perceives is neither.

### The fix: figures span the run

A **run** is every drain since the engine was last idle. `SyncedFolderController` now keeps a
per-source accumulator: each drain adds its snapshot to `run.total` and its progress to
`run.completed`, and the terminal summary is published **once**, by `finishRuns()`, when the trigger
loop finds nothing further pending. The coalescing is deterministic rather than timed — after a drain
the loop drains any *already pending* trigger into the same run — because the conflated channel makes
"is more work queued right now" an exact question.

Consequences worth stating:

- The numerator never restarts mid-run, and the denominator grows **only** when files genuinely
  arrived. That is honest: 500 files really are waiting once 100 more land.
- `ingestAndAwait` (the headless worker) closes its own run: one wake, one run, one summary.
- ⚠ A stable state ends a run. `setStable` drops the accumulator, so a source that pauses off-policy
  or is switched off cannot be overwritten by a stale "Backed up N" — guarded by
  `aPauseMidRun_keepsThePausedState_ratherThanBeingOverwrittenBySummary`.

Guarded by three cases in `SyncedFolderControllerTest`, verified red without the change. ⚠ The
intermediate `BackingUp` frames are conflated away by the aggregate `StateFlow`, so the run is
asserted through its **summaries** — one `JustFinished(5)` where the old code produced
`JustFinished(2)` then `JustFinished(3)`, each of which was a visible restart.

### ⚠ Open, found in the same pass: the byte figure is noise

The banner rendered *"Uploading 6 of 20 · 0b left"* and *"· 3.8 Kb left"* while 1 MB files were still
uploading — it should have read ~14 MB. `pendingBytes` is summed from the deferred-content journal as
of the last scheduler tick, and journal-first uploads clear their entry almost immediately, so on a
fast link the figure collapses to noise. The file count is right; the bytes are not. Unfixed — the
honest options are to compute remaining bytes from the run's own items (Drive knows their sizes) or
to drop the byte clause when it cannot be trusted.

**Closed for the gallery source by P9 — §14.4**, taking the first option. The second is still the
right answer for a source that cannot size its items, and §14.4 keeps it as the fallback rather than
inventing a number.

### ⚠ Also open: granting the photo permission does not re-trigger a drain

Observed on the reinstalled device: Drive reported "Up to date" with 886 selectable items because the
first drain ran before `READ_MEDIA_IMAGES` was granted and found nothing. `SyncedFolderController`
re-drains on settings, storage and MediaStore change events — a permission grant is none of those.

## 14.4. As-built — P9: the byte figure is the backup's, not the file in flight (2026-09-05)

**Reported:** *"I like that it shows e.g. 250 of 2271, to indicate the number of images, but the
bytes shown after the point are only for the current file… Is this technically feasible, or are we
restricted by Android here somewhat?"*

Not an Android restriction — the same shape of answer as §14, one field over. The two halves of
*"Uploading 250 of 2271 · 3.8 Kb left"* came from **two different loops**, and only one of them had
been fixed:

- the **count** is the run's (§14.3), which enumerates every selected bucket before uploading
  anything, so it is honest;
- the **bytes** were `ContentTransferStatus.pendingBytes` — the *proxy's* figure, summed by
  `SyncObserver.deferredContentFigures()` over the deferred-content journal. Since journal-first
  (P4a) an entry is written and drained almost immediately, so on a fast link the journal holds
  roughly the file uploading right now. An honest count beside a per-file size.

### The fix: the run knows the rest, so add the two

Drive already walks every candidate up front; it was **throwing the sizes away**. `MediaItem` now
carries `sizeBytes`, the run accumulates `totalBytes` / `handedOffBytes` alongside `total` /
`completed`, and `BackupState.BackingUp` carries `remainingBytes` out to `BackupProgress`.

`SyncBanner.remainingBytesFor` composes the whole remainder:

```
bytesLeft = run.remainingBytes          # enumerated, not yet handed to the data plane
          + content.pendingBytes        # handed off, journalled, not yet uploaded
```

⚠ **No double count, by construction.** An item leaves the first set exactly as it enters the second
— `uploadForBackup` returns after the local write plus the journalled intent, and the relay upload is
the scheduler's job afterwards. The only slack is the sub-second local write itself and the scheduler
tick that re-sums the journal. This is also the fix for §14's own caveat that `completed` counts
items *handed off*: the bytes still in flight at "2271 of 2271" are precisely `pendingBytes`.

### ⚠⚠ Unknown is not zero, and one unknown forfeits the run

`sizeBytes` is `Long?`. A provider that cannot size an item cheaply answers `null`, one `null` latches
`RunAccumulator.bytesKnown` false for the whole run, and `aggregateState` applies the same rule across
sources. The banner then falls back to `pendingBytes` alone — **exactly the pre-P9 figure**.

A partial sum is the failure mode worth designing against: *"14 MB left"* that is short by one
unsizable video looks identical to a correct one, and the user has no way to tell. A known-imperfect
number is recoverable; a plausible wrong one is not. (Same argument §14 makes about a denominator
that is honest per drain and dishonest per run.)

### Why Android costs nothing here

`MediaStore.MediaColumns.SIZE` rides the projection `queryNewItems` already runs — same cursor, same
query, no extra resolver round-trip, no new permission, no `openFileDescriptor` per candidate.
⚠ Read with `cursor.isNull(sizeCol)`, not `getLong`: a null SIZE reads back as `0`, which is a
perfectly good size for an empty file and a lie for a row MediaStore has not finished writing
(`IS_PENDING`, a file placed outside the store and not yet scanned).

**The bar for a provider is "already in the enumeration".** A size worth having is a column or a
`File.length()`; opening every candidate to `stat` it would put a per-file OS round-trip in front of a
backup that has not started, to improve a byte figure. A provider in that position answers `null`.

### Not covered

Deliberately gallery-only this phase (maintainer's sequencing, "1 first"). The other three providers
answer `null` and so keep their pre-P9 banner exactly:

- **Desktop** (`DesktopMediaSourceProvider`) — `file.length()`, free, it is already stat'ing. A
  one-liner.
- **SAF document tree** (`AndroidDocumentTreeSourceProvider`) — `DocumentFile.length()` is one more
  resolver query per file on a walk that already does three (`name`, `lastModified`, `isDirectory`).
  Not a new class of cost, but the right fix is a raw `DocumentsContract` query with a full
  projection, which is its own change.
- **iOS** (`IosMediaSourceProvider`) — genuinely not cheap: `PHAsset` carries no size, and getting one
  means a `PHAssetResource` lookup per asset. iOS also renders no sync banner at all (§14.2), so
  `null` is both the honest and the free answer.

Also untouched: **Drive's own FGS notification** and the Synced Folders footer, which render
`BackingUp` as *"Backing up 21 of 400"*. `remainingBytes` is on the state they already read, so adding
a byte clause there is a copy decision, not plumbing.

### Verification

Seven guards, each verified red against its own mutation (composition removed; composition applied to
only one of the three `Syncing` branches; the unknown-size latch removed; the deselect give-back
removed; `aggregateState`'s all-or-nothing removed; the run's byte arithmetic stubbed out):

- `SyncBannerTest` — the composition, the fallback when the run cannot size, and all three transfer
  branches composing (the tick-boundary branch is where the defect was measured).
- `SyncedFolderControllerTest` — the run's remaining bytes covering the whole backup rather than the
  file in flight, one unsizable item forfeiting the run's figure, unticking a folder mid-run giving
  back its bytes as well as its count, and `aggregateState`'s cross-source rule.

⚠ **The `MediaStore` read itself has no host-side test** — `app-ingest` has no Android unit-test
source set, and standing up Robolectric for one cursor column is disproportionate. It is covered by
the smoke gate (HR #8, `androidMain` changed) and needs a device pass to confirm the figure against a
real gallery. No wire, schema, storage-format, relay or proxy change: `BackupProgress` never crosses
the IPC boundary.
