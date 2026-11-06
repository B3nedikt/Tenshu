package org.tenshu.ingest.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.ingest.IngestSource
import org.tenshu.core.ingest.SetSourceEnabled

/**
 * The central kill switch — flips an existing [IngestSource]'s `enabled` state,
 * preserving descriptor + status. A no-op if no row exists for `(sourceId,
 * deviceId)`. The owning app honours the new value on its next self-triggered
 * tick / watch event (§5) — there is no push.
 */
internal class SetSourceEnabledImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sourceId: TString = params.read(SetSourceEnabled.Params.SOURCE_ID)
        val deviceId: TString = params.read(SetSourceEnabled.Params.DEVICE_ID)
        val enabled: TBoolean = params.read(SetSourceEnabled.Params.ENABLED)

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
            enabled = enabled.get(),
            watchedPaths = existing.watchedPaths?.get(),
            lastRunAt = existing.lastRunAt?.get(),
            lastStatus = existing.lastStatus?.get(),
            pendingCount = existing.pendingCount?.get(),
            lastError = existing.lastError?.get(),
        )
        executionContext.saveData(merged)

        return null as T
    }
}
