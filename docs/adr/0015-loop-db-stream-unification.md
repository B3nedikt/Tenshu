# ADR 0015 — Loop / DB / stream unification: event-driven array listeners + channel-based sync emit

- **Status:** Accepted, implemented
- **Date:** 2026-05-24
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Depends on:** [ADR 0008 — `ExecutionContext` decomposition](0008-execution-context-decomposition.md)
  (`StreamRegistry` / `ObserverRegistry`), [ADR 0014 — proxy session
  lifecycle](0014-proxy-session-lifecycle-teardown.md) (owned `sessionScope` +
  suspend `quiesce()`)

## Context

ADR 0014 fixed the *lifecycle* facet of a larger problem: the proxy's
loop / database-update / stream-management plumbing was **coarse-grained** — each
consumer re-invented its own mechanism around one root, *"rewrite the whole entity,
diff the whole array, fan out without a typed event."* Two facets remained open after
ADR 0014 (typed write provenance, `WriteOrigin`, having already landed separately):

- **Sync emit (A).** `SyncObserver.onDataSaved` did serialize + AEAD encrypt + HLC
  tick + `pendingBlocks` enqueue **synchronously inside `ExecutionContext.saveData`**.
  `saveData` didn't return until the block was encrypted and queued — a coupling that
  gave clients **no** observable invariant (the block hadn't reached the relay) yet
  forced the lock-free HLC CAS loop (anything suspending in `onDataSaved` would ripple
  `suspend` through ~24 `saveData` call sites).
- **Array listeners (B).** `DatabaseImpl.addToArray`/`removeFromArray` rewrote the whole
  `TList` entry; the `setArrayListener` callback then **reconstructed** the delta via an
  **O(n²) full-array diff + per-entry re-deserialise on every write** — even though the
  caller passed the exact `value`. The only production consumer (`StreamRegistry`)
  ignores the value and just re-resolves, so the diff was pure waste on the hot path.

## Decision

1. **Two independent phases, not one shared abstraction.** The facets are *conceptually*
   unified (both replace a coarse synchronous fan-out with a precise, typed event) but
   share **no natural mechanism**; a unified "event bus" would be premature. Done as two
   PRs under one umbrella, **B first** (lower-risk: self-contained, perf, no HLC,
   desktop-verifiable), then A (changes a semantic + test invariants).

2. **Facet B — event-driven array listeners.** `addToArray`/`removeFromArray` fire
   `onAdded(value, origin)` / `onRemoved(dataId, origin)` **directly** from the call
   site with the value the caller already holds. `DatabaseImpl` keeps a per-array
   listener map + a ref-list snapshot; targeted mutations **pre-update the snapshot** so
   the `ObjectStore.setListener` diff sees no delta and does not double-fire. That diff
   **stays as an O(n) (set-based) fallback** for array changes that bypass the targeted
   methods — a `saveData(entity)` that rewrites an embedded list field (e.g.
   `FolderMerger` only-grow), which fires with `WriteOrigin.USER` (the save-layer origin
   isn't visible at the store boundary). `WriteOrigin` is threaded onto the array
   surface, completing the enum already on `saveData`/`deleteData`.

3. **Facet A — channel-based sync emit.** `SyncObserver.onDataSaved` only
   `emitChannel.trySend(...)` (non-blocking, no I/O, no HLC). A **single consumer
   coroutine** does serialize + AEAD + HLC tick + `pendingBlocks` enqueue. `saveData` no
   longer pays that cost inline.

4. **Consumer scope = injected, defaulting to `sessionScope`.** The consumer takes a
   `coroutineScope` constructor param (mirroring `SyncScheduler`/`ScheduleDrainLoop`),
   defaulting to the session's `ExecutionContext.sessionScope`, which ADR 0014
   cancels-and-joins on teardown. The injected-param shape (not a direct
   `sessionScope.launch`) also keeps it off the Phase L2 detach-discipline static scan
   (`PhaseL2CancellationTest`). `sessionScope` is correct for **every producer today** —
   per-request saves *and* detached L2 tasks, which `ClientRegistry.deferredTeardown`
   keeps the session alive for. (Service-tier is deferred — see When to revisit #1.)

5. **`awaitEmitIdle()` is the drain-and-join join point.** A `Barrier` event through the
   FIFO single-consumer channel completes only after all prior `Saved` events are
   processed. Tests inspecting `pendingBlocks` right after `saveData` call it; bgproc
   Phase 6's headless drain will call it before releasing its wakelock — *"the triggering
   work isn't done until its emitted blocks are durably queued."*

6. **HLC CAS loop stays; buffer UNLIMITED; error sink = log + drop.** Only the *outbound*
   path is single-writer — `emitPropertyBlock` / `emit` / ingest still tick the shared
   clock — so the CAS loop in `HybridLogicalClockUtil` is **not** removed. The channel is
   `UNLIMITED` (the `onDataSaved` producer is non-suspend, can't backpressure; saves are
   bounded per operation). A serialize/encrypt failure in the consumer is logged and the
   single block dropped; the entity is already persisted locally, so its change re-syncs
   on the next save.

## Consequences

- **`saveData` is off the encrypt/HLC critical path**; the O(n²) array diff is gone from
  the hot path (StreamRegistry re-resolves without a diff/deserialise).
- **A durability invariant moved.** Synchronous emit guaranteed "block durably queued by
  the time `saveData` returns"; now it's queued only once the consumer drains. Harmless
  on the live in-process path (drains in ~ms); the headless path closes it via the
  `awaitEmitIdle()` drain-and-join contract (#5), realised with bgproc Phase 6.
- **An ordering invariant moved too.** An op that saves an entity and then emits an
  *inline* block for it — `emitPropertyBlock` / `emitFileContent`, which stay synchronous
  (#6) — used to get "entity block enqueued (and HLC-ticked) before the inline block" for
  free, because `saveData` generated the entity block inline. With the entity block now
  async, the inline block races ahead of it and gets the *lower* HLC, so the joiner
  receives e.g. file bytes before the parent `File` and the receiver's
  `FileBytesPropertyWriter` drops them (entity-before-bytes; the proactive push never
  retries). Such ops must call `awaitEmitIdle()` between the `saveData` and the inline
  emit. `UploadFileImplementation` does; any future "save then inline-emit for the same
  entity" site must too. (Caught by `FileBytesOnDemandSystemTest`'s proactive-push case.)
- **No threat-model change.** The relay still sees ciphertext only (emit still seals
  before anything leaves the process; only *when* the seal happens moved). HLC ordering
  is preserved (outbound is now single-threaded; the CAS guard against ingest-side
  concurrent advance stays). No change to A1–A4/A9, the relay data shape, or the wire.
- Both originating tech-debt entries ("Sync Decoupling", "`Database` Surface") are
  resolved.

## Amendment (2026-07-24) — the task-scoped sync lane rides the emit consumer

TS-2 of the task-scoped sync lane
([`core-background-processing.md`](../proposals/core-background-processing.md)
§10.3) confirms and extends this ADR's channel decision: lane staging,
checkpointing, and journal replay are all marshalled as events on the SAME
single-consumer emit channel, so lane state is consumer-confined (no locking)
and `awaitEmitIdle` covers it — the emit channel was "where a task-scoped
checkpoint boundary naturally lands", and now it is.

**Entity-before-bytes under staging (§10.5 D8).** The original ordering
invariant — an entity's block is HLC-ordered ahead of its content-plane bytes
via the `awaitEmitIdle` barrier — is *restated*, not weakened, for lanes: a
staged delta's HLC is ticked at stage time (before any content emit that
follows it on the consumer), and content-plane blobs are fetched by reference
only, so a blob PUT completing before the checkpoint block that references it
is invisible to receivers until the batch lands. What the lane relaxes is only
*emission latency* (the delta rides the next checkpoint instead of the next
drain tick); the HLC ordering the invariant actually protects is assigned at
stage time and unchanged. No current producer stages file-content entities
(TS-4/TS-5 will); this paragraph is the contract they inherit.

## When to revisit

1. **bgproc Phase 6 (`PlatformScheduler` / WorkManager) lands.** A headless OS wake has
   no bound session, so the consumer's `sessionScope` (#4) no longer covers it. Then
   `SyncObserver` becomes **one-per-proxy** on a service/process-tier scope, and the
   headless drain calls `awaitEmitIdle()` (drain + join) before releasing its wakelock —
   the §A.6 tier requirement + the moved durability invariant are realised together.
2. **HLC single-writer simplification.** Only after an ingest-side audit shows *all*
   clock mutations (`SyncProcessor` / `buildSignedTip` / peer-tip observation) can funnel
   through one writer; then the CAS loop could simplify to plain `var`s. Until then it
   stays (#6).
3. **Durable emit queue.** If headless emit loss is ever observed *despite* the
   await-idle contract, back `emitChannel` with a durable queue (crash-safe by
   construction, removes the drain-and-join obligation). Over-build for v1; not done.

## Alternatives considered

- **A shared "event bus" serving both facets.** Rejected (decision 1) — they share no
  natural mechanism; premature abstraction.
- **Consumer on a dedicated service-tier scope now.** Rejected (decision 4) —
  `SyncObserver` is per-session and captures the session's `ExecutionContext`, so a
  consumer outliving the session would dereference a disposed context; genuine
  service-tier needs a one-per-proxy refactor, and `sessionScope` already covers every
  current producer. Deferred to Phase 6 (When to revisit #1).
- **Delete the array diff entirely.** Rejected (decision 2) — `saveData` bulk rewrites
  (e.g. `FolderMerger` only-grow) bypass `addToArray`, so the diff is kept as an O(n)
  fallback.
- **Bounded channel with `DROP_OLDEST`.** Rejected — silently loses a sync block.
- **Remove the HLC CAS loop.** Rejected (decision 6) — ingest still advances the clock.

## References

- `core-common/.../proxy/Database.kt` + `db/DatabaseImpl.kt` — event-driven
  `addToArray`/`removeFromArray` + `setArrayListener` (direct fire + O(n) diff fallback);
  `WriteOrigin` on the array surface.
- `core-common/.../proxy/WriteOrigin.kt` — the typed provenance enum.
- `sync-service-proxy/.../SyncObserver.kt` — `onDataSaved` → channel; consumer +
  `awaitEmitIdle()`; injected `coroutineScope`.
- `sync-service-proxy/.../SyncProxyInitialization.kt` — passes the session-owned
  background scope.
- `core-common/.../transport/PhaseL2CancellationTest.kt` — the detach-discipline scan the
  injected-scope shape satisfies.
- [ADR 0014](0014-proxy-session-lifecycle-teardown.md) — owned scope + quiesce (the
  lifecycle this builds on). [ADR 0008](0008-execution-context-decomposition.md) —
  `StreamRegistry` / `ObserverRegistry`.
- `core-proxy/proxy-agent.md` §2 "`ExecutionContext` and its collaborators" — the
  `Database` / observer surface conventions.
- `docs/proposals/core-observer-async-dispatch.md` — adjacent (generic observer dispatch
  policy; deferred), distinct from this sync-emit decoupling.
