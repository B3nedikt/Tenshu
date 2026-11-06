package org.tenshu.core.common.impl.schema

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.TypeParameter

class TypeParameterDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), TypeParameter {

    override val schema: CompositeSchema
        get() = TypeParameter.Schema
}
