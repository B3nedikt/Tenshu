package app.tenshu.control.data

/**
 * iOS is assumed unmetered for the Path A sync constraint gate
 * (`bgproc-scheduling-roles.md` §2), mirroring [DesktopNetworkStateProvider] — so
 * `WifiOnly` never blocks the in-process sync cycle here. Real metered-network
 * detection (NWPathMonitor) lands with the iOS background-window work (C6/O4); this
 * is the single place to implement it.
 */
class IosNetworkStateProvider : NetworkStateProvider {
    override fun current(): NetworkStatus = NetworkStatus.UNMETERED
}
