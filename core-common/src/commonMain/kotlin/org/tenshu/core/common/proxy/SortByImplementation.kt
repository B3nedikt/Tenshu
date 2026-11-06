package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SortBy
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.number.TInteger
import org.tenshu.core.relation.StaticPropertyRelation
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.Comparable

/**
 * Reorders a list by a selector-extracted key. The selector callstack is
 * evaluated against each element (mirroring [FilterImplementation]'s per-element
 * walk), the resulting keys are compared by [compareKeys], and the list is
 * sorted stably — reversed when `descending`.
 *
 * Comparability is enforced here, at execution time: [compareKeys] handles the
 * primitive value types (`TInteger` — covering `Instant`/`UnixInstant` via
 * inheritance — `TString`, `TBoolean`) and errors on anything else. This is the
 * "registration/runtime rejection" path from `docs/proposals/core-chain-sort.md`
 * §2.2; a compile-time comparable marker is deferred.
 *
 * Asynchronous selectors (a key evaluation that defers to an [AsyncReference])
 * are not supported in this first cut — sort keys are simple value/property
 * accesses. Tracked as a follow-up in the proposal.
 */
internal class SortByImplementation : SyncOperationImplementation {

    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // Ordered (selector callstack, descending) keys: primary first, then each
        // `thenBy` in order — the lexicographic precedence.
        val keys: List<Pair<List<Data>, Boolean>> =
            parseSortKeys(params) ?: error("No selector provided")

        val array = executionContext.resolveList(caller)
            ?: error("sortBy must be called on a list. Caller: $caller")

        // Per element, extract its key value for every sort key (in order).
        val keyed: List<Pair<Data, List<Data?>>> = array.toList().map { element ->
            element to keys.map { (selector, _) -> extractKey(executionContext, element, selector) }
        }

        // Lexicographic comparator: first key whose comparison is non-zero decides,
        // with that key's own descending flag applied. Stable `sortedWith` keeps
        // input order for fully-equal rows.
        val comparator = Comparator<Pair<Data, List<Data?>>> { a, b ->
            for (i in keys.indices) {
                val cmp = compareSortKeys(a.second[i], b.second[i])
                if (cmp != 0) return@Comparator if (keys[i].second) -cmp else cmp
            }
            0
        }
        val sorted = keyed.sortedWith(comparator).map { it.first }

        @Suppress("UNCHECKED_CAST")
        return sorted.toTList(executionContext.context) as T
    }

    /**
     * Extract one sort key value off [element] for [selector]. A value-property
     * sort (`sortBy(File.LastModifiedAt)`) records a single [StaticPropertyRelation]
     * step; a lambda selector (`sortBy { name }`) records a callstack, walked per
     * element (mirroring [FilterImplementation]).
     *
     * ⚠⚠ The property arm reads the loaded row's own `properties` map **only when
     * there is one**, and otherwise re-reads the row as an entity — the same
     * `getData(dataId, asEntity = true)` re-load `ReferenceResolver`'s static
     * property resolution does, minus its throw on a row that isn't there.
     * Reading `getProperty` alone was silently wrong for the most ordinary case in
     * the codebase: an array whose element schema is not itself an `Entity` subtype
     * (`File : Composite`, so `defaultFileSystem.root.files` resolves with
     * `isEntity = false`) deserialises its rows as plain [Composite]s, so
     * `element as? Entity<*>` is null and EVERY row's key came back null. Every
     * comparison then ties and the order is decided by whatever breaks ties — a
     * `thenBy`, else encounter order — *identically in both directions*. That is
     * what made Drive's "Date modified" / "Date created" direction toggle a no-op
     * (it reordered nothing) while "Name" worked: `File.Name` derives from the
     * stored `path` FIELD through the callstack arm below, and needs no entity.
     * The same reason `File.Size` sorting and Home's "Recent files" were wrong.
     *
     * A projected read is the second way to arrive here with no properties on the
     * row: [Projection] licenses dropping every relation precisely because engine
     * relation reads go through `resolveProperty` — which this arm now does.
     */
    private fun extractKey(
        executionContext: ExecutionContext,
        element: Data,
        selector: List<Data>,
    ): Data? {
        val propertyKey = selector.singleOrNull() as? StaticPropertyRelation
        if (propertyKey != null) {
            val propertyId = propertyKey.reference.get()
            (element as? Entity<*>)?.getProperty<Data>(propertyId)?.let { return it }
            // Re-read the row as an entity to reach its stored properties. Tolerant by
            // design: a row with no [DataId], or one that never reached the store (an
            // in-memory fixture), simply has no key — `null` sorts per
            // [compareSortKeys]'s missing-key rule rather than throwing mid-listing.
            val dataId = executionContext.getDataId(element) ?: return null
            val stored = executionContext.database.getData(dataId, asEntity = true) as? Entity<*>
            return stored?.getProperty(propertyId)
        }

        val elementWithContext = if (element is Entity<*>) {
            val dataId = executionContext.getDataId(element)
            if (dataId != null) EntityDatabaseReference(element, dataId) else element
        } else element

        // Phase 4 of `docs/proposals/core-error-channel.md`: evaluateCallstack returns
        // Data? and throws TenshuException on typed failure; the wire catch at
        // `LocalTaskExecutor` wraps to `TResult.Failure`.
        val result = evaluateCallstack(
            executionContext = executionContext,
            contextData = elementWithContext as Composite,
            callstack = selector,
            allowDeferring = false
        )
        if (result is AsyncReference) {
            error("sortBy does not yet support asynchronous selectors")
        }
        return result
    }

}

/**
 * Parse a `SortBy` op's params into ordered (selector callstack, descending)
 * keys — primary first, then each `thenBy` in order (the lexicographic
 * precedence). `null` when no selector is present. Top-level so the
 * ordered-window fusion ([tryOrderedWindowFusion]) parses the identical shape.
 */
internal fun parseSortKeys(params: Map<String, Data>?): List<Pair<List<Data>, Boolean>>? {
    val primarySelector = (params?.get(SortBy.Params.SELECTOR) as? TList<*>)
        ?.toList()
        ?: return null
    val primaryDescending = (params[SortBy.Params.DESCENDING] as? TBoolean)?.get() ?: false

    // Secondary keys (`thenBy`), parallel lists; empty for a single-key sort.
    // Each THEN_SELECTORS entry is itself a callstack (a TList<Data>).
    val thenSelectors = (params[SortBy.Params.THEN_SELECTORS] as? TList<*>)
        ?.toList()
        ?.map { (it as TList<*>).toList() }
        .orEmpty()
    val thenDescendings = (params[SortBy.Params.THEN_DESCENDINGS] as? TList<*>)
        ?.toList()
        ?.map { (it as TBoolean).get() }
        .orEmpty()

    return listOf(primarySelector to primaryDescending) + thenSelectors.zip(thenDescendings)
}

/**
 * Order on the primitive comparable value types. `TInteger` must come first:
 * `Instant` / `UnixInstant` are `TInteger` subtypes and compare by their
 * epoch-millis `Long`.
 *
 * Composite keys route through the [Comparable] trait (the composite flavour,
 * §2.2). The trait is *consulted* here, but key-extraction is deferred
 * (§6.7), so a composite key is always an error today — with a message that
 * distinguishes "declare the trait" from "trait present, resolution pending."
 *
 * Top-level so the fused ([tryOrderedWindowFusion]) and unfused sort paths share
 * one comparator — identical ordering AND identical typed errors are the
 * fusion's semantics contract.
 */
internal fun compareSortKeys(a: Data?, b: Data?): Int = when {
    // Missing value-property keys sort last (ascending); reversed for descending.
    a == null && b == null -> 0
    a == null -> 1
    b == null -> -1
    a is TInteger && b is TInteger -> a.get().compareTo(b.get())
    a is TString && b is TString -> a.get().compareTo(b.get())
    a is TBoolean && b is TBoolean -> a.get().compareTo(b.get())
    a is Composite && b is Composite -> error(compositeKeyMessage(a))
    else -> error(
        "sortBy selector must yield a comparable value " +
            "(TInteger / TString / TBoolean); got " +
            (a?.let { it::class.simpleName } ?: "null")
    )
}

private fun compositeKeyMessage(key: Composite): String {
    val typeId = key.schema.id.get()
    return if (key.schema.hasTrait(Comparable.ID)) {
        "sortBy key '$typeId' is Comparable, but composite-key sort " +
            "resolution is not implemented yet (core-chain-sort §6.7). " +
            "Project the selector to a value for now."
    } else {
        "sortBy key '$typeId' is not Comparable; add the Comparable trait to " +
            "its schema, or project the selector to a value " +
            "(TInteger / TString / TBoolean)."
    }
}
