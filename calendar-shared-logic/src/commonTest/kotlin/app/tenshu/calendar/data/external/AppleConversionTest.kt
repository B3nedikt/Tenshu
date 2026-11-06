package app.tenshu.calendar.data.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.common.ics.IcsImport

/**
 * EventKit rows → `ImportedEvent`s (`calendar-external-sources.md` CX-2, §5.6). Rows are shaped to
 * what `EKEventStore.events(matching:)` returns: one row per occurrence, each carrying its series.
 */
class AppleConversionTest {

    private val berlin = TimeZone.of("Europe/Berlin")
    private fun ms(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

    /** Wednesdays 09:00–09:30 in Berlin, from 1 July 2026. */
    private val weekly = AppleRecurrenceRow(frequency = 1, daysOfTheWeek = listOf(3 to 0))

    private fun standup(
        day: Int,
        movedTo: Pair<Int, Int>? = null,
        uid: String? = "standup@corp",
        recurrence: AppleRecurrenceRow? = weekly,
        alarms: List<Long> = emptyList(),
    ): AppleOccurrenceRow {
        val (h, mi) = movedTo ?: (9 to 0)
        return AppleOccurrenceRow(
            eventIdentifier = "EK-1",
            externalIdentifier = uid,
            title = if (movedTo != null) "Standup (late)" else "Standup",
            notes = "",
            location = "",
            startMs = ms(berlin, 2026, 7, day, h, mi),
            endMs = ms(berlin, 2026, 7, day, h, mi + 30),
            allDay = false,
            timeZoneId = "Europe/Berlin",
            occurrenceDateMs = ms(berlin, 2026, 7, day, 9, 0),
            isDetached = movedTo != null,
            recurrence = recurrence,
            seriesStartMs = ms(berlin, 2026, 7, 1, 9, 0),
            seriesEndMs = ms(berlin, 2026, 7, 1, 9, 30),
            alarmOffsetsSeconds = alarms,
        )
    }

    /** The predicate's window, as the reader builds it: July, Berlin midnight to Berlin midnight. */
    private val windowStartMs get() = ms(berlin, 2026, 7, 1)
    private val windowEndMs get() = ms(berlin, 2026, 8, 1)

    private fun convert(vararg rows: AppleOccurrenceRow) =
        AppleConversion.snapshot(rows.toList(), windowStartMs, windowEndMs, berlin)

    /** A one-row-per-occurrence series of 30-minute events, all on [rule]. */
    private fun occurrence(
        zone: TimeZone,
        day: Int,
        h: Int,
        rule: AppleRecurrenceRow,
        seriesDay: Int,
        month: Int = 7,
    ) = AppleOccurrenceRow(
        eventIdentifier = "EK-9",
        externalIdentifier = "series@corp",
        title = "Series",
        notes = "",
        location = "",
        startMs = ms(zone, 2026, month, day, h),
        endMs = ms(zone, 2026, month, day, h, 30),
        allDay = false,
        timeZoneId = zone.id,
        occurrenceDateMs = ms(zone, 2026, month, day, h),
        isDetached = false,
        recurrence = rule,
        seriesStartMs = ms(zone, 2026, 7, seriesDay, h),
        seriesEndMs = ms(zone, 2026, 7, seriesDay, h, 30),
    )

    /** ⚠⚠ The stored id is namespaced; the export reads the source's UID back from the sidecar. */
    @Test
    fun a_copied_series_carries_its_source_uid_in_the_sidecar() {
        val series = convert(standup(1), standup(8), standup(15), standup(22), standup(29)).events.single()
        assertEquals("standup@corp", IcsImport.uidOf(series.raw))
    }

    /** ⚠ No BYYEARDAY / BYWEEKNO in our expander: skipped and named, never a bare yearly rule. */
    @Test
    fun a_yearly_rule_on_days_or_weeks_of_the_year_is_skipped() {
        val byYearDay = AppleRecurrenceRow(frequency = 3, daysOfTheYear = listOf(100, 200))
        val byWeekNo = AppleRecurrenceRow(frequency = 3, weeksOfTheYear = listOf(20))

        assertEquals(setOf("standup@corp"), convert(standup(1, recurrence = byYearDay)).unreadableUids)
        assertEquals(setOf("standup@corp"), convert(standup(1, recurrence = byWeekNo)).unreadableUids)
    }

    /**
     * ⚠ Week start decides which weeks a fortnightly multi-day rule lands in. Mondays and Sundays
     * every other week from Monday 6 July, weeks starting Sunday: 6, 19, 20 July — not 6, 12, 20, 26.
     */
    @Test
    fun the_rules_week_start_is_kept() {
        val rule = AppleRecurrenceRow(
            frequency = 1,
            interval = 2,
            daysOfTheWeek = listOf(1 to 0, 7 to 0),
            firstDayOfWeek = 7,
        )
        val rows = listOf(6, 19, 20).map { occurrence(berlin, it, 9, rule, seriesDay = 6) }

        val series = AppleConversion.snapshot(rows, windowStartMs, windowEndMs, berlin).events.single()
        assertEquals("", series.exceptionDates, "no occurrence is missing once weeks start on Sunday")
    }

    /**
     * ⚠ An occurrence the predicate could not have returned is not a deletion. On a Berlin device,
     * 31 July 23:00 in Los Angeles is 1 August 08:00 — past the window's end.
     */
    @Test
    fun an_occurrence_past_the_windows_end_instant_is_not_inferred_deleted() {
        val la = TimeZone.of("America/Los_Angeles")
        val daily = AppleRecurrenceRow(frequency = 0)
        val rows = listOf(29, 30).map { occurrence(la, it, 23, daily, seriesDay = 29) }

        val series = AppleConversion.snapshot(rows, windowStartMs, windowEndMs, berlin).events.single()
        assertEquals("", series.exceptionDates)
    }

    @Test
    fun the_occurrences_of_a_series_fold_back_into_one_event_with_its_rule() {
        val snapshot = convert(standup(1), standup(8), standup(15), standup(22), standup(29))

        val series = snapshot.events.single()
        assertEquals("standup@corp", series.uid)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", series.recurrenceRule)
        assertEquals("2026-07-01T09:00", series.startLocal)
        assertEquals("2026-07-01T09:30", series.endLocal)
        assertEquals("Europe/Berlin", series.timeZoneId)
        assertEquals("", series.exceptionDates, "every expected occurrence came back")
    }

    /** The slots EventKit actually returned — positive evidence a mirror sync may act on. */
    @Test
    fun the_snapshot_records_which_original_slots_were_seen() {
        val snapshot = convert(standup(1), standup(8, movedTo = 11 to 0), standup(22))
        assertEquals(setOf("2026-07-01", "2026-07-08", "2026-07-22"), snapshot.observedOccurrenceDates["standup@corp"])
    }

    /** ⚠⚠ EventKit has no exception dates: a missing occurrence inside the window is one. */
    @Test
    fun an_occurrence_eventkit_did_not_return_is_an_inferred_exception_date() {
        val snapshot = convert(standup(1), standup(8), standup(22), standup(29))

        assertEquals("2026-07-15", snapshot.events.single().exceptionDates)
    }

    /** ⚠ A moved occurrence is an override keyed by where it was — not a deletion. */
    @Test
    fun a_detached_occurrence_is_an_override_not_an_exception() {
        val snapshot = convert(standup(1), standup(8, movedTo = 11 to 0), standup(15), standup(22), standup(29))

        val (series, override) = snapshot.events.partition { it.recurrenceId == null }
        assertEquals("", series.single().exceptionDates)
        assertEquals("2026-07-08T09:00", override.single().recurrenceId)
        assertEquals("2026-07-08T11:00", override.single().startLocal)
        assertEquals("Standup (late)", override.single().title)
        assertEquals("standup@corp", override.single().uid, "an override carries its series' identity")
    }

    /**
     * ⚠⚠ Shaped from the iOS 27.2 simulator: a moved occurrence's identifiers carry a `/RID=`
     * suffix. It must still fold into its series as an override — not become a second series and
     * leave its slot behind as a deleted occurrence.
     */
    @Test
    fun a_moved_occurrence_with_rid_suffixed_identifiers_is_its_series_override() {
        val moved = standup(8, movedTo = 11 to 0).copy(
            eventIdentifier = "EK-1/RID=817030800",
            externalIdentifier = "standup@corp/RID=817030800",
        )

        val snapshot = convert(standup(1), moved, standup(15), standup(22), standup(29))

        val (series, override) = snapshot.events.partition { it.recurrenceId == null }
        assertEquals("standup@corp", series.single().uid)
        assertEquals("Standup", series.single().title)
        assertEquals("", series.single().exceptionDates, "a moved occurrence is not a deleted one")
        assertEquals("standup@corp", override.single().uid)
        assertEquals("2026-07-08T09:00", override.single().recurrenceId)
    }

    /** ⚠ The series' fields come from an unmoved occurrence, even when a moved one is earliest. */
    @Test
    fun the_series_title_comes_from_an_unmoved_occurrence_when_the_first_was_moved() {
        val snapshot = convert(standup(1, movedTo = 11 to 0), standup(8), standup(15), standup(22), standup(29))

        val series = snapshot.events.single { it.recurrenceId == null }
        assertEquals("Standup", series.title)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", series.recurrenceRule)
    }

    /**
     * ⚠ The window may hold only an edited occurrence of a long-period series; the edit's fields
     * must not become the series'. The reader hands the master's separately.
     */
    @Test
    fun a_series_seen_only_through_an_edit_keeps_its_masters_fields() {
        val renamed = standup(8, movedTo = 11 to 0).copy(
            series = AppleSeriesFields("Standup", "agenda", "Room 1", allDay = false, timeZoneId = "Europe/Berlin"),
        )

        val series = convert(renamed).events.single { it.recurrenceId == null }
        assertEquals("Standup", series.title)
        assertEquals("agenda", series.notes)
        assertEquals("Room 1", series.location)
    }

    /** ⚠ Year-wide weekdays ("the 20th Monday", "every Monday") are skipped; month-relative kept. */
    @Test
    fun a_yearly_weekday_rule_without_a_month_is_skipped() {
        val twentiethMonday = AppleRecurrenceRow(frequency = 3, daysOfTheWeek = listOf(1 to 20))
        val everyMonday = AppleRecurrenceRow(frequency = 3, daysOfTheWeek = listOf(1 to 0))
        assertEquals(setOf("standup@corp"), convert(standup(1, recurrence = everyMonday)).unreadableUids)
        val firstAndFifteenth = AppleRecurrenceRow(frequency = 3, daysOfTheMonth = listOf(1, 15))
        assertEquals(setOf("standup@corp"), convert(standup(1, recurrence = firstAndFifteenth)).unreadableUids)
        val thanksgiving = AppleRecurrenceRow(frequency = 3, daysOfTheWeek = listOf(4 to 4), monthsOfTheYear = listOf(11))

        assertEquals(setOf("standup@corp"), convert(standup(1, recurrence = twentiethMonday)).unreadableUids)
        assertTrue(convert(standup(1, recurrence = thanksgiving)).unreadableUids.isEmpty())
    }

    /** ⚠ All-day: local midnight in the device zone, so the date is the date — never shifted by UTC. */
    @Test
    fun an_all_day_event_keeps_its_date_in_the_device_zone() {
        val holiday = AppleOccurrenceRow(
            eventIdentifier = "EK-2",
            externalIdentifier = "holiday",
            title = "Holiday",
            notes = "",
            location = "",
            startMs = ms(berlin, 2026, 7, 10),
            endMs = ms(berlin, 2026, 7, 11),
            allDay = true,
            timeZoneId = null,
            occurrenceDateMs = ms(berlin, 2026, 7, 10),
            isDetached = false,
            recurrence = null,
            seriesStartMs = ms(berlin, 2026, 7, 10),
            seriesEndMs = ms(berlin, 2026, 7, 11),
        )

        val event = convert(holiday).events.single()
        assertEquals("2026-07-10T00:00", event.startLocal)
        assertEquals("2026-07-11T00:00", event.endLocal, "exclusive end, the iCal convention")
        assertEquals("", event.timeZoneId)
        assertTrue(event.allDay)
    }

    /** ⚠ A rule the expander cannot express is skipped and named — never flattened to a one-off. */
    @Test
    fun a_rule_with_set_positions_is_skipped_and_its_series_spared() {
        val lastFriday = AppleRecurrenceRow(frequency = 2, daysOfTheWeek = listOf(5 to 0), setPositions = listOf(-1))

        val snapshot = convert(standup(1, recurrence = lastFriday))

        assertTrue(snapshot.events.isEmpty())
        assertEquals(1, snapshot.skipped)
        assertEquals(setOf("standup@corp"), snapshot.unreadableUids)
    }

    @Test
    fun alarms_before_the_start_are_reminders_in_minutes() {
        val snapshot = convert(standup(1, alarms = listOf(-900L, -3600L, -900L)))
        assertEquals(listOf(15, 60), snapshot.events.single().reminderMinutes)
    }

    @Test
    fun without_an_external_id_the_device_id_names_the_series() {
        val snapshot = convert(standup(1, uid = null), standup(8, uid = null))
        assertEquals("apple-event-EK-1", snapshot.events.single().uid)
    }

    /** ⚠ (§5.11) The device-local identifier is a handle, not the identity. */
    @Test
    fun a_calendars_key_does_not_depend_on_its_device_local_identifier() {
        val row = AppleCalendarRow("ABC-123", "Work", "Exchange", "exchange", 0xFF0000)
        val again = row.copy(identifier = "XYZ-789")

        assertEquals(AppleConversion.calendar(row).sourceKey, AppleConversion.calendar(again).sourceKey)
        assertEquals(ExternalSourceKind.APPLE_EVENTKIT, AppleConversion.calendar(row).kind)
        assertEquals("ABC-123", AppleConversion.calendar(row).handle)
    }
}
