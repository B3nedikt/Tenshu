package org.tenshu.core.schema

import org.tenshu.core.collection.list.TList

interface UnionSchema : CompositeSchema {

    override val schema: CompositeSchema
        get() = Schema

    val members: TList<DataSchema>
        get() = getField(Fields.MEMBERS)

    companion object {
        const val ID = "tenshu/core/data/schema/union"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = CompositeSchema.Schema,
            fields = {
                list(Fields.MEMBERS, of = DataSchema.Schema)
            }
        )
    }

    object Fields {
        const val MEMBERS = "${ID}/members"
    }
}
