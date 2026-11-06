package app.tenshu.home.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.tenshu.home.device.DeviceState as SyncedDeviceState
import org.tenshu.home.device.HomeDevice
import org.tenshu.home.device.client.deviceStatesFlow
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.homeDevicesFlow
import org.tenshu.core.common.TenshuClient

/**
 * The real [DeviceSource]: Home's Tenshu client joining the hub-authored
 * `HomeDevice` inventory rows with their `DeviceState` snapshots through
 * `:home-device-service-client` (a local reactive read of the synced rows).
 * Every failure mode — no proxy connection, an older Control without the
 * home-device wiring, a realm with no bridged devices — degrades to an
 * empty emission, which is exactly the repository's fall-back-to-mock
 * signal (the [ClientHubStatusSource] contract).
 */
class ClientDeviceSource(
    private val client: TenshuClient,
) : DeviceSource {

    override fun devices(): Flow<List<SyncedDeviceInfo>> = flow {
        emitAll(
            combine(
                client.homeDeviceService.homeDevicesFlow(),
                client.homeDeviceService.deviceStatesFlow(),
            ) { devices, states ->
                val statesById = states.associateBy { it.deviceId.get() }
                devices.map { device -> device.toInfo(statesById[device.deviceId.get()]) }
            },
        )
    }.catch { emptyOnUnreachable("device", it) }

    private fun HomeDevice.toInfo(state: SyncedDeviceState?): SyncedDeviceInfo =
        SyncedDeviceInfo(
            deviceId = deviceId.get(),
            kind = kind.get(),
            bridgeId = bridgeId.get(),
            capabilities = capabilities(),
            name = name.get(),
            room = room.get(),
            stateFresh = state != null,
            online = state?.online?.get() ?: false,
            powerOn = state?.powerOnOrNull(),
            levelPercent = state?.levelPercent?.get()
                ?.takeIf { it != SyncedDeviceState.NO_PERCENT }?.toInt(),
            temperatureDeciC = state?.temperatureDeciC?.get()
                ?.takeIf { it != SyncedDeviceState.NO_TEMPERATURE_DECI_C }?.toInt(),
            humidityPercent = state?.humidityPercent?.get()
                ?.takeIf { it != SyncedDeviceState.NO_PERCENT }?.toInt(),
            batteryPercent = state?.batteryPercent?.get()
                ?.takeIf { it != SyncedDeviceState.NO_PERCENT }?.toInt(),
            // The correlation pair for actuation: whose command the hub last
            // consumed for this device, and what came of it.
            lastCommandId = state?.lastCommandId?.get().orEmpty(),
            lastCommandOutcome = state?.lastCommandOutcome?.get().orEmpty(),
        )
}
