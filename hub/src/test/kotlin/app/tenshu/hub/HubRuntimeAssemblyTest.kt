package app.tenshu.hub

import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * H1 deliverable (a): the hub's **production DI assembly** constructs, starts headless,
 * and stops cleanly — with durable state proving a restart keeps the device identity.
 * No relay is involved: the runtime stays unpaired, so the storage-gated scheduler never
 * makes a relay request; the relay URL is still pinned to a dead local port so any
 * accidental egress fails fast instead of touching the production relay.
 */
class HubRuntimeAssemblyTest {

    private val dataRoot = createTempDirectory("hub-assembly-test").toFile()

    /** Dead local relay: any unexpected relay call fails fast + offline. */
    private val deadRelay = module {
        single(named("syncRelayUrl")) { "http://127.0.0.1:9" }
    }

    @AfterTest
    fun tearDown() {
        dataRoot.deleteRecursively()
    }

    @Test
    fun assemblesStartsHeadlessAndStopsCleanly_withDurableDeviceIdentity() = runBlocking {
        val first = HubRuntime.assemble(dataRoot = dataRoot, overrides = listOf(deadRelay))
        first.start()
        assertFalse(first.isPaired(), "a fresh data dir must come up unpaired")
        val deviceId = assertNotNull(first.syncManager.currentDeviceId)
        first.shutdown()

        // "Survives restart": a second assembly over the SAME data dir (fresh Koin
        // modules — the builder contract) resumes the same device identity from disk.
        val second = HubRuntime.assemble(dataRoot = dataRoot, overrides = listOf(deadRelay))
        second.start()
        assertEquals(
            deviceId,
            second.syncManager.currentDeviceId,
            "device identity must be durable across a hub restart",
        )
        second.shutdown()
    }

    /**
     * The cadence knob has to reach the scheduler as a `Long` under this name — that is what the
     * desktop init hook resolves (`PlatformProxyModule`, `getOrNull<Long>(named(...))`). For as
     * long as the hub existed it did not: `{ it }` inside the Koin definition bound the definition's
     * `ParametersHolder`, the lookup answered null, and every hub ran at the 30 s production
     * cadence (`docs/tech-debt.md`, "The hub's sync cadence override never reached its
     * scheduler", 2026-09-13). Asserting the resolved TYPE is the guard: reverting the named
     * lambda parameter in `HubRuntime.assemble` fails it with `expected:<250> but was:<null>`.
     */
    @Test
    fun syncIntervalOverride_reachesTheSchedulerAsALong() = runBlocking {
        val runtime = HubRuntime.assemble(
            dataRoot = dataRoot,
            syncIntervalMs = 250,
            overrides = listOf(deadRelay),
        )
        try {
            assertEquals(
                250L,
                runtime.koin.getOrNull<Long>(named("syncPollingIntervalMs")),
                "the override must resolve as a Long under the name the desktop init hook reads",
            )
            runtime.start()
        } finally {
            runtime.shutdown()
        }
    }

    /**
     * The programmatic seam refuses what the CLI refuses: a non-positive cadence would make
     * `SyncScheduler.awaitOrTimeout` return at once and the loop spin. Before the binding was
     * fixed such a value was silently dropped, which is the only reason it was ever harmless.
     */
    @Test
    fun aNonPositiveSyncInterval_isRejectedAtAssembly() {
        for (bad in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException>("syncIntervalMs = $bad must be rejected") {
                HubRuntime.assemble(dataRoot = dataRoot, syncIntervalMs = bad, overrides = listOf(deadRelay))
            }
        }
    }
}
