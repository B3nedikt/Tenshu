package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.DailyActivity
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordDailyActivity
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordDailyActivityUnitTest {

    @Test
    fun recordDailyActivityRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val dailyActivity = DailyActivity.create(
            context = context,
            date = "2026-05-24",
            steps = 6_120,
            distanceM = 4_500,
            activeEnergyKj = 1_940,
            activeMinutes = 42,
        )

        val request = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(RecordDailyActivity.ID),
            *testProxy.serializer.serialize(dailyActivity),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        // Read back the dailyActivities relation stream.
        val flowId = 1L
        val dailyActivitiesRequest = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(HealthService.Relations.DAILY_ACTIVITIES),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(dailyActivitiesRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val activities = (valueUpdates.first().payload as TList<DailyActivity>).toList()
        assertEquals(1, activities.size)

        val received = activities.first()
        assertEquals("2026-05-24", received.date.get())
        assertEquals(6_120L, received.steps.get())
        assertEquals(4_500L, received.distanceM.get())
        assertEquals(42L, received.activeMinutes.get())
    }

    @Test
    fun reRecordingSameDayFoldsToOneRow() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // Two records for the same day (a re-import) must fold to one row via the date key.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(DailyActivity.create(context, date = "2026-05-24", steps = 100)),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(DailyActivity.create(context, date = "2026-05-24", steps = 6_120)),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.DAILY_ACTIVITIES),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val activities = (valueUpdates.last().payload as TList<DailyActivity>).toList()
        assertEquals(1, activities.size)
        assertEquals(6_120L, activities.first().steps.get())
    }

    /**
     * The clobber this fold exists for: a Takeout import supplies a day's distance / energy /
     * active minutes, then a Health Connect refresh re-records the same day carrying **only**
     * steps (on a device where nothing writes DistanceRecord or TotalCaloriesBurnedRecord, the
     * importer maps both to 0). Whole-row replace reverted the day to "0 m, 0 kcal".
     */
    @Test
    fun aStepsOnlyReRecordKeepsTheFieldsItsSourceCannotReport() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // A rich import (Takeout): every stat populated.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(
                    DailyActivity.create(
                        context, date = "2026-07-11",
                        steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
                    )
                ),
            )
        )
        // A steps-only refresh (Health Connect) for the same day.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(
                    DailyActivity.create(context, date = "2026-07-11", steps = 7_500),
                ),
            )
        )

        val day = testProxy.readSingleDailyActivity(flowId = 1L)
        // The live figure wins where the new source actually reported one...
        assertEquals(7_500L, day.steps.get())
        // ...and everything it left at 0 survives.
        assertEquals(6_000L, day.distanceM.get())
        assertEquals(1_200L, day.activeEnergyKj.get())
        assertEquals(45L, day.activeMinutes.get())
    }

    /** A row with nothing populated at all must not blank the day. */
    @Test
    fun anAllZeroReRecordErasesNothing() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(
                    DailyActivity.create(
                        context, date = "2026-07-11",
                        steps = 8_000, distanceM = 6_000, activeEnergyKj = 1_200, activeMinutes = 45,
                    )
                ),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(DailyActivity.create(context, date = "2026-07-11")),
            )
        )

        val day = testProxy.readSingleDailyActivity(flowId = 1L)
        assertEquals(8_000L, day.steps.get())
        assertEquals(6_000L, day.distanceM.get())
        assertEquals(1_200L, day.activeEnergyKj.get())
        assertEquals(45L, day.activeMinutes.get())
    }

    /** A day nothing has written yet is stored verbatim — the fold is a no-op on first write. */
    @Test
    fun theFirstRecordForADayIsStoredVerbatim() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyActivity.ID),
                *testProxy.serializer.serialize(
                    DailyActivity.create(context, date = "2026-07-11", steps = 8_000),
                ),
            )
        )

        val day = testProxy.readSingleDailyActivity(flowId = 1L)
        assertEquals(8_000L, day.steps.get())
        assertEquals(0L, day.distanceM.get())
    }
}

/**
 * Reads the `dailyActivities` relation and asserts it holds exactly one row — the shape every
 * fold assertion wants, since the whole point of the date key is that a day is one row.
 */
private suspend fun TestProxy.readSingleDailyActivity(flowId: Long): DailyActivity {
    handleRequest(
        byteArrayOf(
            *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.DAILY_ACTIVITIES),
            *idToUuid(Stream.ID), *flowId.toByteArray(),
        )
    )
    val valueUpdates = updates.filterIsInstance<StreamElement.Value<*>>()
        .filter { it.streamId.get() == flowId }
    assertTrue(valueUpdates.isNotEmpty(), "no dailyActivities emission")

    @Suppress("UNCHECKED_CAST")
    val activities = (valueUpdates.last().payload as TList<DailyActivity>).toList()
    assertEquals(1, activities.size, "expected the date key to fold the day to one row")
    return activities.first()
}
