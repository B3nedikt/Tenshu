package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.CompositeTypeReference
import org.tenshu.core.reference.ReferenceSchema

class CompositeTypeReferenceDto(
    private val typeString: String
) : CompositeTypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}