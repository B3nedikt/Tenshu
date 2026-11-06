package org.tenshu.core.common.util

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HLCManager.seed] — the raise-only entry point TC-2 needs
 * (`docs/proposals/sync-hlc-tip-device-identity.md`).
 *
 * ⚠⚠ The only property that matters is that it **cannot lower the clock**. Everything TC-2 builds
 * on top assumes a device's published position never goes backwards; a seed that could lower would
 * re-create the exact backward step the track exists to remove, and would do it from inside the
 * mechanism meant to prevent it.
 */
class HlcManagerSeedTest {

    private lateinit var proxy: TestProxy

    @BeforeTest
    fun setup() {
        proxy = TestProxy(schemaRegistry = createCoreTestRegistry())
    }

    /** A frozen wall clock, so "did seed floor at now?" is answerable rather than timing-dependent. */
    private class FixedClock(private val nowMs: Long) : Clock {
        override fun nowEpochMillis(): Long = nowMs
    }

    private fun manager(nowMs: Long = 1_000L) =
        HLCManager("device-a", proxy.tContext, clock = FixedClock(nowMs))

    @Test
    fun seedRaisesAnUnsetClock() {
        val manager = manager()
        val seeded = manager.seed(500L, 3L)

        assertEquals(500L, seeded.physicalTime.get())
        assertEquals(3L, seeded.logicalCounter.get())
        assertEquals(500L, manager.current().physicalTime.get())
    }

    @Test
    fun seedNeverLowersPhysicalTime() {
        val manager = manager()
        manager.seed(500L, 0L)

        val result = manager.seed(400L, 9L)

        assertEquals(500L, result.physicalTime.get(), "a lower seed is ignored outright")
        assertEquals(0L, result.logicalCounter.get(), "including its counter")
        assertEquals(500L, manager.current().physicalTime.get())
    }

    @Test
    fun seedBreaksTiesOnTheCounter() {
        // Same millisecond, which is exactly when two runtimes on one device collide.
        val manager = manager()
        manager.seed(500L, 5L)

        assertEquals(5L, manager.seed(500L, 4L).logicalCounter.get(), "lower counter ignored")
        assertEquals(7L, manager.seed(500L, 7L).logicalCounter.get(), "higher counter adopted")
    }

    @Test
    fun seedDoesNotFloorAtNowTheWayUpdateDoes() {
        // `update()` folds in a remote event and floors at `now`, because ingesting a block IS a
        // local event. `seed()` must not: reading a watermark observes nothing, and inflating the
        // clock to `now` would make this device claim it applied tombstones it never saw — the
        // relay's GC quorum reads exactly this number.
        val manager = manager(nowMs = 9_000L)
        val seeded = manager.seed(500L, 0L)

        assertEquals(500L, seeded.physicalTime.get())
    }

    @Test
    fun seedThenTickStillProducesAForwardEvent() {
        val manager = manager(nowMs = 9_000L)
        manager.seed(500L, 0L)

        val ticked = manager.tick()

        assertEquals(9_000L, ticked.physicalTime.get(), "a local event still stamps at now")
    }
}
