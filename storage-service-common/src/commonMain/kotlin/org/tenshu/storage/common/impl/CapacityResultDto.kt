package org.tenshu.storage.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.storage.CapacityResult

internal class CapacityResultDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), CapacityResult {

    override val schema: CompositeSchema
        get() = CapacityResult.Schema
}
