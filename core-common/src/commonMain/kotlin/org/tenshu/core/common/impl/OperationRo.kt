package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.common.impl.array.ListRo
import org.tenshu.core.common.impl.maybe.MaybeRo
import org.tenshu.core.core.OperationReceiverHolder
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.DataSchema

abstract class OperationRo(
    override val requestChain: RequestChain,
    override val context: TContext
) : Operation, RequestObject, OperationReceiverHolder {

    /**
     * The receiver RO this operation was resolved from — set by `Data.findOperation` after
     * construction. Used by parameterised operation ROs (`FilterRo`, `ListSearchRo`, …) to
     * derive their result list / maybe element schema from the receiver's `elementType` at
     * chain-construction time. Phase 5.2 of `docs/adr/0009-operation-schema-typing.md`.
     */
    override var receiver: Data? = null

    /**
     * Optional narrowing of the element schema, set by DSL extensions when the caller
     * specifies a more specific reified type than the receiver carries (e.g.
     * `root.files /* TList<File> */ .search<Folder> { ... }`). When set, takes precedence
     * over the receiver's `elementType` in [receiverElementType]. Phase 5.4 of
     * `docs/adr/0009-operation-schema-typing.md`.
     */
    override var narrowedElementType: DataSchema? = null

    override fun <T : Data> getField(reference: String): T {
        error("Operation ROs do not have fields")
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        error("Operation ROs do not have optional fields")
    }
}

/**
 * Element schema for the operation's receiver context. Resolution order:
 * 1. [OperationRo.narrowedElementType] — set by DSL extensions when the caller passes a
 *    reified type more specific than the receiver carries.
 * 2. The receiver's `elementType` (parameterised `ListRo` / `MaybeRo`).
 * 3. [Data.Schema] fallback.
 *
 * Phase 5.2 / 5.4 of `docs/adr/0009-operation-schema-typing.md`.
 */
val OperationRo.receiverElementType: DataSchema
    get() {
        narrowedElementType?.let { return it }
        return when (val r = receiver) {
            is ListRo<*> -> r.elementType
            is MaybeRo<*> -> r.elementType
            else -> Data.Schema
        }
    }
