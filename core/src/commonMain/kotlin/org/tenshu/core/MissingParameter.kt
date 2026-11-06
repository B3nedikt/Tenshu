package org.tenshu.core

import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * Dispatcher-raised structural error: a required operation parameter was absent
 * from `protocol.params` when [org.tenshu.core.common.proxy.evaluateCallstack]
 * reached the execute step. Carries the field id only — no freetext message
 * crosses the boundary (`docs/proposals/core-error-channel.md` §2.3).
 */
interface MissingParameter : TError {
    override val schema: CompositeSchema
        get() = Schema

    val name: TString
        get() = getField(Fields.NAME)

    companion object {
        const val ID = "tenshu/core/error/dispatch/missing-parameter"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.NAME)
            }
        )

        fun create(
            context: TContext,
            name: String
        ): MissingParameter = context.create(
            type = ID,
            fields = listOf(name.toTString(context))
        )
    }

    object Fields {
        const val NAME = "$ID/name"
    }
}
