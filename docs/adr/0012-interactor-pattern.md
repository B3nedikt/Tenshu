# ADR 0012 — Interactor pattern: a unified execution adapter for the request DSL

- **Status:** Accepted, implemented. ⚠ The one open item recorded here — the
  `EvaluateCallstack` string-literal stream check — is **closed**: it now reads
  `protocol.isStream()`, which resolves through the `StreamOperation` trait.
- **Date:** 2026-04
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Sits underneath:** [ADR 0011 — `TaskExecutor` split](0011-task-executor-split.md)
  (Interactor uses the executor layer it documents)

## Context

Pre-this-work, an operation that performed IO (e.g. `SendNotification`, `Login`)
had two unattractive options:

1. **Declare `suspend` on the operation interface itself.** Forces every
   consumer of the operation to be `suspend` — even pure chain construction
   (`.equalTo`, `.filter`, …) — because the consumer is calling a `suspend`
   function. The "Color-Blind Interface Rule" (\§5 of the schema DSL)
   explicitly rejects this for the same reason: chain construction is pure,
   and contaminating it with `suspend` propagates execution-mode commitments
   into pure composition.
2. **Declare a non-suspend signature and hide the asynchrony in a cast.**
   `Stream` did this: `Stream.stream()` returned `TResult<Data?>`, but the
   runtime payload was actually a `StreamInteractor<T>` the DSL cast to
   (`Stream.kt:82`). Works, but the cast is brittle and the type system
   tells a lie.

Neither matched the truth. The truth is:

- The **operation interface** describes *what* it does. It should not commit
  to *how* the result is delivered (sync / suspend / background / stream).
- The **DSL extension** is the user-facing entry point. It's the natural place
  to commit to an execution mode — that's where the call-site code can choose
  to be `suspend`, return a `Flow`, return a `Job`.
- The **bridge** between the two is the platform's job, not the operation's.
  It already was for streams (`StreamInteractor`); generalising it to the
  other three execution modes finished the job.

## Decision

One sealed `Interactor<T>` hierarchy with four typed subtypes — one per
execution mode. One `InteractorProvider` (a `TComponent`) that picks the
subtype based on the terminal operation's schema traits. Four typed
`as*Interactor()` extensions that DSL authors call from terminal extensions.

API surface + authoring conventions live in
[`docs/schema-dsl.md`](../schema-dsl.md) §5 "Request DSL Extensions". This ADR
captures the rationale.

Four anchor decisions:

1. **Operation methods return `RequestObject`, not `TResult`.** The operation
   interface's job is chain construction. A non-suspend method cannot honestly
   return a `TResult` of an unrun IO chain; the pre-existing Stream pattern's
   `TResult<Data?>` return was an artefact we did not propagate. Every
   operation interface across `:identity-service`, `:file-service`,
   `:sync-service`, `:notification-service` migrated to this shape.
2. **One sealed `Interactor<T>` hierarchy with four mode-specific subtypes.**
   `SyncInteractor<T>` / `TaskInteractor<T>` / `BackgroundInteractor<T>` /
   `StreamInteractor<T>` share a common base. Each variant has the right
   return type for its mode — there is no shared `execute()` signature on
   the base, because the four modes genuinely return different things
   (forcing one signature would require all four to lie about cost or shape).
3. **`BackgroundInteractor.execute()` returns a `Job`.** Honest about
   lifecycle: background work outlives the request. Cancellation, completion
   handlers, structured concurrency composition all fall out of the `Job`
   return. The bridge: `DefaultInteractorProvider` retrieves `serviceScope`
   from `ExecutionContext`; the interactor calls
   `serviceScope.launch { executeAsync(...) }`. Essential for `StartPairing`
   — the handshake must continue even if the client's interactive coroutine
   is cancelled or timed out.
4. **One unified `InteractorProvider`.** The pre-existing
   `StreamInteractorProvider` was the prototype of this pattern;
   generalising it to cover all four modes meant one provider, one DSL
   story, one test rig. The provider lives on `TContext.components` because
   it needs to be reachable from any DSL extension regardless of which
   module the extension lives in.

## Consequences

- **Every IO operation migrated** to the new shape (~20 operations across
  identity / file / sync / notification services). Each: operation method
  returns `RequestObject`, `IoOperation.Schema` added to traits, DSL
  extension calls `.asTaskInteractor<T>().execute()`. Mechanical change;
  one PR per service.
- **`TTask` renamed to `TaskInteractor`**. `core/TTask.kt` →
  `core/TaskInteractor.kt`; deprecated typealias `TTask<T> = TaskInteractor<T>`
  carried for one release.
- **`Stream` refactored to fit.** `StreamInteractor<T>` gained the
  `: Interactor<T>` supertype (no method changes); `Stream.stream()` now
  returns `RequestObject`; the `flow()` extension uses
  `ro.asStreamInteractor()` instead of the old `result as StreamInteractor<T>`
  cast. `StreamInteractorProvider` deprecated and removed after the migration
  window.
- **Test rigs got sharper.** `:core-test-utils` exposes a
  `FakeInteractorProvider` that takes a `Map<RequestObject, Interactor<*>>`
  of canned answers, plus a `recordedRequests: List<RequestObject>` for
  assertions. DSL extension tests and ViewModel tests stop mocking
  `TenshuClient.taskExecutor` and substitute the provider instead — a
  sharper interface for the assertion.
- **Cooperative cancellation only at the language level.** `TaskInteractor`'s
  `suspend fun execute()` cooperates with the caller's coroutine
  cancellation, matching idiomatic Kotlin coroutines. Cross-process
  cancellation (client app cancels its scope; proxy keeps running the work
  to completion) is **not** addressed by cooperative cancellation alone —
  it needs a wire-level mechanism. Tracked in
  [`docs/tech-debt.md`](../tech-debt.md) under *Wire-Level Task Cancellation*;
  not blocking for this work.
- **One open item: `EvaluateCallstack` line 51's string-literal stream
  check.** The engine still identifies stream operations by hardcoded id
  (`"tenshu/core/collection/list/stream/start"`) rather than via the
  `StreamOperation` schema trait. The trait exists; this is a one-line fix
  with no semantic change. Tracked in `docs/tech-debt.md`.

## When to revisit

Re-open when one of these is true:

1. **The `EvaluateCallstack` stream check migrates to the trait.** Closes
   the one open item. Mechanical change; lands when somebody picks it up.
   Tracked as "Interactor Pattern Migration — Largely Landed" in
   `docs/tech-debt.md`.
2. **Wire-level task cancellation lands.** `TaskInteractor`'s cooperative
   cancellation is sufficient within-process; cross-process needs a
   `TaskHandle` with a server-assigned id the client can use to send an
   explicit cancel RPC. Tracked in `docs/tech-debt.md` under
   *Wire-Level Task Cancellation*. When that lands, `TaskInteractor`'s
   execution contract grows a cancellation surface.
3. **Backpressure on `BackgroundInteractor` becomes a real concern.** Today
   the default is "launch unconditionally on `serviceScope`" — drop/block/
   fail-fast on a full queue is unspecified. Revisit when a real use case
   surfaces.
4. **A fifth execution mode appears.** Today's four cover sync / IO /
   background / stream — every operation pattern we know of fits one. A
   fifth would mean adding a new `Interactor` subtype + a new trait + a
   new `as*Interactor()` extension; the pattern composes additively.

## Alternatives considered

- **Declare `suspend` on the operation interface for IO ops.** The
  pre-work shape (option 1 in §Context). Rejected — propagates `suspend`
  through pure chain composition, contaminates `.equalTo` / `.filter` /
  every non-terminal operation. The Color-Blind Interface Rule explicitly
  rejects this; the Interactor layer is what makes the rule keep working
  for IO ops.
- **Hide asynchrony in a cast.** The pre-work shape for Stream
  (`TResult<Data?>` that's secretly a `StreamInteractor<T>`). Rejected —
  the cast is brittle and the type system tells a lie. Generalising the
  Stream pattern made it possible to remove the cast everywhere, not
  duplicate it.
- **Mode-specific providers (one per mode).** Considered for separation.
  Rejected per anchor decision 4 — the pre-existing
  `StreamInteractorProvider` proved the shape; multiple providers means
  multiple `TComponent` lookups, multiple test rigs, and divergence over
  time. One provider is the natural fit because the trait-based dispatch
  is uniform across modes.
- **Shared `execute(): Result` signature on the `Interactor<T>` base.**
  Considered for type uniformity. Rejected per anchor decision 2 — the
  four modes return different things (immediate `TResult`, suspending
  `TResult`, `Job`, `Flow`). Forcing one signature would make all four
  lie about cost or shape (e.g. `suspend` on `SyncInteractor` —
  unnecessary suspend cost; `Job` wrapping `TResult` for sync —
  unnecessary indirection). The DSL extension already picks one mode
  per call site; downcasting from `Interactor<T>` to the specific subtype
  is what the typed `as*Interactor()` extensions do.
- **Single combined provider that returns a sealed union the caller
  switches on.** Considered for type-safe dispatch. Rejected — caller-side
  switching means every DSL extension has a four-case `when` block, which
  is exactly what the typed `as*Interactor()` extensions hide.
  Mode-specific extensions are how DSL authors interact with the provider;
  the cast inside `asTaskInteractor` etc. is hidden from call sites.
- **Skip the `TTask` → `TaskInteractor` rename.** Considered for
  smaller-scope. Rejected — keeping the name `TTask` would leave the
  asymmetry in the hierarchy (`SyncInteractor` / `BackgroundInteractor` /
  `StreamInteractor` + `TTask`). The rename brings the four into a
  consistent family. Deprecated typealias `TTask = TaskInteractor` carried
  the migration window.

## References

- `docs/schema-dsl.md` §5 — authoring reference for the four execution
  modes, the `as*Interactor()` extensions, and the worked DSL extension
  examples.
- [ADR 0011](0011-task-executor-split.md) — the `TaskExecutor` layer
  underneath this work. Interactor uses the executor layer to actually
  run the chain; this ADR documents the DSL-side adapter.
- [ADR 0008](0008-execution-context-decomposition.md) — `ExecutionContext`
  decomposition. `BackgroundInteractor` bridges through
  `ExecutionContext.serviceScope`, which lives on the post-decomposition
  context.
- `:core/.../Interactor.kt` — sealed `Interactor<T>` hierarchy.
- `:core/.../InteractorProvider.kt` — `TComponent` interface +
  `TContext.interactorProvider()` accessor.
- `:core-common/.../DefaultInteractorProvider.kt` — production
  implementation; trait-driven dispatch.
- `:core-common/.../asInteractor.kt` — the four typed extensions
  (`asSyncInteractor`, `asTaskInteractor`, `asBackgroundInteractor`,
  `asStreamInteractor`).
- ⚠ `FakeInteractorProvider` was proposed here but never built — no such class
  exists in `core-test-utils` or anywhere else.
- `docs/tech-debt.md` §"Interactor Pattern Migration — Largely Landed" —
  the one open item.
