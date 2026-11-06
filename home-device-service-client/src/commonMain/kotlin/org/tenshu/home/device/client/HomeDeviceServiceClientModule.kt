package org.tenshu.home.device.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.home.device.HomeDeviceService
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.home.device.UpdateHomeDevice
import org.tenshu.home.device.client.impl.HomeDeviceServiceRo
import org.tenshu.home.device.client.impl.IssueDeviceCommandRo
import org.tenshu.home.device.client.impl.UpdateHomeDeviceRo

val homeDeviceServiceClientModule = tModule {

    operation(
        interfaceClass = IssueDeviceCommand::class,
        protocolSchema = IssueDeviceCommand.Schema,
        roFactory = ::IssueDeviceCommandRo
    )

    operation(
        interfaceClass = UpdateHomeDevice::class,
        protocolSchema = UpdateHomeDevice.Schema,
        roFactory = ::UpdateHomeDeviceRo
    )

    service(
        interfaceClass = HomeDeviceService::class,
        serviceSchema = HomeDeviceService.Schema,
        roFactory = ::HomeDeviceServiceRo
    )
}
