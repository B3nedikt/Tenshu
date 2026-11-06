package app.tenshu.home.data

import org.tenshu.core.TResult
import org.tenshu.core.common.TenshuClient
import org.tenshu.home.device.client.homeDeviceService
import org.tenshu.home.device.client.openPairingWindow

/**
 * The real [DevicePairingSource]: issues HD-2's `permitJoin` command row
 * through `:home-device-service-client`. The hub whose bridge owns the target
 * consumes it exactly once, clamps the window, and asks the radio.
 *
 * Failures (no proxy connection, an older Control without the home-device
 * wiring) surface as `false` rather than an exception — the caller's honest
 * "could not start pairing" state, symmetric with [ClientDeviceSource]'s
 * degrade-to-empty contract.
 */
class ClientDevicePairingSource(
    private val client: TenshuClient,
) : DevicePairingSource {

    override suspend fun openPairingWindow(bridgeId: String, seconds: Int): Boolean = try {
        client.homeDeviceService.openPairingWindow(bridgeId, seconds) is TResult.Success
    } catch (t: Throwable) {
        false
    }
}
