package app.tenshu.maps.data

import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The region reads feed the app's **shared** reachability signal (maps-app.md §7.1) with a
 * load-bearing failure taxonomy: `installed()`/`deleteRegion` are proxy-local (their failures ARE
 * a transport signal); `catalog()`/`downloadRegion` may fail with Control healthy (region-host
 * fetch) and so mark nothing. Dead direction = a proxy with **no mapregion implementations**, so
 * every op fails exactly like an unreachable Control; healthy direction = the
 * [InProcessMapRegionProxy] dev graph.
 *
 * ⚠⚠ Only the *unreachable* half is a repository's to report. Nothing here marks a success any
 * more — `ControlReachability.markReachable` is `internal` and the probe owns that direction.
 */
class MapsRegionReachabilityTest {

    @Test
    fun deadProxyMarksUnreachableAndDegradesInsteadOfThrowing() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().apply {
                registerConfig(mapRegionServiceCommonModule)
                registerConfig(mapRegionServiceClientModule)
            },
            coroutineScope = this,
        )
        val reach = mapsControlReachability(clientOf(proxy), probeScope = null, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val repo = ClientRegionRepository(clientOf(proxy), reach = reach)
        assertNull(reach.state.value)

        // ⚠⚠ regions() now says it could not READ the catalogue, where it used to answer an
        // empty list — which the picker rendered as "there are no maps to download", about the one
        // screen whose purpose is downloading maps.
        assertIs<RegionsRead.Unavailable>(repo.regions())
        // ⚠ And it no longer flips the reachability signal by itself: a catalogue fetch reaches the
        // region host, which can be down while the proxy is healthy. The transport edge and the
        // probe own that verdict now.
        assertEquals(null, reach.state.value)

        // download(): a folded failure is ambiguous (could be the region host) — but against a
        // dead proxy it must still return false without crashing the caller's scope.
        assertFalse(repo.download("de-bayern"))

        // delete(): proxy-local → failure keeps the signal down; never throws.
        repo.delete("at")
        assertEquals(false, reach.state.value)
    }

    /**
     * ⚠⚠ **A healthy read no longer asserts reachability, and that is the change.** This test used
     * to require the opposite — `assertEquals(true, reach.state.value)` after a successful region
     * read — because the repository called `markReachable` on each success. It no longer can:
     * setting the state cancels the probe (`ControlReachability.markReachable`) and skips the
     * recovery, and a repository cannot tell a served read from a cached one. So a healthy read
     * leaves the signal **unknown** here, and the probe is what resolves it.
     */
    @Test
    fun healthyReadsDoNotAssertReachability_becauseTheProbeDecides() = runTest {
        val proxy = InProcessMapRegionProxy(serviceScope = backgroundScope)
        val reach = mapsControlReachability(proxy.client, probeScope = null, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        val repo = ClientRegionRepository(proxy.client, reach = reach)
        assertNull(reach.state.value)

        assertIs<RegionsRead.Regions>(repo.regions())
        repo.delete("at")
        assertTrue(repo.download("de-bayern"))

        // ⚠ Still `null` — unknown, not false. Nothing here is entitled to an opinion, and the
        // shell renders no banner for `null` any more than it did before.
        assertNull(reach.state.value)
    }

    private fun clientOf(proxy: TestProxy): TenshuClient {
        val env = TenshuEnvironment(
            taskExecutor = proxy.localTaskExecutor,
            schemaRegistry = proxy.schemaRegistry,
            tContext = proxy.tContext,
            requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
        )
        return DefaultTenshuClient(env)
    }
}
