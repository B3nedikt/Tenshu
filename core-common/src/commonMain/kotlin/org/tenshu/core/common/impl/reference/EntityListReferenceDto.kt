package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.EntityListReference
import org.tenshu.core.reference.ReferenceSchema

class EntityListReferenceDto(
    private val typeString: String
) : EntityListReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}
