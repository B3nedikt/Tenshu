package org.tenshu.core.common.impl.reference

import org.tenshu.core.reference.ReferenceSchema
import org.tenshu.core.reference.ServiceTypeReference

class ServiceTypeReferenceDto(
    private val typeString: String
) : ServiceTypeReference, ReferenceDto(typeString) {

    override val schema: ReferenceSchema
        get() = super<ReferenceDto>.schema
}