package org.tenshu.sync.proxy

import org.tenshu.core.TResult
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SliceSnapshotProducer
import org.tenshu.core.common.proxy.sliceSnapshotProducers
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.ContentBlobStore
import org.tenshu.sync.common.RelationSliceSnapshot
import org.tenshu.sync.common.SyncManager

/**
 * Fetches relation slices from the content plane and hands them to the service that owns them,
 * so a joining device can render its first screen before it has replayed the log
 * (`sync-device-catch-up.md` §5.7, C8 — the read half of [SliceSnapshotWriter]).
 *
 * This side owns *fetch, decrypt and envelope*; a [SliceSnapshotProducer] owns what the entries
 * mean. The same split as the writer, and for the same reason: this module cannot see a
 * service's types.
 *
 * ⚠ **Degrade, never fail.** Every failure here — no store, no keys, a missing blob, a blob that
 * will not decrypt, an envelope from a newer build, a producer that declines the entries — costs
 * the device nothing but the ordinary relation read it would otherwise have done. Nothing about
 * correctness depends on a slice existing, which is what lets this be attempted optimistically
 * and abandoned silently.
 */
class SliceSnapshotReader(
    private val executionContext: ExecutionContext,
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
    private val contentBlobStore: ContentBlobStore?,
    /**
     * Whether an entity has a stored tombstone or reap point on this device — the registry's
     * `deletionPointOf(id) != null` in production.
     *
     * ⚠⚠ **Required, with no default.** It is what keeps a slice from bringing back a row whose
     * tombstone this device has already ingested (§11.4.4): this reader runs again on every launch
     * until catch-up completes, and a default of "nothing is deleted" would make that silent.
     */
    private val isDeleted: (DataId) -> Boolean,
) {

    private var bootstrapped = false

    /**
     * Fetch and apply every producer's seed slice, once per process. Returns rows created.
     *
     * ⚠⚠ **This deliberately does NOT move `lastSyncTimestamp` to the snapshot's `asOfSeq`,
     * and that is a correction to §11.4's sketch rather than an omission.** The sketch predates
     * C2's live-tail handover, and against the cursor machinery as built the jump is unsafe:
     * `backfillOnce` captures `backfillTarget` from `lastSyncTimestamp` at handover and stops
     * the backward walk there. A reader that raised the forward cursor to `asOfSeq` first would
     * therefore make the walk stop at `asOfSeq` — declaring everything below it applied, when a
     * per-slice snapshot only ever covered *one slice*. Every row outside the snapshot, below
     * that seq, would be silently unreachable forever.
     *
     * That leaves the snapshot doing exactly one job, which is the one worth doing: **content
     * now**. C2 already makes the device live for new writes within a tick of its first backward
     * page, and the walk still fills history underneath. `asOfSeq` stays what it always was —
     * the reader's evidence of *what* it got — and the cursors stay C2's alone.
     *
     * ⚠ Skipped on a device that has finished catching up (`backfillCursor == 0`): a snapshot
     * can only tell it things it already has, and the applier is create-only, so the fetch would
     * be pure cost. ⚠ The latch is burned only once an attempt was actually possible — a realm
     * still locked at the first tick defers rather than spending it, matching
     * [PendingBlockRestamper].
     */
    /**
     * Whether the next [bootstrapOnce] would go to the content plane — the same guards, asked
     * without spending the latch. The scheduler's catch-up signal raises on it before the fetch
     * (`ios-sync-activity-indicator.md` §3.1, raise rule 1).
     *
     * ⚠ Keep in step with the guards below: a `true` here that [bootstrapOnce] then declines
     * shows "Syncing…" for a fetch that never happens.
     */
    fun willFetch(): Boolean =
        !bootstrapped &&
            contentBlobStore != null &&
            syncManager.currentRealmContext() != null &&
            syncManager.backfillCursor != 0L

    suspend fun bootstrapOnce(): Int {
        if (bootstrapped) return 0
        contentBlobStore ?: return 0
        syncManager.currentRealmContext() ?: return 0
        if (syncManager.backfillCursor == 0L) {
            bootstrapped = true
            return 0
        }

        bootstrapped = true
        var applied = 0
        executionContext.context.sliceSnapshotProducers().forEach { producer ->
            producer.relationIds.forEach { relationId ->
                producer.bootstrapSliceKeys(executionContext, relationId).forEach { sliceKey ->
                    applied += fetchAndApply(producer, relationId, sliceKey)
                }
            }
        }
        if (applied > 0) {
            println("SliceSnapshotReader: bootstrapped $applied rows from slice snapshots")
        }
        return applied
    }

    /**
     * Fetch one slice by the coordinates a reader already holds, and apply it. Returns rows
     * created — `0` for every "not available" case, which the caller treats identically.
     *
     * ⚠ The envelope's own `relationId`/`sliceKey` are checked against what was asked for. The
     * address is a hash, so a mismatch cannot be a collision in practice — but a blob that
     * decrypts and decodes into *some other slice* must not be applied under this one's name,
     * and the check costs nothing.
     */
    suspend fun fetchAndApply(
        producer: SliceSnapshotProducer,
        relationId: String,
        sliceKey: String,
    ): Int {
        val store = contentBlobStore ?: return 0
        val realm = syncManager.currentRealmContext() ?: return 0

        val ref = RelationSliceSnapshot.ref(realm.ownerId, relationId, sliceKey)
        val fetched = store.getChunk(ref)
        if (fetched !is TResult.Success) return 0

        // ⚠ A slice this device cannot decrypt is not an error to report: the realm key may have
        // rotated since the slice was sealed (GR-7), and the log delivers the same rows anyway.
        val ciphertext = fetched.value?.get() ?: return 0
        val plaintext = cryptoManager.decrypt(ciphertext, realm.derivedKeys.sharedIdentityKey)
            ?: return 0
        val snapshot = RelationSliceSnapshot.decode(plaintext) ?: return 0
        if (snapshot.relationId != relationId || snapshot.sliceKey != sliceKey) return 0

        return producer.applySlice(executionContext, relationId, sliceKey, snapshot.entries, isDeleted)
    }
}
