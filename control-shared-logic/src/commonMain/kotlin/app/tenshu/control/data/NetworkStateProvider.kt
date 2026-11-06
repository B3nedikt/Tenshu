package app.tenshu.control.data

/** Coarse network classification for the Path A sync constraint gate. */
enum class NetworkStatus { UNMETERED, METERED, NONE }

/**
 * Platform view of the current network. Path A
 * (`bgproc-scheduling-roles.md`): desktop reports [NetworkStatus.UNMETERED]
 * (assumed wired/unmetered, so `WifiOnly` never blocks there); Android queries
 * `ConnectivityManager`.
 */
interface NetworkStateProvider {
    fun current(): NetworkStatus
}
