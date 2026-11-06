package org.tenshu.preference.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.preference.ClearSetting
import org.tenshu.preference.WriteSetting
import org.tenshu.preference.proxy.impl.ClearSettingImplementation
import org.tenshu.preference.proxy.impl.WriteSettingImplementation

fun preferenceServiceProxyImplementationFactory(
    id: String
): OperationImplementation? {
    return when (id) {
        WriteSetting.ID -> WriteSettingImplementation()
        ClearSetting.ID -> ClearSettingImplementation()
        else -> null
    }
}
