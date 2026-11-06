package org.tenshu.preference.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.preference.ClearSetting
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.WriteSetting
import org.tenshu.preference.client.impl.ClearSettingRo
import org.tenshu.preference.client.impl.PreferenceServiceRo
import org.tenshu.preference.client.impl.WriteSettingRo

val preferenceServiceClientModule = tModule {

    operation(
        interfaceClass = WriteSetting::class,
        protocolSchema = WriteSetting.Schema,
        roFactory = ::WriteSettingRo
    )

    operation(
        interfaceClass = ClearSetting::class,
        protocolSchema = ClearSetting.Schema,
        roFactory = ::ClearSettingRo
    )

    service(
        interfaceClass = PreferenceService::class,
        serviceSchema = PreferenceService.Schema,
        roFactory = ::PreferenceServiceRo
    )
}
