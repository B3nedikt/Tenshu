package org.tenshu.sync.proxy

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.content.ContentDeclarations
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.SchemaRegistry
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.File
import org.tenshu.core.trait.ContentDerivationKind
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.ContentRefusal
import org.tenshu.sync.common.RevivedContent

/**
 * What a re-upload for a revive produced ([FreedContentResolver]'s `reupload` port; CR-2 B4 §2.2).
 */
sealed interface ReviveUpload {
    /** The fresh chunks and the manifest landed; [refs] is all of them, and no describe was sent. */
    data class Uploaded(val refs: List<String>) : ReviveUpload

    /** This device holds no plaintext for the content: not repairable. */
    data object NoLocalBytes : ReviveUpload

    /**
     * The upload gate is closed (metered, `507`, locked realm) or the upload failed: the bytes still
     * exist here, so the block stays queued and a later tick tries again. ⚠ Never a retire.
     */
    data object NotNow : ReviveUpload
}

/**
 * ⭐ **The way out of `content_freed`** for a queued block (CR-2 B4, `content-plane-cr2-b4-spec.md`
 * §2.2; the decision is `content-plane-cr2-quorumless.md` §9 Q2): the relay refused a push because the
 * row names content it has freed. Every drain that sends edges hands the refusal here.
 *
 * - **Repairable** — the refused id is the row's **Primary** content (B3a's declaration) and this
 *   device holds the plaintext: re-upload it under a fresh CEK, persist the refs as the block's
 *   [PendingBlockReviveStore] row **before** retrying (a crash must not re-upload: G4), and retry with
 *   the revive. On `200` the block retires as usual and the sidecar with it.
 * - **Not repairable** — retire the row: delete it with [WriteOrigin.SYNC_INGEST] (the refused block
 *   never reached the log, so no peer holds the row and no tombstone is owed — `processDeleteBlock` is
 *   the precedent), drop every queued block for that holder naming a freed id, and raise the P3
 *   notice.
 *
 * ⚠⚠ **The row moved on** is a third case the spec folds into the first two and this does not: the
 * local row no longer names the freed content (a later save changed it, or the row is gone). Then this
 * block is stale — a later block for the holder, or its tombstone, carries what it said — so it is
 * dropped with no retire and no notice. Retiring there would delete a row holding a newer change, and
 * re-uploading would put that newer file's bytes under the freed id.
 *
 * ⚠ Stateless over the local store: the scheduler builds one, and the pairing flusher reaches the same
 * instance through `SyncSchedulerHolder`.
 */
class FreedContentResolver(
    private val executionContext: ExecutionContext,
    /** The row a queued whole-row block carries, decrypted (`SyncProcessor.rowOfOwnBlock`); null if unreadable. */
    private val rowOf: (SyncBlock) -> Data?,
    /** Re-upload [contentId] for [holder]'s [propertyId] from local bytes, returning every ref, no describe. */
    private val reupload: suspend (holder: DataId, propertyId: String, contentId: String) -> ReviveUpload,
    /** The P3 notice for a retired row (B4 §2.4). */
    private val notifyChangeLost: (holder: DataId, row: Data) -> Unit,
) {

    /** What became of a refused block. */
    sealed interface Resolution {
        /** The revive is persisted: push again now. */
        data object Retry : Resolution

        /** Stays queued for a later tick (gate closed, revive incomplete, upload failed). */
        data object Held : Resolution

        /** The block, and [droppedBlockIds] with it, left the queue (retired row, or a stale block). */
        data class Dropped(val droppedBlockIds: Set<String>) : Resolution
    }

    suspend fun resolve(
        queues: List<ArrayDatabaseReference>,
        block: SyncBlock,
        blockId: String,
        refusal: ContentRefusal,
    ): Resolution = when (refusal) {
        is ContentRefusal.ReviveIncomplete -> {
            // A ref was missing or still queued for freeing: re-upload on the next tick (§2.2).
            println("FreedContentResolver -> revive incomplete for ${refusal.contentIds}; re-uploading next tick")
            PendingBlockReviveStore(executionContext).delete(blockId)
            Resolution.Held
        }
        is ContentRefusal.ContentFreed -> onContentFreed(queues, block, blockId, refusal.contentIds.toSet())
    }

    private suspend fun onContentFreed(
        queues: List<ArrayDatabaseReference>,
        block: SyncBlock,
        blockId: String,
        freed: Set<String>,
    ): Resolution {
        val reviveStore = PendingBlockReviveStore(executionContext)
        val already = reviveStore.reviveOf(blockId)
        // Ids a revive already covers would not be refused as freed; those that are not covered are the work.
        val missing = freed - already.map { it.contentId }.toSet()
        if (missing.isEmpty()) {
            reviveStore.delete(blockId)
            return Resolution.Held
        }
        val holderWire = block.targetEntityIdHash?.get()
        val pushed = rowOf(block)
        val holder = pushed?.let(executionContext::getDataId)
        val registry = executionContext.schemaProvider as? SchemaRegistry
        if (pushed == null || holder == null || holderWire == null || registry == null) {
            // ⚠ Cannot read what the block carries, so neither repair nor retire can be decided. It can
            // never land either (every push meets the same refusal), so it leaves the queue alone.
            println("FreedContentResolver -> cannot open refused block $blockId; dropping it")
            return Resolution.Dropped(dropBlocksNaming(queues, holderWire, freed, alwaysDrop = blockId))
        }
        val declarations = ContentDeclarations.of(registry)
        val stored = runCatching { executionContext.database.getData(holder, asEntity = true) }.getOrNull()
        val storedIds = stored?.let(declarations::edgesOf)?.mapNotNull { it.contentId }?.toSet().orEmpty()
        if (!storedIds.containsAll(missing)) {
            println("FreedContentResolver -> block $blockId is stale (its row moved on); dropping it")
            return Resolution.Dropped(dropBlocksNaming(queues, holderWire, missing, alwaysDrop = blockId))
        }

        val edges = PendingBlockEdgeStore(executionContext).edgesOf(blockId).filter { it.contentId != null }
        val propertyOf = edges.associate { it.contentId!! to it.propertyId }
        val repairable = missing.all { id ->
            val property = propertyOf[id] ?: return@all false
            ContentDeclarations.declarationOf(registry, property)?.kind == ContentDerivationKind.Primary
        }
        if (!repairable) return retire(queues, holder, holderWire, stored ?: pushed, missing, blockId)

        val revived = mutableListOf<RevivedContent>()
        for (id in missing) {
            when (val upload = reupload(holder, propertyOf.getValue(id), id)) {
                is ReviveUpload.Uploaded -> revived += RevivedContent(id, upload.refs)
                ReviveUpload.NoLocalBytes -> return retire(queues, holder, holderWire, stored ?: pushed, missing, blockId)
                ReviveUpload.NotNow -> {
                    // ⚠ Keep what did land, so the next tick re-uploads only the rest.
                    reviveStore.save(blockId, already + revived)
                    return Resolution.Held
                }
            }
        }
        // ⚠⚠ Durable BEFORE the retry: a crash from here re-sends these refs, never re-uploads.
        reviveStore.save(blockId, already + revived)
        return Resolution.Retry
    }

    /** §9 Q2: the row has no bytes anywhere. Gone here too, with a notice. */
    private fun retire(
        queues: List<ArrayDatabaseReference>,
        holder: DataId,
        holderWire: String,
        row: Data,
        freed: Set<String>,
        blockId: String,
    ): Resolution {
        println("FreedContentResolver -> content $freed is freed and not repairable here; retiring the row")
        val dropped = dropBlocksNaming(queues, holderWire, freed, alwaysDrop = blockId)
        // ⚠⚠ SYNC_INGEST: no outbound tombstone. The refused block never reached the log, so no peer
        // holds this row, and a tombstone would only remove edges the relay never had.
        runCatching { executionContext.deleteData(holder, origin = WriteOrigin.SYNC_INGEST) }
            .onFailure { println("FreedContentResolver -> could not delete the retired row: ${it.message}") }
        runCatching { notifyChangeLost(holder, row) }
            .onFailure { println("FreedContentResolver -> could not raise the change-lost notice: ${it.message}") }
        return Resolution.Dropped(dropped)
    }

    /**
     * Drops every queued block for [holderWire] whose edges name an id in [ids], and [alwaysDrop]
     * whatever it names, each with its sidecars. Returns the dropped block ids.
     */
    private fun dropBlocksNaming(
        queues: List<ArrayDatabaseReference>,
        holderWire: String?,
        ids: Set<String>,
        alwaysDrop: String,
    ): Set<String> {
        val edgeStore = PendingBlockEdgeStore(executionContext)
        val dropped = mutableSetOf<String>()
        for (queue in queues) {
            val queued = executionContext.database.getArrayData(queue)?.toList().orEmpty().filterIsInstance<SyncBlock>()
            for (candidate in queued) {
                val id = candidate.blockId.get()
                val names = id == alwaysDrop || (
                    holderWire != null && candidate.targetEntityIdHash?.get() == holderWire &&
                        edgeStore.edgesOf(id).any { edge -> edge.contentId?.let(ids::contains) == true }
                    )
                if (!names) continue
                val dataId = executionContext.getDataId(candidate) ?: continue
                executionContext.database.transaction {
                    executionContext.database.removeFromArray(queue, dataId)
                    executionContext.deleteRetiredBlock(candidate)
                }
                dropped += id
            }
        }
        return dropped
    }

    companion object {
        /** The path a retired row's notice names: a file's own path, else the row's key. */
        fun pathOf(holder: DataId, row: Data): String =
            (row as? Entity<*>)?.let { runCatching { it.optionalFieldValue<TString>(File.Field.PATH)?.get() }.getOrNull() }
                ?: (holder as? DataId.StringDataId)?.keyValue?.get()
                ?: holder.toString()
    }
}
