package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface PropertyTypeReference : Reference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type/property"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
