package app.tenshu.macos

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MacPowerProbe] against the real machine.
 *
 * ⚠ What a test can pin is that each call **resolves and returns a sane value** — the symbol names,
 * the framework paths, and the scalar signatures. A wrong descriptor does not throw on a downcall;
 * it returns garbage, which is why the idle timer is checked for a plausible range rather than for
 * non-null alone. Whether this Mac is plugged in right now is not something a test can assert.
 *
 * ⚠ Off macOS each answer is pinned as `null` — the documented unknown every caller reads as no —
 * rather than skipped, the `MacDatalessProbeTest` shape.
 */
class MacPowerProbeTest {

    /** Mirrors [MacPowerProbe]'s own private `isMac`; spelled the same way on purpose. */
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

    @Test
    fun powerStateResolves() {
        if (mac && ForeignSupport.isAvailable()) {
            assertNotNull(MacPowerProbe.onExternalPower(), "IOPSGetTimeRemainingEstimate did not resolve")
            assertNotNull(MacPowerProbe.batteryLow(), "IOPSGetBatteryWarningLevel did not resolve")
        } else {
            assertNull(MacPowerProbe.onExternalPower())
            assertNull(MacPowerProbe.batteryLow())
        }
    }

    /**
     * ⚠⚠ A plausible range, not just non-null: a mis-declared return type reads a register the call
     * never wrote, and "some double" would pass. A machine running this test has had input within a
     * week.
     */
    @Test
    fun theIdleTimerReadsAPlausibleNumberOfSeconds() {
        val seconds = MacPowerProbe.secondsSinceLastInput()
        if (mac && ForeignSupport.isAvailable()) {
            assertNotNull(seconds, "CGEventSourceSecondsSinceLastEventType did not resolve")
            assertTrue(seconds in 0.0..(7 * 24 * 3600.0), "implausible idle time: $seconds")
        } else {
            assertNull(seconds)
        }
    }
}
