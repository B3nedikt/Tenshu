package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.health.HealthSource
import org.tenshu.core.health.SetSourceAutoImport

/**
 * The per-source kill switch — flips an existing [HealthSource]'s `autoImport`, preserving the
 * descriptor and the denormalised run trio. A no-op if no row exists for (`kind`, `deviceId`).
 *
 * The owning device honours the new value on its next foreground; there is no push. That is
 * what makes turning off another device's Apple Health import possible at all (D4).
 */
internal class SetSourceAutoImportImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val kind: TString = params.read(SetSourceAutoImport.Params.KIND)
        val deviceId: TString = params.read(SetSourceAutoImport.Params.DEVICE_ID)
        val autoImport: TBoolean = params.read(SetSourceAutoImport.Params.AUTO_IMPORT)

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = HealthSource.ID,
            keyValue = HealthSource.keyOf(kind.get(), deviceId.get())
        )
        val existing = executionContext.database.getData(dataId) as? HealthSource ?: return null as T

        val merged = HealthSource.create(
            context = executionContext.context,
            kind = existing.kind.get(),
            deviceId = existing.deviceId.get(),
            deviceLabel = existing.deviceLabel.get(),
            autoImport = autoImport.get(),
            lastRunAt = existing.lastRunAt?.get(),
            lastStatus = existing.lastStatus?.get(),
            lastItemCount = existing.lastItemCount?.get(),
        )
        executionContext.saveData(merged)

        return null as T
    }
}
