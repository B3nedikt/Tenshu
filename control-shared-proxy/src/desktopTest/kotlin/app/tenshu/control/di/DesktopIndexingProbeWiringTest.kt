package app.tenshu.control.di

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Which probe Control's desktop graph hands `DesktopIndexingConditions`. ⚠ A Windows host with no
 * probe reads every fact as unknown, and unknown is "no": scheduled indexing would never run there
 * and nothing would fail. So on a Windows host the real probe must also answer.
 */
class DesktopIndexingProbeWiringTest {

    @Test
    fun windowsGetsTheWindowsProbe_everyOtherOsKeepsTheDefault() {
        assertSame(WindowsIndexingProbe, windowsIndexingProbeFor("Windows 11"))
        assertSame(WindowsIndexingProbe, windowsIndexingProbeFor("Windows Server 2022"))
        assertNull(windowsIndexingProbeFor("Mac OS X"))
        assertNull(windowsIndexingProbeFor("Linux"))
    }

    /**
     * ⚠ This suite runs on the module's JDK-17 toolchain, which has no final FFM: there the probe must
     * *load* and answer unknown (the W2 class-init rule), and only an FFM JDK — the desktop apps'
     * bundled runtime — must answer for real. `WindowsPowerProbeTest` pins the real calls on JDK 25.
     */
    @Test
    fun onAWindowsHostTheProbeLoads_andAnswersWhereverFfmExists() {
        val os = System.getProperty("os.name").orEmpty()
        val probe = windowsIndexingProbeFor(os) ?: return
        if (Runtime.version().feature() >= FINAL_FFM_JDK) {
            assertNotNull(probe.onExternalPower(), "GetSystemPowerStatus did not answer on $os")
            assertNotNull(probe.batteryLow(), "battery state did not answer on $os")
            assertNotNull(probe.secondsSinceLastInput(), "GetLastInputInfo did not answer on $os")
        } else {
            assertNull(probe.onExternalPower())
            assertNull(probe.secondsSinceLastInput())
        }
        // The graph builds the conditions over the same probe without throwing.
        desktopIndexingConditions(os).allowManualIndexing()
    }

    private companion object {
        const val FINAL_FFM_JDK = 22
    }
}
