package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.DailyNutrition
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordDailyNutrition
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordDailyNutritionUnitTest {

    @Test
    fun recordDailyNutritionRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val dailyNutrition = DailyNutrition.create(
            context = context,
            date = "2026-05-24",
            energyKj = 8_400,
            hydrationMl = 2_100,
        )

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(RecordDailyNutrition.ID),
                *testProxy.serializer.serialize(dailyNutrition),
            )
        )
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.DAILY_NUTRITIONS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val nutritions = (valueUpdates.first().payload as TList<DailyNutrition>).toList()
        assertEquals(1, nutritions.size)

        val received = nutritions.first()
        assertEquals("2026-05-24", received.date.get())
        assertEquals(8_400L, received.energyKj.get())
        assertEquals(2_100L, received.hydrationMl.get())
    }

    @Test
    fun reRecordingSameDayFoldsToOneRow() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // Two records for the same day (a re-import) fold to one row via the date key.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyNutrition.ID),
                *testProxy.serializer.serialize(DailyNutrition.create(context, date = "2026-05-24", energyKj = 100)),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordDailyNutrition.ID),
                *testProxy.serializer.serialize(DailyNutrition.create(context, date = "2026-05-24", energyKj = 8_400)),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.DAILY_NUTRITIONS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val nutritions = (valueUpdates.last().payload as TList<DailyNutrition>).toList()
        assertEquals(1, nutritions.size)
        assertEquals(8_400L, nutritions.first().energyKj.get())
    }
}
