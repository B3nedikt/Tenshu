package org.tenshu.core.core

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.baseSchema

interface OperationResolver : TComponent {

    /**
     * Receiver-aware lookup: derives the receiver's type id from the operation's
     * [OperationSchema.receiver]. Falls back to [Operation.ID] (top-level dispatch) when
     * the schema declares no receiver. Phase 4.2 of `docs/adr/0009-operation-schema-typing.md`
     * deleted the prior `(typeId, operationId, ...)` overload.
     */
    fun <T : Operation> findOperation(
        operationId: String,
        requestChain: RequestChain,
        context: TContext
    ): T?

    companion object {
        const val COMPONENT_ID = "tenshu/core/operationResolver"
    }
}

fun TContext.operationResolver(): OperationResolver =
    components[OperationResolver.COMPONENT_ID] as OperationResolver

/**
 * DSL helper for resolving an [Operation] from the receiver's context. The receiver's
 * dispatch type is read from the operation schema — Phase 2.1 of
 * `docs/adr/0009-operation-schema-typing.md`. Phase 4.2 deleted the prior two-arg form.
 *
 * Phase 5.2: also publishes the receiver to the operation RO via the
 * [OperationReceiverHolder.receiver] property (when applicable), so parameterised
 * operations can derive their result element schema from the receiver.
 */
inline fun <reified T : Operation> Data.findOperation(
    operationId: String
): T {
    val ro = this as RequestObject
    val context = this as TContext
    val operation = context.operationResolver()
        .findOperation<T>(operationId, ro.requestChain, context)
        ?: error("Operation $operationId not found")
    if (operation is OperationReceiverHolder) {
        operation.receiver = this
    }
    return operation
}

/**
 * Marker interface implemented by operation ROs that need to know their receiver to derive
 * a result element schema (e.g. `TList<E>.filter(...)` returns `TList<E>` — the result
 * RO's element schema is the receiver's element schema). [Data.findOperation] sets
 * [receiver] after constructing the operation. Phase 5.2 of
 * `docs/adr/0009-operation-schema-typing.md`.
 *
 * [narrowedElementType] is set by DSL extensions when the caller specifies a more specific
 * reified type than the receiver carries — e.g. `root.files /* TList<File> */ .search<Folder> { ... }`.
 * In that case the predicate-RO and the result `Maybe<Folder>` should bind to `Folder`
 * even though the receiver is `TList<File>`. Phase 5.4 of `docs/adr/0009-operation-schema-typing.md`
 * — typed replacement for the prior `type: String` marker mutation.
 */
interface OperationReceiverHolder {
    var receiver: Data?
    var narrowedElementType: org.tenshu.core.schema.DataSchema?
}
