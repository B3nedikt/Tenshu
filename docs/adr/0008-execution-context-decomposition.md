# ADR 0008 — `ExecutionContext` decomposition

- **Status:** Accepted, implemented (closed out 2026-05-13)
- **Date:** 2026-05-13
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —

## Context

`core-common/.../proxy/ExecutionContext.kt` was 813 lines, 19 public methods, 9
constructor params, and at least 8 distinct responsibilities living in one class:

| Cluster | Responsibility | Lines (~) |
|---|---|---|
| DI bag | 9-param constructor | 50 |
| Components map | Lazy override of `TContext.components` | 10 |
| Lifecycle | `init()`, `destroy()` | 20 |
| Observer registry | `addDataObserver`, fire on `saveData` / `deleteData` | 30 |
| Stream registry | `setDatabaseListener`, six per-stream maps, `findDatabaseReferences` | 270 |
| Scope | `withEvaluationScope` | 30 |
| Database delegate | `getArrayData`, `addDataToArray`, `removeDataFromArray`, `saveData`, `deleteData`, `getDataId` | 80 |
| Reference resolution | `resolveList`, `resolveProperty`, `resolveDynamicProperty`, `resolveServiceType`, `resolveServiceData` | 160 |
| Operation dispatch | `execute`, `executeAsync` | 80 |

Three concrete pains:

1. **No unit tests on the most subtle paths.** `grep -rl "ExecutionContext(" :commonTest`
   returned ~0 direct constructions; everything went through `TestProxy` /
   `createFileProxy(...)`. Reference resolution and stream emission semantics
   were pinned only by whatever happened to flow through a Search/Filter/Select
   integration test. The stream-element-race (CME) fix and the observer-sharing fix
   both took multiple iterations because the surface couldn't be exercised in
   isolation.
2. **Three `when (...)`-blocks disagreed in detail** on the
   `ArrayDatabaseReference` / `DatabaseReference` / `TList<*>` / `Sequence<Data>`
   quartet: `ExecutionContext.resolveList`,
   `TenshuProtocolObjectDeserializer.writeValuesToDb`, and
   `TenshuProtocolObjectDeserializer.deSerialize`. A patch had to add the
   `ArrayDatabaseReference` branch to (2) because it was missing — a real
   production crash on a Folder merge save. Another added a safe-cast in
   `Entity<out File>.data` for `PendingOnDemandPropertyDto` for similar reasons.
   Both were defensive patches at consumer sites because no single component
   *owned* "what reference shapes can a list-valued field carry, and how do I
   read/write each."
3. **Cross-platform pressure.** The web-track JS runtime
   (`docs/web/architecture.md` Phase P) was increasingly exercising the same
   proxy surface from a different platform. The longer `ExecutionContext`
   stayed monolithic, the harder cross-platform parity of proxy-side behaviours
   was to test.

The decomposition was also the right moment to settle a meta-question that had
been growing: `TContext`'s role on the proxy side — uniform parameter (good) or
string-keyed DI map (worse). The decomposition forced a decision because the
naïve "register the new components as `TComponent`s" path was on the table.

## Decision

Extract three collaborators from `ExecutionContext`, each backed by its own unit
tests, each constructor-injected (not registered as `TComponent`s). Defer
serializer cleanup to a follow-up. Land in five small phases, each
behaviour-preserving by construction.

Architectural details + the collaborator wiring shape live in
[`core-proxy/proxy-agent.md`](../../core-proxy/proxy-agent.md) §2
"`ExecutionContext` and its collaborators".

Six anchor decisions:

1. **Three components, not more, not fewer.** `ReferenceResolver` (the
   load-bearing extraction), `StreamRegistry`, `ObserverRegistry`. Serializer
   cleanup is its own proposal; `Database` delegates are mechanical and bundled
   with the last phase.
2. **Constructor injection, not `TContext.components`.** Every new collaborator
   is a constructor parameter on `ExecutionContext` (with a default that
   constructs a fresh instance), constructor-injected into the impls that use
   it. Not registered as `TComponent`s. This shrinks `ExecutionContext`
   without growing the `components` registry — see "TContext A vs B" below.
3. **`withEvaluationScope` shares collaborator instances with the parent.**
   Load-bearing: cross-process IPC through `ProxyGateway.call` wraps the
   session, and observers (`SyncObserver` / `DeviceRelationMaintainer` /
   `ParentFilesRelationMaintainer` / `NotificationRelationMaintainer`) must
   still fire on `executionContext.saveData(...)` inside an operation impl;
   stream lifecycle (start on one request, fire from another, end from a
   third) sees one backing map set; the `collectingReferences` scope is
   observable across request boundaries.
4. **Listener methods take `ExecutionContext` as a parameter, not a held
   reference.** `setDatabaseListener` / `removeDatabaseListener` /
   `resolveProperty` / `resolveDynamicProperty` all recurse into
   `evaluateCallstack` and need the context. Passing it through as data
   (mirroring `evaluateCallstack`'s own shape) keeps the collaborators
   constructible before any `ExecutionContext` exists — used by the default
   constructor on `ExecutionContext` itself.
5. **Free helper `arrayReferenceOrNull(data: Any?)` next to the resolver, not
   a method on it.** The two-arm `ArrayDatabaseReference` /
   `DatabaseReference + ArrayOperationContext` detection lives at both read
   sites (`ReferenceResolverImpl.resolveList`) and write sites
   (`TenshuProtocolObjectDeserializer.writeValuesToDb`); a free function lets
   the deserializer use it without taking a `ReferenceResolver` constructor
   dependency. The drift behind the Folder-merge-save crash is exactly the risk this consolidation
   prevents.
6. **`executionContext.getArrayData(...)` stays** even though it looks like a
   pure `database` delegate. It's not — it threads
   `referenceResolver.collectingReferences`'s side-channel for stream
   nested-ref discovery. Replacing call sites with
   `executionContext.database.getArrayData(...)` would silently break
   `StreamRegistry.setDatabaseListener`'s `onUpdate` closure. A KDoc on the
   wrapper spells this out. The other two thin wrappers (`addDataToArray`,
   `removeDataFromArray`) were pruned in Phase 5.

### TContext: A vs B

The decision that landed for `TContext`'s two jobs:

| Concern | `TContext.components` (Job B) | Constructor injection |
|---|---|---|
| Type safety at lookup | Cast at retrieval; missing key = runtime crash | Compile-time enforcement |
| Visibility of dependencies | Hidden inside `execute()` body | Constructor parameter list |
| Cross-platform parity | Uniform across JVM/Desktop/JS | Already uniform — each platform's container builds the same DAG |
| Per-operation testability | Have to stub a whole `components` map | Construct the impl with fakes |
| Wire/serialiser usage | Required (Job A — schemas, factories, converters need it) | Not applicable; Job A concern |

**Keep Job A. Don't grow Job B.** New collaborators are constructor-injected.
Existing entries (`OnDemandResolution`, `ExternalPropertyWriter`,
`SyncCryptoManager`, `BackgroundScopeProvider`, `ParcelFileDescriptorCache`,
`SharedMemoryCache`) stay for now and migrate opportunistically; the bar for
staying is "foreign-module code looks me up by name without a wiring
relationship" — at least `OnDemandResolution` and `ExternalPropertyWriter`
plausibly meet that bar.

## Consequences

- **`ExecutionContext.kt` is ~200 lines** (was 800+). Constructor + lifecycle
  + `getArrayData` (documented) + `saveData` / `deleteData` (dispatch to
  observers) + `execute` / `executeAsync` + `withEvaluationScope` + thin
  delegates for the three collaborators.
- **New unit-test surface.** `ReferenceResolverTest` (14 tests),
  `ObserverRegistryTest` (5 tests pinning the snapshot-then-iterate invariant — observer's
  `onDataSaved` calling `add(observer)` mid-iteration must not CME).
  `StreamRegistry` keeps integration-test-only coverage (the load-bearing
  paths — CME, listener registration/unregistration ref-counting,
  snapshot-then-iterate, stream end on error — are honestly exercised by
  `ExecutionContextStreamTest` × 3 + `LocalStreamingIntegrationTest` × 3 +
  `ProxyStreamMultiplexingTest` + `ProxyStreamLifecycleTest` +
  `StreamEndElementConstructionTest` × 2; a dedicated `StreamRegistryTest`
  would mock 4 collaborators for less honest coverage).
- **Stream lifecycle and observer sharing across request boundaries became
  explicit.** Was a `sharedStreamState: StreamState?` / `sharedDataObservers:
  MutableList<DataObserver>?` constructor smell pre-decomposition;
  `withEvaluationScope` now shares whole collaborator instances. The earlier
  half-extraction was the right shape; the full extraction just removed the
  "nullable shared bag" wrapping.
- **Tech-debt §"Stream Lifecycle Shared Across Request Contexts" closed**
  (superseded by the `StreamRegistry` extraction).
- **Follow-up: `core-observer-async-dispatch.md`** (deferred). Pick up when a
  profile shows it matters or an I/O-bound observer lands.

## When to revisit

Re-open when one of these is true:

1. **`evaluateCallstack` stops depending on `ExecutionContext` directly.** The
   current shape passes the context through as data (decision 4). If the
   callstack evaluator's collaborator surface narrows (e.g. just `database` +
   `referenceResolver`), the listener methods could hold those directly,
   simplifying the `StreamRegistry` signature.
2. **A real serializer cleanup proposal** addresses tech-debt §"Database
   Serializer: Only-Grow Logic Doesn't Recurse Into Nested Composites" and the
   "full-graph walk on every save" concern. That work is what the decomposition
   explicitly *didn't* take on; it has its own design questions (per-field
   dirtiness vs. per-entity re-walk).
3. **A profile shows synchronous observer dispatch is a hot path.** The
   `core-observer-async-dispatch.md` follow-up gets unblocked. Today's
   ~microsecond synchronous dispatch is fine; an I/O-bound observer (say,
   a future Photo-indexer that writes to disk on `onDataSaved`) would change
   the calculus.
4. **The `TContext.components` migration audit lands.** §"TContext A vs B"
   freezes the rule at the *new* additions; the existing 6 entries
   (`OnDemandResolution`, `ExternalPropertyWriter`, `SyncCryptoManager`,
   `BackgroundScopeProvider`, `ParcelFileDescriptorCache`, `SharedMemoryCache`)
   should each be audited against the "foreign-module code looks me up by
   name" bar.

## Alternatives considered

- **Register the new components as `TComponent`s** (the natural extension of
  what's there today). Rejected per the TContext A vs B framing — adds string-
  keyed lookups, hides dependencies, makes unit testing harder. Constructor
  injection beats it on every concern except "uniform parameter shape", and
  the new collaborators aren't needed by code outside their wiring graph.
- **One-shot decomposition, all in a single PR.** Rejected — ~300 call sites
  touch `executionContext.X(...)` today. Five phases each landing
  behaviour-preserving lets reviewers (and any rollback) work at a sensible
  granularity.
- **Make `StreamRegistry` hold `ExecutionContext` directly.** Considered for
  ergonomics. Rejected: the listener closure recurses into
  `evaluateCallstack`, which already receives the context as a parameter;
  matching that shape (decision 4) keeps `StreamRegistry` constructible
  before any `ExecutionContext` exists.
- **Drop `executionContext.getArrayData(...)` as part of Phase 5's delegate
  cleanup.** The mechanical move was to make 9 call sites use
  `executionContext.database.getArrayData(...)`. Rejected — the wrapper is
  not a pure delegate; it threads the reference-collector for stream
  nested-ref discovery. Pruning it would silently break
  `StreamRegistry.setDatabaseListener`'s `onUpdate`. KDoc'd instead.
- **Bundle the serializer cleanup (tech-debt §"Only-Grow Logic Doesn't
  Recurse")** with the decomposition. Rejected — it has independent design
  questions (per-field dirtiness vs. per-entity re-walk) and a different
  cost-benefit. Better as its own proposal with focused exploration.
- **Skip Phase 2's `arrayReferenceOrNull(...)` helper, keep the inline
  `when`-arms.** Considered after Phase 1 landed and the duplication felt
  smaller. Rejected — the duplication was exactly what crashed the Folder merge save;
  a 4-line free function buys structural prevention.

## References

- `core-proxy/proxy-agent.md` §2 "`ExecutionContext` and its collaborators" — the
  collaborator wiring shape. The `TContext` rule stays in `docs/agent.md` §2
  ("`TContext`'s two jobs").
- `:core-common/.../proxy/ExecutionContext.kt` — the post-decomposition
  orchestrator.
- `:core-common/.../proxy/ReferenceResolver.kt` — `ReferenceResolverImpl` +
  `collectingReferences { }` + the `arrayReferenceOrNull(...)` free helper.
- `:core-common/.../proxy/StreamRegistry.kt` — stream lifecycle + listener
  registration.
- `:core-common/.../proxy/ObserverRegistry.kt` — observer dispatch with the
  snapshot-then-iterate invariant.
- `:core-common/.../proxy/db/TenshuProtocolObjectDeserializer.kt::writeValuesToDb`
  — second consumer of `arrayReferenceOrNull(...)`.
- `:core-common/src/commonTest/.../proxy/ReferenceResolverTest.kt` (14 tests).
- `:core-common/src/commonTest/.../proxy/ObserverRegistryTest.kt` (5 tests).
- `core-observer-async-dispatch.md` — follow-up, deferred.
