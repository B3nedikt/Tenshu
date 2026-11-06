package org.tenshu.sync.proxy

import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.sliceSnapshotProducers
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.SyncManager

/**
 * Seals each registered service's relation slices onto the content plane, so a joining device
 * can render its first screen from one fetch instead of replaying history
 * (`sync-device-catch-up.md` §5.7, C8).
 *
 * The writer owns *when, sealing and PUT*; a [SliceSnapshotProducer] owns what a slice
 * contains — the C8a split made structural, so this module never needs a service's types.
 *
 * ⚠ Never correctness-bearing. A slice that is missing, stale or unreadable costs a joiner an
 * ordinary relation read; nothing depends on one existing. That is what lets this run on the
 * idle+charging maintenance path and abandon a pass whenever it likes.
 */
class SliceSnapshotWriter(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val contentBlobStore: ContentBlobStore?,
    /** [RelationSliceSnapshot.MAX_ENCODED_BYTES]; injectable so a test reaches it with small slices. */
    private val maxEncodedBytes: Int = RelationSliceSnapshot.MAX_ENCODED_BYTES,
) {

    /**
     * Seal every slice of every registered producer. Returns how many were written.
     *
     * ⚠⚠ **Refuses to publish while this device's own backfill is incomplete, and that gate is
     * the load-bearing part of this class.** A snapshot's `asOfSeq` is a promise: *"everything
     * at or below this seq is already reflected in these entries, so a reader may skip it."*
     * A device still walking C2's backward pass has NOT applied everything below its forward
     * cursor — the cursor is the live tail, not a high-water mark of applied state. Publishing
     * then would hand a joiner a snapshot that tells it to skip history nobody has read, and
     * the joiner would silently miss rows it can never learn about.
     *
     * ⚠ The seq claimed is [mergedCursor], the **min** of the block and tombstone cursors, not
     * the block cursor alone. A snapshot that claimed a seq past the applied tombstones would
     * let a joiner skip a delete and render a file its owner removed — the resurrection class
     * this track has already paid for once.
     */
    suspend fun writeAll(): Int {
        val store = contentBlobStore ?: return skipped("no content store is wired")
        val realm = syncManager.currentRealmContext() ?: return skipped("the realm is locked")
        val realmId = realm.ownerId
        val keys = realm.derivedKeys

        if (!isCaughtUp()) {
            return skipped(
                "this device has not finished catching up (backfillCursor=${syncManager.backfillCursor})"
            )
        }
        val asOfSeq = mergedCursor()
        if (asOfSeq <= 0) return skipped("there is no relay sequence to claim yet")

        val producers = executionContext.context.sliceSnapshotProducers()
        if (producers.isEmpty()) return skipped("no service registered a slice producer")

        var written = 0
        var oversized = 0
        var unproducible = 0
        producers.forEach { producer ->
            producer.relationIds.forEach { relationId ->
                producer.sliceKeys(executionContext, relationId).forEach { sliceKey ->
                    val encoded = producer.encodeSlice(executionContext, relationId, sliceKey)
                    // ⚠⚠ A slice the producer cannot produce now (`null`: a cut day, a folder that
                    // will not read) is withdrawn as well, for the same reason as an over-budget one:
                    // otherwise a seal from an earlier pass stays at its address for good (§11.4.4).
                    if (encoded == null) {
                        unproducible++
                        val put = store.putChunk(
                            RelationSliceSnapshot.ref(realmId, relationId, sliceKey),
                            cryptoManager.encrypt(RelationSliceSnapshot.withdrawn(), keys.sharedIdentityKey),
                        )
                        if (put !is TResult.Success) println("SliceSnapshotWriter: withdrawing a $relationId slice failed")
                        return@forEach
                    }
                    val snapshot = RelationSliceSnapshot(
                        asOfSeq = asOfSeq,
                        relationId = relationId,
                        sliceKey = sliceKey,
                        entryCount = encoded.entryCount,
                        entries = encoded.entries,
                    )
                    val plaintext = RelationSliceSnapshot.encode(snapshot)
                    // ⚠⚠ The size bound, for every producer at once (§11.4.4): a slice over the
                    // budget is never cut to fit — a cut slice would render a folder or a day with
                    // entries missing. Its address gets the withdrawn marker instead, so an earlier,
                    // smaller seal does not stay there for good. The key is not logged: for a folder
                    // it is a path.
                    val overBudget = plaintext.size > maxEncodedBytes
                    if (overBudget) {
                        oversized++
                        println(
                            "SliceSnapshotWriter: withdrew a $relationId slice of ${encoded.entryCount} " +
                                "entries — ${plaintext.size} bytes is over the $maxEncodedBytes-byte budget",
                        )
                    }
                    // Sealed under the realm's identity key, exactly like a content manifest —
                    // the relay stores opaque bytes and learns nothing from them (HR #1). The
                    // ref is derived from ids it already cannot invert, so the address leaks
                    // no path or folder name either.
                    val sealed = cryptoManager.encrypt(
                        if (overBudget) RelationSliceSnapshot.withdrawn() else plaintext,
                        keys.sharedIdentityKey,
                    )
                    val put = store.putChunk(
                        RelationSliceSnapshot.ref(realmId, relationId, sliceKey),
                        sealed,
                    )
                    if (put is TResult.Success && !overBudget) written++
                    // ⚠ A failed PUT is not fatal and is not retried here: the next maintenance
                    // pass re-seals from current state anyway, and a partially-written slice set
                    // is fine by construction — each slice is independently addressed and
                    // independently optional.
                }
            }
        }
        println(
            "SliceSnapshotWriter: sealed $written slice(s) at seq $asOfSeq; withdrew $oversized over budget " +
                "and $unproducible that could not be produced",
        )
        return written
    }

    /**
     * ⚠⚠ **Every "did nothing" path says why, and this is not decoration.** The pass runs at most
     * once a day, on constraints that may go unmet for longer, and it returns `0` for six unrelated
     * reasons — no store, a locked realm, an incomplete backfill, no seq to claim, no registered
     * producer, or a realm whose every slice declined to encode. On a device, a silent `0` is
     * indistinguishable from a pass that never ran at all, which is exactly the hole the first
     * on-device verification fell into: the pass had already run and skipped, and nothing said so.
     *
     * A named line costs one log entry a day and turns "is this feature working?" from an
     * inference into a fact.
     */
    private fun skipped(reason: String): Int {
        println("SliceSnapshotWriter: nothing sealed — $reason")
        return 0
    }

    /**
     * True when this device has read the whole log, so a claim about [mergedCursor] is honest.
     *
     * `backfillCursor == 0L` is C2's **complete** sentinel; `null` means the backward walk has
     * never run and anything `> 0` means it is still descending. ⚠ Collapsing `null` and `0`
     * is the mistake C2's own notes warn about — here it would be the difference between
     * "never had to walk" and "has not walked yet".
     */
    private fun isCaughtUp(): Boolean = syncManager.backfillCursor == 0L

    private fun mergedCursor(): Long =
        minOf(syncManager.lastSyncTimestamp, syncManager.lastTombstoneSyncTimestamp)
}
