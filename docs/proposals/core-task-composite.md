# Task Composite — Unifying Async Operation Lifecycles (`AsyncTask` / `BackgroundTask` / `StreamTask`)

**Status:** **TS-3 IMPLEMENTED 2026-07-24** (see line 25 below and `core-background-processing.md`
§10.6). ⚠ This said "Spec'd; not started" until 2026-08-11 while its own body recorded the ship.
Raised by the maintainer during the
folder-upload Phase 3a work (`drive-folder-upload.md` §5c) as the principled
generalisation of the ad-hoc cancellable-upload surface. It
**composes with and would eventually subsume** Phase L2
(`core-background-processing.md` "Phase L2") and the `Stream` mechanism, so it
is a cross-cutting `:core` async-runtime refactor — by AGENTS.md it gets a
design doc *before* code, and it must not block the (landed) 3a deliverable.
3a deliberately rides Phase L2 *as landed*; this doc records the better shape,
the two hard problems that must be solved before any code, and the linkage.

**Update 2026-07-24 — no longer fully deferred; §5–§6 added.** The pick-up
trigger fired: the **task-scoped sync lane**
([`core-background-processing.md`](core-background-processing.md) §10, from
[`bounded-store-cache.md`](bounded-store-cache.md)'s maintainer direction)
needs exactly this composite as its client-facing face. §5 specs the
composition DSL the original write-up left implicit — request-DSL methods that
build a `BackgroundTask` from **multiple `IoOperation` calls** — and §6 binds
the task to liveness and the sync lane. The §4 hard problems are re-scoped
in place (they shrink at this scope). Integrated phasing lives in
`core-background-processing.md` §10.6 (the composite is phase TS-3; Fit
import TS-4 and folder upload TS-5 are the first consumers).

**TS-3 IMPLEMENTED 2026-07-24** — §5's `BackgroundTask` slice is code (the
`AsyncTask`/`StreamTask` unification remains future): `TaskSpec<R>` (`:core`) +
`TaskService` (`register`/`advance`/`transition`) + `BackgroundTaskHandle<R>` +
`withTask` riding the **Phase-L2 cancellable envelope** as its carrier (the §1
table's two mechanisms now compose instead of competing: the Stream-style
in-band id for lifecycle ops, the L2 envelope for constituent-call tagging —
and task-cancel can cancel an in-flight constituent call). As-built pins in
`core-background-processing.md` §10.6 TS-3.

**Scope when picked up:** `:core` (`Interactor` hierarchy, a new `Task`
composite family + generic lifecycle ops), `:core-common`
(`DefaultInteractorProvider`, `ProxyGateway`, transports), and a migration of
`Stream` + the Phase-L2 `TaskHandle` path onto the unified shape. Not a feature
change — a runtime unification.

---

## 1. The observation

`TaskHandle` (Phase L2) and `Stream`/`EndStream` solve the *same shape*:
"a long-lived operation + a client-minted, **caller-hidden** id + a later
lifecycle action (cancel / end) addressed by that id." They solve it
differently:

| | id carried by | start call | lifecycle action | new transport surface |
|---|---|---|---|---|
| **`Stream`** | a normal op **param** (`Stream.Params.STREAM_ID`), minted by `StreamRo` | normal `call()` | a **sibling op** `EndStream(STREAM_ID)` via normal `call()` | **none** — proxy-side `ListStream*Implementation` keeps its own id→stream registry |
| **Phase L2 `TaskHandle`** | the **wire envelope** (`callCancellable(req, taskId)`) | a dedicated cancellable frame | a dedicated **out-of-band RPC** `cancel(taskId)` (`oneway` AIDL / desktop `TYPE_CANCEL`) | `callCancellable` + `cancel` on every transport (AIDL, socket, JS) |

Both hide the id from the caller (`TList.flow()` never exposes `streamId`;
`TaskHandleImpl` wraps it). `Stream` is the codebase's *own precedent* for
doing this with **zero bespoke transport surface** — just (a) an id on the op,
(b) a sibling lifecycle op, (c) the proxy's id→work registry.

## 2. The idea

Model every async operation's result as a first-class **`Task` composite**
that the op returns, with the lifecycle expressed as **generic operations whose
receiver is that Task** — schema-first, routed through the normal `call()`
path, the id hidden inside the composite exactly as any composite's key is:

- `AsyncTask<T>` — for `IoOperation`. Lifecycle: `await(): TResult<T>`.
- `BackgroundTask<T>` — for `BackgroundOperation`. Lifecycle: `await`,
  `cancel`.
- `StreamTask<T>` — for `StreamOperation`. Lifecycle: element callback +
  `end`.

This is the **data mirror of the existing sealed `Interactor` hierarchy**
(`TaskInteractor` / `CancellableTaskInteractor` / `StreamInteractor`). It would
let Phase L2 *shed* its bespoke wire surface (`callCancellable`, `oneway
cancel`, `TYPE_CANCEL`) — the very thing that made it a four-PR cross-transport
slog — collapsing cancellation into "call `cancel` on the returned
`BackgroundTask`," which the architecture already routes, types, serialises,
and dispatches. Of the two framings the maintainer raised, the `Task`-composite
one is preferred over "the `BackgroundOperation` trait carries a field
reference": the latter is still an ad-hoc param, only indirected.

## 3. Why it is not folder-upload-3a, and not a quick fold-in

It reopens three landed/precedent areas (the `Interactor` model, the
just-landed Phase L2 across all transports + in-process carve-out + the §7.5
pairing migration, and `Stream`). Folding it into a feature PR would violate
"minimal existing changes" and the doc-first norm, and would bet the
high-value, ready-now 3a deliverable on a speculative core change. Hence: 3a
ships on Phase L2 *as landed* with contained new `BackgroundOperation` upload
variants (`drive-folder-upload.md` §5c); this unification is its own track.

## 4. The two hard problems (must be designed before any code)

**4.1 Result delivery / double round-trip.** An `IoOperation` today is one
round-trip: the call blocks server-side and the real `TResult<T>` returns on
that same response. Phase L2's client-minted-id-in-envelope was chosen
*specifically* to keep that one-call shape (its own impl notes: it "avoids the
binder double-return and a back-channel result route"). "Return a `Task`, then
`await(Task)`" is structurally **two** round-trips (register+return-id, then
fetch-result) and forces the proxy to **hold completed-but-unawaited results**
keyed by id — a new lifetime/GC concern `Stream` sidesteps by pushing, never
holding. Collapsing back to one call by blocking the first call until done and
carrying the result *inside* the returned `Task` re-derives Phase L2. This
applies to **every** `IoOperation` (every file upload, every op), so the
answer here is load-bearing, not a detail.

**4.2 Cancel-channel out-of-band-ness.** `cancel(Task)` as a normal op shares
the `call()` lane and can be serialised behind a blocking `await`/long call on
single-lane transports. `Stream` dodges this (callback channel, no blocking
call held); `BackgroundTask.cancel` must re-answer it, and the honest answer is
it probably still needs *something* out-of-band for "cancel during one long
call" — i.e. it relocates Phase L2's `oneway cancel` rather than removing it.
The folder-upload case tolerates in-band cancel (per-file calls are short; the
loop sits between them — `drive-folder-upload.md` §5c) but the general case
does not.

A credible design must answer 4.1 (where the result lives; one vs two
round-trips for the common IO case) and 4.2 (what, if anything, stays
out-of-band) *on paper, with the maintainer*, before code — the same bar the
other `:core` refactors cleared.

**Re-scope 2026-07-24 (with §5):** both problems shrink at the §5 scope.
**4.1** dissolves because plain `IoOperation`s keep their one-shot shape —
only *task* ops (`register`, the lifecycle ops) return/receive the composite, and
a task is long-lived by definition, so "two round-trips" is its natural
shape; the "every file upload pays a double round-trip" objection no longer
applies. The completed-but-unawaited-result lifetime question remains but is
bounded by the task lease (§6). **4.2** is answered by *relocation*: the
landed Phase-L2 `oneway cancel` frame stays as the transport under the generic
`cancel(Task)` op, so cancel-during-long-call keeps an out-of-band path
without new surface. The full `Stream`→`StreamTask` migration stays deferred
(§7); §5–§6 need only `BackgroundTask`.

## 5. The composition DSL — a `BackgroundTask` from multiple `IoOperation`s (added 2026-07-24)

The original write-up specced the composite family but left the *composition*
question implicit: how does a bulk flow whose orchestration loop **cannot
leave the client process** (folder upload's SAF tree grant, Fit's
Health-Connect client) become *one* task? The answer is a small request-DSL
surface on a new generic `TaskService`, taking a **`TaskSpec` composite**:

```kotlin
// :core — schema composite describing a task; R is a PHANTOM result-type
// parameter (Kotlin-side only, the chain-DSL Entity<E>/TList<E> precedent —
// nothing on the wire).
interface TaskSpec<R : Data?> : Composite

// :file-service — one small typed subtype per bulk-task kind
// (create(...) companion, DTO internal, per the house convention):
interface FolderUploadTaskSpec : TaskSpec<FolderUploadSummary> {
    val parentPath: TString
    val totalItems: TNumber
    companion object {
        fun create(parentPath: String, totalItems: Int): FolderUploadTaskSpec
    }
}
```

```kotlin
// 1. Register the task — one op, returns the composite. Client-minted
//    taskId (the Phase-L2 precedent), id hidden inside the composite,
//    Stream-style. Inference ties spec → result via the phantom R:
val task = client.taskService.register(
    FolderUploadTaskSpec.create(parentPath, listing.entries.size)
)   // : BackgroundTask<FolderUploadSummary>

// 2. The orchestration loop stays client-side; every constituent
//    IoOperation call issued inside withTask joins the task:
withTask(task) {
    for (entry in listing.entries) {
        fileSystem.uploadFile(parentPath, entry.name, bytes)
        task.renewLease()            // heartbeat only — progress is a domain concern
    }
}

// 3. Terminal:
task.complete(summary)               // typed by the same R
// or task.fail(error) / task.cancel()
// Observers: task.await(), task.progress(): Flow<TaskProgress>
```

Load-bearing points:

- **`TaskSpec` is a composite, deliberately** — the Layer-2 sibling of
  Layer 3's `ScheduleItem` (`ScheduleSpec` + `ConstraintSpec` + `RetryPolicy`
  already describe *deferred* work as data; `TaskSpec` describes *live* work
  the same way; the name follows that family). Being wire-serialisable buys:
  the proxy `TaskRegistry` stores a real row, the FGS notification renders
  **proxy-authoritative** content from it (the proxy/client isolation rule
  for notifications), and a future durable Layer-3 task is this composite
  persisted with a `ConstraintSpec` attached — the layers converge instead
  of forking. ⚠ Keep `R` **phantom**, never a schema field — the preference
  service tried `Setting<V>` and retreated to a non-parameterised marker
  (`DataFactory.collectFields` double-walk; a `typeVar` value field that
  never deserialises). A `GenericTaskSpec(kindId, …)` escape hatch may exist
  for ad-hoc tasks; typed subtypes are the primary surface.
- **`register`, not `begin`/`start`** — the op *registers* the task with the
  `TaskRegistry` (row created, lane opened, lease started); the work itself
  is the client loop that follows, which no `TaskService` verb should claim.
  (`open` was the runner-up but pairs with `close`, not
  `complete`/`fail`/`cancel`; `submit` falsely implies the proxy executes;
  `create` collides with the composite-companion idiom.)
- **Lifecycle ops are generic ops with receiver `BackgroundTask`** (§2
  unchanged): `renewLease`, `complete`/`fail`/`cancel` (one `transition` op),
  and `await` (future). No new transport surface; Phase-L2 `oneway cancel`
  underneath (§4.2).
- **Progress is deliberately NOT in the generic API** (maintainer decision,
  2026-07-24, superseding the first-cut `advance(completed)`): progress is
  domain-shaped — bytes-of-bytes for a blob upload, n-of-m for a multi-file
  task, a percentage where raw counts (4 000/20 000) stop meaning anything to
  a user — so a generic count would bake ONE model into every consumer.
  The clean line: **typed `TaskSpec` subtypes may carry any domain fields;
  the generic registry/row interprets none of them**, and each consumer's
  own progress surface renders progress (as both current consumers already
  do). The generic op is a pure lease heartbeat (`RenewTaskLease`). Escape
  hatch if a proxy-authoritative display is ever wanted: an opaque
  domain-defined progress composite on the heartbeat — deferred.
- **`withTask(task) { }` is the join mechanism** (Q-DSL-1 below): a
  top-level function (the `withContext` / `withTimeout` idiom; in-repo
  precedent `withEvaluationScope`) that stamps the taskId onto every op call
  issued inside the block, carried to the proxy as ordinary call metadata and
  threaded through `ExecutionContext` to `saveData` — that is what the sync
  lane keys on (`core-background-processing.md` §10.3, D3). Constituent ops
  stay ordinary one-shot `IoOperation`s; the color-blind rule
  (`schema-dsl.md` §5) is preserved — they never return a `Task`.
- **Proxy-side `TaskRegistry`:** taskId → task row (state `RUNNING` /
  `CHECKPOINTED` / `DONE` / `FAILED` / `CANCELLED`, progress counters,
  result). Task rows are **local-only in v1** (not `Syncable`; cross-device
  task visibility is deferred). Durability is **Layer 2**: the task dies with
  the proxy; recovery = re-`register` + consumer idempotency (both first
  consumers already have it). A durable Layer-3 task is TS-6 / future.
- **Lease:** `register` opens a lease renewed by any constituent call /
  `advance`; expiry (client died mid-loop) transitions the task to `FAILED`
  and auto-checkpoints its sync lane so staged deltas are never stranded
  (`core-background-processing.md` §10.3 D6/D9).

**Q-DSL-1 — RESOLVED 2026-07-24 → (a) `withTask`.** Today's terminal
request-DSL extensions execute immediately
(`suspend fun … = ….asTaskInteractor().execute()`), so a post-hoc
`.inTask(task)` cannot chain after them. Options considered: **(a) a scoped
context — chosen**: `withTask(task) { }` installs a client-side
`CoroutineContext` element the interactor provider reads (zero churn on the
~dozens of existing extensions; the taskId survives suspend points for
free); (b) per-op overloads with a task param (invasive); (c) restructure
terminals to builder + `.execute()` (reopens the settled ADR 0012 surface).
Naming settled with the maintainer: **`withTask`** over `task.scope { }`
(names the meaning, not the mechanism; mirrors `withContext`/`withTimeout`
and the repo's `withEvaluationScope`) and over literal `withContext(task)`
(would work if the client handle were a `CoroutineContext.Element` — kept as
the *implementation* under `withTask`, rejected as the *surface* for
discoverability and to avoid coupling the handle type to coroutine
machinery). Deliberately not exactly-once: joining one task from several
`withTask` blocks is legal (e.g. a retry pass after conflict resolution).

## 6. Liveness + sync-lane binding (added 2026-07-24)

The `core-background-processing.md` §10 equation, made structural:

> **1 `BackgroundTask` == 1 liveness op == 1 sync lane == 1 checkpoint block
> per checkpoint == N content-plane blobs.**

- **Liveness:** the proxy's `TaskRegistry` live-task count feeds the same
  additive `StateFlow<Int>` that `ForegroundTaskLiveness` /
  `BackgroundLiveness` already consume (§L2-DRAIN) — a task in flight holds
  the FGS / daemon liveness with no per-feature bridge. Client-side,
  `NotificationLiveness` observes `task.progress()` instead of per-feature
  controller state, collapsing `FolderUploadLiveness` and the Fit-import FGS
  bridge into one pattern (the notification content mapper stays per-feature).
- **Sync lane:** `register` opens the lane keyed by taskId; constituent saves
  stage into it; cadence + `complete`/`fail`/lease-expiry checkpoint and
  close it. Wire + emit-boundary design, open decisions, and phasing:
  `core-background-processing.md` §10.2–§10.6. The composite itself is phase
  **TS-3** there; it intentionally lands *after* the wire primitive (TS-1)
  and sender lane (TS-2) so the lane is testable without the DSL.

## 7. Linkage / sequencing

- **Phase L2** (`core-background-processing.md`) is the current Layer-2
  runtime; this proposal would re-express it as `BackgroundTask`. Until then
  Phase L2 stands as landed.
- **`Stream`** is the existing precedent for the in-band id+sibling-op pattern;
  it would migrate to `StreamTask` (this is also the cleanest eventual home for
  the parked `Stream` schema-trait dispatch item in
  [ADR 0012](../adr/0012-interactor-pattern.md)).
- **`drive-folder-upload.md` Phase 3a** is the first consumer that *wanted*
  this and instead used contained Bg-op variants; it would simplify to the
  generic surface once this lands.
- Pick-up trigger: when the next cancellable/long-running op surface would
  otherwise add more bespoke wiring, or when the maintainer prioritises the
  Phase-L2 wire-surface reduction. **Fired 2026-07-24** — the task-scoped
  sync lane (`core-background-processing.md` §10) is that surface; see the
  status header.

## 8. Non-goals

- Renaming `BackgroundOperation` (see `core-background-processing.md` §9).
- Changing the *threat model* — this is a runtime shape, same ciphertext, same
  relay contract.
- Blocking any feature work: every current async surface keeps working on the
  existing `Interactor` hierarchy until (and only if) this lands.
