package org.tenshu.calendar.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Re-fetches every enabled subscription (CA-7).
 *
 * ⚠ Async, unlike every other calendar op: this one waits on the network, and a
 * synchronous implementation would block the proxy's dispatch for as long as a
 * slow publisher takes to answer.
 */
internal class RefreshSubscriptionsImplementation(
    private val refresher: SubscriptionRefresher,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        refresher.refreshAll(executionContext, implementationFactory)
        return null as T
    }
}
