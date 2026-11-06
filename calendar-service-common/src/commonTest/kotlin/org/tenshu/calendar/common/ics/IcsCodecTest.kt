package org.tenshu.calendar.common.ics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iCalendar codec (CA-6).
 *
 * ⚠⚠ The tests that matter most are the **round-trip** ones at the bottom. Every
 * assertion above them checks a field we chose to model; the round trip checks
 * the fields we did *not*, which is the entire justification for `EventSource`
 * existing and the one thing a from-scratch serialiser would silently break.
 */
class IcsCodecTest {

    private val minimal = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Test//EN
        BEGIN:VEVENT
        UID:standup-1
        SUMMARY:Standup
        DTSTART;TZID=Europe/Berlin:20260803T090000
        DTEND;TZID=Europe/Berlin:20260803T093000
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    // --- content lines -------------------------------------------------------

    @Test
    fun a_folded_line_is_rejoined() {
        val text = "BEGIN:VEVENT\r\nSUMMARY:A very long\r\n  title\r\nEND:VEVENT"

        val event = parseIcs(text).children("VEVENT").single()

        assertEquals("A very long title", event.first("SUMMARY")?.value)
    }

    /** ⚠ One leading whitespace character is dropped, not all of it. */
    @Test
    fun folding_preserves_indentation_beyond_the_first_space() {
        val text = "BEGIN:VEVENT\r\nDESCRIPTION:line\r\n   indented\r\nEND:VEVENT"

        assertEquals(
            "line  indented",
            parseIcs(text).children("VEVENT").single().first("DESCRIPTION")?.value,
        )
    }

    @Test
    fun a_long_line_folds_and_unfolds_back_to_itself() {
        val value = "x".repeat(300)
        val component = IcsComponent(
            name = "VEVENT",
            properties = listOf(IcsProperty(name = "DESCRIPTION", value = value)),
        )

        val reparsed = parseIcs(serializeIcs(component)).children("VEVENT").single()

        assertEquals(value, reparsed.first("DESCRIPTION")?.value)
    }

    @Test
    fun parameters_survive_a_colon_inside_quotes() {
        val line = """DTSTART;TZID="GMT+01:00":20260803T090000"""

        val property = parseIcsLine(line)

        assertEquals("DTSTART", property?.name)
        assertEquals("GMT+01:00", property?.param("TZID"))
        assertEquals("20260803T090000", property?.value)
        // ⚠ …and re-quotes on the way out, or the line cannot be re-parsed.
        assertEquals(line, renderIcsLine(property!!))
    }

    @Test
    fun components_nest() {
        val text = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            BEGIN:VALARM
            TRIGGER:-PT10M
            END:VALARM
            SUMMARY:Standup
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val event = parseIcs(text).children("VCALENDAR").single().children("VEVENT").single()

        assertEquals("Standup", event.first("SUMMARY")?.value)
        assertEquals("-PT10M", event.children("VALARM").single().first("TRIGGER")?.value)
        assertNull(event.first("TRIGGER"), "an alarm's trigger is not the event's")
    }

    /** ⚠ A single malformed line must never cost the rest of the calendar. */
    @Test
    fun a_line_with_no_colon_is_skipped_not_fatal() {
        val text = "BEGIN:VEVENT\r\nGARBAGE\r\nSUMMARY:Standup\r\nEND:VEVENT"

        assertEquals(
            "Standup",
            parseIcs(text).children("VEVENT").single().first("SUMMARY")?.value,
        )
    }

    /** ⚠ A stray END must not empty the stack and swallow everything after it. */
    @Test
    fun a_stray_end_does_not_lose_the_rest_of_the_file() {
        val text = "END:VEVENT\r\nBEGIN:VEVENT\r\nSUMMARY:Standup\r\nEND:VEVENT"

        assertEquals(1, parseIcs(text).children("VEVENT").size)
    }

    // --- escaping ------------------------------------------------------------

    @Test
    fun escapes_round_trip_through_decode_and_encode() {
        val value = "Lunch, then talk; see notes\nline two \\ end"

        assertEquals(value, decodeText(encodeText(value)))
    }

    /** ⚠ Outlook writes `\N` for a newline; RFC 5545 allows either. */
    @Test
    fun a_capital_escape_is_a_newline_too() {
        assertEquals("a\nb", decodeText("a\\Nb"))
    }

    // --- durations -----------------------------------------------------------

    @Test
    fun durations_parse_to_signed_minutes() {
        assertEquals(-10, parseIcsDuration("-PT10M"))
        assertEquals(-60, parseIcsDuration("-PT1H"))
        assertEquals(-90, parseIcsDuration("-PT1H30M"))
        assertEquals(-1440, parseIcsDuration("-P1D"))
        assertEquals(-10080, parseIcsDuration("-P1W"))
        assertEquals(0, parseIcsDuration("PT0M"))
        assertEquals(60, parseIcsDuration("PT1H"))
    }

    /** ⚠ `M` outside a `T` section is *months*, which is not a duration we take. */
    @Test
    fun a_month_duration_is_refused_rather_than_read_as_minutes() {
        assertNull(parseIcsDuration("P2M"))
        assertNull(parseIcsDuration("PT10"), "trailing digits with no unit is malformed")
        assertNull(parseIcsDuration("10M"), "no leading P")
    }

    @Test
    fun triggers_format_back_to_what_they_parsed_from() {
        listOf("-PT10M", "-PT1H", "-PT1H30M", "-P1D", "PT0M", "PT9H").forEach { trigger ->
            val minutes = -(parseIcsDuration(trigger)!!)
            assertEquals(trigger, formatTrigger(minutes), "for $trigger")
        }
    }

    // --- import --------------------------------------------------------------

    @Test
    fun a_timed_event_reads_its_zone_and_civil_times() {
        val event = IcsImport.read(minimal).events.single()

        assertEquals("standup-1", event.uid)
        assertEquals("Standup", event.title)
        assertEquals("2026-08-03T09:00", event.startLocal)
        assertEquals("2026-08-03T09:30", event.endLocal)
        assertEquals("Europe/Berlin", event.timeZoneId)
        assertFalse(event.allDay)
        assertNull(event.recurrenceId)
    }

    /**
     * ⚠⚠ All-day is `VALUE=DATE`, never a guess from a midnight time — a timed
     * event that genuinely starts at 00:00 is a real thing.
     */
    @Test
    fun all_day_comes_from_the_value_parameter_not_from_midnight() {
        val allDay = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:holiday
            SUMMARY:Holiday
            DTSTART;VALUE=DATE:20260803
            DTEND;VALUE=DATE:20260804
            END:VEVENT
            """.trimIndent()
        ).events.single()
        assertTrue(allDay.allDay)
        assertEquals("", allDay.timeZoneId, "an all-day event has no zone by definition")

        val midnight = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:nightshift
            DTSTART;TZID=Europe/Berlin:20260803T000000
            END:VEVENT
            """.trimIndent()
        ).events.single()
        assertFalse(midnight.allDay)
    }

    @Test
    fun a_duration_stands_in_for_a_missing_end() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:call
            DTSTART:20260803T233000
            DURATION:PT1H
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertEquals("2026-08-04T00:30", event.endLocal, "and it crosses midnight correctly")
    }

    /** ⚠ Blank, not invented: `EventTime.parse` already implies the right end per kind. */
    @Test
    fun neither_end_nor_duration_leaves_the_end_blank() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:x
            DTSTART;VALUE=DATE:20260803
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertEquals("", event.endLocal)
    }

    /**
     * ⚠⚠ The sign flip. Reading this backwards fires every reminder *after* the
     * event, which surfaces as "reminders don't work" rather than as a sign bug.
     */
    @Test
    fun a_relative_alarm_becomes_positive_minutes_before() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:x
            DTSTART:20260803T090000
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H
            END:VALARM
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertEquals(listOf(15, 60), event.reminderMinutes)
    }

    /**
     * ⚠ An absolute or end-relative trigger has no offset to become. It is left
     * alone rather than guessed at — and survives in `raw`, which the round-trip
     * test below proves.
     */
    @Test
    fun an_absolute_or_end_relative_alarm_yields_no_offset() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:x
            DTSTART:20260803T090000
            BEGIN:VALARM
            TRIGGER;VALUE=DATE-TIME:20260803T080000Z
            END:VALARM
            BEGIN:VALARM
            TRIGGER;RELATED=END:-PT5M
            END:VALARM
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertTrue(event.reminderMinutes.isEmpty())
    }

    /**
     * ⚠⚠ **The `VALUE` parameter is the declaration of what the value is, and it
     * wins over the value's shape.** Found by mutation, not by review: the test
     * above used an absolute value that `parseIcsDuration` rejects anyway, so
     * deleting the `VALUE=DATE-TIME` guard changed nothing and the guard looked
     * decorative. A file whose parameter and value contradict each other is
     * exactly where trusting the shape would fire a reminder a day *late*.
     */
    @Test
    fun a_trigger_declared_absolute_is_not_read_as_a_duration_even_if_it_looks_like_one() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:x
            DTSTART:20260803T090000
            BEGIN:VALARM
            TRIGGER;VALUE=DATE-TIME:-P1D
            END:VALARM
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertTrue(event.reminderMinutes.isEmpty())
    }

    /**
     * ⚠ …and the same on the way out: neither form is one of ours to replace.
     * ⚠⚠ Both are asserted here because mutation showed each guard needs its own
     * case — the `VALUE=DATE-TIME` fixture left the `RELATED=END` branch
     * unexercised on the export side, and deleting it changed nothing.
     */
    @Test
    fun triggers_we_cannot_express_survive_export_rather_than_being_replaced() {
        val raw = """
            BEGIN:VEVENT
            UID:x
            DTSTART:20260803T090000
            BEGIN:VALARM
            TRIGGER;VALUE=DATE-TIME:-P1D
            END:VALARM
            BEGIN:VALARM
            TRIGGER;RELATED=END:-PT5M
            END:VALARM
            END:VEVENT
        """.trimIndent()

        val text = IcsExport.write(listOf(exportable(raw = raw, reminders = listOf(10))))

        assertTrue(text.contains("TRIGGER;VALUE=DATE-TIME:-P1D"), text)
        assertTrue(text.contains("TRIGGER;RELATED=END:-PT5M"), text)
        assertTrue(text.contains("TRIGGER:-PT10M"), "…alongside our own\n$text")
    }

    /** ⚠ Both `EXDATE` forms are in the wild; handling one drops exceptions silently. */
    @Test
    fun exception_dates_read_from_repeated_and_comma_separated_lines() {
        val event = IcsImport.read(
            """
            BEGIN:VEVENT
            UID:x
            DTSTART:20260803T090000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            EXDATE;VALUE=DATE:20260810,20260817
            EXDATE;VALUE=DATE:20260824
            END:VEVENT
            """.trimIndent()
        ).events.single()

        assertEquals("2026-08-10,2026-08-17,2026-08-24", event.exceptionDates)
    }

    /**
     * ⚠⚠ A series and its exceptions share one `UID` and differ only by
     * `RECURRENCE-ID`. Treating them all as series makes the last one win and
     * silently discards the recurrence.
     */
    @Test
    fun an_override_block_is_distinguished_by_its_recurrence_id() {
        val events = IcsImport.read(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:standup
            SUMMARY:Standup
            DTSTART:20260803T090000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:standup
            SUMMARY:Standup (moved)
            RECURRENCE-ID:20260810T090000
            DTSTART:20260810T110000
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        ).events

        assertEquals(2, events.size)
        assertNull(events[0].recurrenceId)
        assertEquals("2026-08-10T09:00", events[1].recurrenceId)
    }

    /** ⚠ Counted, never dropped silently — a user must not lose two of 44 events. */
    @Test
    fun an_event_with_no_usable_start_is_counted_as_skipped() {
        val result = IcsImport.read(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:good
            DTSTART:20260803T090000
            END:VEVENT
            BEGIN:VEVENT
            UID:no-start
            SUMMARY:Broken
            END:VEVENT
            BEGIN:VEVENT
            UID:bad-start
            DTSTART:not-a-date
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()
        )

        assertEquals(1, result.events.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun the_calendar_name_is_read_when_the_exporter_wrote_one() {
        assertEquals(
            "Work",
            IcsImport.read("BEGIN:VCALENDAR\r\nX-WR-CALNAME:Work\r\nEND:VCALENDAR").name,
        )
    }

    // --- export --------------------------------------------------------------

    private fun exportable(
        raw: String = "",
        allDay: Boolean = false,
        zone: String = "Europe/Berlin",
        end: String = "2026-08-03T09:30",
        reminders: List<Int> = emptyList(),
        recurrenceId: String? = null,
    ) = ExportableEvent(
        eventId = "standup-1",
        title = "Standup",
        startLocal = "2026-08-03T09:00",
        endLocal = end,
        timeZoneId = zone,
        allDay = allDay,
        location = "Room A",
        notes = "bring laptop",
        recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        exceptionDates = "2026-08-10",
        reminderMinutes = reminders,
        raw = raw,
        recurrenceId = recurrenceId,
    )

    @Test
    fun a_hand_authored_event_writes_a_complete_block() {
        val text = IcsExport.write(listOf(exportable()), calendarName = "Work")
        val event = IcsImport.read(text).events.single()

        assertEquals("standup-1", event.uid)
        assertEquals("Standup", event.title)
        assertEquals("2026-08-03T09:00", event.startLocal)
        assertEquals("2026-08-03T09:30", event.endLocal)
        assertEquals("Europe/Berlin", event.timeZoneId)
        assertEquals("Room A", event.location)
        assertEquals("bring laptop", event.notes)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", event.recurrenceRule)
        assertEquals("2026-08-10", event.exceptionDates)
        assertEquals("Work", IcsImport.read(text).name)
    }

    /**
     * ⚠ Floating means *no* `TZID` and *no* trailing `Z`. Stamping `Z` on it
     * would move the event by the reader's offset.
     */
    @Test
    fun a_floating_event_is_written_without_a_zone_or_a_z() {
        val text = IcsExport.write(listOf(exportable(zone = "")))

        assertTrue(text.contains("DTSTART:20260803T090000"), text)
        assertFalse(text.contains("DTSTART;TZID"), text)
        assertFalse(text.contains("090000Z"), text)
    }

    @Test
    fun an_all_day_event_is_written_as_a_date() {
        val text = IcsExport.write(
            listOf(exportable(allDay = true, end = "2026-08-04T00:00", zone = ""))
        )

        assertTrue(text.contains("DTSTART;VALUE=DATE:20260803"), text)
        assertTrue(text.contains("DTEND;VALUE=DATE:20260804"), text)
    }

    @Test
    fun reminders_are_written_as_display_alarms() {
        val text = IcsExport.write(listOf(exportable(reminders = listOf(10, 60))))

        assertEquals(listOf(10, 60), IcsImport.read(text).events.single().reminderMinutes)
        assertTrue(text.contains("ACTION:DISPLAY"), "required, or consumers reject the alarm")
    }

    /** ⚠ An `RRULE` value is structured; escaping its `;` produces an unreadable rule. */
    @Test
    fun a_recurrence_rule_is_not_escaped() {
        val text = IcsExport.write(listOf(exportable()))

        assertTrue(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO"), text)
    }

    @Test
    fun an_override_is_written_with_its_recurrence_id() {
        val text = IcsExport.write(listOf(exportable(recurrenceId = "2026-08-10T09:00")))

        assertEquals("2026-08-10T09:00", IcsImport.read(text).events.single().recurrenceId)
    }

    // --- the round trip ------------------------------------------------------

    private val exotic = """
        BEGIN:VEVENT
        UID:standup-1
        SUMMARY:Standup
        DTSTART;TZID=Europe/Berlin:20260803T090000
        DTEND;TZID=Europe/Berlin:20260803T093000
        ORGANIZER;CN=Anna:mailto:anna@example.com
        ATTENDEE;PARTSTAT=ACCEPTED:mailto:bo@example.com
        ATTACH:https://example.com/deck.pdf
        X-MICROSOFT-CDO-BUSYSTATUS:BUSY
        SEQUENCE:3
        BEGIN:VALARM
        TRIGGER;VALUE=DATE-TIME:20260803T080000Z
        ACTION:EMAIL
        END:VALARM
        END:VEVENT
    """.trimIndent()

    /**
     * ⚠⚠ **The test the whole sidecar exists for.** Everything we never modelled
     * has to come back out — and a from-scratch serialiser would pass every test
     * above this one while failing this.
     */
    @Test
    fun properties_we_do_not_model_survive_a_round_trip() {
        val imported = IcsImport.read(exotic).events.single()

        val text = IcsExport.write(listOf(exportable(raw = imported.raw)))

        listOf(
            "ORGANIZER;CN=Anna:mailto:anna@example.com",
            "ATTENDEE;PARTSTAT=ACCEPTED:mailto:bo@example.com",
            "ATTACH:https://example.com/deck.pdf",
            "X-MICROSOFT-CDO-BUSYSTATUS:BUSY",
            "SEQUENCE:3",
        ).forEach { assertTrue(text.contains(it), "lost $it\n\n$text") }
    }

    /** ⚠ Including an alarm shape we could not map onto an offset. */
    @Test
    fun an_unmappable_alarm_survives_a_round_trip() {
        val imported = IcsImport.read(exotic).events.single()

        val text = IcsExport.write(listOf(exportable(raw = imported.raw, reminders = listOf(10))))

        assertTrue(text.contains("TRIGGER;VALUE=DATE-TIME:20260803T080000Z"), text)
        assertTrue(text.contains("ACTION:EMAIL"), text)
        assertTrue(text.contains("TRIGGER:-PT10M"), "…alongside our own\n$text")
    }

    /**
     * ⚠⚠ **Native fields win.** The stored raw says "Standup" in Berlin; the row
     * says the user has since renamed and moved it, and the export must agree
     * with the app rather than with the file it came from.
     */
    @Test
    fun native_fields_overwrite_the_stored_source() {
        val imported = IcsImport.read(exotic).events.single()

        val text = IcsExport.write(
            listOf(exportable(raw = imported.raw).copy(title = "Renamed", location = "Room B"))
        )
        val reread = IcsImport.read(text).events.single()

        assertEquals("Renamed", reread.title)
        assertEquals("Room B", reread.location)
        assertFalse(text.contains("SUMMARY:Standup"), "the old value must not linger\n$text")
    }

    /**
     * ⚠ A field cleared in the app is cleared in the export, not left stale —
     * the other half of "native fields win".
     */
    @Test
    fun a_cleared_field_is_removed_rather_than_left_stale() {
        val imported = IcsImport.read(exotic).events.single()

        val text = IcsExport.write(listOf(exportable(raw = imported.raw).copy(location = "")))

        assertFalse(text.contains("LOCATION:"), text)
    }

    /**
     * ⚠⚠ RFC 5545 forbids `DTEND` and `DURATION` on one event, so `DURATION` is
     * stripped when we write an end — but stripping it when we *cannot* would
     * export an event with no length at all.
     */
    @Test
    fun duration_is_replaced_by_our_end_and_kept_when_we_have_none() {
        val withDuration = """
            BEGIN:VEVENT
            UID:call
            DTSTART:20260803T090000
            DURATION:PT1H
            END:VEVENT
        """.trimIndent()

        val replaced = IcsExport.write(listOf(exportable(raw = withDuration)))
        assertTrue(replaced.contains("DTEND"), replaced)
        assertFalse(replaced.contains("DURATION"), "both would be invalid\n$replaced")

        val kept = IcsExport.write(listOf(exportable(raw = withDuration, end = "")))
        assertFalse(kept.contains("DTEND"), kept)
        assertTrue(kept.contains("DURATION:PT1H"), "or the event loses its length\n$kept")
    }

    /** ⚠ A full file we wrote must read back as itself. */
    @Test
    fun a_file_we_wrote_reads_back_unchanged() {
        val events = listOf(
            exportable(reminders = listOf(10)),
            exportable(raw = exotic, recurrenceId = "2026-08-10T09:00"),
        )

        val once = IcsExport.write(events, calendarName = "Work")
        val reread = IcsImport.read(once)
        val twice = IcsExport.write(
            reread.events.mapIndexed { index, event ->
                events[index].copy(raw = event.raw)
            },
            calendarName = reread.name,
        )

        assertEquals(once, twice, "a second pass must be a fixed point")
    }
}
