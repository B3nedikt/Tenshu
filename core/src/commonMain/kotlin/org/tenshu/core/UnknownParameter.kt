package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Dispatcher-raised structural error: an inbound `OperationDto` carries a `params`
 * key that doesn't correspond to any declared field on the operation's schema.
 * Caught at construction time by `OperationDto.init`. Carries the rejected param
 * name + the operation id — both non-PII identifiers
 * (`docs/proposals/core-error-channel.md` §2.3).
 */
interface UnknownParameter : TError {
    override val schema: CompositeSchema
        get() = Schema

    val name: TString
        get() = getField(Fields.NAME)

    val operationId: TString
        get() = getField(Fields.OPERATION_ID)

    companion object {
        const val ID = "tenshu/core/error/dispatch/unknown-parameter"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.NAME)
                string(Fields.OPERATION_ID)
            }
        )

        fun create(
            context: TContext,
            name: String,
            operationId: String
        ): UnknownParameter = context.create(
            type = ID,
            fields = listOf(name.toTString(context), operationId.toTString(context))
        )
    }

    object Fields {
        const val NAME = "$ID/name"
        const val OPERATION_ID = "$ID/operation-id"
    }
}
