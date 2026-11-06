package org.tenshu.core.reference

import org.tenshu.core.schema.reference

interface FieldReference : Reference {

    override val schema: ReferenceSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/reference/field"

        val Schema: ReferenceSchema = reference(ID)
    }
}
