@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.BackupEnvironment
import app.tenshu.ingest.backup.BackupNetworkStatus
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.dispatch_get_global_queue
import platform.posix.QOS_CLASS_UTILITY

/**
 * iOS [BackupEnvironment] for the Synced-Folders gate (ios-drive-parity.md P6b) — the iOS twin of
 * the Android/desktop network+charging seams. `wifiOnly` (the realm background-data policy) and
 * `chargingOnly` (device option) gate the drain; this reports the live values.
 *
 * **Network** rides `NWPathMonitor` (Network.framework): a monitor started once caches the latest
 * path, classified `UNMETERED` (satisfied, not expensive) / `METERED` (satisfied + expensive =
 * cellular / personal hotspot) / `NONE` (unsatisfied). Cheap, correct, and the modern API (vs the
 * deprecated `SCNetworkReachability`). **Charging** reads `UIDevice.batteryState`
 * (charging or full = on external power).
 *
 * ⚠ Actual gate behaviour (a real cellular vs Wi-Fi decision, low-data-mode) is verified on the
 * P7 device pass — the simulator reports a fixed satisfied/unmetered path.
 */
class IosBackupEnvironment : BackupEnvironment {

    private val latest = atomic(BackupNetworkStatus.NONE)

    // ⚠ Network only. A charger change is not signalled on iOS (it would need
    // `UIDeviceBatteryStateDidChangeNotification`), so "Waiting to charge" still clears on the
    // next media event rather than at the plug.
    private val networkMoved =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(QOS_CLASS_UTILITY.toLong(), 0u))
        nw_path_monitor_set_update_handler(monitor) { path ->
            val status = when {
                path == null -> BackupNetworkStatus.NONE
                nw_path_get_status(path) != nw_path_status_satisfied -> BackupNetworkStatus.NONE
                nw_path_is_expensive(path) -> BackupNetworkStatus.METERED
                else -> BackupNetworkStatus.UNMETERED
            }
            if (latest.getAndSet(status) != status) networkMoved.tryEmit(Unit)
        }
        nw_path_monitor_start(monitor)
    }

    override fun network(): BackupNetworkStatus = latest.value

    override fun changes(): Flow<Unit> = networkMoved

    override fun isCharging(): Boolean {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val state = device.batteryState
        return state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
    }
}
