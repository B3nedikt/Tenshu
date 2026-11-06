package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.IsA
import org.tenshu.core.Lowercase
import org.tenshu.core.Operation
import org.tenshu.core.TString
import org.tenshu.core.collection.list.Drop
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.Take
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.impl.reference.FieldReferenceDto
import org.tenshu.core.id
import org.tenshu.core.common.util.isParameterized
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.entity.Entity
import org.tenshu.core.equalToLogic
import org.tenshu.core.lowercaseLogic
import org.tenshu.core.reference.PropertyTypeReference
import org.tenshu.core.reference.Reference
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.ValueSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.toTBoolean

/**
 * Ordered-window fusion (`docs/proposals/core-ordered-window-fusion.md`) — the
 * fourth peephole in the [evaluateCallstack] family, beside the window fold,
 * the count short-circuit, and the by-key search.
 *
 * A `sortBy(key)` on a raw array whose downstream ops begin with a **finite**
 * `take`/`drop` window and end in a `select` executes as ONE projected pass:
 * the array is read with the select's projection *widened by the sort-key
 * fields/properties*, a bounded stable top-N keeps only the window's rows
 * resident, and the window ops are consumed. This replaces the unfused shape —
 * materialise every row **unprojected**, evaluate the selector callstack per
 * element, sort everything, then discard all but the window.
 *
 * Semantics contract: identical results to the unfused path — same comparator
 * ([compareSortKeys]: same ordering, same nulls-last, same typed errors), same
 * stability (equal keys keep encounter order), same window arithmetic
 * ([foldWindowPrefix]). Any gate miss returns `null` and the unchanged
 * [SortByImplementation] path runs.
 */
internal class OrderedWindowResult(
    val rows: Sequence<Data>,
    /** Window ops consumed from `remaining` — the caller removes exactly this many. */
    val consumedFromRemaining: Int,
)

internal fun tryOrderedWindowFusion(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    sortOp: Operation,
    remaining: List<Data>,
): OrderedWindowResult? {
    val plan = planOrderedWindow(executionContext, reference, sortOp, remaining) ?: return null

    // Widen the select's projection so the sort keys survive the thin read:
    // field keys ride [Projection.keepFields], value-property keys ride
    // [Projection.keepProperties] (the one exception to projection-strips-relations).
    // ⚠ A kept property makes the mapper read the row AS AN ENTITY whatever the
    // reference says (see [Projection.keepProperties]) — which is what lets a
    // property key fuse over `root.files`-shaped composite arrays, and why
    // [readSortKey]'s type-test arm must take the row's type from `Entity.type`
    // in that case and from `schema` otherwise.
    val merged = plan.selectProjection.copy(
        keepFields = plan.selectProjection.keepFields + plan.keptFields,
        keepProperties = plan.selectProjection.keepProperties + plan.keptProperties,
    )

    val rows = executionContext.getArrayData(reference, merged) ?: emptySequence()
    val topN = BoundedStableTopN(plan.comparator, plan.keep)
    var index = 0
    for (element in rows) {
        if (!topN.wouldKeepAny) break
        topN.offer(plan.keyed(element, index++))
    }
    return OrderedWindowResult(plan.window(topN).asSequence(), plan.consumedFromRemaining)
}

/**
 * Everything an ordered-window fusion decides **before** reading a row: the sort keys (gate 3),
 * the finite window (gate 4), the downstream `select`'s projection, and the comparator. Shared by
 * the raw-array fusion above and the filter-aware one ([tryFilteredOrderedWindowFusion]), so the
 * two cannot disagree on which keys fuse, how a window folds or how ties break.
 *
 * `null` = a gate missed; fall through.
 */
internal class OrderedWindowPlan(
    val accesses: List<Pair<SortKeyAccess, Boolean>>,
    val fold: WindowFold,
    /** Ops of the `remaining` passed in that the window consumed. */
    val consumedFromRemaining: Int,
    /** The downstream `select`'s own projection, before any widening. */
    val selectProjection: Projection,
) {
    val keep: Int = (fold.offset.toLong() + fold.limit.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    val keptFields: Set<String> get() = accesses.mapNotNull { (a, _) -> a.keptField }.toSet()
    val keptProperties: Set<String> get() = accesses.mapNotNull { (a, _) -> a.keptProperty }.toSet()

    /**
     * Same lexicographic comparator as [SortByImplementation], plus the encounter-order
     * tie-break that makes the bounded selection stable.
     */
    val comparator: Comparator<KeyedRow> = Comparator { a, b ->
        for (i in accesses.indices) {
            val cmp = compareSortKeys(a.keys[i], b.keys[i])
            if (cmp != 0) return@Comparator if (accesses[i].second) -cmp else cmp
        }
        a.index.compareTo(b.index)
    }

    fun keyed(element: Data, index: Int): KeyedRow =
        KeyedRow(element, accesses.map { (access, _) -> readSortKey(element, access) }, index)

    /** The winners with the window's offset applied — what the consumed ops would have left. */
    fun window(topN: BoundedStableTopN): List<Data> =
        topN.result().drop(fold.offset).map { it.element }
}

internal fun planOrderedWindow(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    sortOp: Operation,
    remaining: List<Data>,
): OrderedWindowPlan? {
    // Gate 3 (proposal §3.1, widened by `drive-sorted-folder-paging.md` §4.4): every key —
    // primary and each thenBy — must be one of the [SortKeyAccess] kinds: a stored field, a
    // stored field through pure scalar transforms, a scalar value property, or a type test.
    // Anything else (dynamic properties, ops on the row, composite keys) falls through.
    val keys = parseSortKeys(sortOp.params) ?: return null
    val accesses = keys.map { (selector, descending) ->
        val access = classifySortKey(executionContext, reference, selector) ?: return null
        access to descending
    }

    // Gate 4: the ops after the sort must begin with a drop/take window whose
    // folded limit is finite — an unbounded window degenerates to a full sort.
    val firstWindowOp = remaining.firstOrNull() as? Operation ?: return null
    if (firstWindowOp.id != Drop.ID && firstWindowOp.id != Take.ID) return null
    val fold = foldWindowPrefix(firstWindowOp, remaining.drop(1)) ?: return null
    if (fold.limit == Int.MAX_VALUE) return null
    val consumed = 1 + fold.consumedFromRemaining

    // A `select` must consume the result: the fused read applies a projection,
    // and the [Projection] contract licenses that only when a downstream select
    // rebuilds what it keeps. No select ⇒ thin rows would escape ⇒ fall through.
    val nextOps = remaining.drop(consumed)
    val projection = nextOps.firstOrNull()
        ?.let { captureProjection(it, nextOps.drop(1)) }
        ?: return null

    return OrderedWindowPlan(accesses, fold, consumed, projection)
}

internal class KeyedRow(val element: Data, val keys: List<Data?>, val index: Int)

/** A run of consecutive `SortBy` ops folded to its last member — see [foldSortByRun]. */
internal class SortByRun(
    /** The op whose keys are the whole run's keys. */
    val op: Operation,
    /** Ops of `remaining` the run consumed — the caller removes exactly this many. */
    val consumedFromRemaining: Int,
)

/**
 * The `thenBy` fold on the client **appends** a new `SortBy` carrying the full key list and
 * leaves the prior one on the chain (`SortByRo.foldThenKey`: the chain wrapper cannot be
 * truncated), so `sortBy(A).thenBy(B).thenBy(C)` arrives as THREE ops — `SortBy(A)`,
 * `SortBy(A, B)`, `SortBy(A, B, C)` — ahead of the window. The gate looks at one `SortBy` and
 * expects a window next, so without this it saw `SortBy(A)` followed by another `SortBy` and
 * fell through: every tie-broken sort — Drive's entire "Sort by" menu — ran unfused, three
 * full in-memory passes per page (`drive-sorted-folder-paging.md` §10.6, the fifth blocker).
 *
 * A run of consecutive `SortBy` ops in which each op's keys **extend** the previous op's
 * (same primary and same prefix of `thenBy` keys, in the same directions, plus at least one
 * more) is exactly the fold's output, and its last member alone produces the same order:
 * every pass is stable, and rows equal under the full key are equal under each prefix, so the
 * earlier passes never reorder them. So the run folds to its last op and the prefixes are
 * consumed. ⚠ Two *unrelated* sorts (`sortBy { a }.sortBy { b }`, where `b`'s ties keep
 * `a`'s order) are not a run and are left alone; a fusion that changed their result would
 * break the semantics contract. Selectors are compared structurally ([sameData]) because
 * after the wire the ops are fresh parses, not the shared instances the client built.
 */
internal fun foldSortByRun(first: Operation, remaining: List<Data>): SortByRun {
    var last = first
    var lastKeys = parseSortKeys(first.params) ?: return SortByRun(first, 0)
    var consumed = 0
    for (protocol in remaining) {
        val op = protocol as? Operation ?: break
        if (op.id != SortBy.ID) break
        val keys = parseSortKeys(op.params) ?: break
        if (!keys.extends(lastKeys)) break
        last = op
        lastKeys = keys
        consumed++
    }
    return SortByRun(last, consumed)
}

private fun List<Pair<List<Data>, Boolean>>.extends(prefix: List<Pair<List<Data>, Boolean>>): Boolean {
    if (size <= prefix.size) return false
    return prefix.indices.all { i ->
        val (selector, descending) = prefix[i]
        val (candidate, candidateDescending) = this[i]
        descending == candidateDescending &&
            selector.size == candidate.size &&
            selector.indices.all { j -> sameData(selector[j], candidate[j]) }
    }
}

/**
 * Structural equality for the shapes a selector callstack carries — an op with its params, a
 * reference, a property relation, a value, a list. Anything else compares by `==`, and an
 * unknown shape that is not `==` simply does not fold: falling through is always safe.
 */
private fun sameData(a: Data?, b: Data?): Boolean {
    if (a == null || b == null) return a == b
    if (a === b) return true
    return when {
        a is Operation && b is Operation ->
            a.id == b.id && sameParams(a.params, b.params)

        a is StaticPropertyRelation && b is StaticPropertyRelation ->
            a.reference.get() == b.reference.get()

        a is Reference && b is Reference ->
            a.id == b.id && a.get() == b.get()

        a is TList<*> && b is TList<*> -> {
            val left = a.toList()
            val right = b.toList()
            left.size == right.size && left.indices.all { sameData(left[it] as? Data, right[it] as? Data) }
        }

        a is org.tenshu.core.TValue || a is org.tenshu.core.TConstant ->
            a.id == b.id && equalToLogic(a, b).get()

        else -> a == b
    }
}

private fun sameParams(a: Map<String, Data>?, b: Map<String, Data>?): Boolean {
    val left = a.orEmpty()
    val right = b.orEmpty()
    return left.keys == right.keys && left.all { (key, value) -> sameData(value, right[key]) }
}

/**
 * How a fused sort key is read off a projected row. Four kinds, each cheap by construction —
 * a value the thin row already carries, at most passed through a pure scalar op:
 *
 *  - [Field] — a composite scalar field, read directly off the loaded row.
 *  - [FieldTransform] — a field followed only by **pure scalar** ops from
 *    [PURE_SCALAR_TRANSFORMS] (`path.lowercase()`), applied to the read field. This is what
 *    keeps a case-insensitive Name key and tie-break on the fused path
 *    (`drive-sorted-folder-paging.md` D4).
 *  - [ValueProperty] — a stored scalar value property, read off the row's kept properties.
 *  - [TypeIs] — a type test (`isA<Folder>()`): the row's own type id checked for subtype.
 *    The id rides the stored entry and survives deserialisation, so the key costs nothing
 *    beyond a cached schema walk per distinct type.
 *
 * The bargain is the same one `filter` strikes: any selector stays *correct* on the unfused
 * path; only these shapes are recognised as *fast*. An arbitrary predicate key
 * (`sortBy { isMedia() }`) still falls through to the per-row interpreter.
 */
internal sealed interface SortKeyAccess {
    /** The field the thin read must keep for this key, if any. */
    val keptField: String? get() = null

    /** The value property the thin read must keep for this key, if any. */
    val keptProperty: String? get() = null

    /** A composite scalar field — read directly off the loaded row's fields. */
    class Field(val id: String) : SortKeyAccess {
        override val keptField: String get() = id
    }

    /** A composite scalar field passed through [transforms], in order. */
    class FieldTransform(val id: String, val transforms: List<(Data) -> Data>) : SortKeyAccess {
        override val keptField: String get() = id
    }

    /** A stored scalar value property — read off the row's kept properties map. */
    class ValueProperty(val id: String) : SortKeyAccess {
        override val keptProperty: String get() = id
    }

    /**
     * A type test against [typeId]; [isSubtype] answers for a row's own type id and memoises,
     * so a folder listing pays two schema walks, not one per row.
     */
    class TypeIs(val typeId: String, val isSubtype: (rowTypeId: String) -> Boolean) : SortKeyAccess
}

/**
 * The scalar ops a fused key may pass a stored field through — each the same pure logic its
 * `OperationImplementation` dispatches, so the fused and unfused keys are computed by one
 * function. Grows by one line per op; an op with parameters would need its params threaded
 * through and is not admitted by [classifySortKey] today.
 */
private val PURE_SCALAR_TRANSFORMS: Map<String, (Data) -> Data> = mapOf(
    Lowercase.ID to ::lowercaseLogic,
)

/**
 * Classify a selector as a fusable [SortKeyAccess], or `null` to fall through.
 *
 *  - A [StaticPropertyRelation] (the `sortBy(File.LastModifiedAt)` property overload) must
 *    name a [ValueSchema] relation. ⚠ Whether the array reads as entities is deliberately *not*
 *    a condition: keeping the property makes the mapper read the row as an entity, so the key is
 *    always there to read ([Projection.keepProperties]). Reinstating an `isEntity` refusal here
 *    costs a `root.files` date sort two store reads per row (`drive-sorted-folder-paging.md`
 *    §3 B2).
 *  - A bare field [Reference] (the `sortBy { start }` lambda shape) must name a
 *    non-parameterised, non-optional scalar field on the element schema; it may be followed
 *    by pure scalar ops ([PURE_SCALAR_TRANSFORMS]) and nothing else.
 *  - An [IsA] op alone is a type test.
 *
 * Dynamic properties ([PropertyTypeReference]) are never fused — their resolution can be
 * arbitrarily expensive. Nor is any other op on the row.
 */
internal fun classifySortKey(
    executionContext: ExecutionContext,
    reference: ArrayDatabaseReference,
    selector: List<Data>,
): SortKeyAccess? {
    val head = selector.firstOrNull() ?: return null
    val tail = selector.drop(1)
    val schemaProvider = executionContext.context.schemaProvider()

    if (head is StaticPropertyRelation) {
        if (tail.isNotEmpty()) return null
        val id = head.reference.get()
        return if (schemaProvider.getSchema(id) is ValueSchema) {
            SortKeyAccess.ValueProperty(id)
        } else {
            null
        }
    }

    if (head is Reference && head !is PropertyTypeReference) {
        val id = head.get()
        val elementSchema = reference.elementSchemaId
            ?.let { schemaProvider.getSchema(it) } as? CompositeSchema
            ?: return null
        val field = elementSchema.values.toList().firstOrNull { it.id.get() == id }
            ?: return null
        if (field.isParameterized) return null
        // Optional fields fall through: the unfused per-element read (`getField`)
        // rejects them, and the fusion must not be more permissive than the path
        // it replaces.
        if (field.isOptional.get()) return null
        if (tail.isEmpty()) return SortKeyAccess.Field(id)
        val transforms = tail.map { step ->
            val op = step as? Operation ?: return null
            if (!op.params.isNullOrEmpty()) return null
            PURE_SCALAR_TRANSFORMS[op.id] ?: return null
        }
        return SortKeyAccess.FieldTransform(id, transforms)
    }

    if (head is Operation && head.id == IsA.ID && tail.isEmpty()) {
        val typeId = (head.params?.get(IsA.Params.TYPE_ID) as? TString)?.get() ?: return null
        val memo = HashMap<String, Boolean>()
        return SortKeyAccess.TypeIs(typeId) { rowTypeId ->
            memo.getOrPut(rowTypeId) {
                rowTypeId == typeId || (schemaProvider.getSchema(rowTypeId)?.isSubtypeOf(typeId) ?: false)
            }
        }
    }

    return null
}

/**
 * Read one key off a loaded (projection-thinned) row. Mirrors the unfused
 * reads: the field arms are `resolveReference`'s `isField` shape (plus the
 * same pure logic the transform ops dispatch), the property arm is
 * [SortByImplementation]'s `getProperty`, and the type-test arm is [isALogic]'s
 * own-type rule — ⚠⚠ `Entity.type` for an entity, `schema` for a composite; an
 * entity's `schema` is always `Entity.Schema`, so reading that would call every
 * folder not-a-folder the moment a property key makes the scan read as entities.
 */
internal fun readSortKey(element: Data, access: SortKeyAccess): Data? = when (access) {
    is SortKeyAccess.Field -> readField(element, access.id)

    is SortKeyAccess.FieldTransform -> readField(element, access.id)?.let { value ->
        access.transforms.fold(value) { acc, transform -> transform(acc) }
    }

    is SortKeyAccess.ValueProperty -> (element as? Entity<*>)?.getProperty(access.id)

    is SortKeyAccess.TypeIs -> access.isSubtype(
        (element as? Entity<*>)?.type?.get() ?: element.id,
    ).toTBoolean()
}

internal fun readField(element: Data, id: String): Data? = when (element) {
    is Entity<*> -> element.fields[FieldReferenceDto(id)]
    is Composite -> element.getField<Data>(id)
    else -> null
}

/**
 * The best [keep] rows per [comparator], in comparator order, offered one at a time. An
 * array-backed max-heap holds the *worst kept* row at the root, so only O(keep) rows stay
 * resident and each of the N offers costs O(log keep) — no full O(N log N) sort, no full-list
 * residency. [comparator] must already encode the stable tie-break (encounter index), which is
 * what makes a heap-based selection order-deterministic for equal keys.
 *
 * Push-shaped rather than over a `Sequence` so the filter-aware fusion can feed it from a
 * recursive walk without materialising the walk's output first.
 */
internal class BoundedStableTopN(
    private val comparator: Comparator<KeyedRow>,
    private val keep: Int,
) {
    private val heap = ArrayList<KeyedRow>(minOf(keep.coerceAtLeast(0), INITIAL_HEAP_CAPACITY))

    /** `false` for a `take(0)` window — nothing offered can ever be kept. */
    val wouldKeepAny: Boolean get() = keep > 0

    fun offer(row: KeyedRow) {
        if (keep <= 0) return
        if (heap.size < keep) {
            heap.add(row)
            siftUp(heap.size - 1)
        } else if (comparator.compare(row, heap[0]) < 0) {
            heap[0] = row
            siftDown()
        }
    }

    fun result(): List<KeyedRow> = heap.sortedWith(comparator)

    private fun swap(i: Int, j: Int) {
        val t = heap[i]; heap[i] = heap[j]; heap[j] = t
    }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (comparator.compare(heap[i], heap[parent]) > 0) {
                swap(i, parent); i = parent
            } else break
        }
    }

    private fun siftDown() {
        var i = 0
        while (true) {
            val left = 2 * i + 1
            val right = left + 1
            var largest = i
            if (left < heap.size && comparator.compare(heap[left], heap[largest]) > 0) largest = left
            if (right < heap.size && comparator.compare(heap[right], heap[largest]) > 0) largest = right
            if (largest == i) break
            swap(i, largest); i = largest
        }
    }
}

private const val INITIAL_HEAP_CAPACITY = 1024

/**
 * **The proxy-side bounded read** (`core-ordered-window-fusion.md` F3 §6, opened 2026-07-30):
 * the newest [limit] rows of [reference] by [orderByFieldId], read thin and selected with the same
 * bounded stable top-N the chain fusion uses.
 *
 * ⚠⚠ **Why this exists at all.** [tryOrderedWindowFusion] is a peephole inside [evaluateCallstack] —
 * it serves *chain* readers (ops, clients). A proxy-side `DataObserver` has no chain: it reads
 * `database.getArrayData(reference)`, which returns the **whole relation, unprojected**. That is the
 * unbounded read that blanked Timeline Home at ~10.5k rows, and it is what blocks the reconciliation
 * passes in `timeline-recognition-and-place-naming.md` §10.9/§10.12 — not because the scan is slow,
 * but because **no bounded read was reachable from where an observer stands**.
 *
 * ⚠ Deliberately **not** F3. F3 is the maintained key→DataId index for *sub*-O(N), and it stays
 * deferred exactly as §3.3 says ("do not build it speculatively"). This is the same O(N thin reads +
 * n resident) the fused chain path already achieves — the win is the thin projection and the absence
 * of a per-row interpreted selector, not a smaller complexity class. If a profile ever shows the thin
 * scan itself as the bottleneck, F3 slots underneath *this* function too.
 *
 * ⚠ Deliberately **not** callstack synthesis. Building `sortBy`/`take`/`select` protocol DTOs by hand
 * to re-enter the evaluator would put the fusion's gate encoding (selector shapes, window folding,
 * projection capture) on a caller that has all three inputs already — and getting that encoding
 * subtly wrong is the repo's recurring opaque-`error/internal` failure class. Sharing the *engine*
 * rather than the *protocol* keeps one implementation of the semantics with none of that surface.
 *
 * [keepFields] is the caller's projection; [orderByFieldId] is added to it automatically, since the
 * key must survive the thin read. Ordering, nulls-last and stability match the chain path exactly —
 * same [compareSortKeys], same encounter-order tie-break.
 */
fun ExecutionContext.readOrderedWindow(
    reference: ArrayDatabaseReference,
    orderByFieldId: String,
    limit: Int,
    descending: Boolean = true,
    keepFields: Set<String> = emptySet(),
): List<Data> {
    if (limit <= 0) return emptyList()

    val projection = Projection(
        keepFields = keepFields + orderByFieldId,
        skipUnprojectedComposites = true,
    )
    val rows = getArrayData(reference, projection) ?: return emptyList()

    val comparator = Comparator<KeyedRow> { a, b ->
        val cmp = compareSortKeys(a.keys[0], b.keys[0])
        if (cmp != 0) return@Comparator if (descending) -cmp else cmp
        a.index.compareTo(b.index)
    }

    val key = SortKeyAccess.Field(orderByFieldId)
    val topN = BoundedStableTopN(comparator, limit)
    var index = 0
    for (element in rows) {
        topN.offer(KeyedRow(element, listOf(readSortKey(element, key)), index++))
    }
    return topN.result().map { it.element }
}
