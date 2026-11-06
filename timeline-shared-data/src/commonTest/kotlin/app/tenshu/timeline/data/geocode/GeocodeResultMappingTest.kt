package app.tenshu.timeline.data.geocode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The platform-neutral address→[GeocodeResult] precedence (place-geocoding.md G2) — the one
 * piece of the Android geocoder that is pure logic, tested device-free here; the framework
 * shim around `android.location.Geocoder` is covered by the smoke gate / on-device pass.
 */
class GeocodeResultMappingTest {

    @Test
    fun poiNameWinsAndCarriesCityAndCountry() {
        val result = geocodeResultOf(
            featureName = "Bistro",
            subThoroughfare = "12",
            thoroughfare = "Street",
            locality = "Lisbon",
            countryCode = "PT",
        )
        assertEquals(GeocodeResult(label = "Bistro", city = "Lisbon", countryCode = "PT"), result)
    }

    @Test
    fun houseNumberFeatureNameFallsBackToStreet() {
        // Android's featureName degrades to the house number (== subThoroughfare) when no POI
        // is known at the coordinate — "12" is not a place name.
        val result = geocodeResultOf(
            featureName = "12",
            subThoroughfare = "12",
            thoroughfare = "Street",
            locality = "Lisbon",
            countryCode = "PT",
        )
        assertEquals("Street", result?.label)
    }

    @Test
    fun blankStreetFallsBackToLocality() {
        val result = geocodeResultOf(
            featureName = null,
            subThoroughfare = null,
            thoroughfare = " ",
            locality = "Sintra",
            countryCode = "PT",
        )
        assertEquals("Sintra", result?.label)
        assertEquals("Sintra", result?.city)
    }

    @Test
    fun allBlankIsNoAnswer() {
        assertNull(
            geocodeResultOf(
                featureName = null,
                subThoroughfare = null,
                thoroughfare = null,
                locality = "",
                countryCode = "PT",
            ),
        )
    }

    @Test
    fun missingCityAndCountryMapToEmpty() {
        val result = geocodeResultOf(
            featureName = "Miradouro de Santa Luzia",
            subThoroughfare = null,
            thoroughfare = null,
            locality = null,
            countryCode = null,
        )
        assertEquals(GeocodeResult(label = "Miradouro de Santa Luzia"), result)
    }
}
