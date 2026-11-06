package org.tenshu.calendar.common.recurrence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * The time model (`calendar-app.md` §3 D3 / Q3) — the part most likely to be
 * got wrong, and the part whose bugs only surface in April.
 */
class EventTimeTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private val tokyo = TimeZone.of("Asia/Tokyo")

    private fun time(
        start: String,
        end: String,
        zone: String = "Europe/Berlin",
        allDay: Boolean = false,
    ) = EventTime.parse(start, end, zone, allDay)!!

    @Test
    fun parses_local_date_times_without_an_offset() {
        val t = time("2026-08-05T09:00", "2026-08-05T10:30")
        assertEquals(LocalDateTime(2026, 8, 5, 9, 0), t.startLocal)
        assertEquals(90L, t.durationMinutes)
    }

    @Test
    fun accepts_a_bare_date_and_seconds_precision() {
        assertEquals(LocalDateTime(2026, 8, 5, 0, 0), time("2026-08-05", "2026-08-06").startLocal)
        assertEquals(
            LocalDateTime(2026, 8, 5, 9, 0),
            time("2026-08-05T09:00:00", "2026-08-05T10:00:00").startLocal,
        )
    }

    /**
     * ⚠ An import frequently omits `DTEND`. Refusing to read the row would lose
     * the event outright, so a blank end is filled in — as a point in time for a
     * *timed* event.
     */
    @Test
    fun a_missing_end_on_a_timed_event_is_a_point_in_time() {
        val t = EventTime.parse("2026-08-05T09:00", "", "", false)!!
        assertEquals(t.startLocal, t.endLocal)
        assertEquals(0L, t.durationMinutes)
    }

    /**
     * ⚠⚠ ...but an **all-day** event with no `DTEND` is **one day**, not zero
     * (RFC 5545, `DTEND` exclusive). Public-holiday feeds routinely omit it, and
     * the zero-length reading would render every holiday as a blip.
     */
    @Test
    fun a_missing_end_on_an_all_day_event_is_one_day() {
        val t = EventTime.parse("2026-08-05", "", "", allDay = true)!!
        assertEquals(LocalDateTime(2026, 8, 6, 0, 0), t.endLocal)
        assertEquals(1440L, t.durationMinutes)
    }

    @Test
    fun an_unparseable_start_yields_null() {
        assertNull(EventTime.parse("not-a-date", "2026-08-05T10:00", "", false))
    }

    /** ⚠ An empty zone id is *floating*, which is a distinct third shape. */
    @Test
    fun an_empty_zone_is_floating() {
        assertTrue(EventTime.parse("2026-08-05T09:00", "2026-08-05T10:00", "", false)!!.isFloating)
        assertTrue(!time("2026-08-05T09:00", "2026-08-05T10:00").isFloating)
    }

    // --- the point of the whole design -------------------------------------

    /**
     * ⭐⭐ The reason instants are derived and never stored: a zoned event keeps
     * its civil time regardless of where it is read, so its *instant* is a
     * function of the event's own zone — not the reader's.
     */
    @Test
    fun a_zoned_event_resolves_to_the_same_instant_whoever_reads_it() {
        val occurrence = occurrenceOf(time("2026-08-05T09:00", "2026-08-05T10:00"))
        assertEquals(
            occurrence.startInstant(berlin),
            occurrence.startInstant(tokyo),
            "a zoned event's instant must not depend on the reader's zone",
        )
    }

    /**
     * ⭐ ...and the mirror image: a *floating* event deliberately does depend on
     * the reader, which is why `startInstant` takes the zone as a parameter
     * instead of reaching for a system default.
     */
    @Test
    fun a_floating_event_resolves_against_the_readers_zone() {
        val floating = EventTime.parse("2026-08-05T09:00", "2026-08-05T10:00", "", false)!!
        val occurrence = occurrenceOf(floating)
        assertNotEquals(
            occurrence.startInstant(berlin),
            occurrence.startInstant(tokyo),
            "a floating event is 09:00 wherever the reader is",
        )
    }

    /**
     * ⚠ An unknown zone yields null rather than a guess: silently falling back
     * to the reader's zone would place the event at the wrong moment and say
     * nothing about it.
     */
    @Test
    fun an_unknown_zone_id_yields_null_rather_than_a_guess() {
        val bogus = time("2026-08-05T09:00", "2026-08-05T10:00", zone = "Mars/Olympus_Mons")
        assertNull(occurrenceOf(bogus).startInstant(berlin))
    }

    /**
     * ⚠⚠ The DST case that motivates the whole civil-time model: 09:00 Berlin is
     * a *different* UTC instant in January and in July. A stored instant would
     * have had to pick one and be wrong the rest of the year.
     */
    @Test
    fun the_same_civil_time_is_a_different_instant_across_a_dst_boundary() {
        val winter = occurrenceOf(time("2026-01-15T09:00", "2026-01-15T10:00")).startInstant(berlin)!!
        val summer = occurrenceOf(time("2026-07-15T09:00", "2026-07-15T10:00")).startInstant(berlin)!!
        val winterMinuteOfDayUtc = (winter.epochSeconds / 60) % (24 * 60)
        val summerMinuteOfDayUtc = (summer.epochSeconds / 60) % (24 * 60)
        assertNotEquals(
            winterMinuteOfDayUtc,
            summerMinuteOfDayUtc,
            "09:00 Berlin is 08:00 UTC in winter and 07:00 UTC in summer",
        )
    }

    // --- occurrence plumbing ------------------------------------------------

    @Test
    fun expansion_carries_the_series_duration_onto_every_occurrence() {
        val occurrences = expandOccurrences(
            time = time("2026-08-03T09:00", "2026-08-03T09:45"),
            rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO"),
            windowStart = LocalDate(2026, 8, 1),
            windowEnd = LocalDate(2026, 8, 31),
        )
        assertEquals(5, occurrences.size)
        occurrences.forEach {
            assertEquals(9, it.startLocal.hour)
            assertEquals(45, it.endLocal.minute)
        }
    }

    /**
     * An occurrence crossing midnight must land on the following date, not wrap
     * within the same one.
     */
    @Test
    fun an_occurrence_crossing_midnight_ends_on_the_next_day() {
        val occurrence = expandOccurrences(
            time = time("2026-08-05T23:30", "2026-08-06T00:30"),
            rule = null,
            windowStart = LocalDate(2026, 8, 1),
            windowEnd = LocalDate(2026, 8, 31),
        ).single()
        assertEquals(LocalDate(2026, 8, 6), occurrence.endLocal.date)
        assertEquals(0, occurrence.endLocal.hour)
        assertEquals(30, occurrence.endLocal.minute)
    }

    /**
     * ⚠ `recurrenceId` is the key joining `EventOverride` rows to occurrences,
     * so its format is single-sourced. If the two sides ever formatted it
     * differently every override would silently orphan.
     */
    @Test
    fun recurrence_ids_are_the_occurrences_original_start() {
        val occurrences = expandOccurrences(
            time = time("2026-08-03T09:00", "2026-08-03T09:30"),
            rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO"),
            windowStart = LocalDate(2026, 8, 1),
            windowEnd = LocalDate(2026, 8, 12),
        )
        assertEquals(listOf("2026-08-03T09:00", "2026-08-10T09:00"), occurrences.map { it.recurrenceId })
    }

    @Test
    fun exception_dates_parse_from_the_delimited_string_and_ignore_junk() {
        assertEquals(
            setOf(LocalDate(2026, 8, 2), LocalDate(2026, 8, 9)),
            parseExceptionDates("2026-08-02, 2026-08-09 ,,nonsense"),
        )
        assertTrue(parseExceptionDates("").isEmpty())
    }

    private fun occurrenceOf(t: EventTime) = EventOccurrence(
        startLocal = t.startLocal,
        endLocal = t.endLocal,
        zoneId = t.zoneId,
        allDay = t.allDay,
        recurrenceId = formatRecurrenceId(t.startLocal),
    )
}
