package org.tenshu.ingest.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.UpdateSourceStatus
import org.tenshu.ingest.proxy.IngestRunRegistry
import org.tenshu.core.number.TInteger

/**
 * Writes the live status fields of an existing [IngestSource], preserving its
 * descriptor + `enabled` state. A no-op if no row exists for `(sourceId,
 * deviceId)` — status without a registered descriptor is meaningless.
 *
 * `pendingCount` / `lastRunAtMillis` of `0` are treated as "unset" (stored
 * absent); a blank `lastError` clears the error.
 *
 * [runs], when wired, also records the transition in the device-local
 * [org.tenshu.ingest.proxy.IngestRunRegistry] — this op is the only *local* writer of a source's
 * status, so it is the one place that can tell a run this device is driving from a row that
 * arrived over sync. See that class for why the row itself cannot carry the signal.
 */
internal class UpdateSourceStatusImplementation(
    private val runs: IngestRunRegistry? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sourceId: TString = params.read(UpdateSourceStatus.Params.SOURCE_ID)
        val deviceId: TString = params.read(UpdateSourceStatus.Params.DEVICE_ID)
        val status: TString = params.read(UpdateSourceStatus.Params.STATUS)
        val pendingCount: TInteger = params.read(UpdateSourceStatus.Params.PENDING_COUNT)
        val lastRunAt: TInteger = params.read(UpdateSourceStatus.Params.LAST_RUN_AT)
        val lastError: TString = params.read(UpdateSourceStatus.Params.LAST_ERROR)

        val key = IngestSource.keyOf(sourceId.get(), deviceId.get())
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = IngestSource.ID,
            keyValue = key
        )
        val existing = executionContext.database.getData(dataId) as? IngestSource ?: return null as T

        val merged = IngestSource.create(
            context = executionContext.context,
            sourceId = existing.sourceId.get(),
            appId = existing.appId.get(),
            deviceId = existing.deviceId.get(),
            displayName = existing.displayName.get(),
            sourceKind = existing.sourceKind.get(),
            enabled = existing.enabled.get(),
            watchedPaths = existing.watchedPaths?.get(),
            lastRunAt = lastRunAt.get().takeIf { it > 0L },
            lastStatus = status.get(),
            pendingCount = pendingCount.get().takeIf { it > 0L },
            lastError = lastError.get().ifBlank { null },
        )
        executionContext.saveData(merged)
        // After the write, so a failed save cannot leave the registry claiming a run that was
        // never recorded.
        runs?.onStatusReported(key = key, status = status.get())

        return null as T
    }
}
