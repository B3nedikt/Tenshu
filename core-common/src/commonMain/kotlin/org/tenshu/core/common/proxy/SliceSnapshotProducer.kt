package org.tenshu.core.common.proxy

import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId

/**
 * Produces the **opaque entry block** of a relation slice snapshot for one service
 * (`sync-device-catch-up.md` §5.7, C8).
 *
 * ## Why this is a port
 *
 * The snapshot writer lives in `:sync-service-proxy`, which **deliberately cannot see** a
 * service's domain types — the same module boundary [ExternalPropertyWriter] exists to cross,
 * and for the same reason. The writer owns *scheduling, sealing and PUT*; a producer owns
 * *what a slice contains*. That split is C8a made structural: the envelope is platform-owned,
 * the entries belong to the service, and neither side needs the other's types.
 *
 * ⚠ A service that registers no producer simply gets no snapshot and bootstraps by replaying
 * the log — the same "absence is the default" posture as the `Sliceable` trait. Nothing here
 * is required for correctness; a snapshot only ever makes a joiner *faster*.
 */
interface SliceSnapshotProducer : TComponent {

    /**
     * Relation ids this producer can snapshot — e.g. `tenshu/file/folder/files`.
     *
     * The writer consults only these, so a producer is never asked about a relation it does
     * not own, and two producers cannot collide on one relation.
     */
    val relationIds: Set<String>

    /**
     * Every slice key that currently exists for [relationId] — for `Folder.FILES`, the entity
     * id of each folder in the realm.
     *
     * ⚠ Called on the writer's idle+charging pass, never on a read path, so it may walk the
     * store. It must still be *bounded*: this list is the writer's work queue, so a producer
     * that returns every row of an unbounded relation makes the pass unbounded too.
     */
    fun sliceKeys(executionContext: ExecutionContext, relationId: String): List<String>

    /**
     * The sealed entry block for one slice, or `null` when it cannot be produced right now
     * (the slice's parent has been deleted mid-pass, a row will not resolve, …).
     *
     * ⚠ `null` is not an error — the writer skips that slice and the reader falls back to an
     * ordinary relation read. Producing a *wrong* slice is far worse than producing none, so
     * a producer that is unsure must answer `null`.
     *
     * ⚠ The writer skips any slice whose encoded envelope is over its byte budget
     * (`RelationSliceSnapshot.MAX_ENCODED_BYTES`, `sync-device-catch-up.md` §11.4.4), so a producer
     * never cuts a slice to fit: a slice is whole or absent.
     */
    fun encodeSlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
    ): EncodedSlice?

    /**
     * The slices a device should fetch **before it holds any local state** — the reader's seed.
     *
     * ⚠ Distinct from [sliceKeys], and the distinction is the whole reason this exists:
     * [sliceKeys] enumerates what is *in the local store*, which on a joiner is empty. A seed
     * has to be a coordinate the service can name a priori — for `Folder.FILES` the root path;
     * for a time-bucketed relation, today's bucket.
     *
     * ⚠ Takes the [executionContext] purely so a time-bucketed producer can reach a **clock**.
     * A day-keyed seed is "today", which is not a constant — that is the one thing separating
     * this from `Folder.FILES`, whose seed is the literal string `"/"`.
     *
     * Empty by default: a service with no seed simply bootstraps by replaying the log.
     */
    fun bootstrapSliceKeys(
        executionContext: ExecutionContext,
        relationId: String,
    ): List<String> = emptyList()

    /**
     * Materialise a fetched slice into the local store, returning how many rows it created.
     *
     * The inverse of [encodeSlice], and it belongs to the same port for the same reason: these
     * bytes are the service's own projection and `:sync-service-proxy` cannot parse them. The
     * platform side owns fetching, decrypting and decoding the *envelope*; everything inside
     * [entries] is this method's business.
     *
     * ⚠⚠ **Create-only, and that is a correctness rule rather than an optimisation.** A slice
     * carries a *projection* of each row, not the row — overwriting an existing entity with it
     * would silently drop every field the projection omits. So an implementation must skip any
     * entity that already exists locally and let the ordinary log deliver the newer version.
     *
     * ⚠ Rows must be saved with [WriteOrigin.SYNC_INGEST]: a snapshot is state arriving from
     * the relay, and re-emitting it would echo the realm's own contents back at it.
     *
     * ⚠ And they must be saved **without `SyncMetadata`** — that absence is what makes a later
     * real block win. `SyncProcessor.resolveConflictAndSave` reads a missing metadata row as
     * "anything incoming is newer", so the snapshot's provisional row is replaced by the real
     * one rather than fencing it out; the entity id is the same, so it is an update, never a
     * duplicate.
     *
     * ⚠⚠ **And it must skip every entity [isDeleted] answers `true` for** (`sync-device-catch-up.md`
     * §11.4.4). A slice is older than the log a joiner is replaying, and `applySlice` reruns on every
     * launch until catch-up completes. Without this, a slice applied after a row's tombstone has been
     * ingested recreates the row, and the tombstone cursor is already past it, so nothing deletes it
     * again. Skipping a row that was deleted and later recreated costs nothing: its create block is
     * newer than the tombstone, so the log still delivers it.
     */
    fun applySlice(
        executionContext: ExecutionContext,
        relationId: String,
        sliceKey: String,
        entries: ByteArray,
        isDeleted: (DataId) -> Boolean,
    ): Int

    companion object {
        const val ID = "tenshu/core/sliceSnapshotProducer"
    }
}

/**
 * One producer's answer for a single slice: the opaque bytes plus the count the platform
 * envelope carries so a reader can size the slice without parsing it.
 */
class EncodedSlice(
    val entryCount: Int,
    val entries: ByteArray,
)

/**
 * Registered producers, keyed by the component id. ⚠ A **list**, unlike
 * [ExternalPropertyWriter]'s single registration: every service that wants snapshots
 * contributes one, and the writer fans out across all of them.
 */
fun TContext.sliceSnapshotProducers(): List<SliceSnapshotProducer> =
    (components[SliceSnapshotProducer.ID] as? SliceSnapshotProducerRegistry)?.producers.orEmpty()

/** Holder so several services can register producers under one component id. */
class SliceSnapshotProducerRegistry(
    val producers: List<SliceSnapshotProducer>,
) : TComponent
