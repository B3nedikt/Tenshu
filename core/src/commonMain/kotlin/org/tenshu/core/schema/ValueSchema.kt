package org.tenshu.core.schema

interface ValueSchema : DataSchema {

    override val schema: CompositeSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/data/schema/value"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = DataSchema.Schema,
            fields = {
                composite(Fields.FIELD, type = Field.Schema)
            }
        )
    }

    object Fields {
        const val FIELD = "${ID}/field"
    }
}
