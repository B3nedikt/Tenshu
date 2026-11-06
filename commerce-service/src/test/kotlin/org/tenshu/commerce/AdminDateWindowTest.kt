package org.tenshu.commerce

import org.tenshu.commerce.routing.formatMinor
import org.tenshu.commerce.routing.parseAdminDateWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The admin reporting window (WC-3) — where the operator's **inclusive dates** meet the store's
 * **half-open instants**. Getting this edge wrong drops or duplicates a whole day of revenue in a
 * filing that still looks complete, which is why it is pinned separately from the routes.
 */
class AdminDateWindowTest {

    private fun instantOf(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val someDayInAugust = Instant.parse("2026-08-09T12:34:56Z").toEpochMilli()

    /**
     * ⚠⚠ The whole point. "1–31 August" must include the 31st, so the exclusive instant is midnight
     * at the start of 1 September. Passing the typed date straight through — the obvious reading —
     * would silently drop the last day of every period.
     */
    @Test
    fun theTypedToDateIsInclusive_soTheExclusiveInstantIsTheFollowingMidnight() {
        val window = assertNotNull(parseAdminDateWindow("2026-08-01", "2026-08-31", someDayInAugust))

        assertEquals(instantOf("2026-08-01"), window.fromInclusive)
        assertEquals(instantOf("2026-09-01"), window.toExclusive, "the 31st must be fully included")
    }

    @Test
    fun consecutiveWindowsTileWithoutOverlap() {
        val july = assertNotNull(parseAdminDateWindow("2026-07-01", "2026-07-31", someDayInAugust))
        val august = assertNotNull(parseAdminDateWindow("2026-08-01", "2026-08-31", someDayInAugust))

        // July's exclusive end is exactly August's inclusive start: no gap, no shared instant.
        assertEquals(july.toExclusive, august.fromInclusive)
    }

    @Test
    fun aSingleDayWindowCoversThatWholeDay() {
        val window = assertNotNull(parseAdminDateWindow("2026-08-09", "2026-08-09", someDayInAugust))

        assertEquals(instantOf("2026-08-09"), window.fromInclusive)
        assertEquals(instantOf("2026-08-10"), window.toExclusive)
    }

    @Test
    fun defaultsToTheCurrentMonthToDate() {
        val window = assertNotNull(parseAdminDateWindow(null, null, someDayInAugust))

        assertEquals(LocalDate.parse("2026-08-01"), window.fromDate)
        assertEquals(LocalDate.parse("2026-08-09"), window.toDate, "…to today, inclusive")
        assertEquals(instantOf("2026-08-10"), window.toExclusive)
    }

    @Test
    fun theWindowIsResolvedInUtc_notTheHostZone() {
        // 00:30 UTC on the 9th. A host east of UTC would call this the 9th too; one west of it
        // would still be on the 8th — a filing period must not depend on which it is.
        val justAfterMidnightUtc = Instant.parse("2026-08-09T00:30:00Z").toEpochMilli()
        val window = assertNotNull(parseAdminDateWindow(null, null, justAfterMidnightUtc))
        assertEquals(LocalDate.parse("2026-08-09"), window.toDate)
    }

    @Test
    fun unparseableOrBackwardsRangesAreRefused() {
        assertNull(parseAdminDateWindow("not-a-date", null, someDayInAugust))
        assertNull(parseAdminDateWindow(null, "2026-13-01", someDayInAugust))
        assertNull(parseAdminDateWindow("2026-08-31", "2026-08-01", someDayInAugust), "backwards")
    }

    @Test
    fun blankParametersFallBackToTheDefaults_ratherThanFailing() {
        val window = assertNotNull(parseAdminDateWindow("", "  ", someDayInAugust))
        assertEquals(LocalDate.parse("2026-08-01"), window.fromDate)
    }

    @Test
    fun describeShowsTheInclusiveDatesBack() {
        val window = assertNotNull(parseAdminDateWindow("2026-08-01", "2026-08-31", someDayInAugust))
        assertEquals("2026-08-01 to 2026-08-31 (inclusive, UTC)", window.describe())
    }

    // --- money rendering ---------------------------------------------------------------------

    @Test
    fun minorUnitsRenderWithTwoDecimalsAndTheCurrency() {
        assertEquals("11.90 EUR", formatMinor(1190, "eur"))
        assertEquals("0.05 EUR", formatMinor(5, "eur"), "the padding matters below ten cents")
        assertEquals("0.00 EUR", formatMinor(0, "eur"))
        assertEquals("-11.90 EUR", formatMinor(-1190, "eur"), "a signed total stays readable")
    }
}
