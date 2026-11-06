package org.tenshu.place.common.impl

import org.tenshu.core.Data
import org.tenshu.core.common.impl.CompositeDto
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.schema.CompositeSchema

internal class PlaceRoleDto(
    fieldValues: List<Data?>,
) : CompositeDto(fieldValues), PlaceRole {

    override val schema: CompositeSchema get() = PlaceRole.Schema
}
