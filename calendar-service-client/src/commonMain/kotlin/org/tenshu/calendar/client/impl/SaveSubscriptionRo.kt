package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SaveSubscription
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.number.toTInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

internal class SaveSubscriptionRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SaveSubscription {

    override fun saveSubscription(
        subscriptionId: String,
        calendarId: String,
        url: String,
        enabled: Boolean,
        lastFetchedEpochMs: Long?,
        etag: String?,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SaveSubscription.Params.SUBSCRIPTION_ID, subscriptionId.toTString(context))
            put(SaveSubscription.Params.CALENDAR_ID, calendarId.toTString(context))
            put(SaveSubscription.Params.URL, url.toTString(context))
            put(SaveSubscription.Params.ENABLED, enabled.toTBoolean(context))
            // ⚠ Omitted when null rather than sent as a zero: absent means "leave
            // whatever the row already records", which is what a caller editing
            // only the URL or the pause flag means.
            lastFetchedEpochMs?.let {
                put(SaveSubscription.Params.LAST_FETCHED_EPOCH_MS, it.toTInteger(context))
            }
            etag?.let { put(SaveSubscription.Params.ETAG, it.toTString(context)) }
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
