@file:OptIn(ExperimentalForeignApi::class, SharedReplicaPlatformApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.posix.O_APPEND
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pwrite
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Control's snapshot of a corrupt store (§6 step 4's `CORRUPT` column, F6k; Appendix B.18, Q47),
 * against the real SQLite and the real file system: the three paths, the `.partial` and
 * `COMPLETE`, every file synced first, the stop at backgrounding, a full disk, the writer drain
 * against a sibling, and which copy may be offered.
 */
class ReplicaDamagedSnapshotTest {

    private fun markerOf(directory: String) = "$directory/${ReplicaCorruptMarker.NAME}"

    /** A store with [rows] rows, closed with its last commits still in the `-wal`. */
    private fun aStore(rows: Long = 300, payloadBytes: Int = 2_000): ReplicaProcess {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        (1L..rows).chunked(20).forEach { some -> store.databaseLock.withLock { some.forEach { store.storeObject(row(it, payloadBytes = payloadBytes)) } } }
        store.close()
        return process
    }

    /** `replica/CORRUPT` as a detector writes it, with a time long past, so the checkpoint drain has nothing to wait for. */
    private fun mark(directory: String, time: Long = 0) {
        ReplicaCorruptMarker(PosixReplicaFiles, directory).write("time=$time\napp=test\nerror=database disk image is malformed\n")
    }

    /** The copy-files seam: the real one, with the free space and the protection recorded or faked. */
    private class Copies(private val real: DamagedCopyFiles = PosixDamagedCopyFiles) : DamagedCopyFiles by real {
        var free: Long? = null
        val protectedPaths = ArrayList<String>()
        var failWriteAfter: Int? = null
        private var writes = 0

        override fun freeBytes(path: String): Long = free ?: real.freeBytes(path)

        var failProtectOf: String? = null

        override fun protect(path: String, directory: Boolean) {
            if (path.substringAfterLast('/') == failProtectOf) throw ReplicaFileException("injected: the protection could not be set")
            protectedPaths += path.substringAfterLast('/')
            real.protect(path, directory)
        }

        override fun writeAll(descriptor: Int, bytes: ByteArray, count: Int) {
            failWriteAfter?.let { if (writes++ >= it) throw ReplicaNoSpace("injected: the volume is full") }
            real.writeAll(descriptor, bytes, count)
        }
    }

    private fun snapshot(process: ReplicaProcess, files: ReplicaFiles = PosixReplicaFiles, copies: DamagedCopyFiles = Copies(), clock: () -> Long = { files.now().first * 1_000 }, sleep: (Long) -> Unit = {}) =
        ReplicaDamagedSnapshot(process.registry, process.directory, files, copies, process.limits, clock, sleep)

    /** The originals, byte for byte (`-shm` aside: SQLite's own, rebuilt by any open). */
    private fun originals(process: ReplicaProcess) =
        (bytesOf(process.databasePath)?.toList()) to (bytesOf("${process.databasePath}-wal")?.toList())

    private fun rootOf(process: ReplicaProcess) = ReplicaDamagedSnapshot.damagedRootOf(process.directory)

    private fun subdirectories(process: ReplicaProcess) = PosixDamagedCopyFiles.list(rootOf(process), directories = true)

    private fun rowsIn(databasePath: String): Long {
        val connection = SqliteConnection.open(databasePath, readOnly = true)
        try {
            return connection.queryLong("SELECT count(*) FROM objects")
        } finally {
            connection.close()
        }
    }

    // ── a copy completes, and only then is it offered ──

    @Test
    fun aMarkedStoreIsCopiedWholeByTheBackupFirstOfferedOnceCompleteAndTakenOncePerMarker() {
        val process = aStore()
        assertTrue(bytesOf("${process.databasePath}-wal")!!.isNotEmpty(), "the last commits are still in the -wal")
        mark(process.directory)
        val before = bytesOf(process.databasePath)!!.toList() to bytesOf("${process.databasePath}-wal")!!.toList()

        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertEquals(DamagedCopyMethod.BACKUP, copy.method, "⭐ faithful first (319): the backup API, page for page")
        assertEquals(listOf("COMPLETE", "CORRUPT", "objects.db"), copy.files.map { it.substringAfterLast('/') }.sorted())
        assertEquals(300, rowsIn("${copy.directory}/objects.db"), "one consistent copy, the -wal's commits included")
        assertTrue("error=database disk image is malformed" in bytesOf("${copy.directory}/CORRUPT")!!.decodeToString())
        assertTrue("method=BACKUP" in bytesOf("${copy.directory}/COMPLETE")!!.decodeToString())
        assertEquals(listOf(copy.directory.substringAfterLast('/')), subdirectories(process), "no .partial is left")
        assertTrue(process.registry.handles().isEmpty(), "the SNAPSHOT handle is released")
        // S1: the originals are read, never written.
        assertEquals(before, bytesOf(process.databasePath)!!.toList() to bytesOf("${process.databasePath}-wal")!!.toList())

        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
        // Once per marker: the same originals are not copied again.
        assertEquals(copy.directory, assertIs<DamagedSnapshotOutcome.AlreadyComplete>(snapshot(process).take()).copy.directory)
        assertEquals(1, subdirectories(process).size)
    }

    @Test
    fun everyCopiedFileIsFullSyncedBeforeCompleteExistsAndTheCopyIsRenamedFinalLast() {
        val process = aStore()
        mark(process.directory)
        val files = RecordingFiles()
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process, files).take()).copy
        val name = copy.directory.substringAfterLast('/')
        val calls = files.calls.filter { it.startsWith("fullSync") || it.startsWith("rename") }
        val complete = calls.indexOf("rename COMPLETE.tmp -> COMPLETE")
        assertTrue(complete > 0, "$calls")
        for (file in listOf("objects.db", "CORRUPT")) {
            val synced = calls.indexOf("fullSync $file")
            assertTrue(synced in 0 until complete, "$file is synced before COMPLETE: $calls")
        }
        assertTrue(calls.indexOf("fullSync COMPLETE.tmp") in 0 until complete, "COMPLETE by the sidecar rule: $calls")
        assertTrue(calls.subList(complete, calls.size).contains("fullSync $name.partial"), "the directory after COMPLETE: $calls")
        val renamed = calls.indexOf("rename $name.partial -> $name")
        assertTrue(renamed > complete, "renamed final only once COMPLETE is durable: $calls")
        assertEquals("fullSync replica-damaged", calls.last(), "and the parent synced last: $calls")
    }

    @Test
    fun aCopyWhoseFileCannotBeSyncedIsNeverMarkedCompleteNorOffered() {
        val process = aStore()
        mark(process.directory)
        val files = RecordingFiles().apply { failOn = "fullSync objects.db" }
        assertIs<DamagedSnapshotOutcome.Failed>(snapshot(process, files).take())
        assertFalse(files.calls.any { it.startsWith("rename COMPLETE") }, "${files.calls}")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(subdirectories(process).isEmpty(), "a failed copy holds no disk: ${subdirectories(process)}")
        assertTrue(process.registry.handles().isEmpty())
    }

    // ── F6k: backgrounded mid-copy ──

    @Test
    fun backgroundedMidCopyTheCopyStopsLeavesItsPartialWithNoCompleteAndTheNextForegroundTakesItAgain() {
        // Over 1 MiB, so the byte copy has a gate check between chunks.
        val process = aStore(rows = 700)
        mark(process.directory)
        val first = snapshot(process).apply {
            methods = listOf(DamagedCopyMethod.BYTE_COPY)
            betweenChunks = { _, _, _ -> process.inBackground() }
        }
        assertIs<DamagedSnapshotOutcome.Stopped>(first.take())
        val left = subdirectories(process)
        assertEquals(1, left.size, "$left")
        assertTrue(left.single().endsWith(ReplicaDamagedSnapshot.PARTIAL_SUFFIX), "$left")
        assertFalse(fileExists("${rootOf(process)}/${left.single()}/COMPLETE"))
        assertNull(SharedReplicaDamagedCopies.current(process.directory), "a .partial is never offered")
        assertTrue(process.registry.handles().isEmpty(), "released through the registry")
        assertTrue(process.registry.transactionHolder() == null)
        // While still in the background nothing is attempted.
        assertIs<DamagedSnapshotOutcome.Stopped>(snapshot(process).take())

        process.inForeground()
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).apply { methods = listOf(DamagedCopyMethod.BYTE_COPY) }.take()).copy
        assertEquals(listOf(copy.directory.substringAfterLast('/')), subdirectories(process), "the old .partial is deleted")
        assertEquals(bytesOf(process.databasePath)!!.toList(), bytesOf("${copy.directory}/objects.db")!!.toList())
    }

    @Test
    fun vacuumIntoIsTheLastResortTriedOnlyOnceTheBackupAndTheByteCopyHaveFailed() {
        // ⭐ Decided by the maintainer 2026-10-30 (319): a logical rebuild hides the damage.
        val process = aStore()
        mark(process.directory)
        val tried = snapshot(process).apply { failing = setOf(DamagedCopyMethod.BACKUP) }.take()
        assertEquals(DamagedCopyMethod.BYTE_COPY, assertIs<DamagedSnapshotOutcome.Completed>(tried).copy.method, "the byte copy second")
        PosixReplicaFiles.remove(markerOf(process.directory))
        mark(process.directory, time = 1)
        val last = snapshot(process).apply { failing = setOf(DamagedCopyMethod.BACKUP, DamagedCopyMethod.BYTE_COPY) }.take()
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(last).copy
        assertEquals(DamagedCopyMethod.VACUUM_INTO, copy.method)
        assertEquals(300, rowsIn("${copy.directory}/objects.db"))
    }

    @Test
    fun aVacuumIntoStoppedByTheGateIsStoppedNotAFailureAndTriesNoOtherPath() {
        val process = aStore()
        mark(process.directory)
        // True only once VACUUM INTO has begun writing its destination: the stop reaches it from inside.
        val writing = {
            PosixDamagedCopyFiles.list(rootOf(process), directories = true).any { name ->
                name.endsWith(ReplicaDamagedSnapshot.PARTIAL_SUFFIX) && fileExists("${rootOf(process)}/$name/objects.db")
            }
        }
        val outcome = snapshot(process).apply { methods = listOf(DamagedCopyMethod.VACUUM_INTO) }.take(writing)
        assertEquals(DamagedSnapshotOutcome.Stopped("VACUUM INTO was stopped"), outcome)
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(process.registry.handles().isEmpty())
    }

    // ── room ──

    @Test
    fun aDiskWithoutRoomForAWholeCopyAndTheFloorRefusesItWithNothingWritten() {
        val process = aStore()
        mark(process.directory)
        val needed = bytesOf(process.databasePath)!!.size + bytesOf("${process.databasePath}-wal")!!.size + ReplicaDamagedSnapshot.FREE_FLOOR_BYTES
        val copies = Copies().apply { free = needed - 1 }
        val refused = assertIs<DamagedSnapshotOutcome.NoRoom>(snapshot(process, copies = copies).take())
        assertEquals(needed, refused.neededBytes)
        assertFalse(fileExists(rootOf(process)), "nothing is written, not even replica-damaged/ (B.18.8 (316))")
        assertTrue(process.registry.handles().isEmpty())

        copies.free = needed
        assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process, copies = copies).take())
    }

    @Test
    fun aDiskThatFillsDuringTheCopyDiscardsItAndOffersNothing() {
        val process = aStore(rows = 700)
        mark(process.directory)
        val copies = Copies().apply { failWriteAfter = 1 }
        assertIs<DamagedSnapshotOutcome.NoRoom>(snapshot(process, copies = copies).apply { methods = listOf(DamagedCopyMethod.BYTE_COPY) }.take())
        assertTrue(subdirectories(process).isEmpty(), "${subdirectories(process)}")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
    }

    // ── the three paths ──

    @Test
    fun aStoreWhoseTreeIsDamagedIsCopiedByTheBackupWhenVacuumIntoCannotReadIt() {
        val process = aStore()
        // Everything into objects.db, then garbage over four pages in the middle of the tree.
        process.sql { connection ->
            connection.queryLong("SELECT count(*) FROM objects")
            assertFalse(connection.checkpoint(SqliteCheckpointMode.TRUNCATE).busy)
        }
        val pages = bytesOf(process.databasePath)!!.size / ReplicaLimits.PAGE_BYTES
        val garbage = ByteArray(4 * ReplicaLimits.PAGE_BYTES) { 0x5a }
        val descriptor = open(process.databasePath, O_WRONLY)
        try {
            assertEquals(garbage.size.toLong(), pwrite(descriptor, garbage.refTo(0), garbage.size.convert(), (pages / 2).toLong() * ReplicaLimits.PAGE_BYTES))
        } finally {
            close(descriptor)
        }
        mark(process.directory)
        val before = originals(process)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertEquals(DamagedCopyMethod.BACKUP, copy.method)
        assertEquals(before, originals(process), "S1: the originals are read, never written")
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
        assertEquals(bytesOf(process.databasePath)!!.size, bytesOf("${copy.directory}/objects.db")!!.size, "page for page")
    }

    @Test
    fun aHeaderSqliteCannotReadIsPreservedByTheByteCopyAlone() {
        val process = aStore()
        // Page 1 out of the log first: a reader takes the newest page 1 from the -wal, over the file's.
        process.sql { connection ->
            connection.queryLong("SELECT count(*) FROM objects")
            assertFalse(connection.checkpoint(SqliteCheckpointMode.TRUNCATE).busy)
        }
        val garbage = ByteArray(100) { 0x33 }
        val descriptor = open(process.databasePath, O_WRONLY)
        try {
            pwrite(descriptor, garbage.refTo(0), garbage.size.convert(), 0)
        } finally {
            close(descriptor)
        }
        mark(process.directory)
        val before = originals(process)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertEquals(DamagedCopyMethod.BYTE_COPY, copy.method)
        assertEquals(before, originals(process), "S1: the originals are read, never written")
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
        assertEquals(bytesOf(process.databasePath)!!.toList(), bytesOf("${copy.directory}/objects.db")!!.toList())
        assertEquals(bytesOf("${process.databasePath}-wal")!!.toList(), bytesOf("${copy.directory}/objects.db-wal")!!.toList())
        assertFalse(fileExists("${copy.directory}/objects.db-shm"), "-shm is derived and is not copied")
    }

    private fun appendTo(path: String) {
        val descriptor = open(path, O_WRONLY or O_APPEND)
        assertTrue(descriptor >= 0)
        try {
            val one = byteArrayOf(1)
            write(descriptor, one.refTo(0), 1.convert())
        } finally {
            close(descriptor)
        }
    }

    @Test
    fun aByteCopyWhoseOriginalsMoveUnderItIsDiscardedRetriedAndAfterThreeReportedInconsistent() {
        val process = aStore()
        mark(process.directory)
        var moved = 0
        val outcome = snapshot(process).apply {
            methods = listOf(DamagedCopyMethod.BYTE_COPY)
            betweenChunks = { _, file, _ -> if (file == "objects.db") { moved++; appendTo("${process.databasePath}-wal") } }
        }.take()
        assertEquals(DamagedSnapshotOutcome.Inconsistent(3), outcome)
        assertEquals(3, moved)
        assertTrue(subdirectories(process).isEmpty(), "the discarded copy is not kept: ${subdirectories(process)}")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(process.registry.handles().isEmpty())
    }

    @Test
    fun aByteCopyWhoseOriginalsMovedOnceIsTakenAgainAndCompletes() {
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(
            snapshot(process).apply {
                methods = listOf(DamagedCopyMethod.BYTE_COPY)
                betweenChunks = { attempt, file, _ -> if (attempt == 1 && file == "objects.db") appendTo("${process.databasePath}-wal") }
            }.take(),
        ).copy
        assertEquals(bytesOf("${process.databasePath}-wal")!!.toList(), bytesOf("${copy.directory}/objects.db-wal")!!.toList())
        // ⚠ B.18.8 (311): COMPLETE records the originals the consistent attempt read, so the copy
        // is the one offered, though they moved after this attempt began.
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
        assertEquals(copy.directory, assertIs<DamagedSnapshotOutcome.AlreadyComplete>(snapshot(process).take()).copy.directory, "and not copied again")
    }

    // ── (4): a peer holds the file ──

    @Test
    fun aSiblingHoldingATransactionMakesTheDrainBusyAndNothingIsCopiedOrOffered() {
        val process = aStore()
        mark(process.directory)
        val sibling = rawConnection(process.databasePath)
        try {
            sibling.exec("BEGIN IMMEDIATE")
            val outcome = snapshot(process).take()
            assertIs<DamagedSnapshotOutcome.Busy>(outcome, "$outcome")
            sibling.exec("ROLLBACK")
        } finally {
            sibling.close()
        }
        assertTrue(subdirectories(process).isEmpty(), "${subdirectories(process)}")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(process.registry.handles().isEmpty())
    }

    // ── the marker ──

    @Test
    fun withNoMarkerThereIsNothingToCopyAndNothingIsCreated() {
        val process = aStore()
        assertEquals(DamagedSnapshotOutcome.NotCorrupt, snapshot(process).take())
        assertFalse(fileExists(rootOf(process)))
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
    }

    @Test
    fun theCopyWaitsForCkptMaxSinceTheMarkersTimeBeforeItTakesAnyLock() {
        val process = aStore()
        val now = PosixReplicaFiles.now().first
        mark(process.directory, time = now)
        var clock = now * 1_000
        var slept = 0L
        var lockedWhileWaiting = false
        val snapshot = snapshot(process, clock = { clock }, sleep = { ms ->
            if (process.registry.handles().isNotEmpty()) lockedWhileWaiting = true
            slept += ms
            clock += ms
        })
        assertIs<DamagedSnapshotOutcome.Completed>(snapshot.take())
        assertTrue(slept >= process.limits.checkpointMaxMs, "waited $slept ms, CKPT_MAX is ${process.limits.checkpointMaxMs}")
        assertFalse(lockedWhileWaiting)
    }

    @Test
    fun aCopyIsOfferedOnlyForTheMarkerAndTheOriginalsItWasTakenFromAndANewOneReplacesItOnlyOnceComplete() {
        val process = aStore()
        mark(process.directory)
        val first = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy

        // The originals moved (belt and braces, §6): not offered, and taken again.
        appendTo("${process.databasePath}-wal")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        val second = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertTrue(second.directory != first.directory)
        assertFalse(fileExists(first.directory), "the superseded copy is removed once the new one is complete")
        assertEquals(second.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)

        // A .partial is never offered, even with a COMPLETE in it; nor a directory without one.
        val completeText = bytesOf("${second.directory}/COMPLETE")!!
        PosixReplicaFiles.rename(second.directory, second.directory + ReplicaDamagedSnapshot.PARTIAL_SUFFIX)
        assertTrue(fileExists(second.directory + ReplicaDamagedSnapshot.PARTIAL_SUFFIX + "/COMPLETE"))
        assertNull(SharedReplicaDamagedCopies.current(process.directory), "a .partial with a COMPLETE")
        PosixReplicaFiles.rename(second.directory + ReplicaDamagedSnapshot.PARTIAL_SUFFIX, second.directory)
        assertEquals(second.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
        PosixReplicaFiles.remove("${second.directory}/COMPLETE")
        assertNull(SharedReplicaDamagedCopies.current(process.directory), "a directory without COMPLETE")
        assertTrue(completeText.isNotEmpty())

        // A marker that is not the one a copy was taken under (a store since replaced) is not offered it.
        val third = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        PosixReplicaFiles.remove(markerOf(process.directory))
        mark(process.directory, time = 1)
        assertNull(SharedReplicaDamagedCopies.current(process.directory), "a copy of another marker")
        assertTrue(fileExists(third.directory))
    }

    // ── B.18.8: the originals are never written, whatever their state ──

    @Test
    fun withNoObjectsDbNothingIsCopiedAndNothingIsCreatedInReplica() {
        val process = aStore()
        mark(process.directory)
        PosixReplicaFiles.remove(process.databasePath)
        val wal = bytesOf("${process.databasePath}-wal")
        assertIs<DamagedSnapshotOutcome.Failed>(snapshot(process).take())
        assertFalse(fileExists(process.databasePath), "the SNAPSHOT open never creates objects.db (310)")
        assertEquals(wal?.toList(), bytesOf("${process.databasePath}-wal")?.toList())
        assertTrue(process.registry.handles().isEmpty())
    }

    @Test
    fun theSnapshotHandlesOpenNeverCreatesTheFile() {
        // (310), belt and braces under the stat: a SNAPSHOT connection on no file fails, and makes none.
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val handle = process.registry.register(ReplicaHandleKind.SNAPSHOT, "test")
        assertFailsWith<SqliteException> { process.registry.connectionFor(handle) }
        assertFalse(fileExists(process.databasePath))
        process.registry.release(handle)
    }

    @Test
    fun anEmptyObjectsDbBesideANonEmptyWalIsByteCopiedAndTheWalIsKept() {
        val process = aStore()
        mark(process.directory)
        // A zero-length objects.db: SQLite would delete the -wal at its first shared lock.
        val descriptor = open(process.databasePath, O_WRONLY or platform.posix.O_TRUNC)
        close(descriptor)
        val before = originals(process)
        assertTrue(before.second!!.isNotEmpty())
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertEquals(DamagedCopyMethod.BYTE_COPY, copy.method)
        assertEquals(before, originals(process), "the -wal survives (310)")
        assertEquals(before.second, bytesOf("${copy.directory}/objects.db-wal")?.toList())
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
    }

    @Test
    fun aMarkerTimeInTheFutureWaitsAtMostCkptMaxFromNow() {
        val process = aStore()
        val now = PosixReplicaFiles.now().first
        mark(process.directory, time = now + 3_600)
        var clock = now * 1_000
        var slept = 0L
        val snapshot = snapshot(process, clock = { clock }, sleep = { ms -> slept += ms; clock += ms })
        assertIs<DamagedSnapshotOutcome.Completed>(snapshot.take())
        assertTrue(slept <= process.limits.checkpointMaxMs + 200, "a clock moved back costs CKPT_MAX, not an hour: slept $slept ms (313)")
    }

    @Test
    fun aHandleOfThisProcessStillOpenMakesItBusyAndTheAttemptAfterItsReleaseCompletes() {
        // Corruption met in-process: the fenced runtime's handle is still registered (§6 step 2b
        // releases it), so the drain answers Busy and the cover says "not copied yet".
        val process = aStore()
        val store = process.store()
        mark(process.directory)
        assertIs<DamagedSnapshotOutcome.Busy>(snapshot(process).take())
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(subdirectories(process).isEmpty(), "${subdirectories(process)}")
        store.close()
        assertTrue(process.registry.handles().isEmpty())
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory)
    }

    @Test
    fun theByteCopysDescriptorsCloseOnlyWithTheSnapshotConnectionEvenWhenItsCloseIsDeferred() {
        // (312): backgrounded mid-copy with protection attached, so the release's close is
        // deferred by the closed gate. Closing the descriptors then would drop the connection's
        // POSIX locks on objects.db and -shm.
        val process = aStore(rows = 700)
        mark(process.directory)
        val files = RecordingFiles()
        val snapshot = snapshot(process, files).apply {
            methods = listOf(DamagedCopyMethod.BYTE_COPY)
            betweenChunks = { _, _, _ ->
                process.inBackground()
                process.registry.gate.closeAndDrain(0)
            }
        }
        assertIs<DamagedSnapshotOutcome.Stopped>(snapshot.take())
        assertEquals(1, process.registry.deferredCloseCount(), "the close is owed")
        assertTrue(files.openDescriptors.values.contains("objects.db"), "kept open with the connection: ${files.openDescriptors}")
        process.registry.gate.reopen()
        process.registry.closeDeferredNow()
        assertFalse(files.openDescriptors.values.any { it.startsWith("objects.db") }, "closed with it: ${files.openDescriptors}")
        assertTrue(process.registry.handles().isEmpty())
    }

    @Test
    fun theCleanUpRemovesStoppedPartialsAlwaysAndEveryCopyOnceTheMarkerIsGone() {
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        PosixReplicaFiles.ensureDirectory("${rootOf(process)}/20000101T000000Z${ReplicaDamagedSnapshot.PARTIAL_SUFFIX}")
        assertEquals(1, SharedReplicaDamagedCopies.cleanUp(process.directory), "the .partial only")
        assertTrue(fileExists(copy.directory), "a copy of the marker there now is kept")
        PosixReplicaFiles.remove(markerOf(process.directory))
        assertEquals(1, SharedReplicaDamagedCopies.cleanUp(process.directory))
        assertFalse(fileExists(copy.directory), "with no marker nothing could offer it")
    }

    // ── ⭐ Q51: the user's "Delete the copy" ──

    @Test
    fun aDeletedCopyIsGoneWithItsPartialsAndTheSameDamageIsNeverCopiedAgainButANewMarkerIs() {
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        PosixReplicaFiles.ensureDirectory("${rootOf(process)}/20000101T000000Z${ReplicaDamagedSnapshot.PARTIAL_SUFFIX}")
        val before = originals(process)
        assertTrue(SharedReplicaDamagedCopies.delete(process.directory))
        assertTrue(subdirectories(process).isEmpty(), "the copy and every .partial: ${subdirectories(process)}")
        assertFalse(fileExists(copy.directory))
        assertEquals(before, originals(process), "the originals are never what is deleted")
        assertNull(SharedReplicaDamagedCopies.current(process.directory))
        assertTrue(SharedReplicaDamagedCopies.isDeleted(process.directory))
        assertEquals(DamagedSnapshotOutcome.Deleted, snapshot(process).take(), "not copied again at the next foreground")
        assertTrue(subdirectories(process).isEmpty())

        // The store damaged anew (a new marker): copied again.
        PosixReplicaFiles.remove(markerOf(process.directory))
        mark(process.directory, time = 1)
        assertFalse(SharedReplicaDamagedCopies.isDeleted(process.directory))
        assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take())
    }

    /** A delete that wrote its tombstone and then crashed before any removal. */
    private fun tombstoneOnly(process: ReplicaProcess) {
        val marker = ReplicaDamagedSnapshot.markerOf(PosixReplicaFiles, PosixDamagedCopyFiles, markerOf(process.directory))!!
        ReplicaDamagedSnapshot.writeDeleted(PosixReplicaFiles, rootOf(process), marker)
    }

    @Test
    fun aDeleteThatCrashedAfterItsTombstoneHasItsCopyRemovedByTheNextAttempt() {
        // B.18.9 (322): the data the user deleted may not stay until the marker goes.
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        tombstoneOnly(process)
        assertTrue(fileExists(copy.directory))
        assertEquals(DamagedSnapshotOutcome.Deleted, snapshot(process).take())
        assertTrue(subdirectories(process).isEmpty(), "${subdirectories(process)}")
    }

    @Test
    fun aDeleteThatCrashedAfterItsTombstoneHasItsCopyRemovedByTheCleanUp() {
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        PosixReplicaFiles.ensureDirectory("${rootOf(process)}/20000101T000000Z${ReplicaDamagedSnapshot.PARTIAL_SUFFIX}")
        tombstoneOnly(process)
        assertEquals(2, SharedReplicaDamagedCopies.cleanUp(process.directory))
        assertFalse(fileExists(copy.directory))
        assertTrue(subdirectories(process).isEmpty())
    }

    @Test
    fun aDeleteThatCannotProtectItsDirectoryWritesNoTombstoneAndKeepsTheCopyOffered() {
        val process = aStore()
        mark(process.directory)
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take()).copy
        val copies = Copies().apply { failProtectOf = "replica-damaged" }
        assertFalse(SharedReplicaDamagedCopies.deleteIn(process.directory, PosixReplicaFiles, copies))
        assertFalse(fileExists("${rootOf(process)}/${ReplicaDamagedSnapshot.DELETED_NAME}"), "no tombstone without the removals")
        assertEquals(copy.directory, SharedReplicaDamagedCopies.current(process.directory)?.directory, "not deleted, so still offered")
    }

    @Test
    fun withNoMarkerNothingIsDeletedAndTheCleanUpDropsAStaleTombstone() {
        val process = aStore()
        mark(process.directory)
        assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process).take())
        assertTrue(SharedReplicaDamagedCopies.delete(process.directory))
        PosixReplicaFiles.remove(markerOf(process.directory))
        assertFalse(SharedReplicaDamagedCopies.delete(process.directory), "no marker: nothing to delete for")
        SharedReplicaDamagedCopies.cleanUp(process.directory)
        assertFalse(fileExists("${rootOf(process)}/${ReplicaDamagedSnapshot.DELETED_NAME}"))
    }

    // ── privacy: §2.4's class and backup exclusion ──

    @Test
    fun theCopiesDirectoryIsExcludedFromBackupAndEveryPathIsGivenTheReplicasProtectionClass() {
        val process = aStore()
        mark(process.directory)
        val copies = Copies()
        val copy = assertIs<DamagedSnapshotOutcome.Completed>(snapshot(process, copies = copies).take()).copy
        val name = copy.directory.substringAfterLast('/')
        assertTrue(copies.protectedPaths.containsAll(listOf("replica-damaged", "$name.partial", "objects.db", "CORRUPT")), "${copies.protectedPaths}")
        memScoped {
            val value = alloc<ObjCObjectVar<Any?>>()
            assertTrue(NSURL.fileURLWithPath(rootOf(process), isDirectory = true).getResourceValue(value.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null))
            assertEquals(true, (value.value as? NSNumber)?.boolValue)
        }
    }

    @Test
    fun theProductionEntryRefusesAProcessWithNoInstalledProtection() {
        val process = aStore()
        mark(process.directory)
        assertFailsWith<SharedReplicaUnprotected> { SharedReplicaDamagedCopies.take(process.directory) }
        assertFalse(fileExists(rootOf(process)))
    }

    @Test
    fun theTimestampIsSortableUtc() {
        assertEquals("19700101T000000Z", ReplicaDamagedSnapshot.utcStamp(0))
        assertEquals("20261030T101112Z", ReplicaDamagedSnapshot.utcStamp(1_793_355_072))
        assertEquals("21000101T000000Z", ReplicaDamagedSnapshot.utcStamp(4_102_444_800))
    }
}
