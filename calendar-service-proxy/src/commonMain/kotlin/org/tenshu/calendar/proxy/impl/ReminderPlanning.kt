package org.tenshu.calendar.proxy.impl

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.Calendar
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.common.recurrence.OccurrenceResolver
import org.tenshu.calendar.common.recurrence.OverrideInput
import org.tenshu.calendar.common.recurrence.SeriesInput
import org.tenshu.calendar.common.reminder.DueReminder
import org.tenshu.calendar.common.reminder.ReminderInput
import org.tenshu.calendar.common.reminder.ReminderPlanner
import org.tenshu.calendar.common.reminder.ReminderStateInput
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext

/**
 * Reads the stored rows and asks `ReminderPlanner` what is due.
 *
 * ⭐ The proxy-side half of CA-5c: everything that touches storage, with every
 * *decision* left to the pure planner one layer down. The op above this does no
 * reading and the planner below does no I/O.
 */
internal fun ExecutionContext.planReminders(
    from: Instant,
    until: Instant,
    zoneId: String,
    limit: Int = ReminderPlanner.DEFAULT_LIMIT,
): List<DueReminder> {
    val reminders = allReminders().map {
        ReminderInput(
            reminderId = it.reminderId.get(),
            eventId = it.eventId.get(),
            minutesBefore = it.minutesBefore.get().toInt(),
        )
    }
    if (reminders.isEmpty()) return emptyList()

    val zone = runCatching { TimeZone.of(zoneId) }.getOrNull() ?: TimeZone.UTC

    // ⚠⚠ The occurrence window is **wider than the reminder window, by the
    // largest offset any reminder uses**. A reminder is `minutesBefore` ahead of
    // its occurrence, so an alert due today can belong to an event next week —
    // resolving occurrences only over the alert window would miss it entirely,
    // and the miss would be invisible (no error, just a reminder that never
    // arrives). A fixed margin would break the moment someone set "1 week
    // before"; deriving it from the data cannot.
    val margin = reminders.maxOf { it.minutesBefore }.minutes.absoluteValue() + 1.days
    val windowStart = (from - margin).civilDate(zone)
    val windowEnd = (until + margin).civilDate(zone)

    val occurrences = OccurrenceResolver.resolve(
        series = allEvents().map { event ->
            SeriesInput(
                eventId = event.eventId.get(),
                calendarId = event.calendarId.get(),
                title = event.title.get(),
                location = event.location.get(),
                notes = event.notes.get(),
                startLocal = event.startLocal.get(),
                endLocal = event.endLocal.get(),
                timeZoneId = event.timeZoneId.get(),
                allDay = event.allDay.get(),
                recurrenceRule = event.recurrenceRule.get(),
                exceptionDates = event.exceptionDates.get(),
            )
        },
        overrides = allEventOverrides().map { override ->
            OverrideInput(
                eventId = override.eventId.get(),
                recurrenceId = override.recurrenceId.get(),
                startLocal = override.startLocal?.get(),
                endLocal = override.endLocal?.get(),
                title = override.title?.get(),
                location = override.location?.get(),
                notes = override.notes?.get(),
            )
        },
        windowStart = windowStart,
        windowEnd = windowEnd,
    )

    return ReminderPlanner.plan(
        occurrences = occurrences,
        reminders = reminders,
        states = allReminderStates().map {
            ReminderStateInput(
                reminderId = it.reminderId.get(),
                recurrenceId = it.recurrenceId.get(),
                snoozedUntilEpochMs = it.snoozedUntilEpochMs.get(),
                dismissed = it.dismissed.get(),
            )
        },
        sensitiveCalendarIds = allCalendars()
            .filter { it.sensitive.get() }
            .mapTo(mutableSetOf()) { it.calendarId.get() },
        from = from,
        until = until,
        floatingZoneId = zoneId,
        limit = limit,
    )
}

/** ⚠ A negative offset is "after the start"; the margin cares about magnitude. */
private fun kotlin.time.Duration.absoluteValue(): kotlin.time.Duration =
    if (this < kotlin.time.Duration.ZERO) -this else this

private fun Instant.civilDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

internal fun ExecutionContext.allCalendars(): List<Calendar> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.CALENDARS),
    )
    return resolveList(arrayRef)?.filterIsInstance<Calendar>()?.toList().orEmpty()
}

internal fun ExecutionContext.allEventOverrides(): List<EventOverride> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.EVENT_OVERRIDES),
    )
    return resolveList(arrayRef)?.filterIsInstance<EventOverride>()?.toList().orEmpty()
}
