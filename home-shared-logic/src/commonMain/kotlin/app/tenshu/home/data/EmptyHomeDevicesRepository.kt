package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.Room
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * No devices, no rooms — the base of the devices chain once the mock household was retired
 * (`ADR 0025`; RE-1's "swaps from mock to real" finishing).
 *
 * ⚠⚠ It exists so the chain has a bottom, not to supply content. Everything real enters above it:
 * bridged devices via [SyncedDeviceAwareHomeDevicesRepository]'s [DeviceSource], and the Hub and
 * Storage tiles synthesised from live status by [HubAwareHomeDevicesRepository]. What used to sit
 * here was a seeded household whose lamps read as *on* on a device that could reach nothing.
 */
class EmptyHomeDevicesRepository : HomeDevicesRepository {
    override fun rooms(): Flow<List<Room>> = flowOf(emptyList())
    override fun devices(): Flow<List<Device>> = flowOf(emptyList())
}
