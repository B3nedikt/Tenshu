package app.tenshu.home.data

import app.tenshu.home.model.Device
import app.tenshu.home.model.DeviceKind
import app.tenshu.home.model.DeviceState
import app.tenshu.home.model.Room
import app.tenshu.home.model.formatDeciC
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import org.tenshu.home.device.HomeDevice

/**
 * The Devices repository with REAL bridged devices from `:home-device-service`
 * (`home-device-service.md` HD-D5) — the same decorator seam the storage tile
 * proved ([HubAwareHomeDevicesRepository]); in `homeClientModule` this wraps
 * that decorator, so the tile overlay keeps working underneath.
 *
 * **Fallback semantics** ("the mock is Home's client-side fallback; the
 * bridge's devices are REAL rows — keep the two distinct, never mix"): while
 * the [DeviceSource] emits no devices — no proxy connection, no hub, no
 * bridged devices — the delegate's household passes through **untouched**,
 * exactly as today. Once real devices exist, the delegate's *household*
 * rows (lights, sensors, thermostats, …) are replaced wholesale by the real
 * ones; only the delegate's infrastructure tiles ([DeviceKind.Hub] /
 * [DeviceKind.Storage] — themselves live-overlaid by the storage-tile slice)
 * are kept. A mock lamp is never rendered next to a real one.
 *
 * ⚠ There are no mutations any more: add/remove/reorder only ever acted on the mock household,
 * which is gone. Adding a *real* device is the HD-2 permit-join flow through `DevicePairingSource`,
 * and renaming one is the `renameDevice` client op (not part of this tab's v1 surface).
 */
class SyncedDeviceAwareHomeDevicesRepository(
    private val delegate: HomeDevicesRepository,
    private val source: DeviceSource,
) : HomeDevicesRepository {

    override fun rooms(): Flow<List<Room>> =
        combine(delegate.rooms(), sourceDevices()) { delegateRooms, real ->
            if (real.isEmpty()) delegateRooms
            else {
                val syncedRooms = real
                    .mapNotNull { it.room.takeIf(String::isNotBlank) }
                    .distinct()
                    .filter { name -> delegateRooms.none { it.name == name } }
                    .map { name -> Room(id = syncedRoomId(name), name = name) }
                delegateRooms + syncedRooms
            }
        }.distinctUntilChanged()

    override fun devices(): Flow<List<Device>> =
        combine(delegate.devices(), delegate.rooms(), sourceDevices()) { delegateDevices, delegateRooms, real ->
            if (real.isEmpty()) delegateDevices
            else {
                val infrastructure = delegateDevices.filter {
                    it.kind == DeviceKind.Hub || it.kind == DeviceKind.Storage
                }
                (infrastructure + real.map { it.toDevice(delegateRooms) })
                    .mapIndexed { index, device -> device.copy(sortIndex = index) }
            }
        }.distinctUntilChanged()


    /** Starts empty so the UI renders (mock) immediately rather than waiting on the source. */
    private fun sourceDevices(): Flow<List<SyncedDeviceInfo>> =
        source.devices().onStart { emit(emptyList()) }.distinctUntilChanged()

    private fun SyncedDeviceInfo.toDevice(delegateRooms: List<Room>): Device = Device(
        id = deviceId,
        name = name,
        // A synced room string resolves to an existing (mock) room of the
        // same name where one exists, else to its synthetic synced-room id
        // (the id `rooms()` mints for it).
        roomId = room.takeIf(String::isNotBlank)?.let { name ->
            delegateRooms.firstOrNull { it.name == name }?.id ?: syncedRoomId(name)
        },
        kind = kindModel(),
        state = stateModel(),
        sortIndex = 0, // re-assigned by the caller's reindex pass
    )

    private fun SyncedDeviceInfo.kindModel(): DeviceKind = when (kind) {
        HomeDevice.KIND_LIGHT -> DeviceKind.Light
        HomeDevice.KIND_THERMOSTAT -> DeviceKind.Thermostat
        HomeDevice.KIND_SPEAKER -> DeviceKind.Speaker
        HomeDevice.KIND_CAMERA -> DeviceKind.Camera
        // A radio bridge (HD-2) is infrastructure, not a household device —
        // it renders like the Hub tile, with connectivity as its state.
        HomeDevice.KIND_BRIDGE -> DeviceKind.Hub
        // Plugs, sensors and unknown future kinds render as sensor rows —
        // the generic "here is a reading" shape.
        else -> DeviceKind.Sensor
    }

    private fun SyncedDeviceInfo.stateModel(): DeviceState = when (kindModel()) {
        DeviceKind.Light -> DeviceState.Light(
            on = powerOn ?: false,
            brightnessPercent = levelPercent ?: 0,
        )

        DeviceKind.Thermostat -> DeviceState.Thermostat(
            currentDeciC = temperatureDeciC ?: 0,
            targetDeciC = temperatureDeciC ?: 0,
        )

        DeviceKind.Speaker, DeviceKind.Camera, DeviceKind.Hub ->
            DeviceState.Connectivity(online = online)

        else -> DeviceState.SensorReading(summary = sensorSummary())

        // Storage never occurs for a bridged device (kindModel above).
    }

    /**
     * Punctuation-grade reading line, locale-neutral like the model's other
     * formatters (ADR 0016 n=1): "21.5° · 45%"; empty while no state synced.
     */
    private fun SyncedDeviceInfo.sensorSummary(): String =
        listOfNotNull(
            temperatureDeciC?.let { formatDeciC(it) },
            humidityPercent?.let { "$it%" },
        ).joinToString(" · ")

    companion object {
        /** Stable synthetic Room id for a synced room string (D5 — no Room entity yet). */
        fun syncedRoomId(name: String): String = "synced-room:$name"
    }
}
