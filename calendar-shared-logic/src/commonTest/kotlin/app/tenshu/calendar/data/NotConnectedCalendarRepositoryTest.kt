package app.tenshu.calendar.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/**
 * The honest-empty surface (CA-8a).
 *
 * ⚠⚠ Every write is listed here one by one, and that is the point of the file: the
 * failure this guards against is a method that returns normally on a device with no
 * realm, which the caller then renders as success. There is no reflection in common
 * Kotlin to enumerate the interface, so the enumeration is by hand — and a new
 * repository method added without a line here is exactly the gap to watch for.
 */
class NotConnectedCalendarRepositoryTest {

    private val repository = NotConnectedCalendarRepository()
    private val day = LocalDate(2026, 8, 9)

    // ── reads ────────────────────────────────────────────────────────────────

    @Test
    fun every_read_is_empty_rather_than_failing() = runTest {
        assertEquals(emptyList(), repository.calendars())
        assertEquals(emptyList(), repository.occurrences(day, day))
        assertEquals(emptyList(), repository.occurrences(day, day, includeHidden = true))
        assertEquals(emptyList(), repository.occurrencesOn(day))
        assertEquals("", repository.defaultCalendar())
        assertEquals(emptyList(), repository.subscriptions())
        assertEquals(emptyList(), repository.remindersFor("event-1"))
    }

    /**
     * ⚠ Read while building a *form*, so it answers "no reminder" instead of
     * refusing — an editor that cannot open is a worse answer than one whose
     * reminder field starts empty. The save is where the refusal belongs, and the
     * test below is what pins that half.
     */
    @Test
    fun the_default_reminder_is_absent_rather_than_a_refusal() = runTest {
        assertNull(repository.defaultReminderMinutes(allDay = false))
        assertNull(repository.defaultReminderMinutes(allDay = true))
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /**
     * ⚠⚠ The whole reason this class throws. `CalendarRepository` has no result
     * type, so a disconnected write can only return or throw — and returning is
     * indistinguishable from having saved, which closes the editor over an event
     * that does not exist.
     */
    @Test
    fun a_refused_write_says_why_it_was_refused() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            repository.saveEvent(EventDraft(calendarId = "c", title = "t", startLocal = "", endLocal = ""))
        }

        assertEquals(NotConnectedCalendarRepository.NOT_SET_UP, failure.message)
        assertTrue(
            failure.message.orEmpty().contains("Control"),
            "it must name Control, not 'the network' — that is what is actually missing",
        )
    }

    @Test
    fun no_event_write_quietly_succeeds() = runTest {
        val draft = EventDraft(calendarId = "c", title = "t", startLocal = "", endLocal = "")

        assertFailsWith<IllegalStateException> { repository.saveEvent(draft) }
        assertFailsWith<IllegalStateException> { repository.saveOccurrence(draft, "r") }
        assertFailsWith<IllegalStateException> { repository.deleteEvent("e") }
        assertFailsWith<IllegalStateException> { repository.deleteOccurrence("e", "r") }
    }

    @Test
    fun no_calendar_write_quietly_succeeds() = runTest {
        assertFailsWith<IllegalStateException> { repository.setCalendarVisible("c", true) }
        assertFailsWith<IllegalStateException> {
            repository.saveCalendar("c", "Work", 0xFF112233.toInt(), sensitive = false)
        }
        assertFailsWith<IllegalStateException> { repository.deleteCalendar("c") }
        assertFailsWith<IllegalStateException> { repository.setDefaultCalendar("c") }
    }

    @Test
    fun no_subscription_write_quietly_succeeds() = runTest {
        assertFailsWith<IllegalStateException> { repository.addSubscription("https://x/y.ics", "n") }
        assertFailsWith<IllegalStateException> { repository.setSubscriptionEnabled("s", false) }
        assertFailsWith<IllegalStateException> { repository.removeSubscription("s") }
        assertFailsWith<IllegalStateException> { repository.refreshSubscriptions() }
    }

    @Test
    fun no_interop_or_reminder_write_quietly_succeeds() = runTest {
        assertFailsWith<IllegalStateException> { repository.importIcs("BEGIN:VCALENDAR", "c") }
        assertFailsWith<IllegalStateException> { repository.exportIcs() }
        assertFailsWith<IllegalStateException> { repository.setReminders("e", listOf(10)) }
        assertFailsWith<IllegalStateException> { repository.dismissReminder("r", "rid") }
        assertFailsWith<IllegalStateException> { repository.snoozeReminder("r", "rid", 0L) }
    }

    /**
     * ⚠⚠ The one write that must **not** throw. It runs from the shell's launch
     * effect with nothing catching it, its contract is "make sure, or leave it
     * alone", and on a device with no realm there is nothing to ensure — so
     * throwing here would take down the app on the very screen that is supposed to
     * explain why it is empty.
     */
    @Test
    fun ensuring_a_default_calendar_is_a_no_op_and_never_throws() = runTest {
        repository.ensureDefaultCalendar()
    }
}
