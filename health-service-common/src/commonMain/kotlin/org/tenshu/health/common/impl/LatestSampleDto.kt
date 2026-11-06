package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.LatestSample
import org.tenshu.core.schema.CompositeSchema

internal class LatestSampleDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), LatestSample {

    override val schema: CompositeSchema get() = LatestSample.Schema
}
