package org.tenshu.core.common.impl.schema

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field

class FieldDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Field {

    override val schema: CompositeSchema
        get() = Field.Schema
}
