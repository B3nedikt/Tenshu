package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.RecordImportRun

/**
 * Saves the run, then folds its figures onto the owning [HealthSource] (D5) so the Data sources
 * list stays a single read.
 *
 * Two cases where no source is touched, both correct:
 * - **A Takeout run.** An import from a file is an act, not a standing connection, so no
 *   `HealthSource` row exists for it (D11) — the batch surface reads the runs directly.
 * - **A run whose source row is absent.** Recording the fact still succeeds; a missing
 *   descriptor must never cost the user the record of what was imported.
 */
internal class RecordImportRunImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val run: HealthImportRun = params.read(RecordImportRun.Params.RUN)

        // saveData (not Database.saveData): fires the SyncObserver + the relation maintainer.
        executionContext.saveData(run)

        val kind = run.kind.get()
        if (kind == HealthImportRun.KIND_TAKEOUT) return null as T

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = HealthSource.ID,
            keyValue = HealthSource.keyOf(kind, run.deviceId.get())
        )
        val source = executionContext.database.getData(dataId) as? HealthSource ?: return null as T

        val updated = HealthSource.create(
            context = executionContext.context,
            kind = source.kind.get(),
            deviceId = source.deviceId.get(),
            deviceLabel = source.deviceLabel.get(),
            autoImport = source.autoImport.get(),
            lastRunAt = run.finishedAt.get(),
            lastStatus = run.status.get(),
            lastItemCount = run.itemCount.get(),
        )
        executionContext.saveData(updated)

        return null as T
    }
}
