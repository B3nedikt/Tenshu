package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.ReferenceSchema

class PropertyTypeReferenceDto(
    private val typeString: String
) : PropertyTypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}