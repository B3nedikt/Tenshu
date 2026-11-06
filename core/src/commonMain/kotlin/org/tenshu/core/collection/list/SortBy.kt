package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.TValue
import org.tenshu.core.core.OperationReceiverHolder
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.relation.Property
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.list
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import kotlin.jvm.JvmName
import kotlin.reflect.KClass

/**
 * Reorders a list by a comparable key extracted from each element, returning a
 * new list. Mirrors Kotlin stdlib's `Iterable.sortBy { … }`: the selector lambda
 * yields the sort key. See `docs/proposals/core-chain-sort.md`.
 *
 * ⚠ **Followed by a finite window, a sort is a bounded top-N, not a full sort**
 * (`docs/proposals/core-ordered-window-fusion.md`): `sortBy(k).take(n)` — with
 * `drop` before or after, optionally behind [filter]s and a [traverse] (§9) —
 * reads thin rows and keeps only the window. ⚠ Only for single-step keys and a
 * leading finite `take`; a `drop`-only suffix, a stream op downstream, or a
 * non-fusable [filter] predicate falls back to sorting everything. ⚠ The result is
 * STABLE on both paths: equal keys keep encounter order, which a keyset cursor
 * over ties may rely on.
 *
 * The selector return type is constrained to [TValue]; *comparability* is
 * enforced by the engine at execution time (the runtime comparator accepts
 * `TInteger` — including `Instant`/`UnixInstant` — `TString`, and `TBoolean`,
 * and rejects anything else). A compile-time comparable marker is a deferred
 * nicety; see the proposal §2.2 / §6.
 *
 * **Default overload — element type inferred from the receiver.** For
 * `TList<File>.sortBy { it.createdAt }`, Kotlin picks this (receiver
 * `TList<File>` is more specific than `TList<*>`), `T = File`.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("sortByInferred")
inline fun <reified T : Data> TList<T>.sortBy(
    noinline selector: (T).() -> TValue
): TList<T> = sortByInternal(selector, descending = false)

@Suppress("UNCHECKED_CAST")
@JvmName("sortByDescendingInferred")
inline fun <reified T : Data> TList<T>.sortByDescending(
    noinline selector: (T).() -> TValue
): TList<T> = sortByInternal(selector, descending = true)

/**
 * **Narrowing overload — explicit `<T>` selects a subtype of the receiver's
 * element type.** Co-exists with the default via Kotlin's "more specific
 * receiver wins" rule, exactly like `search` (`core-chain-entity-typing.md` §2.1).
 *
 * The selector returns [TValue] (not a second type parameter) so the narrowing
 * call site can write `list.sortBy<Subtype> { … }` with a single explicit type
 * argument — Kotlin forbids partial type-argument specification, so a `K : TValue`
 * second parameter would force callers to spell out the key type too.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("sortByNarrowed")
inline fun <reified T : Data> TList<*>.sortBy(
    noinline selector: (T).() -> TValue
): TList<T> = sortByInternal(selector, descending = false)

@Suppress("UNCHECKED_CAST")
@JvmName("sortByDescendingNarrowed")
inline fun <reified T : Data> TList<*>.sortByDescending(
    noinline selector: (T).() -> TValue
): TList<T> = sortByInternal(selector, descending = true)

/**
 * Sort by a static **value property** ([Property]) handle instead of a
 * selector lambda — `allFiles.sortByDescending(File.LastModifiedAt)`. The lambda
 * overloads can't take a property handle because the property accessors are on
 * `Entity<…>`, not the bare element type; the handle records the property
 * reference directly and the engine reads it per element. Element type is
 * inferred from the receiver (a `sortBy<Subtype>(property)` narrowing form can be
 * added if a call site needs it).
 */
@JvmName("sortByPropertyInferred")
inline fun <reified T : Data> TList<T>.sortBy(property: Property<*>): TList<T> =
    sortByPropertyInternal(property, descending = false)

@JvmName("sortByDescendingPropertyInferred")
inline fun <reified T : Data> TList<T>.sortByDescending(property: Property<*>): TList<T> =
    sortByPropertyInternal(property, descending = true)

// ── thenBy — secondary sort keys (lexicographic multi-key) ───────────────────
//
// `list.sortBy(A).thenBy(B)` orders by A, then breaks ties by B — the classic
// two-level sort. `thenBy` must follow a `sortBy` (or another `thenBy`): it folds
// its key into the preceding `SortBy` op rather than adding an independent sort
// step (a second sort would re-order by B alone, losing A's precedence). See
// `docs/proposals/core-chain-sort.md` §2.4. Same overload shapes as `sortBy`.

@Suppress("UNCHECKED_CAST")
@JvmName("thenByInferred")
inline fun <reified T : Data> TList<T>.thenBy(
    noinline selector: (T).() -> TValue
): TList<T> = thenByInternal(selector, descending = false)

@Suppress("UNCHECKED_CAST")
@JvmName("thenByDescendingInferred")
inline fun <reified T : Data> TList<T>.thenByDescending(
    noinline selector: (T).() -> TValue
): TList<T> = thenByInternal(selector, descending = true)

@JvmName("thenByPropertyInferred")
inline fun <reified T : Data> TList<T>.thenBy(property: Property<*>): TList<T> =
    thenByPropertyInternal(property, descending = false)

@JvmName("thenByDescendingPropertyInferred")
inline fun <reified T : Data> TList<T>.thenByDescending(property: Property<*>): TList<T> =
    thenByPropertyInternal(property, descending = true)

/**
 * Shared body — all four overloads dispatch the same [SortBy] operation; only
 * the Kotlin static types at the call site differ. Receiver is typed as [Data]
 * (matching `findOperation`'s extension) so this helper sits in `commonMain` and
 * serves both `TList<E>` and `TList<*>` callers.
 */
@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T : Data> Data.sortByInternal(
    noinline selector: (T).() -> TValue,
    descending: Boolean
): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<SortBy<T>>(SortBy.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.sortBy(selector, descending)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T : Data> Data.sortByPropertyInternal(
    property: Property<*>,
    descending: Boolean
): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<SortBy<T>>(SortBy.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.sortByProperty(property.selector() as StaticPropertyRelation, descending)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T : Data> Data.thenByInternal(
    noinline selector: (T).() -> TValue,
    descending: Boolean
): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<SortBy<T>>(SortBy.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.thenBy(selector, descending)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal inline fun <reified T : Data> Data.thenByPropertyInternal(
    property: Property<*>,
    descending: Boolean
): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<SortBy<T>>(SortBy.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.thenByProperty(property.selector() as StaticPropertyRelation, descending)
}

interface SortBy<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun sortBy(selector: (T) -> TValue, descending: Boolean): TList<T>

    fun sortByProperty(projection: StaticPropertyRelation, descending: Boolean): TList<T>

    /**
     * Fold a secondary key into the preceding `sortBy` — the receiver's last
     * chain op MUST be a `SortBy`. The new key's callstack + direction append to
     * the [Params.THEN_SELECTORS] / [Params.THEN_DESCENDINGS] tail so the engine
     * builds a lexicographic comparator (primary first, then each `thenBy` in
     * order).
     */
    fun thenBy(selector: (T) -> TValue, descending: Boolean): TList<T>

    fun thenByProperty(projection: StaticPropertyRelation, descending: Boolean): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/sortBy"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            params = {
                list(id = Params.SELECTOR)
                boolean(id = Params.DESCENDING)
                // Secondary keys (thenBy). Optional + empty for a single-key sort,
                // so a single-key sort's params carry nothing extra.
                // THEN_SELECTORS is a list of callstacks (TList<TList<Data>>) parallel
                // to THEN_DESCENDINGS (TList<TBoolean>).
                list(id = Params.THEN_SELECTORS, of = listType(Data.Schema), isOptional = true)
                list(id = Params.THEN_DESCENDINGS, of = TBoolean.Schema, isOptional = true)
            },
            returnType = listType("E")
        )
    }

    object Params {
        const val SELECTOR = "$ID/selector"
        const val DESCENDING = "$ID/descending"
        const val THEN_SELECTORS = "$ID/thenSelectors"
        const val THEN_DESCENDINGS = "$ID/thenDescendings"
    }
}
