package org.tenshu.health.client.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.health.RecordMedicalRecord
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType

internal class RecordMedicalRecordRo(
    requestChain: RequestChain,
    context: TContext
) : OperationRo(requestChain, context), RecordMedicalRecord {

    override fun recordMedicalRecord(medicalRecord: MedicalRecord): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    RecordMedicalRecord.Params.MEDICAL_RECORD to medicalRecord
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context
        )
    }
}
