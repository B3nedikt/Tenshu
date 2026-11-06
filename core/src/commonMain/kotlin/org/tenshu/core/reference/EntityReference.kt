package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface EntityReference : Reference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/entity"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
