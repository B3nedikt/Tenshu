package org.tenshu.preference.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.WriteSetting

/**
 * Discriminates on whether [WriteSetting.Params.DEVICE_ID] is present:
 *
 *  - present → [DeviceSetting] (one row per `(deviceId, preferenceId)`);
 *  - absent  → [RealmSetting] (one row per `preferenceId`).
 *
 * The composite is built via the relevant `Companion.create<V>(...)` factory
 * with `V = Data` (erased — the actual value type is whatever the caller
 * passed). `executionContext.saveData(entity)` fires both the relation
 * maintainer (see `PreferenceProxyInitialization`) and the regular sync
 * observer pipeline. Per `core-preference-service.md` §6.2.
 */
internal class WriteSettingImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val preferenceId: TString = params.read(WriteSetting.Params.PREFERENCE_ID)
        val value: Data = params.read(WriteSetting.Params.VALUE)
        val deviceId: TString? = params?.get(WriteSetting.Params.DEVICE_ID) as? TString

        val entity = if (deviceId != null) {
            DeviceSetting.create(
                context = executionContext.context,
                deviceId = deviceId.get(),
                preferenceId = preferenceId.get(),
                value = value,
            )
        } else {
            RealmSetting.create(
                context = executionContext.context,
                preferenceId = preferenceId.get(),
                value = value,
            )
        }

        executionContext.saveData(entity)

        return null as T
    }
}
