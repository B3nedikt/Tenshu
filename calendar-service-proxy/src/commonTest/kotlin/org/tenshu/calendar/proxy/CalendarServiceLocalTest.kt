package org.tenshu.calendar.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.deleteCalendar
import org.tenshu.calendar.deleteEvent
import org.tenshu.calendar.deleteOccurrence
import org.tenshu.calendar.saveCalendar
import org.tenshu.calendar.saveEvent
import org.tenshu.calendar.saveEventOverride
import org.tenshu.calendar.test.createCalendarProxy
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.search
import org.tenshu.core.common.TestProxy
import org.tenshu.core.equalTo
import org.tenshu.core.get
import org.tenshu.core.resolve
import org.tenshu.calendar.test.environmentFor
import org.tenshu.calendar.test.calendarRow
import org.tenshu.calendar.test.event
import org.tenshu.calendar.test.override
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.set

/**
 * Local single-proxy round-trip over the CA-1 operation surface: each op writes
 * through the SDK chain, the generic relation maintainer files the row, and the
 * chain DSL reads it back on the same client.
 *
 * ⚠ Deliberately not a sync test — the cross-device behaviour is the platform's,
 * already proven elsewhere. What is unproven and Calendar-specific is the op
 * wiring: schema registration, RO factories, param optionality and the §6.4
 * default resolution.
 */
class CalendarServiceLocalTest {

    private suspend fun service(proxy: TestProxy): CalendarService =
        environmentFor(proxy).getService(CalendarService.ID)

    private suspend fun preferences(proxy: TestProxy): PreferenceService =
        environmentFor(proxy).getService(PreferenceService.ID)

    // --- calendars ----------------------------------------------------------

    @Test
    fun a_saved_calendar_is_readable_through_the_chain() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)

        val saved = service.saveCalendar("work", "Work", 0x4285F4)
        assertTrue(saved is TResult.Success<*>, "saveCalendar failed: $saved")

        val found = service.calendars
            .search<Calendar> { calendarId equalTo "work" }
            .resolve()
        assertTrue(found is TResult.Success<*>, "search failed: $found")
        assertEquals("Work", proxy.calendarRow("work")?.name?.get())
    }

    // --- the §6.4 headless defaults, which are the point of CA-1 -------------

    /**
     * ⭐⭐ The Mia case: title + start only, everything else resolved by the
     * service. This is what makes the op usable from an assistant that heard
     * *"add lunch with Anna next Tuesday at one"* and nothing more.
     */
    @Test
    fun an_event_with_only_a_title_and_a_start_resolves_its_calendar_and_end() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("personal", "Personal", 0)
        preferences(proxy).set(CalendarPreferences.DefaultCalendarId, "personal")

        val saved = service.saveEvent(
            eventId = "e1",
            title = "Lunch with Anna",
            startLocal = "2026-08-11T13:00",
        )
        assertTrue(saved is TResult.Success<*>, "saveEvent failed: $saved")

        val event = proxy.event("e1")
        assertNotNull(event, "event was not written")
        assertEquals("personal", event.calendarId.get(), "calendar came from the default")
        assertEquals("2026-08-11T14:00", event.endLocal.get(), "end came from the 60-minute default")
    }

    /** The duration default is a preference, not a constant. */
    @Test
    fun the_default_duration_is_read_from_its_preference() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("personal", "Personal", 0)
        preferences(proxy).set(CalendarPreferences.DefaultCalendarId, "personal")
        preferences(proxy).set(CalendarPreferences.DefaultEventDurationMinutes, 30L)

        service.saveEvent(eventId = "e2", title = "Standup", startLocal = "2026-08-11T09:00")

        assertEquals("2026-08-11T09:30", proxy.event("e2")?.endLocal?.get())
    }

    /**
     * ⚠⚠ An all-day event with no end is **one day**, and must not pick up the
     * duration preference — "an all-day event lasting 30 minutes" is not a thing.
     */
    @Test
    fun an_all_day_event_ignores_the_duration_default_and_lasts_a_day() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("personal", "Personal", 0)
        preferences(proxy).set(CalendarPreferences.DefaultCalendarId, "personal")
        preferences(proxy).set(CalendarPreferences.DefaultEventDurationMinutes, 30L)

        service.saveEvent(
            eventId = "e3",
            title = "Public holiday",
            startLocal = "2026-08-15",
            allDay = true,
        )

        assertEquals("2026-08-16T00:00", proxy.event("e3")?.endLocal?.get())
    }

    /**
     * ⚠ With no default set the op **fails loudly**. Guessing "the first
     * calendar" would depend on store iteration order, so the same utterance
     * could file events differently on two devices with nothing to explain why.
     */
    @Test
    fun saving_without_a_calendar_or_a_default_fails_rather_than_guessing() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)   // exists, but is not the default

        val result = service.saveEvent(eventId = "e4", title = "Orphan", startLocal = "2026-08-11T13:00")

        assertTrue(result is TResult.Failure<*>, "expected a typed failure, got $result")
        assertNull(proxy.event("e4"), "nothing may be written when the calendar is unresolved")
    }

    /** An explicit calendar overrides the default. */
    @Test
    fun an_explicit_calendar_wins_over_the_default() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("personal", "Personal", 0)
        service.saveCalendar("work", "Work", 0)
        preferences(proxy).set(CalendarPreferences.DefaultCalendarId, "personal")

        service.saveEvent(
            eventId = "e5",
            title = "Review",
            startLocal = "2026-08-11T10:00",
            calendarId = "work",
        )

        assertEquals("work", proxy.event("e5")?.calendarId?.get())
    }

    // --- occurrences --------------------------------------------------------

    /**
     * ⚠⚠ Skipping one occurrence must not delete the series — the distinction
     * `DeleteOccurrence` exists to preserve.
     */
    @Test
    fun deleting_an_occurrence_excepts_a_date_and_leaves_the_series_intact() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        service.deleteOccurrence("standup", "2026-08-10T09:00")

        val event = proxy.event("standup")
        assertNotNull(event, "the series must survive skipping one occurrence")
        assertEquals("2026-08-10", event.exceptionDates.get())
        assertEquals("FREQ=WEEKLY;BYDAY=MO", event.recurrenceRule.get())
    }

    /** ⚠ Idempotent: skipping twice must not double-enter the date. */
    @Test
    fun skipping_the_same_occurrence_twice_is_idempotent() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        service.deleteOccurrence("standup", "2026-08-10T09:00")
        service.deleteOccurrence("standup", "2026-08-10T09:00")

        assertEquals("2026-08-10", proxy.event("standup")?.exceptionDates?.get())
    }

    /**
     * ⚠⚠ `null` means inherit, `""` means override-with-empty. The
     * implementation must not coerce absent params, or a caller could never
     * clear a field and every unmentioned field would be blanked.
     */
    @Test
    fun an_override_leaves_unmentioned_fields_absent_rather_than_blank() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )

        service.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-08-10T09:00",
            startLocal = "2026-08-10T11:00",
        )

        val override = proxy.override("standup", "2026-08-10T09:00")
        assertNotNull(override)
        assertEquals("2026-08-10T11:00", override.startLocal?.get())
        assertNull(override.title, "an unmentioned field must stay absent, not become \"\"")
        assertNull(override.location)
    }

    /** An explicit empty string is a real override, distinct from absence. */
    @Test
    fun an_explicitly_empty_field_is_stored_as_an_override_not_as_absence() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
        )

        service.saveEventOverride(
            eventId = "standup",
            recurrenceId = "2026-08-10T09:00",
            location = "",
        )

        val override = proxy.override("standup", "2026-08-10T09:00")
        assertEquals("", override?.location?.get(), "\"\" must survive as an override")
        assertNull(override?.title)
    }

    /** Skipping an occurrence drops its override — it describes something gone. */
    @Test
    fun skipping_an_occurrence_drops_its_override() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO",
        )
        service.saveEventOverride("standup", "2026-08-10T09:00", startLocal = "2026-08-10T11:00")

        service.deleteOccurrence("standup", "2026-08-10T09:00")

        assertNull(proxy.override("standup", "2026-08-10T09:00"))
    }

    // --- cascades -----------------------------------------------------------

    /** ⚠ Deleting an event takes its overrides with it — no orphans. */
    @Test
    fun deleting_an_event_cascades_to_its_overrides() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveEvent(
            eventId = "standup",
            title = "Standup",
            startLocal = "2026-08-03T09:00",
            calendarId = "work",
        )
        service.saveEventOverride("standup", "2026-08-10T09:00", title = "Moved")

        service.deleteEvent("standup")

        assertNull(proxy.event("standup"))
        assertNull(proxy.override("standup", "2026-08-10T09:00"), "an orphaned override survived")
    }

    /**
     * ⚠⚠ Deleting a calendar takes its events with it. `Event.calendarId` is an
     * id, not a reference, so nothing else would ever collect them.
     */
    @Test
    fun deleting_a_calendar_cascades_to_its_events() = runTest {
        val proxy = createCalendarProxy(backgroundScope)
        val service = service(proxy)
        service.saveCalendar("work", "Work", 0)
        service.saveCalendar("personal", "Personal", 0)
        service.saveEvent("in-work", "Review", "2026-08-11T10:00", calendarId = "work")
        service.saveEvent("in-personal", "Lunch", "2026-08-11T13:00", calendarId = "personal")

        service.deleteCalendar("work")

        assertNull(proxy.event("in-work"), "an event outlived its calendar")
        assertNotNull(proxy.event("in-personal"), "the other calendar's events must survive")
    }
}
