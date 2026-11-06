package app.tenshu.calendar.data.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.common.ics.IcsImport

/**
 * The Android calendar-provider rules (`calendar-external-sources.md` §5.4, §5.5), off a device.
 *
 * ⚠ Every instant here is built from a civil time in a named zone, never written as a literal —
 * a literal epoch is unreadable in review, and a wrong one would pass for a right one.
 */
class ProviderConversionTest {

    private val berlin = TimeZone.of("Europe/Berlin")

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone).toEpochMilliseconds()

    private fun utcMidnight(y: Int, mo: Int, d: Int) = at(TimeZone.UTC, y, mo, d)

    private fun timed(id: Long = 1, uid: String = "standup@example.com") = ProviderEventRow(
        id = id,
        uid2445 = uid,
        title = "Standup",
        dtStart = at(berlin, 2026, 7, 1, 9, 0),
        dtEnd = at(berlin, 2026, 7, 1, 9, 30),
        eventTimezone = "Europe/Berlin",
    )

    private fun convert(vararg rows: ProviderEventRow, reminders: List<ProviderReminderRow> = emptyList()) =
        ProviderConversion.snapshot(rows.toList(), reminders)

    // ── time ─────────────────────────────────────────────────────────────────

    @Test
    fun a_timed_event_is_civil_time_in_its_own_zone() {
        val event = convert(timed()).events.single()

        assertEquals("2026-07-01T09:00", event.startLocal, "09:00 Berlin, not 07:00 UTC")
        assertEquals("2026-07-01T09:30", event.endLocal)
        assertEquals("Europe/Berlin", event.timeZoneId)
        assertEquals(false, event.allDay)
    }

    /**
     * ⚠⚠ The all-day trap. Android stores the date as UTC midnight; reading it in any zone west of
     * Greenwich gives the day before. The conversion must never consult a device zone at all.
     */
    @Test
    fun an_all_day_event_keeps_its_utc_date_and_has_no_zone() {
        val christmas = ProviderEventRow(
            id = 7,
            uid2445 = "xmas",
            title = "Christmas",
            dtStart = utcMidnight(2026, 12, 25),
            dtEnd = utcMidnight(2026, 12, 26),
            eventTimezone = "UTC",
            allDay = true,
        )

        val event = convert(christmas).events.single()

        assertEquals("2026-12-25T00:00", event.startLocal)
        assertEquals("2026-12-26T00:00", event.endLocal, "exclusive end, the iCal convention")
        assertEquals("", event.timeZoneId, "an all-day event has no zone (§3 D3)")
        assertTrue(event.allDay)
    }

    /**
     * ⚠ The provider contract says an all-day row carries `UTC`, but a sync adapter that wrote the
     * user's own zone would make a reader that trusts the column move the date. The date is UTC's
     * whatever the column says.
     */
    @Test
    fun an_all_day_event_is_read_in_utc_even_when_the_row_names_another_zone() {
        val sloppy = ProviderEventRow(
            id = 8,
            uid2445 = "xmas-la",
            dtStart = utcMidnight(2026, 12, 25),
            dtEnd = utcMidnight(2026, 12, 26),
            eventTimezone = "America/Los_Angeles",
            allDay = true,
        )

        val event = convert(sloppy).events.single()

        assertEquals("2026-12-25T00:00", event.startLocal, "not the 24th, as Los Angeles would read it")
        assertEquals("", event.timeZoneId)
    }

    @Test
    fun a_recurring_event_takes_its_length_from_android_style_duration() {
        val weekly = timed().copy(dtEnd = null, duration = "P1800S", rrule = "FREQ=WEEKLY;BYDAY=WE")

        val event = convert(weekly).events.single()

        assertEquals("2026-07-01T09:30", event.endLocal)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", event.recurrenceRule)
    }

    @Test
    fun an_rrule_prefix_is_stripped() {
        val event = convert(timed().copy(rrule = "RRULE:FREQ=DAILY")).events.single()
        assertEquals("FREQ=DAILY", event.recurrenceRule)
    }

    @Test
    fun durations_in_every_shape_android_writes() {
        val cases = mapOf(
            "PT1H" to 3_600L,
            "P3600S" to 3_600L,
            "P1D" to 86_400L,
            "P1W" to 604_800L,
            "PT1H30M" to 5_400L,
            "-PT10M" to -600L,
            "+PT5M" to 300L,
        )
        cases.forEach { (raw, seconds) ->
            assertEquals(seconds, ProviderConversion.parseDurationSeconds(raw), raw)
        }
        listOf("", "1H", "P", "PT", "P1X", "PT1").forEach {
            assertNull(ProviderConversion.parseDurationSeconds(it), "'$it' is not a duration")
        }
    }

    @Test
    fun a_row_with_neither_end_nor_duration_leaves_the_end_blank_for_the_op_to_imply() {
        val event = convert(timed().copy(dtEnd = null)).events.single()
        assertEquals("", event.endLocal)
    }

    // ── exceptions ──────────────────────────────────────────────────────────

    /** ⚠⚠ Dropping a cancelled exception would bring back the occurrence the user deleted. */
    @Test
    fun a_cancelled_exception_becomes_an_exdate_not_an_event() {
        val series = timed().copy(dtEnd = null, duration = "PT30M", rrule = "FREQ=WEEKLY;BYDAY=WE")
        val cancelled = ProviderEventRow(
            id = 2,
            originalId = 1,
            originalInstanceTime = at(berlin, 2026, 7, 15, 9, 0),
            status = ProviderConversion.STATUS_CANCELED,
        )

        val snapshot = convert(series, cancelled)

        assertEquals(1, snapshot.events.size, "no event for the cancelled occurrence")
        assertEquals("2026-07-15", snapshot.events.single().exceptionDates)
        assertEquals(0, snapshot.skipped)
    }

    /** ⚠⚠ An unreadable changed occurrence flags its series, so a mirror keeps the stored override. */
    @Test
    fun an_unreadable_exception_flags_its_series_as_incomplete() {
        val series = timed().copy(dtEnd = null, duration = "PT30M", rrule = "FREQ=WEEKLY;BYDAY=WE")
        val noStart = ProviderEventRow(
            id = 2,
            originalId = 1,
            originalInstanceTime = at(berlin, 2026, 7, 8, 9, 0),
            dtStart = null,
            eventTimezone = "Europe/Berlin",
        )
        val noInstance = noStart.copy(id = 3, originalInstanceTime = null, dtStart = at(berlin, 2026, 7, 15, 11, 0))

        // Each case on its own, so neither branch can pass for the other.
        for (broken in listOf(noStart, noInstance)) {
            val snapshot = convert(series, broken)
            assertEquals(1, snapshot.skipped)
            assertEquals(setOf("standup@example.com"), snapshot.incompleteOverridesFor, "row ${broken.id}")
            assertTrue(snapshot.unreadableUids.isEmpty(), "the series itself was read")
        }
    }

    @Test
    fun a_moved_occurrence_is_an_override_keyed_by_where_it_used_to_be() {
        val series = timed().copy(dtEnd = null, duration = "PT30M", rrule = "FREQ=WEEKLY;BYDAY=WE")
        val moved = ProviderEventRow(
            id = 2,
            title = "Standup (late)",
            originalId = 1,
            originalInstanceTime = at(berlin, 2026, 7, 8, 9, 0),
            dtStart = at(berlin, 2026, 7, 8, 11, 0),
            dtEnd = at(berlin, 2026, 7, 8, 11, 30),
            eventTimezone = "Europe/Berlin",
        )

        val override = convert(series, moved).events.single { it.recurrenceId != null }

        assertEquals("2026-07-08T09:00", override.recurrenceId, "the occurrence it replaces")
        assertEquals("2026-07-08T11:00", override.startLocal)
        assertEquals("2026-07-08T11:30", override.endLocal)
        assertEquals("standup@example.com", override.uid, "an override carries its series' identity")
        assertEquals("Standup (late)", override.title)
    }

    @Test
    fun an_override_is_found_by_its_series_server_id_too() {
        val series = timed().copy(syncId = "g-123", rrule = "FREQ=DAILY")
        val moved = ProviderEventRow(
            id = 9,
            originalSyncId = "g-123",
            originalInstanceTime = at(berlin, 2026, 7, 2, 9, 0),
            dtStart = at(berlin, 2026, 7, 2, 10, 0),
        )
        assertEquals(1, convert(series, moved).events.count { it.recurrenceId != null })
    }

    @Test
    fun an_exception_without_its_series_is_counted_not_dropped() {
        val orphan = ProviderEventRow(
            id = 5,
            originalId = 404,
            originalInstanceTime = at(berlin, 2026, 7, 8, 9, 0),
            dtStart = at(berlin, 2026, 7, 8, 11, 0),
        )
        val snapshot = convert(timed(), orphan)

        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.skipped)
    }

    @Test
    fun the_exdate_column_in_every_shape_the_provider_writes() {
        val dates = ProviderConversion.parseExdates(
            "20260708T070000Z,20260715T070000Z\nEurope/Berlin;20260722T090000\nTZID=UTC:20260729T070000",
            berlin,
        )
        assertEquals(
            setOf(
                LocalDate(2026, 7, 8),
                LocalDate(2026, 7, 15),
                LocalDate(2026, 7, 22),
                LocalDate(2026, 7, 29),
            ),
            dates,
        )
    }

    /** ⚠ A UTC value late in the evening is the next day in Berlin — the date is the series' date. */
    @Test
    fun an_exdate_is_dated_in_the_series_zone() {
        assertEquals(
            setOf(LocalDate(2026, 7, 9)),
            ProviderConversion.parseExdates("20260708T230000Z", berlin),
        )
    }

    @Test
    fun the_exdate_column_and_cancelled_exceptions_are_merged() {
        val series = timed().copy(rrule = "FREQ=DAILY", exdate = "20260702T070000Z")
        val cancelled = ProviderEventRow(
            id = 2,
            originalId = 1,
            originalInstanceTime = at(berlin, 2026, 7, 3, 9, 0),
            status = ProviderConversion.STATUS_CANCELED,
        )
        assertEquals("2026-07-02,2026-07-03", convert(series, cancelled).events.single().exceptionDates)
    }

    // ── rows we do not import ───────────────────────────────────────────────

    @Test
    fun rows_pending_deletion_and_cancelled_series_are_left_out_silently() {
        val snapshot = convert(
            timed(id = 1),
            timed(id = 2, uid = "gone").copy(deleted = true),
            timed(id = 3, uid = "off").copy(status = ProviderConversion.STATUS_CANCELED),
        )
        assertEquals(listOf("standup@example.com"), snapshot.events.map { it.uid })
        assertEquals(0, snapshot.skipped, "neither is a failure the user should be told about")
    }

    @Test
    fun a_series_with_no_start_is_counted() {
        val snapshot = convert(timed(), timed(id = 2, uid = "broken").copy(dtStart = null))
        assertEquals(1, snapshot.events.size)
        assertEquals(1, snapshot.skipped)
    }

    // ── reminders ───────────────────────────────────────────────────────────

    @Test
    fun only_reminders_that_notify_come_across() {
        val reminders = listOf(
            ProviderReminderRow(eventId = 1, minutes = 10, method = 1),   // alert
            ProviderReminderRow(eventId = 1, minutes = 30, method = 0),   // default
            ProviderReminderRow(eventId = 1, minutes = 5, method = 4),    // alarm
            ProviderReminderRow(eventId = 1, minutes = 60, method = 2),   // email — not ours to send
            ProviderReminderRow(eventId = 1, minutes = -1, method = 1),   // "use the default"
            ProviderReminderRow(eventId = 1, minutes = 10, method = 0),   // duplicate offset
            ProviderReminderRow(eventId = 99, minutes = 15, method = 1),  // another event
        )
        assertEquals(listOf(5, 10, 30), convert(timed(), reminders = reminders).events.single().reminderMinutes)
    }

    /**
     * Shaped like a Google birthday on a real phone: "on the day at 9:00" is `-540` (after the
     * all-day start), "a week before" is positive. Only Android's `-1` sentinel is dropped.
     */
    @Test
    fun an_all_day_reminder_after_midnight_comes_across() {
        val birthday = ProviderEventRow(
            id = 1,
            uid2445 = "birthday",
            title = "Birthday",
            dtStart = utcMidnight(2026, 3, 30),
            dtEnd = utcMidnight(2026, 3, 31),
            eventTimezone = "UTC",
            allDay = true,
        )
        val reminders = listOf(
            ProviderReminderRow(eventId = 1, minutes = -540, method = 1),
            ProviderReminderRow(eventId = 1, minutes = 9540, method = 1),
            ProviderReminderRow(eventId = 1, minutes = -1, method = 1),
        )
        assertEquals(listOf(-540, 9540), convert(birthday, reminders = reminders).events.single().reminderMinutes)
    }

    /** ⚠⚠ A skipped series names every id it could have had, so a mirror's sweep can spare it. */
    @Test
    fun a_skipped_series_reports_the_ids_it_could_have_been_stored_under() {
        val broken = timed(id = 9, uid = "broken@x").copy(dtStart = null, syncId = "srv-9")
        val snapshot = convert(timed(), broken)

        assertEquals(1, snapshot.skipped)
        assertEquals(setOf("broken@x", "srv-9", "android-event-9"), snapshot.unreadableUids)
    }

    // ── identity ────────────────────────────────────────────────────────────

    @Test
    fun the_uid_is_the_icalendar_uid_then_the_server_id_then_the_row() {
        val ids = convert(
            timed(id = 1, uid = "ical-uid").copy(syncId = "server-1"),
            timed(id = 2, uid = "").copy(syncId = "server-2"),
            timed(id = 3, uid = ""),
        ).events.map { it.uid }

        assertEquals(listOf("ical-uid", "server-2", "android-event-3"), ids)
    }

    /** ⚠ Two series sharing a `UID` must both survive, not have the second overwrite the first. */
    @Test
    fun a_uid_taken_by_another_series_falls_through_to_the_server_id() {
        val ids = convert(
            timed(id = 1, uid = "split").copy(syncId = "a"),
            timed(id = 2, uid = "split").copy(syncId = "b"),
        ).events.map { it.uid }

        assertEquals(listOf("split", "b"), ids)
    }

    /**
     * ⚠⚠ The sidecar is how the export gets the source's identity back once the stored id is
     * namespaced — and how `RDATE`, which we cannot model, survives at all.
     */
    @Test
    fun the_sidecar_carries_the_source_uid_and_what_we_cannot_model() {
        val series = timed().copy(rrule = "FREQ=WEEKLY", rdate = "Europe/Berlin;20260704T090000")
        val raw = convert(series).events.single().raw

        assertEquals("standup@example.com", IcsImport.uidOf(raw))
        assertTrue("RDATE;TZID=Europe/Berlin:20260704T090000" in raw, raw)
        val reread = IcsImport.read(raw).events.single()
        assertEquals("Standup", reread.title)
        assertEquals("2026-07-01T09:00", reread.startLocal)
        assertEquals("Europe/Berlin", reread.timeZoneId)
    }

    // ── calendars ───────────────────────────────────────────────────────────

    @Test
    fun a_synced_calendar_is_keyed_by_its_server_id_and_a_local_one_by_its_row() {
        val google = ProviderConversion.calendar(
            ProviderCalendarRow(
                id = 4,
                accountName = "me@gmail.com",
                accountType = "com.google",
                displayName = "Work",
                color = 0x4285F4,
                syncId = "work@group.calendar.google.com",
                syncEvents = true,
            )
        )
        val local = ProviderConversion.calendar(
            ProviderCalendarRow(
                id = 9,
                accountName = "Phone",
                accountType = "LOCAL",
                displayName = null,
                color = null,
                syncId = null,
                syncEvents = false,
            )
        )

        assertEquals("com.google|me@gmail.com|work@group.calendar.google.com", google.sourceKey)
        assertEquals("4", google.handle)
        assertEquals("LOCAL|Phone|local:9", local.sourceKey)
        assertEquals("Phone", local.name, "an unnamed calendar takes its account's name")
        assertEquals(false, local.holdsEvents)
    }
}
