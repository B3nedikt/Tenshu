package app.tenshu.home.data

import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.setDeviceLevel
import org.tenshu.home.device.client.setDevicePower

/**
 * The real [DeviceControlSource]: writes a `DeviceCommand` row through
 * `:home-device-service-client`. The hub owning the target's bridge consumes it
 * exactly once (durable journal, keyed by the caller-minted command id), asks
 * the radio, and publishes the outcome onto the device's `DeviceState` row.
 *
 * Failures — no proxy connection, an older Control without the home-device
 * wiring, a refused write — surface as `false` rather than an exception,
 * symmetric with [ClientDevicePairingSource].
 */
class ClientDeviceControlSource(
    private val client: TenshuClient,
) : DeviceControlSource {

    override suspend fun setPower(deviceId: String, on: Boolean, commandId: String): Boolean = try {
        client.homeDeviceService.setDevicePower(deviceId, on, commandId) is TResult.Success
    } catch (t: Throwable) {
        false
    }

    override suspend fun setLevel(deviceId: String, percent: Int, commandId: String): Boolean = try {
        client.homeDeviceService.setDeviceLevel(deviceId, percent, commandId) is TResult.Success
    } catch (t: Throwable) {
        false
    }
}
