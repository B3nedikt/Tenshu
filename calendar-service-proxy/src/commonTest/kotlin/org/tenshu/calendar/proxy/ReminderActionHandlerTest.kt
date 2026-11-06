package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.common.reminder.CalendarAlerts
import org.tenshu.calendar.dismissed
import org.tenshu.calendar.recurrenceId
import org.tenshu.calendar.reminderId
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.snoozedUntilEpochMs
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.fields
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.select
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.get
import org.tenshu.calendar.proxy.impl.ReminderActionHandler

/**
 * The notification's Snooze and Dismiss buttons, end to end through storage
 * (CA-5f).
 *
 * ⚠⚠ **This is the half that was missing since CA-5a.** `ReminderPlanner` has
 * honoured dismissal and snooze from the first slice, and nothing anywhere could
 * write either — a reminder swiped away came back on the next re-arm.
 */
class ReminderActionHandlerTest {

    private val now = 1_785_000_000_000L

    private suspend fun seed(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID).also { service ->
            service.saveCalendar("work", "Work", 0x4285F4)
            service.saveEvent(
                eventId = "standup",
                title = "Standup",
                startLocal = "2026-08-03T09:00",
                endLocal = "2026-08-03T09:30",
                calendarId = "work",
            )
            service.saveReminder("standup", 10)
        }

    private suspend fun states(proxy: TestProxy): List<Triple<String, Long, Boolean>> =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .reminderStates
            .select {
                fields(
                    ReminderState.Field.REMINDER_ID,
                    ReminderState.Field.RECURRENCE_ID,
                    ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
                    ReminderState.Field.DISMISSED,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()
            .map {
                Triple(it.reminderId.get(), it.snoozedUntilEpochMs.get(), it.dismissed.get())
            }

    private fun payload(recurrenceId: String = "2026-08-03T09:00") =
        "calendar/occurrence/standup|$recurrenceId"

    /**
     * ⚠ Built directly rather than through `initCalendarService`, so these tests
     * exercise the handler and not the wiring. The wiring has its own test below —
     * conflating them would mean a broken handler and a missing binding failed the
     * same assertions.
     */
    private fun handler(proxy: TestProxy) = ReminderActionHandler(
        executionContext = proxy.executionContext,
        implementationFactory = { id ->
            calendarServiceProxyImplementationFactory(id, nowEpochMs = { now })
        },
        nowEpochMs = { now },
    )

    @Test
    fun dismiss_writes_a_dismissed_state() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val handled = handler(proxy).handle(payload(), CalendarAlerts.ACTION_DISMISS)

        assertTrue(handled)
        val (reminderId, snooze, dismissed) = states(proxy).single()
        assertEquals("standup|10", reminderId)
        assertTrue(dismissed)
        assertEquals(0L, snooze, "a dismissal is not also a snooze")
    }

    @Test
    fun snooze_writes_a_deadline_ten_minutes_out() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        handler(proxy).handle(payload(), CalendarAlerts.ACTION_SNOOZE)

        val (_, snooze, dismissed) = states(proxy).single()
        assertEquals(now + 10 * 60_000L, snooze)
        assertFalse(dismissed, "snoozing is not dismissing — the planner ranks dismissal higher")
    }

    /**
     * ⚠⚠ **Every reminder on the occurrence, not just the one that rang.** An
     * event can carry "10 minutes before" *and* "1 hour before"; the notification
     * names the occurrence, not which fired. Writing one would let the other
     * arrive minutes after the user dismissed.
     */
    @Test
    fun dismissing_covers_every_reminder_on_the_event() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = seed(proxy)
        service.saveReminder("standup", 60)

        handler(proxy).handle(payload(), CalendarAlerts.ACTION_DISMISS)

        assertEquals(
            setOf("standup|10", "standup|60"),
            states(proxy).map { it.first }.toSet(),
        )
        assertTrue(states(proxy).all { it.third })
    }

    /** ⚠ The state is per-occurrence: dismissing Monday must not silence Tuesday. */
    @Test
    fun a_dismissal_is_scoped_to_the_occurrence_it_names() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        handler(proxy).handle(payload("2026-08-10T09:00"), CalendarAlerts.ACTION_DISMISS)

        assertEquals("2026-08-10T09:00", statesRecurrence(proxy).single())
    }

    /**
     * ⚠ Not ours ⇒ `false`, so another app's handler gets a look. A handler that
     * claimed everything would make `CompositeAlertActionHandler` a one-app list.
     */
    @Test
    fun a_payload_from_another_app_is_declined() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val handled = handler(proxy).handle("routine/fired/porch-light", CalendarAlerts.ACTION_DISMISS)

        assertFalse(handled)
        assertTrue(states(proxy).isEmpty())
    }

    /**
     * ⚠ An unrecognised action on a payload we *do* own is still ours — handing
     * it on would give it to a handler that understands it even less.
     */
    @Test
    fun an_unknown_action_on_our_payload_is_claimed_but_writes_nothing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)

        val handled = handler(proxy).handle(payload(), "calendar.explode")

        assertTrue(handled)
        assertTrue(states(proxy).isEmpty())
    }

    /** ⚠ Deleted between the arm and the tap: nothing to write, still claimed. */
    @Test
    fun an_event_with_no_reminders_left_is_claimed_without_writing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .saveCalendar("work", "Work", 0x4285F4)

        val handled = handler(proxy).handle(payload(), CalendarAlerts.ACTION_DISMISS)

        assertTrue(handled)
        assertTrue(states(proxy).isEmpty())
    }

    private suspend fun statesRecurrence(proxy: TestProxy): List<String> =
        environmentFor(proxy).getService<CalendarService>(CalendarService.ID)
            .reminderStates
            .select {
                fields(
                    ReminderState.Field.REMINDER_ID,
                    ReminderState.Field.RECURRENCE_ID,
                    ReminderState.Field.SNOOZED_UNTIL_EPOCH_MS,
                    ReminderState.Field.DISMISSED,
                )
            }
            .getAll()
            .let { (it as? TResult.Success)?.value?.toList() }
            .orEmpty()
            .map { it.recurrenceId.get() }

    /**
     * ⚠⚠ The wiring, separately: `initCalendarService` fills the holder the
     * framework-instantiated receiver reads. A handler that works and a holder
     * nobody fills look identical from the app — the buttons do nothing.
     */
    @Test
    fun init_populates_the_holder_the_receiver_reads() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        seed(proxy)
        val holder = CalendarReminderActionHolder()
        assertFalse(
            holder.handle(payload(), CalendarAlerts.ACTION_DISMISS),
            "an unfilled holder declines rather than pretending",
        )

        proxy.executionContext.initCalendarService(
            implementationFactory = { id ->
                calendarServiceProxyImplementationFactory(id, nowEpochMs = { now })
            },
            actionHandler = holder,
            nowEpochMs = { now },
        )

        assertTrue(holder.handle(payload(), CalendarAlerts.ACTION_DISMISS))
        assertTrue(states(proxy).single().third)
    }
}
