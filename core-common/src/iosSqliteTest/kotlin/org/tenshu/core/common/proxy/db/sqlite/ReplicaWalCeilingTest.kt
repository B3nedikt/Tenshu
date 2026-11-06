@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import platform.posix.chmod
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The WAL ceiling and the measured budget (§4.2.1, S21), and the handle registry and gate as far
 * as the store core uses them (§2.8). The limits here are a few dozen pages so a test reaches
 * them in a handful of rows; the arithmetic is the production one.
 */
class ReplicaWalCeilingTest {

    // C = 8, G = 8, H = 8 + 4·1 = 12, δ = 20 frames, Wmax = 68 pages, journal_size_limit = 17.
    // (68 is the least Wmax these give: a reset log must leave a migration chunk its largest object.)
    private val small = ReplicaLimits(
        wmaxPages = 68,
        cachePages = 8,
        statementPages = 8,
        rowMaxBytes = 8_000,
        ceilingWaitMs = 150,
    )

    private fun rewinds(process: ReplicaProcess): Long {
        val connection = rawConnection(process.databasePath)
        try {
            return connection.queryLong("SELECT coalesce((SELECT v FROM meta WHERE k = 'wal_rewind'), 0)")
        } finally {
            connection.close()
        }
    }

    /** A sibling holding a read mark: a read transaction with a statement stepped. */
    private fun heldReader(process: ReplicaProcess): SqliteConnection =
        rawConnection(process.databasePath).also {
            it.exec("BEGIN")
            it.queryLong("SELECT count(*) FROM objects")
        }

    // ── the measured budget ──

    @Test
    fun aTransactionsPagesReachTheWalBeforeItCommitsBecauseTheCacheIsCapped() {
        val process = ReplicaProcess(tempReplica(), small.copy(wmaxPages = 400))
        val store = process.store()
        store.databaseLock.withLock {
            val before = store.walBytes
            repeat(60) { store.storeObject(row(it.toLong(), payloadBytes = 3_000)) }
            val spilled = (store.walBytes - before) / ReplicaLimits.WAL_FRAME_BYTES
            assertTrue(spilled >= 60 - 2 * small.cachePages, "frames in the -wal before COMMIT: $spilled")
        }
        assertEquals(0, store.ceilingBreaches)
    }

    @Test
    fun theStatementThatWouldPassWmaxIsRefusedBeforeItRunsAndNothingOfTheTransactionStays() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        store.storeObject(row(-1))
        var stored = 0
        val refused = assertFailsWith<TransactionBudgetExceeded> {
            store.databaseLock.withLock {
                repeat(1_000) {
                    store.storeObject(row(it.toLong(), payloadBytes = 3_000))
                    stored++
                }
            }
        }
        assertTrue(stored in 5..60, "a transaction of small rows gets real room: $stored")
        assertFalse(small.fits(refused.walBytes, refused.headroomPages))
        assertTrue(store.walBytes <= small.wmaxBytes, "refused BEFORE the -wal passed Wmax: ${store.walBytes}")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)

        // Rolled back whole, and the frames it spilled do not wedge the next process.
        val after = ReplicaProcess(process.directory, small).store()
        assertEquals(row(-1), after.getObject(-1))
        assertNull(after.getObject(0))
        after.storeObject(row(5))
        assertEquals(row(5), after.getObject(5))
    }

    // ── the ceiling (writer rule 1) ──

    @Test
    fun withNoReaderTheLogIsResetLongBeforeTheCeiling() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        repeat(300) { store.storeObject(row(it.toLong(), payloadBytes = 2_000)) }
        assertEquals(0, store.ceilingBreaches)
        assertTrue(store.walBytes <= small.beginCeilingBytes, "the -wal stayed under the ceiling: ${store.walBytes}")
        assertTrue(rewinds(process) > 0, "the opportunistic checkpoint rewound the log")
        assertEquals(0, store.verifiedResets, "the ceiling checkpoint was never needed")
        repeat(300) { assertEquals(row(it.toLong(), payloadBytes = 2_000), store.getObject(it.toLong())) }
        assertEquals(1, process.registry.handles().size, "every checkpoint handle was released")
    }

    @Test
    fun pastTheCeilingAWriteIsDeferredUntilTheResetIsVerified() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        store.storeObject(row(0))
        val reader = heldReader(process)
        try {
            // The reader's mark stops every PASSIVE checkpoint short, so the log only grows.
            var written = 1L
            while (store.walBytes <= small.beginCeilingBytes) {
                store.storeObject(row(written++, payloadBytes = 2_000))
                assertTrue(written < 500, "the -wal never reached the ceiling")
            }
            val rewindsBefore = rewinds(process)
            val waited = TimeSource.Monotonic.markNow()
            assertFailsWith<WalResetPending> { store.storeObject(row(9_999)) }
            assertTrue(waited.elapsedNow().inWholeMilliseconds >= small.ceilingWaitMs, "it waited B_ckpt for the reader")
            // Retryable: no fence, nothing written, no rewind claimed.
            assertNull(store.fencedBy)
            assertEquals(0, store.verifiedResets)
            assertEquals(rewindsBefore, rewinds(process))
            // ⚠ Every outermost access is a write transaction, so a read is deferred the same way.
            assertFailsWith<WalResetPending> { store.getObject(0) }
            assertEquals(1, process.registry.handles().size, "the checkpoint handles were released")
        } finally {
            reader.exec("ROLLBACK")
            reader.close()
        }
        // The reader has ended: the same write now checkpoints, rewinds, verifies, and lands.
        store.storeObject(row(9_999))
        assertEquals(1, store.verifiedResets)
        assertTrue(store.walBytes <= small.beginCeilingBytes, "reset: ${store.walBytes}")
        assertEquals(row(9_999), store.getObject(9_999))
        assertEquals(row(0), store.getObject(0))
        assertEquals(0, store.ceilingBreaches)
    }

    @Test
    fun aCeilingCheckpointThatCannotLookForTheMarkerIsSkippedLikeAnyOtherAndTheWriteStaysDeferred() {
        val files = RecordingFiles()
        val directory = tempReplica()
        val process = ReplicaProcess(directory, small, files = files)
        val store = process.store()
        store.storeObject(row(0))
        val reader = heldReader(process)
        try {
            var written = 1L
            while (store.walBytes <= small.beginCeilingBytes) {
                store.storeObject(row(written++, payloadBytes = 2_000))
                assertTrue(written < 500, "the -wal never reached the ceiling")
            }
            // A change in the directory, so its time is not yet believed and every look for the
            // marker goes by path. The access's own look succeeds; the checkpoint's, at its
            // step 0, fails.
            PosixReplicaFiles.createExclusive("$directory/just-now")
            files.failLookupOf = ReplicaCorruptMarker.NAME
            files.lookupsBeforeFailure = 1
            // ⚠ "Skipped", so the answer is the ceiling's own: the lookup failing inside the
            // checkpoint used to escape it as a different refusal.
            assertFailsWith<WalResetPending> { store.storeObject(row(9_999)) }
            assertTrue(files.lookupFailures >= 1, "the checkpoint's look for the marker was failed")
            assertNull(store.fencedBy)
            assertEquals(1, process.registry.handles().size, "no checkpoint handle was left")
        } finally {
            files.failLookupOf = null
            reader.exec("ROLLBACK")
            reader.close()
        }
        store.storeObject(row(9_999))
        assertEquals(row(9_999), store.getObject(9_999))
    }

    @Test
    fun aRollbackThatFailsAtTheCeilingFencesTheHandleInsteadOfCheckpointingOverAnOpenTransaction() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        store.storeObject(row(0))
        val reader = heldReader(process)
        try {
            var written = 1L
            while (store.walBytes <= small.beginCeilingBytes) {
                store.storeObject(row(written++, payloadBytes = 2_000))
                assertTrue(written < 500, "the -wal never reached the ceiling")
            }
            // The ROLLBACK that precedes the ceiling checkpoint fails, once.
            var faults = 0
            store.rollbackFault = { if (faults++ == 0) throw SqliteException(SQLITE_IOERR, "injected rollback failure") }

            // An access failure, not WalResetPending: that would be a retry on a connection whose
            // transaction may still be open.
            assertFailsWith<ReplicaAccessFailed> { store.getObject(0) }
            assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
            assertEquals(0, store.verifiedResets)
            assertTrue(process.registry.handles().isEmpty(), "the fenced handle was released, its connection closed")
        } finally {
            reader.exec("ROLLBACK")
            reader.close()
        }
        // Nothing of this process still holds the write lock: a sibling writes at once.
        val sibling = ReplicaProcess(process.directory, small).store()
        sibling.storeObject(row(9_999))
        assertEquals(row(9_999), sibling.getObject(9_999))
    }

    @Test
    fun noWalAnyProcessCanMeetExceedsWmaxPlusDelta() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        val reader = heldReader(process)
        var largest = 0L
        try {
            for (i in 0 until 200L) {
                try {
                    // The largest rows ROW_MAX allows, written over each other: the design's
                    // promise is that every single row operation fits a transaction that began.
                    store.storeObject(row(i % 7, payloadBytes = 7_000))
                } catch (_: WalResetPending) {
                    // Deferred, as it must be while the reader holds its mark.
                }
                largest = maxOf(largest, store.walBytes)
            }
        } finally {
            reader.exec("ROLLBACK")
            reader.close()
        }
        assertNull(store.fencedBy)
        assertEquals(0, store.ceilingBreaches)
        assertTrue(largest <= small.wmaxBytes, "largest -wal seen: $largest of ${small.wmaxBytes}")
    }

    @Test
    fun withTheCorruptMarkerPresentNoCheckpointAndNoRewindStarts() {
        val process = ReplicaProcess(tempReplica(), small)
        val store = process.store()
        val databaseBefore = bytesOf(process.databasePath)
        store.databaseLock.withLock {
            // Enough for the commit to leave the -wal past Wmax/4, the opportunistic trigger.
            repeat(25) { store.storeObject(row(it.toLong(), payloadBytes = 2_000)) }
            // A sibling marks the store after this access passed its own check.
            ReplicaCorruptMarker(PosixReplicaFiles, process.directory).write("time=0\napp=sibling\nerror=test\n")
        }
        val walAfterCommit = store.walBytes
        assertTrue(walAfterCommit > small.journalSizeLimitBytes, "the trigger was met: $walAfterCommit")
        assertEquals(ReplicaFence.CORRUPT, store.fencedBy, "the checkpoint path found the marker and fenced")
        assertEquals(0L, rewinds(process), "no rewind write ran")
        assertEquals(walAfterCommit, store.walBytes, "the log was not restarted")
        // A checkpoint copies frames into objects.db: untouched, so none even began (step 0).
        assertContentEquals(databaseBefore, bytesOf(process.databasePath), "no frame was backfilled")
    }

    // ── waiting on a sibling ──

    @Test
    fun anAccessWaitsOutASiblingsWriteLockInSlicesAndThenRuns() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val sibling = rawConnection(process.databasePath)
        sibling.exec("BEGIN IMMEDIATE")
        val waited = TimeSource.Monotonic.markNow()
        runBlocking {
            val write = async(Dispatchers.Default) { store.storeObject(row(1)) }
            usleep(300_000u)
            assertFalse(write.isCompleted, "the access is still waiting on the sibling")
            sibling.exec("COMMIT")
            write.await()
        }
        sibling.close()
        assertTrue(waited.elapsedNow().inWholeMilliseconds >= 300)
        assertEquals(row(1), store.getObject(1))
        assertNull(store.fencedBy)
    }

    // ── the registry and the gate (§2.8) ──

    @Test
    fun oneRuntimeHandlePerPathAndARebuildAfterAFenceLeavesExactlyOne() {
        val process = ReplicaProcess(tempReplica(), ReplicaLimits(rowMaxBytes = 10_000))
        val store = process.store()
        assertFailsWith<IllegalStateException> { process.open() }
        assertEquals(1, process.registry.handles().size, "the refused open left nothing registered")

        assertFailsWith<RowTooLarge> { store.storeObject(row(1, payloadBytes = 20_000)) }
        assertTrue(process.registry.handles().isEmpty(), "the fence released the handle")
        val rebuilt = process.store()
        assertEquals(listOf(ReplicaHandleKind.RUNTIME), process.registry.handles().map { it.kind })
        rebuilt.storeObject(row(2))
        assertEquals(row(2), rebuilt.getObject(2))
    }

    // ── a probe that fails is not a small -wal, an absent marker or a reset ──

    @Test
    fun aWalThatCannotBeOpenedForMeasuringRefusesTheAccessInsteadOfReadingAsEmpty() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        val wal = process.databasePath + "-wal"
        assertTrue(fileExists(wal))
        // SQLite already holds its own descriptor; only the store's measuring open is refused.
        assertEquals(0, chmod(wal, 0.convert()))
        try {
            assertFailsWith<StoreUnavailable> { store.storeObject(row(1)) }
            assertFailsWith<StoreUnavailable> { store.getObject(1) }
        } finally {
            chmod(wal, 0x180.convert() /* 0600 */)
        }
        assertNull(store.fencedBy, "a retry, not a fence: nothing had been started")
        assertNull(store.getObject(1), "the refused write wrote nothing")
        store.storeObject(row(1))
        assertEquals(row(1), store.getObject(1))
    }

    /** Closes the `-wal` descriptor behind the store while [broken], so the real `fstat` fails. */
    private class WalStatFails(private val real: ReplicaFiles) : ReplicaFiles by real {
        var broken = false
        private val walDescriptors = HashSet<Int>()

        override fun openReadOnly(path: String): Int =
            real.openReadOnly(path).also { if (path.endsWith("-wal")) walDescriptors += it }

        // No declared return type: it is whatever the interface says.
        override fun stat(descriptor: Int) = run {
            if (broken && descriptor in walDescriptors) real.closeDescriptor(descriptor)
            real.stat(descriptor)
        }
    }

    @Test
    fun aWalWhoseSizeCannotBeReadRefusesTheAccessInsteadOfReadingAsEmpty() {
        val files = WalStatFails(PosixReplicaFiles)
        val process = ReplicaProcess(tempReplica(), files = files)
        val store = process.store()
        store.storeObject(row(1))

        files.broken = true
        assertFailsWith<StoreUnavailable> { store.storeObject(row(2)) }
        assertFailsWith<StoreUnavailable> { store.getObject(1) }
        files.broken = false

        assertNull(store.fencedBy)
        assertNull(store.getObject(2), "the refused write wrote nothing")
        store.storeObject(row(2))
        assertEquals(row(2), store.getObject(2))
    }

    @Test
    fun aDirectoryThatCannotBeSearchedIsNotReadAsNoCorruptionMarker() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        // The marker's lookup by path and the -wal's measuring open both fail with EACCES now.
        assertEquals(0, chmod(process.directory, 0.convert()))
        try {
            assertFailsWith<StoreUnavailable> { store.getObject(1) }
        } finally {
            chmod(process.directory, 0x1C0.convert() /* 0700 */)
        }
        assertNull(store.fencedBy)
        assertEquals(row(1), store.getObject(1))
    }

    @Test
    fun anOpenThatCannotLookForTheCorruptionMarkerIsARetryNotAFreshOrHealthyStore() {
        val directory = tempReplica()
        ReplicaProcess(directory).store().close()
        assertEquals(0, chmod(directory, 0.convert()))
        try {
            assertFailsWith<StoreUnavailable> { ReplicaProcess(directory).open() }
        } finally {
            chmod(directory, 0x1C0.convert() /* 0700 */)
        }
        assertIs<ReplicaOpenResult.Ready>(ReplicaProcess(directory).open())
    }

    @Test
    fun quiesceClosesTheRuntimeConnectionKeepsItsHandleAndTheNextAccessReopensIt() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        val handle = process.registry.handles().single()
        assertTrue(handle.hasConnection)

        repeat(2) { round ->
            assertTrue(process.registry.gate.closeAndDrain(1_000))
            process.registry.closeAllForQuiesce()
            assertEquals(listOf(handle), process.registry.handles(), "round $round: kept registered")
            assertFalse(handle.hasConnection, "round $round: its connection is closed")
            assertFailsWith<ReplicaGateClosed> { store.getObject(1) }
            assertFalse(handle.hasConnection, "round $round: a refused access opens nothing")

            process.registry.gate.reopen()
            assertEquals(row(1), store.getObject(1), "round $round: reopened through the registry")
            assertTrue(handle.hasConnection)
            store.storeObject(row(10L + round))
        }
        assertNull(store.fencedBy)
        // The reopened connection carries the per-connection settings again: WAL, never a
        // close-time checkpoint.
        assertTrue(fileExists(process.databasePath + "-wal"))
    }

    @Test
    fun quiesceReleasesAJobHandle() {
        val process = ReplicaProcess(tempReplica())
        process.store()
        val job = process.registry.register(ReplicaHandleKind.CHECKPOINT, "test job")
        process.registry.connectionFor(job)
        assertTrue(process.registry.gate.closeAndDrain(1_000))
        process.registry.closeAllForQuiesce()
        assertTrue(job.isReleased)
        assertEquals(listOf(ReplicaHandleKind.RUNTIME), process.registry.handles().map { it.kind })
        assertFailsWith<IllegalStateException> { process.registry.connectionFor(job) }
    }

    @Test
    fun theGateDoesNotDrainWhileAnAccessIsInFlight() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.databaseLock.withLock {
            store.storeObject(row(1))
            assertFalse(process.registry.gate.closeAndDrain(20), "an admitted access is still in its transaction")
        }
        // The access already admitted finished and committed; nothing new is admitted.
        assertTrue(process.registry.gate.closeAndDrain(20))
        assertFailsWith<ReplicaGateClosed> { store.getObject(1) }
        process.registry.gate.reopen()
        assertEquals(row(1), store.getObject(1))
    }

    @Test
    fun aFailedCloseLatchesTheProcessUnusableUntilARetrySucceeds() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        process.registry.closeFault = { throw SqliteException(SQLITE_IOERR, "injected close failure") }
        assertFailsWith<StoreUnavailable> { store.close() }
        assertTrue(process.registry.isUnusable)
        assertEquals(1, process.registry.handles().size, "the handle stays registered: nothing is unreachable")
        // Nothing is opened in this process while latched.
        val job = process.registry.register(ReplicaHandleKind.CHECKPOINT, "test job")
        assertFailsWith<StoreUnavailable> { process.registry.connectionFor(job) }
        process.registry.release(job)
        assertFalse(process.registry.retryUnusable(), "the close still fails")
        assertTrue(process.registry.isUnusable)

        process.registry.closeFault = null
        assertTrue(process.registry.retryUnusable())
        assertFalse(process.registry.isUnusable)
        // ⚠ The failed close belonged to a release, and the retry finished it: the store that was
        // closed stays closed (it does not quietly reopen its connection), and a rebuilt runtime
        // can register.
        assertTrue(process.registry.handles().isEmpty(), "the released handle is gone")
        assertEquals(ReplicaFence.RELEASED, store.fencedBy)
        assertNull(store.getObject(1), "a released store answers absent")
        assertTrue(process.registry.handles().isEmpty(), "and opened nothing")
        assertEquals(row(1), process.store().getObject(1), "a replacement runtime registers and reads")
    }

    @Test
    fun aQuiesceThatClosesAHandleOwedAReleaseFinishesThatRelease() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        process.registry.closeFault = { throw SqliteException(SQLITE_IOERR, "injected close failure") }
        assertFailsWith<StoreUnavailable> { store.close() }
        process.registry.closeFault = null

        // Quiesce reaches the runtime handle through "close the connection, keep the handle".
        assertTrue(process.registry.gate.closeAndDrain(1_000))
        process.registry.closeAllForQuiesce()
        process.registry.gate.reopen()

        assertFalse(process.registry.isUnusable)
        assertTrue(process.registry.handles().isEmpty(), "the release that was owed is finished, not forgotten")
        assertEquals(ReplicaFence.RELEASED, store.fencedBy)
        assertEquals(row(1), process.store().getObject(1), "a replacement runtime registers")
    }

    @Test
    fun aSecondReleaseOfAHandleOwedOneFinishesIt() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        process.registry.closeFault = { throw SqliteException(SQLITE_IOERR, "injected close failure") }
        assertFailsWith<StoreUnavailable> { store.close() }
        process.registry.closeFault = null
        store.close()
        assertFalse(process.registry.isUnusable)
        assertTrue(process.registry.handles().isEmpty())
        assertEquals(ReplicaFence.RELEASED, store.fencedBy)
    }

    @Test
    fun aRetryAfterAFailedQuiesceCloseKeepsTheRuntimeHandleAndReopensIt() {
        // The other half of the same latch: a close for quiesce is not a release, so its retry
        // must NOT release the handle.
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        val handle = process.registry.handles().single()
        process.registry.closeFault = { throw SqliteException(SQLITE_IOERR, "injected close failure") }
        assertTrue(process.registry.gate.closeAndDrain(1_000))
        assertFailsWith<StoreUnavailable> { process.registry.closeAllForQuiesce() }
        process.registry.gate.reopen()
        assertFailsWith<StoreUnavailable> { store.getObject(1) }

        process.registry.closeFault = null
        assertTrue(process.registry.retryUnusable())
        assertEquals(listOf(handle), process.registry.handles())
        assertFalse(handle.isReleased)
        assertNull(store.fencedBy)
        assertEquals(row(1), store.getObject(1))
    }

    @Test
    fun aQuiesceWhereOneCloseFailsStillClosesEveryOtherHandleAndTheRetryFinishesTheJobsRelease() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        val runtime = process.registry.handles().single()
        val failing = process.registry.register(ReplicaHandleKind.CHECKPOINT, "failing job")
        val other = process.registry.register(ReplicaHandleKind.CHECKPOINT, "other job")
        process.registry.connectionFor(failing)
        process.registry.connectionFor(other)
        // Registration order is runtime, failing, other: the failure comes before `other`.
        process.registry.closeFault = { if (it === failing) throw SqliteException(SQLITE_IOERR, "injected close failure") }

        assertTrue(process.registry.gate.closeAndDrain(1_000))
        assertFailsWith<StoreUnavailable> { process.registry.closeAllForQuiesce() }
        assertFalse(runtime.hasConnection, "the runtime connection is closed")
        assertTrue(other.isReleased, "a handle after the failing one is still released")
        assertFalse(other.hasConnection)
        assertTrue(failing.hasConnection)
        assertFalse(failing.isReleased)
        assertEquals(listOf(runtime, failing), process.registry.handles())

        process.registry.closeFault = null
        assertTrue(process.registry.retryUnusable())
        assertTrue(failing.isReleased, "the retry finished the release, not only the close")
        assertFalse(failing.hasConnection)
        assertEquals(listOf(runtime), process.registry.handles())
        assertFailsWith<IllegalStateException> { process.registry.connectionFor(failing) }
        process.registry.gate.reopen()
        assertEquals(row(1), store.getObject(1))
    }
}
