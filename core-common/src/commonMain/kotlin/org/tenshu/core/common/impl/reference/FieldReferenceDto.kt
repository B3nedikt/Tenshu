package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.FieldReference
import org.tenshu.core.reference.ReferenceSchema

class FieldReferenceDto(
    private val typeString: String
) : FieldReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}