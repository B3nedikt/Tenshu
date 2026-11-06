package org.tenshu.sync.common

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.sync.SyncService

/**
 * Point a moved row's **deferred content intents** at where the row and its bytes are now, for a
 * caller that has just applied a move on this device (`MoveFileImplementation`, before the step that
 * retires the old `DataId`).
 *
 * ⭐ **A retarget, not a re-key.** An intent is keyed on the content it publishes, and a move
 * does not change content, so the key survives every move — including a peer's, which arrives as
 * block application where no operation could have re-keyed anything. What a LOCAL move does change is
 * physical: the row the intent names as its holder is retired, and `File.Data`'s bytes are renamed to
 * the new path. Both are rewritten here, in place, under the same key.
 *
 * ⚠⚠ Skipping it is survivable, not fatal. The drain treats the holder as a hint
 * and keeps an entry whose bytes are absent while a live row still names its content, so a missed
 * retarget costs a stuck entry rather than a deleted one. It is still owed: the drain cannot find
 * bytes it was never told moved.
 *
 * ⚠ The path is rewritten **only** for entries that actually named the moved file. A holder's
 * thumbnail classes live at a `contentId`-derived path a move does not touch
 * (`FileBytesPropertyWriter.resolveExternalPath`), so rewriting those to the file's new path would
 * point them at bytes that were never there.
 *
 * ⚠ An entry with no content id (journalled before content keying, or on the entity-keyed path)
 * carries the ENTITY key, and for it the key does change — it is re-keyed, so an intent parked
 * across the upgrade survives a move made after it.
 */
fun retargetPendingContentEmits(
    executionContext: ExecutionContext,
    oldDataId: DataId,
    newDataId: DataId,
    oldFilePath: String,
    newFilePath: String,
) {
    val oldHash = oldDataId.getDataIdHash().toString()
    val newHash = newDataId.getDataIdHash().toString()
    if (oldHash == newHash) return

    val ref = pendingContentEmitsRef(executionContext)
    val moved = executionContext.database.getArrayData(ref)
        ?.filterIsInstance<PendingContentEmit>()
        ?.toList()
        ?.filter { it.targetEntityId.getDataIdHash().toString() == oldHash }
        ?: return
    if (moved.isEmpty()) return

    executionContext.database.transaction {
        moved.forEach { entry ->
            val propertyId = entry.propertyId.get()
            val contentId = entry.contentId.get()
            val newKey = if (contentId.isNotEmpty()) {
                entry.emitKey.get()
            } else {
                PendingContentEmit.keyOf(targetEntityIdHash = newHash, propertyId = propertyId)
            }
            val retargeted = PendingContentEmit.create(
                context = executionContext.context,
                emitKey = newKey,
                targetEntityId = newDataId,
                propertyId = propertyId,
                filePath = if (entry.filePath.get() == oldFilePath) newFilePath else entry.filePath.get(),
                // ⚠ Resume state rides along, or the retry re-uploads chunks the relay already
                // has — the waste the journal exists to avoid.
                contentHash = entry.contentHash.get(),
                wrappedCek = entry.wrappedCek.get(),
                landedChunkHashes = entry.landedChunkHashes.get(),
                contentId = contentId,
            )
            val oldEntryId = DataId.StringDataId.create(
                context = executionContext.context,
                type = PendingContentEmit.ID,
                keyValue = entry.emitKey.get(),
            )
            executionContext.database.removeFromArray(ref, oldEntryId)
            // ⚠⚠ Delete the ROW too when the key changes, not just the membership — the same leak
            // `SyncObserver.clearDeferredContent` exists to avoid. Every counter and the drain read
            // the ARRAY, so an orphaned row reports as nothing pending and accumulates silently,
            // reading exactly like a stuck upload when inspected.
            if (newKey != entry.emitKey.get()) executionContext.deleteData(oldEntryId)
            executionContext.database.addToArray(ref, retargeted)
        }
    }
}

private fun pendingContentEmitsRef(executionContext: ExecutionContext) = ArrayDatabaseReference(
    arrayId = DataId.RelationDataId.create(
        context = executionContext.context,
        type = PendingContentEmit.ID,
        relationId = SyncService.Collections.PENDING_CONTENT_EMITS,
    ),
    isEntity = false,
    elementSchemaId = PendingContentEmit.ID,
)
