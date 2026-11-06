package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

interface NotFoundError : TError {
    override val schema: CompositeSchema
        get() = Schema

    val message: TString
        get() = getField(Fields.MESSAGE)

    companion object {
        const val ID = "tenshu/result/error/notFound"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.MESSAGE)
            }
        )

        fun create(
            context: TContext,
            message: String
        ): NotFoundError = context.create(
            type = ID,
            fields = listOf(message.toTString(context))
        )
    }

    object Fields {
        const val MESSAGE = "$ID/message"
    }
}
