package org.tenshu.core.common.impl

import org.tenshu.core.Data
import org.tenshu.core.Resolve
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType

/**
 * Chain-side half of [Resolve] — appends the terminal and hands back an RO of the **same** type.
 *
 * ⚠ Unlike `maybe.GetRo`, which unwraps `Maybe<T>` to `T` and so reads its element type off the
 * receiver, a value terminal changes nothing about the type: the chain already produces the value,
 * and this only marks where it ends. The RO keeps the receiver's own type.
 */
internal class ResolveRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), Resolve<T> {

    override val schema: OperationSchema
        get() = Resolve.Schema

    override fun resolve(): RequestObject {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = emptyMap<String, Data>(),
                schema = schema,
            )
        )

        return context.roProvider().createRequestObject(
            // ⚠ Falls back to `Data` when the receiver carries no schema — the terminal does not
            // change the value's type, so a coarse type here costs nothing a caller can observe.
            type = (receiver?.schema ?: Data.Schema).toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
