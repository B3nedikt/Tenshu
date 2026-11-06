package org.tenshu.sync.proxy

import org.tenshu.core.entity.DataId
import org.tenshu.core.Data
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.content.ContentEdge
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.db.DbEntryMapper
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.externalPropertyWriter
import org.tenshu.core.file.sync.DeclarableContent

/**
 * ⭐⭐ The backfill's enumeration, driven by the **schema** rather than by Drive's folder tree
 * (`content-plane-refcount.md` §9.6).
 *
 * ```
 * every Syncable(OnDemand) property → its receiver type → every row of that type →
 *     ContentDeclarations.edgesOf(row) → (holder, propertyId, contentId)
 * ```
 *
 * ⭐ **The answer is the schema's, not the port's** (`content-plane-cr2-quorumless.md` §10.11):
 * `ContentReferencing.CONTENT_ID` names the slot, a `ContentDerivation` on each property says how
 * its id derives from it, and [ContentDeclarations] computes the edges from the decoded row. The
 * emit's port (`ExternalPropertyWriter.resolveContentId`) goes through the same declarations, so the
 * two cannot disagree: they share one function.
 *
 * ## ⚠⚠ Why not the tree walk
 *
 * §9.6's invariant is *declared ⇒ walkable*, and a walk of `File` rows from `/` can only ever
 * satisfy it for Drive: it sees neither the **derived** thumbnail ids unless a list names them, nor
 * `Workout.Streams` or `SharedFile`. This asks the schema's declarations about **every** type that
 * carries an `OnDemand` property, so the invariant holds by construction instead of by a list
 * somebody has to remember to extend.
 *
 * The store enumerates by type two ways — [ObjectStore.rowsOfTypes] and the bounded
 * [ObjectStore.scanRowsOfType] — as compaction does.
 *
 * ## ⚠⚠ Bounded, because the unbounded one is fatal on the store that needs it
 *
 * [ObjectStore.scanRowsOfType], not `rowsOfTypes`: a measured store held **77 800 block rows
 * totalling 129 MB** (`core-local-store-orphan-gc.md` G3), and loading a type wholesale to walk it
 * would OOM the devices this is meant to help. Paging keeps peak memory bounded.
 *
 * ⚠⚠ **Completion is keyed on [ObjectStore.TypeScanPage.exhausted], never on a short page.** The
 * scan has two budgets — matches and rows examined — so a short page is ambiguous between "no more
 * rows" and "I stopped early", and reading the second as the first ends the walk with content
 * undeclared. That ambiguity is the reason `exhausted` exists.
 *
 * ## ⚠ Where the store cannot enumerate
 *
 * `rowsOfTypes`/`scanRowsOfType` answer `null` on a store without the capability — the JS
 * `IndexedDbObjectStore` implements neither. `null` is **not** empty (that distinction is the whole
 * point of the SPI's contract), so this reports the walk incomplete and the caller falls back to
 * the injected tree walk rather than concluding the realm has no content.
 */
class SchemaDrivenContentWalk(
    private val executionContext: ExecutionContext,
    private val objectStore: ObjectStore,
    /**
     * Materialises a stored row. ⚠ Needed because `getDataIdHash()` is **one-way** — a row's hash
     * cannot be turned back into the `DataId` `resolveContentId` requires, so the row has to be
     * decoded and its key re-derived, exactly as `SchemaCompactor` does.
     */
    private val mapper: DbEntryMapper,
    /** Used when the store cannot enumerate — the Drive-tree walk, which works on any store. */
    private val fallback: (suspend () -> DeclarableContent.Result)? = null,
    /**
     * A row's content references; `null` builds them from the schema ([ContentDeclarations]), which
     * is what production uses. ⚠ A seam for tests only: the schema's answer cannot throw on demand,
     * and the walk's "could not ask" counting needs one that does.
     */
    private val edgesOf: ((Data) -> List<ContentEdge>?)? = null,
) {

    suspend fun enumerate(): DeclarableContent.Result {
        // ⚠⚠ `null` and empty mean opposite things here, exactly as they do for
        // `ObjectStore.rowsOfTypes`. Empty is "asked, and nothing in this graph declares" — a true
        // answer, and a complete walk. Null is "could not ask", which must never be read as empty:
        // the backfill would latch its marker over a realm it never looked at.
        val byReceiver = onDemandPropertiesByReceiver()
            ?: return fallbackOr("the schema registry is not reachable, so nothing could be asked")
        if (byReceiver.isEmpty()) {
            return DeclarableContent.Result(entries = emptyList(), walkComplete = true, rowsRead = 0)
        }

        // ⚠ The walk does not ask the port, but a runtime with no port emits no content, so it does
        // not walk either.
        executionContext.context.externalPropertyWriter()
            ?: return fallbackOr("no ExternalPropertyWriter registered")
        // ⭐ A row's content references come from the schema's declaration, computed from the decoded
        // row itself — the same answer the block builder and the content emit give, so the walk
        // cannot disagree with either.
        val rowEdges: (Data) -> List<ContentEdge>? = edgesOf
            ?: (executionContext.schemaProvider as? SchemaRegistry)?.let { ContentDeclarations.of(it)::edgesOf }
            ?: return fallbackOr("the schema registry is not reachable, so nothing could be asked")

        val entries = mutableListOf<DeclarableContent.Entry>()
        // ⚠⚠ Per-type, not whole-walk. Falling back on the FIRST type the store cannot enumerate
        // would throw away every entry already collected and drop to the Drive tree walk — so a realm
        // with one awkward type would declare its files and **nothing else** (its `Workout.Streams`,
        // say). Undeclared content is unreclaimable (§9.5), so those bytes would read as orphans to
        // any gauge — and to anything that later frees on one.
        var sawEverything = true
        var enumeratedAnyType = false
        var rowsRead = 0
        var undecodable = 0
        var unkeyed = 0
        var unresolvedProperties = 0
        for ((receiverTypeId, propertyIds) in byReceiver) {
            val pages = pagesOf(receiverTypeId)
            if (pages == null) {
                // ⚠ Skip THIS type and keep the rest. Declaring more is always the safe direction
                // (§9.7), so a partial pass is strictly better than a narrower one — but it must
                // not claim completeness, or the once-only marker latches over what it skipped.
                println("SchemaDrivenContentWalk -> store cannot enumerate $receiverTypeId; skipping it, walk is INCOMPLETE")
                sawEverything = false
                continue
            }
            enumeratedAnyType = true
            for (page in pages) {
                for (row in page) {
                    rowsRead++
                    // ⚠⚠ Every skip below is COUNTED. A bare `continue` would make a walk over a store
                    // it could mostly not read look exactly like a walk over a small realm
                    // ([DeclarableContent.Skipped]).
                    // ⚠ `getEntry` follows nested-composite references back into the store:
                    // rows reference children by hash rather than inlining them.
                    // ⚠⚠ `asEntity = true`: the content slot is a stored VALUE property, and only an
                    // entity carries its property map. Decoded as a bare composite every row would
                    // answer `none` for every property, and the walk would declare nothing.
                    val data = runCatching {
                        mapper.deSerialize(row, asEntity = true) { hash ->
                            objectStore.getObject(hash) ?: error("missing nested row $hash")
                        }
                    }.getOrNull()
                    if (data == null) {
                        undecodable++
                        continue
                    }
                    // ⚠ A row whose type has no DataIdProvider has no key to name its holder, and is
                    // skipped rather than guessed at — the same call SchemaCompactor makes.
                    val dataId = executionContext.getDataId(data)
                    if (dataId == null) {
                        unkeyed++
                        continue
                    }
                    // ⚠ A throw or a null LIST is "could not ask", counted once per content-bearing
                    // property of the type; a `none` edge is "this row has no content here", which
                    // is most rows, and is not a skip.
                    val edges = runCatching { rowEdges(data) }.getOrNull()
                    if (edges == null) {
                        unresolvedProperties += propertyIds.size
                        continue
                    }
                    for (edge in edges) {
                        val contentId = edge.contentId?.takeIf { it.isNotBlank() } ?: continue
                        val propertyId = edge.propertyId
                        entries += DeclarableContent.Entry(
                            // ⚠⚠ The DataId, not the local `row.dataIdHash`: the backfill names the
                            // holder with the same resolver the emit and the tombstone use (§9.14).
                            holder = dataId,
                            propertyId = propertyId,
                            contentId = contentId,
                        )
                    }
                }

            }
        }
        // ⚠⚠ **No type at all** means the store has no enumeration capability — the JS
        // `IndexedDbObjectStore` posture — and there the tree walk is the only way to see anything.
        // ⚠ Distinguished from "some types worked": falling back *then* would trade a partial
        // schema walk for a Drive-only one, which is strictly less coverage: one awkward type would
        // cost every other.
        if (!enumeratedAnyType) {
            return fallbackOr("the store could enumerate none of ${byReceiver.size} declaring types")
        }
        return DeclarableContent.Result(
            entries = entries,
            walkComplete = sawEverything,
            rowsRead = rowsRead,
            skipped = DeclarableContent.Skipped(undecodable, unkeyed, unresolvedProperties),
        )
    }

    /**
     * Rows of [typeId] as a lazy sequence of pages, or `null` when this store cannot enumerate.
     *
     * ⚠ Prefers the **bounded** [ObjectStore.scanRowsOfType] and accepts the unbounded
     * [ObjectStore.rowsOfTypes] only as a second choice. `SqliteObjectStore` (Android, desktop) and
     * the iOS shared replica implement the scan; `InMemoryObjectStore` implements only the
     * unbounded one — which costs nothing there, because the whole map is resident by construction.
     * A store implementing neither answers `null`, which is *not* empty.
     */
    private fun pagesOf(typeId: String): Sequence<List<ObjectStore.DbEntry>>? {
        objectStore.scanRowsOfType(typeId, Long.MIN_VALUE, MATCH_LIMIT, EXAMINE_LIMIT)
            ?: return objectStore.rowsOfTypes(setOf(typeId))?.let { sequenceOf(it) }

        return sequence {
            var cursor = Long.MIN_VALUE
            while (true) {
                val page = objectStore.scanRowsOfType(typeId, cursor, MATCH_LIMIT, EXAMINE_LIMIT)
                    ?: return@sequence
                yield(page.rows)
                cursor = page.nextCursor
                // ⚠⚠ `exhausted`, never `rows.size < matchLimit`. The scan has two budgets, so a
                // short page is ambiguous between "no more rows" and "I stopped early", and reading
                // the second as the first ends the walk with content undeclared. That ambiguity is
                // why `exhausted` exists at all.
                if (page.exhausted) return@sequence
            }
        }
    }

    private suspend fun fallbackOr(reason: String): DeclarableContent.Result {
        val alternative = fallback
        if (alternative == null) {
            println("SchemaDrivenContentWalk -> $reason, and no fallback walk is wired")
            // ⚠ Incomplete, not empty. Empty would let the backfill latch its marker over a realm
            // it never looked at, and the pass runs once by design.
            return DeclarableContent.Result(entries = emptyList(), walkComplete = false)
        }
        println("SchemaDrivenContentWalk -> $reason; falling back to the tree walk, walk is INCOMPLETE")
        // ⚠⚠ **The fallback is never complete, however cleanly it ran.** It is the Drive tree walk,
        // whose coverage is narrower than the declarable set *by construction* — it walks `File`
        // rows from "/" and can never see a `Workout`, a `SharedFile`, or anything else that grows
        // an `OnDemand` property later. Returning its own `walkComplete` would let the backfill latch
        // its once-only marker over types it had not asked about: not "latched on an empty realm" but
        // "latched on a partial one that reported itself complete".
        return alternative().copy(walkComplete = false)
    }

    /**
     * Receiver type → the `Syncable(OnDemand)` properties registered on it.
     *
     * ⚠ Read straight off each property schema's trait list. `registeredRelations()` is precisely
     * the statement *"this id can appear as a slot on that receiver"*, which is the set that can
     * ever hold content — the same source `PropertyKindBaseline` pins for the same reason.
     */
    private fun onDemandPropertiesByReceiver(): Map<String, List<String>>? =
        executionContext.onDemandPropertiesByReceiver()

    companion object {
        /** One page's matches. Small: each match is decoded in full to compute its edges. */
        const val MATCH_LIMIT = 256

        /**
         * Rows examined per page. ⚠ The second budget exists because a store whose only index is
         * the hash pays an unbounded scan to find [MATCH_LIMIT] matches of a rare type.
         */
        const val EXAMINE_LIMIT = 4_096
    }
}

/**
 * Receiver type → the `Syncable(OnDemand)` properties registered on it, for the corpus walk.
 */
internal fun ExecutionContext.onDemandPropertiesByReceiver(): Map<String, List<String>>? {
        // ⚠ `registeredRelations()` is a `SchemaRegistry` capability, not a `SchemaProvider` one.
        // In production the component IS the registry; null here says so rather than pretending.
        // (`executionContext.schemaProvider as? SchemaRegistry` — the same idiom
        // `SchemaCompactionMaintenance` uses.)
        val schemaRegistry = schemaProvider as? SchemaRegistry ?: return null
        // ⚠ One definition, in `:core-common`, shared with `ContentDeclarations`.
        return ContentDeclarations.onDemandPropertiesByReceiver(schemaRegistry)
    }
