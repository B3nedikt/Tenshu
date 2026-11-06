package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.toList
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.baseSchema
import org.tenshu.core.schema.isSubtypeOf

data class OperationDto(
    override val params: Map<String, Data>,
    override val schema: OperationSchema
) : Operation {

    init {
        // Phase 5.7 of `docs/adr/0009-operation-schema-typing.md`: at the `OperationDto` boundary,
        // assert that every params key corresponds to a declared schema field id and the
        // value's runtime schema is subtype-compatible with the declared field type.
        // Catches mis-built ROs and malformed inbound payloads early. Required-field
        // presence isn't enforced here — operation ROs construct partial chain entries
        // that gain the full params map at execution time; presence-on-execute is
        // checked by `evaluateCallstack` just before dispatch.
        //
        // Phase 2 of `docs/proposals/core-error-channel.md` (the structural validation
        // lift): both throws are typed `TenshuException(TError)` instead of
        // `kotlin.require`'s freetext `IllegalArgumentException`. Catch-alls still
        // flatten via `e.message` in this phase — `error.id` rides the message
        // channel — but Phase 3 will unwrap and ship the typed error on the wire.
        val schemaFields = schema.params.toList()
        val knownFieldIds = schemaFields.asSequence().map { it.id.get() }.toSet()
        for ((paramId, value) in params) {
            if (paramId !in knownFieldIds) {
                throw TenshuException(
                    UnknownParameterDto(
                        name = paramId,
                        operationId = schema.id.get()
                    )
                )
            }
            val expectedBaseId = schemaFields
                .first { it.id.get() == paramId }
                .type.baseSchema?.id?.get()
                ?: continue
            val actualSchema = value.schema
            if (!actualSchema.isSubtypeOf(expectedBaseId)) {
                throw TenshuException(
                    WrongParameterDto(
                        name = paramId,
                        actualTypeId = actualSchema.id.get(),
                        expectedTypeId = expectedBaseId
                    )
                )
            }
        }
    }

    override fun <T : Data> getField(reference: String): T {
        @Suppress("UNCHECKED_CAST")
        return params[reference] as T
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        @Suppress("UNCHECKED_CAST")
        return params[reference] as T?
    }
}
