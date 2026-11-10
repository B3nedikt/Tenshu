package app.tenshu.control.data

import app.tenshu.windows.WindowsNetworkCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Desktop's network status: Windows' cost when it has one, the unmetered default otherwise. The
 * Windows binding and its cost mapping are pinned by `:windows-helpers`' `WindowsNetworkCostTest`.
 */
class DesktopNetworkStateProviderTest {

    @Test
    fun meteredIsMetered_unmeteredAndUnknownKeepTheDefault() {
        assertEquals(NetworkStatus.METERED, DesktopNetworkStateProvider { WindowsNetworkCost.Cost.METERED }.current())
        assertEquals(NetworkStatus.UNMETERED, DesktopNetworkStateProvider { WindowsNetworkCost.Cost.UNMETERED }.current())
        assertEquals(NetworkStatus.UNMETERED, DesktopNetworkStateProvider { WindowsNetworkCost.Cost.UNKNOWN }.current())
    }

    @Test
    fun theProviderReadsOnEveryCall() {
        var cost = WindowsNetworkCost.Cost.UNMETERED
        val provider = DesktopNetworkStateProvider { cost }
        assertEquals(NetworkStatus.UNMETERED, provider.current())
        cost = WindowsNetworkCost.Cost.METERED
        assertEquals(NetworkStatus.METERED, provider.current(), "a hotspot joined mid-session must show")
    }

    @Test
    fun theProductionProviderNeverReportsNone() {
        // On a Windows host this is a real `GetNetworkConnectivityHint`; elsewhere, the default.
        assertNotEquals(NetworkStatus.NONE, DesktopNetworkStateProvider().current())
    }
}
