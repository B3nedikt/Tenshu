package org.tenshu.mapregion.proxy

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The locality tiers, which used to be unreachable.**
 *
 * `ReverseGeocode` returned a bare `TString`, so `ProxyReverseGeocoder` could only build
 * `GeocodeResult(label = …)` and `city` defaulted to `""` on every lookup. Measured on a real store:
 * nine geocoded places, nine blank cities — which silently disabled `PlaceLabelEnricher`'s
 * accuracy-coarsening, whose rule is `accuracyM > 80 && result.city.isNotBlank()`.
 *
 * ⚠⚠ **The enricher's own coarsening test passed throughout**, because its fake geocoder supplies a
 * city the production path could never deliver. That is why the assertion that matters lives *here*,
 * at the seam where the value is actually produced, and not one layer up where a fake can invent it.
 *
 * Pure, like [PhotonGeocoderTest] beside it and for the same reason: the network path is a thin
 * wrapper, and the value is in *which* feature the locality is read from. Testing that through
 * `MockEngine` would also drag in `withTimeoutOrNull`, whose `delay` `runTest`'s virtual clock
 * fast-forwards past while the engine is still answering — making every lookup `null` for reasons
 * unrelated to the code under test.
 */
class PhotonGeocoderPlaceTest {

    private val qLat = 49.0323
    private val qLng = 20.9034

    private fun feature(
        name: String? = null,
        osmKey: String? = null,
        city: String? = null,
        countryCode: String? = null,
        lat: Double = 49.03239,
    ) = PhotonFeature(
        geometry = PhotonGeometry(listOf(20.9034, lat)),
        properties = PhotonProperties(name = name, osmKey = osmKey, city = city, countryCode = countryCode),
    )

    /** The measured shape: the venue wins the label and carries no locality; the street carries it. */
    private val venue = feature(name = "Office", osmKey = "office")
    private val street = feature(name = "Street", osmKey = "highway", city = "München", countryCode = "DE", lat = 49.03248)

    @Test
    fun theLocalityComesBackAlongsideTheLabel() {
        val place = PhotonGeocoder.localityOf(listOf(venue, street), qLat, qLng)
        assertEquals("Office", place?.label)
        assertEquals("München", place?.city, "the city was the whole point of widening the op")
        assertEquals("DE", place?.countryCode)
    }

    @Test
    fun aLocalityOnANonLabelFeatureIsStillFound() {
        // ⚠ The chosen label carries no city of its own. Reading the locality only off the *chosen*
        // feature would have left it blank — which is the bug, one layer down.
        assertTrue(PhotonGeocoder.localityOf(listOf(venue, street), qLat, qLng)!!.city.isNotBlank())
    }

    @Test
    fun aMissingLocalityStaysBlankRatherThanInvented() {
        // ⚠⚠ Blank means "this tier did not say", never "no city here" — the enricher reads it as a
        // reason to skip coarsening, so a fabricated value would be strictly worse than an absent one.
        val place = PhotonGeocoder.localityOf(listOf(venue), qLat, qLng)
        assertEquals("Office", place?.label)
        assertEquals("", place?.city)
        assertEquals("", place?.countryCode)
    }

    @Test
    fun nothingLabelledIsNull() {
        assertNull(PhotonGeocoder.localityOf(emptyList(), qLat, qLng))
    }

    @Test
    fun theDefaultEgressImplementationDegradesToLabelOnly() = runTest {
        // A SAM fake — and any future tier with no locality, such as an offline one reading POIs out
        // of map tiles — still satisfies the interface. No HTTP, so no virtual-clock hazard.
        val sam = ProxyReverseGeocoderEgress { _, _ -> "Somewhere" }
        assertEquals(GeocodedLocality(label = "Somewhere"), sam.place(0.0, 0.0))
    }
}
