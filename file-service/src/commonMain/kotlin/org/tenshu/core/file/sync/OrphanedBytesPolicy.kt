package org.tenshu.core.file.sync

/**
 * **When is it safe to drop plaintext this device no longer has a row for?**
 *
 * Decides about the local copy, never the relay's. Pure and total — every branch reachable from a
 * unit test, because deleting a user's bytes is not a decision that can be walked back.
 *
 * ⚠⚠ **The asymmetry that shapes every rule below.** Losing local plaintext is *usually* recoverable
 * — the content is on the relay and `OnDemand` re-fetches it — and losing the last copy is not. So a
 * wrong "drop" here is normally a wasted download and occasionally a lost file, while a wrong "keep"
 * is only disk. Every uncertain answer is therefore a keep.
 */
object OrphanedBytesPolicy {

    /**
     * How long a candidate's bytes must go untouched before they may be dropped. **One hour.**
     *
     * ⚠⚠ **This is a bound on a LOCAL operation, not a settling period — which is why it is a timer
     * at all.** `drive-upload-stop-and-reclamation.md` §4.16 argues at length that a timer cannot
     * stand in for distributed convergence, and that argument does not apply here: what this bounds
     * is `UploadFileImplementation` writing a file's bytes *before* saving its row. In that window a user's own upload looks exactly like a stranded file, and only elapsed
     * local time distinguishes them. ⭐ It also expires into the **safe** direction: older bytes are
     * more certainly orphaned, never less.
     *
     * ⚠ An hour is enormous next to that window (seconds, or a minute for a very large file) and
     * costs nothing, since the bytes are already written off.
     */
    const val DEFAULT_SETTLE_MS: Long = 60L * 60 * 1000

    /**
     * Reserved namespaces in the byte store: thumbnails, previews, shared-realm content, health
     * streams, and anything else a subsystem keys under a leading dot.
     *
     * ⚠⚠ **The single most dangerous line in this file.** These paths have no `File` row *by design*
     * — on one real device 9,195 of 10,141 stored files were `/.thumbnails/` and `/.previews/` — so
     * a sweep that reasoned only from "no row" would delete every thumbnail, cached map region and
     * inference model on the device. `FileBytesPropertyWriter.thumbnailPath` states the convention
     * these rely on: a reserved `/.…` prefix cannot collide with a personal `"/…"` path.
     *
     * ⚠ Candidates come from `File` row ids, which are personal paths by construction, so this
     * should never fire. It is here because "should never" is not a guarantee, and the cost of being
     * wrong is somebody's photo library.
     */
    fun isReservedNamespace(path: String): Boolean = path.startsWith("/.")

    /** What to do with one candidate. */
    enum class Verdict {
        /** Drop the bytes and forget the candidate. */
        DROP,

        /** Forget the candidate without touching anything — there is nothing to drop. */
        FORGET,

        /** Keep the candidate and ask again later. */
        WAIT,
    }

    /**
     * @param rowExists whether a `File` row lives at this path *now*. ⚠ A move's create, or a
     *   re-creation at the same path, may land after the tombstone that made this a candidate —
     *   lane order gives no guarantee — so this is asked at decision time, never at record time.
     * @param bytesPresent whether the store still holds plaintext there.
     * @param bytesStillOwed whether a journalled upload intent still reads its bytes from this path.
     *   ⚠⚠ After a peer moves a file whose upload is parked here, ingest leaves the bytes at the old
     *   path — no row names it, which is every other condition for a DROP — and they are the only copy
     *   of content the relay has never received. Not an orphan until the intent is gone.
     * @param bytesLastModifiedMs when those bytes were last written, or null if unknown. ⚠ Unknown
     *   is treated as *too recent*: a platform that cannot answer must not thereby license a delete.
     */
    fun verdictFor(
        candidate: OrphanedBytesCandidates.Entry,
        rowExists: Boolean,
        bytesPresent: Boolean,
        bytesStillOwed: Boolean,
        bytesLastModifiedMs: Long?,
        nowMs: Long,
        settleMs: Long = DEFAULT_SETTLE_MS,
    ): Verdict = when {
        // The path came back — a re-creation, or the far side of a move that landed here after its
        // own tombstone. Whatever is at that path now owns those bytes.
        rowExists -> Verdict.FORGET

        // Nothing to collect: the local delete path already dropped them, or they were never here.
        !bytesPresent -> Verdict.FORGET

        // Belt and braces; see isReservedNamespace.
        isReservedNamespace(candidate.path) -> Verdict.FORGET

        // ⚠⚠ Owed to the relay, so not an orphan however old — and WAIT, not FORGET: once the intent
        // drains these bytes ARE stranded, and this candidate is the only record that says so.
        bytesStillOwed -> Verdict.WAIT

        // ⚠ Unknown mtime is not "old enough". Same shape as an unreadable roster or an incomplete
        // walk: the absence of an answer is never permission.
        bytesLastModifiedMs == null -> Verdict.WAIT

        nowMs - bytesLastModifiedMs < settleMs -> Verdict.WAIT

        // ⚠ The candidate's own stamp is checked too, so a freshly recorded path cannot be dropped
        // on the strength of bytes that merely happen to be old — a file restored from a backup, say.
        nowMs - candidate.removedAtMs < settleMs -> Verdict.WAIT

        else -> Verdict.DROP
    }
}
