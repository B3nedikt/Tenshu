package app.tenshu.control.bridge

import java.util.Locale

/**
 * Deletes and renames made in the Explorer folder, carried to the realm (X3b, desktop-windows-port.md §7.10): pure, so
 * the rules are tested without Windows.
 *
 * Windows tells us *before* it deletes or renames a placeholder (`NOTIFY_DELETE` / `NOTIFY_RENAME`), and it may still
 * fail or be undone. So a notice is never acted on as it comes: it waits [GRACE_MILLIS], and is then checked against
 * the folder as it is. ⚠⚠ What matters most is what is **not** a delete: an editor's save removes or renames the
 * original and puts the new version at its path, and that is an edit (the write path's) — trashing the original
 * would break its history.
 */
internal object ExplorerLocalOps {

    const val GRACE_MILLIS = 3_000L

    /**
     * One notice. [relative]: where the entry was; [target]: where a rename put it, `null` for a delete or a move out
     * of the folder (the Recycle Bin). [identityPath] / [version]: what our placeholder named.
     */
    data class Op(
        val relative: String,
        val target: String?,
        val identityPath: String,
        val version: String,
        val isFolder: Boolean,
        val at: Long,
    ) {
        val isDelete: Boolean get() = target == null
    }

    sealed interface Step {
        val op: Op

        /**
         * Move [realmPath] to the realm's Trash (D-X3-1) — or, when it is in the Trash already, delete it for good
         * ([permanently], D-X3-3): emptying one item of a recycle bin.
         */
        data class Trash(override val op: Op, val realmPath: String, val isFolder: Boolean, val permanently: Boolean = false) : Step

        /** `moveFile([from], [to])`: the realm follows the folder (`op.target`). */
        data class Move(override val op: Op, val from: String, val to: String, val isFolder: Boolean) : Step
    }

    /**
     * [steps] to apply, in notice order; [waiting]: notices still in their grace; [dropped]: notices the folder says
     * are not what they looked like (nothing to do — the mirror or the write path has it). [guarded]: keys (folder
     * paths, lower case) the mirror and the write path must leave alone meanwhile, subtrees included.
     */
    data class Plan(val steps: List<Step>, val waiting: List<Op>, val dropped: List<Op>, val guarded: Set<String>)

    fun plan(ops: List<Op>, remote: List<ExplorerMirror.Remote>, local: List<ExplorerMirror.Local>, now: Long): Plan {
        val localByKey = local.associateBy { key(it.relative) }
        val remoteByKey = remote.associateBy { key(it.path.trim('/')) }
        val steps = ArrayList<Step>()
        val waiting = ArrayList<Op>()
        val dropped = ArrayList<Op>()
        val trashed = ArrayList<String>() // realm-path keys a step already takes (a folder takes its subtree)
        // Folder deletes first, shallowest first: Explorer deletes a folder's contents before the folder, and the folder
        // is trashed whole (its structure kept), taking those notices with it. The rest in notice order.
        val ordered = ops.sortedWith(
            compareBy<Op>({ !(it.isDelete && it.isFolder) }, { if (it.isDelete && it.isFolder) it.relative.count { c -> c == '/' } else 0 }, { it.at }),
        )
        // ⚠ All wait until the notices have been quiet for the grace: a large folder's own delete comes seconds after its
        // contents', and planned before it arrives, each file would go to the Trash on its own.
        val quiet = ops.all { now - it.at >= GRACE_MILLIS }
        for (op in ordered) {
            if (!quiet) {
                waiting += op
                continue
            }
            val identityKey = key(op.identityPath.trim('/'))
            val row = remoteByKey[identityKey]
            if (row == null || row.isFolder != op.isFolder) {
                dropped += op // gone from the realm already, or something else is there now
                continue
            }
            if (op.isDelete) {
                // Undone, failed, or the editor's new version already stands where it was.
                if (key(op.relative) in localByKey || identityKey in localByKey) {
                    dropped += op
                    continue
                }
                // Changed elsewhere since: that version was never seen here, so it stays (the mirror shows it again).
                if (!op.isFolder && row.contentVersion != op.version) {
                    dropped += op
                    continue
                }
                // Goes with its folder; or a second notice of the same delete (the Recycle Bin sends a rename and a delete).
                if (trashed.any { identityKey == it || identityKey.startsWith("$it/") }) continue
                // ⚠ The Trash folder itself is kept (Photos puts its trash there too); its contents go with their own
                // notices, which Explorer sends before the folder's.
                if (row.path.equals(TRASH, ignoreCase = true)) {
                    dropped += op
                    continue
                }
                trashed += identityKey
                // Already in the Trash: deleted for good (D-X3-3, 2026-11-12).
                steps += Step.Trash(op, row.path, op.isFolder, permanently = isTrashed(row.path))
            } else {
                val target = op.target!!
                val source = localByKey[key(op.relative)]
                // A case-only rename finds the source under the target's key: only its spelling tells them apart.
                val caseOnly = key(op.relative) == key(target)
                val sourceStill = if (caseOnly) source != null && source.relative != target else source != null
                val arrived = localByKey[key(target)]?.takeIf { !caseOnly || it.relative == target }
                if (sourceStill || arrived == null || arrived.isFolder != op.isFolder) {
                    dropped += op // undone or failed, or moved on again (a later notice has it)
                    continue
                }
                val to = canonical(target, remote)
                val occupant = remoteByKey[key(to.trim('/'))]
                if (occupant != null && key(occupant.path) != key(row.path)) {
                    dropped += op // the realm has something there: the mirror puts both back as they are
                    continue
                }
                steps += Step.Move(op, row.path, to, op.isFolder)
            }
        }
        val guarded = HashSet<String>()
        for (op in steps.map { it.op } + waiting) {
            guarded += key(op.relative)
            op.target?.let { guarded += key(it) }
            guarded += key(op.identityPath.trim('/'))
        }
        return Plan(steps, waiting, dropped, guarded)
    }

    /** Whether [key] (lower case) is one of [guarded] or below one. */
    fun isGuarded(key: String, guarded: Set<String>): Boolean =
        key in guarded || guarded.any { key.startsWith("$it/") }

    /** [op] after the realm moved [from] to [to]: its identity follows (a later notice inside a moved folder). */
    fun rebased(op: Op, from: String, to: String): Op {
        val path = op.identityPath
        return when {
            path.equals(from, ignoreCase = true) -> op.copy(identityPath = to)
            path.lowercase(Locale.ROOT).startsWith(from.lowercase(Locale.ROOT) + "/") ->
                op.copy(identityPath = to + path.substring(from.length))
            else -> op
        }
    }

    /** Where [realmPath] goes in the Trash: the Photos convention, `/Trash/<contentId>-<name>`. */
    fun trashPathFor(realmPath: String, contentId: String?, now: Long, taken: Set<String>): String {
        val name = realmPath.substringAfterLast('/')
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        var candidate = "$TRASH/${contentId ?: now}-$name"
        var n = 2
        while (candidate.lowercase(Locale.ROOT) in lower) candidate = "$TRASH/${contentId ?: now}-$n-$name".also { n++ }
        return candidate
    }

    /** `org.tenshu.core.file.TRASH_ROOT`; ⚠ the prefix test is `"$TRASH/"`, never `startsWith(TRASH)`. */
    const val TRASH = "/Trash"

    fun isTrashed(realmPath: String): Boolean = realmPath.startsWith("$TRASH/")

    /** [relative]'s realm path, each existing folder of it in the realm's own spelling. */
    fun canonical(relative: String, remote: List<ExplorerMirror.Remote>): String {
        val folders = remote.filter { it.isFolder }.associateBy { it.path.lowercase(Locale.ROOT) }
        val segments = relative.split('/')
        var path = ""
        for ((i, segment) in segments.withIndex()) {
            val next = "$path/$segment"
            path = if (i < segments.lastIndex) folders[next.lowercase(Locale.ROOT)]?.path ?: next else next
        }
        return path
    }

    private fun key(relative: String) = relative.trim('/').lowercase(Locale.ROOT)
}
