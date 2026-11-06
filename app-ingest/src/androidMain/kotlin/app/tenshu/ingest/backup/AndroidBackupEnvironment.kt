package app.tenshu.ingest.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Android [BackupEnvironment] for the in-process (app-alive) gate
 * (`docs/proposals/drive-synced-folders.md` §3.8). Mirrors
 * `AndroidNetworkStateProvider`'s metered/unmetered classification and adds the
 * charging signal via [BatteryManager]. The Phase-2 WorkManager tier maps the
 * same two switches onto native `Constraints`; this is only the app-alive path.
 *
 * Not locally compilable in the headless build env (no Android SDK); correctness
 * by construction + Studio verification.
 */
class AndroidBackupEnvironment(
    private val context: Context,
) : BackupEnvironment {

    override fun network(): BackupNetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return BackupNetworkStatus.NONE
        val network = cm.activeNetwork ?: return BackupNetworkStatus.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return BackupNetworkStatus.NONE
        return classify(caps)
    }

    private fun classify(caps: NetworkCapabilities): BackupNetworkStatus = when {
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> BackupNetworkStatus.NONE
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> BackupNetworkStatus.UNMETERED
        else -> BackupNetworkStatus.METERED
    }

    /**
     * ⚠ Both halves are deduplicated on the *classification*, not the raw callback:
     * `onCapabilitiesChanged` fires on every signal-strength step and `ACTION_BATTERY_CHANGED` on
     * every percent, and each emission here costs the controller a gated drain.
     */
    override fun changes(): Flow<Unit> = merge(
        networkStatuses().distinctUntilChanged().map { },
        chargingStates().distinctUntilChanged().map { },
    )

    private fun networkStatuses(): Flow<BackupNetworkStatus> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // The DEFAULT network, matching [network]'s `activeNetwork`: a handover to cellular
        // arrives as the new network's capabilities, and `onLost` only when nothing is left.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(classify(caps))
            }

            override fun onLost(network: Network) {
                trySend(BackupNetworkStatus.NONE)
            }
        }
        // Registration can throw (SecurityException, the per-app callback cap). The gate still
        // reads the environment on every other trigger, so losing this one is a staleness, not a
        // failure worth taking the controller's collector down for.
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    private fun chargingStates(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.isChargingStatus())
            }
        }
        // Sticky and system-only, so no exported flag is owed.
        val registered = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.isSuccess
        awaitClose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun Intent.isChargingStatus(): Boolean {
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batteryManager != null) {
            return batteryManager.isCharging
        }
        // Fallback: sticky battery-changed broadcast.
        return context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.isChargingStatus() ?: false
    }
}
