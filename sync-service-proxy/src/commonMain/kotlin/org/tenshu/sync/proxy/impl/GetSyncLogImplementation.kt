package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger
import org.tenshu.sync.GetSyncLog
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.SyncRelay

/**
 * Proxy-side implementation of the merged lane (`sync-lane-cursor-split.md` L3).
 *
 * ⚠ A failure is rethrown as a [TenshuException] carrying the relay's **typed** error
 * unchanged — in particular [org.tenshu.sync.RelayRouteUnsupportedError], which
 * `SyncScheduler` must be able to tell apart from a network failure to decide between
 * "fall back to the two lanes permanently" and "retry next tick". Collapsing it to a
 * generic error here would destroy exactly the distinction the type exists to carry.
 */
internal class GetSyncLogImplementation(
    private val relay: SyncRelay
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sinceTimestamp: TInteger = params.read(GetSyncLog.Params.SINCE_TIMESTAMP)
        val tip: SignedHlcTip = params.read(GetSyncLog.Params.TIP)

        return when (val result = relay.getSyncLog(sinceTimestamp.get(), tip)) {
            is SyncRelay.SyncLogFetch.Found -> result.response as T

            // Typed, so the caller can tell "your relay is older than you" from "the network
            // is down" — see the class KDoc.
            SyncRelay.SyncLogFetch.RelayUnsupported -> throw TenshuException(
                RelayRouteUnsupportedError.create(executionContext.context, "/sync/log")
            )

            is SyncRelay.SyncLogFetch.Error -> throw TenshuException(
                GenericError.create(executionContext.context, result.message)
            )
        }
    }
}
