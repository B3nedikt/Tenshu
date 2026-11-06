# ADR 0014 — Proxy session lifecycle: owned scope + deterministic suspend teardown

- **Status:** Accepted, implemented
- **Date:** 2026-05-24
- **Owner:** Benedikt
- **Supersedes:** —
- **Superseded by:** —
- **Depends on:** [ADR 0008 — execution-context decomposition](0008-execution-context-decomposition.md)
  (`ExecutionContext` / `sessionScope` / `destroy()`)

## Context

The proxy runs several long-lived background coroutines — `SyncScheduler`'s poll
loop, `ScheduleDrainLoop`'s registry drain, and Control's `ConstraintSyncGate`
`backgroundSyncConstraints()` observer — and they all read/write the same
`ExecutionContext` (`database`, `dataFactory()`, `roProvider()`, `StreamRegistry`).

Pre-fix, **no single owner held that lifecycle.** Each loop was launched on an
ad-hoc `CoroutineScope(Dispatchers.Default)` minted inline in DI
(`initSyncService`'s default `coroutineScope`, `ConstraintSyncGate(scope = …)`),
none tied to the `ExecutionContext` / session it served. Nothing cancelled those
scopes at teardown, so the loops kept ticking after the context was gone:
`stopKoin()` (tests) / app shutdown dropped the Koin singletons but did not cancel
the scopes, and the next tick hit a context whose components were null:

```
NullPointerException: null cannot be cast to non-null type DataFactory
  at …dataFactory(…) ← ConstraintSpec.create ← ConstraintSyncGate combine flow
  at …ScheduleStore.scheduledItemsRef(…) ← ScheduleDrainLoop.tick(…)
```

`ScheduleDrainLoop`'s NPE was swallowed by its own `try`/`printStackTrace` (noisy,
non-fatal). `ConstraintSyncGate`'s combine-flow NPE was **uncaught** (plain
`launchIn`), landed on `Dispatchers.Default`, and was then picked up by the *next*
`runTest` test as `UncaughtExceptionsBeforeTest` — cascading failures across
unrelated Control tests. Present on `master`, intermittent; surfaced
deterministically when a paging change shifted teardown timing.

**Why the obvious quick fix doesn't fully close it.** Tying the loops to
`ExecutionContext.sessionScope` (which `ClientRegistry` *does* cancel) plus a Koin
`onClose { shutdown() }` narrows the window but does not eliminate it: cancellation
is cooperative and asynchronous, while the loops do **synchronous, non-suspending**
context access (e.g. `ConstraintSpec.create → dataFactory()`) between suspension
points. A tick already in flight when teardown begins can still touch a dropped
singleton. Closing it needs teardown to **quiesce** — cancel *and join* the
background coroutines before the context is destroyed — which `runBlocking`/join
can't express in `commonMain` (and is impossible on JS).

## Decision

Give the proxy session a single owned lifecycle and a deterministic, **suspend**
teardown. Five anchor decisions.

1. **One owned scope.** `initSyncService`'s loops and `ConstraintSyncGate`'s
   collector launch on the session's **`ExecutionContext.sessionScope`** — the
   scope `ClientRegistry` already cancels on teardown — rather than detached
   `Dispatchers.Default` scopes. The gate collector is wrapped in `.catch` so a
   throw inside the teardown window can't escape onto the dispatcher.

2. **Deterministic teardown = cancel *and join*.** `ExecutionContext.quiesce()`
   `cancelAndJoin()`s the session-scope job, then `destroy()`s. The join is what
   actually closes the race: a bare `cancel` returns before in-flight ticks
   unwind. Exposed as `ClientRegistry.shutdownAndJoin()` +
   `ProxyGateway.shutdownAndJoin()`.

3. **Teardown is `suspend`.** Cancel-and-join is not expressible via `runBlocking`
   in `commonMain` (and impossible on JS), so the join-bearing teardown is a
   `suspend` API. The pre-existing synchronous teardown paths stay for
   fire-and-forget callers (Android `onUnbind`).

4. **Reuse the existing `sessionScope`; no dedicated child `backgroundScope`.**
   (Deviation from the tech-debt's "proposed direction", which floated either.)
   The *join* is what closes the race; a separate child scope adds moving parts
   without changing the teardown guarantee.

5. **Entry-point wiring.** Control integration tests quiesce before `stopKoin()`
   via a new `stopAppIntegrationKoin()`; control-desktop `Main` and Android
   `ProxyService.onDestroy` call `shutdownAndJoin()`. Android `onUnbind` stays on
   the fire-and-forget `unregisterAll()` path **on purpose** — a detached L2
   background task should survive a client rebind, so onUnbind must not join.

## Consequences

- **The cross-test cascade is gone.** No background tick touches a torn-down
  context; the uncaught `ConstraintSyncGate` NPE that surfaced as
  `UncaughtExceptionsBeforeTest` in unrelated Control tests no longer occurs.
  ⚠ **Amended 2026-07-26 — this held only for sessions teardown could
  still reach, and it could reach one in three.** `ExecutionContext` ran its
  `initialization()` hook from its *constructor*, before `ClientRegistry`
  registered the session; the hook's background wiring re-entered
  `getExecutionContext(clientId)` (the in-process `SessionAwareLocalTaskExecutor`
  session provider), found nothing registered, and minted a further session —
  recursively. The clientId-keyed copy-on-write `clients` map then overwrote all
  but the last, so the rest were never cancelled and their loops ticked on past
  `stopKoin()` — reviving exactly this cascade, and leaking two of every three
  loop sets in production. Fixed by registering the session *before* running the
  hook (`ExecutionContext.runInitialization()`, called after `clients.update`).
  **Decision 2 (cancel-and-join) was never wrong — its reachability precondition
  was simply unstated:** a session must be in the registry before it runs work
  that can re-enter the registry. See `docs/tech-debt.md` "Proxy Background
  Lifecycle …".
- **Teardown is now `suspend`.** Callers that need the deterministic guarantee
  must be in a coroutine (tests, `Main`, `onDestroy`); fire-and-forget callers
  keep the synchronous path.
- **Regression tests pin both halves** — `SyncProxyLifecycleTest` (loops launch on
  the owned scope) and `ExecutionContextQuiesceTest` (quiesce/`shutdownAndJoin`
  join semantics); both **fail under a bare `cancel`** (no join), so they lock in
  decision 2.
- **Scope: lifecycle only (Phases 1–3).** The broader entanglement the tech-debt
  item describes — unifying loop / database-update / stream-management plumbing
  with channel-based sync decoupling and the `WriteOrigin` / event-driven listener
  surface — is **not** in this change; it remains tracked in `docs/tech-debt.md`.
- Verified: `:sync-service-proxy:jvmTest` 96/0, `:core-common:desktopTest` 271/0,
  `:control-shared:desktopTest` 24/0, `:control-desktop:compileKotlinJvm`, JS
  compiles. Android edits inspection-only (no SDK in the build env). `JsRuntime`
  has no teardown method yet → N/A; system tests already quiesce via
  `clientScope.cancelAndJoin`.

## When to revisit

1. **The broader loop / database-update / stream-management unification** lands —
   bundle the owned-scope lifecycle with channel-based sync decoupling and the
   `WriteOrigin` / event-driven `Database` listener surface so each facet stops
   re-inventing its own loop + scope + teardown. (The remaining tech-debt.)
2. **A background consumer needs a sub-session lifetime** (e.g. cancel one loop
   without tearing the session down). Then split a dedicated child scope off
   `sessionScope` — decision 4 chose against it only because nothing needed it.
3. **`JsRuntime` grows a teardown path.** It has none today; when it does, it
   needs the same quiesce semantics (cancel-and-join before destroy).

## Alternatives considered

- **Tie loops to `sessionScope` + a Koin `onClose { shutdown() }`, no join.**
  Rejected as insufficient — narrows the window but leaves it open: a synchronous
  in-flight tick between suspension points can still touch a dropped singleton.
  The join is load-bearing.
- **A dedicated child `backgroundScope` off the session.** Rejected (decision 4) —
  more moving parts, same teardown guarantee; revisit only if a sub-lifetime is
  needed.
- **`runBlocking`-based synchronous teardown.** Rejected — not expressible in
  `commonMain` and impossible on JS; the suspend API is the portable shape.
- **Keep `onUnbind` joining too** (symmetry with `onDestroy`). Rejected — a
  rebind after onUnbind should find detached L2 work still running; joining there
  would kill background tasks the next client expects to inherit.

## References

- `:core-common/.../proxy/ExecutionContext.kt` — `quiesce()` (cancel-and-join →
  destroy).
- `:core-common/.../proxy/ClientRegistry.kt` + `ProxyGateway.kt` —
  `shutdownAndJoin()` (suspend) alongside the fire-and-forget paths.
- `:sync-service-proxy/.../SyncProxyInitialization.kt` — loops on `sessionScope`.
- `:control-shared/.../data/ConstraintSyncGate.kt` — collector on `sessionScope`,
  `.catch`-wrapped.
- `:control-shared/.../integration/utils/AppIntegrationTestUtils.kt` —
  `stopAppIntegrationKoin()`; `:control-desktop/.../Main.kt` +
  Android `ProxyService` entry points.
- `:sync-service-proxy/.../SyncProxyLifecycleTest.kt` +
  `:core-common/.../proxy/ExecutionContextQuiesceTest.kt` — regression pins.
- `:core-common/.../proxy/ClientRegistryTest.kt` —
  `getExecutionContext_reentrantLookupDuringInitialization_reusesTheSameSession`,
  the 2026-07-26 amendment's pin (fails if the registration/initialisation order
  is reversed).
- [ADR 0008](0008-execution-context-decomposition.md) — `ExecutionContext`.
- `docs/tech-debt.md` "Proxy Background Lifecycle …" — the originating item +
  the remaining broader-entanglement scope.
