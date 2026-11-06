package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records that **one occurrence** of a recurring [Event] differs from what the
 * rule produces — "this Tuesday's standup is at 11:00, in room B".
 *
 * ⚠⚠ **`null` means "inherit", `""` means "override with empty".** Every field
 * here is optional, and the two absences are genuinely different: passing
 * `null` for [title] keeps the series' title, while passing `""` gives this one
 * occurrence no title at all. A caller that coalesces them — the obvious
 * `?: ""` — silently loses the second and can never clear a field.
 *
 * ⚠ [recurrenceId] is the occurrence's **original** start, never its new one:
 * a moved occurrence still has to say which occurrence it replaced.
 */
@Suppress("LongParameterList")
suspend fun CalendarService.saveEventOverride(
    eventId: String,
    recurrenceId: String,
    startLocal: String? = null,
    endLocal: String? = null,
    title: String? = null,
    location: String? = null,
    notes: String? = null,
): TResult<Data?> =
    findOperation<SaveEventOverride>(SaveEventOverride.ID)
        .saveEventOverride(eventId, recurrenceId, startLocal, endLocal, title, location, notes)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveEventOverride : Operation {

    override val schema: OperationSchema get() = Schema

    @Suppress("LongParameterList")
    fun saveEventOverride(
        eventId: String,
        recurrenceId: String,
        startLocal: String?,
        endLocal: String?,
        title: String?,
        location: String?,
        notes: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveEventOverride"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.EVENT_ID)
                string(id = Params.RECURRENCE_ID)
                string(id = Params.START_LOCAL, isOptional = true)
                string(id = Params.END_LOCAL, isOptional = true)
                string(id = Params.TITLE, isOptional = true)
                string(id = Params.LOCATION, isOptional = true)
                string(id = Params.NOTES, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENT_ID = "$ID/eventId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
        const val START_LOCAL = "$ID/startLocal"
        const val END_LOCAL = "$ID/endLocal"
        const val TITLE = "$ID/title"
        const val LOCATION = "$ID/location"
        const val NOTES = "$ID/notes"
    }
}
