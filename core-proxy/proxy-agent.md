# Tenshu Platform: Proxy & Execution Conventions

This document captures the conventions for operation execution and proxy-side state management.

## 1. Proxy & ExecutionContext

* **Re-evaluation**: On any database change, the `ExecutionContext` re-runs the entire operation
  chain on the full dataset.
* **Stateful Diffing**: The proxy maintains a history of items sent to each stream (
  `streamIdToItems`) and performs a granular diff.
* **Initial Emission**: The proxy should always send an initial state emission (even if empty).
* **Service Identity**: For operations targeting service-level relations (singletons), always use
  `RelationDataId` with the unique relation identifier.
* **Idempotent Initialization**: The `init()` method in `ExecutionContext` must be idempotent.

## 2. `ExecutionContext` and its collaborators

`ExecutionContext` (`:core-common/proxy/ExecutionContext.kt`) is the per-session container
every `OperationImplementation` receives. Per
[ADR 0008](../docs/adr/0008-execution-context-decomposition.md), it's a thin orchestrator
(~200 lines) holding four collaborators plus the operation-dispatch + lifecycle methods.

| Collaborator | What it owns | Wiring |
|---|---|---|
| `ReferenceResolver` | `resolveList`, `getArrayData`, `resolveProperty`, `resolveDynamicProperty`, `resolveServiceType`, `resolveServiceData`, plus the `collectingReferences { }` stream-collector scope | Constructor param on `ExecutionContext` (default = `ReferenceResolverImpl(context, database, materializer)`). `withEvaluationScope` shares the parent's instance so the collector scope is observable across request boundaries. |
| `StreamRegistry` | `setDatabaseListener`, `removeDatabaseListener`, the per-stream maps (`streamIdToItems`, `streamIdToRef`, `refToStreamListeners`, …), `activeStreamCount` | Constructor param. Shared by `withEvaluationScope` so stream lifecycle (start on one request, fire from another, end from a third) sees one backing map set. The two listener methods take `executionContext: ExecutionContext` as a parameter — they recurse into `evaluateCallstack` and need the context. |
| `ObserverRegistry` | The `MutableList<DataObserver>` and the snapshot-then-iterate dispatch on `saveData` / `deleteData`. Pins the load-bearing invariant: an observer's `onDataSaved` calling `add(observer)` mid-iteration must not CME. | Constructor param. Shared by `withEvaluationScope` (load-bearing — `SyncObserver` and the generic `RelationMaintainer` fire on `executionContext.saveData(...)` inside operation impls, and cross-process IPC routed through `ProxyGateway.call` wraps the session). |
| `Database` | Storage + array operations. Operation impls call `executionContext.database.X(...)` directly for most paths. Array listeners are **event-driven** ([ADR 0015](../docs/adr/0015-loop-db-stream-unification.md)): `addToArray`/`removeFromArray` take a `WriteOrigin` and fire `onAdded(value, origin)`/`onRemoved(dataId, origin)` directly from the call site (no per-write O(n²) full-array diff); the diff survives only as an O(n) fallback for `saveData`-driven bulk array rewrites (e.g. `FolderMerger` only-grow). | Constructor param. There are no `addDataToArray` / `removeDataFromArray` wrappers; use `executionContext.database.addToArray(...)` etc. The one wrapper is **`executionContext.getArrayData(...)`** — see KDoc; it's **not** a pure delegate (it threads `referenceResolver.collectingReferences`'s side-channel for stream nested-ref discovery). |

Free helper next to the resolver: `arrayReferenceOrNull(data: Any?): ArrayDatabaseReference?` —
the two-arm `ArrayDatabaseReference` / `DatabaseReference + ArrayOperationContext` detection
that lives at both read sites (`ReferenceResolverImpl.resolveList`) and write sites
(`TenshuProtocolObjectDeserializer.writeValuesToDb`). Use the helper, don't reinline the
`when`; the inline forms drift in detail, and a drifted copy has crashed production.

**Outbound sync emit is decoupled from `saveData`**
([ADR 0015](../docs/adr/0015-loop-db-stream-unification.md)).
`SyncObserver.onDataSaved` only `trySend`s onto a channel; a single consumer coroutine —
on an injected scope defaulting to `sessionScope` (mirrors `SyncScheduler`; ADR 0014
cancels+joins it) — does the serialize + AEAD + HLC tick + `pendingBlocks` enqueue. So
`saveData` does not pay that cost inline. `SyncObserver.awaitEmitIdle()` is the
drain-and-join join point (tests that inspect `pendingBlocks` right after `saveData`, and
a headless background drain). The HLC CAS loop stays — only the outbound path
is single-writer; ingest still advances the clock.

The rule for what belongs on `TContext.components` vs constructor injection lives in
[`docs/agent.md`](../docs/agent.md) §2 "`TContext`'s two jobs".

## 3. Operation Execution Strategies & Scopes

* **Operation Execution Strategies**: The platform distinguishes between execution modes based on
  schema traits. This avoids "suspend everywhere" and provides clear lifecycle management for tasks.
    * **Synchronous (`SyncOperationImplementation`)**: The default. Used for metadata, calculations,
      or memory-only state access. Signature: `fun execute(...)`.
    * **Asynchronous IO (`IoOperation` trait)**: Used for operations that wait on network or disk.
      Implementations must use `AsyncOperationImplementation`. These tasks are tied to the
      `evaluationScope` and cancelled if the request is aborted. Signature:
      `suspend fun execute(...)`.
    * **Background (`BackgroundOperation` trait)**: Used for operations that initiate tasks that
      should outlive the individual request. These are launched in the `sessionScope`.
* **Structured Concurrency**: Proxy operations receive an `ExecutionContext`, which provides two
  scopes:
    * `evaluationScope`: Bound to the lifecycle of the current request. Created by
      `ProxyGateway.call` per IPC and attached to the per-request `ExecutionContext` via
      `withEvaluationScope(...)`.
    * `sessionScope`: Bound to the lifecycle of the client session (the connection, not the
      request). Created when a client binds; cancelled on unbind. `BackgroundOperation`s and
      stream-listener fires that outlive the originating request run here. Stream lifecycle
      state crosses requests, so `ExecutionContext` carries a `sharedStreamState` that survives
      `withEvaluationScope` derivation — see
      [`docs/tech-debt.md`](../docs/tech-debt.md) §"Stream State Lives on a Shared
      `StreamState`".
* **Pure vs. Operation Implementation**: The platform distinguishes between side-effect-free "Pure"
  operations and stateful "Proxy" operations.
    * **`PureOperationImplementation` (in `:core-common`)**: Interface for implementing logic that
      only depends on `TContext` and parameters. Used for operations marked with the
      `PureOperation` trait.
    * **`SyncOperationImplementation` / `AsyncOperationImplementation` (in `:core-common`)**:
      the interfaces for logic that requires the full `ExecutionContext` (e.g. Database access,
      stream management) — synchronous and suspending respectively, as named in the bullets
      above. ⚠ There is no `ProxyOperationImplementation`; the three real implementation
      interfaces are `Pure`, `Sync` and `Async`.
    * **Rule**: Favor Pure operations wherever possible to maximize portability and simplify
      testing. Only use Proxy implementations when interaction with platform infrastructure is
      strictly required.
* **`PureOperationRunner` (TComponent, in `:core`)**: Interface extending `TComponent` that
  exposes an `execute()` method for running operations marked with the `PureOperation` trait.
  Low-level components (like the `Database` or frontend list utilities) SHOULD use this runner via
  `TContext.pureOperationRunner()` to remain decoupled from the full `ExecutionContext`. The
  concrete implementation is `PureOperationRunnerImplementation` (in `:core-common`).

## 4. Dependency Management & Context

* **Context Requirement**: Most core components (especially proxy objects and DTOs) rely on
  `TContext` for factories. This MUST be provided explicitly during initialization.
* **Constructor Injection**: Always prefer passing `TContext` or specific dependencies via the
  constructor.
* **`TenshuEnvironment`**: Use `TenshuEnvironment` to manage the lifecycle of services and their
  dependencies.

## 5. Operation Implementation Details

* **Search Predicates**: When performing a `search`, the predicate callstack must explicitly
  navigate to the target field before applying comparison operations.
* **Cross-Platform Sync**: Use the `synchronized(lock, block)` helper in `commonMain` for
  thread-safe state management.

## 6. Task Execution & Testing

* **`LocalTaskExecutor`**: This is the default executor for proxy-side operations.
* **Testing Strategy**:
  * Integration tests (via `TestProxy`) SHOULD use `LocalTaskExecutor` with its optional binary
    serialization path enabled.
  * This ensures that tests cover the full
    `Serializer -> RequestHandler -> Parser -> EvaluateCallstack` pipeline, catching protocol
    inconsistencies that in-memory tests would miss.
