package org.tenshu.core.schema

interface ConstantSchema : DataSchema {

    companion object {
        const val ID = "tenshu/core/data/schema/constant"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = DataSchema.Schema,
            fields = {
                string(id = DataSchema.Fields.SCHEMA_ID)
            }
        )
    }
}
