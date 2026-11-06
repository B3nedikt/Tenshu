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
import org.tenshu.core.health.HealthSample
import org.tenshu.core.health.HealthService
import org.tenshu.core.health.RecordHealthSample
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordHealthSampleUnitTest {

    @Test
    fun recordHealthSampleRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val sample = HealthSample.create(
            context = context,
            metric = "Weight",
            value = "74.2",
            unit = "kg",
            time = UnixInstantDto(1_800_000_000_000L),
            source = "HealthConnect",
            resting = false,
        )

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(RecordHealthSample.ID),
                *testProxy.serializer.serialize(sample),
            )
        )
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.HEALTH_SAMPLES),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val samples = (valueUpdates.first().payload as TList<HealthSample>).toList()
        assertEquals(1, samples.size)

        val received = samples.first()
        assertEquals("Weight", received.metric.get())
        assertEquals("74.2", received.value.get())
        assertEquals("kg", received.unit.get())
        assertEquals(false, received.resting.get())
    }

    @Test
    fun distinctTimesOfSameMetricRetainAsSeparateRows() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // §12 B2: readings of the same metric at *different* instants accrue as separate rows (the
        // time-series the Body/Vitals detail charts) — they no longer fold to one latest.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordHealthSample.ID),
                *testProxy.serializer.serialize(
                    HealthSample.create(context, "HeartRate", "70", "bpm", UnixInstantDto(1_000L), "Manual", resting = true),
                ),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordHealthSample.ID),
                *testProxy.serializer.serialize(
                    HealthSample.create(context, "HeartRate", "62", "bpm", UnixInstantDto(2_000L), "HealthConnect", resting = true),
                ),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.HEALTH_SAMPLES),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val samples = (valueUpdates.last().payload as TList<HealthSample>).toList()
        assertEquals(2, samples.size)
        assertEquals(setOf("70", "62"), samples.map { it.value.get() }.toSet())
    }

    @Test
    fun reRecordingSameMetricAndInstantFoldsToOne() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        // A re-import of the *same* reading (identical metric + instant → identical sampleId) folds
        // to one row, the newer value winning — idempotent re-import.
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordHealthSample.ID),
                *testProxy.serializer.serialize(
                    HealthSample.create(context, "HeartRate", "70", "bpm", UnixInstantDto(1_000L), "Manual", resting = true),
                ),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordHealthSample.ID),
                *testProxy.serializer.serialize(
                    HealthSample.create(context, "HeartRate", "62", "bpm", UnixInstantDto(1_000L), "HealthConnect", resting = true),
                ),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.HEALTH_SAMPLES),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val samples = (valueUpdates.last().payload as TList<HealthSample>).toList()
        assertEquals(1, samples.size)
        assertEquals("62", samples.first().value.get())
    }
}
