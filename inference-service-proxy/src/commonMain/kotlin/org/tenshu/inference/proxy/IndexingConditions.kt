package org.tenshu.inference.proxy

/**
 * Whether the device is in a state a library index pass may work in right now: **on external
 * power, battery not low, and not in use** (IN-D6, `inference-service.md` §26.2 and §32).
 *
 * ⚠⚠ **The scheduler does not enforce this, which is why the pass asks for itself.**
 * `registerMediaIndexSchedule` declares charging + device-idle + battery-not-low, but
 * `ScheduleDrainLoop` stores a `ConstraintSpec` without enforcing it — only Android's WorkManager
 * honours one, and only on the wakes it drives. While the proxy is alive, and always on desktop, the
 * loop dispatches the pass on its first tick: without this check, opening Control would start a
 * multi-hour run of a neural network over every photograph, on battery, under the user's hands.
 *
 * ⚠ Read **per image**, like the pause: unplugging mid-pass has to stop the pass at the next photo,
 * not after the library.
 *
 * ⚠⚠ Platform-provided, and **absent means never** ([Never]) — the `supportedModelRuntimes` shape. A
 * platform that cannot tell whether it is plugged in and unattended does not index.
 */
fun interface IndexingConditions {
    fun allowIndexing(): Boolean

    /**
     * Whether a pass the **user just asked for** may work (§34's *Index now*).
     *
     * ⚠⚠ "Not in use" cannot be required here — they are holding the device, which is how they
     * pressed the button. What remains is the power question: a low battery is still a no, because a
     * neural network over a whole library is not something to do at 8 %.
     *
     * ⚠ The default is `true`, the opposite of [allowIndexing]'s fail-closed absence, and
     * deliberately: silence means "this platform cannot read the device's state", and an explicit
     * request from the person holding it outranks a signal nobody can produce. The pass is pausable
     * and reports progress, so the user can see it and stop it.
     */
    fun allowManualIndexing(): Boolean = true

    /**
     * Whether a **new-arrivals batch** may work (IN-D6a): a small batch of photographs no pass has
     * seen, started by their arrival rather than by a person or the 12 h schedule.
     *
     * ⚠⚠ Its own bar, between the other two. Battery not low, as for a press — but nobody asked, so
     * the press's "unknown is allowed" does not carry over: a platform that cannot read its battery
     * does not start unasked work. And never the scheduled bar loosened: the 12 h pass keeps
     * charging + not in use, because it is the one that may run for hours.
     *
     * ⚠⚠ The default is `false` — absent means never, [allowIndexing]'s rule. A platform that wants
     * arrivals indexed says so.
     */
    fun allowArrivalIndexing(): Boolean = false

    companion object {
        /**
         * ⚠⚠ An explicit object, NOT `IndexingConditions { false }`. A SAM lambda implements
         * [allowIndexing] only and inherits [allowManualIndexing]'s `true` default — so the
         * fail-closed sentinel would have let a *pressed* pass run a neural network over the whole
         * library on battery, on exactly the platform whose wiring forgot to contribute conditions.
         * The default is right for a platform that answers the question and cannot tell; it is wrong
         * for one that was never asked.
         */
        val Never: IndexingConditions = object : IndexingConditions {
            override fun allowIndexing(): Boolean = false
            override fun allowManualIndexing(): Boolean = false
            override fun allowArrivalIndexing(): Boolean = false
        }
    }
}

/**
 * The shared policy over whatever a platform can observe. ⚠⚠ Every unknown is a **no**: a signal the
 * platform could not read is not evidence that the device is plugged in and unattended.
 *
 * @param onExternalPower charging, or a machine with no battery at all.
 * @param batteryLow the OS's own low-battery state — a device on a weak charger can still discharge,
 *   and this is the workload that would win that race.
 * @param inUse someone is using the device: the screen is on, or there was input recently.
 */
fun indexingConditionsAllow(onExternalPower: Boolean?, batteryLow: Boolean?, inUse: Boolean?): Boolean =
    onExternalPower == true && batteryLow == false && inUse == false
