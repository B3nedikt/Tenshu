package org.tenshu.mapregion.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.fold
import org.tenshu.core.mapregion.MeteredNetworkRefusedError
import org.tenshu.core.mapregion.downloadRegion
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.mapregion.test.FakeRegionStore
import org.tenshu.mapregion.test.createMapRegionProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metered-network gate (`maps-region-delivery.md` D5 / §7.1 track 3g), driven end-to-end through a
 * real client over an in-process proxy.
 *
 * ⚠⚠ End-to-end **on purpose**: the refusal is only useful if the caller can tell it apart from a dead
 * connection, which means the [MeteredNetworkRefusedError] composite has to survive the round trip and
 * materialise on the far side. Asserting the executor threw would prove nothing about that — and a
 * composite registered on only one side throws on every call (see `ReverseGeocodePlaceOpTest`).
 * Reading a field off the returned error is what proves it was really built.
 */
class MeteredDownloadGateTest {

    private fun clientOn(metered: Boolean, store: FakeRegionStore = FakeRegionStore()): TenshuClient {
        val proxy = createMapRegionProxy(
            regionStore = store,
            meteredNetwork = { metered },
        )
        return DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
    }

    @Test
    fun aMeteredNetworkRefusesWithAnErrorTheCallerCanActOn() = runTest {
        val result = clientOn(metered = true).mapRegionService.downloadRegion("de-bayern")

        val error = result.fold(onSuccess = { null }, onFailure = { it })
        assertTrue(
            error is MeteredNetworkRefusedError,
            "a generic failure is indistinguishable from a dead connection, so the caller could not " +
                "offer the override — got ${error?.let { it::class.simpleName }}",
        )
        // Reading the field is the part that proves the composite materialised rather than merely
        // arriving as some error-shaped thing.
        assertEquals("de-bayern", error.regionId.get())
    }

    @Test
    fun theExplicitOverrideGetsThrough() = runTest {
        // The whole point of a per-call flag rather than a preference: a deliberate "yes, use mobile
        // data" proceeds immediately, without sending the user to a settings screen.
        val result = clientOn(metered = true).mapRegionService
            .downloadRegion("de-bayern", allowMetered = true)

        val error = result.fold(onSuccess = { null }, onFailure = { it })
        assertNull(error, "the override was ignored: $error")
    }

    @Test
    fun anUnmeteredNetworkNeedsNoOverride() = runTest {
        val result = clientOn(metered = false).mapRegionService.downloadRegion("de-bayern")
        assertNull(result.fold(onSuccess = { null }, onFailure = { it }))
    }

    @Test
    fun aProxyWithNoSignalWiredBehavesExactlyAsBefore() = runTest {
        // `MeteredNetworkSignal.Unknown` is the default on every platform that wires nothing (desktop and
        // iOS both hardcode UNMETERED today). It must fail OPEN: turning "we don't know the network"
        // into "you may not download" would be a worse failure than the one the gate guards, and silent.
        val proxy = createMapRegionProxy(regionStore = FakeRegionStore())
        val client = DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
        assertNull(
            client.mapRegionService.downloadRegion("de-bayern")
                .fold(onSuccess = { null }, onFailure = { it }),
        )
    }
}
