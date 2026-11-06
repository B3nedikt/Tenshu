package org.tenshu.file.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.core.file.sync.OrphanedBytesCandidates
import org.tenshu.core.file.sync.OrphanedBytesPolicy
import org.tenshu.preference.StringListAdapter

/**
 * Collects plaintext this device kept after a delete it *received* — `docs/tech-debt.md` *"A delete
 * frees the deleting device's disk and no one else's"*.
 *
 * An ingested tombstone removes the row and nothing else, so the bytes stay on disk with nothing
 * pointing at them and no pass to find them. ⚠ A **move** is the same event to a peer, so this
 * collects the bytes a move strands as well.
 *
 * ```
 * candidates → row still gone? → bytes still there? → old enough? → deleteFile → forget
 * ```
 *
 * ⚠⚠ **The decision is [OrphanedBytesPolicy]'s, and this class only carries it out.** Everything
 * that could be got wrong — a path that came back, an upload still writing, a reserved namespace, a
 * platform with no mtime — is a pure branch with a test, precisely because deleting a user's bytes
 * is not walkable-back.
 *
 * ⚠ Never a zero-length write, always `deleteFile`. An empty file is not an absent one
 * (`fileSize` → `0L`, not `null`), which reads as "downloaded" and wedges the `OnDemand` fetch off
 * permanently — the trap recorded in the byte-read audit.
 */
class OrphanedBytesSweep(
    private val executionContext: ExecutionContext,
    private val fileDatabase: FileDatabase,
    private val deviceId: () -> String?,
    private val now: () -> Long,
    /**
     * Whether a journalled upload intent still reads its bytes from a path
     * (`FileContentSyncEmitter.hasQueuedContentAt`).
     *
     * ⚠⚠ Required, with no default, on purpose: "nothing is owed" is the answer that deletes the only
     * copy of an upload a peer moved before it drained, and a defaulted seam is exactly how
     * that answer ends up in production unnoticed. A wiring that cannot tell must say `true`.
     */
    private val isOwed: suspend (path: String) -> Boolean,
    private val settleMs: Long = OrphanedBytesPolicy.DEFAULT_SETTLE_MS,
) {

    /** [dropped] bytes actually deleted; [kept] candidates still waiting. */
    data class Outcome(val dropped: Int, val kept: Int)

    suspend fun run(): Outcome {
        val device = deviceId()?.takeIf { it.isNotBlank() } ?: return Outcome(0, 0)
        val candidates = executionContext.readOrphanedBytesCandidates(device)
        if (candidates.isEmpty()) return Outcome(0, 0)

        val nowMs = now()
        val finished = mutableListOf<String>()
        var dropped = 0

        for (candidate in candidates) {
            val rowId = DataId.StringDataId.create(
                context = executionContext.context,
                type = File.ID,
                keyValue = candidate.path,
            )
            val verdict = OrphanedBytesPolicy.verdictFor(
                candidate = candidate,
                rowExists = executionContext.database.getData(rowId) != null,
                bytesPresent = fileDatabase.fileSize(candidate.path) != null,
                bytesStillOwed = isOwed(candidate.path),
                bytesLastModifiedMs = fileDatabase.lastModifiedMs(candidate.path),
                nowMs = nowMs,
                settleMs = settleMs,
            )
            when (verdict) {
                OrphanedBytesPolicy.Verdict.WAIT -> Unit
                OrphanedBytesPolicy.Verdict.FORGET -> finished += candidate.path
                OrphanedBytesPolicy.Verdict.DROP -> {
                    // ⚠ A failed delete keeps the candidate: the next sweep re-decides from scratch
                    // rather than retrying a verdict that may have gone stale.
                    val ok = runCatching { fileDatabase.deleteFile(candidate.path) }.isSuccess
                    if (ok) {
                        dropped++
                        finished += candidate.path
                    }
                }
            }
        }

        if (finished.isNotEmpty()) {
            executionContext.writeOrphanedBytesCandidates(
                device,
                OrphanedBytesCandidates.remove(candidates, finished),
            )
        }
        return Outcome(dropped = dropped, kept = candidates.size - finished.size)
    }
}

/**
 * The production [OrphanedBytesRecorder] — the seam DI hands to `initFileService`.
 *
 * ⚠ A named factory rather than a lambda in the DI module: what it encodes is policy (whose list,
 * stamped by which clock), and policy in wiring is untestable and unread.
 *
 * ⚠⚠ The write is suspend and the observer is not, so it is launched on [scope]. That is why
 * recording is best-effort rather than ordered: a note lost to a cancelled scope costs disk, and
 * blocking sync ingest to guarantee it would cost the thing the user is waiting on.
 */
fun orphanedBytesRecorderFactory(
    executionContext: ExecutionContext,
    scope: CoroutineScope,
    deviceId: () -> String?,
    now: () -> Long,
): OrphanedBytesRecorder = OrphanedBytesRecorder { path ->
    val device = deviceId()?.takeIf { it.isNotBlank() } ?: return@OrphanedBytesRecorder
    scope.launch {
        bestEffort("OrphanedBytesSweep -> recording an orphaned-bytes candidate") {
            val existing = executionContext.readOrphanedBytesCandidates(device)
            val merged = OrphanedBytesCandidates.merge(existing, listOf(path), now())
            // ⚠ Skip a no-op write: this rides a synced device row, so an unchanged list still costs
            // every peer a block — the derived-broadcast traffic shape.
            if (merged.size != existing.size) {
                executionContext.writeOrphanedBytesCandidates(device, merged)
            }
        }
    }
}

/** This device's orphaned-byte candidates, read straight off the store — see `ProxyDeviceSettings`. */
internal fun ExecutionContext.readOrphanedBytesCandidates(
    deviceId: String,
): List<OrphanedBytesCandidates.Entry> {
    val stored = StringListAdapter.fromData(
        readDeviceSettingData(deviceId, OrphanedBytesCandidates.Candidates.id)
    ) ?: return emptyList()
    return OrphanedBytesCandidates.decode(stored)
}

internal suspend fun ExecutionContext.writeOrphanedBytesCandidates(
    deviceId: String,
    entries: List<OrphanedBytesCandidates.Entry>,
) = writeDeviceSettingValue(
    deviceId = deviceId,
    preferenceId = OrphanedBytesCandidates.Candidates.id,
    value = StringListAdapter.toData(OrphanedBytesCandidates.encode(entries), context),
)
