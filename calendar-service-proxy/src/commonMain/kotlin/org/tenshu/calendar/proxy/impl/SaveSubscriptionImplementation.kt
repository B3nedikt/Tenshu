package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.CalendarSubscription
import org.tenshu.calendar.SaveSubscription
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.get
import org.tenshu.core.number.TInteger

/**
 * Upserts a [CalendarSubscription].
 *
 * ⚠⚠ **An omitted `lastFetchedEpochMs` or `etag` keeps what the row already
 * holds**, rather than resetting it. The two callers want opposite halves — the
 * user editing a URL or pausing a feed supplies neither, the refresher supplies
 * both — and blanking on every user edit would make the next refresh re-download
 * a calendar that had not changed.
 */
internal class SaveSubscriptionImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val subscriptionId: TString = params.read(SaveSubscription.Params.SUBSCRIPTION_ID)
        val calendarId: TString = params.read(SaveSubscription.Params.CALENDAR_ID)
        val url: TString = params.read(SaveSubscription.Params.URL)
        val enabled: TBoolean = params.read(SaveSubscription.Params.ENABLED)

        val existing = executionContext.allSubscriptions()
            .firstOrNull { it.subscriptionId.get() == subscriptionId.get() }
        val lastFetched = (params?.get(SaveSubscription.Params.LAST_FETCHED_EPOCH_MS) as? TInteger)
            ?.get() ?: existing?.lastFetchedEpochMs?.get() ?: 0L
        val etag = (params?.get(SaveSubscription.Params.ETAG) as? TString)
            ?.get() ?: existing?.etag?.get().orEmpty()

        executionContext.saveData(
            CalendarSubscription.create(
                context = executionContext.context,
                subscriptionId = subscriptionId.get(),
                calendarId = calendarId.get(),
                url = url.get(),
                enabled = enabled.get(),
                lastFetchedEpochMs = lastFetched,
                etag = etag,
            )
        )
        return null as T
    }
}
