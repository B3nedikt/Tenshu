package org.tenshu.timeline.common

import org.tenshu.timeline.common.PlaceRoleDetector.VisitDwell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceRoleDetectorTest {

    // Epoch day 0 (1970-01-01) was a Thursday, so day index 4 is the first Monday. Every fixture
    // below counts days from that Monday, which keeps the weekday/weekend cases readable.
    private val mondayMs = 4L * 86_400_000L
    private val hour = 3_600_000L

    private fun at(dayOffset: Long, hourOfDay: Long): Long =
        mondayMs + dayOffset * 86_400_000L + hourOfDay * hour

    private fun visit(placeId: String, dayOffset: Long, hourOfDay: Long, hours: Long) =
        VisitDwell(placeId, at(dayOffset, hourOfDay), hours * 3_600)

    private fun homeOf(result: List<PlaceRoleDetector.RoleCandidate>) =
        result.firstOrNull { it.role == PlaceRoleNames.HOME }?.placeId

    private fun workOf(result: List<PlaceRoleDetector.RoleCandidate>) =
        result.firstOrNull { it.role == PlaceRoleNames.WORK }?.placeId

    @Test
    fun nightsAtOnePlaceMakeItHome() {
        // 23:00 → 05:00 for six nights.
        val visits = (0L..5L).map { visit("flat", it, 23, 6) }

        assertEquals("flat", homeOf(PlaceRoleDetector.detect(visits)))
    }

    @Test
    fun weekdayDaytimeAtAnotherPlaceMakesItWork() {
        val nights = (0L..5L).map { visit("flat", it, 23, 6) }
        // Mon–Fri, 09:00 → 17:00.
        val days = (0L..4L).map { visit("office", it, 9, 8) }

        val result = PlaceRoleDetector.detect(nights + days)

        assertEquals("flat", homeOf(result))
        assertEquals("office", workOf(result))
    }

    @Test
    fun workIsNeverTheSamePlaceAsHome() {
        // Working from home: one place holds both the nights and the weekday daytime. Reporting it
        // as Work too would render every commute as "Home → Home".
        val nights = (0L..5L).map { visit("flat", it, 23, 6) }
        val days = (0L..4L).map { visit("flat", it, 9, 8) }

        val result = PlaceRoleDetector.detect(nights + days)

        assertEquals("flat", homeOf(result))
        assertNull(workOf(result))
    }

    @Test
    fun aHotelWeekIsDenseButTooNarrowToBecomeHome() {
        // Four consecutive nights — plenty of hours, but not enough distinct days. This is the gate
        // that keeps a trip from unseating a real home, and it is the one doing the most work given
        // that visits abroad bucket under the device's own timezone (proposal D6).
        val visits = (0L..2L).map { visit("hotel", it, 23, 6) }

        assertNull(homeOf(PlaceRoleDetector.detect(visits)))
    }

    @Test
    fun evidenceSplitFourWaysNamesNoHome() {
        // Four places at a quarter of the night dwell each: none clears the 30% share, so the honest
        // answer is silence rather than the arbitrary winner of a near-tie.
        val visits = (0L..5L).flatMap { day ->
            listOf(
                visit("a", day, 22, 1),
                visit("b", day, 23, 1),
                visit("c", day, 0, 1),
                visit("d", day, 1, 1),
            )
        }

        assertNull(homeOf(PlaceRoleDetector.detect(visits)))
    }

    @Test
    fun aVisitSpanningMidnightCountsWholeAgainstTheNight() {
        // 22:00 → 02:00 is four hours entirely inside the night window, split across two calendar
        // days. A naive "which bucket does the START fall in" would still get this right; what it
        // would get wrong is the 05:00 → 09:00 case below.
        val spanning = (0L..5L).map { visit("flat", it, 22, 4) }

        assertEquals("flat", homeOf(PlaceRoleDetector.detect(spanning)))
    }

    @Test
    fun onlyTheInWindowPartOfAnEdgeVisitCounts() {
        // "early" sits 05:00 → 09:00: one hour of night, three outside it.
        // "flat" sits 23:00 → 05:00: six hours, all night.
        // If edge visits were counted whole, `early` would hold 4h vs 6h and stay a serious
        // contender; correctly clipped it holds 1h, so `flat` wins on both dwell and share.
        val flat = (0L..5L).map { visit("flat", it, 23, 6) }
        val early = (0L..5L).map { visit("early", it, 5, 4) }

        val result = PlaceRoleDetector.detect(flat + early)

        assertEquals("flat", homeOf(result))
        val share = result.first { it.role == PlaceRoleNames.HOME }.share
        assertTrue(share > 0.8, "expected the clipped edge visit to barely dilute the winner, got $share")
    }

    @Test
    fun weekendDaytimeDoesNotCountTowardsWork() {
        // Days 5 and 6 are Saturday and Sunday. A place used only at weekends is not Work, however
        // many hours it holds.
        val nights = (0L..5L).map { visit("flat", it, 23, 6) }
        val weekends = listOf(5L, 6L, 12L, 13L, 19L, 20L).map { visit("allotment", it, 9, 8) }

        val result = PlaceRoleDetector.detect(nights + weekends)

        assertEquals("flat", homeOf(result))
        assertNull(workOf(result))
    }

    @Test
    fun noVisitsYieldNoCandidates() {
        assertEquals(emptyList(), PlaceRoleDetector.detect(emptyList()))
    }

    @Test
    fun aZeroLengthVisitContributesNothing() {
        val visits = (0L..5L).map { VisitDwell("flat", at(it, 23), durationSec = 0) }

        assertEquals(emptyList(), PlaceRoleDetector.detect(visits))
    }

    @Test
    fun theVerdictIsIndependentOfInputOrder() {
        // Two devices holding the same window must reach the same verdict, or each overwrites the
        // other's row forever. Ordering is the most likely way that could silently differ.
        val visits = (0L..5L).flatMap { listOf(visit("flat", it, 23, 6), visit("office", it, 9, 8)) }

        val forwards = PlaceRoleDetector.detect(visits)
        val backwards = PlaceRoleDetector.detect(visits.reversed())

        assertEquals(forwards, backwards)
    }

    @Test
    fun anExactTieResolvesDeterministically() {
        // Same dwell, same days, different ids — resolved by the smaller id. Arbitrary, but
        // identically arbitrary on every device, which is the property that matters.
        val visits = (0L..5L).flatMap { listOf(visit("b-place", it, 22, 2), visit("a-place", it, 0, 2)) }

        val first = PlaceRoleDetector.detect(visits)
        val second = PlaceRoleDetector.detect(visits.reversed())

        assertEquals(homeOf(first), homeOf(second))
    }
}
