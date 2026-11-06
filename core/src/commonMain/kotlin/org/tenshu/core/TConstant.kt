package org.tenshu.core

import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.constant

interface TConstant : Data {

    override val schema: ConstantSchema

    companion object {
        const val ID = "tenshu/core/value"

        val Schema = constant(
            id = ID,
            parent = DataSchema.Schema
        )
    }
}