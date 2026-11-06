package org.tenshu.core.health

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Records (upserts) a [MedicalRecord]. Idempotent by [MedicalRecord.recordId]. The record is
 * read-only *in the Fit UI* (v1 never authors one); this op is the **import** write path — the
 * FHIR / Health-Connect import seam (and the seeder, which stands in for it) calls it. Persists
 * via `ExecutionContext.saveData` → syncs eagerly to every paired device.
 *
 * House Rule #3: the operation method is non-suspend and returns a `RequestObject`; the async
 * gap is crossed only here in the DSL extension via `asTaskInteractor`.
 */
suspend fun HealthService.recordMedicalRecord(medicalRecord: MedicalRecord): TResult<Data?> =
    findOperation<RecordMedicalRecord>(RecordMedicalRecord.ID)
        .recordMedicalRecord(medicalRecord)
        .asTaskInteractor<Data?>()
        .execute()

interface RecordMedicalRecord : Operation {

    override val schema: OperationSchema get() = Schema

    fun recordMedicalRecord(medicalRecord: MedicalRecord): RequestObject

    companion object {
        const val ID = "tenshu/health/service/recordMedicalRecord"

        val Schema = operation(
            id = ID,
            receiver = HealthService.Schema.toType(),
            params = {
                composite(id = Params.MEDICAL_RECORD, type = MedicalRecord.Schema)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MEDICAL_RECORD = "$ID/medicalRecord"
    }
}
