package org.tenshu.file.proxy

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.ExternalPropertyWriter
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.content.contentEdgeOf
import org.tenshu.core.common.content.deriveContentId
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.THUMBNAIL_CONTENT_NAMESPACE
import org.tenshu.core.file.Thumbnail
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.schema.propertyRef

/**
 * Joiner-side counterpart to the host's `UploadFileImplementation`-driven
 * byte emit: when `SyncProcessor.attachPropertyAndSave` receives a
 * decrypted `DataBlockPayload(propertyId = File.Relation.Data.id, value
 * = TByteArray(bytes))`, this writer looks up the `File` composite by
 * its already-synced `StringDataId`, reads its `path` field, and writes
 * the bytes to the local [FileDatabase] under that path.
 *
 * Without this the joiner stores nothing — the OnDemand `Maybe.get()` /
 * `.flow()` paths would dispatch the resolver, fetch the bytes from the
 * relay, run them through `SyncProcessor`, and drop them on the floor
 * because there's no entity-properties slot for externally-stored bytes.
 * The visible symptom is `AndroidFileDatabaseImpl`'s "File not found"
 * warning when the UI tries to render a synced file.
 *
 * `database.getData(...)` returns the unwrapped `File` composite (not
 * `Entity<File>`) — the entity-wrapping shape is a render-time concern
 * for the SDK call sites, not a storage shape. `File.path` is a direct
 * field accessor on the composite, so we use that rather than
 * `Entity<File>.path`.
 *
 * Ordering note: the byte block always arrives AFTER its parent `File`
 * entity in production — `UploadFileImplementation` saves the entity,
 * then `awaitEmitIdle()`s so the (async, ADR 0015) entity block is
 * HLC-stamped *before* it emits the byte block; both `getChanges`
 * (HLC-ordered) and `fetchEntityBlocks` (push-ordered) then preserve that
 * order. Without that barrier the inline byte block would race ahead of
 * the async entity block (see ADR 0015 Consequences). If the entity
 * hasn't landed yet for some reason, [write] no-ops; the OnDemand resolve
 * cycle picks it up once the entity is present (the proactive push does
 * not retry — hence the barrier).
 */
class FileBytesPropertyWriter(
    private val fileDatabase: FileDatabase
) : ExternalPropertyWriter {

    override fun supports(propertyId: String): Boolean =
        propertyId == File.Data.id ||
            propertyId == SharedFile.DATA_PROPERTY_ID ||
            propertyId in THUMBNAIL_PROPERTY_IDS

    override fun write(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        value: Data
    ) {
        val bytes = value as? TByteArray ?: return
        val path = resolveExternalPath(executionContext, targetEntityId, propertyId)
            ?: return
        fileDatabase.writeByteArray(filePath = path, bytes = bytes)
        // A class written here is present again, so the keeper's "deliberately gone" sentinel is stale
        // (`drive-xxlarge-thumbnail-rung.md` §4.7). ⚠ Belt-and-braces only: a thumbnail
        // arrives as a content-plane blob promoted by `FileContentManifestApplier.moveFile`, which
        // never reaches a writer, so the self-heal on the read path is the one that actually fires.
        thumbnailClassFor(propertyId)?.let { thumbnailClass ->
            resolveFileStringValue(executionContext, targetEntityId, propertyId, File.ContentId.id)
                ?.let { contentId -> ThumbnailCacheSidecars.clearEvicted(fileDatabase, contentId, thumbnailClass) }
        }
    }

    /**
     * The `File`'s on-disk `FileDatabase` path. Used by the
     * two-plane read path (`docs/adr/0017-file-content-two-plane-channel.md`): the
     * manifest applier in `:sync-service-proxy` cannot see the
     * `:file-service` `File` type, so it resolves the destination path
     * through this `:core-common` port, reassembles the verified file
     * itself one bounded chunk at a time, and promotes it here. Returns
     * `null` if the parent `File` entity hasn't synced yet (the
     * documented "entity-before-bytes" ordering tolerance — the next
     * resolve retries once the entity has landed).
     */
    override fun resolveExternalPath(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? {
        // Thumbnail bytes are content-plane blobs of their own, stored beside the
        // file's under a reserved prefix and keyed by the file's **contentId** rather
        // than its path — so a move stays pure metadata for the thumbnail exactly as
        // it is for the file's bytes (`file-content-addressing.md`).
        thumbnailClassFor(propertyId)?.let { thumbnailClass ->
            val contentId = resolveFileStringValue(executionContext, targetEntityId, propertyId, File.ContentId.id)
                ?: return null
            return thumbnailPath(contentId, thumbnailClass)
        }
        val data = executionContext.database.getData(targetEntityId) ?: return null
        return when (data) {
            // Personal file: bytes live at the file's own path.
            is File -> runCatching { data.getField<TString>(File.Field.PATH).get() }.getOrNull()
            // Shared-realm file: the recipient has no personal path for
            // it, so reassemble under a deterministic realm byte-path keyed by its
            // stable (realmId, fileId) — decoupled from the human-facing name/tree
            // (which lives on the SharedFile's parentFileId/name). The reserved
            // `/.shared/…` prefix never collides with a personal "/…" path.
            is SharedFile -> runCatching {
                val realmId = data.getField<TString>(SharedFile.Field.REALM_ID).get()
                val fileId = data.getField<TString>(SharedFile.Field.FILE_ID).get()
                "/.shared/$realmId/$fileId"
            }.getOrNull()
            else -> null
        }
    }

    /**
     * The file's stable random `contentId` — the content-plane address of its
     * manifest.
     *
     * - A personal [File]: `contentId` is a *value* property, read through the
     *   property resolver (as `UploadFileImplementation` does for `CreatedAt`).
     * - A [SharedFile]: `contentId` is a plain composite **field** carrying the
     *   *personal* contentId of the shared file (§3.4.1). When non-empty this routes
     *   the realm's content manifest through the content plane exactly like a
     *   personal file's; empty (a folder, or a shared file minted before the field
     *   existed) returns `null` → the legacy entity-id mailbox block.
     */
    override fun resolveContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? {
        if (!supports(propertyId)) return null
        // ⚠⚠ A thumbnail must NOT answer with the file's own contentId. The
        // manifest's relay address is `manifestContentRef(contentId)` — keyed on the
        // content id **alone** — and a File has exactly one, so Data, Thumbnail and
        // ThumbnailLarge would all PUT their manifests to the same ref and overwrite
        // each other. Deriving a per-property id is what keeps them apart, and it
        // needs no change to that shipped derivation. ⚠ A ref outside the relay's `REF_PATTERN`
        // (`^blob-[0-9a-f]{64}$`) is refused by every content route. It also domain-separates the
        // chunk AAD for free, since `fileChunkAad` binds whatever id is handed to the emit.
        val data = executionContext.database.getData(targetEntityId)
        return when (data) {
            // ⚠ A shared file has no thumbnail rungs; only its own content slot answers.
            is SharedFile -> if (thumbnailClassFor(propertyId) != null) null
                else data.contentId.get().takeIf { it.isNotEmpty() }
            is File -> declaredContentId(executionContext, targetEntityId, propertyId)
            else -> null
        }
    }

    /**
     * ⭐ A `File`'s content ids come from the schema's declaration (`content-plane-cr2-quorumless.md`
     * §10.11) — `File.ContentId` bound to `ContentReferencing.CONTENT_ID`, and a `ContentDerivation`
     * on each content-bearing property — through the same `ContentDeclarations` the backfill walk
     * and the push's block builder use (through [contentEdgeOf], its one-property path), so the id
     * this emits under and the edge a push names cannot disagree.
     *
     * ⚠ Reads the **stored** row, because this port takes a `DataId`. The block builder builds edges
     * from the payload instead (`content-plane-cr2-b2-audit.md` §2.1).
     *
     * ⚠ A property the declaration does not cover (`SharedFile.DATA_PROPERTY_ID` asked of a `File`
     * row) answers with the row's own `File.ContentId`.
     */
    private fun declaredContentId(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
    ): String? {
        val registry = executionContext.schemaProvider as? SchemaRegistry
        val row = executionContext.database.getData(targetEntityId, asEntity = true) ?: return null
        // ⚠ The one-property path, not `ContentDeclarations.of`: this port is asked per row per
        // property on read paths (a thumbnail grid), and building the declarations walks every
        // registered relation. Same declaration, same answer.
        val edge = registry?.let { contentEdgeOf(it, row, propertyId) }
        if (edge != null) return edge.contentId
        // ⚠⚠ Loud: a File row the declaration cannot answer is a schema wiring defect, and the
        // fallback below derives the same ids so the emit stays correct while it is fixed.
        if (thumbnailClassFor(propertyId) != null) {
            println("FileBytesPropertyWriter -> no content declaration answered for $propertyId; using the legacy derivation")
            val fileContentId = resolveFileStringValue(executionContext, targetEntityId, propertyId, File.ContentId.id)
                ?: return null
            return derivedThumbnailContentId(fileContentId, thumbnailClassFor(propertyId)!!)
        }
        return resolveFileStringValue(executionContext, targetEntityId, propertyId, File.ContentId.id)
    }

    /**
     * The personal file's whole-file `contentHash` (the manifest's integrity anchor).
     *
     * ⚠ `null` for a thumbnail property, deliberately: `File.ContentHash` is the hash of
     * the file's *own* bytes, and handing it back for a thumbnail emit would stamp the
     * manifest with an anchor the reassembled thumbnail can never match — the integrity
     * gate would then reject every thumbnail it fetched. Null makes the emit hash the
     * bytes it is actually streaming (`SyncObserver`'s `precomputedHash ?: wholeFileHasher`).
     */
    override fun resolveContentHash(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String
    ): String? {
        if (thumbnailClassFor(propertyId) != null) return null
        return resolveFileStringValue(executionContext, targetEntityId, propertyId, File.ContentHash.id)
    }

    private fun resolveFileStringValue(
        executionContext: ExecutionContext,
        targetEntityId: DataId,
        propertyId: String,
        valuePropertyId: String,
    ): String? {
        if (!supports(propertyId)) return null
        val data = executionContext.database.getData(targetEntityId) as? File ?: return null
        return runCatching {
            (executionContext.resolveProperty(data, propertyRef(valuePropertyId)) as? TString)?.get()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    companion object {

        // ⚠ Derived from the one rung table (`ThumbnailClasses`), never spelled out here.
        private val THUMBNAIL_PROPERTY_IDS: Map<String, String> =
            ThumbnailClasses.ALL.associate { thumbnailClass -> thumbnailClass.property.id to thumbnailClass.id }

        /** The thumbnail class [propertyId] addresses, or null if it is not a thumbnail. */
        fun thumbnailClassFor(propertyId: String): String? = THUMBNAIL_PROPERTY_IDS[propertyId]

        /**
         * The inverse: the `File.Thumbnail*` property id that carries [thumbnailClass], or null for
         * a class this writer does not know. The backfill emits by class and needs the
         * property id the upload emits under, from the same table, so the two cannot drift.
         */
        fun propertyIdFor(thumbnailClass: String): String? =
            THUMBNAIL_PROPERTY_IDS.entries.firstOrNull { it.value == thumbnailClass }?.key

        /**
         * Every thumbnail class this writer declares content for.
         *
         * ⚠⚠ Exposed so the **live walk derives from the same table the emit declares from**
         * (`ProxyLiveContentIds`). `content-plane-refcount.md` §9.6's invariant is *declared ⇒
         * walkable*, and the only way to keep two lists in step is not to have two: adding a
         * thumbnail class here extends both sides at once.
         */
        val THUMBNAIL_CLASSES: Set<String> = THUMBNAIL_PROPERTY_IDS.values.toSet()

        /**
         * Local [FileDatabase] path for a thumbnail blob. Keyed by the file's `contentId`,
         * so it survives a move for the same reason the file's own bytes do. The reserved
         * `/.thumbnails/…` prefix cannot collide with a personal `"/…"` path, mirroring
         * `/.shared/…` and health's `/.health/streams/…`.
         */
        fun thumbnailPath(fileContentId: String, thumbnailClass: String): String =
            "/.thumbnails/$fileContentId/$thumbnailClass"

        /**
         * The per-property content id a thumbnail's manifest is addressed by — see
         * [resolveContentId] for why it must not be the file's own.
         *
         * ⚠ Derived (rather than a second stored field) so it needs no schema slot and
         * stays stable across a move: the file's `contentId` is already random and
         * move-invariant, and hashing it with a domain tag keeps both properties true
         * while making the two classes' addresses independent. ⚠ It is fed to
         * `manifestContentRef`, which hashes again — the double hash is intentional, not
         * redundant: this value has to *be* a content id, not a ref.
         */
        fun derivedThumbnailContentId(fileContentId: String, thumbnailClass: String): String =
            // ⚠⚠ The schema's derivation (`DerivedContent(THUMBNAIL_CONTENT_NAMESPACE, class)` on each
            // rung), not a second copy of it: one function, so the two can never drift.
            deriveContentId(THUMBNAIL_CONTENT_NAMESPACE, fileContentId, thumbnailClass)
    }
}
