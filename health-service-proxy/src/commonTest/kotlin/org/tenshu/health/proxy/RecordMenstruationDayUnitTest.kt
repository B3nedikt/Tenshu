package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.MenstruationDay
import org.tenshu.core.health.RecordMenstruationDay
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordMenstruationDayUnitTest {

    @Test
    fun recordMenstruationDayRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val menstruationDay = MenstruationDay.create(
            context = context,
            date = "2026-05-10",
            flow = "Medium",
            periodStart = true,
        )

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(RecordMenstruationDay.ID),
                *testProxy.serializer.serialize(menstruationDay),
            )
        )
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.MENSTRUATION_DAYS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val days = (valueUpdates.first().payload as TList<MenstruationDay>).toList()
        assertEquals(1, days.size)

        val received = days.first()
        assertEquals("2026-05-10", received.date.get())
        assertEquals("Medium", received.flow.get())
        assertEquals(true, received.periodStart.get())
    }

    @Test
    fun reRecordingSameDayFoldsToOneRow() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordMenstruationDay.ID),
                *testProxy.serializer.serialize(MenstruationDay.create(context, date = "2026-05-10", flow = "Light")),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordMenstruationDay.ID),
                *testProxy.serializer.serialize(MenstruationDay.create(context, date = "2026-05-10", flow = "Heavy")),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.MENSTRUATION_DAYS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val days = (valueUpdates.last().payload as TList<MenstruationDay>).toList()
        assertEquals(1, days.size)
        assertEquals("Heavy", days.first().flow.get())
    }
}
