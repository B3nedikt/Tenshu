package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.HealthSample
import org.tenshu.core.schema.CompositeSchema

internal class HealthSampleDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HealthSample {

    override val schema: CompositeSchema get() = HealthSample.Schema
}
