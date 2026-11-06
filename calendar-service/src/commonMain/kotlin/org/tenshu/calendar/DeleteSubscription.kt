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
 * Deletes a subscription **and the calendar it filled** (CA-7).
 *
 * ⚠⚠ The cascade is deliberate and is the opposite of `DeleteCalendar`'s: there,
 * deleting a calendar takes its events; here, deleting the *subscription* takes
 * the whole calendar, because every event in it came from the feed and none of
 * them is the user's. Leaving a frozen copy behind would be a calendar that looks
 * live and silently never updates again.
 *
 * ⚠ Pausing is the other option, and it is a different op: `SaveSubscription`
 * with `enabled = false` stops the egress and keeps the events.
 */
suspend fun CalendarService.deleteSubscription(subscriptionId: String): TResult<Data?> =
    findOperation<DeleteSubscription>(DeleteSubscription.ID)
        .deleteSubscription(subscriptionId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteSubscription : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteSubscription(subscriptionId: String): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/deleteSubscription"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = { string(id = Params.SUBSCRIPTION_ID) },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val SUBSCRIPTION_ID = "$ID/subscriptionId"
    }
}
