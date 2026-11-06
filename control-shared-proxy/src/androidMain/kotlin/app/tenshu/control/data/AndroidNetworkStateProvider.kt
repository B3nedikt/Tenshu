package app.tenshu.control.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Android network classification via `ConnectivityManager` (Path A,
 * `bgproc-scheduling-roles.md`). `NOT_METERED` → [NetworkStatus.UNMETERED]
 * (Wi-Fi / unmetered Ethernet); a live metered transport → [NetworkStatus.METERED]
 * (cellular / metered Wi-Fi); no validated internet → [NetworkStatus.NONE].
 */
class AndroidNetworkStateProvider(
    private val context: Context,
) : NetworkStateProvider {

    override fun current(): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus.NONE
        val network = cm.activeNetwork ?: return NetworkStatus.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkStatus.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkStatus.NONE
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkStatus.UNMETERED
        } else {
            NetworkStatus.METERED
        }
    }
}
