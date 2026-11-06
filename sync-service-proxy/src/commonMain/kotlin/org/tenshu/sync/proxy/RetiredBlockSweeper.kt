package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService

/**
 * Collect block rows that leaked before a block was deleted on leaving its queue
 * (`core-local-store-orphan-gc.md` G3).
 *
 * G1 stops new orphans: a block is deleted when it leaves a queue. It does nothing for a store
 * whose orphans predate it — tens of thousands of `sync/block` rows of which a hundred or so are
 * referenced — and G1 alone leaves them there forever. This sweep is what reaches devices already
 * in that state.
 *
 * ## Shape
 *
 * A block row is garbage iff no pending queue references it. The queues are tiny, the block
 * population is not — so the sweep pages *rows* and tests each against a
 * small in-memory keep-set, rather than doing it the other way round.
 *
 * Each pass is **bounded** — [PAGE] rows collected, [EXAMINE_PAGE] rows *looked at* — and
 * **resumable** (`hash > cursor`, persisted), so it can run a slice per sync tick and never block
 * anything. That boundedness is the requirement, not an optimisation: an unbounded sweep at
 * startup would reproduce the iOS launch-watchdog kill this whole track exists to remove.
 *
 * ⚠⚠ **Both budgets and a durable cursor are required.** Bounding only the rows *collected*
 * bounds nothing on a store whose sole index is the hash: the scan has to read every row to prove
 * the type is exhausted, so once the sweep has done its job — few blocks left, [PAGE] never
 * reached — each pass would read the WHOLE store (on an iPhone, ~100k blob reads under the
 * route-1 cross-process flock), and with an in-memory cursor it would do so once per process.
 *
 * ## ⚠⚠ Why refusing is the safe default
 *
 * Deleting a row that *is* queued destroys an **unsent write**. The keep-set therefore has to be
 * complete, and completeness is not something to assume:
 *
 *  - the personal queue and the metered-exempt queue are always readable;
 *  - **shared realms each have their own queue**, and they are only discoverable through
 *    [RealmRegistry]. Without it this sweep cannot prove a block is unreferenced, so it
 *    **refuses to run at all** rather than guess.
 *
 * A store that cannot enumerate ([ObjectStore.scanRowsOfType] → `null`, today JS) is
 * likewise refused — the same posture `SchemaCompactor` takes, and for the same reason.
 *
 * Deletion itself goes through [deleteRetiredBlock], so the owned-child rule (the `hlc` row) stays
 * single-sourced with G1 rather than being restated here.
 */
class RetiredBlockSweeper internal constructor(
    private val executionContext: ExecutionContext,
    private val realmRegistry: RealmRegistry?,
    private val objectStore: ObjectStore?,
    /** Rows examined per pass; overridable so tests can exercise a budgeted stop cheaply. */
    private val examineLimit: Int = EXAMINE_PAGE,
) {

    /**
     * Cursor into the hash-ordered row space, lazily loaded from the store on first use.
     *
     * ⚠ `null` here means "not read yet", which is NOT the same as the store's `null` ("never
     * scanned" ⇒ start at [Long.MIN_VALUE]). Keeping the load lazy matters because the store may
     * be absent, and because construction happens during proxy init where a read is unwelcome.
     */
    private var cursor: Long? = null

    /** Set once a scan reports exhaustion — the population is swept, nothing left to look at. */
    private var finished: Boolean = false

    /** Outcome of one bounded pass, for logging and tests. */
    data class Pass(val scanned: Int, val deleted: Int, val done: Boolean)

    /**
     * Sweep one bounded pass: at most [PAGE] block rows, examining at most [EXAMINE_PAGE] rows to
     * find them. Returns null when the sweep cannot run safely (see the class KDoc) or has already
     * finished.
     */
    fun sweepOnce(): Pass? {
        if (finished) return null
        val store = objectStore ?: return null
        // ⚠ No registry ⇒ shared-realm queues are unknowable ⇒ cannot prove any block is orphaned.
        val registry = realmRegistry ?: return null

        val from = cursor ?: store.loadRetiredBlockSweepCursor()?.also {
            // ⚠⚠ A device that already finished must never scan again — that is the entire point
            // of persisting this. Recomputing it per process is what made a swept store pay a
            // full read of every row at every launch.
            if (it == ObjectStore.SWEEP_COMPLETE) finished = true
        } ?: Long.MIN_VALUE
        if (finished) return null
        cursor = from

        val page = store.scanRowsOfType(
            typeId = SyncBlock.ID,
            afterHash = from,
            matchLimit = PAGE,
            examineLimit = examineLimit,
        ) ?: return null

        // ⚠ `exhausted`, NEVER `rows.size < PAGE`. A pass that stopped on the examine budget
        // legitimately returns few rows — or none — and reading that as completion would end the
        // sweep with garbage still on disk. This is the distinction `TypeScanPage` exists to make.
        if (page.rows.isEmpty()) {
            advancePast(page, store)
            return Pass(scanned = 0, deleted = 0, done = page.exhausted)
        }

        // ⚠⚠ Read BEFORE the cursor moves. A keep-set that cannot be read (a queue-row read that
        // throws: unknown is not empty) ends the pass with nothing deleted AND the cursor where it
        // was, so the same page is swept again next pass. Advanced first, that page would be
        // skipped for good — and on the last page, `SWEEP_COMPLETE` means never retried at all.
        val keep = queuedBlockHashes(registry, store)
        val blockIdIndex = blockIdFieldIndex() ?: return null

        var deleted = 0
        page.rows.forEach { entry ->
            if (entry.dataIdHash in keep) return@forEach

            val blockId = (entry.fields.getOrNull(blockIdIndex) as? ObjectStore.DbValue.StringValue)
                ?.value
                ?: return@forEach
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = SyncBlock.ID,
                keyValue = blockId,
            )
            // Re-read through the ordinary path so the delete sees exactly what a live caller
            // would — and so `deleteRetiredBlock` can walk the schema for owned children.
            val block = executionContext.database.getData(dataId) ?: return@forEach
            executionContext.deleteRetiredBlock(block)
            deleted++
        }

        // Only once the page is processed. A throw above leaves the cursor, so the page is retried;
        // a block deleted already then reads absent and is skipped.
        advancePast(page, store)
        return Pass(scanned = page.rows.size, deleted = deleted, done = page.exhausted)
    }

    /** Persists the sweep past [page]: the next cursor, or `SWEEP_COMPLETE` on the last page. */
    private fun advancePast(page: ObjectStore.TypeScanPage, store: ObjectStore) {
        if (page.exhausted) {
            finished = true
            store.storeRetiredBlockSweepCursor(ObjectStore.SWEEP_COMPLETE)
        } else {
            cursor = page.nextCursor
            store.storeRetiredBlockSweepCursor(page.nextCursor)
        }
    }

    /**
     * Every block hash currently queued for push, across **all** outbound queues: personal and
     * one per registered shared realm. Anything not here is unreferenced.
     */
    private fun queuedBlockHashes(registry: RealmRegistry, store: ObjectStore): Set<Long> {
        val refs = buildList {
            add(personalQueue(SyncService.Collections.PENDING_BLOCKS))
            // ⚠⚠ The tombstone queue is a keep-set source too: the keep-set has to be COMPLETE, and
            // completeness is not something to assume. Omitting a relation that holds a block ref
            // sweeps a still-referenced block, and the eager array read then throws "Entry not
            // found" out of `initSyncService`, bricking the device. Enumerate every relation that
            // can hold a block ref, not the ones that obviously do.
            add(personalQueue(SyncService.Collections.PENDING_TOMBSTONES))
            // ⚠ `sharedRealms()`, NOT `sharedRuntimesWithRelay()`: a realm whose relay is not
            // wired still has a queue that can hold blocks, and missing it would let this sweep
            // delete somebody's unsent writes.
            registry.sharedRealms().forEach { realm ->
                add(
                    personalQueue(
                        SyncService.Collections.PENDING_BLOCKS +
                            RealmRegistry.NAMESPACE_SEP + realm.ownerId
                    )
                )
            }
        }
        // ⚠⚠ Under the DatabaseLock. This read decides what to DELETE, and the
        // page scan above ran unlocked. `DatabaseImpl.addToArray` writes the block row BEFORE the
        // queue row, both under the lock, so the scan can see a block whose queue append has not
        // landed yet. Locked, this read waits for the append and keeps the block; unlocked, it can
        // read the old queue and delete an unsent write.
        // Only these few rows, never the page's scan or deletes, so the lock is held for a handful
        // of reads.
        return executionContext.database.transaction { keepSetUnderLock(refs, store) }
    }

    /** [queuedBlockHashes]'s reads; ⚠ the caller holds the DatabaseLock. */
    private fun keepSetUnderLock(refs: List<ArrayDatabaseReference>, store: ObjectStore): Set<Long> {
        // ⚠⚠ The members as the stored row LISTS them, never decoded. A
        // decode reads each member through `getObject` and drops one it cannot find, and a block
        // missing from the keep-set is one this sweep may delete. Listed is a superset of decoded:
        // it keeps more, never less. A queue-row read that throws ends the pass before the cursor
        // moves (unknown is not empty), so the same page is swept again next pass.
        val hashes = HashSet<Long>()
        for (ref in refs) {
            store.getObject(ref.arrayId.getDataIdHash())
                ?.fields
                ?.forEach { (it as? ObjectStore.DbValue.LongValue)?.value?.let(hashes::add) }
        }
        return hashes
    }

    private fun personalQueue(relationId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = SyncBlock.ID,
            relationId = relationId,
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID,
    )

    /**
     * Positional index of `blockId` in a stored `SyncBlock` row. Derived from the schema, not
     * assumed to be 0 — the store seam is positional by design, but the position is the schema's
     * to decide.
     */
    private fun blockIdFieldIndex(): Int? {
        val schema = executionContext.schemaProvider.getSchema(SyncBlock.ID) as? CompositeSchema
            ?: return null
        val index = schema.values.toList()
            .indexOfFirst { field: Field -> field.id.get() == SyncBlock.Fields.BLOCK_ID }
        return index.takeIf { it >= 0 }
    }

    companion object {
        /**
         * Rows per pass — sized against the store's write mailbox, not picked round.
         *
         * Each swept block costs **two** write ops (the row and its owned `hlc` child), and
         * `SqliteObjectStore.WRITE_MAILBOX_CAPACITY` is 512 with honest blocking backpressure
         * (`trySendBlocking` parks the caller when full). At 250 a pass enqueues ~500 ops — about
         * one mailbox — so it never parks the sync tick behind a queue it just filled several
         * times over.
         *
         * A 78k-row backlog therefore clears over a few hundred ticks: hours of ordinary running
         * for a one-time cleanup, which is the right trade against ever stalling a tick.
         */
        const val PAGE: Int = 250

        /**
         * Rows **examined** per pass — the budget without which [PAGE] bounds nothing on a store
         * with no type index.
         *
         * ⚠⚠ These are different quantities and only one of them is a cost. On a store with
         * no type index a row examined is a file read + decode; a row *matched* is free by
         * comparison. When the type is nearly extinct — which is the steady state after the sweep
         * has done its job — [PAGE] is never reached, so a match-only bound degenerates to
         * "read the entire store" (~100k reads per pass on a phone).
         *
         * 2 000 keeps a pass at roughly the same order of IO as the ~500 write ops [PAGE] admits,
         * so neither budget dominates, and it clears a 100k-row store in ~50 passes — a couple of
         * minutes of ordinary ticking, once, ever (the cursor is durable).
         *
         * ⚠ Nothing is skipped by stopping early: the scan resumes from
         * [ObjectStore.TypeScanPage.nextCursor], and only `exhausted` ends the sweep.
         */
        const val EXAMINE_PAGE: Int = 2_000
    }
}
