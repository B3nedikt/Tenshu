package org.tenshu.inference.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DesktopIndexingConditions] over a scripted probe — the policy, not the machine. `MacPowerProbeTest`
 * pins the FFM calls against the real one.
 */
class DesktopIndexingConditionsTest {

    private class Probe(
        val power: Boolean? = true,
        val low: Boolean? = false,
        val idleFor: Double? = 600.0,
    ) : DesktopIndexingConditions.Probe {
        override fun onExternalPower() = power
        override fun batteryLow() = low
        override fun secondsSinceLastInput() = idleFor
    }

    @Test
    fun aPluggedInMacUntouchedForLongEnoughMayIndex() {
        assertTrue(DesktopIndexingConditions(idleSeconds = 300.0, probe = Probe(idleFor = 301.0)).allowIndexing())
    }

    /** ⚠⚠ Someone at the keyboard: a first index is hours of every core, under their hands. */
    @Test
    fun recentInputIsInUse() {
        assertFalse(DesktopIndexingConditions(idleSeconds = 300.0, probe = Probe(idleFor = 12.0)).allowIndexing())
    }

    @Test
    fun onBatteryIsNotReady() {
        assertFalse(DesktopIndexingConditions(probe = Probe(power = false)).allowIndexing())
    }

    /** ⚠⚠ Windows, Linux, or a probe that could not read the machine — unknown, so no. */
    @Test
    fun aMachineThatCannotBeReadNeverIndexes() {
        assertFalse(DesktopIndexingConditions(probe = Probe(power = null, low = null, idleFor = null)).allowIndexing())
        assertFalse(DesktopIndexingConditions(probe = Probe(idleFor = null)).allowIndexing())
    }

    /** IN-D6a: battery not low, in use and on battery alike — and an unreadable battery is a no. */
    @Test
    fun arrivalsNeedOnlyABatteryThatIsKnownNotLow() {
        assertTrue(DesktopIndexingConditions(probe = Probe(power = false, idleFor = 1.0)).allowArrivalIndexing())
        assertFalse(DesktopIndexingConditions(probe = Probe(low = true)).allowArrivalIndexing())
        assertFalse(DesktopIndexingConditions(probe = Probe(low = null)).allowArrivalIndexing(), "nobody asked: unknown is no")
        assertTrue(DesktopIndexingConditions(probe = Probe(low = null)).allowManualIndexing(), "unlike a press")
    }
}
