package app.tenshu.calendar.data

import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCopyIds
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.calendar.data.external.ExternalSourceKind
import app.tenshu.calendar.data.external.ProviderConversion
import app.tenshu.calendar.data.external.ProviderEventRow
import app.tenshu.calendar.data.external.ProviderReminderRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.common.ics.IcsImport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.test.asTenshuClient
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.common.TestProxy

/**
 * *Copy once*, end to end: Android rows → `ProviderConversion` → the repository → the batched op
 * → storage → the read path (`calendar-external-sources.md` CX-1).
 */
class ExternalCopyRepositoryTest {

    private val berlin = TimeZone.of("Europe/Berlin")

    private fun repo(proxy: TestProxy) = TenshuCalendarRepository(
        client = proxy.asTenshuClient(),
        deviceIdentity = CalendarDeviceIdentity { "device-a" },
        reachability = null /* test: no reachability engine */,
    )

    private fun source(name: String = "Work", key: String = "com.google|me@gmail.com|work") = ExternalCalendar(
        kind = ExternalSourceKind.ANDROID_PROVIDER,
        sourceKey = key,
        handle = "4",
        name = name,
        accountName = "me@gmail.com",
        accountType = "com.google",
        colorArgb = 0xFF4285F4.toInt(),
        holdsEvents = true,
    )

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(berlin).toEpochMilliseconds()

    /** A weekly standup with one moved occurrence and one cancelled, and a reminder. */
    private fun standupSnapshot(uid: String = "standup@corp"): ExternalSnapshot = ProviderConversion.snapshot(
        events = listOf(
            ProviderEventRow(
                id = 1,
                uid2445 = uid,
                title = "Standup",
                dtStart = at(2026, 7, 1, 9),
                duration = "PT30M",
                eventTimezone = "Europe/Berlin",
                rrule = "FREQ=WEEKLY;BYDAY=WE",
            ),
            ProviderEventRow(
                id = 2,
                title = "Standup (late)",
                originalId = 1,
                originalInstanceTime = at(2026, 7, 8, 9),
                dtStart = at(2026, 7, 8, 11),
                dtEnd = at(2026, 7, 8, 11, 30),
                eventTimezone = "Europe/Berlin",
            ),
            ProviderEventRow(
                id = 3,
                originalId = 1,
                originalInstanceTime = at(2026, 7, 15, 9),
                status = ProviderConversion.STATUS_CANCELED,
            ),
        ),
        reminders = listOf(ProviderReminderRow(eventId = 1, minutes = 10, method = 1)),
    )

    private suspend fun july(proxy: TestProxy, calendarId: String) =
        repo(proxy).occurrences(LocalDate(2026, 7, 1), LocalDate(2026, 7, 31))
            .filter { it.calendarId == calendarId }

    @Test
    fun a_copied_series_reads_back_with_its_moved_and_cancelled_occurrences() = runTest {
        val proxy = createCalendarProxy(backgroundScope)

        val result = repo(proxy).copyExternalCalendar(source(), standupSnapshot())

        assertEquals("Work", result.calendarName)
        assertEquals(1, result.copied)
        assertEquals(1, result.added)
        assertEquals(1, result.overrides)
        val occurrences = july(proxy, result.calendarId)
        assertEquals(
            listOf("2026-07-01T09:00", "2026-07-08T11:00", "2026-07-22T09:00", "2026-07-29T09:00"),
            occurrences.map { it.startLocal.toString() },
            "the 8th moved to 11:00, the 15th is gone",
        )
        assertEquals("Standup (late)", occurrences[1].title)
        assertEquals(listOf(10), repo(proxy).remindersFor(occurrences[0].eventId))
        assertEquals(
            0xFF4285F4.toInt(),
            repo(proxy).calendars().single { it.calendarId == result.calendarId }.colorArgb,
            "opaque, like the palette — the calendars screen draws it as given",
        )
    }

    /** ⭐ Copy again: the same rows, nothing new, and the user's name for their copy survives. */
    @Test
    fun copying_again_converges_and_keeps_the_users_name() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val first = repo(proxy).copyExternalCalendar(source(), standupSnapshot())
        repo(proxy).saveCalendar(first.calendarId, "Day job", 0x4285F4, sensitive = false)

        val second = repo(proxy).copyExternalCalendar(source(), standupSnapshot())

        assertEquals(first.calendarId, second.calendarId)
        assertEquals(0, second.added, "nothing new on a second copy")
        assertEquals("Day job", second.calendarName)
        assertEquals(4, july(proxy, first.calendarId).size, "no doubled occurrences")
    }

    /** ⚠⚠ An invite in two calendars shares its `UID` — and must stay in both. */
    @Test
    fun the_same_meeting_in_two_calendars_is_copied_into_both() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val mine = repo(proxy).copyExternalCalendar(source("Work"), standupSnapshot())
        val team = repo(proxy).copyExternalCalendar(
            source("Team", key = "com.google|me@gmail.com|team"),
            standupSnapshot(),
        )

        assertNotEquals(mine.calendarId, team.calendarId)
        assertEquals(4, july(proxy, mine.calendarId).size, "still in the first calendar")
        assertEquals(4, july(proxy, team.calendarId).size)
    }

    @Test
    fun a_name_already_taken_is_disambiguated_by_account() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .saveCalendar("mine", "Work", 0x0F9D58)

        val result = repo(proxy).copyExternalCalendar(source("Work"), standupSnapshot())

        assertEquals("Work (me@gmail.com)", result.calendarName)
    }

    /** Past one chunk (200), every series and the last series' override still land. */
    @Test
    fun a_calendar_bigger_than_one_chunk_lands_whole() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val series = (1..450).map { n ->
            imported(uid = "e$n", start = "2026-07-01T09:00", rule = "FREQ=DAILY;COUNT=3")
        }
        val override = imported(uid = "e450", start = "2026-07-02T15:00").copy(recurrenceId = "2026-07-02T09:00")

        val result = repo(proxy).copyExternalCalendar(source(), ExternalSnapshot(series + override, skipped = 0))

        assertEquals(450, result.added)
        val day2 = repo(proxy).occurrences(LocalDate(2026, 7, 2), LocalDate(2026, 7, 2))
            .filter { it.calendarId == result.calendarId }
        assertEquals(450, day2.size)
        assertEquals(1, day2.count { it.startLocal.toString() == "2026-07-02T15:00" })
    }

    /** ⚠⚠ Long descriptions: more calls than the count alone would make, and every event lands. */
    @Test
    fun a_calendar_of_long_descriptions_lands_whole_across_byte_bounded_batches() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val text = "x".repeat(3_000)
        val series = (1..200).map { n ->
            imported(uid = "e$n", start = "2026-07-01T09:00").copy(notes = text, raw = "DESCRIPTION:$text")
        }

        val result = repo(proxy).copyExternalCalendar(source(), ExternalSnapshot(series, skipped = 0))

        assertEquals(200, result.added)
        assertEquals(0, result.skipped)
        assertEquals(200, july(proxy, result.calendarId).size)
    }

    /**
     * ⚠⚠ The stored id is namespaced; the exported `UID` must still be the source's, on the
     * series *and* on its override — or an exported invite stops matching its meeting.
     */
    @Test
    fun an_exported_copy_carries_the_source_uid_not_ours() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val result = repo(proxy).copyExternalCalendar(source(), standupSnapshot())

        val exported = IcsImport.read(repo(proxy).exportIcs(result.calendarId)).events

        assertEquals(setOf("standup@corp"), exported.map { it.uid }.toSet())
        assertEquals(2, exported.size, "the series and its moved occurrence")
        assertTrue(exported.any { it.recurrenceId == "2026-07-08T09:00" })
        assertEquals(
            ExternalCopyIds.eventId(result.calendarId, "standup@corp", emptySet()),
            july(proxy, result.calendarId).first().eventId,
            "while the stored id stays namespaced",
        )
    }

    /**
     * ⚠⚠ Export a copy and import the file back into the same calendar: the file names events by
     * the source `UID`, and the import must land on the namespaced rows rather than beside them.
     */
    @Test
    fun importing_an_exported_copy_back_does_not_double_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val result = repo(proxy).copyExternalCalendar(source(), standupSnapshot())
        val file = repo(proxy).exportIcs(result.calendarId)

        repo(proxy).importIcs(file, result.calendarId, origin = "file")

        val occurrences = july(proxy, result.calendarId)
        assertEquals(4, occurrences.size, "no doubled occurrences")
        assertEquals(setOf(ExternalCopyIds.eventId(result.calendarId, "standup@corp", emptySet())),
            occurrences.map { it.eventId }.toSet())
        assertEquals("Standup (late)", occurrences[1].title, "the override still applies")

        val again = repo(proxy).copyExternalCalendar(source(), standupSnapshot())
        assertEquals(0, again.added, "and a later phone copy still finds its own rows")
    }

    private fun imported(uid: String, start: String, rule: String = "") = ImportedEvent(
        uid = uid,
        title = uid,
        startLocal = start,
        endLocal = "",
        timeZoneId = "Europe/Berlin",
        allDay = false,
        location = "",
        notes = "",
        recurrenceRule = rule,
        exceptionDates = "",
        recurrenceId = null,
        reminderMinutes = emptyList(),
        raw = "",
    )
}
