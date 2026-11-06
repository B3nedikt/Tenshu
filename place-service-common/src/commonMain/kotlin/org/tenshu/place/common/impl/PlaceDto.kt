package org.tenshu.place.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.place.Place
import org.tenshu.core.schema.CompositeSchema

internal class PlaceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Place {

    override val schema: CompositeSchema get() = Place.Schema
}
