package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.File
import org.tenshu.sync.common.TombstoneEmitterHolder

/**
 * Deletes `File`/`Folder` rows that no longer hang off the tree
 * (`core-local-store-orphan-gc.md` §9).
 *
 * ## Why this exists, and why it is not cosmetic
 *
 * `DeleteFileImplementation` emits **one** tombstone, for the delete's root, so a peer applying it
 * deletes the folder row and nothing beneath it. [DeletedFolderSubtreeReaper] reproduces the
 * cascade there; a descendant it does not reach is stranded (measured in §9.1).
 *
 * ⚠⚠ **Those rows are not merely wasted space — they block recovery.** `UploadFileImplementation`
 * tests for an existing file **by path `DataId`**, not by reachability:
 * `getData(StringDataId(File.ID, path)) != null` ⇒ `AlreadyExistsError`. An orphaned row therefore
 * still *occupies its destination path*, so Synced Folders re-uploads resolve to
 * `BackupUploadOutcome.Skipped` and the folder can never be rebuilt — the drain runs, skips every
 * file, and reports `UP_TO_DATE`. That is the user-visible symptom this sweep removes.
 *
 * ⚠⚠ **This is a standing backstop, not one-time cleanup** (§9.7). `DeletedFolderSubtreeReaper`
 * cascades on a folder row's death, so the ordinary path creates no orphans — but a store that has
 * reached [ObjectStore.SWEEP_COMPLETE], which is *terminal*, can still gain them: the reaper degrades
 * to *"descendants not reaped"* when it cannot read the children array. Top-level array reads skip a
 * dangling member rather than throw, but nothing establishes that no other path strands rows, so:
 *
 *  - the reaper **re-arms** it ([ObjectStore.clearOrphanedFileSweepCursor]) whenever its cascade
 *    cannot read the children it was supposed to delete, whatever the reason; and
 *  - a **one-shot repair** ([ObjectStore.FILE_SWEEP_REPAIR_VERSION]) re-arms every device exactly
 *    once, which is the only thing that reaches rows a store stranded without re-arming.
 *
 * It still self-terminates, so a swept device stops paying for a scan it does not need.
 *
 * ## The rule, and why it is this rule rather than reachability
 *
 * A row is garbage **iff some ancestor on its own path is absent**. `/a/b/c.jpg` is garbage when
 * `/a/b` or `/a` has no row.
 *
 * §5.3 rejects a general mark-and-sweep because "it must walk the whole store… and a bug
 * deletes user data". This rule needs neither a global reference graph nor a root walk: each
 * candidate is decided by at most *path-depth* point lookups against rows that either exist or do
 * not. It cannot mis-delete a reachable row, because a row whose ancestors all exist is never a
 * candidate — regardless of what else in the system may reference it.
 *
 * ⚠ Checking **every** ancestor rather than only the immediate parent is what makes one pass
 * sufficient for a chain: `/a/b/c/d.jpg` under a deleted `/a/b` is caught now, instead of only
 * after `/a/b/c` was collected on some later cycle.
 *
 * ⚠⚠ The root `/` is never a candidate — it is bootstrapped by `initFileService`, has no parent,
 * and deleting it would take the entire tree.
 *
 * ## Shape
 *
 * Bounded and resumable, exactly as `RetiredBlockSweeper`: [PAGE] matches collected, [EXAMINE_PAGE]
 * rows looked at, cursor persisted between passes. That boundedness is a requirement, not an
 * optimisation — an unbounded sweep at startup is an iOS launch-watchdog kill.
 *
 * ⚠ **Refuses rather than guesses**: a store that cannot enumerate by type returns `null` from
 * [ObjectStore.scanRowsOfType], and this sweep then does nothing at all rather than deleting on a
 * partial view.
 */
class OrphanedFileSweeper(
    private val executionContext: ExecutionContext,
    private val objectStore: ObjectStore?,
    private val examineLimit: Int = EXAMINE_PAGE,
    /**
     * ⭐ When sync is present, an orphan is removed with a **real tombstone**, not a silent local
     * delete (`content-plane-cr2-quorumless.md` §10.12 Q4). The reaper keeps a descendant written
     * after its folder's deletion, and hands it here; a silent delete would drop it on this device
     * only, while the others and the relay kept it. Null ⇒ no sync on this runtime, and the delete
     * stays local.
     */
    private val tombstoneEmitterHolder: TombstoneEmitterHolder? = null,
) {

    /** One bounded pass: rows scanned, rows deleted, and whether the sweep is finished. */
    data class Pass(val scanned: Int, val deleted: Int, val done: Boolean)

    private var cursor: Long? = null
    private var finished = false

    /**
     * Whether this instance has ever been *called*, and whether it has already explained a
     * refusal.
     *
     * ⚠⚠ **A sweep that never runs and a sweep that runs and refuses are indistinguishable from
     * outside.** An absent cursor row cannot say whether the hook was never registered, never
     * called, or called and refusing every time; these two log lines can (§9.5).
     */
    private var loggedFirstCall = false
    private var loggedRefusal = false

    fun sweepOnce(): Pass? {
        if (!loggedFirstCall) {
            loggedFirstCall = true
            println("OrphanedFileSweeper: first pass requested")
        }
        val store = objectStore ?: return refuse("no ObjectStore was supplied")

        // ⚠⚠ Re-check the persisted marker rather than trusting the in-memory latch: the sweep can
        // be **re-armed** after it finished, by a `DeletedFolderSubtreeReaper` that could not
        // cascade (see [ObjectStore.clearOrphanedFileSweepCursor]). That re-arm clears a store row,
        // and this instance outlives it — a latch believed forever would swallow every re-arm and
        // leave the stranded subtree uncollectable.
        //
        // ⚠ One point read per tick on an already-swept device, NOT a scan. What the sentinel
        // exists to prevent is the full read of every row (§7.1); a single reserved-row lookup is
        // the price of noticing that the world changed.
        if (finished) {
            if (store.loadOrphanedFileSweepCursor() == ObjectStore.SWEEP_COMPLETE) return null
            println("OrphanedFileSweeper: re-armed — completion marker cleared, walking again")
            finished = false
            cursor = null
        }

        val from = cursor ?: store.loadOrphanedFileSweepCursor()?.also {
            // A device that already finished must never scan again — the same reason
            // `RetiredBlockSweeper` persists its terminal sentinel.
            if (it == ObjectStore.SWEEP_COMPLETE) finished = true
        } ?: Long.MIN_VALUE
        if (finished) return null
        // ⚠⚠ An ancestor's absence is evidence only on a store that has pulled its realm's whole
        // history (`core-local-store-orphan-gc.md` §9.8). The backward history walk applies the log
        // newest-first, so on a freshly joined device, or a store reset to re-pull, a file arrives
        // before the folder it sits in; judged then, it would be tombstoned **realm-wide**. Checked
        // before the scan and before the cursor moves, so nothing is skipped either.
        if (!mayJudgeAbsence(store)) return waitForHistory()
        cursor = from

        val page = store.scanRowsOfType(
            typeId = File.ID,
            afterHash = from,
            matchLimit = PAGE,
            examineLimit = examineLimit,
        ) ?: return refuse("this store cannot enumerate by type (scanRowsOfType returned null)")

        // ⚠ `exhausted`, NEVER `rows.size < PAGE`. A pass that stopped on the examine budget
        // legitimately returns few rows — or none — and reading that as completion would end the
        // sweep with garbage still on disk.
        if (page.exhausted) {
            finished = true
            store.storeOrphanedFileSweepCursor(ObjectStore.SWEEP_COMPLETE)
        } else {
            cursor = page.nextCursor
            store.storeOrphanedFileSweepCursor(page.nextCursor)
        }

        var deleted = 0
        page.rows.forEach { entry ->
            val path = pathOf(entry) ?: return@forEach
            if (!isOrphaned(path)) return@forEach
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = File.ID,
                keyValue = path,
            )
            // ⚠⚠ Tombstone FIRST, so the delete reaches every device and the relay at one HLC — and so
            // that, for a folder, the reaper below finds that HLC as its T. An emitter that refuses
            // (realm locked, sync paused) keeps the row: deleting it silently would be the per-device
            // split this exists to prevent. Kept means a leak until the next re-arm, never a loss.
            // ⚠ A device that belongs to no realm (`residentOwnerId == null`) has no peers to tell, so
            // there the delete stays local.
            val emitter = tombstoneEmitterHolder?.emitter?.takeIf { it.residentOwnerId != null }
            if (emitter != null && emitter.emit(dataId, File.ID) == null) {
                println("OrphanedFileSweeper: tombstone refused for an orphan; kept until the next pass")
                return@forEach
            }
            // Delete through the ordinary path so every observer runs: the relation maintainer
            // unlinks it, `FileHistoryPolicyObserver` drops its history, and — for a folder —
            // [DeletedFolderSubtreeReaper] takes its children and its FILES array with it.
            executionContext.deleteData(dataId)
            deleted++
        }
        return Pass(scanned = page.rows.size, deleted = deleted, done = page.exhausted)
    }

    /**
     * Whether a missing ancestor may be read as garbage now. On a runtime without sync
     * ([tombstoneEmitterHolder] null), or one that belongs to no realm, nothing is pulled into the
     * store, so yes. Otherwise — a realm member, or an emitter not attached yet — only once the
     * store has pulled its history ([ObjectStore.hasPulledItsHistory]); ⚠ unknown is not complete.
     */
    private fun mayJudgeAbsence(store: ObjectStore): Boolean {
        val holder = tombstoneEmitterHolder ?: return true
        return try {
            // ⚠⚠ "This process belongs to no realm" is NOT "nothing is pulled into
            // this store". On iOS the store is the shared replica and the owner is this app's own,
            // null until it adopts Control's identity, while Control may be re-pulling that very
            // store. So the no-realm shortcut holds only for a store that has never pulled at all.
            val emitter = holder.emitter
            if (emitter != null && emitter.residentOwnerId == null && store.hasNeverPulled()) return true
            historyPulled?.invoke() ?: store.hasPulledItsHistory()
        } catch (t: Throwable) {
            println("OrphanedFileSweeper: the store's pull markers could not be read (${t.message}); not sweeping")
            false
        }
    }

    /**
     * ⚠ The scheduler's answer ([org.tenshu.sync.proxy.SyncScheduler.storeHistoryPulled]),
     * set where the sweep is registered. It knows what the store's rows cannot: whether this process
     * would still walk a backfill floor left above zero (a relay that lost the merged lane never
     * advances it, so read alone it would hold the sweep off for ever). Null: the store's own,
     * stricter rule.
     */
    var historyPulled: (() -> Boolean)? = null

    private var loggedWaitingForHistory = false

    /** Not a refusal: the sweep resumes, from where it was, once the history has been pulled. Said once. */
    private fun waitForHistory(): Pass? {
        if (!loggedWaitingForHistory) {
            loggedWaitingForHistory = true
            println("OrphanedFileSweeper: waiting — this store has not pulled its realm's history yet, so a missing folder is not evidence")
        }
        return null
    }

    /**
     * Explain a refusal **once**, then stay quiet. Declining every pass is a legitimate state (a
     * store without by-type enumeration), but an *unexplained* one is indistinguishable from a
     * sweep that nothing ever calls.
     */
    private fun refuse(reason: String): Pass? {
        if (!loggedRefusal) {
            loggedRefusal = true
            println("OrphanedFileSweeper: refusing to sweep — $reason")
        }
        return null
    }

    /**
     * A row's own path. ⚠ Read from the **scanned entry's fields**, not by re-reading the row: the
     * scan deliberately does not enter residency, and a `getData` per candidate would pull the
     * store back into memory one page at a time.
     */
    private fun pathOf(entry: ObjectStore.DbEntry): String? =
        (entry.fields.firstOrNull() as? ObjectStore.DbValue.StringValue)
            ?.value
            ?.takeIf { it.startsWith("/") }

    /**
     * Whether any ancestor of [path] is missing. Root is never orphaned.
     *
     * ⚠ Ancestors only — a row is never judged by its own presence, which is trivially true here.
     */
    private fun isOrphaned(path: String): Boolean {
        if (path == ROOT) return false
        var parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        while (parent.isNotEmpty()) {
            if (!rowExists(parent)) return true
            parent = parent.substringBeforeLast('/', missingDelimiterValue = "")
        }
        // Reached `/` without a gap: every ancestor exists.
        return !rowExists(ROOT)
    }

    private fun rowExists(path: String): Boolean = executionContext.database.getData(
        DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = path,
        )
    ) != null

    companion object {
        private const val ROOT = "/"

        /**
         * Matches collected per pass. **Aligned with `RetiredBlockSweeper.PAGE`**, which sweeps the
         * same store on the same tick under the same constraint; a smaller page only multiplies the
         * ticks a cleanup takes, for no safety gained.
         *
         * ⚠ Deletions fan out to every observer (relation maintainer, history policy, the subtree
         * reaper), so this is the budget that actually bounds a pass's cost. It stays a few hundred
         * rather than unbounded, for the same reason the block sweep's does: never park the tick.
         */
        const val PAGE = 250

        /**
         * Rows *looked at* per pass, also aligned with the block sweep.
         *
         * ⚠ **Moot on `SqliteObjectStore` and the iOS shared replica** — their `type` index means a
         * scan never reads a non-matching row, so [PAGE] alone paces the sweep. This budget exists
         * for a store whose only index is the hash, where filtering costs a read per row examined
         * (`core-local-store-orphan-gc.md` §9.6).
         */
        const val EXAMINE_PAGE = 2_000
    }
}
