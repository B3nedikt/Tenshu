package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.DeleteCalendar
import org.tenshu.calendar.DeleteSubscription
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.get
import org.tenshu.core.toTString

/**
 * Deletes a subscription **and the calendar it filled**.
 *
 * ⚠⚠ Delegated to `DeleteCalendar` rather than re-implemented, so the cascade to
 * events, overrides and sidecars stays in one place. ⚠ The subscription row goes
 * **first**: if the calendar delete fails half way, what is left is an ordinary
 * calendar with no feed behind it — visibly the user's — rather than a live
 * subscription pointing at a calendar that no longer exists.
 */
internal class DeleteSubscriptionImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val subscriptionId: TString = params.read(DeleteSubscription.Params.SUBSCRIPTION_ID)
        val subscription = executionContext.allSubscriptions()
            .firstOrNull { it.subscriptionId.get() == subscriptionId.get() }

        executionContext.deleteSubscriptionRow(subscriptionId.get(), tombstoneEmitter)

        val calendarId = subscription?.calendarId?.get() ?: return null as T
        (implementationFactory(DeleteCalendar.ID) as? SyncOperationImplementation)
            ?.execute<Data?>(
                executionContext = executionContext,
                operationContext = null,
                implementationFactory = implementationFactory,
                caller = null,
                params = mapOf(
                    DeleteCalendar.Params.CALENDAR_ID to
                        calendarId.toTString(executionContext.context),
                ),
            )
        return null as T
    }
}
