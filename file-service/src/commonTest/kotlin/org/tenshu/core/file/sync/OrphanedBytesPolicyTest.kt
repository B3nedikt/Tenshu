package org.tenshu.core.file.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every test here is a way to delete local plaintext that was not orphaned. A wrong DROP is a re-download at best and a lost file at worst; a wrong WAIT is
 * disk. So every uncertain input must come out WAIT or FORGET.
 */
class OrphanedBytesPolicyTest {

    private val now = 1_700_000_000_000L
    private val settle = OrphanedBytesPolicy.DEFAULT_SETTLE_MS

    private fun candidate(path: String = "/photo.jpg", ageMs: Long = settle * 2) =
        OrphanedBytesCandidates.Entry(path, now - ageMs)

    private fun verdict(
        candidate: OrphanedBytesCandidates.Entry = candidate(),
        rowExists: Boolean = false,
        bytesPresent: Boolean = true,
        bytesStillOwed: Boolean = false,
        mtime: Long? = now - settle * 2,
    ) = OrphanedBytesPolicy.verdictFor(
        candidate = candidate,
        rowExists = rowExists,
        bytesPresent = bytesPresent,
        bytesStillOwed = bytesStillOwed,
        bytesLastModifiedMs = mtime,
        nowMs = now,
    )

    @Test
    fun anOldOrphanWithNoRow_isDropped() {
        assertEquals(OrphanedBytesPolicy.Verdict.DROP, verdict())
    }

    /**
     * ⚠⚠ The move case, and the reason the row is re-checked at decision time rather than trusted
     * from record time: a peer receives the tombstone for the old path and the create for the new
     * one in either order. If the path came back, it is not an orphan.
     */
    @Test
    fun aPathThatCameBack_isForgottenNotDropped() {
        assertEquals(OrphanedBytesPolicy.Verdict.FORGET, verdict(rowExists = true))
    }

    /**
     * ⚠⚠ A peer moved a file whose upload is still parked here. Ingest never moves bytes, so
     * they sit at the old path with no row — every other condition for a DROP holds, and they are
     * the only copy the relay has never received. Old enough or not, they wait for the intent.
     */
    @Test
    fun bytesAnUploadStillOwes_wait_howeverOld() {
        assertEquals(OrphanedBytesPolicy.Verdict.WAIT, verdict(bytesStillOwed = true))
        assertEquals(
            OrphanedBytesPolicy.Verdict.WAIT,
            verdict(candidate = candidate(ageMs = settle * 100), bytesStillOwed = true, mtime = now - settle * 100),
        )
    }

    @Test
    fun noBytes_isForgotten() {
        assertEquals(OrphanedBytesPolicy.Verdict.FORGET, verdict(bytesPresent = false))
    }

    /**
     * ⚠⚠ The upload window. `UploadFileImplementation` writes a file's bytes before saving its row,
     * so a user's own in-flight upload is indistinguishable from a stranded file except by age.
     * Freshly written bytes must never be dropped.
     */
    @Test
    fun freshlyWrittenBytes_wait() {
        assertEquals(OrphanedBytesPolicy.Verdict.WAIT, verdict(mtime = now - 1_000))
    }

    /**
     * ⚠⚠ Absence of an answer is not permission. A platform whose `FileDatabase` cannot report an
     * mtime must not thereby license every delete.
     */
    @Test
    fun anUnknownModificationTime_waits() {
        assertEquals(OrphanedBytesPolicy.Verdict.WAIT, verdict(mtime = null))
    }

    /**
     * ⚠ Old bytes under a *fresh* candidate still wait: a file restored from a backup has an old
     * mtime and is not an orphan, and the candidate's own stamp is what says how long we have
     * actually believed this path to be row-less.
     */
    @Test
    fun oldBytesUnderAFreshCandidate_wait() {
        assertEquals(
            OrphanedBytesPolicy.Verdict.WAIT,
            verdict(candidate = candidate(ageMs = 0), mtime = now - settle * 10),
        )
    }

    /**
     * ⚠⚠ The line that protects 9,195 of 10,141 files on a real device. These have no `File` row by
     * design; reasoning from "no row" alone would delete every thumbnail, preview, cached map region
     * and inference model on the disk.
     */
    @Test
    fun reservedNamespaces_areNeverDropped() {
        for (path in listOf(
            "/.thumbnails/content-1/LIST",
            "/.previews/content-1",
            "/.shared/realm/x.jpg",
            "/.health/streams/workout-1",
        )) {
            assertEquals(
                OrphanedBytesPolicy.Verdict.FORGET,
                verdict(candidate = candidate(path = path)),
                "$path is a reserved namespace and must never be dropped",
            )
        }
    }

    /** The boundary itself, pinned so a refactor cannot drift it by one. */
    @Test
    fun exactlyAtTheSettleBoundary_drops() {
        assertEquals(
            OrphanedBytesPolicy.Verdict.DROP,
            verdict(candidate = candidate(ageMs = settle), mtime = now - settle),
        )
    }
}
