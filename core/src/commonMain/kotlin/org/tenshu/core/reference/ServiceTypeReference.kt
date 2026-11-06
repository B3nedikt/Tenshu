package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface ServiceTypeReference : TypeReference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type/service"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
