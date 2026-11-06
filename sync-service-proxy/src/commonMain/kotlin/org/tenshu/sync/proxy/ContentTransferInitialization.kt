package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.sync.ContentTransferStatus
import org.tenshu.sync.ContentUploadProgress
import org.tenshu.sync.SyncService

/**
 * Maintains [SyncService.Relations.CONTENT_TRANSFER] as a **single-row** derived view of the
 * [SyncHealthHolder]'s deferred-content figures (`sync-metered-plane-split.md` §8) — the
 * *"Waiting for Wi-Fi. N files"* banner's data source.
 *
 * Structurally identical to [initSyncHealth], and for the same reasons: an external flow collected
 * on [scope], remove-then-add upsert inside a transaction, local derivation that never leaves the
 * device. It is a *separate row* rather than three more fields on `SyncHealthStatus` because that
 * type is a positional composite consumed over IPC by a separately-installed Drive — see
 * [ContentTransferStatus]'s KDoc.
 *
 * ⚠ Writes only on a **change** to the figures it publishes. The holder's flow ticks on every
 * scheduler pass (1.5s while a stream is hot), and each write is a store transaction that wakes
 * every observing client; `distinctUntilChanged` over the projected triple keeps an idle device
 * quiet. [initSyncHealth] does not need this because its figures genuinely move most ticks
 * (`isSyncing`, `lastSyncedAtMillis`), whereas a deferred queue is static for as long as the user
 * is away from Wi-Fi.
 */
fun ExecutionContext.initContentTransferStatus(
    scope: CoroutineScope,
    holder: SyncHealthHolder,
) {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.CONTENT_TRANSFER,
        ),
        isEntity = false,
        elementSchemaId = ContentTransferStatus.ID,
    )
    val singletonId = DataId.StringDataId.create(
        context = context,
        type = ContentTransferStatus.ID,
        keyValue = ContentTransferStatus.SINGLETON_KEY,
    )
    scope.launch {
        holder.observe()
            .map {
                ContentTransferFigures(
                    deferred = it.deferred,
                    pendingFileCount = it.deferredContentCount,
                    pendingBytes = it.deferredContentBytes,
                )
            }
            .distinctUntilChanged()
            .collect { figures ->
              guardDerivedViewTick(SyncService.Relations.CONTENT_TRANSFER) {
                val status = ContentTransferStatus.create(
                    context = context,
                    deferred = figures.deferred,
                    pendingFileCount = figures.pendingFileCount.toLong(),
                    pendingBytes = figures.pendingBytes,
                )
                database.transaction {
                    database.removeFromArray(ref, singletonId)
                    database.addToArray(ref, status)
                }
              }
            }
    }
}

/**
 * The projection [initContentTransferStatus] de-duplicates on — everything the
 * `ContentTransferStatus` row carries, and nothing else. A data class rather than a `Triple` now
 * that P2 added the pass figures: `distinctUntilChanged` must compare *all* of them, and a
 * positional tuple is exactly the shape where a forgotten field silently stops the banner updating.
 */
private data class ContentTransferFigures(
    val deferred: Boolean,
    val pendingFileCount: Int,
    val pendingBytes: Long,
)

/**
 * Maintains [SyncService.Relations.CONTENT_UPLOAD_PROGRESS] — the "3 of 12" of an upload pass in
 * flight (`drive-upload-progress.md` P2). Same shape as [initContentTransferStatus]; a separate row
 * because `ContentTransferStatus` is positional and cannot grow fields (see [ContentUploadProgress]).
 *
 * ⚠ Writes on **change only**. A pass publishes many updates — `SyncScheduler` already throttles to
 * one per 500 ms — and each write is a store transaction that wakes every observing client.
 */
fun ExecutionContext.initContentUploadProgress(
    scope: CoroutineScope,
    holder: SyncHealthHolder,
) {
    val ref = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = context,
            type = TList.ID,
            relationId = SyncService.Relations.CONTENT_UPLOAD_PROGRESS,
        ),
        isEntity = false,
        elementSchemaId = ContentUploadProgress.ID,
    )
    val singletonId = DataId.StringDataId.create(
        context = context,
        type = ContentUploadProgress.ID,
        keyValue = ContentUploadProgress.SINGLETON_KEY,
    )
    scope.launch {
        holder.observe()
            .map { it.uploadPassTotal to it.uploadPassCompleted }
            .distinctUntilChanged()
            .collect { (total, completed) ->
              guardDerivedViewTick(SyncService.Relations.CONTENT_UPLOAD_PROGRESS) {
                val row = ContentUploadProgress.create(
                    context = context,
                    passTotal = total.toLong(),
                    passCompleted = completed.toLong(),
                )
                database.transaction {
                    database.removeFromArray(ref, singletonId)
                    database.addToArray(ref, row)
                }
              }
            }
    }
}
