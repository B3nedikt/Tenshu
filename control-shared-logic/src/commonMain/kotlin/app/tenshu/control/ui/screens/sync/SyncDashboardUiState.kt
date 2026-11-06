package app.tenshu.control.ui.screens.sync

import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.identity.Device

data class SyncDashboardUiState(
    val devices: List<Device> = emptyList(),
    /** Per-device read-only sync policy ("what each device keeps", §4.6), keyed by
     *  device id. Only devices with an explicit policy row appear; absent ⇒ no line. */
    val deviceSyncPolicies: Map<String, SyncPolicy> = emptyMap(),
    /** Admission lineage (delegated-device-admission-authority.md §5): deviceId →
     *  display name of the device that admitted it. Absent for genesis/pre-P1 rows,
     *  self-admissions, and devices whose admitter row is unknown. */
    val deviceAdmittedBy: Map<String, String> = emptyMap(),
    /**
     * `deviceId` → wall-clock ms of the newest write this device has ingested from it
     * (`DeviceLastSeen`). **Absent means "nothing heard from it here"** — which is equally true of
     * a peer that has been offline and of a row whose identity no longer exists, so the surface
     * renders it as an observation and never as a verdict.
     */
    val deviceLastSeen: Map<String, Long> = emptyMap(),
    /**
     * `deviceId` → that device's self-published hardware model (`DeviceHardware`). Absent ⇒ the
     * roster shows the user-chosen name alone; join the two with `deviceDisplayLabel`.
     */
    val deviceHardwareNames: Map<String, String> = emptyMap(),
    val currentDeviceId: String = "",
    val isLoading: Boolean = false,
    val isSyncEnabled: Boolean = false,
    /** A host pairing session is in flight (control-pairing-ux.md §3.5). Drives the
     *  "in Pairing" placeholder row — a visible home + re-entry point for the session. */
    val isPairing: Boolean = false,
    /** Seconds left in the bounded waiting window, or null when paused/unknown. */
    val pairingRemainingSeconds: Int? = null,
    /** A joiner's request has arrived and awaits approval (the row flips to "wants to join"). */
    val pairingRequestPending: Boolean = false,
    val error: String? = null,
    /**
     * A non-failure outcome the user must still be told about, because its effect is **not on
     * this screen** — "Restore access" repairs a *peer's* registration, and the peer is where the
     * change shows up, on its next sync tick. Without this the only honest rendering of a
     * successful readmit would be nothing at all.
     */
    val notice: String? = null,
)
