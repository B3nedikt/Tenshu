package org.tenshu.core.common.util

import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.impl.UnixInstantDto
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeUnitCoherenceTest {

    @Test
    fun `HLC physicalTime and UnixInstant share the same epoch-millis unit`() {
        val fixedNow = 1_704_067_200_000L  // 2024-01-01T00:00:00Z
        val clock = FakeClock(fixedNow)
        val testProxy = TestProxy()
        val manager = HLCManager("device-1", testProxy.tContext, clock)

        val hlc = manager.tick()
        val instant = UnixInstantDto(clock.nowEpochMillis())

        assertEquals(
            hlc.physicalTime.get(),
            instant.toEpochMilliseconds(),
            "HLC.physicalTime and UnixInstant.toEpochMilliseconds() must use the same unit."
        )
    }
}
