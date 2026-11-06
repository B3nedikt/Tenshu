package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface TypeReference : Reference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/type"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
