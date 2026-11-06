package org.tenshu.core

import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.value

interface TValue : Data {

    override val schema: ValueSchema
        get() = Schema

    val value: Data

    companion object {
        const val ID = "tenshu/core/value"

        val Schema: ValueSchema = value(
            id = ID,
            parent = Data.Schema
        )
    }
}