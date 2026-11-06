package app.tenshu.calendar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.test.asTenshuClient
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.common.TestProxy

/**
 * Where a reminder tap lands (CA-5f).
 *
 * ⚠⚠ The payload carries **ids and no text** — deliberately, so a redacted
 * reminder's title cannot ride back onto a lock screen in a `PendingIntent`
 * extra. The cost is that the app must resolve them, and this is that resolution.
 */
class ReminderDeepLinkTest {

    private fun repo(proxy: TestProxy) =
        TenshuCalendarRepository(
            client = proxy.asTenshuClient(),
            deviceIdentity = CalendarDeviceIdentity { "device-a" },
            reachability = null /* test: no reachability engine */,
        )

    private suspend fun service(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID).also {
            it.saveCalendar("work", "Work", 0x4285F4)
        }

    // --- parsing -------------------------------------------------------------

    @Test
    fun a_calendar_payload_parses_to_its_ids_and_day() {
        val link = ReminderDeepLink.parse("calendar/occurrence/standup|2026-08-10T09:00")

        assertEquals("standup", link?.eventId)
        assertEquals("2026-08-10T09:00", link?.recurrenceId)
        assertEquals(LocalDate(2026, 8, 10), link?.anchorDate)
    }

    /** ⚠ Another app's payload is not ours — the tap belongs to whoever owns it. */
    @Test
    fun another_apps_payload_is_not_a_calendar_link() {
        assertNull(ReminderDeepLink.parse("routine/fired/porch-light"))
        assertNull(ReminderDeepLink.parse(""))
    }

    /** ⚠ A malformed recurrence id yields no day rather than throwing on open. */
    @Test
    fun an_unparseable_recurrence_id_has_no_anchor() {
        assertNull(ReminderDeepLink("standup", "not-a-date").anchorDate)
    }

    // --- resolution ----------------------------------------------------------

    @Test
    fun it_finds_the_occurrence_the_payload_names() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy).saveEvent(
            "standup", "Standup", "2026-08-03T09:00", endLocal = "2026-08-03T09:30",
            calendarId = "work", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        val found = repo(proxy).resolveReminderLink(
            ReminderDeepLink("standup", "2026-08-10T09:00")
        )

        assertEquals("Standup", found?.title)
        assertEquals("2026-08-10T09:00", found?.recurrenceId)
    }

    /**
     * ⚠⚠ **The window, not the day.** A `recurrenceId` names the occurrence's
     * *original* slot, and an override can have moved it — asking for that one day
     * would find nothing for exactly the events a user is most likely to have
     * fiddled with.
     */
    @Test
    fun it_finds_an_occurrence_an_override_moved_to_another_day() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val calendar = service(proxy)
        calendar.saveEvent(
            "standup", "Standup", "2026-08-03T09:00", endLocal = "2026-08-03T09:30",
            calendarId = "work", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )
        calendar.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-08-10T09:00",
            startLocal = "2026-08-11T09:00",
            endLocal = "2026-08-11T09:30",
        )

        val found = repo(proxy).resolveReminderLink(
            ReminderDeepLink("standup", "2026-08-10T09:00")
        )

        assertEquals(LocalDate(2026, 8, 11), found?.startLocal?.date)
    }

    /**
     * ⚠⚠ A reminder was armed for this event, so the user asked to be told —
     * refusing to open it because the calendar is hidden on *this* device would
     * make the notification unactionable.
     */
    @Test
    fun a_hidden_calendars_occurrence_still_opens() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy).saveEvent(
            "standup", "Standup", "2026-08-03T09:00", calendarId = "work",
        )
        val repo = repo(proxy)
        repo.setCalendarVisible("work", visible = false)

        val found = repo.resolveReminderLink(ReminderDeepLink("standup", "2026-08-03T09:00"))

        assertEquals("Standup", found?.title)
    }

    /** ⚠ Deleted between the arm and the tap: nothing to open, and no crash. */
    @Test
    fun a_deleted_occurrence_resolves_to_nothing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy)

        assertNull(repo(proxy).resolveReminderLink(ReminderDeepLink("gone", "2026-08-03T09:00")))
    }

    @Test
    fun a_link_with_no_anchor_resolves_to_nothing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy)

        assertNull(repo(proxy).resolveReminderLink(ReminderDeepLink("standup", "nonsense")))
    }
}
