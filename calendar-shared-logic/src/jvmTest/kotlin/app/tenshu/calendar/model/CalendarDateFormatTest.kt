package app.tenshu.calendar.model

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * The locale-data bridge (CA-3), on the JVM where a locale can be pinned.
 *
 * ⚠⚠ **The weekday index is the whole risk here.** `DateFormatSymbols` returns a
 * Sunday-first, 1-based array with an empty slot at index 0, while ISO numbers
 * Monday 1 … Sunday 7 — two independent off-by-ones that a smoke check ("it
 * returns a non-empty string") would sail straight past while showing Tuesday's
 * name over Monday's column.
 */
class CalendarDateFormatTest {

    private lateinit var original: Locale

    /** ⚠ Pinned, or the assertions would pass or fail with the build machine's locale. */
    @BeforeTest
    fun setUp() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun tearDown() = Locale.setDefault(original)

    @Test
    fun every_weekday_maps_to_its_own_name() {
        assertEquals("Mon", CalendarDateFormat.weekdayShort(DayOfWeek.MONDAY))
        assertEquals("Tue", CalendarDateFormat.weekdayShort(DayOfWeek.TUESDAY))
        assertEquals("Wed", CalendarDateFormat.weekdayShort(DayOfWeek.WEDNESDAY))
        assertEquals("Thu", CalendarDateFormat.weekdayShort(DayOfWeek.THURSDAY))
        assertEquals("Fri", CalendarDateFormat.weekdayShort(DayOfWeek.FRIDAY))
        assertEquals("Sat", CalendarDateFormat.weekdayShort(DayOfWeek.SATURDAY))
        assertEquals("Sun", CalendarDateFormat.weekdayShort(DayOfWeek.SUNDAY))
    }

    /**
     * ⚠ Locale-independent, so it still guards the index arithmetic in a build
     * that ever stops pinning English: seven distinct non-blank labels is
     * impossible if the mapping collides or reads the empty slot.
     */
    @Test
    fun the_seven_weekday_labels_are_distinct_and_non_blank_in_any_locale() {
        Locale.setDefault(Locale.GERMANY)
        val labels = DayOfWeek.entries.map(CalendarDateFormat::weekdayShort)

        assertEquals(7, labels.toSet().size, "got $labels")
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun months_are_named_one_based() {
        assertEquals("January", CalendarDateFormat.monthName(1))
        assertEquals("August", CalendarDateFormat.monthName(8))
        assertEquals("December", CalendarDateFormat.monthName(12))
    }

    @Test
    fun the_twelve_month_names_are_distinct_and_non_blank_in_any_locale() {
        Locale.setDefault(Locale.GERMANY)
        val names = (1..12).map(CalendarDateFormat::monthName)

        assertEquals(12, names.toSet().size, "got $names")
        assertTrue(names.none { it.isBlank() })
    }

    /**
     * ⚠⚠ A civil date must render as **that** date. Building the intermediate
     * `Date` at midnight in a zone whose offset shifts would render the day
     * before — which is why the bridge picks midday.
     */
    @Test
    fun a_date_renders_as_its_own_day_not_the_one_before() {
        val label = CalendarDateFormat.mediumDate(LocalDate(2026, 8, 10))

        assertTrue(label.contains("10"), "expected the 10th in $label")
        assertTrue(label.contains("2026"), "expected the year in $label")
    }

    /** ⭐ The locale's clock convention, not ours — English is 12-hour. */
    @Test
    fun a_time_follows_the_locales_clock_convention() {
        val us = run {
            Locale.setDefault(Locale.US)
            CalendarDateFormat.shortTime(LocalTime(14, 30))
        }
        val de = run {
            Locale.setDefault(Locale.GERMANY)
            CalendarDateFormat.shortTime(LocalTime(14, 30))
        }

        assertTrue(us.contains("2:30"), "expected a 12-hour clock in $us")
        assertTrue(de.contains("14:30"), "expected a 24-hour clock in $de")
    }

    /**
     * ⚠⚠ The picker's convention and the label's convention must come from the
     * same answer — a 12-hour dial that produces "14:00" labels is worse than
     * either convention on its own.
     */
    @Test
    fun the_clock_convention_matches_what_the_labels_render() {
        Locale.setDefault(Locale.US)
        assertFalse(CalendarDateFormat.uses24HourClock())
        assertTrue(CalendarDateFormat.shortTime(LocalTime(14, 0)).contains("2"))

        Locale.setDefault(Locale.GERMANY)
        assertTrue(CalendarDateFormat.uses24HourClock())
        assertTrue(CalendarDateFormat.shortTime(LocalTime(14, 0)).contains("14"))
    }

    @Test
    fun the_month_header_carries_the_month_and_the_year() {
        assertEquals("August 2026", CalendarDateFormat.monthAndYear(2026, 8))
    }
}
