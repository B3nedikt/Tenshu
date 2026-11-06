package org.tenshu.mapregion.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.fold
import org.tenshu.core.mapregion.reverseGeocodePlace
import org.tenshu.mapregion.client.mapRegionService
import org.tenshu.mapregion.test.createMapRegionProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `reverseGeocodePlace` driven end-to-end through a real client over an in-process proxy — the op
 * **returning its composite**, not the geocoder's ranking (which `PhotonGeocoderPlaceTest` covers).
 *
 * ⚠⚠ **Why this test exists.** The op returns the [org.tenshu.core.mapregion.GeocodedPlace]
 * composite, which the proxy builds. Registered only in `mapRegionServiceClientModule`, via
 * `type(...)`, the type gets a *request-object* factory and nothing else, so
 * `SchemaRegistry.compositeFactory` has no entry for it and the proxy's `GeocodedPlace.create(...)`
 * throws `No composite factory registered for type: …` on **every** call. `LocalTaskExecutor`'s
 * catch-all wraps that into an opaque `error/internal`, which `ProxyReverseGeocoder.resolve` folds
 * to `null` and reads as "nothing found" — so the proxy venue-naming tier dies silently and the
 * Android chain falls through to the device geocoder, which answers with the street rather than the
 * venue.
 *
 * Nothing else catches it: the geocoder's own tests never build the composite, the schema-lockstep
 * beacon compares format versions, and the announce gate compares service versions — neither moves.
 * On a device the only signal is one `IPC Client -> Failure` line.
 *
 * So this asserts the **materialised fields**, not merely that the call succeeded — reading them is
 * what proves the composite was actually built.
 */
class ReverseGeocodePlaceOpTest {

    private fun clientFor(geocoder: ProxyReverseGeocoderEgress?): TenshuClient {
        val proxy = createMapRegionProxy(reverseGeocoder = geocoder)
        return DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = proxy.localTaskExecutor,
                schemaRegistry = proxy.schemaRegistry,
                tContext = proxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(proxy.localTaskExecutor, ctx) },
            ),
        )
    }

    /** A tier that answers — the case that throws for every caller when the composite is unregistered. */
    @Test
    fun aFoundPlaceMaterialisesAllThreeLocalityTiers() = runTest {
        val client = clientFor(
            object : ProxyReverseGeocoderEgress {
                override suspend fun label(lat: Double, lng: Double) = "Office"
                override suspend fun place(lat: Double, lng: Double) =
                    GeocodedLocality(label = "Office", city = "München", countryCode = "DE")
            },
        )

        val result = client.mapRegionService.reverseGeocodePlace(48_137_154L, 11_576_124L)

        val place = result.fold(onSuccess = { it }, onFailure = { null })
        assertEquals("Office", place?.label?.get(), "the op failed, or its composite never materialised")
        // city/countryCode are what the composite carries — a blank city silently disables
        // PlaceLabelEnricher's accuracy-coarsening, whose second conjunct is `city.isNotBlank()`.
        assertEquals("München", place?.city?.get())
        assertEquals("DE", place?.countryCode?.get())
    }

    /**
     * The not-found path must still return a *built* composite with blank fields — the app maps a
     * blank label to `null` and falls through the chain. A failure here would be read as "nothing
     * found" too, which is exactly how the defect above stayed invisible; only the blank-vs-failed
     * distinction tells them apart, so it is asserted rather than assumed.
     */
    @Test
    fun nothingFoundIsBlankFieldsNotAFailure() = runTest {
        val client = clientFor(
            object : ProxyReverseGeocoderEgress {
                override suspend fun label(lat: Double, lng: Double): String? = null
                override suspend fun place(lat: Double, lng: Double): GeocodedLocality? = null
            },
        )

        val place = client.mapRegionService
            .reverseGeocodePlace(48_137_154L, 11_576_124L)
            .fold(onSuccess = { it }, onFailure = { null })

        assertEquals("", place?.label?.get(), "a miss must be a successful blank, not a Failure")
        assertEquals("", place?.city?.get())
        assertEquals("", place?.countryCode?.get())
    }

    /** An unconfigured tier (`geocodingEndpoint = null`) is a blank answer, not an error either. */
    @Test
    fun anUnconfiguredGeocoderIsAlsoBlank() = runTest {
        val place = clientFor(null).mapRegionService
            .reverseGeocodePlace(48_137_154L, 11_576_124L)
            .fold(onSuccess = { it }, onFailure = { null })

        assertEquals("", place?.label?.get())
    }
}
