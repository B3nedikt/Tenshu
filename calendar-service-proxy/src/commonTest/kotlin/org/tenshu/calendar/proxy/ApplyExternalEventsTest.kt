package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.applyExternalEvents
import org.tenshu.calendar.client.calendarService
import org.tenshu.calendar.proxy.impl.allReminderStates
import org.tenshu.calendar.proxy.impl.allReminders
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.proxy.impl.allMirrors
import org.tenshu.calendar.proxy.impl.allMirrorStamps
import org.tenshu.calendar.saveCalendarMirrorStamp
import org.tenshu.calendar.deleteCalendar
import org.tenshu.calendar.deleteCalendarMirror
import org.tenshu.calendar.saveCalendarMirror
import org.tenshu.calendar.saveReminder
import org.tenshu.calendar.setReminderState
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.calendar.test.environmentFor
import org.tenshu.calendar.test.event
import org.tenshu.calendar.test.override
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.DefaultTenshuClient
import org.tenshu.core.common.TenshuEnvironment
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.request.AsyncTaskExecutor
import org.tenshu.core.common.request.LocalRequestChain
import org.tenshu.core.schema.Type

/**
 * The batched write a copied calendar arrives through (`calendar-external-sources.md` CX-0).
 *
 * ⚠ The first test goes **over the wire** — serialized and parsed the way Binder frames a
 * request — because four lists of composites in one op frame is exactly what an in-process call
 * would never exercise (the `RecordImportedHealthUnitTest` precedent).
 */
class ApplyExternalEventsTest {

    private suspend fun service(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService(CalendarService.ID)

    private fun event(proxy: TestProxy, id: String, endLocal: String = "2026-07-01T09:30") = Event.create(
        context = proxy.executionContext.context,
        eventId = id,
        calendarId = "copy",
        title = "Standup",
        startLocal = "2026-07-01T09:00",
        endLocal = endLocal,
        timeZoneId = "Europe/Berlin",
        recurrenceRule = "FREQ=WEEKLY;BYDAY=WE",
    )

    @Test
    fun a_batch_lands_whole_over_the_wire_and_reports_what_was_new() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy).saveCalendar("copy", "Work", 0x4285F4)
        val client = serializingClientOf(proxy)
        val context = proxy.executionContext.context

        val first = client.calendarService.applyExternalEvents(
            events = listOf(event(proxy, "copy~a"), event(proxy, "copy~b")),
            sources = listOf(EventSource.create(context, "copy~a", "BEGIN:VEVENT\r\nUID:a\r\nEND:VEVENT")),
            reminders = listOf(Reminder.create(context, "copy~a", 10)),
            overrides = listOf(
                EventOverride.create(
                    context = context,
                    eventId = "copy~a",
                    recurrenceId = "2026-07-08T09:00",
                    startLocal = "2026-07-08T11:00",
                    title = "Standup (late)",
                )
            ),
        )

        assertEquals(2L, (first as TResult.Success).value?.get(), "both events were new")
        assertEquals("copy", proxy.event("copy~a")?.calendarId?.get())
        assertEquals("Standup (late)", proxy.override("copy~a", "2026-07-08T09:00")?.title?.get())
        assertEquals(listOf(10L), proxy.executionContext.allReminders().map { it.minutesBefore.get() })

        val again = client.calendarService.applyExternalEvents(events = listOf(event(proxy, "copy~a")))
        assertEquals(0L, (again as TResult.Success).value?.get(), "a re-copy adds nothing new")
    }

    /** ⚠ A re-copy converges on the source's reminders instead of accumulating every copy's. */
    @Test
    fun reminders_are_set_for_the_events_in_the_batch_and_nobody_else() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("copy", "Work", 0x4285F4)
        service.saveReminder("copy~a", 30)          // the source no longer has this one
        service.saveReminder("someone-else", 15)    // not in the batch: untouchable
        val context = proxy.executionContext.context

        service.applyExternalEvents(
            events = listOf(event(proxy, "copy~a")),
            reminders = listOf(Reminder.create(context, "copy~a", 10)),
        )

        val byEvent = proxy.executionContext.allReminders()
            .groupBy({ it.eventId.get() }, { it.minutesBefore.get() })
        assertEquals(listOf(10L), byEvent["copy~a"])
        assertEquals(listOf(15L), byEvent["someone-else"])
    }

    /**
     * ⚠⚠ Diffed, not rewritten: re-writing an unchanged reminder would drop the user's dismissal,
     * because the content-derived key brings the row back identical but *un*-dismissed.
     */
    @Test
    fun an_unchanged_reminder_keeps_its_dismissal() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("copy", "Work", 0x4285F4)
        service.saveReminder("copy~a", 10)
        service.setReminderState(Reminder.idFor("copy~a", 10), "2026-07-01T09:00", dismissed = true)
        val context = proxy.executionContext.context

        service.applyExternalEvents(
            events = listOf(event(proxy, "copy~a")),
            reminders = listOf(Reminder.create(context, "copy~a", 10)),
        )

        assertEquals(1, proxy.executionContext.allReminderStates().size)
        assertEquals(
            listOf(Reminder.idFor("copy~a", 10)),
            proxy.executionContext.allReminders().map { it.reminderId.get() },
        )
    }

    @Test
    fun an_event_with_no_end_gets_the_same_implied_end_save_event_gives() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        service(proxy).applyExternalEvents(events = listOf(event(proxy, "copy~a", endLocal = "")))

        val end = assertNotNull(proxy.event("copy~a")).endLocal.get()
        assertTrue(end > "2026-07-01T09:00", "an end after the start, not a blank: $end")
    }

    /**
     * ⚠⚠ Overrides are *set* per batch event (CX-1b): an occurrence the source moved back must stop
     * showing as moved — nothing else ever deletes an override. Events outside the batch keep theirs.
     */
    @Test
    fun overrides_are_set_for_the_events_in_the_batch_and_nobody_else() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        val context = proxy.executionContext.context
        fun moved(eventId: String, day: Int) = EventOverride.create(
            context = context,
            eventId = eventId,
            recurrenceId = "2026-07-0${day}T09:00",
            startLocal = "2026-07-0${day}T11:00",
        )
        service.applyExternalEvents(
            events = listOf(event(proxy, "copy~a"), event(proxy, "copy~b")),
            overrides = listOf(moved("copy~a", 8), moved("copy~b", 8)),
        )

        // The source moved a's occurrence back; b is not in this batch at all.
        service.applyExternalEvents(events = listOf(event(proxy, "copy~a")), overrides = emptyList())

        assertNull(proxy.override("copy~a", "2026-07-08T09:00"), "moved back at the source")
        assertNotNull(proxy.override("copy~b", "2026-07-08T09:00"), "not in the batch: untouched")
    }

    @Test
    fun a_mirror_row_round_trips_and_deleting_it_keeps_the_events() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("mirror-1-cal", "Work", 0x4285F4)
        service.saveCalendarMirror(
            mirrorId = "mirror-1",
            calendarId = "mirror-1-cal",
            deviceId = "device-a",
            sourceKind = "ANDROID_PROVIDER",
            sourceKey = "com.google|me|work",
            sourceLabel = "Work · me",
            remindersEnabled = true,
        )
        service.saveCalendarMirrorStamp(mirrorId = "mirror-1", deviceId = "device-a", lastRefreshedEpochMs = 42L)
        service.saveCalendarMirrorStamp(mirrorId = "mirror-1", deviceId = "device-b", lastRefreshedEpochMs = 7L)
        service.applyExternalEvents(
            events = listOf(
                Event.create(
                    context = proxy.executionContext.context,
                    eventId = "mirror-1-cal~a",
                    calendarId = "mirror-1-cal",
                    title = "Standup",
                    startLocal = "2026-07-01T09:00",
                    endLocal = "2026-07-01T09:30",
                )
            )
        )

        val stored = proxy.executionContext.allMirrors().single()
        assertEquals("device-a", stored.deviceId.get())
        assertEquals(true, stored.remindersEnabled.get())
        assertEquals(
            mapOf("mirror-1@device-a" to 42L, "mirror-1@device-b" to 7L),
            proxy.executionContext.allMirrorStamps().associate { it.stampId.get() to it.lastRefreshedEpochMs.get() },
            "one stamp row per device, never a field of the mirror row (P2-a)",
        )

        service.deleteCalendarMirror("mirror-1")

        assertEquals(emptyList(), proxy.executionContext.allMirrors())
        assertEquals(emptyList(), proxy.executionContext.allMirrorStamps(), "the stamps go with the mirror")
        assertNotNull(proxy.event("mirror-1-cal~a"), "stopping the sync keeps the events")
    }

    /** ⚠ A mirror left pointing at a deleted calendar would re-create it on the next refresh. */
    @Test
    fun deleting_a_calendar_takes_its_mirror_with_it() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("mirror-1-cal", "Work", 0x4285F4)
        service.saveCalendarMirror(
            "mirror-1", "mirror-1-cal", "device-a", "ANDROID_PROVIDER", "k", "Work", false,
        )

        service.deleteCalendar("mirror-1-cal")

        assertEquals(emptyList(), proxy.executionContext.allMirrors())
    }

    private fun serializingClientOf(testProxy: TestProxy): DefaultTenshuClient {
        val wireExecutor = object : AsyncTaskExecutor {
            override fun execute(task: TList<Data>): TResult<Data> = error("wire test executes async only")

            override suspend fun executeAsync(task: TList<Data>): TResult<Data> {
                val requestBytes = testProxy.serializer.serialize(
                    task,
                    isTopLevel = true,
                    expectedType = Type.create(baseSchema = TList.Schema),
                )
                val responseBytes = testProxy.handleRequest(requestBytes)
                return testProxy.parser.parse(
                    data = responseBytes,
                    expectedType = Type.create(baseSchema = TResult.Schema),
                )
            }
        }
        return DefaultTenshuClient(
            TenshuEnvironment(
                taskExecutor = wireExecutor,
                schemaRegistry = testProxy.schemaRegistry,
                tContext = testProxy.tContext,
                requestChainFactory = { ctx -> LocalRequestChain(wireExecutor, ctx) },
            )
        )
    }
}
