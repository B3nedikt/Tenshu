package org.tenshu.inference.proxy

import app.tenshu.macos.MacPowerProbe

/**
 * Desktop [IndexingConditions]: on external power, no low-battery warning, and no input for
 * [idleSeconds].
 *
 * ⚠⚠ **Not the Synced Folders stance.** `DesktopBackupEnvironment` treats a desktop as always wired,
 * which is fine for an upload that finishes in seconds; a first index is hours of every core on a
 * laptop someone is typing on. So this reads the machine: IOKit for power, the HID idle timer for
 * use (`MacPowerProbe`).
 *
 * ⚠⚠ **macOS only, and every other desktop never indexes.** Windows and Linux have no probe here, and
 * unknown reads as no — a platform that cannot tell whether someone is at the keyboard does not
 * start a multi-hour job under their hands.
 */
class DesktopIndexingConditions(
    private val idleSeconds: Double = DEFAULT_IDLE_SECONDS,
    private val probe: Probe = MacProbe,
) : IndexingConditions {

    interface Probe {
        fun onExternalPower(): Boolean?
        fun batteryLow(): Boolean?
        fun secondsSinceLastInput(): Double?
    }

    override fun allowIndexing(): Boolean = indexingConditionsAllow(
        onExternalPower = probe.onExternalPower(),
        batteryLow = probe.batteryLow(),
        inUse = probe.secondsSinceLastInput()?.let { it < idleSeconds },
    )

    /**
     * ⚠ Neither AC power nor idleness is required — the user asked, at the keyboard. ⚠⚠ A laptop on
     * a low battery is still refused, and an unreadable machine (Windows, Linux) is **allowed**: for
     * a manual run, "I cannot tell" must not be the same answer as "no".
     */
    override fun allowManualIndexing(): Boolean = probe.batteryLow() != true

    /**
     * IN-D6a's new-arrivals batch: no low-battery warning, and ⚠⚠ an unreadable machine is **not**
     * allowed — nobody asked. A Mac answers (a desktop Mac with no battery reads "not low"); Windows
     * and Linux index arrivals never, as they index on the schedule never.
     */
    override fun allowArrivalIndexing(): Boolean = probe.batteryLow() == false

    private object MacProbe : Probe {
        override fun onExternalPower() = MacPowerProbe.onExternalPower()
        override fun batteryLow() = MacPowerProbe.batteryLow()
        override fun secondsSinceLastInput() = MacPowerProbe.secondsSinceLastInput()
    }

    companion object {
        /** Five minutes without input — long enough that a pause to read is not "away". */
        const val DEFAULT_IDLE_SECONDS: Double = 5.0 * 60
    }
}
