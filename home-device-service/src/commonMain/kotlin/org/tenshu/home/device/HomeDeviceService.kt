package org.tenshu.home.device

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The smart-home device service (`home-device-service.md` HD-D1/HD-D6):
 * bridged devices as realm data — **synced state, not remote operations**
 * (the H4 inversion; see `HubStatus`'s KDoc in `:hub-management-service` for
 * the rationale). Three relations, three authorship rules:
 *
 *  - [homeDevices]: inventory, hub-authored on join, `name`/`room`
 *    user-editable via [UpdateHomeDevice];
 *  - [deviceStates]: live state, hub-authored only, change-gated +
 *    rate-limited by the hub-side publisher;
 *  - [deviceCommands]: idempotent commands a managing device authors (via
 *    [IssueDeviceCommand]) and the owning hub consumes at most once inside
 *    the 2-minute [DeviceCommand.STALE_WINDOW_MS].
 *
 * Reads are local reactive reads of the synced rows; the only ops execute on
 * the *caller's* proxy and author rows sync then delivers.
 */
interface HomeDeviceService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Hub-authored device inventory, one row per bridged device. */
    val homeDevices: TList<HomeDevice>

    /** Hub-authored live state, one row per bridged device. */
    val deviceStates: TList<DeviceState>

    /** Device commands; consumed at most once by the owning hub. */
    val deviceCommands: TList<DeviceCommand>

    companion object {
        const val ID = "tenshu/home/device/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:home-device-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.HOME_DEVICES,
                    type = TList.typeOf(HomeDevice.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_STATES,
                    type = TList.typeOf(DeviceState.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.DEVICE_COMMANDS,
                    type = TList.typeOf(DeviceCommand.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val HOME_DEVICES = "$ID/homeDevices"
        const val DEVICE_STATES = "$ID/deviceStates"
        const val DEVICE_COMMANDS = "$ID/deviceCommands"
    }
}
