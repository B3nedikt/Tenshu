package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.health.DailyActivity
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cross-device half of the fold: a peer's block for a day this device already holds must
 * become the *union* of what both devices know, not a whole-row overwrite.
 */
class DailyActivityMergerTest {

    @Test
    fun mergeUnionsTheFieldsEachSideKnows() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // This device ran a Takeout import; the peer only has Health Connect's step count.
        val existing = DailyActivity.create(
            context, date = "2026-07-11",
            steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
        )
        val incoming = DailyActivity.create(context, date = "2026-07-11", steps = 7_500)

        val merged = DailyActivityMerger()
            .merge(testProxy.executionContext, existing, incoming) as DailyActivity

        assertEquals("2026-07-11", merged.date.get())
        assertEquals(7_500L, merged.steps.get())
        assertEquals(6_000L, merged.distanceM.get())
        assertEquals(1_200L, merged.activeEnergyKj.get())
        assertEquals(45L, merged.activeMinutes.get())
    }

    /** Symmetric case: the rich row arriving over the wire fills in a locally steps-only day. */
    @Test
    fun mergeFillsInLocallyMissingFieldsFromThePeer() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val existing = DailyActivity.create(context, date = "2026-07-11", steps = 7_500)
        val incoming = DailyActivity.create(
            context, date = "2026-07-11",
            steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
        )

        val merged = DailyActivityMerger()
            .merge(testProxy.executionContext, existing, incoming) as DailyActivity

        assertEquals(8_000L, merged.steps.get())
        assertEquals(6_000L, merged.distanceM.get())
    }

    /** Idempotent: replaying the same block must not change the row. */
    @Test
    fun mergingARowWithItselfIsANoOp() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val row = DailyActivity.create(
            context, date = "2026-07-11",
            steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
        )

        val merged = DailyActivityMerger()
            .merge(testProxy.executionContext, row, row) as DailyActivity

        assertEquals(8_000L, merged.steps.get())
        assertEquals(6_000L, merged.distanceM.get())
        assertEquals(1_200L, merged.activeEnergyKj.get())
        assertEquals(45L, merged.activeMinutes.get())
    }

    /**
     * Rows for different days are not the same entity — the merger must not silently fabricate a
     * blend. It defers to the incoming row rather than guessing.
     */
    @Test
    fun rowsForDifferentDaysAreNotBlended() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val existing = DailyActivity.create(context, date = "2026-07-10", distanceM = 6_000)
        val incoming = DailyActivity.create(context, date = "2026-07-11", steps = 7_500)

        val merged = DailyActivityMerger()
            .merge(testProxy.executionContext, existing, incoming) as DailyActivity

        assertEquals("2026-07-11", merged.date.get())
        assertEquals(0L, merged.distanceM.get())
    }
}
