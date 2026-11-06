package org.tenshu.health.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.RecordMedicalRecord

internal class RecordMedicalRecordImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val medicalRecord: MedicalRecord = params.read(RecordMedicalRecord.Params.MEDICAL_RECORD)

        // Persist via `ExecutionContext.saveData` — fires `SyncObserver` (outbound SyncBlock)
        // and `MedicalRecordRelationMaintainer` (adds to `HealthService.Relations.MEDICAL_RECORDS`).
        // Never `Database.saveData` / `addDataToArray`, which bypass `SyncObserver`.
        executionContext.saveData(medicalRecord)

        return null as T
    }
}
