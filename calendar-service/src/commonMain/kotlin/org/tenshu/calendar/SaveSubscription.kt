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
 * Creates or updates a [CalendarSubscription] (CA-7).
 *
 * ⚠ Upsert on a URL-derived key, so subscribing to a feed twice converges rather
 * than fetching it twice forever — the `SaveReminder` shape.
 *
 * ⚠ [lastFetchedEpochMs] and [etag] are optional because the two callers want
 * different halves: the user adding a feed supplies neither, and the refresher
 * supplies both without wanting to restate the rest.
 */
suspend fun CalendarService.saveSubscription(
    subscriptionId: String,
    calendarId: String,
    url: String,
    enabled: Boolean = true,
    lastFetchedEpochMs: Long? = null,
    etag: String? = null,
): TResult<Data?> =
    findOperation<SaveSubscription>(SaveSubscription.ID)
        .saveSubscription(subscriptionId, calendarId, url, enabled, lastFetchedEpochMs, etag)
        .asTaskInteractor<Data?>()
        .execute()

interface SaveSubscription : Operation {

    override val schema: OperationSchema get() = Schema

    @Suppress("LongParameterList")
    fun saveSubscription(
        subscriptionId: String,
        calendarId: String,
        url: String,
        enabled: Boolean,
        lastFetchedEpochMs: Long?,
        etag: String?,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/saveSubscription"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            params = {
                string(id = Params.SUBSCRIPTION_ID)
                string(id = Params.CALENDAR_ID)
                string(id = Params.URL)
                boolean(id = Params.ENABLED)
                int(id = Params.LAST_FETCHED_EPOCH_MS, isOptional = true)
                string(id = Params.ETAG, isOptional = true)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val SUBSCRIPTION_ID = "$ID/subscriptionId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val URL = "$ID/url"
        const val ENABLED = "$ID/enabled"
        const val LAST_FETCHED_EPOCH_MS = "$ID/lastFetchedEpochMs"
        const val ETAG = "$ID/etag"
    }
}
