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
import org.tenshu.core.ingest.RegisterSource

/**
 * Idempotent upsert of an [IngestSource] descriptor keyed by
 * `(sourceId, deviceId)`. A re-register refreshes the descriptor (display name,
 * watched paths, app, kind) but **preserves** the existing row's `enabled` state
 * and live status, so an app re-registering on every launch never clobbers a
 * central kill-switch toggle or in-flight status. A brand-new source defaults
 * `enabled = true`, `lastStatus = IDLE`.
 *
 * `saveData` fires `SyncObserver` (the row is `Syncable(Eager)` → outbound block)
 * and the generic `RelationMaintainer` (→ adds to `IngestService.Relations.SOURCES`).
 */
internal class RegisterSourceImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val sourceId: TString = params.read(RegisterSource.Params.SOURCE_ID)
        val appId: TString = params.read(RegisterSource.Params.APP_ID)
        val deviceId: TString = params.read(RegisterSource.Params.DEVICE_ID)
        val displayName: TString = params.read(RegisterSource.Params.DISPLAY_NAME)
        val sourceKind: TString = params.read(RegisterSource.Params.SOURCE_KIND)
        val watchedPaths: TString = params.read(RegisterSource.Params.WATCHED_PATHS)

        val key = IngestSource.keyOf(sourceId.get(), deviceId.get())
        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = IngestSource.ID,
            keyValue = key
        )
        val existing = executionContext.database.getData(dataId) as? IngestSource

        val merged = IngestSource.create(
            context = executionContext.context,
            sourceId = sourceId.get(),
            appId = appId.get(),
            deviceId = deviceId.get(),
            displayName = displayName.get(),
            sourceKind = sourceKind.get(),
            enabled = existing?.enabled?.get() ?: true,
            watchedPaths = watchedPaths.get().ifBlank { null },
            lastRunAt = existing?.lastRunAt?.get(),
            lastStatus = existing?.lastStatus?.get() ?: IngestSource.STATUS_IDLE,
            pendingCount = existing?.pendingCount?.get(),
            lastError = existing?.lastError?.get(),
        )
        executionContext.saveData(merged)

        return null as T
    }
}
