package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Dispatcher-raised structural error: an inbound `OperationDto` carries a value
 * whose runtime schema isn't a subtype of the declared field type. Caught at
 * construction time by `OperationDto.init`. Carries the param name + the actual
 * vs expected schema ids — all non-PII identifiers
 * (`docs/proposals/core-error-channel.md` §2.3).
 */
interface WrongParameter : TError {
    override val schema: CompositeSchema
        get() = Schema

    val name: TString
        get() = getField(Fields.NAME)

    val actualTypeId: TString
        get() = getField(Fields.ACTUAL_TYPE_ID)

    val expectedTypeId: TString
        get() = getField(Fields.EXPECTED_TYPE_ID)

    companion object {
        const val ID = "tenshu/core/error/dispatch/wrong-parameter"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.NAME)
                string(Fields.ACTUAL_TYPE_ID)
                string(Fields.EXPECTED_TYPE_ID)
            }
        )

        fun create(
            context: TContext,
            name: String,
            actualTypeId: String,
            expectedTypeId: String
        ): WrongParameter = context.create(
            type = ID,
            fields = listOf(
                name.toTString(context),
                actualTypeId.toTString(context),
                expectedTypeId.toTString(context),
            )
        )
    }

    object Fields {
        const val NAME = "$ID/name"
        const val ACTUAL_TYPE_ID = "$ID/actual-type-id"
        const val EXPECTED_TYPE_ID = "$ID/expected-type-id"
    }
}
