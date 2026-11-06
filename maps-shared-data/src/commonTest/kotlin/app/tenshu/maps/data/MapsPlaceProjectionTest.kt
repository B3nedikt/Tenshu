package app.tenshu.maps.data

import app.tenshu.maps.model.PlaceSection
import app.tenshu.maps.model.PlaceCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure Maps place projection: E7 → real [location], and the documented honest empties for every
 * field `:place-service` can't store (the thin-screens tradeoff).
 */
class MapsPlaceProjectionTest {

    @Test
    fun projectsE7ToRealLocation() {
        val p = MapsPlaceProjection.place(
            PlaceInput(id = "home", label = "Home", latE7 = 437696000, lngE7 = 112558000),
        )
        assertEquals("home", p.id)
        assertEquals("Home", p.name)
        assertNotNull(p.location)
        assertEquals(43.7696, p.location!!.latitude, 1e-6)
        assertEquals(11.2558, p.location!!.longitude, 1e-6)
    }

    @Test
    fun unstoredFieldsProjectToHonestDefaults() {
        val p = MapsPlaceProjection.place(PlaceInput("x", "X", 437700000, 112560000))
        assertEquals(PlaceSection.Yours, p.section) // Saved / Recommended have no store
        assertFalse(p.saved)
        assertNull(p.savedList)
        assertEquals(0, p.visitCount)
        assertTrue(p.photos.isEmpty())
        assertNull(p.reason)
        assertNull(p.note)
    }

    @Test
    fun seedIsStableAcrossCalls() {
        val a = MapsPlaceProjection.place(PlaceInput("stable", "S", 0, 0)).seed
        val b = MapsPlaceProjection.place(PlaceInput("stable", "S", 0, 0)).seed
        assertEquals(a, b)
    }

    @Test
    fun aConfirmedRoleGivesThePlaceItsRealCategory() {
        // PR-4: the first category the store can actually answer for. Before this it was always
        // `Store` — a hardcoded stand-in that made Home and Work indistinguishable on the map.
        assertEquals(
            PlaceCategory.Home,
            MapsPlaceProjection.place(PlaceInput("h", "Flat", 0, 0, role = "home")).category,
        )
        assertEquals(
            PlaceCategory.Work,
            MapsPlaceProjection.place(PlaceInput("w", "Office", 0, 0, role = "work")).category,
        )
    }

    @Test
    fun noRoleKeepsTheHonestDefault() {
        // ⚠ A place with no role is not "a shop" — it is "we do not know", and `Store` stays the
        // stand-in for that rather than the projection inventing a category.
        assertEquals(
            PlaceCategory.Store,
            MapsPlaceProjection.place(PlaceInput("x", "X", 0, 0)).category,
        )
        assertEquals(
            PlaceCategory.Store,
            MapsPlaceProjection.place(PlaceInput("y", "Y", 0, 0, role = "gym")).category,
            "an unrecognised role degrades to the default rather than failing",
        )
    }
}
