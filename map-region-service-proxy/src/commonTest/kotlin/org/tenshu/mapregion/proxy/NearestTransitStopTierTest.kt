package org.tenshu.mapregion.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.getOrNull
import org.tenshu.core.mapregion.nearestTransitStop
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.mapregion.test.createMapRegionProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§28.2 — which tier answers `nearestTransitStop`, and in which order.**
 *
 * Driven end-to-end through a real client over an in-process proxy, so it pins the composition as the
 * app experiences it rather than the two tiers in isolation.
 *
 * ⚠⚠ The order is a **privacy** decision, not a latency one: tiles-first means a coordinate near a
 * station is answered from our own tiles instead of being sent to komoot's public Photon instance. A
 * refactor that reordered these for any reason would be a silent regression of that, which is why it
 * has a test of its own rather than being left to the KDoc.
 */
class NearestTransitStopTierTest {

    /** Mirrors `MapRegionServiceTest.clientOf` — a real client over an in-process proxy. */
    private fun clientFor(
        tiles: TransitStopTiles?,
        geocoder: ProxyReverseGeocoderEgress?,
    ): TenshuClient {
        val proxy = createMapRegionProxy(reverseGeocoder = geocoder, transitStopTiles = tiles)
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
    fun theTileTierAnswersAndTheNetworkIsNeverAsked() = runTest {
        var networkCalls = 0
        val client = clientFor(
            tiles = { _, _, _ -> "Odeonsplatz" },
            geocoder = object : ProxyReverseGeocoderEgress {
                override suspend fun label(lat: Double, lng: Double) = null
                override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? {
                    networkCalls++
                    return "Should Not Be Used"
                }
            },
        )

        val stop = client.mapRegionService.nearestTransitStop(481_425_000, 115_775_000, 300).getOrNull()

        assertEquals("Odeonsplatz", stop?.get())
        // ⚠ The assertion that carries the privacy claim: not merely "the tile name won", but that the
        // coordinate never left. A tiles-first implementation that still called the network to compare
        // would pass a value-only check.
        assertEquals(0, networkCalls, "the coordinate was sent to the network tier anyway")
    }

    @Test
    fun aTileMissFallsBackToTheNetwork() = runTest {
        val client = clientFor(
            tiles = { _, _, _ -> null }, // uncatalogued region, or no POI coverage
            geocoder = object : ProxyReverseGeocoderEgress {
                override suspend fun label(lat: Double, lng: Double) = null
                override suspend fun transitStop(lat: Double, lng: Double, withinM: Double) = "München Ost"
            },
        )

        val stop = client.mapRegionService.nearestTransitStop(481_272_000, 116_045_000, 300).getOrNull()

        assertEquals("München Ost", stop?.get())
    }

    @Test
    fun aBlankTileAnswerIsTreatedAsAMissNotAsAnAnswer() = runTest {
        // ⚠ A blank string is how this op says "no stop" on the wire, so a tier returning `""` must not
        // short-circuit the next one — otherwise a tier bug silently suppresses the fallback everywhere.
        val client = clientFor(
            tiles = { _, _, _ -> "" },
            geocoder = object : ProxyReverseGeocoderEgress {
                override suspend fun label(lat: Double, lng: Double) = null
                override suspend fun transitStop(lat: Double, lng: Double, withinM: Double) = "Odeonsplatz"
            },
        )

        assertEquals(
            "Odeonsplatz",
            client.mapRegionService.nearestTransitStop(481_425_000, 115_775_000, 300).getOrNull()?.get(),
        )
    }

    @Test
    fun withNeitherTierTheAnswerIsBlankNotAnError() = runTest {
        // The iOS/JS posture: no gzip binding and no geocoder configured. The caller must read a plain
        // "no stop", never a failure — `PlaceLabelEnricher` treats blank as "keep the city".
        val client = clientFor(tiles = { _, _, _ -> null }, geocoder = null)
        val stop = client.mapRegionService.nearestTransitStop(481_425_000, 115_775_000, 300).getOrNull()
        assertTrue(stop?.get().isNullOrEmpty(), "expected blank, got ${stop?.get()}")
    }
}
