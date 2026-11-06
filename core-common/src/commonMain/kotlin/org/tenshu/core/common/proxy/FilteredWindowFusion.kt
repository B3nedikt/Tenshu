package org.tenshu.core.common.proxy

import org.tenshu.core.Contains
import org.tenshu.core.Data
import org.tenshu.core.EqualTo
import org.tenshu.core.InRange
import org.tenshu.core.Lowercase
import org.tenshu.core.OneOf
import org.tenshu.core.Operation
import org.tenshu.core.SubstringAfterLast
import org.tenshu.core.TBoolean
import org.tenshu.core.TConstant
import org.tenshu.core.TValue
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.Traverse
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.util.isCompositeField
import org.tenshu.core.common.util.isParameterized
import org.tenshu.core.core.lazyDataMaterializer
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.id
import org.tenshu.core.reference.DynamicPropertyTypeReference
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ConstantSchema
import org.tenshu.core.schema.LazyDataCreator
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.RelationSchema
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.trait.isBackground
import org.tenshu.core.trait.isIo
import org.tenshu.core.trait.isStream

/**
 * **Filter-aware ordered-window fusion** (`core-ordered-window-fusion.md` §9) — the peephole
 * §3.3 leaves out of [tryOrderedWindowFusion]. Recognises, on a raw array reference:
 *
 * ```
 * [traverse()] · filter{p1} · … · filter{pN} · sortBy(keys) · <finite drop/take window> · select{…}
 * ```
 *
 * (a leading `traverse()`, zero or more filters — at least one when there is no traverse, since
 * the bare shape is [tryOrderedWindowFusion]'s) and runs it as ONE walk over thin rows: every array
 * the traversal would
 * read is read with the select's projection widened by the predicates' and keys' fields and value
 * properties, each row is tested against the predicates off what the thin row already carries, and
 * the survivors go straight into the same bounded stable top-N the bare fusion uses. Nothing is
 * materialised as
 * a whole-realm list, no `Filter` or `SortBy` op is dispatched, and no row is re-read per
 * property — which is what the unfused path pays: `TraverseImplementation` loads every row and
 * re-reads every one of them, then each property predicate re-reads the row **unprojected** through
 * `resolveStaticProperty`, once per predicate, and the sort does it once more per row.
 *
 * ### Semantics contract — identical to the unfused chain
 *
 * - **Rows and encounter order:** the walk is `recursivelyWalk`'s — item first, then each `TList`
 *   field's members, depth-first, in schema-field order — and descends through the same
 *   [nestedListFieldValues] helper. Every array it reads goes through
 *   [ExecutionContext.getArrayData], so a stream's reference collector observes exactly the arrays
 *   the unfused traverse observes (a write in any folder still re-evaluates the stream).
 * - **Predicates:** a row passes iff every predicate is true, evaluated in order, short-circuiting
 *   like chained `filter`s. The operators run through the **real dispatcher** on the value read off
 *   the thin row, so `oneOf`/`inRange`/`equalTo`/`contains` answer exactly as they do unfused.
 *   Whenever the thin read has no value for a predicate's head (an absent property, a derived value
 *   that came out `null`) the row's predicate is evaluated by [evaluatePredicateOn] — the very
 *   function `FilterImplementation` runs — so even the error an unfused read raises is the error
 *   the fused one raises.
 * - **Order, ties, nulls, windows, key errors:** [planOrderedWindow] + [BoundedStableTopN], shared
 *   with the bare fusion — same comparator ([compareSortKeys]), encounter-order tie-break among the
 * *surviving*
 *   rows (which is the unfused order: `filter` preserves order and `sortBy` is stable), same
 *   nulls-last, same window arithmetic.
 *
 * ### What falls through (the whole chain, never an approximation)
 *
 * Any predicate that is not *one field, value property or synchronous non-deferred dynamic property,
 * followed only by allow-listed pure operators with constant operands* ([classifyFusedPredicate]); any key
 * the bare fusion refuses; no `select`; an unbounded window; a stream op downstream; a nested list
 * that is not
 * a store-backed array, or whose element type differs from the root's. The last two are only
 * discoverable mid-walk: the walk then abandons ([FallThrough]) before anything was consumed and
 * the unfused path runs from the start.
 *
 * ⚠ One residual difference, deliberately accepted: when **two different rows would each raise a
 * different error**, which error surfaces may differ (unfused runs predicate 1 over every row before
 * predicate 2; fused runs all predicates per row). Both paths fail the chain.
 */
internal fun tryFilteredOrderedWindowFusion(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    first: Operation,
    remaining: List<Data>,
): OrderedWindowResult? {
    val ops: List<Data> = listOf<Data>(first) + remaining
    var at = 0

    val traverse = first.id == Traverse.ID
    if (traverse) {
        if (!first.params.isNullOrEmpty()) return null
        at = 1
    }

    val predicates = mutableListOf<FusedPredicate>()
    while (at < ops.size) {
        val op = ops[at] as? Operation ?: break
        if (op.id != Filter.ID) break
        val callstack = (op.params?.get(Filter.Params.PREDICATE) as? TList<*>)
            ?.toList()
            ?.map { it as? Data ?: return null }
            ?: return null
        predicates += classifyFusedPredicate(executionContext, reference, callstack) ?: return null
        at++
    }
    if (!traverse && predicates.isEmpty()) return null // the bare sort is tryOrderedWindowFusion's

    val sortOp = ops.getOrNull(at) as? Operation ?: return null
    if (sortOp.id != SortBy.ID) return null
    val afterSort = ops.drop(at + 1)
    val run = foldSortByRun(sortOp, afterSort)
    val plan = planOrderedWindow(
        executionContext, reference, run.op, afterSort.drop(run.consumedFromRemaining),
    ) ?: return null

    // Every field is kept (`skipUnprojectedComposites = false`): a predicate, a key, the walk's
    // own DataId derivation and its descent all read fields off the thin row, and a field is the
    // cheap part of a row. What the thin read drops is the relation map — except the value
    // properties the predicates and keys read, which ride [Projection.keepProperties].
    //
    // ⚠ `children` is cleared: the select's nested projection would prune kept composite fields
    // *before* the predicates run, and the unfused filter never sees a pruned row (it reads the
    // array with no nested projection at all). The select still applies its own selectors to the
    // winners, so nothing it omits reaches the caller. Belt and braces with the navigation rule
    // in [classifyFusedPredicate]: an operator in a dynamic property's
    // derivation receives the whole row and may read any composite of it.
    val projection = plan.selectProjection.copy(
        keepFields = plan.selectProjection.keepFields + plan.keptFields +
            predicates.mapNotNull { it.head.keptField },
        skipUnprojectedComposites = false,
        children = emptyMap(),
        keepProperties = plan.selectProjection.keepProperties + plan.keptProperties +
            predicates.mapNotNull { it.head.keptProperty },
    )

    val topN = BoundedStableTopN(plan.comparator, plan.keep)
    var index = 0
    val visit: (Data) -> Unit = { row ->
        if (predicates.all { it.matches(executionContext, row) }) {
            // ⚠ Evaluated even under `take(0)`: the unfused filters run over every row, so a
            // predicate that throws must throw here too.
            topN.offer(plan.keyed(row, index++))
        }
    }

    try {
        walk(executionContext, reference, reference.elementSchemaId, traverse, projection, visit)
    } catch (_: FallThrough) {
        return null
    }

    // ops[1..at] are remaining[0 until at]: the filters after `first`, then the sort.
    return OrderedWindowResult(
        rows = plan.window(topN).asSequence(),
        consumedFromRemaining = at + run.consumedFromRemaining + plan.consumedFromRemaining,
    )
}

/** The walk met a shape it cannot read thin; nothing has been consumed, so run unfused. */
private class FallThrough : RuntimeException()

/**
 * Read [array] thin and visit each row; under [traverse], descend into each row's list fields
 * right after visiting it — `recursivelyWalk`'s order, item first then its subtree.
 */
private fun walk(
    executionContext: ExecutionContext,
    array: ArrayDatabaseReference,
    elementSchemaId: String?,
    traverse: Boolean,
    projection: Projection,
    visit: (Data) -> Unit,
) {
    val rows = executionContext.getArrayData(array, projection) ?: return
    for (row in rows) {
        visit(row)
        if (!traverse) continue
        // ⚠ Leaf rows (every non-folder file) skip the skeleton re-read `recursivelyWalk` pays:
        // with no `TList` field there is nothing it could find.
        val typeId = (row as? Entity<*>)?.type?.get() ?: row.id
        if (!hasNestedListFields(typeId, executionContext)) continue
        for (fieldData in nestedListFieldValues(row, executionContext)) {
            fieldData ?: continue
            val nested = arrayReferenceOrNull(fieldData)
            if (nested == null) {
                // An inline list (no store-backed array) or a non-list value: the unfused walk
                // descends into the former with unprojected rows we cannot match thin.
                if (executionContext.resolveList(fieldData) != null) throw FallThrough()
                continue
            }
            // A nested array of another element type would need its own key classification.
            if (nested.elementSchemaId != elementSchemaId) throw FallThrough()
            walk(executionContext, nested, elementSchemaId, traverse, projection, visit)
        }
    }
}

/**
 * The value a fused predicate reads off a thin row before its operators run.
 */
internal sealed interface PredicateHead {
    val keptField: String? get() = null
    val keptProperty: String? get() = null

    /** A stored scalar field of the element schema. */
    class Field(val id: String) : PredicateHead {
        override val keptField: String get() = id
    }

    /** A stored scalar or constant-valued value property (`File.CreatedAt`, `File.Category`). */
    class ValueProperty(val id: String) : PredicateHead {
        override val keptProperty: String get() = id
    }

    /**
     * A **synchronous, non-deferred** dynamic property (`File.Category` — the `FileCategoryOf`
     * op over the path; `File.Trashed` — `path.contains("/Trash/")`; `File.Name`). Resolved by
     * the very function the unfused read calls ([ExecutionContext.resolveDynamicProperty]) on the
     * thin row — which carries every field (the fused projection keeps them all), and a dynamic
     * property's chain sees the row only as those fields — so the value is the unfused value by
     * construction. What the fusion buys here is not a cheaper derivation but the absent row
     * re-reads around it.
     */
    class Dynamic(val reference: DynamicPropertyTypeReference) : PredicateHead
}

/** One `filter`'s predicate, classified as fusable. */
internal class FusedPredicate(
    /** The predicate exactly as shipped — what the per-row fallback evaluates. */
    val callstack: List<Data>,
    val head: PredicateHead,
    /** The operators after the head, all from [FUSABLE_PREDICATE_OPS] with constant operands. */
    val tail: List<Operation>,
) {
    fun matches(executionContext: ExecutionContext, row: Data): Boolean {
        val value: Data? = when (head) {
            is PredicateHead.Field -> readField(row, head.id)
            is PredicateHead.ValueProperty -> (row as? Entity<*>)?.getProperty(head.id)
            is PredicateHead.Dynamic -> executionContext.resolveDynamicProperty(row, head.reference, null)
                .also { if (it is AsyncReference) throw FallThrough() }
        }
        val verdict = if (value == null) {
            // No value on the thin row: let the unfused per-row evaluation answer — or raise.
            evaluatePredicateOn(executionContext, row, callstack)
                .also { if (it is AsyncReference) throw FallThrough() }
        } else {
            runOperators(executionContext, value, tail)
        }
        // Same cast as `FilterImplementation`: a non-boolean predicate fails identically.
        return (verdict as TBoolean).get()
    }
}

private fun runOperators(executionContext: ExecutionContext, value: Data, ops: List<Operation>): Data? {
    if (ops.isEmpty()) return value
    val result = evaluateCallstack(
        executionContext = executionContext,
        contextData = value,
        callstack = ops,
        allowDeferring = false,
    )
    if (result is AsyncReference) throw FallThrough()
    return result
}

/**
 * Operators a fused predicate may apply to the value it read: pure, synchronous, scalar, reading
 * nothing but their receiver and their constant operands. Grows by one line per operator — and
 * only for one with all three properties, since an operator here runs once per row of the realm.
 */
private val FUSABLE_PREDICATE_OPS: Set<String> = setOf(
    EqualTo.ID,
    OneOf.ID,
    InRange.ID,
    Contains.ID,
    Lowercase.ID,
    SubstringAfterLast.ID,
)

/**
 * Classify one predicate callstack, or `null` to make the whole chain fall through. Accepted:
 * a head ([PredicateHead]) followed only by [FUSABLE_PREDICATE_OPS] whose every parameter is a
 * constant ([isConstantOperand]). Refused, among others: an op on the row itself (`isA<Folder>()`),
 * multi-step navigation, reference properties and entity relations (each a store read of another
 * row), dynamic properties that are not a field through pure operators, list-valued properties,
 * optional or parameterised fields, and any operand that is itself a callstack.
 */
internal fun classifyFusedPredicate(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    callstack: List<Data>,
): FusedPredicate? {
    val head = callstack.firstOrNull() ?: return null
    val tail = fusableOperators(callstack.drop(1)) ?: return null
    val schemaProvider = executionContext.context.schemaProvider()

    val classified: PredicateHead = when {
        head is DynamicPropertyTypeReference -> {
            val relation = schemaProvider.getSchema(head.get())
                as? RelationSchema.DynamicPropertyRelationSchema ?: return null
            if (relation.isSubtypeOf(TList.ID)) return null
            // A deferred property is IO by declaration (`File.Preview`); never per row of a scan.
            if (relation.deferredByDefault) return null
            // Only the type-independent chain — what `resolveDynamicProperty`'s hot path reads —
            // so it can be inspected here, once, rather than per row type.
            if (relation !is LazyDataCreator<*>) return null
            @Suppress("UNCHECKED_CAST")
            val chain = executionContext.context.lazyDataMaterializer()
                .getOrCreate(relation as LazyDataCreator<RelationSchema.DynamicPropertyRelationSchema>)
                .chain?.toList()
                ?: return null
            // ⚠⚠ No navigation. A reference may appear only as the chain's
            // FIRST step, naming a scalar, non-composite field of the row (`path` in
            // `path.contains(…)`). A derivation that walks into a nested composite
            // (`profile.address.city`) reads a part of the row the fused read may have pruned —
            // a downstream select's nested projection prunes kept composites — where the unfused
            // filter sees the complete row. It falls through rather than being trusted to a
            // projection detail.
            //
            // Every op must be synchronous in-process work: no IO, background or stream op. (An
            // op that turned out async anyway would surface as an AsyncReference at the first row
            // and make the walk fall through.)
            val fusable = chain.withIndex().all { (i, step) ->
                when (step) {
                    is Operation -> {
                        val schema = schemaProvider.getSchema(step.id) as? OperationSchema
                            ?: return@all false
                        !schema.isIo() && !schema.isBackground() && !schema.isStream()
                    }
                    is PropertyTypeReference -> false
                    is Reference -> i == 0 && scalarFieldOf(executionContext, reference, step.get()) != null
                    else -> false
                }
            }
            if (!fusable) return null
            PredicateHead.Dynamic(head)
        }

        head is PropertyTypeReference -> {
            val id = head.get()
            if (schemaProvider.getReferenceProperty(id) != null) return null
            if (schemaProvider.getEntityRelation(id) != null) return null
            when (schemaProvider.getSchema(id)) {
                is ValueSchema, is ConstantSchema -> PredicateHead.ValueProperty(id)
                else -> return null
            }
        }

        head is Reference -> PredicateHead.Field(
            scalarFieldOf(executionContext, reference, head.get()) ?: return null,
        )

        else -> return null
    }
    return FusedPredicate(callstack, classified, tail)
}

/** [steps] as operators if every one is fusable with constant operands, else `null`. */
private fun fusableOperators(steps: List<Data>): List<Operation>? = steps.map { step ->
    val op = step as? Operation ?: return null
    if (op.id !in FUSABLE_PREDICATE_OPS) return null
    if (!op.params.orEmpty().values.all(::isConstantOperand)) return null
    op
}

/**
 * A literal: a value or constant, or a list of them. ⚠ A [Reference] is a `TConstant` too, and an
 * [Operation] or a list holding either is a *callstack* — an operand computed per row — so all
 * three are checked first and refused.
 */
private fun isConstantOperand(data: Data?): Boolean = when (data) {
    null -> true
    is Reference, is Operation -> false
    is TList<*> -> data.toList().all { isConstantOperand(it as? Data ?: return false) }
    is TValue, is TConstant -> true
    else -> false
}

/** [id] if it names a non-optional, non-parameterised, non-composite field of the element schema. */
private fun scalarFieldOf(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    id: String,
): String? {
    val elementSchema = reference.elementSchemaId
        ?.let { executionContext.context.schemaProvider().getSchema(it) } as? CompositeSchema
        ?: return null
    val field = elementSchema.values.toList().firstOrNull { it.id.get() == id } ?: return null
    if (field.isParameterized) return null
    if (field.isOptional.get()) return null
    // A composite field is navigation's first step, not a scalar.
    if (field.isCompositeField) return null
    return id
}
