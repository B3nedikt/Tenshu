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
 * Stops keeping a calendar in sync — and **keeps its events** (`calendar-external-sources.md`
 * CX-1b).
 *
 * ⚠⚠ The opposite cascade from `DeleteSubscription`, deliberately. A feed is someone else's
 * public calendar, and a frozen copy of it would look live and silently never update. A mirror
 * is the user's *own* calendar from another app; stopping the sync and keeping what is already
 * here is the useful answer to "I'm moving off Google". The calendar becomes ordinary and
 * editable. Deleting the *calendar* is what removes both.
 */
suspend fun CalendarService.deleteCalendarMirror(mirrorId: String): TResult<Data?> =
    findOperation<DeleteCalendarMirror>(DeleteCalendarMirror.ID)
        .deleteCalendarMirror(mirrorId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteCalendarMirror : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteCalendarMirror(mirrorId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteCalendarMirror"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = { string(id = Params.MIRROR_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val MIRROR_ID = "$ID/mirrorId"
    }
}
