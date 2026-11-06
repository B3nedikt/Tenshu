package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.byteArray
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString

/**
 * The decrypted payload of a **file-content manifest** [SyncBlock] — the
 * metadata-plane half of the two-plane file-content design
 * (`docs/adr/0017-file-content-two-plane-channel.md`, Phase P1).
 *
 * It is a **distinct payload variant**, a sibling of [DataBlockPayload]
 * — *not* a `DataBlockPayload`. This is load-bearing for receiver
 * safety during the P1→P2 window: `SyncProcessor.processSyncBlock`
 * parses every decrypted payload as `DataBlockPayload`; a manifest
 * deserialises to *this* type, the `as? DataBlockPayload` cast yields
 * `null`, and the block is dropped with no crash and no misapply. A
 * pre-P2 device (or a peer) therefore safely **ignores** manifests.
 * Cross-device file *content* sync is intentionally incomplete until
 * the P2 read path lands — that is a known, documented gap, not a
 * silent regression.
 *
 * Like [DataBlockPayload], every identifying field ([fileId],
 * [propertyId]) lives *inside* the ciphertext, never on `SyncBlock`'s
 * wire metadata — the relay must not learn file identity or which
 * property a block targets (`docs/sync/threat-model.md` §8.3
 * "Property-ID exposure to relay").
 *
 * The chunk bodies themselves are NOT here and are NOT on the ordered
 * drain — they live on the separate content plane, fetched on demand
 * by [FileChunkRef.ref] (P2). This manifest is small and bounded
 * regardless of file size, so it travels the existing `pendingBlocks`
 * drain cheaply.
 */
interface FileContentManifestPayload : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /**
     * The stable `DataId` string of the file entity these chunks belong
     * to. Bound into every chunk's AEAD AAD on the emit side so a
     * ciphertext lifted from one file's manifest cannot be replayed as
     * another file's chunk.
     */
    val fileId: TString
        get() = getField(Fields.FILE_ID)

    /**
     * The property slot the reassembled bytes write to (e.g.
     * `File.Relation.Data`). Same role as [DataBlockPayload.propertyId];
     * non-optional here because a manifest is always a property update.
     */
    val propertyId: TString
        get() = getField(Fields.PROPERTY_ID)

    /** Number of chunks; equals `chunks.size`. Bound into per-chunk AAD. */
    val chunkCount: TInteger
        get() = getField(Fields.CHUNK_COUNT)

    /** Plaintext file length in bytes. */
    val totalSize: TInteger
        get() = getField(Fields.TOTAL_SIZE)

    /**
     * Whole-file integrity anchor: hex SHA-256 over the *plaintext*
     * file. The reader (P2) recomputes it after reassembly and rejects
     * a mismatch — a relay that drops, reorders, or substitutes chunks
     * (each individually AEAD-authenticated, but the relay still
     * controls *which* refs it serves) cannot forge a whole file that
     * matches this anchor. A Merkle tree is not required for P1
     * (proposal §2.1) — a flat hash is sufficient because the reader
     * always has the complete manifest before fetching.
     */
    val wholeFileHash: TString
        get() = getField(Fields.WHOLE_FILE_HASH)

    /** Ordered per-chunk refs + ciphertext hashes. */
    val chunks: TList<FileChunkRef>
        get() = getField(Fields.CHUNKS)

    /**
     * The **content encryption key (CEK)** for these chunks, wrapped (AEAD-sealed) under the
     * realm's `sharedVolumeKey` (content-addressing §3.4). The chunks are encrypted under the
     * *random per-content* CEK rather than the realm volume key directly, so the ciphertext is
     * realm-independent — a Mode-B share re-wraps this key to the target realm instead of
     * re-encrypting bytes. The reader unwraps it with the realm's volume key(s) (current +
     * retired, GR-7) and decrypts the chunks with the CEK. Empty ⇒ legacy volume-key-encrypted
     * chunks (shouldn't occur post-Phase-0 reset).
     */
    val wrappedCek: TByteArray
        get() = getField(Fields.WRAPPED_CEK)

    companion object {
        const val ID = "tenshu/sync/fileContentManifestPayload"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                string(Fields.FILE_ID)
                string(Fields.PROPERTY_ID)
                int(Fields.CHUNK_COUNT)
                int(Fields.TOTAL_SIZE)
                string(Fields.WHOLE_FILE_HASH)
                list(Fields.CHUNKS, of = FileChunkRef.Schema)
                byteArray(Fields.WRAPPED_CEK)
            }
        )

        fun create(
            context: TContext,
            fileId: String,
            propertyId: String,
            chunkCount: Int,
            totalSize: Long,
            wholeFileHash: String,
            chunks: List<FileChunkRef>,
            wrappedCek: ByteArray,
        ): FileContentManifestPayload = context.create(
            type = ID,
            fields = listOf(
                fileId.toTString(context),
                propertyId.toTString(context),
                chunkCount.toTInteger(context),
                totalSize.toTInteger(context),
                wholeFileHash.toTString(context),
                SimpleTList(chunks),
                wrappedCek.toTByteArray(context),
            )
        )
    }

    object Fields {
        const val FILE_ID = "$ID/fileId"
        const val PROPERTY_ID = "$ID/propertyId"
        const val CHUNK_COUNT = "$ID/chunkCount"
        const val TOTAL_SIZE = "$ID/totalSize"
        const val WHOLE_FILE_HASH = "$ID/wholeFileHash"
        const val CHUNKS = "$ID/chunks"
        const val WRAPPED_CEK = "$ID/wrappedCek"
    }
}
