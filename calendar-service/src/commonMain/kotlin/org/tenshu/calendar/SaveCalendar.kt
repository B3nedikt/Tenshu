package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Creates or replaces a [Calendar] row, keyed on [calendarId].
 *
 * ⚠ Upsert rather than a create/update pair, following `SaveRoutine` and
 * `WriteSetting`: the row's conflict strategy is already last-writer-wins, so a
 * separate "update" op would carry the same semantics under a second name and
 * give the caller a distinction the storage layer does not make.
 */
suspend fun CalendarService.saveCalendar(
    calendarId: String,
    name: String,
    colorArgb: Int,
    sensitive: Boolean = false,
): TResult<Data?> =
    findOperation<SaveCalendar>(SaveCalendar.ID)
        .saveCalendar(calendarId, name, colorArgb, sensitive)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveCalendar : Operation {

    override val schema: OperationSchema get() = Schema

    fun saveCalendar(
        calendarId: String,
        name: String,
        colorArgb: Int,
        sensitive: Boolean,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveCalendar"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.CALENDAR_ID)
                string(id = Params.NAME)
                int(id = Params.COLOR_ARGB)
                boolean(id = Params.SENSITIVE)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val CALENDAR_ID = "$ID/calendarId"
        const val NAME = "$ID/name"
        const val COLOR_ARGB = "$ID/colorArgb"
        const val SENSITIVE = "$ID/sensitive"
    }
}
