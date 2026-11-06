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
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.RecordMedicalRecord
import org.tenshu.health.test.createHealthProxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordMedicalRecordUnitTest {

    @Test
    fun recordMedicalRecordRoundTrips() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        val record = MedicalRecord.create(
            context = context,
            recordId = "fhir-med-1",
            kind = "Medication",
            title = "Cetirizine",
            detail = "10 mg tablet · once daily",
            effectiveDate = "2026-03-02",
            issuer = "Dr. A. Neumann · Charité",
            source = "HealthConnect",
        )

        val resultBytes = testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID),
                *idToUuid(RecordMedicalRecord.ID),
                *testProxy.serializer.serialize(record),
            )
        )
        assertContentEquals(byteArrayOf(*idToUuid(TResult.Success.ID)), resultBytes)

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.MEDICAL_RECORDS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        assertTrue(valueUpdates.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val records = (valueUpdates.first().payload as TList<MedicalRecord>).toList()
        assertEquals(1, records.size)

        val received = records.first()
        assertEquals("fhir-med-1", received.recordId.get())
        assertEquals("Medication", received.kind.get())
        assertEquals("Cetirizine", received.title.get())
        assertEquals("2026-03-02", received.effectiveDate.get())
    }

    @Test
    fun reRecordingSameIdFoldsToOneRow() = runTest {
        val testProxy = createHealthProxy(this)
        val context = testProxy.executionContext.context

        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordMedicalRecord.ID),
                *testProxy.serializer.serialize(
                    MedicalRecord.create(context, "fhir-1", "Condition", "Old title", effectiveDate = "2026-01-01", source = "Fit"),
                ),
            )
        )
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(RecordMedicalRecord.ID),
                *testProxy.serializer.serialize(
                    MedicalRecord.create(context, "fhir-1", "Condition", "New title", effectiveDate = "2026-01-01", source = "Fit"),
                ),
            )
        )

        val flowId = 1L
        testProxy.handleRequest(
            byteArrayOf(
                *idToUuid(HealthService.ID), *idToUuid(HealthService.Relations.MEDICAL_RECORDS),
                *idToUuid(Stream.ID), *flowId.toByteArray(),
            )
        )

        val valueUpdates = testProxy.updates.filterIsInstance<StreamElement.Value<*>>()
            .filter { it.streamId.get() == flowId }
        @Suppress("UNCHECKED_CAST")
        val records = (valueUpdates.last().payload as TList<MedicalRecord>).toList()
        assertEquals(1, records.size)
        assertEquals("New title", records.first().title.get())
    }
}
