package org.tenshu.home.device.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.common.impl.DeviceCommandDto
import org.tenshu.home.device.common.impl.DeviceCommandRo
import org.tenshu.home.device.common.impl.DeviceStateDto
import org.tenshu.home.device.common.impl.DeviceStateRo
import org.tenshu.home.device.common.impl.HomeDeviceDto
import org.tenshu.home.device.common.impl.HomeDeviceRo

/**
 * Schema registration for the home-device service (`home-device-service.md`
 * HD-1). All three composites are `Syncable(Eager)` NEW types — additive, no
 * existing row reshaped, so no `TENSHU_STORAGE_FORMAT_VERSION` bump (the
 * `MovementTrace` / hub-management precedent).
 */
val homeDeviceServiceCommonModule = tModule {

    composite(
        interfaceClass = HomeDevice::class,
        dataSchema = HomeDevice.Schema,
        dtoFactory = ::HomeDeviceDto,
        roFactory = { chain, ctx ->
            HomeDeviceRo(
                requestChain = chain,
                context = ctx,
                schema = HomeDevice.Schema,
            )
        }
    )

    composite(
        interfaceClass = DeviceState::class,
        dataSchema = DeviceState.Schema,
        dtoFactory = ::DeviceStateDto,
        roFactory = { chain, ctx ->
            DeviceStateRo(
                requestChain = chain,
                context = ctx,
                schema = DeviceState.Schema,
            )
        }
    )

    composite(
        interfaceClass = DeviceCommand::class,
        dataSchema = DeviceCommand.Schema,
        dtoFactory = ::DeviceCommandDto,
        roFactory = { chain, ctx ->
            DeviceCommandRo(
                requestChain = chain,
                context = ctx,
                schema = DeviceCommand.Schema,
            )
        }
    )

    service(HomeDeviceService.Schema)

    trait(
        dataSchema = HomeDevice.Schema,
        traitSchema = HomeDevice.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = DeviceState.Schema,
        traitSchema = DeviceState.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = DeviceCommand.Schema,
        traitSchema = DeviceCommand.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

}
