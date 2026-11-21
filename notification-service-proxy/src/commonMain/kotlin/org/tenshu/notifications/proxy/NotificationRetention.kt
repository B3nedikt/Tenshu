package org.tenshu.notifications.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import org.tenshu.core.Data
import org.tenshu.core.entity.DataId
import org.tenshu.core.common.proxy.DataObserver
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.notifications.DismissedRequest
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.notifications.dismissedNotificationMarker
import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.sync.common.TombstoneEmitterHolder

/**
 * Keeps the notification log bounded: the newest [RETAIN_ROWS], within [RETAIN_MS] of the newest
 * notification, whichever keeps fewer (`control-central-notifications.md` §10 D2). A notification is
 * an event, and before this nothing ever removed one — on any device, or from the relay log.
 *
 * Pruned with **ordinary tombstones** (emitted through the [TombstoneEmitter], one batch per pass), so
 * every peer drops the rows too and the relay's tombstone GC can drop their history. A pruned notice's
 * dismissal marker goes with it.
 *
 * ⚠ Best-effort and convergent: every device prunes by the same rule over the same rows, so a missed
 * prune costs rows, never correctness, and two devices pruning one row write the same tombstone. The
 * age is measured from the newest row rather than this device's clock for the same reason — a pass
 * only runs on a new save, so the newest row is "now", and no device's clock decides for the others.
 *
 * ⚠ Triggered by a **local** notification save, never by sync ingest: a device catching up must not
 * answer every block it pulls with a pass. At most one pass per [MIN_INTERVAL_SAVES] local saves after
 * the first, because the upload-burst coalescer re-saves one notice per file — so the log can stand at
 * up to that many rows over the cap between passes.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class NotificationRetention(
    private val executionContext: ExecutionContext,
    private val scope: CoroutineScope,
    /**
     * What makes a prune reach the other devices and the relay. ⚠⚠ `deleteData` alone deletes **here
     * only** — `SyncObserver.onDataDeleted` is a deliberate no-op — so without this every peer kept
     * the rows and the relay never saw a deletion to collect. `null` only where there is no sync at
     * all (a bare test proxy); see [prune] for a holder whose emitter is not up yet.
     */
    private val tombstones: TombstoneEmitterHolder?,
) : DataObserver {

    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val savesSincePass = AtomicInt(-1)

    override fun onDataSaved(data: Data, origin: WriteOrigin) {
        if (origin == WriteOrigin.SYNC_INGEST || data !is Notification) return
        val since = savesSincePass.incrementAndFetch()
        // The first local save after start always runs: it is what clears an old backlog.
        if (since in 1 until MIN_INTERVAL_SAVES) return
        savesSincePass.store(0)
        // ⚠ A pass already running answers this save too, but only if it runs once more: rows this
        // save added may land after its read.
        if (!running.compareAndSet(expectedValue = false, newValue = true)) {
            again.store(true)
            return
        }
        scope.launch {
            try {
                do {
                    again.store(false)
                    prune()
                } while (again.load())
            } catch (e: Exception) {
                println("NotificationRetention -> pass failed: ${e.message}")
            } finally {
                running.store(false)
            }
        }
    }

    override fun onDataDeleted(dataId: DataId, origin: WriteOrigin) = Unit

    /** One pass; returns how many notifications it removed. */
    fun prune(): Int {
        val notifications = members(NotificationService.Relations.NOTIFICATIONS).filterIsInstance<Notification>()
        val doomed = notificationsToPrune(notifications.map { it.uuid.get() to it.timestamp.get() })
        if (doomed.isEmpty()) return 0

        val markers = doomed.mapTo(HashSet(), ::dismissedNotificationMarker)
        val rows: List<Data> = notifications.filter { it.uuid.get() in doomed } +
            members(NotificationService.Relations.DISMISSED_REQUESTS).filterIsInstance<DismissedRequest>()
                .filter { it.requestId.get() in markers }
        val targets = rows.mapNotNull { row ->
            executionContext.getDataId(row)?.let { TombstoneEmitter.Target(it, row.schema.id.get()) }
        }

        if (tombstones != null) {
            // ⚠ Sync not up yet: a local-only delete now is exactly the defect, so wait for a later pass.
            val emitter = tombstones.emitter ?: return 0
            // ⚠⚠ `null` = these rows could not be named for the wire. Deleting anyway destroys the only
            // ids that could tombstone them later, so keep them. `emptyList()` = no realm: local is right.
            emitter.emitAll(targets) ?: return 0
        }
        targets.forEach { executionContext.deleteData(it.entityId, origin = WriteOrigin.USER) }
        return doomed.size
    }

    private fun members(relationId: String): List<Data> =
        executionContext.getArrayData(executionContext.relationReference(relationId))?.toList().orEmpty()

    companion object {
        /** `RoutineRun`'s numbers (maintainer, §10 Q2). */
        const val RETAIN_ROWS = 200
        const val RETAIN_MS = 30L * 24 * 60 * 60 * 1000

        /** Local notification saves between two passes. */
        const val MIN_INTERVAL_SAVES = 20
    }
}

/**
 * The uuids of [rows] (uuid to epoch-millis timestamp) outside the newest [keepRows], or more than
 * [keepMs] older than the newest row. Ties break by uuid, so every device picks the same rows.
 */
internal fun notificationsToPrune(
    rows: List<Pair<String, Long>>,
    keepRows: Int = NotificationRetention.RETAIN_ROWS,
    keepMs: Long = NotificationRetention.RETAIN_MS,
): Set<String> {
    val newestFirst = rows.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
    val newest = newestFirst.firstOrNull()?.second ?: return emptySet()
    return newestFirst.withIndex()
        .filter { (index, row) -> index >= keepRows || newest - row.second > keepMs }
        .mapTo(HashSet()) { it.value.first }
}
