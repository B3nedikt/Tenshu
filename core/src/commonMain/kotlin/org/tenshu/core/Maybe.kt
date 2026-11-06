package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.field
import org.tenshu.core.schema.type
import org.tenshu.core.schema.typeVar

interface Maybe<out T : Data> : Composite {

    companion object {
        const val ID = "tenshu/core/maybe"
    }

    interface Some<out T : Data> : Maybe<T> {

        override val schema: CompositeSchema
            get() = Schema

        val value: T
            get() = getField(Fields.VALUE)

        companion object {
            const val ID = "tenshu/core/maybe/some"

            val Schema = composite(
                id = ID,
                parent = composite(Maybe.ID, typeParams = { type("E") }),
                typeParams = { type("E") },
                fields = {
                    field(Fields.VALUE, typeVar("E"))
                }
            )

            fun <T : Data> create(
                context: TContext,
                value: T
            ): Some<T> = context.create(
                type = ID,
                fields = listOf(value)
            )
        }

        object Fields {
            const val VALUE = "$ID/value"
        }
    }

    interface Empty<out T : Data> : Maybe<T> {

        override val schema: CompositeSchema
            get() = Schema

        companion object {
            const val ID = "tenshu/core/maybe/empty"

            val Schema = composite(
                id = ID,
                parent = composite(Maybe.ID, typeParams = { type("E") }),
                typeParams = { type("E") },
                fields = {}
            )

            fun <T : Data> create(
                context: TContext
            ): Empty<T> = context.create(
                type = ID,
                fields = emptyList()
            )
        }
    }
}
