package org.tenshu.core.place

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PlaceGrid] — the adjacency test that undoes id minting
 * (`timeline-recognition-and-place-naming.md` §10.10). Coordinates are the reporting device's
 * home cells, moved by whole grid cells (`public-export.md` §6 Q6): 49.0150/20.9310, 49.0150/20.9320 (73 m east) and 49.0140/20.9310
 * (111 m south) — the third is the one a metre radius could not reach.
 */
class PlaceGridTest {

    private val home = "cap:490150000,209310000"
    private val homeEast = "cap:490150000,209320000"
    private val homeSouth = "cap:490140000,209310000"

    @Test
    fun neighbouringCellsAreAdjacentInBothAxes() {
        assertTrue(PlaceGrid.areAdjacent(home, homeEast), "73 m east — one longitude step")
        assertTrue(PlaceGrid.areAdjacent(home, homeSouth), "111 m south — one LATITUDE step")
        assertTrue(PlaceGrid.areAdjacent(homeEast, homeSouth), "diagonal neighbours also touch")
    }

    @Test
    fun aCellIsAdjacentToItself() {
        assertTrue(PlaceGrid.areAdjacent(home, home))
    }

    @Test
    fun twoCellsApartIsNotAdjacent() {
        // ⚠ Self-limiting by construction: the rule can only ever reach across ONE boundary.
        assertFalse(PlaceGrid.areAdjacent(home, "cap:490150000,209330000"))
        assertFalse(PlaceGrid.areAdjacent(home, "cap:490130000,209310000"))
    }

    @Test
    fun aFarAwayPlaceIsNeverAdjacent() {
        assertFalse(PlaceGrid.areAdjacent(home, "cap:491450000,218500000"))
    }

    @Test
    fun nonCaptureIdsHaveNoCellAndNeverClaimAdjacency() {
        // An imported id was never grid-minted, so the grid says nothing about it.
        assertEquals(null, PlaceGrid.cellOf("goog:ChIJcafe"))
        assertFalse(PlaceGrid.areAdjacent(home, "goog:ChIJcafe"))
        assertFalse(PlaceGrid.areAdjacent("goog:a", "goog:b"))
        assertEquals(null, PlaceGrid.cellOf("cap:not,numbers"))
        assertEquals(null, PlaceGrid.cellOf("cap:490150000"))
    }

    @Test
    fun southernAndWesternCoordinatesFloorRatherThanTruncate() {
        // ⚠ Truncation toward zero would make the two cells straddling the equator/meridian look
        // like one, and their neighbours look two apart.
        assertEquals(-1L to -1L, PlaceGrid.cellOf("cap:-5000,-5000"))
        assertEquals(0L to 0L, PlaceGrid.cellOf("cap:5000,5000"))
        assertTrue(PlaceGrid.areAdjacent("cap:-5000,-5000", "cap:5000,5000"))
        assertFalse(PlaceGrid.areAdjacent("cap:-25000,0", "cap:5000,0"))
    }
}
