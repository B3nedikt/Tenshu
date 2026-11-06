package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.MedicalRecord
import org.tenshu.core.schema.CompositeSchema

internal class MedicalRecordDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), MedicalRecord {

    override val schema: CompositeSchema get() = MedicalRecord.Schema
}
