package org.tenshu.core.common.util

import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.time.HybridLogicalClock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HLCTest {

    private lateinit var testProxy: TestProxy

    @BeforeTest
    fun setup() {
        testProxy = TestProxy()
    }

    @Test
    fun testMonotonicity() {
        val clock = FakeClock(1000L)
        val manager = HLCManager("device-1", testProxy.tContext, clock)
        val h1 = manager.tick()
        val h2 = manager.tick()
        clock.setTo(1001L)
        val h3 = manager.tick()

        assertTrue(h1.compare(h2) < 0, "h1 should be before h2 (logical counter)")
        assertTrue(h2.compare(h3) < 0, "h2 should be before h3 (physical time)")
        assertEquals(0, h1.logicalCounter.get())
        assertEquals(1, h2.logicalCounter.get())
        assertEquals(0, h3.logicalCounter.get())
    }

    @Test
    fun testUpdateFromRemote() {
        val clock = FakeClock(1500L)
        val manager = HLCManager("device-local", testProxy.tContext, clock)

        // Remote HLC is in the future
        val remoteHlc = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = 2000,
            logicalCounter = 5,
            deviceId = "device-remote"
        )

        val nextLocal = manager.update(remoteHlc)

        assertTrue(
            nextLocal.physicalTime.get() >= 2000,
            "Local physical time should catch up to remote"
        )
        assertTrue(nextLocal.compare(remoteHlc) > 0, "New local event should be after remote event")
    }

    @Test
    fun testTieBreakingWithDeviceId() {
        val h1 = HybridLogicalClock.create(testProxy.tContext, 1000, 0, "A")
        val h2 = HybridLogicalClock.create(testProxy.tContext, 1000, 0, "B")

        assertTrue(h1.compare(h2) < 0, "A should come before B")
    }
}
