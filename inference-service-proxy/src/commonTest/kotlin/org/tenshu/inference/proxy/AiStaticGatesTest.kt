package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.AiStaticGates.Decision
import org.tenshu.inference.proxy.AiStaticGates.Device
import org.tenshu.inference.proxy.AiStaticGates.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The static gates (`ios-on-device-inference.md` §7 D12 ⓪ and ①, and PR-5's build flag): decided
 * once, before anything is offered.
 */
class AiStaticGatesTest {

    private val floor = AiStaticGates.PLACEHOLDER_MIN_PHYSICAL_MEMORY_BYTES
    private val measured = Device(osMajorVersion = 26, physicalMemoryBytes = 8L * 1024 * 1024 * 1024)

    @Test
    fun withTheFlagOffNothingIsAdmittedAndThePhoneIsNotAsked() {
        var asked = 0
        val decision = AiStaticGates.decide(buildFlag = false) {
            asked++
            measured
        }
        assertEquals(Decision.Refused(Reason.BUILD_FLAG_OFF), decision)
        assertEquals(0, asked, "a build with the flag off read the phone")
    }

    @Test
    fun aPhoneBelowIos17IsRefusedWhateverItsMemory() {
        assertEquals(
            Decision.Refused(Reason.OS_BELOW_FLOOR),
            AiStaticGates.decide(true) { measured.copy(osMajorVersion = 16) },
        )
        assertEquals(Decision.Admitted, AiStaticGates.decide(true) { measured.copy(osMajorVersion = 17) })
    }

    @Test
    fun aPhoneBelowTheMemoryThresholdIsRefused() {
        assertEquals(
            Decision.Refused(Reason.MEMORY_BELOW_THRESHOLD),
            AiStaticGates.decide(true) { measured.copy(physicalMemoryBytes = floor - 1) },
        )
        assertEquals(Decision.Admitted, AiStaticGates.decide(true) { measured.copy(physicalMemoryBytes = floor) })
    }

    /** What the placeholder is argued from. ⚠ It pins the argument, not a measurement. */
    @Test
    fun thePlaceholderThresholdAdmitsNoSixGigabytePhone() {
        val sixGigabytes = 6L * 1024 * 1024 * 1024
        assertTrue(floor > sixGigabytes, "a 6 GB phone, which nobody measured, would be admitted")
        assertTrue(floor < 8L * 1024 * 1024 * 1024, "the one memory class that was measured would be refused")
    }
}
