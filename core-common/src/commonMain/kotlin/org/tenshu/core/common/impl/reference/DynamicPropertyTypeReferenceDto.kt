package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.ReferenceSchema

class DynamicPropertyTypeReferenceDto(
    private val typeString: String
) : DynamicPropertyTypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}