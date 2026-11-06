package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface DynamicPropertyTypeReference : PropertyTypeReference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type/property/dynamic"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
