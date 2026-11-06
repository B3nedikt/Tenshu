package org.tenshu.sync.common

import org.tenshu.core.Data
import org.tenshu.core.entity.DataId

/**
 * Bridge for operation implementations to emit a property-block
 * [org.tenshu.sync.SyncBlock] for an entity property whose bytes live
 * outside `Entity.properties` (e.g. file bytes held in `FileDatabase`
 * rather than the in-memory entity store).
 *
 * The regular `SyncObserver.onDataSaved` path only sees what crosses
 * `ExecutionContext.saveData(...)` — entities and their in-band
 * properties. For externally-stored data (file bytes, previews) the
 * upload-side operation knows the bytes exist but never routes them
 * through `saveData`, so the observer has no chance to emit. This
 * emitter is the explicit hook: after writing to external storage,
 * the operation calls [emitPropertyBlock] to push the bytes through
 * the same wrap-encrypt-queue pipeline `SyncObserver` uses for
 * entity blocks.
 *
 * On the joiner side the symmetric piece is
 * [org.tenshu.core.common.proxy.ExternalPropertyWriter] — the
 * `SyncProcessor` looks it up by `propertyId` and dispatches the
 * decoded value to external storage instead of attaching it as an
 * entity property.
 *
 * Implemented by `:sync-service-proxy`'s `SyncObserver`; consumers
 * pull it through [SyncBlockEmitterHolder] because the Koin singleton
 * graph constructs the consumer (e.g. `UploadFileImplementation`)
 * before `initSyncService` runs.
 */
interface SyncBlockEmitter {

    /**
     * Wrap [value] in a `DataBlockPayload(propertyId, value)`, encrypt
     * with the realm's shared identity key, and queue the resulting
     * `SyncBlock` for relay push. [targetEntityId] scopes the block to
     * the parent entity — the receiver looks it up by its hash to
     * route the decoded property value via `ExternalPropertyWriter`.
     */
    fun emitPropertyBlock(
        targetEntityId: DataId,
        propertyId: String,
        value: Data
    )

    /**
     * Suspends until every entity block enqueued by `onDataSaved` before
     * this call has been generated and queued to `pendingBlocks`.
     *
     * Needed because Facet A (ADR 0015) moved entity-block generation onto
     * an async consumer, while [emitPropertyBlock] / file-content emits stay
     * inline. An operation that saves an entity and *then* emits a property
     * block for it (e.g. `UploadFileImplementation`: `File` entity, then its
     * bytes) must call this in between, or the inline property block lands in
     * `pendingBlocks` — and ticks the HLC — *before* the async entity block.
     * The joiner then receives the bytes before the parent entity and
     * `FileBytesPropertyWriter` drops them (it resolves the write path from
     * the entity, which isn't there yet, and the proactive push never
     * retries). See `FileBytesPropertyWriter`'s "entity-before-bytes" note.
     *
     * Default no-op: emitters without an async consumer are already ordered.
     */
    suspend fun awaitEmitIdle() {}

    // ---- Task-scoped sync lane (core-background-processing.md §10.3) ----
    // The lane API rides this existing bridge so op implementations (e.g.
    // TaskService's RegisterTask/TransitionTask in :sync-service-proxy, and
    // TS-4/TS-5 consumers) reach the lanes through SyncBlockEmitterHolder
    // without depending on SyncObserver directly. Default no-ops: emitters
    // without a lane implementation simply don't batch.

    /** Explicitly open a sync lane — the gate: saves tagged with an UNOPENED lane id keep the per-save path. */
    suspend fun openSyncLane(laneId: String) {}

    /** Seal the lane's staged deltas into a batch block now (no-op when empty). */
    suspend fun checkpointSyncLane(laneId: String) {}

    /** Final checkpoint + lane teardown. */
    suspend fun closeSyncLane(laneId: String) {}
}
