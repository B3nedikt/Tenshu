package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.schema.migration.PayloadUpcaster
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.BlockPayloadEnvelope
import org.tenshu.sync.common.EnvelopeAuth
import org.tenshu.sync.common.SyncManager

/**
 * The **fenced-device outbound tail** (`core-schema-evolution.md` §8.3, last
 * bullet): after this device's schema moves forward, repair the *already
 * queued* pending blocks so nothing below the current version is pushed.
 *
 * ## Why the queue goes stale in the first place
 *
 * A block's version stamp is sealed into its ciphertext at **enqueue** time
 * ([SyncObserver]'s `blockStamp`) and is never touched again — the payload is
 * encrypted, so a drain cannot even see it. So a device that queues writes,
 * then updates, then drains, pushes blocks stamped at the *old* vector. The
 * pull fence (§7.3) makes this the normal case rather than a corner: a fenced
 * device is pull-fenced but **push-allowed by design**, so it keeps queuing —
 * and everything it queued before the update is stale-stamped.
 *
 * That is harmless *today*: peers upcast old blocks on read (§8.2, the §3
 * asymmetric contract). It stops being harmless once E4 compaction advances
 * the **readable floor** past the stamped version — the same push then lands
 * as a block nobody can decode, and the write is lost. Repairing the queue at
 * the source costs one pass over a normally-empty queue and closes it
 * permanently.
 *
 * ## In-place restamp, not regeneration
 *
 * §8.3 describes this as regenerating the journal "from the migrated store".
 * That is not reachable: a queued block addresses its entity only as
 * `targetEntityIdHash` — an opaque hash the relay indexes by, which cannot be
 * inverted back to a `DataId` to re-read the row. In-place restamp gets the
 * same result through the machinery that already exists, and is strictly more
 * faithful: [PayloadUpcaster] is the *same* upcast a receiving peer would run
 * on this block (E2c), just run locally before the push instead of remotely
 * after it, so the block keeps its identity (`blockId`, `hlc`,
 * `targetEntityIdHash`) and its point-in-time content rather than being
 * replaced by a newer snapshot.
 *
 * ## Scope and cost
 *
 * - **One-shot per process.** The declared version vector is fixed for the
 *   process, so a queue that is clean once stays clean — every block enqueued
 *   afterwards is sealed with the current stamp by construction.
 * - **One AEAD decrypt per queued block, once.** The stamp lives inside the
 *   ciphertext (House Rule #1), so there is no way to read it without
 *   decrypting — but a block whose stamp already matches stops right there:
 *   no parse, no re-serialize, no re-encrypt, no store write. A large
 *   backlog therefore costs one decrypt pass on the first enabled tick and
 *   nothing on any tick after.
 * - **Inert without migration chains.** With none registered (production
 *   today) there is nothing a stale stamp could even be upcast *from*: the
 *   pass marks itself done without decrypting anything.
 * - **Runs before any drain**, from [SyncScheduler.tickOnce]'s enabled path,
 *   so keys are present. A locked realm defers it to the next tick rather
 *   than consuming the one-shot.
 * - **Personal-realm queues only** (the pending + metered-exempt queues), matching
 *   [SchemaPullFence]'s v1 scope: each shared realm's queue is encrypted under its
 *   own `Kr` and belongs with the namespaced-epoch-key work.
 */
internal class PendingBlockRestamper(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    /** The personal-realm outbound queues, in drain order. */
    private val queues: List<ArrayDatabaseReference>,
) {

    private val serializer = BinarySerializer()

    /** This runtime's stamp — byte-identical to the one [SyncObserver] seals with. */
    private val currentStamp: ByteArray by lazy {
        BlockPayloadEnvelope.stampOf(executionContext.schemaProvider, executionContext.context)
    }

    /**
     * Built exactly as [SyncProcessor]'s, and `null` under the same condition: no
     * registered chains ⇒ no version-keyed upcast exists ⇒ nothing here to do.
     */
    private val upcaster: PayloadUpcaster? by lazy {
        val schemas = executionContext.schemaProvider as? SchemaRegistry ?: return@lazy null
        if (schemas.schemaMigrations().isEmpty()) return@lazy null
        PayloadUpcaster(
            live = schemas,
            context = executionContext.context,
            deviceIdProvider = { syncManager.currentRealmContext()?.deviceId ?: "" },
        )
    }

    private var done = false

    /** Blocks resealed at the current stamp. Tick-thread confined; a test handle. */
    internal var restampedCount: Int = 0
        private set

    /** Blocks dropped BY DECISION (below floor / migration drop). Tick-thread confined. */
    internal var droppedCount: Int = 0
        private set

    /**
     * Run the one-shot repair pass. A no-op after it has run once, and after any
     * run that found nothing to fix. Returns without consuming the one-shot when
     * the realm has no keys yet (locked / not yet rehydrated) — the caller ticks
     * again.
     */
    suspend fun restampOnce() {
        if (done) return
        val upcaster = upcaster ?: run {
            done = true
            return
        }
        val identityKey = syncManager.currentRealmContext()?.derivedKeys?.sharedIdentityKey ?: return

        var unreadable = 0
        var missing = 0
        for (queue in queues) {
            val read = readQueue(queue)
            missing += read.missing
            for (data in read.members) {
                val block = data as? SyncBlock ?: continue
                when (val outcome = restamp(block, identityKey, upcaster)) {
                    Outcome.Current -> Unit
                    Outcome.Unreadable -> unreadable++
                    is Outcome.Reseal -> {
                        replace(queue, block, outcome.block)
                        restampedCount++
                        outcome.droppedValues.forEach { reason ->
                            println(
                                "WARN PendingBlockRestamper: value dropped BY DECISION while " +
                                    "restamping queued block ${block.blockId.get()} — $reason"
                            )
                        }
                    }

                    is Outcome.Drop -> {
                        remove(queue, block)
                        droppedCount++
                        println(
                            "WARN PendingBlockRestamper: queued block ${block.blockId.get()} " +
                                "dropped BY DECISION — ${outcome.reason}. It could only have " +
                                "been pushed in a shape no peer can decode."
                        )
                    }
                }
            }
        }
        done = true
        if (restampedCount > 0 || droppedCount > 0 || unreadable > 0 || missing > 0) {
            println(
                "PendingBlockRestamper: outbound queue repaired for the current schema vector — " +
                    "$restampedCount block(s) resealed, $droppedCount dropped BY DECISION, " +
                    "$unreadable left untouched (undecryptable — e.g. an orphaned-owner block " +
                    "the drain quarantines), $missing skipped (row missing from the store)."
            )
        }
    }

    /** What [readQueue] could and could not materialise. */
    private class QueueRead(val members: List<Data>, val missing: Int)

    /**
     * Materialise one queue, tolerating a member whose row is gone from the store.
     *
     * ⚠⚠ **This is the whole reason the class no longer takes the sync lane down with it.** A
     * plain `getArrayData(queue).toList()` resolves every member eagerly and throws
     * `IllegalStateException: Entry not found` on the first dangling one (`DatabaseImpl`'s
     * `objectStore.getObject(hash) ?: error(...)`). That threw out of [restampOnce], which
     * [SyncScheduler.tickOnce] calls *before* the slice bootstrap and both drains — so one
     * dangling ref cost the device **push and pull entirely**, on every tick, forever: `done` is
     * set at the end of the pass, so the throw also guarantees the one-shot is never consumed.
     * Measured on a real iPhone 2026-08-13: 53 identical traces in ~20 s of one launch, sync
     * completely dead, and nothing user-visible because `reportSyncHealth()` sits in the same
     * skipped region. See `tech-debt.md` §Sync → "PendingBlockRestamper throws on a dangling
     * queue member".
     *
     * The shape is [TombstoneRegistry]'s, which learned it the same way on 2026-08-04, and its
     * hard-won rule applies here too: **degrade means skip that member, not abandon the pass.**
     * The fast path is byte-for-byte the read this always did, so a healthy store pays nothing.
     *
     * ⚠ A skipped member stays queued and stays dangling — this deliberately does **not** try to
     * remove it. Removal needs a `DataId` that a missing row cannot supply, and destroying queue
     * entries to tidy up is the exact direction that has already cost this repo unsent writes
     * (`RetiredBlockSweeper`'s keep-set incident). The drain deals with it on its own terms.
     */
    private fun readQueue(queue: ArrayDatabaseReference): QueueRead {
        try {
            return QueueRead(executionContext.database.getArrayData(queue)?.toList().orEmpty(), missing = 0)
        } catch (_: IllegalStateException) {
            // A member's row is gone. Fall through and read them one at a time.
        }

        val memberCount = executionContext.database.getArrayMemberHashes(queue)?.size
        if (memberCount == null) {
            // A store that cannot enumerate members cannot be read member-wise either. Skipping
            // the queue loses this pass's repair for it — the blocks stay queued, stale-stamped —
            // which is strictly better than losing the drain.
            println(
                "WARN PendingBlockRestamper: a queued block's row is missing and this store cannot " +
                    "enumerate array members — skipping the repair for this queue. Its blocks stay " +
                    "queued at their old stamp."
            )
            return QueueRead(emptyList(), missing = 0)
        }

        val members = ArrayList<Data>(memberCount)
        var missing = 0
        for (i in 0 until memberCount) {
            val one = try {
                executionContext.database.getArrayDataWindow(queue, projection = null, offset = i, limit = 1)
            } catch (_: IllegalStateException) {
                null
            }
            if (one == null) missing++ else members.addAll(one)
        }
        println(
            "WARN PendingBlockRestamper: $missing of $memberCount queued block(s) have no row in " +
                "the store and were skipped; the other ${memberCount - missing} were read. A " +
                "dangling queue member no longer costs this device its sync."
        )
        return QueueRead(members, missing)
    }

    private sealed interface Outcome {
        /** Already sealed at the current stamp — left byte-for-byte alone. */
        data object Current : Outcome

        /**
         * Not decryptable/openable under this realm's key. Deliberately left
         * queued rather than discarded: the classic case is an orphaned-owner
         * block from a realm this device left, which [SyncScheduler.drainPendingBlocks]
         * quarantines on its own terms. Restamping must never be the thing that
         * destroys a block it merely failed to read.
         */
        data object Unreadable : Outcome

        /** Upcast succeeded; [block] is the same block resealed at the current stamp. */
        class Reseal(val block: SyncBlock, val droppedValues: List<String>) : Outcome

        /** The payload is unrecoverable under the current chains (below floor / dropType). */
        class Drop(val reason: String) : Outcome
    }

    private fun restamp(
        block: SyncBlock,
        identityKey: ByteArray,
        upcaster: PayloadUpcaster,
    ): Outcome {
        // ⭐ sync-envelope-authentication.md §3.2: queued blocks are envelope-bound like any other.
        val plaintext = EnvelopeAuth.openSyncBlock(cryptoManager, block, identityKey) ?: return Outcome.Unreadable
        val opened = BlockPayloadEnvelope.open(plaintext) ?: return Outcome.Unreadable

        // Fast path — and the only path in every run after the first: a block sealed
        // by this runtime carries this runtime's stamp bytes verbatim.
        if (opened.formatVersion == TENSHU_STORAGE_FORMAT_VERSION &&
            opened.stampBytes.contentEquals(currentStamp)
        ) {
            return Outcome.Current
        }

        val result = try {
            upcaster.upcast(opened.payloadBytes, opened.stampBytes)
        } catch (_: Exception) {
            // Malformed under the producing schemas — the same non-fatal treatment
            // the ingest funnel gives a parse failure. Left queued.
            return Outcome.Unreadable
        }
        return when (result) {
            is PayloadUpcaster.UpcastResult.Dropped -> Outcome.Drop(result.reason)
            is PayloadUpcaster.UpcastResult.Current -> Outcome.Reseal(
                block = EnvelopeAuth.sealSyncBlock(
                    crypto = cryptoManager,
                    context = executionContext.context,
                    // Identity preserved: the relay dedupes by blockId, and the HLC is
                    // this write's position in the realm's causal order — a restamp is
                    // a re-encoding, never a new event. The same envelope is re-bound.
                    blockId = block.blockId.get(),
                    ownerId = block.ownerId.get(),
                    hlc = block.hlc,
                    targetEntityIdHash = block.targetEntityIdHash?.get(),
                    plaintext = BlockPayloadEnvelope.seal(serializer.serialize(result.payload), currentStamp),
                    identityKey = identityKey,
                ),
                droppedValues = result.droppedValues,
            )
        }
    }

    /**
     * Swap one queued block for its resealed self. `SyncBlockDataIdProviderImpl` keys
     * on `blockId`, which the reseal preserves, so this is an upsert of the same row —
     * written as remove-then-add in one transaction (the [SchemaPullFence] idiom) so a
     * crash between the two can never leave the queue holding neither.
     */
    private fun replace(queue: ArrayDatabaseReference, old: SyncBlock, new: SyncBlock) {
        val dataId = executionContext.getDataId(old) ?: return
        executionContext.database.transaction {
            executionContext.database.removeFromArray(queue, dataId)
            executionContext.database.addToArray(queue, new)
        }
    }

    /**
     * Drop a block BY DECISION (below the readable floor — it can never be resealed).
     *
     * Terminal, so the row goes too (`core-local-store-orphan-gc.md` G1). ⚠ Deliberately NOT done
     * in [replace]: that removal is half of an upsert of the *same* row (`blockId` survives the
     * reseal), so deleting there would destroy what the paired `addToArray` re-adds.
     */
    private fun remove(queue: ArrayDatabaseReference, block: SyncBlock) {
        val dataId = executionContext.getDataId(block) ?: return
        executionContext.database.transaction {
            executionContext.database.removeFromArray(queue, dataId)
            executionContext.deleteRetiredBlock(block)
        }
    }
}
