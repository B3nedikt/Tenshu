package app.tenshu.maps.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ph1 (docs/proposals/maps-app.md §6) — sanity over the domain model shape ported from
 * `maps-agent.md` §5. Guards the load-bearing invariants: the enum sets the UI switches on,
 * and the never-visited / recommended-provenance shapes.
 */
class MapsModelTest {

    @Test
    fun categorySet_matchesHandoff() {
        assertEquals(
            setOf("Home", "Work", "Cafe", "Restaurant", "Park", "Store", "Culture", "Transit", "Viewpoint"),
            PlaceCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun sectionSet_matchesHandoff() {
        assertEquals(setOf("Yours", "Saved", "Recommended"), PlaceSection.entries.map { it.name }.toSet())
    }

    @Test
    fun neverVisitedPlace_hasNoDates() {
        val rio = Place(
            id = "rio", name = "Tasca do Rio", category = PlaceCategory.Restaurant,
            section = PlaceSection.Saved, savedList = "To try", address = "Cais do Sodré",
            coord = Coord(0.49, 0.70), seed = 15, saved = true, visitCount = 0,
            note = "Saved from a friend — not visited yet.",
        )
        assertEquals(0, rio.visitCount)
        assertNull(rio.lastVisit)
        assertTrue(rio.recentVisits.isEmpty())
    }

    @Test
    fun recommendedPlace_carriesProvenance() {
        val padaria = Place(
            id = "padaria", name = "Padaria Central", category = PlaceCategory.Cafe,
            section = PlaceSection.Recommended, address = "Rua da Prata 88",
            coord = Coord(0.54, 0.46), seed = 6, saved = false, visitCount = 14,
            lastVisit = LocalDate(2026, 7, 1),
            reason = Reason(ReasonKind.Timeline, "You pass here most mornings"),
        )
        assertEquals(ReasonKind.Timeline, padaria.reason?.kind)
        assertEquals("You pass here most mornings", padaria.reason?.text)
    }
}
