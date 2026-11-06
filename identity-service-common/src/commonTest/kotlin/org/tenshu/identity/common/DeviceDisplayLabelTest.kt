package org.tenshu.identity.common

import org.tenshu.identity.deviceDisplayLabel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `deviceDisplayLabel` — the one join of `Device.name` and `DeviceHardware.hardwareName`, shared by
 * the Compose and iOS rosters. ⚠ The web twin, `formatDeviceLabel`, is pinned to the same cases in
 * `web/apex/src/control/devices/format.test.ts`; change both or neither.
 */
class DeviceDisplayLabelTest {

    @Test
    fun aKnownDistinctModelFollowsTheName() {
        assertEquals("Work Laptop · Mac OS X (aarch64)", deviceDisplayLabel("Work Laptop", "Mac OS X (aarch64)"))
    }

    @Test
    fun anUnknownModelLeavesTheNameAlone() {
        assertEquals("Work Laptop", deviceDisplayLabel("Work Laptop", null))
        assertEquals("Work Laptop", deviceDisplayLabel("Work Laptop", "  "))
    }

    /** The default label IS the model on Android, so most rows would otherwise read "X · X". */
    @Test
    fun aModelEqualToTheNameIsNotRepeated() {
        assertEquals("Google Pixel 8", deviceDisplayLabel("Google Pixel 8", "google pixel 8"))
    }

    @Test
    fun aBlankNameFallsBackToTheModel() {
        assertEquals("iPhone", deviceDisplayLabel(" ", "iPhone"))
    }
}
