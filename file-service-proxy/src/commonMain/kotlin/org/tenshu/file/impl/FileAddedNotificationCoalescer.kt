package org.tenshu.file.impl

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.notifications.FileAddedBurst
import org.tenshu.core.file.notifications.FileAddedNotification
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Producer-side burst coalescing for [FileAddedNotification]
 * (`docs/proposals/notification-burst-aggregation.md` §2.2).
 *
 * A bulk upload (Synced-Folders auto-backup, folder upload) reaches the proxy
 * as N independent `UploadFile` ops. Minting one **synced** notification row
 * per file made the row count unbounded (~920 rows observed for one photo
 * backup — every one encrypted, relayed, and stored on every realm device) and
 * forced the events feed to fold the backlog client-side on every emission
 * (the Control Home ANR). Instead, uploads within [BURST_WINDOW_MS] of each
 * other share **one** notification row: the same `uuid` is re-saved with
 * `count + 1`, the newest path, and a refreshed timestamp. Re-saving is an
 * ordinary LastWriterWins row update on the existing sync path (`Notification`
 * is keyed by `uuid`), and `NotificationRelationMaintainer`'s remove-then-add
 * convergence keeps the relation at one entry per uuid.
 *
 * Coalescing state is the durable, local-only [FileAddedBurst] pointer record,
 * not in-memory tracker state: implementations are minted per op dispatch, so
 * there is no instance to hold state on, and a durable record additionally
 * survives a proxy restart mid-backup. The read-decide-write runs inside
 * `Database.transaction` (the DB's reentrant lock), so concurrent uploads on
 * one proxy cannot interleave the pointer update. Bursts are deliberately
 * **per producing device** — only the minting proxy ever touches its burst
 * row, so the in-place update is conflict-free by construction.
 *
 * Interleaved folder/delete notifications do *not* end a burst (delta vs the
 * old client-side fold, which broke a run on any non-file-added row): the
 * burst is a single advancing row, and other event rows simply sort around it
 * by timestamp.
 */
internal object FileAddedNotificationCoalescer {

    /**
     * Max gap between two consecutive file uploads for them to count as the
     * same burst. Sized to bridge per-file upload latency (large photos take a
     * while each) without merging genuinely separate user actions. Moved here
     * from the client-side `NotificationMapper.BURST_WINDOW` — the window now
     * closes against the minting clock, not re-derived at read time.
     */
    const val BURST_WINDOW_MS = 60_000L

    @OptIn(ExperimentalUuidApi::class)
    fun recordFileAdded(
        executionContext: ExecutionContext,
        path: String,
        nowMillis: Long,
    ) {
        executionContext.database.transaction {
            val previous = loadBurst(executionContext)
            val elapsed = previous?.let { nowMillis - it.lastTimestamp.get() }
            val sameBurst = elapsed != null && elapsed in 0..BURST_WINDOW_MS

            val uuid = if (sameBurst) previous!!.uuid.get() else Uuid.random().toString()
            val count = if (sameBurst) previous!!.count.get() + 1 else 1L

            // The visible row: syncs (Notification is Syncable(Eager)) and joins
            // the NOTIFICATIONS relation via the maintainer. Same-uuid re-save =
            // in-place bump.
            executionContext.saveData(
                FileAddedNotification.create(
                    context = executionContext.context,
                    uuid = uuid,
                    timestamp = nowMillis,
                    path = path,
                    count = count,
                )
            )
            // The pointer: Database-level write (not ExecutionContext.saveData) —
            // local bookkeeping only, no observers, no sync. Same shape as
            // ResolvedDataId (TombstoneRegistry.rememberDataId).
            executionContext.database.saveData(
                FileAddedBurst.create(
                    context = executionContext.context,
                    uuid = uuid,
                    lastTimestamp = nowMillis,
                    count = count,
                )
            )
        }
    }

    private fun loadBurst(executionContext: ExecutionContext): FileAddedBurst? {
        val recordId = DataId.StringDataId.create(
            context = executionContext.context,
            type = FileAddedBurst.ID,
            keyValue = FileAddedBurst.KEY_CURRENT
        )
        return executionContext.database.getData(recordId) as? FileAddedBurst
    }
}
