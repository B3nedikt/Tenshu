package org.tenshu.core

import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.composite

interface Data {

    val schema: DataSchema
        get() = Schema

    companion object {
        const val ID = "tenshu/core/data"

        val Schema: DataSchema = composite(id = ID)
    }
}

val Data.id: String
    get() = schema.id.get()
