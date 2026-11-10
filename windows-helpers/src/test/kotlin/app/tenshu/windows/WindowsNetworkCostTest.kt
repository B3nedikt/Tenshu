package app.tenshu.windows

import app.tenshu.windows.NetworkCostDecoding.COST_FIXED
import app.tenshu.windows.NetworkCostDecoding.COST_UNKNOWN
import app.tenshu.windows.NetworkCostDecoding.COST_UNRESTRICTED
import app.tenshu.windows.NetworkCostDecoding.COST_VARIABLE
import app.tenshu.windows.WindowsNetworkCost.ConnectivityHint
import app.tenshu.windows.WindowsNetworkCost.Cost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cost mapping on every host; the real `GetNetworkConnectivityHint` on a Windows host only.
 *
 * ⚠ On Windows the availability assertion is **hard**: an unresolved binding answers UNKNOWN, which
 * the sync gate reads as today's unmetered default — a hotspot would silently look like Ethernet.
 */
class WindowsNetworkCostTest {

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun hint(cost: Int, approaching: Boolean = false, over: Boolean = false, roaming: Boolean = false) =
        ConnectivityHint(level = 3, cost = cost, approachingDataLimit = approaching, overDataLimit = over, roaming = roaming)

    @Test
    fun unrestrictedIsUnmetered_fixedAndVariableAreMetered() {
        assertEquals(Cost.UNMETERED, NetworkCostDecoding.decode(hint(COST_UNRESTRICTED)))
        assertEquals(Cost.METERED, NetworkCostDecoding.decode(hint(COST_FIXED)))
        assertEquals(Cost.METERED, NetworkCostDecoding.decode(hint(COST_VARIABLE)))
    }

    @Test
    fun aDataLimitOrRoamingIsMeteredWhateverTheCost() {
        for (cost in listOf(COST_UNKNOWN, COST_UNRESTRICTED)) {
            assertEquals(Cost.METERED, NetworkCostDecoding.decode(hint(cost, approaching = true)))
            assertEquals(Cost.METERED, NetworkCostDecoding.decode(hint(cost, over = true)))
            assertEquals(Cost.METERED, NetworkCostDecoding.decode(hint(cost, roaming = true)))
        }
    }

    @Test
    fun unknownOrUnlistedCostIsUnknown() {
        assertEquals(Cost.UNKNOWN, NetworkCostDecoding.decode(hint(COST_UNKNOWN)))
        assertEquals(Cost.UNKNOWN, NetworkCostDecoding.decode(hint(4)))
        assertEquals(Cost.UNKNOWN, NetworkCostDecoding.decode(hint(-1)))
    }

    @Test
    fun theLevelIsNotRead() {
        val offline = ConnectivityHint(level = 1, cost = COST_UNRESTRICTED, approachingDataLimit = false, overDataLimit = false, roaming = false)
        assertEquals(Cost.UNMETERED, NetworkCostDecoding.decode(offline))
    }

    @Test
    fun availableExactlyOnWindows() {
        // Windows 10 2004+ is the floor the port targets (`desktop-windows-port.md`).
        assertEquals(windows, WindowsNetworkCost.isAvailable())
        if (!windows) assertEquals(Cost.UNKNOWN, WindowsNetworkCost.current())
    }

    @Test
    fun theRealHintAnswersOnWindows() {
        if (!windows) return
        val raw = assertNotNull(WindowsNetworkCost.hint(), "GetNetworkConnectivityHint failed")
        // A mis-laid struct reads garbage; both enums are small closed sets.
        assertTrue(raw.level in 0..5, "ConnectivityLevel=${raw.level}")
        assertTrue(raw.cost in 0..3, "ConnectivityCost=${raw.cost}")
        println("WindowsNetworkCost on this host: raw=$raw current=${WindowsNetworkCost.current()}")
    }
}
