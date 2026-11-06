package app.tenshu.ingest.backup

import kotlinx.coroutines.flow.Flow

/**
 * Everything the backup drain needs from a file store — **six methods, and nothing else**
 * (`photos-app.md` §19).
 *
 * ## ⚠⚠ Why this exists
 *
 * Drive's whole `FilesRepository` is forty-odd methods for listing, previewing, sharing, moving,
 * trashing and pinning, of which the drain calls five. A neutral module cannot depend on an app's
 * entire repository interface, so taking it would keep the drain inside `:drive-shared-logic`, and
 * Photos could *manage* backup sources but never *add* one (§14.4).
 *
 * Narrowing is the whole move. Drive's `FilesRepository` implements this and loses nothing; the
 * drain does not know that a file store can do anything else.
 *
 * ## ⚠ It is a **data plane**, not a repository
 *
 * Every method here is something the drain does to *bytes it is uploading*. Nothing reads a
 * listing, nothing resolves a path for display, nothing writes user-visible state. A method that
 * does not serve one upload does not belong here — that rule is what stops this widening back into
 * the interface it was extracted from.
 */
interface BackupDataPlane {

    /**
     * Run [block] — a whole source's drain — as one registered `BackgroundTask`, so every upload's
     * saves join the task's sync lane and ride the relay as `DeltaBatchPayload` blocks rather than
     * per-save `SyncBlock`s (TS-5, `core-background-processing.md` §10.6).
     *
     * ⚠ Default = run bare. A runtime with no `TaskService` is byte-identical per-save sync, and a
     * fake that models nothing still works.
     */
    suspend fun <T> withBulkUploadTask(kind: String, block: suspend () -> T): T = block()

    /**
     * Ensure `/Backup/<device>/<bucket>` and its ancestors exist.
     *
     * ⚠ Called **once per bucket per drain**, not per file — creating a folder chain per upload is
     * a round trip per file for an answer that cannot change mid-pass.
     */
    suspend fun ensureBackupDestination(parentPath: String) {}

    /**
     * The whole-file plaintext hash of the bytes behind [uriString], or `null` when they could not
     * be read — the ingest-time duplicate question (`photos-app.md` §16).
     *
     * ⚠⚠ `null` means *"could not read"*, never *"no match"*: a caller must fall back to
     * uploading, because treating an unreadable candidate as a duplicate drops a photograph.
     */
    suspend fun contentHashOf(uriString: String): String?

    /** Upload one file headlessly. ⚠ No conflict dialog: there is no user present to answer one. */
    suspend fun uploadForBackup(
        parentPath: String,
        fileName: String,
        uriString: String,
    ): BackupUploadOutcome

    /**
     * The realm's storage figures, or `null` until the proxy has derived them.
     *
     * ⚠ A flow the drain subscribes to **once** and then reads from a cache — `first()` on it per
     * file would be a genuinely expensive read repeated per upload.
     */
    fun storageStatus(): Flow<StorageStatusModel?>

    /**
     * Let the bytes this device has already handed over cross the **metered** content gate once —
     * the user confirmed "use mobile data this once" on a *Back up now*
     * (`drive-synced-folders-phase2.md` §4.1a).
     *
     * ⚠⚠ Why the drain needs it at all: Wi-Fi-only is enforced twice. The drain's own gate decides
     * whether to hand a file over; the proxy's content plane decides whether to *upload* it, and on
     * mobile data it journals the bytes and waits for Wi-Fi. Lifting only the first backs up
     * nothing. Drive maps this to the proxy's one-shot "sync now over cellular".
     *
     * ⚠ Default = nothing: a store with no metered gate has nothing to lift.
     */
    suspend fun uploadOverMeteredOnce() {}
}

/**
 * What one headless upload did.
 *
 * ⚠ `Skipped` is not a failure and not a success: the file is already accounted for — at its
 * destination, or by content (`photos-app.md` §16) — so the cursor advances past it and it counts
 * toward neither
 * `uploaded` nor `failed`. Folding it into either makes a drain's own figures lie.
 */
sealed interface BackupUploadOutcome {

    data object Uploaded : BackupUploadOutcome

    data object Skipped : BackupUploadOutcome

    /**
     * ⚠⚠ [reason] is why this is a class and not an enum constant. Backup is the one upload path
     * with **no user standing over it**, so a discarded cause would leave nothing at all — no
     * error event, no log, no UI — and total failure would render as "Backed up 0 files"
     * (`drive-synced-folders.md` §8c).
     *
     * ⚠ Best-effort and developer-shaped: the error's message, else its type id. Shown to the user
     * anyway, because a bad sentence they can quote beats a good one that says nothing.
     */
    data class Failed(val reason: String) : BackupUploadOutcome
}

/**
 * The realm's storage, as the backup gate reads it.
 *
 * ⚠ [hasStorage] `false` means *unbound* — no plan, no limit known — which is different from a
 * limit of zero. The gate treats the two differently and must keep being able to.
 */
data class StorageStatusModel(
    val hasStorage: Boolean,
    val usedBytes: Long,
    val limitBytes: Long,
    /**
     * The **relay** refused a content write with `507` (over quota) — `SyncHealthStatus`'s
     * `CONNECTIVITY_STORAGE_FULL`, joined in by the repository that builds this model.
     *
     * ⚠⚠ Carried here, next to the figures, because it answers the same question they do and the
     * two can disagree. See [isFull].
     */
    val relayRejected: Boolean = false,
) {
    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0L)

    /**
     * Bound, and bytes cannot land — the "out of storage" case, from **either** of the two
     * authorities on it.
     *
     * ⚠⚠ **`remainingBytes <= 0` alone is not the question anyone is actually asking**, and
     * treating it as such lets a device upload nothing for hours while every UI says it is
     * working. Two cases it answers wrongly:
     *
     *  - **A sliver.** E.g. `used = 5,368,708,284`, `limit = 5,368,709,120` — **836 bytes free** on
     *    the 5 GiB tier. `remainingBytes > 0`, so the figures alone keep the banner on "Uploading 297
     *    of 606" and auto-backup enqueuing, while the relay refuses all 606 (chunks are 2 MiB;
     *    anything over 836 bytes is over quota). Raising this to a fixed headroom constant would
     * only move the boundary —
     *    the smallest write that must fit is not knowable from these two numbers.
     *  - **Another device.** These figures re-fetch on a 60 s backstop, so bytes a *second*
     *    device of the same owner has just written are invisible here for up to that long. The
     *    owner is over quota and this model does not know it yet. ⚠ Shortening that interval does
     *    not close the gap — it only narrows it; the relay's answer is what actually closes it.
     *
     * [relayRejected] closes both: the relay is the only authority on whether a write fits, and it
     * has already answered. ⚠ It is therefore possible (briefly, until the next pull) to report
     * "full" while the figures alongside still read e.g. 3 GB of 5 GB — the *headline* is right and
     * the numbers are merely stale, which is the better way round.
     */
    val isFull: Boolean get() = hasStorage && (relayRejected || remainingBytes <= 0L)
}
