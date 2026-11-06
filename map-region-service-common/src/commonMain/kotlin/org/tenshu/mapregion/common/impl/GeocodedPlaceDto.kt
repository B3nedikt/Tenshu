package org.tenshu.mapregion.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.schema.CompositeSchema

internal class GeocodedPlaceDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), GeocodedPlace {
    override val schema: CompositeSchema get() = GeocodedPlace.Schema
}
