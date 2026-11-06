package org.tenshu.place.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.place.ExternalPlaceRef
import org.tenshu.core.schema.CompositeSchema

internal class ExternalPlaceRefDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), ExternalPlaceRef {

    override val schema: CompositeSchema get() = ExternalPlaceRef.Schema
}
