package org.tenshu.sync.relay

import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTByteArray
import org.tenshu.sync.SyncBlock

/**
 * [SyncBlock] wrapper that delegates every field to the original except
 * [encryptedPayload], which is replaced with an empty `TByteArray`. Used by the GC sweep
 * to free body bytes while preserving the envelope, so subsequent `/sync/changes` queries
 * still indicate the entity was deleted (`docs/adr/0006-sync-tombstones.md` — "drop
 * encryptedPayload, keep the envelope").
 *
 * Shared by both [MailboxStore] backends: the in-memory [SyncMailbox] swaps a live block
 * for this wrapper in place; [org.tenshu.sync.relay.storage.SqliteSyncMailbox] re-wraps a
 * parsed block on read whenever its `body_dropped` flag is set. Reconstructing via the
 * wrapper (rather than `SyncBlock.create`) avoids needing a `TContext` on the read path.
 */
internal class EnvelopeOnlySyncBlock(private val original: SyncBlock) : SyncBlock {
    override val schema: CompositeSchema get() = original.schema
    private val empty: TByteArray = byteArrayOf().toTByteArray()

    override val blockId: TString get() = original.blockId
    override val ownerId: TString get() = original.ownerId
    override val hlc: HybridLogicalClock get() = original.hlc
    override val encryptedPayload: TByteArray get() = empty
    override val targetEntityIdHash: TString? get() = original.targetEntityIdHash

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getField(reference: String): T = when (reference) {
        SyncBlock.Fields.ENCRYPTED_PAYLOAD -> empty as T
        else -> original.getField(reference)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data> getOptionalField(reference: String): T? = when (reference) {
        SyncBlock.Fields.ENCRYPTED_PAYLOAD -> empty as T
        else -> original.getOptionalField(reference)
    }
}
