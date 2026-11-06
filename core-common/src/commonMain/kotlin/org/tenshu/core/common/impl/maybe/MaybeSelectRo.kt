package org.tenshu.core.common.impl.maybe

import org.tenshu.core.Data
import org.tenshu.core.collection.list.MaybeSelect
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.maybeType

/**
 * Maybe-shaped twin of `ListSelectRo`. Builds the same selector list onto
 * the chain (the engine's `SelectImplementation` dispatches on
 * `caller: Maybe<*>` and applies the same projection), but the next
 * RO it constructs is `Maybe`-shaped rather than `TList`-shaped — the
 * single static-type difference that distinguishes the two operations
 * at the chain level.
 *
 * See `core/.../Select.kt` ([MaybeSelect] companion) for the schema /
 * dispatch story; see `docs/proposals/core-chain-entity-typing.md` §2.2
 * for the design.
 */
internal class MaybeSelectRo<R : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), MaybeSelect<R> {

    override val schema: OperationSchema
        get() = MaybeSelect.Schema

    override fun select(selectors: List<Selector<R>>): Data {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    Select.Params.SELECTORS to TListDto(selectors)
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = maybeType(receiverElementType),
            context = context
        )
    }
}
