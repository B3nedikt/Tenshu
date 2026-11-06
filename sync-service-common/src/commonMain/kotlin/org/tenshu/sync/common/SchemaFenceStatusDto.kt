package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.sync.SchemaFenceStatus

internal class SchemaFenceStatusDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), SchemaFenceStatus {

    override val schema: CompositeSchema
        get() = SchemaFenceStatus.Schema
}
