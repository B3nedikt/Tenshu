package app.tenshu.maps.data

import app.tenshu.maps.model.PlaceSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ph2 (docs/proposals/maps-app.md §6) — the mock seam over `mockup/maps-data.js`. Asserts
 * the seed shape (counts + section split), id lookup, and that `setSaved` re-emits through
 * the held state so the tabs and detail observe the change.
 */
class MockPlaceRepositoryTest {

    @Test
    fun seed_countsAndSections() = runTest {
        val repo = MockPlaceRepository()
        val places = repo.places().first()
        assertEquals(12, places.size)
        assertEquals(3, places.count { it.section == PlaceSection.Yours })
        assertEquals(5, places.count { it.section == PlaceSection.Saved })
        assertEquals(4, places.count { it.section == PlaceSection.Recommended })
        assertEquals(3, repo.savedLists().first().size)
    }

    @Test
    fun place_lookupById() = runTest {
        val repo = MockPlaceRepository()
        assertEquals("Café Aurora", repo.place("aurora")?.name)
        assertNull(repo.place("does-not-exist"))
    }

    @Test
    fun recommendedPlace_carriesProvenance() = runTest {
        val repo = MockPlaceRepository()
        assertEquals("You pass here most mornings", repo.place("padaria")?.reason?.text)
    }

    @Test
    fun everyPlace_carriesProjectedLocationWithinFirenze() = runTest {
        val repo = MockPlaceRepository()
        val places = repo.places().first()
        assertTrue(places.all { it.location != null }, "Ph9 seam: every mock place has a location")
        places.forEach { p ->
            val g = p.location!!
            assertTrue(g.latitude in MapsMockData.FIRENZE_MIN_LAT..MapsMockData.FIRENZE_MAX_LAT, "${p.id} lat")
            assertTrue(g.longitude in MapsMockData.FIRENZE_MIN_LNG..MapsMockData.FIRENZE_MAX_LNG, "${p.id} lng")
        }
    }

    @Test
    fun projectToGeo_mapsCornersOfTheNormalizedSpace() {
        // my = 0 → north edge (max lat); my = 1 → south edge (min lat); mx spans west→east.
        val topLeft = MapsMockData.projectToGeo(app.tenshu.maps.model.Coord(0.0, 0.0))
        assertEquals(MapsMockData.FIRENZE_MAX_LAT, topLeft.latitude, 1e-9)
        assertEquals(MapsMockData.FIRENZE_MIN_LNG, topLeft.longitude, 1e-9)
        val bottomRight = MapsMockData.projectToGeo(app.tenshu.maps.model.Coord(1.0, 1.0))
        assertEquals(MapsMockData.FIRENZE_MIN_LAT, bottomRight.latitude, 1e-9)
        assertEquals(MapsMockData.FIRENZE_MAX_LNG, bottomRight.longitude, 1e-9)
    }

    @Test
    fun setSaved_reEmitsThroughState() = runTest {
        val repo = MockPlaceRepository()
        assertFalse(repo.place("padaria")!!.saved)

        repo.setSaved("padaria", true)
        assertTrue(repo.place("padaria")!!.saved)
        assertTrue(repo.places().first().first { it.id == "padaria" }.saved)

        repo.setSaved("aurora", false)
        assertFalse(repo.place("aurora")!!.saved)
    }
}
