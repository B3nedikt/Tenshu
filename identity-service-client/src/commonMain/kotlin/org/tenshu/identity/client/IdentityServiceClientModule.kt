package org.tenshu.identity.client

import org.tenshu.core.common.impl.CompositeRo
import org.tenshu.core.common.impl.EntityRo
import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.type
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.identity.Device
import org.tenshu.identity.DeviceHardware
import org.tenshu.identity.DeviceLastSeen
import org.tenshu.identity.GetDevices
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.GetSharedRealmMemberships
import org.tenshu.identity.IdentityService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.identity.client.impl.GetDevicesRo
import org.tenshu.identity.client.impl.GetRealmMembersRo
import org.tenshu.identity.client.impl.GetSharedRealmMembershipsRo
import org.tenshu.identity.client.impl.IdentityServiceRo

val identityServiceClientModule = tModule {

    type(
        interfaceClass = Device::class,
        dataSchema = Device.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, Device.Schema), Device {
                override val schema: CompositeSchema get() = Device.Schema
            }
        }
    )

    // Without this the `deviceLastSeen` relation materialises as opaque composites and the roster
    // join dies as `error/internal` — the client-module RO registration that on-demand properties
    // and typed relations both need, not merely a nicety.
    type(
        interfaceClass = DeviceLastSeen::class,
        dataSchema = DeviceLastSeen.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DeviceLastSeen.Schema), DeviceLastSeen {
                override val schema: CompositeSchema get() = DeviceLastSeen.Schema
            }
        }
    )

    // Same reason as DeviceLastSeen above: the roster joins this relation by deviceId.
    type(
        interfaceClass = DeviceHardware::class,
        dataSchema = DeviceHardware.Schema,
        roFactory = { chain, ctx ->
            object : CompositeRo(chain, ctx, DeviceHardware.Schema), DeviceHardware {
                override val schema: CompositeSchema get() = DeviceHardware.Schema
            }
        }
    )

    operation(
        interfaceClass = GetDevices::class,
        protocolSchema = GetDevices.Schema,
        roFactory = ::GetDevicesRo
    )

    // A typed RO so `getSharedRealmMemberships`' `TList<SharedRealmMembership>`
    // materialises its elements as `SharedRealmMembership` (mirrors the file-service
    // `Placement` RO behind `listPlacements`).
    type(
        interfaceClass = SharedRealmMembership::class,
        dataSchema = SharedRealmMembership.Schema,
        roFactory = { chain, ctx ->
            object : EntityRo<SharedRealmMembership>(chain, ctx, SharedRealmMembership.Schema), SharedRealmMembership {
                override val schema: CompositeSchema get() = SharedRealmMembership.Schema
            }
        }
    )

    operation(
        interfaceClass = GetSharedRealmMemberships::class,
        protocolSchema = GetSharedRealmMemberships.Schema,
        roFactory = ::GetSharedRealmMembershipsRo
    )

    operation(
        interfaceClass = GetRealmMembers::class,
        protocolSchema = GetRealmMembers.Schema,
        roFactory = ::GetRealmMembersRo
    )

    type(
        interfaceClass = IdentityService::class,
        dataSchema = IdentityService.Schema,
        roFactory = ::IdentityServiceRo
    )
}
