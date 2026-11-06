package org.tenshu.identity.client.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.collection.list.TList
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.identity.IdentityService

internal class IdentityServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : IdentityService, ServiceRo(requestChain, context) {

    override val schema: ServiceSchema
        get() = IdentityService.Schema

    override val components: Map<String, TComponent>
        get() = context.components

    override val devices: TList<Device>
        get() = serviceRelation(IdentityService.Relations.DEVICES, Device.Schema)

    override val deviceLastSeen: TList<DeviceLastSeen>
        get() = serviceRelation(IdentityService.Relations.DEVICE_LAST_SEEN, DeviceLastSeen.Schema)

    override val deviceHardware: TList<DeviceHardware>
        get() = serviceRelation(IdentityService.Relations.DEVICE_HARDWARE, DeviceHardware.Schema)
}
