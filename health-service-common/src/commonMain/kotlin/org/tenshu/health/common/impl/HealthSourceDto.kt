package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.HealthSource
import org.tenshu.core.schema.CompositeSchema

internal class HealthSourceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HealthSource {

    override val schema: CompositeSchema get() = HealthSource.Schema
}
