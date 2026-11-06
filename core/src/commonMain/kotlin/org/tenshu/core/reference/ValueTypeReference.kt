package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface ValueTypeReference : TypeReference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type/value"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
