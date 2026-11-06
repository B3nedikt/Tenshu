package org.tenshu.core.file.sync

/**
 * The other missing half of ST-3b (`drive-upload-stop-and-reclamation.md` §4.10): **every
 * `contentId` any remaining file still carries**, realm-wide.
 *
 * ⚠⚠ **This is the input whose incompleteness destroys data.** `ContentReclamationPolicy` treats an
 * absent id as "nothing references this any more", so a set that is merely *mostly* right reclaims
 * the bytes of every file it happened to miss — and for most owners the relay holds the only copy.
 * A partial answer is not a degraded answer here; it is a wrong one.
 *
 * That is why the result is a [Result] with an explicit [Result.Incomplete] rather than a bare
 * `Set<String>`. A walk that fails part-way, hits a folder it cannot read, or is cancelled must be
 * *unable* to be mistaken for a complete one. Returning the ids gathered so far — the obvious,
 * friendly thing — would be the bug.
 */
object LiveContentIds {

    sealed interface Result {
        /**
         * Every live `contentId` in the realm, as this device's **currently synced** view has it.
         *
         * ⚠ "Complete" means the walk finished, not that the realm has converged. A peer's copy
         * that has not reached this device is invisible here — which is precisely the race the
         * policy's settle window bounds, and cannot close.
         */
        data class Complete(val contentIds: Set<String>) : Result

        /**
         * The walk could not be finished, so the caller **must not reclaim anything**.
         *
         * ⚠ Carries no partial set on purpose. Handing back what was gathered invites a caller to
         * "just use it", and the one thing this type exists to prevent is exactly that.
         */
        data class Incomplete(val reason: String) : Result
    }

    /**
     * Walk [children] from [root] depth-first, collecting the `contentId` of every file.
     *
     * @param children lists one folder's entries, or `null` if that folder could not be read —
     *   which fails the whole walk. ⚠ `null` and "empty folder" must be distinguishable: an
     *   unreadable folder that reported itself empty is how a complete-looking answer loses a
     *   subtree, and with it every file underneath.
     */
    suspend fun collect(
        root: String,
        children: suspend (folderPath: String) -> List<Node>?,
    ): Result {
        val found = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(root))
        val visited = mutableSetOf<String>()

        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            // A cycle would otherwise spin forever. Paths are a tree today, so this is a guard
            // against a future shape (a mount, a placement) rather than a known case.
            if (!visited.add(folder)) continue

            val entries = children(folder)
                ?: return Result.Incomplete("could not list $folder")

            for (entry in entries) {
                when (entry) {
                    is Node.Folder -> pending += entry.path
                    is Node.File ->
                        // ⚠ A file with no contentId is skipped, NOT treated as an error: a file
                        // created before content-addressing, or one whose bytes were never
                        // uploaded, has none and owns no blobs to protect.
                        entry.contentId?.takeIf { it.isNotBlank() }?.let { found += it }
                }
            }
        }
        return Result.Complete(found)
    }

    /** One entry in a folder listing, reduced to what the walk needs. */
    sealed interface Node {
        data class Folder(val path: String) : Node
        data class File(val path: String, val contentId: String?) : Node
    }
}
