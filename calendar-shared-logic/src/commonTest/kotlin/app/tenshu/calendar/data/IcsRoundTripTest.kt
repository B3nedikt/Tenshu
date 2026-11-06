package app.tenshu.calendar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.test.asTenshuClient
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.common.TestProxy

/**
 * Import and export through real storage (CA-6).
 *
 * ⚠⚠ `IcsCodecTest` proves the *format*; this proves the **wiring** — that a
 * parsed block reaches the right ops, that a re-import converges instead of
 * duplicating, and that what comes back out has been through the store rather
 * than round-tripped in memory.
 */
class IcsRoundTripTest {

    private fun repo(proxy: TestProxy) =
        TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { "device-a" },
            reachability = null /* test: no reachability engine */,
        )

    private suspend fun seed(proxy: TestProxy) {
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .saveCalendar("work", "Work", 0x4285F4)
    }

    private val file = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Other App//EN
        X-WR-CALNAME:Imported
        BEGIN:VEVENT
        UID:standup-1
        SUMMARY:Standup
        DTSTART;TZID=Europe/Berlin:20260803T090000
        DTEND;TZID=Europe/Berlin:20260803T093000
        LOCATION:Room A
        DESCRIPTION:bring laptop
        RRULE:FREQ=WEEKLY;BYDAY=MO
        EXDATE;VALUE=DATE:20260817
        ORGANIZER;CN=Anna:mailto:anna@example.com
        X-VENDOR-FLAG:keep-me
        BEGIN:VALARM
        ACTION:DISPLAY
        TRIGGER:-PT15M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:standup-1
        SUMMARY:Standup (moved)
        RECURRENCE-ID:20260810T090000
        DTSTART;TZID=Europe/Berlin:20260810T110000
        DTEND;TZID=Europe/Berlin:20260810T113000
        END:VEVENT
        BEGIN:VEVENT
        UID:lunch
        SUMMARY:Lunch
        DTSTART;VALUE=DATE:20260805
        DTEND;VALUE=DATE:20260806
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    /** Where the file's `standup-1` is stored in "work" — namespaced by its calendar (§7). */
    private val STANDUP = "work~standup-1"

    // --- import --------------------------------------------------------------

    @Test
    fun an_import_reports_what_it_wrote() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val result = repo(proxy).importIcs(file, calendarId = "work", origin = "work.ics")

        assertEquals(2, result.imported, "two series")
        assertEquals(1, result.overrides, "one exception")
        assertEquals(0, result.skipped)
    }

    @Test
    fun imported_events_appear_in_the_calendar() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")

        val august = repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        assertEquals(
            listOf("Standup", "Lunch", "Standup (moved)", "Standup", "Standup"),
            august.map { it.title },
            "4 Mondays minus the EXDATE, one of them moved, plus the all-day",
        )
    }

    /** ⚠ The `EXDATE` really removes an occurrence — the exception is not decorative. */
    @Test
    fun an_exdate_from_the_file_removes_that_occurrence() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")

        assertTrue(repo.occurrencesOn(LocalDate(2026, 8, 17)).isEmpty())
    }

    @Test
    fun an_override_from_the_file_moves_its_occurrence() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")

        val moved = repo.occurrencesOn(LocalDate(2026, 8, 10)).single()
        assertEquals("Standup (moved)", moved.title)
        assertEquals(11, moved.startLocal.hour)
        assertTrue(moved.isOverridden)
    }

    @Test
    fun a_valarm_becomes_a_reminder() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")

        assertEquals(listOf(15), repo.remindersFor(STANDUP))
    }

    /**
     * ⚠⚠ **Idempotent by `UID`.** This is what makes "import it again after I
     * fixed something" safe rather than a way to end up with two of everything.
     */
    @Test
    fun re_importing_the_same_file_converges_rather_than_duplicating() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")
        val once = repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))
        repo.importIcs(file, calendarId = "work")
        val twice = repo.occurrences(LocalDate(2026, 8, 1), LocalDate(2026, 8, 31))

        assertEquals(once.map { it.title to it.recurrenceId }, twice.map { it.title to it.recurrenceId })
    }

    /**
     * ⚠⚠ **Importing into B a `UID` that lives in A copies it; it does not move it**
     * (`calendar-external-sources.md` §7). Keyed by bare `UID`, the second import rewrote A's
     * row with B's calendar id — the user asked for a second copy and lost the first. Both
     * exports still carry the file's `UID`, so neither copy forgets the meeting it came from.
     */
    @Test
    fun importing_into_a_second_calendar_copies_rather_than_moves() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .saveCalendar("home", "Home", 0x0F9D58)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")
        repo.importIcs(file, calendarId = "home")

        listOf("work", "home").forEach { calendarId ->
            val exported = repo.exportIcs(calendarId)
            assertTrue("SUMMARY:Lunch" in exported, "$calendarId still holds Lunch")
            assertTrue("SUMMARY:Standup" in exported, "$calendarId still holds the series")
            assertTrue("UID:lunch" in exported, "$calendarId exports the file's UID")
        }
    }

    /**
     * ⚠ …and a row CA-6 stored under the bare `UID` before the fix is re-imported in place, not
     * doubled beside a namespaced twin — which is why existing users need no re-keying migration.
     */
    @Test
    fun re_importing_onto_a_bare_uid_row_from_before_the_fix_converges() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .saveEvent("lunch", "Old lunch", "2026-08-05", calendarId = "work", allDay = true)
        val repo = repo(proxy)

        repo.importIcs(file, calendarId = "work")

        assertEquals(listOf("Lunch"), repo.occurrencesOn(LocalDate(2026, 8, 5)).map { it.title })
    }

    /** ⚠ And a re-import *narrows* reminders to what the file says, rather than accumulating. */
    @Test
    fun re_importing_a_file_with_fewer_alarms_removes_the_extra_reminder() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")
        assertEquals(listOf(15), repo.remindersFor(STANDUP))

        repo.importIcs(file.replace(
            "BEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT15M\nEND:VALARM\n", ""
        ), calendarId = "work")

        assertTrue(repo.remindersFor(STANDUP).isEmpty())
    }

    /**
     * ⚠⚠ A file that lists an exception **before** its series. Real exports do
     * this, and writing the override first would leave `saveEvent`'s
     * `existingExceptionDates` read seeing no series — silently dropping the
     * file's EXDATEs.
     */
    @Test
    fun an_exception_listed_before_its_series_still_lands() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)

        repo.importIcs(
            """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:standup
            SUMMARY:Moved
            RECURRENCE-ID:20260810T090000
            DTSTART:20260810T110000
            END:VEVENT
            BEGIN:VEVENT
            UID:standup
            SUMMARY:Standup
            DTSTART:20260803T090000
            DTEND:20260803T093000
            RRULE:FREQ=WEEKLY;BYDAY=MO
            EXDATE;VALUE=DATE:20260817
            END:VEVENT
            END:VCALENDAR
            """.trimIndent(),
            calendarId = "work",
        )

        assertEquals("Moved", repo.occurrencesOn(LocalDate(2026, 8, 10)).single().title)
        assertTrue(repo.occurrencesOn(LocalDate(2026, 8, 17)).isEmpty(), "the EXDATE survived")
    }

    /** ⚠ A block with no `UID` must not duplicate on every import either. */
    @Test
    fun a_block_with_no_uid_is_still_idempotent() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val anonymous = """
            BEGIN:VEVENT
            SUMMARY:Anonymous
            DTSTART:20260803T090000
            END:VEVENT
        """.trimIndent()

        repo.importIcs(anonymous, calendarId = "work")
        repo.importIcs(anonymous, calendarId = "work")

        assertEquals(1, repo.occurrencesOn(LocalDate(2026, 8, 3)).size)
    }

    // --- export --------------------------------------------------------------

    /**
     * ⚠⚠ **The test the sidecar exists for, through storage.** `ORGANIZER` and
     * `X-VENDOR-FLAG` were never modelled and never will be; they come back
     * because `EventSource` kept the block — and because CA-6 finally gave that
     * type a writer.
     */
    @Test
    fun properties_we_never_modelled_survive_the_store() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")

        val exported = repo.exportIcs("work")

        assertTrue(exported.contains("ORGANIZER;CN=Anna:mailto:anna@example.com"), exported)
        assertTrue(exported.contains("X-VENDOR-FLAG:keep-me"), exported)
    }

    @Test
    fun an_export_reads_back_as_the_same_calendar() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")

        val reread = IcsImport.read(repo.exportIcs("work"))

        assertEquals(3, reread.events.size, "two series and one override")
        assertEquals(0, reread.skipped)
        val standup = reread.events.single { it.uid == "standup-1" && it.recurrenceId == null }
        assertEquals("Standup", standup.title)
        assertEquals("Room A", standup.location)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", standup.recurrenceRule)
        assertEquals("2026-08-17", standup.exceptionDates)
        assertEquals(listOf(15), standup.reminderMinutes)
        assertEquals("Europe/Berlin", standup.timeZoneId)
    }

    /** ⚠ An override exports as its own block carrying the series' UID. */
    @Test
    fun an_override_exports_with_its_recurrence_id_and_the_series_uid() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")

        val override = IcsImport.read(repo.exportIcs("work"))
            .events.single { it.recurrenceId != null }

        assertEquals("standup-1", override.uid)
        assertEquals("2026-08-10T09:00", override.recurrenceId)
        assertEquals("Standup (moved)", override.title)
        assertEquals("", override.recurrenceRule, "an override is one occurrence, not a series")
    }

    @Test
    fun an_all_day_event_survives_the_store_as_all_day() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")

        val lunch = IcsImport.read(repo.exportIcs("work")).events.single { it.uid == "lunch" }

        assertTrue(lunch.allDay)
        assertEquals("2026-08-05T00:00", lunch.startLocal)
    }

    /**
     * ⚠⚠ **Native fields win.** The stored `EventSource` still says "Standup";
     * the row says the user has since renamed it, and the export must agree with
     * the app rather than with the file it came from.
     */
    @Test
    fun an_edit_made_after_the_import_wins_over_the_stored_source() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")

        repo.saveEvent(
            EventDraft(
                eventId = STANDUP,
                calendarId = "work",
                title = "Renamed",
                startLocal = "2026-08-03T09:00",
                endLocal = "2026-08-03T09:30",
                recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
            )
        )
        val exported = repo.exportIcs("work")

        assertTrue(exported.contains("SUMMARY:Renamed"), exported)
        assertFalse(exported.contains("SUMMARY:Standup\r\n"), "the old value must not linger")
        assertTrue(exported.contains("X-VENDOR-FLAG:keep-me"), "…and the unknowns still survive")
    }

    /** ⚠ A hand-authored event has no sidecar, and exports fine without one. */
    @Test
    fun an_event_authored_in_the_app_exports_without_a_source_row() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val repo = repo(proxy)
        val id = repo.saveEvent(
            EventDraft(
                calendarId = "work",
                title = "Coffee",
                startLocal = "2026-08-04T15:00",
                endLocal = "2026-08-04T15:30",
            )
        )
        repo.setReminders(id, listOf(10))

        val exported = IcsImport.read(repo.exportIcs("work")).events.single()

        assertEquals("Coffee", exported.title)
        assertEquals("2026-08-04T15:00", exported.startLocal)
        assertEquals(listOf(10), exported.reminderMinutes)
    }

    /** ⚠ Exporting one calendar must not sweep up another's events. */
    @Test
    fun exporting_one_calendar_leaves_the_others_out() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveCalendar("personal", "Personal", 0x0F9D58)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")
        repo.saveEvent(
            EventDraft(calendarId = "personal", title = "Dentist",
                startLocal = "2026-08-06T10:00", endLocal = "2026-08-06T10:30")
        )

        val work = IcsImport.read(repo.exportIcs("work"))

        assertTrue(work.events.none { it.title == "Dentist" })
        assertEquals("Work", work.name, "and it names the calendar it exported")
    }

    /** ⚠ A blank id exports everything — the "back up my whole calendar" case. */
    @Test
    fun a_blank_calendar_id_exports_every_calendar() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
        service.saveCalendar("work", "Work", 0x4285F4)
        service.saveCalendar("personal", "Personal", 0x0F9D58)
        val repo = repo(proxy)
        repo.importIcs(file, calendarId = "work")
        repo.saveEvent(
            EventDraft(calendarId = "personal", title = "Dentist",
                startLocal = "2026-08-06T10:00", endLocal = "2026-08-06T10:30")
        )

        val all = IcsImport.read(repo.exportIcs())

        assertTrue(all.events.any { it.title == "Dentist" })
        assertTrue(all.events.any { it.title == "Standup" })
    }

    /** ⚠ An empty calendar exports a valid, empty file rather than nothing at all. */
    @Test
    fun an_empty_calendar_exports_a_readable_file() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val exported = repo(proxy).exportIcs("work")

        assertTrue(exported.startsWith("BEGIN:VCALENDAR"), exported)
        assertTrue(IcsImport.read(exported).events.isEmpty())
    }

    /**
     * ⚠⚠ **The whole point of CA-6, in one assertion:** a file goes in, comes
     * out, goes back in, and the second store agrees with the first.
     */
    @Test
    fun a_file_survives_import_export_and_import_again() = runTest {
        val first = createCalendarProxy(backgroundScope)
        seed(first)
        val a = repo(first)
        a.importIcs(file, calendarId = "work")
        val exported = a.exportIcs("work")

        val second = createCalendarProxy(backgroundScope)
        seed(second)
        val b = repo(second)
        b.importIcs(exported, calendarId = "work")

        val window = LocalDate(2026, 8, 1) to LocalDate(2026, 8, 31)
        assertEquals(
            a.occurrences(window.first, window.second).map { it.title to it.startLocal },
            b.occurrences(window.first, window.second).map { it.title to it.startLocal },
        )
        assertEquals(listOf(15), a.remindersFor(STANDUP))
        assertEquals(a.remindersFor(STANDUP), b.remindersFor(STANDUP))
    }
}
