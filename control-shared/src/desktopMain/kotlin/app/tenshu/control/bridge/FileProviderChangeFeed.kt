package app.tenshu.control.bridge

import java.util.UUID

/**
 * Turns "what does the realm look like now" into the deltas Finder asks for
 * (docs/proposals/desktop-macos-integration.md §5a).
 *
 * The File Provider framework is anchor-based: it hands back the last anchor it was given and
 * wants everything that changed since. The realm has no files-scoped change log to answer that
 * from — `FileModification` has no *deleted* row, and the sync cursors do not move on local
 * writes — so the feed is **computed**: each [refresh] is handed a full snapshot, diffs it
 * against the previous one, and records the difference under a new anchor.
 *
 * ⚠ **One global feed, many readers.** The working-set enumerator and every folder enumerator
 * the system holds read it independently, each with its own last-seen anchor. So a refresh
 * must not strand the others: the last [HISTORY] deltas are kept and a reader behind by
 * several is answered with their union. Only a reader older than the history — or one from
 * before this process started — is told its anchor expired, which makes the system re-list.
 *
 * ⚠ **In memory.** A Control restart expires every anchor. That costs one re-list and is
 * always correct; persisting the snapshot would save the re-list and add a way to be wrong.
 */
internal class FileProviderChangeFeed {

    /** What changed since an anchor; [updated] holds ids, resolved to items by the caller. */
    data class Changes(val anchor: String, val updated: Set<String>, val deleted: Set<String>)

    private class Delta(val number: Long, val updated: Set<String>, val deleted: Set<String>)

    // Distinguishes this process's anchors from a previous run's, whose numbering restarted.
    private val epoch = UUID.randomUUID().toString().take(8)
    private var number = 0L
    private var snapshot: Map<String, String>? = null
    private val history = ArrayDeque<Delta>()

    /** Folds the realm's current state — `id → version` — into the feed; returns the anchor. */
    @Synchronized
    fun refresh(current: Map<String, String>): String {
        val previous = snapshot
        if (previous != null) {
            val updated = current.filterTo(HashMap()) { (id, version) -> previous[id] != version }.keys
            val deleted = previous.keys - current.keys
            if (updated.isNotEmpty() || deleted.isNotEmpty()) {
                number++
                history.addLast(Delta(number, updated.toSet(), deleted))
                while (history.size > HISTORY) history.removeFirst()
            }
        }
        snapshot = current
        return anchor()
    }

    /**
     * Everything recorded after [since], or `null` when [since] cannot be answered from the
     * history — the caller reports the anchor as expired. Call [refresh] first.
     */
    @Synchronized
    fun changesSince(since: String): Changes? {
        val seen = since.removePrefix("$epoch:").takeIf { it != since }?.toLongOrNull() ?: return null
        if (seen > number) return null
        if (seen == number) return Changes(anchor(), emptySet(), emptySet())
        val oldestAnswerable = (history.firstOrNull()?.number ?: (number + 1)) - 1
        if (seen < oldestAnswerable) return null
        val updated = HashSet<String>()
        val deleted = HashSet<String>()
        for (delta in history) {
            if (delta.number <= seen) continue
            // Later deltas override earlier ones: deleted-then-recreated is an update, and
            // updated-then-deleted is a delete.
            updated -= delta.deleted
            deleted -= delta.updated
            updated += delta.updated
            deleted += delta.deleted
        }
        return Changes(anchor(), updated, deleted)
    }

    private fun anchor(): String = "$epoch:$number"

    companion object {
        const val HISTORY = 64
    }
}
