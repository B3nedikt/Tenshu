package org.tenshu.inference.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.inference.ModelUnavailable

internal class ModelUnavailableDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ModelUnavailable {

    override val schema: CompositeSchema get() = ModelUnavailable.Schema
}
