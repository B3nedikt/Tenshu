package org.tenshu.preference.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.preference.DeviceSetting
import org.tenshu.preference.DeviceSettingDataIdProvider
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.RealmSetting
import org.tenshu.preference.RealmSettingDataIdProvider
import org.tenshu.preference.Setting
import org.tenshu.preference.common.impl.DeviceSettingDataIdProviderImpl
import org.tenshu.preference.common.impl.DeviceSettingDto
import org.tenshu.preference.common.impl.DeviceSettingRo
import org.tenshu.preference.common.impl.RealmSettingDataIdProviderImpl
import org.tenshu.preference.common.impl.RealmSettingDto
import org.tenshu.preference.common.impl.RealmSettingRo

val preferenceServiceCommonModule = tModule {

    // Abstract super-composite — structural registration only, no DTO factory.
    // RealmSetting / DeviceSetting reference it as `parent` and inherit its
    // `preferenceId` + `value` field declarations at materialisation.
    composite(Setting.Schema)

    composite(
        interfaceClass = RealmSetting::class,
        dataSchema = RealmSetting.Schema,
        dtoFactory = ::RealmSettingDto,
        roFactory = { chain, ctx ->
            RealmSettingRo(
                requestChain = chain,
                context = ctx,
                schema = RealmSetting.Schema,
            )
        }
    )

    composite(
        interfaceClass = DeviceSetting::class,
        dataSchema = DeviceSetting.Schema,
        dtoFactory = ::DeviceSettingDto,
        roFactory = { chain, ctx ->
            DeviceSettingRo(
                requestChain = chain,
                context = ctx,
                schema = DeviceSetting.Schema,
            )
        }
    )

    service(PreferenceService.Schema)

    trait(
        dataSchema = RealmSetting.Schema,
        traitSchema = RealmSettingDataIdProvider.Schema,
        traitFactory = { context, schema ->
            RealmSettingDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = DeviceSetting.Schema,
        traitSchema = DeviceSettingDataIdProvider.Schema,
        traitFactory = { context, schema ->
            DeviceSettingDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

}
