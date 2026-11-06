package org.tenshu.timeline.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.timeline.Trip

internal class TripDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), Trip {

    override val schema: CompositeSchema get() = Trip.Schema
}
