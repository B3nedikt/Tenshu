package app.tenshu.maps.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ph1 (docs/proposals/maps-app.md §6) — the locale-independent "last visit" former. Only
 * the bucket + magnitude are decided here; the words are the UI's job (ADR 0016), so these
 * assert forms and values, never strings.
 */
class MapsFormatTest {

    private val today = LocalDate(2026, 7, 6)

    private fun visit(y: Int, m: Int, d: Int) =
        MapsFormat.relativeVisit(today, LocalDate(y, m, d))

    @Test
    fun sameDay_isToday() {
        assertEquals(RelativeVisit(RelativeForm.Today, 0), visit(2026, 7, 6))
    }

    @Test
    fun futureDate_clampsToToday() {
        assertEquals(RelativeVisit(RelativeForm.Today, 0), visit(2026, 7, 20))
    }

    @Test
    fun oneDayAgo_isYesterday() {
        assertEquals(RelativeVisit(RelativeForm.Yesterday, 0), visit(2026, 7, 5))
    }

    @Test
    fun withinAWeek_isDaysAgo() {
        assertEquals(RelativeVisit(RelativeForm.DaysAgo, 3), visit(2026, 7, 3))
        assertEquals(RelativeVisit(RelativeForm.DaysAgo, 6), visit(2026, 6, 30))
    }

    @Test
    fun withinAMonth_isWeeksAgo_floored() {
        assertEquals(RelativeVisit(RelativeForm.WeeksAgo, 1), visit(2026, 6, 29)) // 7 days
        assertEquals(RelativeVisit(RelativeForm.WeeksAgo, 1), visit(2026, 6, 24)) // 12 days → 1 wk
        assertEquals(RelativeVisit(RelativeForm.WeeksAgo, 3), visit(2026, 6, 13)) // 23 days → 3 wk
    }

    @Test
    fun monthsAgo_bucketed() {
        assertEquals(RelativeVisit(RelativeForm.MonthsAgo, 1), visit(2026, 6, 1))
        assertEquals(RelativeVisit(RelativeForm.MonthsAgo, 3), visit(2026, 4, 2))
    }

    @Test
    fun yearsAgo_bucketed() {
        assertEquals(RelativeVisit(RelativeForm.YearsAgo, 1), visit(2025, 6, 1))
        assertEquals(RelativeVisit(RelativeForm.YearsAgo, 2), visit(2024, 2, 12))
    }
}
