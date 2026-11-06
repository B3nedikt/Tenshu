package org.tenshu.home.device.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.DeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.HomeDeviceService

internal class HomeDeviceServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : HomeDeviceService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val homeDevices: TList<HomeDevice>
        get() = serviceRelation(HomeDeviceService.Relations.HOME_DEVICES, HomeDevice.Schema)

    override val deviceStates: TList<DeviceState>
        get() = serviceRelation(HomeDeviceService.Relations.DEVICE_STATES, DeviceState.Schema)

    override val deviceCommands: TList<DeviceCommand>
        get() = serviceRelation(HomeDeviceService.Relations.DEVICE_COMMANDS, DeviceCommand.Schema)
}
