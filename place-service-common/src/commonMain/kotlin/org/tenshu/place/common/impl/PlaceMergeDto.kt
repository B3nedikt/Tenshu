package org.tenshu.place.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.schema.CompositeSchema

internal class PlaceMergeDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PlaceMerge {

    override val schema: CompositeSchema get() = PlaceMerge.Schema
}
