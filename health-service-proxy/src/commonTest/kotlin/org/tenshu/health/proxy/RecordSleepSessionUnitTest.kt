package org.tenshu.health.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.StreamElement
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.Stream
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.UnixInstantDto
import org.tenshu.core.common.util.idToUuid
import org.tenshu.core.common.util.toByteArray
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordSleepSession
import org.tenshu.core.health.SleepSession
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordSleepSessionUnitTest {

    @Test
    fun recordSleepSessionRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val sleepSession = SleepSession.create(
            context = context,
            date = "2026-05-24",
            start = UnixInstantDto(1_800_000_000_000L),
            end = UnixInstantDto(1_800_000_000_000L + (7 * 3600 + 12 * 60) * 1_000L),
            durationSec = (7 * 3600 + 12 * 60).toLong(),
            source = "HealthConnect",
            awakeMin = 14,
            remMin = 96,
            lightMin = 232,
            deepMin = 90,
        )

        val request = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(RecordSleepSession.ID),
            *testProxy.serializer.serialize(sleepSession),
        )

        val resultBytes = testProxy.handleRequest(request)
        assertContentEquals(
            expected = byteArrayOf(*idToUuid(TResult.Success.ID)),
            actual = resultBytes,
        )

        // Read back the sleepSessions relation stream.
        val flowId = 1L
        val sleepSessionsRequest = byteArrayOf(
            *idToUuid(HealthService.ID),
            *idToUuid(HealthService.Relations.SLEEP_SESSIONS),
            *idToUuid(Stream.ID),
            *flowId.toByteArray(),
        )
        testProxy.handleRequest(sleepSessionsRequest)

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val sessions = (valueUpdates.first().payload as TList<SleepSession>).toList()
        assertEquals(1, sessions.size)

        val received = sessions.first()
        assertEquals("2026-05-24", received.date.get())
        assertEquals((7 * 3600 + 12 * 60).toLong(), received.durationSec.get())
        assertEquals("HealthConnect", received.source.get())
        assertEquals(96L, received.remMin.get())
        assertEquals(90L, received.deepMin.get())
    }

    @Test
    fun reRecordingSameNightFoldsToOneRow() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // Two records for the same night (a re-import) must fold to one row via the date key.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordSleepSession.ID),
                *testProxy.serializer.serialize(
                    SleepSession.create(
                        context, date = "2026-05-24",
                        start = UnixInstantDto(1_000L), end = UnixInstantDto(2_000L),
                        durationSec = 1, source = "Manual", deepMin = 10,
                    ),
                ),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordSleepSession.ID),
                *testProxy.serializer.serialize(
                    SleepSession.create(
                        context, date = "2026-05-24",
                        start = UnixInstantDto(1_000L), end = UnixInstantDto(2_000L),
                        durationSec = 25_920, source = "HealthConnect", deepMin = 90,
                    ),
                ),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.SLEEP_SESSIONS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val sessions = (valueUpdates.last().payload as TList<SleepSession>).toList()
        assertEquals(1, sessions.size)
        assertEquals(90L, sessions.first().deepMin.get())
    }
}
