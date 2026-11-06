package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **§28.2 — the offline stop reader's selection rules, at the values the production tiles actually
 * carry.**
 *
 * Every fixture below is taken from a survey of the deployed `de-bayern` archive around Odeonsplatz
 * (3×3 tiles at z15): a `station` named *Odeonsplatz* at 93 m, three `bus_stop`s with the same name
 * from 52 m, eleven **unnamed** `subway_entrance`s from 21 m, and — the trap — a `stationery` shop.
 */
class StopTileReaderTest {

    private fun hit(kind: String, d: Double, name: String = "Odeonsplatz") =
        StopTileReader.StopHit(name = name, kind = kind, distanceM = d)

    @Test
    fun aStationBeatsANearerStopPosition() {
        // ⚠⚠ The ranking rule that matters. Pure nearest-first would answer from the platform.
        val best = StopTileReader.best(listOf(hit("platform", 113.0), hit("station", 93.0)))
        assertEquals("station", best?.kind)
    }

    @Test
    fun aStationBeatsAMuchNearerPlatform() {
        // The same rule with the distances inverted far enough that only tiering can decide it: a
        // concourse dwell must not be named after whichever platform is geometrically closest.
        val best = StopTileReader.best(listOf(hit("platform", 5.0), hit("station", 200.0)))
        assertEquals("station", best?.kind)
    }

    @Test
    fun withinATierTheNearestWins() {
        val best = StopTileReader.best(
            listOf(hit("station", 102.0, "Far"), hit("station", 93.0, "Near"), hit("halt", 150.0, "Halt")),
        )
        assertEquals("Near", best?.name)
    }

    @Test
    fun nothingInRangeIsNull() {
        assertNull(StopTileReader.best(emptyList()))
    }

    // ── the kind filter ────────────────────────────────────────────────────────

    @Test
    fun aStationeryShopIsNotAStation() {
        // ⚠⚠ The reason [StopTileReader.STOP_KINDS] is matched by equality: a regex survey of one
        // plaza returned "Bethge", a stationery shop, because `station` is a substring of `stationery`.
        for (kind in listOf("stationery", "fire_station", "charging_station", "bicycle_repair_station")) {
            assertNull(StopTileReader.stopKindOf(poi(kind, name = "Bethge")), "$kind was accepted")
        }
    }

    @Test
    fun trackFurnitureIsNotAStop() {
        // `buffer_stop` contains `stop` and is a lump of metal at the end of a siding.
        for (kind in listOf("buffer_stop", "railway_crossing", "tram_crossing", "tram_level_crossing")) {
            assertNull(StopTileReader.stopKindOf(poi(kind, name = "whatever")), "$kind was accepted")
        }
    }

    @Test
    fun busIsDeliberatelyNotAccepted() {
        // ⚠ Not an oversight — the parity argument is in STOP_KINDS' KDoc. Pinned so that admitting bus
        // is a deliberate edit to a test, not a silent widening.
        assertNull(StopTileReader.stopKindOf(poi("bus_stop", name = "Odeonsplatz")))
        assertNull(StopTileReader.stopKindOf(poi("bus_station", name = "ZOB")))
    }

    @Test
    fun theRealStationKindsAreAccepted() {
        for (kind in listOf("station", "halt", "stop", "tram_stop", "platform")) {
            assertEquals(kind, StopTileReader.stopKindOf(poi(kind, name = "Odeonsplatz")), kind)
        }
    }

    @Test
    fun bothSchemaGenerationsAreRead() {
        // Production spells it `kind`; the older cut `pmap:kind`; OSM-tag-style cuts `railway`.
        assertEquals("station", StopTileReader.stopKindOf(poi("station", name = "X")))
        assertEquals("station", StopTileReader.stopKindOf(poi("station", name = "X", key = "pmap:kind")))
        assertEquals("station", StopTileReader.stopKindOf(poi("station", name = "X", key = "railway")))
    }

    // ── what stopsFrom keeps ───────────────────────────────────────────────────

    @Test
    fun anUnnamedStopIsSkipped() {
        // ⚠ All eleven `subway_entrance`s near Odeonsplatz are unnamed, and they are the *nearest*
        // things there — so a reader that returned a kind without a name would answer with nothing
        // useful while outranking the station.
        val layer = layerOf(poi("subway_entrance", name = null), poi("station", name = null))
        assertEquals(emptyList(), stops(layer))
    }

    @Test
    fun aBlankNameCountsAsUnnamed() {
        assertEquals(emptyList(), stops(layerOf(poi("station", name = "   "))))
    }

    @Test
    fun aNonPointStopIsSkipped() {
        // A `platform` is sometimes a way; its first vertex is an arbitrary corner.
        val way = MvtFeature(
            id = 1,
            type = MvtGeometryType.LINESTRING,
            tags = mapOf("kind" to MvtValue.Str("platform"), "name" to MvtValue.Str("Odeonsplatz")),
            geometry = listOf(listOf(MvtPoint(2048, 2048), MvtPoint(2100, 2100))),
        )
        assertEquals(emptyList(), stops(layerOf(way)))
    }

    @Test
    fun aStopBeyondTheRadiusIsSkipped() {
        // The tile centre is ~1.2 km from a corner at z15, so a 1 m radius must exclude it while a
        // generous one includes it — pinning that the distance is computed at all.
        val layer = layerOf(poi("station", name = "Odeonsplatz", x = 4095, y = 4095))
        assertEquals(emptyList(), stops(layer, radius = 1.0))
        assertTrue(stops(layer, radius = 5_000.0).isNotEmpty())
    }

    @Test
    fun theHitCarriesARealProjectedDistance() {
        // The query is the tile's own centre and the feature sits at the centre too, so the distance
        // must be ~0 — the check that catches a mirrored projection, which a synthetic
        // self-consistent comparison cannot.
        val hits = stops(layerOf(poi("station", name = "Odeonsplatz", x = 2048, y = 2048)))
        assertEquals(1, hits.size)
        assertTrue(hits.single().distanceM < 60.0, "distance ${hits.single().distanceM} m")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** z15 tile containing Odeonsplatz, and its centre as the query point. */
    private val z = 15
    private val tileX = 17435L
    private val tileY = 11332L

    private fun stops(layer: MvtLayer, radius: Double = 400.0): List<StopTileReader.StopHit> {
        val centre = layer.toLonLat(MvtPoint(2048, 2048), z, tileX, tileY)
        return StopTileReader.stopsFrom(layer, z, tileX, tileY, centre.lat, centre.lon, radius)
    }

    private fun layerOf(vararg features: MvtFeature) =
        MvtLayer(name = "pois", version = 2, extent = 4096, features = features.toList())

    private fun poi(
        kind: String,
        name: String?,
        key: String = "kind",
        x: Int = 2048,
        y: Int = 2048,
    ) = MvtFeature(
        id = 1,
        type = MvtGeometryType.POINT,
        tags = buildMap {
            put(key, MvtValue.Str(kind))
            name?.let { put("name", MvtValue.Str(it)) }
        },
        geometry = listOf(listOf(MvtPoint(x, y))),
    )
}
