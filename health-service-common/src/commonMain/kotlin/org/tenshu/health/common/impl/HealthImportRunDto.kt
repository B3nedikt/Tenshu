package org.tenshu.health.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.health.HealthImportRun
import org.tenshu.core.schema.CompositeSchema

internal class HealthImportRunDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), HealthImportRun {

    override val schema: CompositeSchema get() = HealthImportRun.Schema
}
