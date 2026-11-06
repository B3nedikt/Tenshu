package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.RegisterHealthSource

/**
 * Idempotent upsert of a ([kind], [deviceId]) [HealthSource] row.
 *
 * ⚠⚠ **Refreshes the descriptor only.** This runs on every launch where the platform importer
 * reports itself available, so an existing row keeps its `autoImport` and its denormalised run
 * trio; only `deviceLabel` is refreshed (a device gets renamed). Re-defaulting `autoImport` to
 * `true` here would silently re-enable a source the user had turned off — from the other side
 * of the realm, since the flag syncs.
 */
internal class RegisterHealthSourceImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val kind: TString = params.read(RegisterHealthSource.Params.KIND)
        val deviceId: TString = params.read(RegisterHealthSource.Params.DEVICE_ID)
        val deviceLabel: TString = params.read(RegisterHealthSource.Params.DEVICE_LABEL)

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = HealthSource.ID,
            keyValue = HealthSource.keyOf(kind.get(), deviceId.get())
        )
        val existing = executionContext.database.getData(dataId) as? HealthSource

        val row = HealthSource.create(
            context = executionContext.context,
            kind = kind.get(),
            deviceId = deviceId.get(),
            deviceLabel = deviceLabel.get(),
            autoImport = existing?.autoImport?.get() ?: true,
            lastRunAt = existing?.lastRunAt?.get(),
            lastStatus = existing?.lastStatus?.get(),
            lastItemCount = existing?.lastItemCount?.get(),
        )

        // saveData (not Database.saveData): fires the SyncObserver + the relation maintainer.
        executionContext.saveData(row)

        return null as T
    }
}
