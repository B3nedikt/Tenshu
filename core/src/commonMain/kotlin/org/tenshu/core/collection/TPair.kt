package org.tenshu.core.collection

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.field
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeVar

interface TPair<out A : Data, out B : Data> : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val first: A
        get() = getField(Fields.FIRST)

    val second: B
        get() = getField(Fields.SECOND)

    companion object {
        const val ID = "tenshu/core/pair"

        val Schema = composite(
            id = ID,
            typeParams = {
                type("A")
                type("B")
            },
            fields = {
                field(Fields.FIRST, typeVar("A"))
                field(Fields.SECOND, typeVar("B"))
            }
        )
    }

    object Fields {
        const val FIRST = "$ID/first"
        const val SECOND = "$ID/second"
    }
}
