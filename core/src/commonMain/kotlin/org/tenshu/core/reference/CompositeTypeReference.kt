package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface CompositeTypeReference : TypeReference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type/composite"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
