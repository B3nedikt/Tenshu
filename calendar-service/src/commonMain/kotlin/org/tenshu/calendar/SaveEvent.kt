package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Creates or replaces an [Event], keyed on [eventId].
 *
 * ## ⭐⭐ Headless completeness (`calendar-app.md` §6.4, Q14)
 *
 * **Only [eventId], [title] and [startLocal] are required.** Everything else is
 * optional and resolved by the *implementation*, because this op has two callers
 * with very different amounts of information:
 *
 * - **A UI**, which knows every field because a form collected them.
 * - **Mia** (§6), which knows only what was said. *"Add lunch with Anna next
 *   Tuesday at one"* carries a title and a start and nothing else — no calendar,
 *   no end time.
 *
 * ⚠⚠ An op that required them would be unusable from the assistant, and pushing
 * the defaults into the UI layer would mean the assistant silently got
 * *different* ones. The service is the only layer both callers share, so it owns
 * them. Resolution:
 *
 * | Absent | Resolved to |
 * |---|---|
 * | [calendarId] | `CalendarPreferences.DefaultCalendarId`; ⚠ **fails loudly if unset** rather than guessing |
 * | [endLocal] | start + `DefaultEventDurationMinutes` — or **+1 day** when [allDay], per RFC 5545 |
 * | [timeZoneId] | `""`, i.e. *floating*. ⚠ A real semantic value, not a missing one |
 * | the rest | empty / `false` |
 *
 * ⚠ Designed in at CA-1 rather than added later on purpose: introducing a
 * default into an op that has already shipped changes behaviour for every
 * existing caller, silently.
 */
@Suppress("LongParameterList")
suspend fun CalendarService.saveEvent(
    eventId: String,
    title: String,
    startLocal: String,
    endLocal: String? = null,
    calendarId: String? = null,
    timeZoneId: String? = null,
    allDay: Boolean = false,
    location: String? = null,
    notes: String? = null,
    recurrenceRule: String? = null,
    exceptionDates: String? = null,
): TResult<Data?> =
    findOperation<SaveEvent>(SaveEvent.ID)
        .saveEvent(
            eventId = eventId,
            title = title,
            startLocal = startLocal,
            endLocal = endLocal,
            calendarId = calendarId,
            timeZoneId = timeZoneId,
            allDay = allDay,
            location = location,
            notes = notes,
            recurrenceRule = recurrenceRule,
            exceptionDates = exceptionDates,
        )
        .asTaskInteractor<Data?>()
        .execute()

interface SaveEvent : Operation {

    override val schema: OperationSchema get() = Schema

    @Suppress("LongParameterList")
    fun saveEvent(
        eventId: String,
        title: String,
        startLocal: String,
        endLocal: String?,
        calendarId: String?,
        timeZoneId: String?,
        allDay: Boolean,
        location: String?,
        notes: String?,
        recurrenceRule: String?,
        exceptionDates: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveEvent"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.EVENT_ID)
                string(id = Params.TITLE)
                string(id = Params.START_LOCAL)
                string(id = Params.END_LOCAL, isOptional = true)
                string(id = Params.CALENDAR_ID, isOptional = true)
                string(id = Params.TIME_ZONE_ID, isOptional = true)
                boolean(id = Params.ALL_DAY)
                string(id = Params.LOCATION, isOptional = true)
                string(id = Params.NOTES, isOptional = true)
                string(id = Params.RECURRENCE_RULE, isOptional = true)
                string(id = Params.EXCEPTION_DATES, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
        const val TITLE = "$ID/title"
        const val START_LOCAL = "$ID/startLocal"
        const val END_LOCAL = "$ID/endLocal"
        const val CALENDAR_ID = "$ID/calendarId"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
        const val ALL_DAY = "$ID/allDay"
        const val LOCATION = "$ID/location"
        const val NOTES = "$ID/notes"
        const val RECURRENCE_RULE = "$ID/recurrenceRule"
        const val EXCEPTION_DATES = "$ID/exceptionDates"
    }
}
