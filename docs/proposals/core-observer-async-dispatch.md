# `ObserverRegistry` async dispatch

**Status:** Draft / not started. Author: Benedikt + Claude (follow-up to
[ADR 0008 — `ExecutionContext` decomposition](../adr/0008-execution-context-decomposition.md)
Phase 4 `ObserverRegistry` extraction).

**Scope:** `ObserverRegistry.onDataSaved` / `onDataDeleted` dispatch shape in
`:core-common`. Surrounds 5 production `DataObserver` implementations and
the ~24 production call sites of `executionContext.saveData(...)`.

**Wire format:** unchanged. Semantics: changes by design — the question this
proposal asks is *what relaxation is acceptable*, not whether to keep the
current blocking shape.

---

## 1. Today's shape

`ObserverRegistry.onDataSaved(data)` is a non-suspend method that calls
`observers.toList().forEach { it.onDataSaved(data) }`. Five observers run
back-to-back, in registration order, on the calling thread of `saveData`:

| Observer                            | Service                    | Work on `onDataSaved`                                                             |
|-------------------------------------|----------------------------|-----------------------------------------------------------------------------------|
| `SyncObserver`                      | `:sync-service-proxy`      | Schema lookup → AEAD encrypt block → enqueue into outbound mailbox + HLC stamp    |
| `TombstoneRegistry`                 | `:sync-service-proxy`      | Schema lookup → if `Tombstoned` trait, record tombstone hash                      |
| `ParentFilesRelationMaintainer`     | `:file-service-proxy`      | If `File`, look up parent folder DataId, `database.addToArray(folder.files, …)`   |
| `DeviceRelationMaintainer`          | `:identity-service-proxy`  | If `Device`, `database.addToArray(identityService.devices, …)`                    |
| `NotificationRelationMaintainer`    | `:notification-service-proxy` | If `Notification`, `database.addToArray(notificationService.items, …)`         |

Most work is CPU-bound and bounded (a handful of map lookups + maybe one
`addToArray`). `SyncObserver` is the heaviest — it does an AEAD encryption
per syncable field/property. The relation maintainers each fire a
`database.addToArray` which can in turn fire stream listeners (those *are*
already async via `sessionScope.launch` in `StreamRegistry`).

`saveData` is non-suspend (`fun`) and is called from 24+ production sites.

---

## 2. The honest question

"Independent observers should run in parallel" is reasonable in principle —
none of the five observers takes input from another, none reads what
another wrote during the same `saveData` call. But the case for parallel
dispatch in *the current shape* is weaker than it looks:

- **Per-observer work is small.** A handful of map lookups + an
  `addToArray`. Coroutine launch + join overhead on JVM is ~µs; for 5
  observers doing ~10µs of work each, parallelism's ceiling is ~5× speedup
  on a ~50µs path, minus ~5× launch overhead. Net wash or worse.
- **`SyncObserver`'s crypto is per-syncable-field, not per-observer.** A
  saved entity with N syncable properties does N AEAD seals serially
  *inside* `SyncObserver.onDataSaved`. Parallelising across observers
  doesn't unlock that — parallelising *within* `SyncObserver` would. Out of
  scope here.
- **HLC ordering matters for `SyncObserver`.** Each generated sync block
  gets an HLC timestamp. Two parallel `saveData` calls could race the HLC
  monotonicity check inside `SyncManager.nextHlc()`. The current
  serial-per-context dispatch keeps the producer side trivially ordered;
  parallel dispatch *across* a single `saveData`'s observers is fine, but
  parallel dispatch *across* multiple `saveData` calls (the more impactful
  speedup) needs explicit HLC coordination.
- **Bulk-save patterns aren't on the hot path today.** The 24 call sites
  are mostly one-saveData-per-operation. The shape that would benefit most
  — `Folder` cascade or sync-block replay applying many entities — is
  already inside `SyncProcessor.resolveConflictAndSave` which is itself
  serial on the inbound block stream.

So the upside of parallelising the *current* 5 observers is small. The
upside grows if:

- A new observer becomes I/O-bound (writes to disk, calls network, opens a
  file descriptor cache eviction).
- The observer count grows substantially (more feature services — Places,
  Photos, Calendar per the milestones).
- A bulk-import path materialises (e.g., a user re-importing a backup) and
  saveData per item becomes the bottleneck.

None of these are true today. They're all plausible within 6–12 months.

---

## 3. Design options

If/when this becomes worth doing, the design space:

### 3.1 Fire-and-forget on a background scope

```kotlin
fun onDataSaved(data: Data, scope: CoroutineScope) {
    observers.toList().forEach { observer ->
        scope.launch { observer.onDataSaved(data) }
    }
}
```

`saveData` stays non-suspend and returns immediately after enqueueing.
Caller never blocks on observer work.

**Pro:** Smallest surface change. No `suspend` propagation through 24 call
sites. Latency-of-saveData becomes "schedule 5 coroutines."

**Con:**
- Loses error backpressure entirely — an observer throwing in the
  background only surfaces via the coroutine exception handler. Today an
  observer throw would propagate up through `saveData` (though no observer
  currently throws — this is theoretical).
- Tests become non-deterministic. `executionContext.saveData(file); val
  folder = database.getData(folder.id)` no longer reliably sees the
  maintainer's `addToArray` — would need explicit awaits.
- Order across observers is no longer registration order. Today nothing
  depends on registration order, but the property isn't documented.
- Need to choose a scope. `sessionScope`? `executionContext.sessionScope`
  is already where stream listeners launch, and it gets cancelled on
  session destroy — appropriate.

### 3.2 Suspend `saveData` with `coroutineScope { launch }`

```kotlin
suspend fun onDataSaved(data: Data) = coroutineScope {
    observers.toList().forEach { observer ->
        launch { observer.onDataSaved(data) }
    }
}
```

`saveData` becomes `suspend`. Awaits all observers before returning.

**Pro:** Structured concurrency. Errors propagate naturally. Tests stay
deterministic ("after saveData returns, all observers have run").

**Con:** Viral `suspend` propagation through 24 call sites. Some of those
are themselves not currently suspend (e.g., `OperationImplementation.execute`
on `SyncOperationImplementation` is non-suspend by design — the Interactor
pattern's `executeAsync` is the suspend path). Migrating saveData to
suspend would either force the suspend-everywhere pattern or require an
explicit "use the async dispatcher" opt-in.

### 3.3 Per-observer mailbox

Each observer gets its own `Channel<Event>` and a dedicated coroutine that
drains it.

```kotlin
private data class ObservedEvent(val kind: Kind, val data: Data?, val dataId: DataId?)
private val channels = mutableMapOf<DataObserver, Channel<ObservedEvent>>()

fun add(observer: DataObserver, scope: CoroutineScope) {
    val channel = Channel<ObservedEvent>(Channel.UNLIMITED)
    channels[observer] = channel
    scope.launch {
        for (event in channel) {
            when (event.kind) {
                Kind.SAVED -> observer.onDataSaved(event.data!!)
                Kind.DELETED -> observer.onDataDeleted(event.dataId!!)
            }
        }
    }
}
```

`saveData` enqueues into each channel and returns. Observers process
serially *relative to themselves* (so HLC ordering inside `SyncObserver`
holds) but in parallel *across observers*.

**Pro:**
- Decouples failure modes: one slow observer doesn't block others, but a
  single observer still sees events in order.
- Bounded queues give natural backpressure (`Channel.BUFFERED` with a
  rendezvous fallback).
- Natural fit for I/O-bound observers.

**Con:**
- Significantly more machinery. Per-observer scopes, channel lifecycle,
  backpressure semantics to specify.
- `clear()` and `destroy()` semantics need rethinking (close channels,
  cancel drainers).
- Memory: one channel + one coroutine per observer per session. With 5
  observers × N sessions, not large but non-zero.
- Loses the synchronous "after saveData, the maintainer has updated the
  folder array" guarantee that current tests rely on.

### 3.4 Selective per-observer policy

Tag each observer at registration time:

```kotlin
enum class DispatchPolicy { Synchronous, Background, Mailbox }
fun add(observer: DataObserver, policy: DispatchPolicy = Synchronous)
```

`onDataSaved` dispatches synchronously for `Synchronous` observers (current
behaviour, fast path) and offloads `Background`/`Mailbox` observers to a
scope.

**Pro:** Migrate one observer at a time. Today's 5 stay `Synchronous` until
they're explicitly opted in. Backwards-compatible.

**Con:** API-surface growth. Three semantics to document and reason
about. Mixing in the same dispatch means partial-result complications (one
synchronous observer fires, another async one hasn't yet — what does
"saveData completed" mean?).

---

## 4. Recommendation

**Defer.** The current implementation is correct, simple, and not a
measured bottleneck. The Phase 4 observer registry already keeps the
snapshot-then-iterate invariant in one place — if/when this needs to
change, the registry is the single point to change.

**Pick this back up when one of these is true:**

1. A profile run shows `saveData` latency dominated by observer work on a
   real user path (file upload, bulk import, sync replay).
2. A new observer is added that does I/O (disk write, network call,
   filesystem flush). At that point §3.3 (mailbox) for *that observer
   only* — leave the others synchronous.
3. The observer count grows past ~10 and per-saveData latency becomes
   measurable. At that point §3.1 (fire-and-forget) might pay for itself.

The path of least resistance, when the time comes, is §3.4 (selective):
register synchronous observers as before, opt new ones into background
dispatch. That keeps the existing 5 observers' semantics untouched and
contains the design uncertainty to the opted-in subset.

---

## 5. Non-goals

- **Parallelising within a single observer.** `SyncObserver` doing N AEAD
  seals serially across an entity's syncable properties is a separate
  question (proposal: batch AEAD via a single key-schedule context, or
  parallelise via `awaitAll` on the per-property seals). Out of scope.
- **Cross-`saveData` parallelism.** Two `saveData` calls running their
  observers concurrently would need HLC coordination, observer
  reentrancy review, and probably a database transaction model. Bigger
  refactor; not this proposal.
- **Replacing `DataObserver` with a Flow-based subscription model.** That's
  a redesign of the surface, not an optimisation of the dispatcher.
  Worth its own doc if anyone wants to argue for it.

---

## 6. Why now (the meta-question)

The decomposition that landed in Phase 4 puts the dispatch policy in one
place for the first time. Until now the choice "should this be parallel?"
would have been a 5-call-site change. Today it's a one-class change. That
doesn't *justify* doing the change — it just removes one of the historical
reasons not to. Worth recording the design space while it's fresh.
