package org.tenshu.mapregion.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage of [PhotonGeocoder.bestLabel] — the P3b venue-preferring ranking
 * (`timeline-recognition-and-place-naming.md` §4.2). The network path is a thin wrapper; the value
 * is in *which* of Photon's candidates becomes the label, so that is what is tested, purely.
 *
 * All fixtures sit around one Munich query point; offsetting only latitude keeps the distances
 * legible (~0.0001° ≈ 11 m north).
 */
class PhotonGeocoderTest {

    private val qLat = 48.150
    private val qLng = 11.586

    private fun feature(
        name: String? = null,
        street: String? = null,
        city: String? = null,
        housenumber: String? = null,
        osmKey: String? = null,
        osmValue: String? = null,
        lat: Double,
        lng: Double = 11.586,
    ) = PhotonFeature(
        geometry = PhotonGeometry(listOf(lng, lat)),
        properties = PhotonProperties(
            name = name,
            housenumber = housenumber,
            street = street,
            city = city,
            osmKey = osmKey,
            osmValue = osmValue,
        ),
    )

    // The reported case: a coarse fix lands on the road, but the office is a few metres further.
    private val street = feature(name = "Street", osmKey = "highway", lat = 48.15009) // ~10 m
    private val building = feature(name = "Building", osmKey = "building", lat = 48.15018) // ~20 m
    private val office = feature(name = "Office", osmKey = "office", lat = 48.15027) // ~30 m

    @Test
    fun aNearbyVenueOutranksANearerStreetAndANamedBuilding() {
        // Street is nearest (10 m) and the building (20 m) is nearer than the office (30 m), but only
        // the office is a venue → it wins. Excluding `building` is what stops "Building".
        assertEquals("Office", PhotonGeocoder.bestLabel(listOf(street, building, office), qLat, qLng))
    }

    @Test
    fun rankedLabelsPutVenuesFirstThenByDistanceDeduped() {
        // The picker's list: the venue (Office) leads even though it's the farthest of the three;
        // the rest follow by distance. A duplicate label collapses.
        val ranked = PhotonGeocoder.rankedLabels(listOf(street, building, office, office), qLat, qLng)
        assertEquals(listOf("Office", "Street", "Building"), ranked)
    }

    @Test
    fun withNoVenueTheNearestFeatureWins() {
        // Street (10 m) and building (20 m), neither a venue → the old nearest-feature behaviour.
        assertEquals("Street", PhotonGeocoder.bestLabel(listOf(street, building), qLat, qLng))
    }

    @Test
    fun aVenueBeyondThePreferRadiusDoesNotWin() {
        // The office is real but ~100 m off (a bad fix) — do not confidently name a place that far.
        val officeFar = feature(name = "Office", osmKey = "office", lat = 48.1509) // ~100 m > 70 m
        assertEquals("Street", PhotonGeocoder.bestLabel(listOf(street, officeFar), qLat, qLng))
    }

    @Test
    fun featuresWithNoUsableLabelAreIgnored() {
        val bare = feature(housenumber = "12", lat = 48.15009) // no name/street/city → not labelled
        assertNull(PhotonGeocoder.bestLabel(listOf(bare), qLat, qLng))
        assertNull(PhotonGeocoder.bestLabel(emptyList(), qLat, qLng))
    }

    @Test
    fun aFeatureWhoseNameIsJustTheHouseNumberFallsThroughToStreet() {
        val addr = feature(name = "12", housenumber = "12", street = "Baker Street", osmKey = "place", lat = 48.15009)
        assertEquals("Baker Street", PhotonGeocoder.bestLabel(listOf(addr), qLat, qLng))
    }

    // ── The Ostbahnhof case ──────────────────────────────────────────────────────
    //
    // Distances below are the ones komoot's instance actually returned for a real dwell at München
    // Ostbahnhof, so these fixtures reproduce the reported "Information" label rather than a
    // hypothetical arrangement of it.

    private val noticeBoard =
        feature(name = "Information", osmKey = "information", osmValue = "board", lat = 48.15045) // ~50 m
    private val stationName =
        feature(name = "Ostbahnhof", osmKey = "railway", osmValue = "station", lat = 48.15055) // ~61 m
    private val stationStop =
        feature(name = "München Ost", osmKey = "railway", osmValue = "stop", lat = 48.15063) // ~70 m

    @Test
    fun aStationOutranksANearerNoticeBoard() {
        // The reported failure, exactly: the board is 11 m nearer and won on distance alone.
        assertEquals(
            "Ostbahnhof",
            PhotonGeocoder.bestLabel(listOf(noticeBoard, stationName, stationStop), qLat, qLng),
        )
    }

    @Test
    fun aStationOutranksAVenueEvenWhenTheVenueIsNearer() {
        // Inside a station, a shop 30 m closer is not where the traveller was.
        assertEquals("Ostbahnhof", PhotonGeocoder.bestLabel(listOf(office, stationName), qLat, qLng))
    }

    @Test
    fun trackIsNotAStation() {
        // ⚠ The value check earning its keep: `railway` also tags the rails, and a line's name is not
        // a place. Falls through to the ordinary nearest-feature rule.
        val line = feature(name = "München–Rosenheim", osmKey = "railway", osmValue = "rail", lat = 48.15009)
        assertEquals("Office", PhotonGeocoder.bestLabel(listOf(line, office), qLat, qLng))
    }

    @Test
    fun streetFurnitureNeverWinsWhileARealPlaceIsOnTheList() {
        // A vending machine is `amenity`, so before this it competed in — and won — the venue tier.
        val vendingMachine =
            feature(name = "Münchner Merkur", osmKey = "amenity", osmValue = "vending_machine", lat = 48.15005)
        assertEquals("Office", PhotonGeocoder.bestLabel(listOf(vendingMachine, office), qLat, qLng))
        assertEquals("Street", PhotonGeocoder.bestLabel(listOf(vendingMachine, street), qLat, qLng))
    }

    @Test
    fun streetFurnitureIsDemotedNotDropped() {
        // ⚠ Deliberate: if the board is genuinely all Photon offers, its name still beats no label.
        assertEquals("Information", PhotonGeocoder.bestLabel(listOf(noticeBoard), qLat, qLng))
        assertEquals(
            listOf("Office", "Information"),
            PhotonGeocoder.rankedLabels(listOf(noticeBoard, office), qLat, qLng),
        )
    }

    @Test
    fun aStationBeyondTheTransitRadiusDoesNotWin() {
        val farStation =
            feature(name = "Ostbahnhof", osmKey = "railway", osmValue = "station", lat = 48.1520) // ~220 m
        assertEquals("Office", PhotonGeocoder.bestLabel(listOf(farStation, office), qLat, qLng))
    }
}
