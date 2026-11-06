package app.tenshu.timeline.data

import app.tenshu.timeline.model.MomentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockTimelineRepositoryTest {

    private fun repo() = MockTimelineRepository()

    @Test
    fun homeDays_seededNewestFirst() = runTest {
        val days = repo().homeDays().first()
        assertEquals(listOf("Today", "Yesterday", "Friday"), days.map { it.dayLabel })
        assertEquals(4, days.first().moments.size)
    }

    @Test
    fun boundTrips_and_suggestion_seeded() = runTest {
        val r = repo()
        assertEquals(listOf("Lisbon"), r.boundTrips().first().map { it.title })
        assertEquals("Hamburg", r.tripSuggestion().first()?.title)
    }

    @Test
    fun trip_lookup_findsNestedTrip() = runTest {
        val r = repo()
        assertEquals("Lisbon", r.trip("lisbon-2026-04")?.title)
        // Sintra is only reachable by recursing into Lisbon's contents.
        assertEquals("Sintra", r.trip("sintra-2026-04-14")?.title)
        assertNull(r.trip("does-not-exist"))
    }

    @Test
    fun moment_lookup_spansDays_and_nestedTrips() = runTest {
        val r = repo()
        // Home day moment.
        assertEquals("Lunch at Mogg", r.moment("lunch")?.title)
        // Moment inside a top-level trip day.
        assertEquals("Belém", r.moment("lis-belem")?.title)
        // Moment inside the nested Sintra trip.
        val pena = r.moment("sintra-pena")
        assertNotNull(pena)
        assertEquals(MomentKind.Visit, pena.kind)
        assertNull(r.moment("nope"))
    }

    @Test
    fun place_lookup_carriesHistory() = runTest {
        val mogg = repo().place("mogg")
        assertNotNull(mogg)
        assertEquals(8, mogg.visitsCount)
        assertEquals(6, mogg.history.size)  // 6 of 8 shown, per the mock
    }

    /**
     * The map heroes' two mock preconditions, pinned together because each is a *fallback gate*: a
     * movement whose trace resolves to a real path, and exactly one place carrying a coordinate.
     * Both are deliberately sited inside the bundled Firenze demo extract — a route or point outside
     * it frames onto empty tiles, which reads as a broken map rather than as missing data.
     */
    @Test
    fun movementMoment_resolvesARouteInsideTheDemoExtent() = runTest {
        val r = repo()
        val walk = r.moment("lis-riverwalk")
        assertNotNull(walk)
        assertEquals(MomentKind.Movement, walk.kind)
        val traceId = walk.traceId
        assertNotNull(traceId)

        val route = r.movementRoute(traceId)
        assertTrue(route.size >= 2, "a movement hero needs at least two points to draw")
        assertTrue(
            route.all { it.latitude in 43.7..43.85 && it.longitude in 11.2..11.35 },
            "route must sit inside the bundled Firenze extract, else it frames onto empty tiles",
        )
        // An unknown trace degrades to "stats only", never to a failure.
        assertEquals(emptyList(), r.movementRoute("no-such-trace"))
    }

    @Test
    fun exactlyOnePlaceCarriesACoordinate() = runTest {
        val places = repo().places().first()
        val located = places.filter { it.latE7 != 0L || it.lngE7 != 0L }
        assertEquals(listOf("ponte-vecchio"), located.map { it.id })
        val pv = located.single()
        assertTrue(pv.latE7 / 1e7 in 43.7..43.85 && pv.lngE7 / 1e7 in 11.2..11.35)
        // The rest keep no coordinate, which is what exercises the flat-hero fallback.
        assertTrue(places.size > 1)
    }

    @Test
    fun dismiss_clearsSuggestion() = runTest {
        val r = repo()
        r.dismissTripSuggestion()
        assertNull(r.tripSuggestion().first())
    }

    @Test
    fun activities_seeded() = runTest {
        val kinds = repo().activities().first().map { it.kind }
        assertEquals(listOf("run", "coffee", "dining", "event", "walk", "book"), kinds)
    }
}
