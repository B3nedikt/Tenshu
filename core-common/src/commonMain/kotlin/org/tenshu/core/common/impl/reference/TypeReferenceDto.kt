package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.TypeReference

class TypeReferenceDto(
    private val typeString: String
) : TypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}