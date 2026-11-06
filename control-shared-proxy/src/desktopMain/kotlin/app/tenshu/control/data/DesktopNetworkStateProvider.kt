package app.tenshu.control.data

/**
 * Desktop is assumed wired / unmetered (`bgproc-scheduling-roles.md` §2), so
 * `WifiOnly` never blocks sync here. If desktop ever needs real metered-network
 * detection, this is the single place to implement it.
 */
class DesktopNetworkStateProvider : NetworkStateProvider {
    override fun current(): NetworkStatus = NetworkStatus.UNMETERED
}
