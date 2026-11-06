package app.tenshu.photos

import app.tenshu.photos.time.PhotoDates
import app.tenshu.photos.ui.screens.feed.FeedDensity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * `PhotoDates` — the one copy of Photos' zone arithmetic every shell draws with (PIO-0).
 *
 * ⚠⚠ The New Year cases are the reason the functions moved: the view models' UTC-ish defaults put a
 * photograph taken just after midnight on 1 January east of Greenwich in the year that had ended,
 * and the SwiftUI shell had no way to reach anything better.
 */
@OptIn(ExperimentalTime::class)
class PhotoDatesTest {

    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val newYork = TimeZone.of("America/New_York")

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

    @Test
    fun a_photograph_just_after_new_year_in_tokyo_is_in_the_new_year() {
        // 00:30 on 1 Jan 2025 in Tokyo is still 2024 in UTC — the default's mistake.
        val ms = at(tokyo, 2025, 1, 1, 0, 30)
        assertEquals(2025, PhotoDates.yearOf(ms, tokyo))
        assertEquals(
            LocalDate(2025, 1, 1).atStartOfDayIn(tokyo).toEpochMilliseconds(),
            PhotoDates.sectionStart(FeedDensity.Year, ms, tokyo),
        )
    }

    @Test
    fun a_photograph_late_on_new_years_eve_in_new_york_stays_in_the_old_year() {
        // 23:30 on 31 Dec 2024 in New York is already 2025 in UTC.
        val ms = at(newYork, 2024, 12, 31, 23, 30)
        assertEquals(2024, PhotoDates.yearOf(ms, newYork))
        assertEquals("31 December 2024", PhotoDates.dayLabel(PhotoDates.dayStart(ms, newYork), newYork))
    }

    @Test
    fun each_density_starts_its_section_at_the_right_local_midnight() {
        val ms = at(tokyo, 2024, 7, 19, 15, 5)
        fun start(y: Int, m: Int, d: Int) = LocalDate(y, m, d).atStartOfDayIn(tokyo).toEpochMilliseconds()
        assertEquals(start(2024, 7, 19), PhotoDates.sectionStart(FeedDensity.Mosaic, ms, tokyo))
        assertEquals(start(2024, 7, 19), PhotoDates.sectionStart(FeedDensity.Day, ms, tokyo))
        assertEquals(start(2024, 7, 1), PhotoDates.sectionStart(FeedDensity.Month, ms, tokyo))
        assertEquals(start(2024, 1, 1), PhotoDates.sectionStart(FeedDensity.Year, ms, tokyo))
    }

    @Test
    fun section_labels_match_what_the_android_shell_has_always_drawn() {
        val ms = at(tokyo, 2024, 12, 17, 9, 0)
        assertEquals("17 December 2024", PhotoDates.sectionLabel(FeedDensity.Mosaic, ms, tokyo))
        assertEquals("17 December 2024", PhotoDates.sectionLabel(FeedDensity.Day, ms, tokyo))
        assertEquals("December 2024", PhotoDates.sectionLabel(FeedDensity.Month, ms, tokyo))
        assertEquals("2024", PhotoDates.sectionLabel(FeedDensity.Year, ms, tokyo))
    }

    @Test
    fun viewer_labels() {
        val ms = at(tokyo, 2026, 4, 9, 21, 26)
        assertEquals("9 Apr", PhotoDates.shortDate(ms, tokyo))
        assertEquals("21:26", PhotoDates.timeOfDay(ms, tokyo))
        assertEquals("Thu, 9 Apr 2026  ·  21:26", PhotoDates.capturedAt(ms, tokyo))
        assertEquals("07:05", PhotoDates.timeOfDay(at(tokyo, 2026, 4, 9, 7, 5), tokyo))
    }
}
