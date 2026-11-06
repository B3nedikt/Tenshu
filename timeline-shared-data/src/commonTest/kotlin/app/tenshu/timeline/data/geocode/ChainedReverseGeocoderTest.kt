package app.tenshu.timeline.data.geocode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tiered chain (place-geocoding.md §3 / timeline-services.md §3.4): first non-null wins. */
class ChainedReverseGeocoderTest {

    private val berlin = 525_200_000L to 134_050_000L
    private val cafe = GeocodeResult(label = "Blue Bottle", city = "Berlin", countryCode = "de")
    private val street = GeocodeResult(label = "Oranienstraße")

    @Test
    fun firstNonNullAnswerWins() = runTest {
        val first = FakeReverseGeocoder(mapOf(berlin to cafe))
        val second = FakeReverseGeocoder(mapOf(berlin to street))
        val chain = ChainedReverseGeocoder(listOf(first, second))

        assertEquals(cafe, chain.resolve(52.52, 13.405))
        // The later tier is never asked once an earlier one answered.
        assertEquals(0, second.calls.size)
    }

    @Test
    fun answerlessTierIsSkipped() = runTest {
        val empty = FakeReverseGeocoder() // no canned results — always null
        val fallback = FakeReverseGeocoder(mapOf(berlin to street))
        val chain = ChainedReverseGeocoder(listOf(empty, fallback))

        assertEquals(street, chain.resolve(52.52, 13.405))
        assertEquals(1, empty.calls.size)
    }

    @Test
    fun throwingTierIsTreatedAsAnswerless() = runTest {
        val broken = object : ReverseGeocoder {
            override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? =
                throw IllegalStateException("provider garbage")
        }
        val fallback = FakeReverseGeocoder(mapOf(berlin to cafe))

        assertEquals(cafe, ChainedReverseGeocoder(listOf(broken, fallback)).resolve(52.52, 13.405))
    }

    @Test
    fun noTierAnswersYieldsNull() = runTest {
        assertNull(ChainedReverseGeocoder(emptyList()).resolve(52.52, 13.405))
        assertNull(ChainedReverseGeocoder(listOf(FakeReverseGeocoder())).resolve(52.52, 13.405))
    }
}
