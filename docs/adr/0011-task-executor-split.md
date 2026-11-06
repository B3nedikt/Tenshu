# ADR 0011 — `TaskExecutor` split: sync vs async as distinct execution domains

- **Status:** Accepted, implemented (landed April 2026)
- **Date:** 2026-04
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

Pre-this-work, `TaskExecutor` had a single `suspend fun execute(task: TList<Data>):
TResult<Data>` driving the evaluation loop — both pure-logic chains (`equalTo`,
`ifEmptyError`) and chains that suspended on IO (`Login`, `PushBlock`,
`StartPairing`) went through the same suspend method. The platform's "Color-Blind
Interface Rule" had let chains stay non-suspend at the operation interface level
(see `docs/schema-dsl.md` §5 §"The Color-Blind Interface Rule"), but at the *executor*
level everything was async because the loop had to handle the worst case.

Three pains:

1. **No compile- or runtime-time safety against accidental IO in sync paths.**
   A chain composed of pure operations could silently grow an IO operation (a
   refactor that adds `Login` to a previously-pure chain), and the executor
   would just run it inside whatever coroutine context the caller happened to
   be on. The Interactor Pattern ([ADR 0012](0012-interactor-pattern.md)) introduced the
   `IoOperation` / `BackgroundOperation` / `StreamOperation` *trait* layer, but
   the executor itself didn't enforce sync-only chains anywhere.
2. **No optimisation opportunity for remote clients on pure chains.** A
   client-side `RemoteBinaryTaskExecutor` had to round-trip every task to the
   proxy because the suspend executor didn't distinguish "this is a pure
   computation I could just run here" from "this needs the proxy's database."
   Every `equalTo`, every `ifEmptyError`, every pure-logic chain paid IPC
   cost.
3. **`suspend` everywhere couples the request DSL to the coroutine machinery
   on the read side.** Schema-DSL extensions for non-terminal operations
   (`Find`, `Filter`, `Select`, ...) are *non-suspend* by design (they return
   a Tenshu type synchronously for further composition — `schema-dsl.md` §5
   §"Non-Terminal Extensions"). Terminal extensions for genuinely-async
   operations (`Login`, `Get`) are suspend. The executor under both was
   suspend regardless, which meant the trait/extension split made compile-time
   promises that the runtime didn't keep.

The fix recognises that **sync and async are two execution *domains*, not just
function colors**. A pure chain has a different runtime contract than an IO
chain; the executor surface should reflect that.

## Decision

Split `TaskExecutor` into two segregated interfaces, with one extending the
other:

```kotlin
interface TaskExecutor {
    /** Sync evaluation. Throws SynchronousViolationException on an async gap. */
    fun execute(task: TList<Data>): TResult<Data>
}

interface AsyncTaskExecutor : TaskExecutor {
    /** Full evaluation loop. Drives AsyncReference interruptions. */
    suspend fun executeAsync(task: TList<Data>): TResult<Data>
}
```

Both `LocalTaskExecutor` and `RemoteBinaryTaskExecutor` implement
`AsyncTaskExecutor`. API surface + per-executor behaviour live in
[`docs/agent.md`](../agent.md) §4 "Client & Environment Strategy". This ADR
captures the rationale.

Five anchor decisions:

1. **Sync vs async are two execution domains, not just function colors.** A
   pure chain has a different runtime contract than an IO chain. The
   executor surface acknowledges that with two interfaces; consumers that
   want sync-only safety call `execute`, consumers that need the full loop
   call `executeAsync`.
2. **`AsyncTaskExecutor extends TaskExecutor`, not separate hierarchies.**
   Every async executor *can* run pure chains synchronously — there's no
   case where you have `executeAsync` but not `execute`. The inheritance
   models that. Consumers that want either-or take `AsyncTaskExecutor`;
   consumers that *must* be sync take `TaskExecutor`.
3. **`SynchronousViolationException` is the runtime safety guard.** The
   sync path throws when evaluation hits an `AsyncReference` (the
   IO-gap signal). Carries the operation id + an actionable message. This
   makes "I declared this chain sync but a pure operation grew an IO
   dependency" a fail-fast at the first attempt, not a silent thread hop
   inside whatever coroutine context the caller was on.
4. **`PureOperationRunner` enables zero-latency sync on remote clients.**
   `RemoteBinaryTaskExecutor.execute` attempts the chain locally via
   `PureOperationRunner`; if every operation is `PureOperation`-marked,
   the call returns without IPC. If any operation needs the proxy,
   `SynchronousViolationException`. Pure-logic chains on the client side
   never pay IPC cost.
5. **`BackgroundInteractor` bridges through the proxy's `serviceScope`.**
   `DefaultInteractorProvider` retrieves `serviceScope` from
   `ExecutionContext`; `BackgroundInteractor.execute()` calls
   `serviceScope.launch { executeAsync(...) }` and returns the `Job`.
   Essential for `StartPairing` — the handshake must continue even if the
   client's interactive coroutine is cancelled or timed out.

## Consequences

- **Two interfaces in `:core` instead of one.** `TaskExecutor` (sync) and
  `AsyncTaskExecutor` (suspend) — every existing call site picked one or
  the other.
- **`SynchronousViolationException` exists** with an actionable message:
  *"Synchronous Context Violation: Operation [ID] attempted to perform IO
  or suspend in a sync-only execution domain."* Signals: either mark the
  operation `IoOperation` and use it via `TaskInteractor`, or the chain
  is incorrectly mixing pure + IO.
- **`PureOperationRunner` runs on the client side.** Pure chains on
  `RemoteBinaryTaskExecutor.execute` complete in microseconds; only chains
  with at least one IO operation pay the IPC round-trip.
- **Test migration completed** across five service modules:
  `:file-service-proxy`, `:file-service-client`, `:identity-service-proxy`,
  `:notification-service-proxy`, `:control-shared`. Tests moved from
  `executionContext.execute(...)` to `executeAsync(...)` or `runBlocking
  { executeAsync(...) }` depending on what the test was actually
  exercising.
- **`ProxyGateway.call`'s `runBlocking(requestScope.coroutineContext)`
  remains incompatible with `runTest`'s `TestDispatcher`.** Tests that
  exercise the handleRequest path (`ProxyFileServiceUnitTest`,
  `CreateFolderNotificationUnitTest`, `PairingHandshakeIntegrationTest`)
  use plain `runBlocking` instead. Worth flagging — the
  TestDispatcher-vs-`runBlocking` incompatibility is a known seam.

## When to revisit

Re-open when one of these is true:

1. **A consumer wants async-only without the sync interface.** Today
   every async executor inherits `execute`; the rationale is that running
   a pure chain synchronously is always a safe optimisation. If a future
   executor genuinely cannot support sync (e.g. one that requires a
   coroutine context to construct anything), splitting `AsyncTaskExecutor`
   into a sibling-not-subtype of `TaskExecutor` would be cleaner. No such
   case today.
2. **`ProxyGateway.call`'s `runBlocking` becomes the bottleneck.** The
   handleRequest path uses `runBlocking(requestScope.coroutineContext)`,
   which doesn't play with `runTest`'s `TestDispatcher`. The
   incompatibility is acceptable today (the affected tests are
   integration-shaped and use plain `runBlocking`), but if more tests
   need to run on `TestDispatcher` the dispatch shape probably needs to
   change.
3. **`PureOperationRunner`'s heuristic isn't good enough.** Today it walks
   the chain and checks every operation for the `PureOperation` trait. If
   pure-ness needs to be a runtime decision (e.g. "this chain is pure on
   pre-cached data, async otherwise"), the runner would need a richer
   classification step. No such case today.

## Alternatives considered

- **Keep `TaskExecutor` as a single `suspend` interface; add a sync
  wrapper for pure chains.** The pre-work shape with a wrapper bolted on
  top. Rejected — the wrapper is essentially what `TaskExecutor.execute`
  becomes; making it the primary interface (rather than an afterthought
  layered over the suspend version) is the difference between "sync is a
  first-class domain" and "sync is a special case of async we tried to
  optimise." The runtime safety guard (`SynchronousViolationException`)
  also wants to be on the primary interface, not the wrapper.
- **No subtype relationship — `TaskExecutor` and `AsyncTaskExecutor` as
  siblings.** Considered for symmetry. Rejected per anchor decision 2 —
  every async executor *can* run pure chains synchronously; the
  inheritance models that correctly. The consumer pattern (take whichever
  surface you need) works cleanly with inheritance.
- **Move sync-vs-async distinction to the operation trait layer only,
  leave the executor surface alone.** The `IoOperation` /
  `BackgroundOperation` / `StreamOperation` traits already exist. Rejected
  — the trait layer is *declarative* (this operation does IO); the
  executor layer is *runtime contract* (this call won't hit IO). Both
  matter; the trait layer doesn't enforce by itself, and the executor
  layer has no way to communicate the failure mode without runtime
  classification.
- **Run the pure-chain optimisation server-side (every executor) instead
  of just on the remote client.** Considered for uniformity. Rejected as
  premature — server-side, the cost difference between the sync and async
  paths is small (no IPC), and the existing `evaluateCallstack(
  allowDeferring = false)` already covers the optimisation indirectly.
  The big win is on the client side where every pure chain previously
  paid the round-trip.
- **Re-use a single `execute(suspend Boolean)` parameter instead of two
  methods.** Rejected — Kotlin doesn't have a clean way to dispatch
  suspend-vs-non-suspend on a single function, and conditional `suspend`
  is exactly what the "Color-Blind Interface" rule explicitly rejects.
  Two methods give you the right type signature for each consumer.

## References

- `docs/agent.md` §4 "Client & Environment Strategy" — authoring reference
  for the sync vs async split, the executor types, and `PureOperationRunner`.
- `docs/schema-dsl.md` §5 — the Color-Blind Interface Rule that drove this
  design.
- [ADR 0012](0012-interactor-pattern.md) — the Interactor Pattern (largely landed) that
  this work sits underneath. The trait layer (`IoOperation`,
  `BackgroundOperation`, `StreamOperation`) is the declarative side; this
  ADR is the runtime contract side.
- `core-common/.../request/TaskExecutor.kt` — both interfaces, and
  `SynchronousViolationException` (declared in the same file, not its own).
- `core-common/.../proxy/LocalTaskExecutor.kt` — proxy-side dual implementation.
- `core-common/.../transport/RemoteBinaryTaskExecutor.kt` — client-side dual
  implementation with `PureOperationRunner` short-circuit.
- `core/.../PureOperationRunner.kt` (interface) and
  `core-common/.../PureOperationRunnerImplementation.kt` — the client-side
  pure-chain runner.
