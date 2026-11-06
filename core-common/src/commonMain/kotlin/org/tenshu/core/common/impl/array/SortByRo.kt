package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.common.impl.receiverElementType
import org.tenshu.core.core.TContext
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.DataSchema
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean

/**
 * Builds the `SortBy` chain step. The selector callstack is captured the same
 * way [FilterRo] captures a predicate — by invoking the lambda against a
 * synthetic element RO inside a nested array — except the selector yields a
 * value (the sort key) rather than a `TBoolean`. The descending flag rides as a
 * `TBoolean` param. See `docs/proposals/core-chain-sort.md` §2.3.
 */
internal class SortByRo<T : Data>(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SortBy<T> {

    override val schema: OperationSchema
        get() = SortBy.Schema

    override fun sortBy(selector: (T) -> TValue, descending: Boolean): TList<T> {
        val elementSchema = receiverElementType
        val chainWithSelector = requestChain.addNestedArray { arrayChain ->
            val elementRo = context.roProvider().createRequestObject<T>(
                type = elementSchema.toType(),
                requestChain = arrayChain,
                context = context
            )

            val resultRo = selector(elementRo)

            (resultRo as RequestObject).requestChain
        }

        @Suppress("UNCHECKED_CAST")
        val selectorList = chainWithSelector.getCallstack().last() as TList<Data>

        return dispatch(selectorList, descending, elementSchema)
    }

    /**
     * Value-property sort: the selector is a single [StaticPropertyRelation] step (the
     * property's projection) instead of a recorded lambda callstack. The engine
     * (`SortByImplementation`) reads the property off each element via
     * `getProperty`.
     */
    override fun sortByProperty(projection: StaticPropertyRelation, descending: Boolean): TList<T> {
        val selectorList: TList<Data> = SimpleTList(listOf(projection))
        return dispatch(selectorList, descending, receiverElementType)
    }

    override fun thenBy(selector: (T) -> TValue, descending: Boolean): TList<T> {
        val elementSchema = receiverElementType
        val chainWithSelector = requestChain.addNestedArray { arrayChain ->
            val elementRo = context.roProvider().createRequestObject<T>(
                type = elementSchema.toType(),
                requestChain = arrayChain,
                context = context
            )
            val resultRo = selector(elementRo)
            (resultRo as RequestObject).requestChain
        }

        @Suppress("UNCHECKED_CAST")
        val selectorList = chainWithSelector.getCallstack().last() as TList<Data>

        return foldThenKey(selectorList, descending, elementSchema)
    }

    override fun thenByProperty(projection: StaticPropertyRelation, descending: Boolean): TList<T> {
        val selectorList: TList<Data> = SimpleTList(listOf(projection))
        return foldThenKey(selectorList, descending, receiverElementType)
    }

    /**
     * Fold a secondary sort key into the preceding `SortBy` (§2.4). Reads the last
     * chain op — which MUST be a `SortBy` — and appends a NEW `SortBy` op carrying
     * the full, ordered key list (the prior op's keys + this one) via the
     * [SortBy.Params.THEN_SELECTORS]/[SortBy.Params.THEN_DESCENDINGS] tail.
     *
     * The new op re-sorts the whole list lexicographically, so the ordering is
     * correct regardless of the prior op's partial order. The prior `SortBy(A)`
     * stays on the chain and runs first — a redundant intermediate pass (one extra
     * O(n log n) per `thenBy`). Appending (rather than replacing the prior op) is
     * deliberate: the chain is an *executable* wrapper (`LocalRequestChain` /
     * `TaskRequestChain`) that can't be truncated without knowing its concrete
     * type, whereas `addArray` preserves it. For the 1–2 keys real listings use,
     * the redundant sort is negligible; if a deep `thenBy` chain ever matters,
     * replace it with a build-time fold behind a chain `replaceLast`.
     *
     * ⚠ The run of prefix ops this leaves is NOT negligible for the ordered-window
     * fusion, which expects a window right after the `SortBy` it gates on: it saw
     * `SortBy(A)` followed by `SortBy(A, B)` and fell through, so every tie-broken
     * sort ran unfused. The proxy now folds a run of key-extending `SortBy` ops to
     * its last member before the gate (`foldSortByRun`), so the appended shape
     * costs nothing on the fused path. The unfused path still pays the extra pass.
     */
    @Suppress("UNCHECKED_CAST")
    private fun foldThenKey(
        selectorList: TList<Data>,
        descending: Boolean,
        elementSchema: DataSchema,
    ): TList<T> {
        val priorOp = requestChain.getCallstack().lastOrNull() as? OperationDto
            ?: error("thenBy must follow a sortBy — no preceding operation on the chain")
        require(priorOp.schema.id.get() == SortBy.ID) {
            "thenBy must follow a sortBy; preceding op was '${priorOp.schema.id.get()}'"
        }

        val priorSelectors = (priorOp.params[SortBy.Params.THEN_SELECTORS] as? TList<Data>)?.toList().orEmpty()
        val priorDescendings = (priorOp.params[SortBy.Params.THEN_DESCENDINGS] as? TList<Data>)?.toList().orEmpty()

        val fullOp = OperationDto(
            params = priorOp.params + mapOf(
                SortBy.Params.THEN_SELECTORS to SimpleTList(priorSelectors + selectorList),
                SortBy.Params.THEN_DESCENDINGS to SimpleTList(priorDescendings + descending.toTBoolean(context)),
            ),
            schema = priorOp.schema,
        )

        val nextChain = requestChain.addArray(fullOp)

        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = listType(elementSchema),
            context = context
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(
        selectorList: TList<Data>,
        descending: Boolean,
        elementSchema: DataSchema,
    ): TList<T> {
        val nextChain = requestChain.addArray(
            OperationDto(
                params = mapOf(
                    SortBy.Params.SELECTOR to selectorList,
                    SortBy.Params.DESCENDING to descending.toTBoolean(context)
                ),
                schema = schema
            )
        )

        return context.roProvider().createRequestObject(
            requestChain = nextChain,
            type = listType(elementSchema),
            context = context
        )
    }
}
