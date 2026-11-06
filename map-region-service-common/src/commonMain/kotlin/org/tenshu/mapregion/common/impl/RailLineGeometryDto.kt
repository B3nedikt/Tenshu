package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.RailLineGeometry
import org.tenshu.core.schema.CompositeSchema

internal class RailLineGeometryDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), RailLineGeometry {
    override val schema: CompositeSchema get() = RailLineGeometry.Schema
}
