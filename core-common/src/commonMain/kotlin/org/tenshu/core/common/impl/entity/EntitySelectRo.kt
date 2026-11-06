package org.tenshu.core.common.impl.entity

import org.tenshu.core.Data
import org.tenshu.core.collection.list.EntitySelect
import org.tenshu.core.collection.list.Select
import org.tenshu.core.collection.list.Selector
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.Entity
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.of

/**
 * Entity-shaped twin of `ListSelectRo` / `MaybeSelectRo`. Same chain
 * mechanics — append a `Select`-shaped `OperationDto` with the selector
 * list — but constructs the next RO as `Entity<R>`-typed so the
 * `.ifEmptyError().select(...)` path stays Entity-shaped through
 * projection (no `Maybe` re-wrap, no `TList` widening).
 *
 * The engine's `SelectImplementation` dispatches on `caller: Entity<*>`
 * (sibling to its existing `caller: Maybe<*>` branch) and applies the
 * same `selectItem` projection used for list members. See
 * `docs/proposals/core-chain-entity-typing.md` §2.2.
 */
internal class EntitySelectRo<R : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), EntitySelect<R> {

    override val schema: OperationSchema
        get() = EntitySelect.Schema

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
            type = Entity.Schema.toType().of(receiverElementType),
            context = context
        )
    }
}
