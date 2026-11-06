package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.EntityReference
import org.tenshu.core.reference.ReferenceSchema

class EntityReferenceDto(
    private val typeString: String
) : EntityReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}