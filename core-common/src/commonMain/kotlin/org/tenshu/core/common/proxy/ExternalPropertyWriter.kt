package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId

/**
 * Joiner-side extension point: when a sync-block decrypts to a
 * `DataBlockPayload(propertyId != null, value)` and the property
 * names a slot whose bytes live OUTSIDE `Entity.properties` (e.g.
 * file bytes routed through `FileDatabase` rather than the in-memory
 * entity store), `SyncProcessor.attachPropertyAndSave` dispatches to
 * the registered writer for that propertyId. The writer is
 * responsible for putting the bytes in the right external place.
 *
 * Symmetric to [org.tenshu.sync.common.SyncBlockEmitter] on the host
 * side and to [OnDemandResolution] for the read direction: the
 * read path fetches blocks via `OnDemandResolution.resolve(...)`,
 * processes them through `SyncProcessor`, and routes each property
 * value through this writer to its real home (e.g. AndroidFileDatabase
 * for `File.Relation.Data`).
 *
 * Registered globally on the [TContext] (not per-schema as a
 * [org.tenshu.core.trait.Trait]) because the dispatcher needs a
 * single, stable lookup point — same reasoning as `OnDemandResolution`.
 * Concrete implementations (e.g. file-service-proxy's
 * `FileBytesPropertyWriter`) declare via [supports] which property
 * IDs they handle.
 */
interface ExternalPropertyWriter : TComponent {

    /**
     * True if this writer is responsible for [propertyId]. The
     * `SyncProcessor` falls back to its in-memory entity-property
     * attach path when no writer supports the id.
     */
    fun supports(propertyId: String): Boolean

    /**
     * Write [value] for the [propertyId] slot of the entity identified
     * by [targetEntityId]. [executionContext] gives access to the
     * local database for any entity-level lookup the writer needs
     * (e.g. resolving a `File`'s path before writing bytes to
     * `FileDatabase`).
     */
    fun write(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        value: Data
    )

    /**
     * Resolve the **external storage path** the [propertyId] bytes of
     * the entity identified by [targetEntityId] live at (e.g. a
     * `File`'s on-disk `FileDatabase` path), or `null` if it can't be
     * resolved yet (entity not synced — the "entity-before-bytes"
     * ordering tolerance).
     *
     * The two-plane file-content **read path**
     * (`docs/adr/0017-file-content-two-plane-channel.md` P2) needs this so the manifest
     * applier — which lives in `:sync-service-proxy` and deliberately
     * cannot see the `:file-service` `File` type (it routes via this
     * `:core-common` port to stay module-decoupled, exactly as
     * `SyncProcessor.attachPropertyAndSave` does) — can reassemble the
     * file *itself* into a temp path one bounded chunk at a time and,
     * only after the whole-file integrity gate passes, atomically
     * promote it onto this resolved path. A whole-file
     * [write]`(TByteArray)` would defeat the `O(CHUNK)` read bound,
     * which is the whole point of the two-plane design.
     *
     * Default returns `null` (writers that have no path notion / only
     * support in-memory property writes are unaffected — a manifest
     * for such a property then stays a clean no-op).
     */
    fun resolveExternalPath(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? = null

    /**
     * The stable **content id** the [propertyId] content of the entity at
     * [targetEntityId] is addressed by — the random id a file's ciphertext chunks +
     * manifest bind to, independent of its (mutable) path (content-addressing Phase 1,
     * `file-content-addressing.md` §3.1). Both the host emit (`SyncObserver`) and the
     * OnDemand read path (`OnDemandResolutionImpl`) route the content manifest by this
     * id — stable across a move — rather than by the entity id, so relocating a file is
     * pure metadata. This port is the bridge because `:sync-service-proxy` cannot see
     * the `:file-service` `File` type, exactly as [resolveExternalPath].
     *
     * `null` when: the entity hasn't synced yet, this writer doesn't own [propertyId],
     * or the entity is not content-addressed (e.g. a shared-realm file, whose id is
     * *already* a stable UUID and so has no move-invalidation to fix — it keeps the
     * pre-Phase-1 path). A null answer makes both planes fall back to the legacy
     * entity-id-keyed manifest, so mixed populations resolve correctly.
     */
    fun resolveContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? = null

    /**
     * The whole-file **content hash** stamped on the entity for the [propertyId]
     * content (the value the manifest carries as its `wholeFileHash` integrity anchor),
     * or `null` under the same conditions as [resolveContentId]. The host emit reads it
     * here so it does not re-hash the file it is already streaming to chunk — the hash
     * was computed once at upload (`file-content-addressing.md` §8.4 one-hash-pass).
     */
    fun resolveContentHash(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? = null

    companion object {
        const val ID = "tenshu/core/externalPropertyWriter"
    }
}

fun TContext.externalPropertyWriter(): ExternalPropertyWriter? =
    components[ExternalPropertyWriter.ID] as? ExternalPropertyWriter
