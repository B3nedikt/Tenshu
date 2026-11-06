package org.tenshu.inference.proxy

/**
 * The facts about a device that cannot change inside a process, decided **before** anything is
 * offered or bound (`ios-on-device-inference.md` §3.2, §7 D12 ⓪ and ①).
 *
 * A device that fails here gets no backend, no readiness state and no route to one: search keeps
 * its *"isn't available on this iPhone yet"* line and the proxy is never asked. What can change
 * while the process runs is [AiReadiness], and only a device admitted here has one.
 */
object AiStaticGates {

    /**
     * The fp16 artifact is an ML Program converted with an iOS 17 deployment target (§3.4): a
     * phone below it could download ~709 MB and then fail to open it.
     */
    const val MIN_OS_MAJOR: Int = 17

    /**
     * ⚠⚠ **A PLACEHOLDER, not a measurement.** D12 ① wants this set from `phys_footprint` of a
     * pass through the backend with both sessions open, on a phone at the intended floor; neither
     * reading exists (D10 and D12 are open, `ios-on-device-inference.md` §7).
     *
     * Until then it admits only the memory class that was measured at all. Every figure in the
     * plan comes from one phone with 8 GB, and D12 lowers the floor only by measuring a phone at
     * the new floor. 6.5 GiB sits between the classes: `physicalMemory` never reports more than a
     * phone's nominal RAM, so no 6 GB phone reaches it, and it leaves 1.5 GiB for what an 8 GB
     * phone's kernel keeps back. ⚠ That an 8 GB iPhone reports above it is inferred, not read off
     * the phone.
     */
    const val PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES: Long = 6_979_321_856L // 6.5 GiB

    /** What `NSProcessInfo` says about this phone. */
    data class Device(val osMajorVersion: Int, val physicalMemoryBytes: Long)

    sealed interface Decision {
        data object Admitted : Decision

        data class Refused(val reason: Reason) : Decision
    }

    enum class Reason { BUILD_FLAG_OFF, OS_BELOW_FLOOR, MEMORY_BELOW_THRESHOLD }

    /**
     * @param device asked only when [buildFlag] is on: a build with the flag off reads nothing
     *   about the phone and behaves as a build without this code.
     */
    fun decide(buildFlag: Boolean, device: () -> Device): Decision {
        if (!buildFlag) return Decision.Refused(Reason.BUILD_FLAG_OFF)
        val phone: Device = device()
        if (phone.osMajorVersion < MIN_OS_MAJOR) return Decision.Refused(Reason.OS_BELOW_FLOOR)
        if (phone.physicalMemoryBytes < PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES) {
            return Decision.Refused(Reason.MEMORY_BELOW_THRESHOLD)
        }
        return Decision.Admitted
    }
}
