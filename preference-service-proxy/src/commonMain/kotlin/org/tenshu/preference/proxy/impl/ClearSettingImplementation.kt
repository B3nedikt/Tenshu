package org.tenshu.preference.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.ClearSetting
import org.tenshu.preference.RealmSetting

/**
 * Removes the row matching `(preferenceId, deviceId?)`. No-op if the row does
 * not exist — `Database.deleteData` is idempotent on a missing id.
 *
 * The Syncable + tombstone machinery handles outbound propagation: deleting a
 * `Syncable` entity fires `SyncObserver.onDataDeleted` which enqueues a
 * tombstone block per `docs/adr/0006-sync-tombstones.md` Phase 1–3. Per
 * `core-preference-service.md` §6.3.
 */
internal class ClearSettingImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val preferenceId: TString = params.read(ClearSetting.Params.PREFERENCE_ID)
        val deviceId: TString? = params?.get(ClearSetting.Params.DEVICE_ID) as? TString

        val dataId = if (deviceId != null) {
            DataId.StringDataId.create(
                context = executionContext.context,
                type = DeviceSetting.ID,
                keyValue = DeviceSetting.compositeKey(deviceId.get(), preferenceId.get())
            )
        } else {
            DataId.StringDataId.create(
                context = executionContext.context,
                type = RealmSetting.ID,
                keyValue = preferenceId.get()
            )
        }

        executionContext.deleteData(dataId)

        return null as T
    }
}
