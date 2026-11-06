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
import org.tenshu.sync.GetSyncLogBefore
import org.tenshu.sync.RelayRouteUnsupportedError
import org.tenshu.sync.SignedHlcTip
import org.tenshu.sync.common.SyncRelay

/**
 * Proxy-side backfill page (`sync-device-catch-up.md` C2). Mirrors `GetSyncLogImplementation`,
 * including its typed [RelayRouteUnsupportedError] — a relay that predates `/sync/log` predates
 * `before=` too, and the scheduler's single "merged lane unavailable" latch covers both.
 */
internal class GetSyncLogBeforeImplementation(
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
        val beforeSeq: TInteger = params.read(GetSyncLogBefore.Params.BEFORE_SEQ)
        val tip: SignedHlcTip = params.read(GetSyncLogBefore.Params.TIP)

        return when (val result = relay.getSyncLogBefore(beforeSeq.get(), tip)) {
            is SyncRelay.SyncLogFetch.Found -> result.response as T

            SyncRelay.SyncLogFetch.RelayUnsupported -> throw TenshuException(
                RelayRouteUnsupportedError.create(executionContext.context, "/sync/log?before=")
            )

            is SyncRelay.SyncLogFetch.Error -> throw TenshuException(
                GenericError.create(executionContext.context, result.message)
            )
        }
    }
}
