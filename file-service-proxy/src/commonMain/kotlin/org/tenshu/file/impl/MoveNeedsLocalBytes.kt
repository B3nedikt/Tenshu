package org.tenshu.file.impl

import org.tenshu.core.Data as TData
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.id
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.system.MoveNeedsLocalBytesError
import org.tenshu.core.schema.propertyRef

/**
 * An **offline** move of a file whose bytes are not on this device — or of a folder holding one — is
 * refused with [MoveNeedsLocalBytesError] (`content-plane-cr2-b6-spec.md` D3, §3).
 *
 * ## Why only that case
 *
 * A move is pure metadata (the same content id under a new path), so it is safe exactly when either
 * the relay hears of it promptly — online — or this device can put the bytes back if a concurrent
 * peer write gets them freed first: the content repair re-emits from local bytes. Offline *and*
 * bytes absent is the one case nothing can repair (`content-plane-cr2-quorumless.md` §3 P1), so the
 * user is told before the move rather than losing the file after it. Thumbnail rungs are not asked
 * for: the repair re-mints them from the primary bytes.
 *
 * ## ⚠⚠ What "offline" reads
 *
 * [offline] is `SyncHealthHolder.current().relayUnreachableForFencing()` — the scheduler's cached
 * `RelayReachability` verdict, deliberately NOT `caughtUp` (an inbound property: a device mid-backlog
 * is perfectly able to push). Because that verdict can be up to 45 s old, a device that
 * *looks* online and is about to move bytes it does not hold asks the relay first ([confirmWritable]);
 * `false` (refused, unreachable, timed out) refuses, `null` (could not ask) keeps the cached verdict.
 *
 * ⚠ The local-bytes walk runs FIRST, and the probe only when it found a missing file: "has bytes" is
 * an O(1) `fileSize` per file, so a move of local content costs no round trip at all.
 *
 * ⚠ A subtree the walk cannot fully see — an unreadable member, a dangling row, a cycle, anything
 * past [MAX_NODES] — counts as missing bytes: unseen is not safe.
 */
internal object MoveNeedsLocalBytes {

    /** Bound on the subtree walk. A larger offline folder move is refused rather than walked. */
    internal const val MAX_NODES = 50_000

    suspend fun refuseIfUnsafe(
        executionContext: ExecutionContext,
        fileDatabase: FileDatabase,
        sourcePath: String,
        offline: Boolean,
        confirmWritable: (suspend () -> Boolean?)? = null,
    ) {
        val missing = firstWithoutLocalBytes(executionContext, fileDatabase, sourcePath) ?: return
        if (!offline && confirmWritable?.invoke() != false) return
        throw TenshuException(
            MoveNeedsLocalBytesError.create(
                context = executionContext.context,
                message = "$sourcePath cannot be moved while offline: the bytes of $missing are not " +
                    "on this device. Download it first, or move it once online.",
            )
        )
    }

    /**
     * The first path under [root] (itself included) whose content this device does not hold, or
     * null when every file in the subtree has local bytes. See the class KDoc for what counts.
     */
    internal fun firstWithoutLocalBytes(
        executionContext: ExecutionContext,
        fileDatabase: FileDatabase,
        root: String,
    ): String? {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            if (path in visited || visited.size >= MAX_NODES) return path
            visited += path

            val data = executionContext.database.getData(
                DataId.StringDataId.create(context = executionContext.context, type = File.ID, keyValue = path)
            ) ?: return path

            if (isFolder(data)) {
                pending += executionContext.folderChildPathsOrNull(data) ?: return path
                continue
            }

            val contentId = runCatching {
                (executionContext.resolveProperty(data, propertyRef(File.ContentId.id)) as? TString)?.get()
            }.getOrElse { return path }
            // A file with no content id references nothing (an empty upload in flight, say): there
            // is nothing to lose.
            if (contentId.isNullOrBlank()) continue
            if (fileDatabase.fileSize(path) == null) return path
        }
        return null
    }

    private fun isFolder(data: TData): Boolean = runCatching {
        if (data is Entity<*>) data.type.get() == Folder.ID else data.id == Folder.ID
    }.getOrDefault(true) // ⚠ Unreadable kind ⇒ folder: the branch that owes a subtree.
}
