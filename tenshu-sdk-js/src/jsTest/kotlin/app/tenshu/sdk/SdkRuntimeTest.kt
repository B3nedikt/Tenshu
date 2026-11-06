package app.tenshu.sdk

import kotlin.test.BeforeTest

// Unreachable relay sink for the in-process runtime tests. Port 9 is the RFC 863
// Discard service — unbound on CI, so any background relay request fails fast
// with "connection refused" instead of reaching a real host.
internal const val TEST_RELAY_SINK_URL = "http://localhost:9"

// Shared base for every suite that drives the singleton [JsRuntime]. Mirrors what
// apex does at bundle load: point the relay at a URL *before* any sync-touching
// call, so the relay's lazy is never constructed under the production default
// (`https://relay.tenshu.app`).
//
// Why this is load-bearing: the runtime can boot already sync-enabled. A prior
// run leaves a `RECOVERY_PHRASE` in IndexedDB (Realm.create persists it), so
// `SyncManagerImpl`'s constructor auto-loads it and `isEnabled()` flips true with
// no `configure()` having run. The first executionContext build then starts the
// `SyncScheduler`, whose first tick dispatches a relay op and bakes in whatever
// URL is current. `JsRuntime.configure(...)` rejects a *later* URL change, so if
// that first construction wins the default, RealmTest's `configure(localhost:9)`
// throws — a flaky, order- and persisted-state-dependent failure, plus a latent
// "tests hit the production relay" hazard.
//
// Configuring the sink in `@BeforeTest` (inherited by subclasses, idempotent for
// the same URL) guarantees the sink is set before any extending suite builds the
// runtime graph, so every relay construction lands on the unreachable sink.
//
// Integration suites that target a real relay (`@Ignore`d, configure their own
// `localhost:8080`) deliberately do NOT extend this — their `configure(8080)`
// would otherwise collide with the sink.
abstract class SdkRuntimeTest {
    @BeforeTest
    fun configureRelaySink() {
        TenshuSdk.configure(TEST_RELAY_SINK_URL)
    }
}
