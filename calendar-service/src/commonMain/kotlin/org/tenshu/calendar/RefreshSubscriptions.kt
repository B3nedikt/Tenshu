package org.tenshu.calendar

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.HeadlessSafe
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Re-fetches every enabled [CalendarSubscription] (CA-7).
 *
 * ⚠⚠ **Parameter-less**, the `DeliverDueReminders` shape and for the same
 * reason: a `ScheduleItem` carries an `opId` and nothing else, so the op has to
 * find its own work when it lands.
 *
 * ⚠ [HeadlessSafe] but deliberately **not** `Punctual`. A holiday feed that
 * refreshes an hour late has not failed — and an exact alarm for it would spend
 * the user's `SCHEDULE_EXACT_ALARM` grant, which reminders actually need, on
 * something that does not. Batched maintenance is the honest class.
 *
 * ⚠ Also invoked directly by a *Refresh now* action, so a user who does not want
 * to wait does not have to.
 */
suspend fun CalendarService.refreshSubscriptions(): TResult<Data?> =
    findOperation<RefreshSubscriptions>(RefreshSubscriptions.ID)
        .refreshSubscriptions()
        .asTaskInteractor<Data?>()
        .execute()

interface RefreshSubscriptions : Operation {

    override val schema: OperationSchema get() = Schema

    fun refreshSubscriptions(): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/refreshSubscriptions"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            traits = listOf(
                IoOperation.Schema,
                TerminalOperation.Schema,
                HeadlessSafe.Schema,
            ),
        )
    }
}
