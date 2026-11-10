package app.tenshu.control.data

import app.tenshu.windows.WindowsNetworkCost

/**
 * Desktop network cost for the Path A sync gate and mint-on-view's metered refusal.
 *
 * - **Windows** exposes metering (Settings → *Metered connection*, cellular, a data limit, roaming),
 *   read on every call through `WindowsNetworkCost` — the answer changes when the user joins a
 *   hotspot, and the call is a cheap local query.
 * - **macOS, Linux**, and a Windows that cannot answer: assumed wired / unmetered
 *   (`bgproc-scheduling-roles.md` §2), so `WifiOnly` never blocks there.
 *
 * ⚠ Never [NetworkStatus.NONE]: offline is `RelayReachability`'s question, and a false NONE here
 * would stall sync under every constraint.
 *
 * [windowsCost] is a seam for tests; production reads the OS.
 */
class DesktopNetworkStateProvider(
    private val windowsCost: () -> WindowsNetworkCost.Cost = WindowsNetworkCost::current,
) : NetworkStateProvider {
    override fun current(): NetworkStatus = desktopNetworkStatus(windowsCost())
}

/**
 * ⚠ [WindowsNetworkCost.Cost.UNKNOWN] — every non-Windows host, a Windows older than 10 2004, a
 * failed call — keeps the unmetered answer desktop has always given: the interface has no
 * "unknown", and refusing sync on every Mac for want of a reading would be the worse default.
 */
internal fun desktopNetworkStatus(cost: WindowsNetworkCost.Cost): NetworkStatus = when (cost) {
    WindowsNetworkCost.Cost.METERED -> NetworkStatus.METERED
    WindowsNetworkCost.Cost.UNMETERED, WindowsNetworkCost.Cost.UNKNOWN -> NetworkStatus.UNMETERED
}
