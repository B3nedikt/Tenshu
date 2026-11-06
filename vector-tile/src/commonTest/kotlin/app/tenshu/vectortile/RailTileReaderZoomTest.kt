package app.tenshu.vectortile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PmtilesTileWalk.chooseZoom] — the adaptive-zoom guard that keeps a long journey from being clipped
 * to a corner of its bounding box at z14 (which collapses rail coverage and falsely demotes a real
 * train, `timeline-recognition-and-place-naming.md` §3). A small area keeps the full-detail zoom.
 */
class RailTileReaderZoomTest {

    @Test
    fun keepsThePreferredZoomForASmallArea() {
        // ~2 km box — a handful of z14 tiles, well within budget.
        val small = GeoBounds(minLon = 11.55, minLat = 48.13, maxLon = 11.58, maxLat = 48.15)
        assertEquals(14, PmtilesTileWalk.chooseZoom(small, preferred = 14, floorZoom = 12, maxTiles = 64))
    }

    @Test
    fun dropsBelowZ14ForALongCorridorButNotBelowTheFloor() {
        // The reported case: a ~71 km × 20 km Munich–Mühldorf corridor is ~440 tiles at z14.
        val corridor = GeoBounds(minLon = 11.57, minLat = 48.13, maxLon = 12.53, maxLat = 48.31)
        val z = PmtilesTileWalk.chooseZoom(corridor, preferred = 14, floorZoom = 12, maxTiles = 64)
        assertTrue(z < 14, "a long corridor must drop below z14 to be surveyed whole, was $z")
        assertTrue(z >= 12, "must not drop below the floor, was $z")
    }

    @Test
    fun neverExceedsThePreferredZoom() {
        val small = GeoBounds(minLon = 11.55, minLat = 48.13, maxLon = 11.56, maxLat = 48.14)
        assertEquals(12, PmtilesTileWalk.chooseZoom(small, preferred = 12, floorZoom = 12, maxTiles = 64))
    }
}
