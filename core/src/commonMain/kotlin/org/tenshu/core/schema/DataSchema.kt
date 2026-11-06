package org.tenshu.core.schema

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList

interface DataSchema : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val id: TString
        get() = getField(Fields.SCHEMA_ID)

    val parent: DataSchema?
        get() = getOptionalField(Fields.PARENT)

    companion object {
        const val ID = "tenshu/core/data/schema"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.SCHEMA_ID)
                reference(Fields.PARENT, type = Schema)
            }
        )
    }

    object Fields {
        const val SCHEMA_ID = "${ID}/id"
        const val PARENT = "${ID}/parent"
    }
}

fun DataSchema.hasTrait(traitId: String): Boolean {
    if (this is CompositeSchema && traits.toList().any { it.isSubtypeOf(traitId) }) {
        return true
    }
    return parent?.hasTrait(traitId) ?: false
}

fun DataSchema.isSubtypeOf(typeId: String): Boolean {
    if (id.get() == typeId) return true
    return parent?.isSubtypeOf(typeId) ?: false
}
