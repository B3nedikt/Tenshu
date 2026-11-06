package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.ValueTypeReference

class ValueTypeReferenceDto(
    private val typeString: String
) : ValueTypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}