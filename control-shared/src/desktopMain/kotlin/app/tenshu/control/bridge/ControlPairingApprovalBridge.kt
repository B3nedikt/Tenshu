package app.tenshu.control.bridge

import app.tenshu.control.sync.HostPairingMonitor
import org.koin.core.Koin
import org.tenshu.core.common.proxy.NativeBridgeServer

/**
 * Registers the native-bridge method backing the macOS tray's OS-level elevation of an
 * **inbound host pairing request** (`docs/proposals/control-pairing-ux.md` Phase 3, §3.6)
 * — the pairing counterpart to [registerPendingRequestMethods].
 *
 *  - `pairingApproval` — whether a device is currently asking to join this realm, read
 *    from the process-lifetime [HostPairingMonitor.activeRequest] (the same `StateFlow`
 *    the app-global approval modal renders). The Swift tray-agent polls this on its
 *    background timer; when `pending` flips true it posts a `UNUserNotificationCenter`
 *    banner, and on tap focuses Control so the in-app modal surfaces.
 *
 * **Why poll, not push / why the tray.** Same as the pending-requests bridge: the bridge
 * is request/response, and only the Swift GUI agent holds notification authorization, not
 * this JVM daemon. The notification is genuinely needed on desktop for the close-to-tray
 * case — a hidden window's Compose modal cannot raise the OS window on its own.
 *
 * **SAS + proxy isolation (load-bearing).** The payload is **minimal, PII-free, and
 * SAS-free**: `{ pending, id }` only. `id` is the opaque joiner `deviceId`, used solely
 * as the notification identifier (so the shade replaces, not stacks) — never displayed.
 * The SAS is *deliberately* withheld: pairing approval requires comparing the SAS digits
 * on both devices, so a notification must never be able to approve — it only routes the
 * user to the in-app modal where the comparison happens (§3.3). The device name (the
 * joiner's self-asserted, unverified value) is likewise never shipped; the tray composes
 * a generic, localised "a device wants to join" string from `pending` alone (ADR 0016).
 * The JSON-shaping is factored into [pairingApprovalResult] (taking the extracted opaque
 * id, not the composite) so it is unit-testable without a socket or a schema registry.
 */
fun NativeBridgeServer.registerPairingApprovalMethod(koin: Koin) {
    register("pairingApproval") {
        pairingApprovalResult(koin.get<HostPairingMonitor>().activeRequest.value?.deviceId?.get())
    }
}

/**
 * `{ pending: Boolean, id: String? }`. [deviceId] is the opaque joiner device id of the
 * pending pairing request (the notification identifier + diff key), or null when no
 * device is asking to join. Deliberately carries no SAS, no device name, no pubkeys.
 */
internal fun pairingApprovalResult(deviceId: String?): Map<String, Any?> = mapOf(
    "pending" to (deviceId != null),
    "id" to deviceId,
)
