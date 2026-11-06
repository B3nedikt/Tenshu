package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * Durable, local-only **intent** to push one file's content to the content plane, parked because
 * the metered content gate was closed at emit time (`sync-metered-plane-split.md` §4, D1).
 *
 * **An intent, deliberately not staged ciphertext.** The bytes are already durable in
 * `FileDatabase`; re-reading them when the gate opens costs nothing, whereas parking the encrypted
 * chunks would double on-disk storage for every deferred upload *and* create a second at-rest copy
 * of user content — which would owe a threat-model A5 amendment. Recording only
 * `(targetEntityId, propertyId, filePath)` keeps this record content-free, so the journal is
 * metadata about work-to-do and never user data (§9).
 *
 * Generalises the in-memory locked-realm hold (`SyncObserver.pendingContent`, replayed by
 * `reemitPendingLockedWork`): that queue only had to survive until an unlock later in the same
 * session, but a Wi-Fi wait spans days and reboots, so this one is persisted. Written **below**
 * `ExecutionContext.saveData` (via `database.addToArray`/`removeFromArray` on the
 * [org.tenshu.sync.SyncService.Collections.PENDING_CONTENT_EMITS] array) so it never re-fires
 * observers — the durable-sidecar pattern shared with [RekeyMigrationMarker] / [ResolvedDataId].
 *
 * **Re-resolved against current state on drain, never replayed blindly** (§4 D6): the drain reads
 * today's bytes for [filePath], so a file modified while deferred uploads its *current* content,
 * and one that has since been deleted is dropped (its tombstone already went out on the ungated
 * metadata plane). Nothing here can resurrect stale content.
 *
 * ## ⭐⭐ Keyed on the CONTENT, not on the row that wanted it
 *
 * An intent is an obligation to publish **content** — bytes this device holds and the relay does not.
 * A personal `File`'s identity is its path, so every move retires the row an entity-keyed intent
 * named, and a move made by a *peer* arrives as block application, where no operation is there to
 * re-key anything. Keyed on the row, the drain would resolve the content id through the retired row,
 * get null, publish under the no-content-id branch — a manifest no reader resolves, no edge, no
 * handle — and clear the intent as done. ⇒ [contentIdKeyOf]: the `contentId` is stable across moves
 * by construction (`file-content-addressing.md`), so nothing that relocates a row can invalidate the
 * key.
 *
 * ⇒ [targetEntityId] and [filePath] are not identity; they are what they physically are: a
 * **holder hint** and a **byte location**. A local move updates both in place
 * ([retargetPendingContentEmits]); a peer's move changes neither, and needs to — ingest never moves
 * bytes, so the old path is still where they are.
 *
 * ⚠ The entity key survives only for a row that resolves no content id (the entity-keyed mailbox
 * path); [keyOf] names it.
 *
 * @property emitKey the key this record is stored under — [contentIdKeyOf] for content-addressed
 *   rows, [keyOf] otherwise — so the writer can address and replace an existing intent instead of
 *   accumulating one per upload (§4 D5; the last-wins semantics come from the writer's
 *   remove-then-add, not from the array itself).
 * @property targetEntityId the row that wanted this content when it was journalled — a HINT. Consulted
 *   for the fast path (it still names [contentId] at [filePath]) and for which holder the edge is
 *   declared under; after a peer's move it names a retired row, and nothing may depend on it existing.
 * @property propertyId the content-bearing property (e.g. `File.Data`).
 * @property filePath where this device holds the bytes. Updated by a local move; after a peer's move
 *   it is still correct, because ingest leaves the bytes where they were.
 * @property contentHash the whole-content hash of [contentId], when the row stores one (`File.Data`'s
 *   `File.ContentHash`), written at journal time. ⚠⚠ The authority on whether the bytes at
 *   [filePath] ARE this content: once the holder stops vouching for them, a location can hold anyone's
 *   bytes, and publishing them under [contentId] would put the wrong file behind the right address.
 *   Also the resume guard. Empty when the row stores no hash (thumbnail rungs, workout streams).
 * @property wrappedCek the CEK the landed chunks were encrypted under, **wrapped under the realm
 *   volume key** exactly as the manifest carries it. ⚠⚠ Wrapped, never raw: the entry then holds a
 *   ciphertext that is useless without a key the relay does not have, so it introduces no at-rest
 *   class this device did not already carry, and the record stays useless in isolation.
 * @property landedChunkHashes the cipher hashes of the chunks already on the relay, comma-joined in
 *   chunk order with an empty segment for a chunk that has not landed.
 * @property contentId the content this intent publishes; empty for an entity-keyed row.
 */
interface PendingContentEmit : Composite {

    override val schema: CompositeSchema
        get() = Schema

    val emitKey: TString
        get() = getField(Fields.EMIT_KEY)

    val targetEntityId: DataId
        get() = getField(Fields.TARGET_ENTITY_ID)

    val propertyId: TString
        get() = getField(Fields.PROPERTY_ID)

    val filePath: TString
        get() = getField(Fields.FILE_PATH)

    val contentHash: TString
        get() = getField(Fields.CONTENT_HASH)

    val wrappedCek: TString
        get() = getField(Fields.WRAPPED_CEK)

    val landedChunkHashes: TString
        get() = getField(Fields.LANDED_CHUNK_HASHES)

    val contentId: TString
        get() = getField(Fields.CONTENT_ID)

    companion object {
        const val ID = "tenshu/sync/pendingContentEmit"

        /**
         * The entity stored key for a `(entity, property)` pair — only for a row that resolves no
         * content id. See [emitKey].
         */
        fun keyOf(targetEntityIdHash: String, propertyId: String): String =
            "$targetEntityIdHash|$propertyId"

        /**
         * The stored key for an intent to publish [contentId] as [propertyId].
         *
         * ⚠ The prefix is what keeps the two key families apart: an entity key starts with a signed
         * decimal hash, which can never read `content:`.
         */
        fun contentIdKeyOf(contentId: String, propertyId: String): String =
            "content:$contentId|$propertyId"

        /**
         * The content id a [contentIdKeyOf] key names, or null for an entity key. ⚠ Split at the
         * LAST `|`: a property id never contains one, a content id is not guaranteed not to.
         */
        fun contentIdOfKey(emitKey: String): String? =
            if (emitKey.startsWith("content:")) emitKey.removePrefix("content:").substringBeforeLast('|') else null

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.EMIT_KEY)
                composite(Fields.TARGET_ENTITY_ID, type = DataId.Schema)
                string(Fields.PROPERTY_ID)
                string(Fields.FILE_PATH)
                string(Fields.CONTENT_HASH)
                string(Fields.WRAPPED_CEK)
                string(Fields.LANDED_CHUNK_HASHES)
                string(Fields.CONTENT_ID)
            }
        )

        fun create(
            context: TContext,
            emitKey: String,
            targetEntityId: DataId,
            propertyId: String,
            filePath: String,
            contentHash: String = "",
            wrappedCek: String = "",
            landedChunkHashes: String = "",
            contentId: String = "",
        ): PendingContentEmit = context.create(
            type = ID,
            fields = listOf(
                emitKey.toTString(context),
                targetEntityId,
                propertyId.toTString(context),
                filePath.toTString(context),
                contentHash.toTString(context),
                wrappedCek.toTString(context),
                landedChunkHashes.toTString(context),
                contentId.toTString(context),
            )
        )
    }

    object Fields {
        const val EMIT_KEY = "$ID/emitKey"
        const val TARGET_ENTITY_ID = "$ID/targetEntityId"
        const val PROPERTY_ID = "$ID/propertyId"
        const val FILE_PATH = "$ID/filePath"
        const val CONTENT_HASH = "$ID/contentHash"
        const val WRAPPED_CEK = "$ID/wrappedCek"
        const val LANDED_CHUNK_HASHES = "$ID/landedChunkHashes"
        const val CONTENT_ID = "$ID/contentId"
    }
}
