# SystemTests — in-process flow tests against the real relay

> Status: **Proposal — decisions resolved; ready for phase 0/1.** Draft 3, 2026-05-10.
> Owner: Benedikt.
> Adjacent docs: [`docs/testing.md`](../testing.md), [`docs/agent.md`](../agent.md),
> [`docs/proposals/sync-pairing-flow-improvements.md`](./sync-pairing-flow-improvements.md),
> [ADR 0007 — synced device management](../adr/0007-sync-device-management.md).

## 1. Motivation

We have two test tiers today:

1. **Unit / module tests** (`commonTest` per module) — fast, isolated. They touch one
   `TestProxy`, often with `MockSyncRelay` injected. Excellent for the inside of an op
   or merger; bad at catching wire-layer regressions because each side of the wire is
   tested in isolation.
2. **Full e2e** (Android instrumentation, desktop launches, sync-relay-server on
   `:8080`) — slow, brittle, requires a phone or a server, can't run in CI without
   significant infrastructure. We rarely write these.

What's missing is a **middle tier** that exercises long, multi-component flows
(pairing, recovery, revocation, multi-device file sync) **across both transport
boundaries**, but still in-process and fast enough to live in `commonTest`.

The motivating gap: **there is no test that exercises a full pairing handshake
across two devices.** `InitiatePairingImplementationTest` and
`ApprovePairingRequestImplementationTest` each cover one side. The wire interaction
through the pairing endpoints + the SAS derivation + the post-handshake JWT issuance
are only validated by manual testing today. Same shape applies to recovery and to any
future "Device A does X, Device B observes Y after sync" flow.

`RevokeDevicePropagationTest`
(`sync-service-proxy/src/commonTest/.../RevokeDevicePropagationTest.kt`) is the
prior art: it hand-wires two `TestProxy` instances that share one `MockSyncRelay` and
asserts that a revoke on A causes B to delete the entity. It's the right shape for
two devices passing data, but it has two limitations: each test re-implements the
wiring (~90 lines of `setupProxy(...)`), and the relay is a hand-rolled in-memory
mock — its quorum / auth / GC behaviours can drift from the real
`:sync-relay-server` implementation without the test noticing. SystemTests fix both:
shared infrastructure and **the real relay code** running in-process.

We want a **first-class, named, reusable framework** for this tier. Internally and
in PR descriptions we'll call them **SystemTests**.

> A note on the name. "System test" has overloaded meaning in industry (sometimes it
> implies real services). We're using it because it conveys "more than a unit, less
> than a deployment" and aligns with how Benedikt phrased the requirement.
> Alternatives considered: `FlowTest`, `ScenarioTest`, `IntegrationFlowTest` — all
> rejected as either too narrow or already overloaded inside this codebase.

## 2. Goals & non-goals

### Goals

- Exercise long flows (pairing, recovery, multi-device sync) end-to-end **in-process**.
- **Bypass the network** on both transport boundaries — no socket, no port — while
  running the **real code** on both sides where feasible:
  - **app ↔ proxy**: real `ProxyGateway` and real `TenshuClient`, joined by the
    in-process `LocalClientConnection` (no AIDL marshalling, no Unix socket framing).
  - **proxy ↔ relay**: real `RestSyncRelay` HTTP client talking to the real
    `:sync-relay-server` `Application.module()` hosted in-process via Ktor's
    `testApplication { }` (no Netty bind, no port). JWT auth, routing, mailbox
    semantics, GC sweep — all real.
- Run on the JVM in `commonTest`/`jvmTest`, no Android emulator, no live relay process.
- Allow **N TestClient instances** in one test, each with its own DI graph and
  identity, all sharing one in-process relay.
- **Mirror production wiring** via Koin. Each TestClient assembles its DI graph from
  the same modules production uses (`coreCommonModule`, `proxyModule`,
  `*ServiceCommonModule`, `*ServiceClientModule`), substituting only the two
  platform-bound transport bindings (`platformClientConnectionFactory` and the
  `HttpClient` that backs `RestSyncRelay`).
- Provide a small fluent API so writing a SystemTest feels like writing a story, not
  wiring infrastructure.
- Be deterministic enough for CI: `runTest` virtual time, fake `Clock`, scheduler
  poll intervals overridable.

### Non-goals

- **Not** a replacement for unit tests. SystemTests are slower (a pairing test will
  spin up two Koin graphs, a Ktor test app, and grind through the scheduler) and
  should be reserved for flows that genuinely need cross-component coverage.
  `MockSyncRelay`-backed unit tests stay in their lane.
- **Not** a replacement for full e2e. They don't exercise real AIDL marshalling, real
  Unix socket framing, real Netty I/O, real Postgres/S3, or real platform crypto
  backends. The AIDL/socket layer still needs separate coverage (§7.5).
- Not running on iOS or JS targets in v1. JS already has its own in-process runtime
  (`JsRuntime`); iOS proxy is not yet implemented. JVM-only is the v1 scope.

## 3. Status quo — what exists today

### 3.1 `TestProxy` (`core-test-utils/.../TestProxy.kt`)

Hand-assembles a full proxy DI graph **without Koin**. Wraps:

- `LocalClientConnection` (in-process app↔proxy "transport"),
- `InMemoryObjectStore`-backed `Database`,
- `LocalTaskExecutor` for ops,
- `ProxyGateway`, `ClientRegistry`,
- one or more `additionalComponents: Map<String, TComponent>` injected at construction.

It's the right primitive for *one* device's proxy side. SystemTests will reuse it
where it fits — but the way it currently works for multi-device (manually
constructing two of them per test) is the seam this proposal formalises.

### 3.2 The relay over `testApplication` (the real impl, in-process)

The relay server already supports this pattern — it's how its own tests run. The
relevant pieces:

- **`fun Application.module(...)`** in `sync-relay-server/.../Application.kt:65`
  (with a back-compat zero-arg overload at line 170) installs the full routing,
  CORS, JWT auth, status pages, and the GC sweep coroutine. It's a normal Ktor
  module function — no Netty assumption.
- **Ktor's `io.ktor.server.testing.testApplication { }`** boots that module
  in-process and gives you a `client: HttpClient` whose requests dispatch directly
  into the server's pipeline. No socket, no port, no Netty.
- Existing relay tests use this verbatim — see
  `sync-relay-server/src/test/.../SyncEndpointAuthTest.kt:36` (`testApplication { application(productionApp()); val response = client.post(...) }`).

The proxy side is already shaped to plug in: **`RestSyncRelay`** at
`sync-service-proxy/.../RestSyncRelay.kt:36-42` takes its `HttpClient` and `baseUrl`
as constructor params. In production those come from the platform module
(Netty/CIO + the real relay URL). In a SystemTest we hand it the
`testApplication`'s `client` and an empty/relative `baseUrl` — every other line of
`RestSyncRelay` runs unchanged.

This is a strictly higher-fidelity replacement for what was originally going to be
`MockSyncRelay`-based SystemTests: the real `SyncRouting`, `RealmRouting`,
`RecoveryRouting`, `JwtAuthenticator`, `SyncMailbox`, and `HlcTipStore` all run.
A bug in any of them surfaces in the SystemTest, not in production.

`MockSyncRelay` (`sync-service-proxy/.../MockSyncRelay.kt`) **is not retired**. It
remains the right tool for single-proxy unit tests where speed matters and relay
fidelity doesn't — `InitiatePairingImplementationTest` and friends are correct to
keep using it. SystemTests just don't.

### 3.3 `LocalClientConnection`

Already the in-memory counterpart to `AndroidClientConnection` and the desktop socket
client connection. Used by `TestProxy` and by JS `JsRuntime`. **No changes needed**
for the v1 SystemTest framework — the app↔proxy boundary is already mockable.

The only concession: app and proxy live in the same JVM and the same `TestClient`
instance. We're not modelling AIDL marshalling errors or socket-framing edge cases.
That's a deliberate non-goal (§2).

### 3.4 Koin in production vs. tests

Production wires this graph in `proxyModule` (`control-shared/.../di/ProxyModule.kt`):

- `coreCommonModule` (the platform's DI base)
- `coreSchemaConfigs` named list (the per-service `*CommonModule`/`*ClientModule`
  schema registrations)
- `Database`, `ProxyGateway`, `ClientRegistry`, `AsyncTaskExecutor`, `TenshuClient`
- Two **platform-injected** bindings:
  - `named("platformClientConnectionFactory")` — AIDL on Android, socket on Desktop
  - `named("platformImplementationFactory")` — operation implementations
- Plus `SyncRelay` injected via the proxy app's platform module (`RestSyncRelay` in
  prod).

Tests today **don't use Koin** — `TestProxy` hand-rolls everything. This is a
divergence from production wiring, exactly the kind the user's
`feedback_mirror_production_wiring.md` memory cautions against. SystemTests fix that
divergence by going through Koin.

### 3.5 Multi-device prior art

`RevokeDevicePropagationTest` is the only test today that runs two proxies sharing
one relay (a `MockSyncRelay`). It works, but the wiring code (~90 lines) is bespoke
per test, and the relay it shares is a mock — so a divergence between
`MockSyncRelay`'s quorum semantics and the real `SyncMailbox`'s would not surface
here. There are no companion two-device tests for pairing, recovery, file sync, or
notification propagation — partly because the wiring is daunting and partly because
a mock relay can't faithfully model JWT auth or pairing-slot single-use semantics.

## 4. Design

### 4.1 Three concentric APIs

```
SystemTest (the test method, your code)
   │
   ├─ TestSystem            ← orchestrates the whole world
   │   │
   │   ├─ relayApp: TestApplication           ← real Application.module() in-process
   │   ├─ relayHttpClient: HttpClient         ← from testApplication; no socket
   │   ├─ relayKnownDevices: KnownDevicesStore ← shared, threaded into module()
   │   ├─ clock: FakeClock                    ← shared, advanceable
   │   ├─ clients: List<TestClient>           ← N devices
   │   │
   │   └─ shared CoroutineScope, schemas
   │
   └─ TestClient            ← one device's worth of stack
       │
       ├─ koinApp: KoinApplication    ← isolated per-client
       ├─ proxy side (Database, ProxyGateway, ClientRegistry, ops, mergers, scheduler)
       ├─ app  side (TenshuClient, request chains, view-model dependencies)
       ├─ identity (DeviceId, keypair, SyncManager)
       └─ wire seam: LocalClientConnection (app↔proxy)
                   + RestSyncRelay over the shared testApplication client (proxy↔relay)
```

`TestSystem` owns shared state (relay, clock, the in-memory transport hub).
Each `TestClient` owns one device's full stack and is conceptually isolated from
the others; they only meet through (a) the shared relay and (b) any out-of-band
helpers the test deliberately uses (e.g. "device A reads its pairing URI; we hand it
to device B"). That out-of-band channel is just a Kotlin reference between the two
`TestClient` Kotlin objects — exactly modelling QR-code transmission.

### 4.2 The two transport seams

| Seam | Production binding | SystemTest binding | Where it plugs in |
|---|---|---|---|
| **app ↔ proxy** | `AndroidClientConnection` / Unix socket | `LocalClientConnection` (already exists) | `proxyModule`'s `named("platformClientConnectionFactory")` |
| **proxy ↔ relay** | `RestSyncRelay` over Netty/CIO `HttpClient` | **`RestSyncRelay` over the shared `testApplication.client`** (real relay, no socket) | the `HttpClient` binding inside the test override module; `single<SyncRelay>` keeps the production `RestSyncRelay` |

Both seams are already abstracted — we don't need to refactor any production code.
For the relay seam we keep the production `RestSyncRelay`; the only swap is the
`HttpClient` it's given, which dispatches into the in-process `Application.module()`
instead of a real socket.

### 4.3 Koin per `TestClient`

Production uses one global `KoinApplication`. SystemTests need N. Koin supports this
via `koinApplication { ... }` — each app is independent, with its own scope and
state. We **never** call `startKoin` (that mutates the global). Each `TestClient`
holds its own `KoinApplication` and resolves through its own scope.

```kotlin
class TestClient internal constructor(
    val deviceId: String,
    val koinApp: KoinApplication,
) {
    inline fun <reified T : Any> get(qualifier: Qualifier? = null): T =
        koinApp.koin.get(qualifier = qualifier)

    val tenshuClient: TenshuClient get() = get()
    val syncManager: SyncManager   get() = get()
    val database: Database         get() = get()
    val proxyGateway: ProxyGateway get() = get()
    val executionContext: ExecutionContext
        get() = get<ClientRegistry>().getExecutionContext(deviceId)
    // ...
}
```

The Koin module list per client is exactly:

```kotlin
val testClientKoinModules = listOf(
    coreCommonModule,                  // production base
    proxyModule,                       // production proxy graph
    systemTestProxyOverridesModule,    // ← swaps the two transport bindings
    systemTestSyncWiringModule,        // ← installs SyncManager, scheduler, crypto
    systemTestIdentityModule,          // ← per-client device identity
    /* + the per-service *CommonModule and *ClientModule lists */
)
```

`systemTestProxyOverridesModule` (constructed by `TestSystemBuilder` once the
shared `testApplication` is up, so the `HttpClient` is in scope):

```kotlin
fun systemTestProxyOverridesModule(
    sharedRelayClient: HttpClient,    // from the shared testApplication
    sharedClock: FakeClock,
    deviceId: String,
    authTokenProvider: suspend () -> String?,
    ownerIdProvider: suspend () -> String?,
) = module {
    single<(String) -> ClientConnection>(named("platformClientConnectionFactory")) {
        val sharedConnection = LocalClientConnection()
        ;{ _ -> sharedConnection }
    }
    single<(String) -> OperationImplementation>(named("platformImplementationFactory")) {
        val factory: OperationImplementationFactory = get()
        ;{ id -> factory.create(id)!! }
    }
    // The production RestSyncRelay, just with a test HttpClient. baseUrl is empty
    // because the testApplication client routes by path.
    single<SyncRelay> {
        RestSyncRelay(
            baseUrl = "",
            httpClient = sharedRelayClient,
            context = get(),
            authTokenProvider = authTokenProvider,
            ownerIdProvider = ownerIdProvider,
        )
    }
    single<Clock> { sharedClock }
}
```

### 4.4 The `TestSystem` builder

Constructing a system is a fluent build; the result is a started, ready-to-use
fixture in your `runTest` block.

```kotlin
class SystemPairingTest {

    @Test fun pairing_handshakeCompletes_andDeviceB_seesHostsRealm() = runTest {
        val system = TestSystem.builder()
            .clock(FakeClock(start = INSTANT_2026_05_10_NOON))
            .schedulerPollingInterval(100.milliseconds)
            .build()
        try {
            val host = system.client("host", role = TestClientRole.RealmHost)
            val joiner = system.client("joiner", role = TestClientRole.JoiningDevice)

            // Host issues a pairing URI — this is exactly the production op call.
            val pairingUri = host.tenshuClient.execute(InitiatePairing()).uri

            // Out-of-band hop (QR code in real life): hand the URI to the joiner.
            joiner.tenshuClient.execute(SubmitPairingRequest(uri = pairingUri))

            // Pairing is async; advance scheduler ticks until both sides commit.
            system.advanceUntil { host.syncManager.pendingPairingRequests().isNotEmpty() }
            val req = host.syncManager.pendingPairingRequests().single()
            assertEquals(req.sas, joiner.syncManager.sasOf(pairingUri))

            // Approve.
            host.tenshuClient.execute(ApprovePairingRequest(req.id))
            system.advanceUntil { joiner.syncManager.realmId != null }

            // Joiner now sees the realm. Sanity: a Device entity for the host shows
            // up in the joiner's DB after the next sync drain.
            val hostDeviceId = host.syncManager.thisDeviceId
            system.advanceUntil { joiner.database.getDevice(hostDeviceId) != null }
        } finally {
            system.shutdown()
        }
    }
}
```

The pieces:

- `TestSystem.builder()` returns a `TestSystemBuilder` with knobs (clock, polling
  interval, additional Koin modules, custom schemas, relay `RelayConfig` —
  e.g. `devMode = false` for JWT-required runs vs `devMode = true` for
  fixture-fast runs).
- `.build()` opens **one shared** Ktor `testApplication`, calls
  `application { module(config, knownDevices, ...) }` with shared
  `KnownDevicesStore` / `RealmStore` / `HlcTipStore` instances, and captures the
  resulting `client: HttpClient`. The `TestSystem` owns the lifecycle.
- `.client(deviceId, role)` registers a fresh `TestClient`. Behind the scenes:
  - constructs a fresh `KoinApplication`,
  - includes the standard module list + the role-specific identity bootstrap
    (a `RealmHost` role calls `SyncManager.initialize(...)` and registers its
    device public key with the shared `KnownDevicesStore`; a `JoiningDevice`
    role leaves the realm uninitialised until pairing completes),
  - binds the **shared** relay `HttpClient`, `FakeClock`, and `CoroutineScope`
    via `systemTestProxyOverridesModule(...)`,
  - starts the scheduler.
- `system.advanceUntil { predicate }` drives `runTest`'s virtual time forward,
  letting scheduler ticks fire, until the predicate holds or a timeout trips.
  Wraps `delay(pollingIntervalMs)` on `runTest`'s test dispatcher — no real wall
  time elapses.
- `system.shutdown()` cancels schedulers, closes each client's `KoinApplication`,
  and tears down the shared `testApplication` (which cancels the relay's GC
  sweep coroutine via `ApplicationStopped` — see `Application.kt:162`).

### 4.5 Out-of-band channels

For pairing the URI passes via QR code in production. In a SystemTest we just pass
it as a `String` between two `TestClient` references. Same for recovery phrases —
the test prints them on host and types them on joiner. This is correct: the
out-of-band channel is genuinely outside the two transport layers we're modelling.

If we ever want to model a malicious or lossy OOB channel (e.g. SAS mismatch tests),
a tiny `OobChannel` abstraction in `system-test-utils` can drop / corrupt / replay.
Out of scope for v1.

### 4.6 Determinism

Two sources of nondeterminism today:

1. **Real coroutine dispatch.** `RevokeDevicePropagationTest` uses
   `runTest { ... backgroundScope }` and `delay(100)` polling. It works because of
   `runTest`'s test dispatcher, but the test sleeps in 100ms increments. We'll
   standardise on `runTest` + `system.advanceUntil` (predicate-driven, virtual time).
2. **Real `Clock`.** Sync HLCs use a wall-clock component; if we use `Clock.System`
   in tests, the HLC tip moves with wall time. SystemTests must inject a `FakeClock`
   that the test controls. `core-test-utils` already has one.

For HLC and JWT issuance specifically, both must read the same clock. The Koin
override binding ensures that.

### 4.7 New module: `:system-test-utils`

A new top-level module, JVM-only in v1 (no `js(IR)` or `androidMain` targets),
hosting:

- `TestSystem`, `TestSystemBuilder`, `TestClient`, `TestClientRole`
- `systemTestProxyOverridesModule`, `systemTestSyncWiringModule`,
  `systemTestIdentityModule`
- `FakeClock` advance helpers, `OobChannel` if/when needed
- Convenience matchers: `assertReplicatedTo(other) { ... }`,
  `assertPairingCommitted(host, joiner)`

Dependencies (Gradle):

- `implementation(project(":sync-relay-server"))` — to consume `Application.module()`
- `implementation("io.ktor:ktor-server-test-host:<version>")` — for `testApplication { }`
- `implementation` of all `*-test-utils`, `*-proxy`, `*-client`, plus Koin and the
  schema/op infrastructure

Therefore it sits **downstream** of every feature module *and* the relay server in the
dep graph — that's why it's a new module rather than living inside an existing
test-utils. (`:sync-relay-server` is configured as a Ktor application but is also a
normal JVM library; consuming its `main` classpath is fine.)

The actual SystemTests live in a sibling module `:system-tests` (or in the
`commonTest` of the most-relevant feature module — both are fine; we'll start with
the latter and promote to a dedicated module if and when test count justifies it).

## 5. Pairing as the v1 case study

The first SystemTest we'd write covers `sync-pairing-flow-improvements.md` end-to-end:

1. Host initialises a realm and registers its device public key with the relay's
   `KnownDevicesStore` (real `RealmStore`, real auth registration).
2. Host calls `InitiatePairing` → returns URI. The op routes through the real
   `:sync-relay-server`'s `POST /sync/pairing/request` and is stored in the real
   in-memory `RealmStore`.
3. Joiner calls `SubmitPairingRequest(uri)` → real
   `POST /sync/pairing/response` round-trip.
4. Both sides compute SAS; test asserts they match.
5. Host calls `ApprovePairingRequest(req.id)` → real `POST /sync/pairing/acknowledge`.
6. Joiner's `SyncManager` receives the response, completes the handshake, persists
   its device JWT, and registers its public key with the shared `KnownDevicesStore`.
7. Joiner's scheduler pulls `/sync/changes` with its real JWT; the relay verifies
   it via the real `JwtAuthenticator`; the host's `Device` block lands in the
   joiner's local DB.
8. Cleanup: `system.shutdown()` cancels schedulers, closes each client's Koin, and
   stops the shared `testApplication` (which triggers `ApplicationStopped` and the
   GC coroutine cancellation).

This single test would cover code paths currently spanning ~10 unit tests across
`:sync-service-proxy` and `:sync-relay-server`, plus the wire layer that no unit
test exercises today.

## 6. Implementation phases

| Phase | Scope | Deliverable |
|---|---|---|
| **0 (prereq)** | `Clock.System.now()` audit | Replace direct `Clock.System.now()` calls in `:sync-service-proxy` with an injected `Clock`. Required for deterministic SystemTests; independent of the framework, so can land in its own PR before phase 1 starts. Confirmed §9.5. |
| **1** | Foundation + pairing SystemTest | New `:system-test-utils` module; `TestSystem`, `TestClient`, `TestClientRole`, the override Koin module; the pairing test from §5 lands **together** with the framework as the first real driver. Going straight to pairing (skipping a port of `RevokeDevicePropagationTest`) per §9.3. Drive any minor API gaps surfaced (e.g. exposing `pendingPairingRequests()` if it's currently private). |
| **2** | Recovery SystemTest | Write recovery-phrase claim across two clients. |
| **3** | File sync SystemTest | Two clients in a realm; host uploads a file; joiner observes via `FileSystem.observe`. Validates `FolderMerger` end-to-end. |
| **4** | OOB channel + adversarial cases | Optional. Only if the prior phases surface real flakiness or if we need SAS-mismatch / replay tests. |

Each phase ships independently; phases 2–3 are parallelisable once phase 1 lands.
`RevokeDevicePropagationTest` keeps its `MockSyncRelay` form indefinitely — it can
migrate opportunistically if there's something to gain, but is not on the critical
path.

## 7. Risks & open questions

1. **Koin global vs isolated.** Koin's most-used path is `startKoin { }` (global).
   Per-test `koinApplication { }` works but has fewer code paths exercised. **Risk:**
   tests pass against `koinApplication` but a real device hits a binding issue under
   `startKoin`. **Mitigation:** the override module is small and additive; the bulk
   of bindings come from the production modules included verbatim.

2. **`TestProxy` deprecation?** `TestProxy` continues to exist for single-proxy unit
   tests — it's lighter weight and doesn't pay the Koin cost. SystemTests are a
   superset. We don't deprecate; both have a place. **Decision:** keep `TestProxy`
   for unit tests, use SystemTest for cross-component flows.

3. **Schedulers and `runTest` virtual time.** `SyncScheduler` uses `delay`, which
   `runTest`'s test dispatcher fast-forwards through. But if anything inside the
   scheduler calls `Clock.System` directly instead of an injected `Clock`, we break
   determinism. **Action:** audit `:sync-service-proxy` for direct
   `Clock.System.now()` calls before phase 1. If found, file as a tech-debt item
   blocking phase 2.

4. **Module dependency cycle risk.** `:system-test-utils` depends on every feature
   module. If a feature module ever needed to depend on `system-test-utils` (say,
   for a fixture used in its own tests), that's a cycle. **Mitigation:** keep
   `:system-test-utils` strictly downstream — feature modules should never depend
   on it, even in `testImplementation`.

5. **The wire-format gap (narrowed).** Because the proxy↔relay path now runs the
   real `RestSyncRelay` against the real Ktor routing, **HTTP serialisation,
   content-type handling, status codes, JWT auth, and routing are all exercised**.
   The remaining gap is the **app↔proxy** seam: AIDL marshalling on Android and
   Unix-socket framing on Desktop are still bypassed by `LocalClientConnection`.
   **Action:** keep a small AIDL-marshal test suite (already exists in `:core-aidl`)
   for that boundary. The Netty I/O layer itself (TLS, keep-alive, HTTP framing)
   is also unexercised — but `testApplication` is what every Ktor team uses to
   validate routes, and Netty has its own coverage upstream.

6. **Naming.** "SystemTest" is the working name. If during implementation it feels
   wrong (clashes, confusion with industry usage), the naming is still cheap to
   change before we have a sprawl of files matching `*SystemTest.kt`. Default:
   stick with "SystemTest" unless there's a concrete grievance.

7. **JS parity.** The JS runtime has its own in-process equivalent (`JsRuntime` +
   `LocalTaskExecutor`). Should we eventually run SystemTests on JS as well, to
   catch JS-specific runtime divergence? **Open.** Not v1 scope; revisit after the
   web track stabilises. Note: Ktor's `testApplication` is JVM-only, so a JS
   SystemTest framework would need a different relay-bypass mechanism (probably a
   pure-JS in-memory `HttpClient` engine pointed at a JS-side simulation, or
   Node-side process running the JVM relay — both involve real design work).

8. **Cost per test.** Booting a `testApplication` is heavier than constructing a
   `MockSyncRelay` (the relay's full module function runs, GC coroutine starts).
   Order-of-magnitude estimate: low tens of milliseconds per `TestSystem`. Should
   stay well under a second per SystemTest including teardown. If this turns out
   to be a problem (lots of SystemTests, slow CI), we can reuse one
   `testApplication` across tests in a class via `@BeforeTest`/`@AfterTest`
   discipline — but that's a follow-up if needed, not v1.

## 8. What this proposal does **not** do

- Doesn't change `TestProxy`, `MockSyncRelay`, `LocalClientConnection`, or any
  production module. Strictly additive.
- Doesn't move existing tests into the new framework wholesale. `RevokeDevicePropagationTest`
  is the only port (as a smoke test for the framework); other multi-device tests can
  migrate opportunistically.
- Doesn't introduce a global Koin scope for tests. Each test creates and tears
  down its own `KoinApplication` instances.
- **Defers** the `docs/testing.md` update to *after* phase 1 lands. Adding a
  three-tier description now would document infrastructure that doesn't exist yet
  and would need rewriting if the framework's shape shifts during implementation.
  When phase 1 lands, the implementing PR is responsible for adding the section
  to `docs/testing.md` and the back-link to this proposal.

## 9. Decisions (resolved 2026-05-10)

1. **Module name** — `:system-test-utils`. (`:flow-test-utils` rejected: too easily
   confused with Kotlin `Flow`.)
2. **Test-class suffix** — `*SystemTest.kt`. (Same reasoning.)
3. **Phase 1 MVP** — go straight to the pairing test as the framework's first
   driver. The `RevokeDevicePropagationTest` port is dropped from the critical
   path; its existing `MockSyncRelay` form keeps providing coverage in
   `:sync-service-proxy/commonTest` and can migrate later only if there's
   something to gain.
4. **`RelayConfig.devMode`** — `false` for SystemTests. Exercising the real JWT
   path is one of the points; tests that genuinely need fixture-fast can opt
   into `devMode = true` via a builder knob.
5. **`Clock.System.now()` in `:sync-service-proxy`** — hard ban. Replaced with
   an injected `Clock` as a phase 0 prereq (§6). Determinism beats convenience
   and makes the rest of the framework simpler.
6. **§7 risks** — accepted as written.
