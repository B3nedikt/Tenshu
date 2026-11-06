package org.tenshu.core.common.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.FakeClock
import org.tenshu.core.common.TestProxy
import org.tenshu.core.time.HybridLogicalClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HLCConcurrencyTest {

    @Test
    fun `tick is monotonic under concurrent callers`() = runTest {
        val testProxy = TestProxy()
        val clock = FakeClock(initial = 1_000_000L)
        val manager = HLCManager("device-1", testProxy.tContext, clock)

        val produced = java.util.concurrent.ConcurrentLinkedQueue<HybridLogicalClock>()
        coroutineScope {
            repeat(100) {
                launch(Dispatchers.Default) {
                    repeat(100) { produced += manager.tick() }
                }
            }
        }

        val ordered = produced.toList()
        assertEquals(10_000, ordered.size)

        // Every (physical, logical) pair must be unique on a single device — the
        // CAS loop makes this true; without it, racing tick() calls produce duplicates.
        val seen = HashSet<Pair<Long, Long>>()
        ordered.forEach { hlc ->
            val key = hlc.physicalTime.get() to hlc.logicalCounter.get()
            assertTrue(seen.add(key), "Duplicate (physical, logical) pair: $key")
        }
    }

    @Test
    fun `tick stays monotonic when wall clock jumps backwards`() {
        val testProxy = TestProxy()
        val clock = FakeClock(1_000_000L)
        val manager = HLCManager("device-1", testProxy.tContext, clock)

        val first = manager.tick()
        clock.setTo(900_000L)  // NTP correction, leap second, traveler's laptop
        val second = manager.tick()

        assertTrue(
            second.compare(first) > 0,
            "HLC must remain monotonic even when wall clock jumps backwards."
        )
    }

    @Test
    fun `update returns an HLC strictly greater than the remote one`() {
        val testProxy = TestProxy()
        val clock = FakeClock(1_000_000L)
        val manager = HLCManager("device-A", testProxy.tContext, clock)

        val remote = HybridLogicalClock.create(testProxy.tContext, 1_000_000L, 5L, "device-B")
        val local = manager.update(remote)

        assertTrue(local.compare(remote) > 0)
        assertEquals("device-A", local.deviceId.get())
    }
}
