package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface EntityListReference : Reference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/entityList"

        val Schema: ReferenceSchema = reference(id = ID)
    }
}
