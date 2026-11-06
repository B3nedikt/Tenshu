package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.CalendarPreferences
import org.tenshu.calendar.Event
import org.tenshu.calendar.SaveEvent
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read

/**
 * Upserts an [Event], resolving the §6.4 headless defaults the caller omitted.
 *
 * ⭐ This is what makes the op usable from Mia: *"add lunch with Anna next
 * Tuesday at one"* supplies a title and a start, and everything else is
 * resolved here — in the one layer both the UI and the assistant share, so the
 * two cannot drift apart.
 */
internal class SaveEventImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val eventId: TString = params.read(SaveEvent.Params.EVENT_ID)
        val title: TString = params.read(SaveEvent.Params.TITLE)
        val startLocal: TString = params.read(SaveEvent.Params.START_LOCAL)
        val allDay: TBoolean = params.read(SaveEvent.Params.ALL_DAY)

        val calendarId = params.optional(SaveEvent.Params.CALENDAR_ID)
            ?: CalendarDefaults.defaultCalendarId(executionContext).ifBlank {
                // ⚠ Fails loudly rather than guessing. Picking "the first
                // calendar" would depend on store iteration order, so the same
                // utterance could file events differently on two devices with
                // nothing to show the user why. CA-2 seeds this preference.
                throw TenshuException(
                    GenericError.create(
                        executionContext.context,
                        "No calendar given and no default set " +
                            "(${CalendarPreferences.DefaultCalendarId.id})",
                    )
                )
            }

        val endLocal = params.optional(SaveEvent.Params.END_LOCAL)
            ?: CalendarDefaults.impliedEnd(
                startLocal = startLocal.get(),
                allDay = allDay.get(),
                durationMinutes = CalendarDefaults.defaultDurationMinutes(executionContext),
            )
            ?: throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Unparseable start: ${startLocal.get()}",
                )
            )

        executionContext.saveData(
            Event.create(
                context = executionContext.context,
                eventId = eventId.get(),
                calendarId = calendarId,
                title = title.get(),
                startLocal = startLocal.get(),
                endLocal = endLocal,
                // ⚠ "" is *floating*, a real third shape — not a missing value.
                timeZoneId = params.optional(SaveEvent.Params.TIME_ZONE_ID).orEmpty(),
                allDay = allDay.get(),
                location = params.optional(SaveEvent.Params.LOCATION).orEmpty(),
                notes = params.optional(SaveEvent.Params.NOTES).orEmpty(),
                recurrenceRule = params.optional(SaveEvent.Params.RECURRENCE_RULE).orEmpty(),
                exceptionDates = params.optional(SaveEvent.Params.EXCEPTION_DATES).orEmpty(),
            )
        )
        return null as T
    }
}

/** An optional string param, or `null` when the caller omitted it. */
internal fun Map<String, Data>?.optional(id: String): String? =
    (this?.get(id) as? TString)?.get()
