package org.tenshu.sdk.ios

/**
 * A host-side pairing approval awaiting the user, flattened for SwiftUI — the iOS twin of what
 * Compose's app-global `HostPairingApprovalDialog` reads off
 * [app.tenshu.control.sync.HostPairingMonitor].
 *
 * Top-level, not nested in [ControlSurface], for the same reason `ControlNotificationsSnapshot`
 * is: a nested Kotlin class arrives in Swift under a flattened name, and the SwiftUI side should
 * name this one (it holds it in `@Published` state).
 *
 * The `PairingRequest` composite itself never crosses the bridge — only the two things the user
 * compares and acts on. [sas] is null when the proxy could not derive one (the field is optional
 * on the wire and only enriched host-side), so the prompt renders off [deviceName]; [error] is the
 * reason the last Approve failed, if it did.
 */
class ControlHostPairingApproval internal constructor(
    val deviceName: String,
    val sas: String?,
    val error: String?,
)
