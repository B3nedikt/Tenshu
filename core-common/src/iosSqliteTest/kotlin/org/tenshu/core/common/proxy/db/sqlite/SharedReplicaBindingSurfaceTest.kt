package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CANTOPEN
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOTADB
import platform.posix.F_RDLCK
import platform.posix.F_SETLK
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix._exit
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.fork
import platform.posix.open
import platform.posix.pipe
import platform.posix.read
import platform.posix.waitpid
import platform.posix.write
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCondition
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Appendix B.13: the platform seam the binding widens (B.12 (156)'s list) and the assertion it
 * makes real (B.11.4 (1), Q24). Through the **production** entries (`SharedReplicaStore.open`,
 * `wipeSharedReplica`, the process's registry for the path), which no other suite reaches: they
 * open through `ReplicaOpener` on registries of their own. Every store is made up.
 */
@OptIn(SharedReplicaPlatformApi::class, ExperimentalForeignApi::class)
class SharedReplicaBindingSurfaceTest {

    private fun ByteArray.toNSData(): NSData = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private class Time : SharedReplicaBackgroundTime {
        private val lock = NSRecursiveLock()
        var begun = 0
        override fun begin(reason: String, onExpired: () -> Unit): Long? {
            lock.lock()
            try {
                return (++begun).toLong()
            } finally {
                lock.unlock()
            }
        }

        override fun end(id: Long) = Unit
        override fun backgroundTimeRemainingMs(): Long = 60_000
    }

    private class Loops : SharedReplicaLoops {
        private val lock = NSRecursiveLock()
        val calls = ArrayList<String>()
        override fun start() = record("start")
        override fun stop() = record("stop")
        override fun awaitStopped(timeoutMs: Long) = record("await")
        private fun record(call: String) {
            lock.lock()
            calls += call
            lock.unlock()
        }

        fun snapshot(): List<String> {
            lock.lock()
            try {
                return calls.toList()
            } finally {
                lock.unlock()
            }
        }
    }

    /** The worker's queue for the tests: a global queue, never this thread. */
    private val offThread: (() -> Unit) -> Unit = { block ->
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u), block)
    }

    private fun open(directory: String, build: ReplicaBuild = testBuild()): ReplicaOpenResult =
        SharedReplicaStore.open(directory, build, ReplicaMigrationSteps { _, _ -> null }, "binding-test", foreground = true, protectedDataAvailable = { true })

    private fun storeOf(result: ReplicaOpenResult): SharedReplicaStore =
        (result as? ReplicaOpenResult.Ready)?.store ?: fail("expected a store, got $result")

    @Test
    fun theProductionOpenIsRefusedWithNoProtectionAttachedAndTouchesNothing() {
        val directory = tempReplica()
        assertFailsWith<SharedReplicaUnprotected> { open(directory) }
        assertFalse(fileExists("$directory/objects.db"), "nothing was created")
    }

    @Test
    fun theProductionOpenIsRefusedWhenAttachedButNotInstalled() {
        val directory = tempReplica()
        val lifecycle = SharedReplicaLifecycle.create(Time(), offThread)
        lifecycle.attach(directory)
        assertFalse(lifecycle.isInstalled)
        assertFailsWith<SharedReplicaUnprotected> { open(directory) }
        assertFalse(fileExists("$directory/objects.db"), "nothing was created")
    }

    @Test
    fun theStoreLessWipeIsRefusedWithNoProtection() {
        val directory = tempReplica()
        assertFailsWith<SharedReplicaUnprotected> {
            SharedReplicaStore.wipeSharedReplica(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", "test", { true })
        }
    }

    // ── D3 (§2.4, S0b-4 slice 2): the protection class and the backup exclusion at every open ──

    @Test
    fun theProductionOpenSetsReplicasProtectionClassAndBackupExclusionAndReadsThemBack() {
        val directory = tempReplica()
        installed(directory)
        val calls = ArrayList<String>()
        val real = org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform
        SharedReplicaStore.atRest = {
            org.tenshu.core.common.proxy.db.ContainerAtRest(platform = object : org.tenshu.core.common.proxy.db.ContainerAtRest.Platform by real {
                override fun setProtection(path: String): Boolean = real.setProtection(path).also { calls += "class " + path.substringAfterLast('/') }
            })
        }
        try {
            storeOf(open(directory)).close()
            assertEquals(listOf("class replica"), calls.map { it.replace(directory.substringAfterLast('/'), "replica") }, "the first open: the directory, before SQLite makes a file")
            assertTrue(real.isExcludedFromBackup(directory), "⚠⚠ replica/ is excluded from backup, read from a fresh URL")
            calls.clear()
            storeOf(open(directory)).close()
            assertTrue("class objects.db" in calls, "every open, and the store's files once they exist: $calls")
            assertTrue("class objects.db-wal" in calls, "$calls")
        } finally {
            SharedReplicaStore.atRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }
        }
    }

    @Test
    fun aClassOrAnExclusionThatDoesNotHoldIsARetryableNotReadyAndNoStoreIsMade() {
        listOf("class", "backup").forEach { failing ->
            val directory = tempReplica()
            installed(directory)
            val real = org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform
            SharedReplicaStore.atRest = {
                org.tenshu.core.common.proxy.db.ContainerAtRest(platform = object : org.tenshu.core.common.proxy.db.ContainerAtRest.Platform by real {
                    override fun setProtection(path: String): Boolean = failing != "class" && real.setProtection(path)
                    override fun isExcludedFromBackup(path: String): Boolean = failing != "backup" && real.isExcludedFromBackup(path)
                })
            }
            try {
                val refused = assertFailsWith<ReplicaNotProtected>(failing) { open(directory) }
                assertTrue(refused is StoreUnavailable, "retryable, as every StoreUnavailable")
                assertFalse(fileExists("$directory/objects.db"), "$failing: no store without the flags")
            } finally {
                SharedReplicaStore.atRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }
            }
        }
    }

    @Test
    fun beforeTheFirstUnlockNoClassIsSetAndTheOpenDecidesAsBefore() {
        val directory = tempReplica()
        installed(directory)
        val classed = ArrayList<String>()
        val real = org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform
        SharedReplicaStore.atRest = {
            org.tenshu.core.common.proxy.db.ContainerAtRest(platform = object : org.tenshu.core.common.proxy.db.ContainerAtRest.Platform by real {
                override fun classKeysAvailable(directory: String): Boolean = false
                override fun setProtection(path: String): Boolean = real.setProtection(path).also { classed += path }
            })
        }
        try {
            // Whatever the open answers then (its own protected-data rule), the flags are not what refuses it.
            runCatching {
                SharedReplicaStore.open(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", foreground = true, protectedDataAvailable = { false })
            }.exceptionOrNull()?.let { assertFalse(it is ReplicaNotProtected, "$it") }
            assertEquals(emptyList(), classed, "no class is set before the first unlock")
        } finally {
            SharedReplicaStore.atRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }
        }
    }

    @Test
    fun aLockedOpenAfterTheFirstUnlockSetsTheClassAndTheExclusion() {
        // ⚠⚠ Fix round 1, S1 (B.21.6 (410)): `protectedDataAvailable` false is a LOCKED phone too.
        val directory = tempReplica()
        installed(directory)
        val classed = ArrayList<String>()
        val real = org.tenshu.core.common.proxy.db.ContainerAtRest.FoundationPlatform
        SharedReplicaStore.atRest = {
            org.tenshu.core.common.proxy.db.ContainerAtRest(platform = object : org.tenshu.core.common.proxy.db.ContainerAtRest.Platform by real {
                override fun classKeysAvailable(directory: String): Boolean = true
                override fun setProtection(path: String): Boolean = real.setProtection(path).also { classed += path.substringAfterLast('/') }
            })
        }
        try {
            runCatching {
                SharedReplicaStore.open(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", foreground = false, protectedDataAvailable = { false })
            }.exceptionOrNull()?.let { assertFalse(it is ReplicaNotProtected, "$it") }
            assertTrue(classed.isNotEmpty(), "⚠⚠ the class is asserted on a locked wake's open")
            assertTrue(real.isExcludedFromBackup(directory), "⚠⚠ and the exclusion")
        } finally {
            SharedReplicaStore.atRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }
        }
    }

    @Test
    fun attachThenInstallThenOpenServesAStore() {
        val directory = tempReplica()
        val lifecycle = SharedReplicaLifecycle.create(Time(), offThread)
        lifecycle.attach(directory)
        lifecycle.install(isForeground = true)
        assertTrue(lifecycle.isInstalled)
        val result = open(directory)
        assertTrue(result is ReplicaOpenResult.Ready && result.created, "$result")
        val store = storeOf(result)
        store.databaseLock.withLock { store.storeObject(row(1)) }
        assertTrue(store.hasObject(1))
        store.close()
        // And the store-less wipe is admitted on the same registry.
        assertTrue(SharedReplicaStore.wipeSharedReplica(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", "test", { true }))
    }

    @Test
    fun anAttachAfterInstallAndASecondAttachAreRefused() {
        val late = SharedReplicaLifecycle.create(Time(), offThread)
        late.install(isForeground = true)
        assertFailsWith<IllegalStateException> { late.attach(tempReplica()) }

        val directory = tempReplica()
        val twice = SharedReplicaLifecycle.create(Time(), offThread)
        twice.attach(directory)
        assertFailsWith<IllegalStateException> { twice.attach(directory) }
    }

    @Test
    fun theLoopsStartAtAForegroundInstallStopAtTheBackgroundAndStartAgain() {
        val directory = tempReplica()
        val lifecycle = SharedReplicaLifecycle.create(Time(), offThread)
        lifecycle.attach(directory)
        val loops = Loops()
        lifecycle.install(isForeground = true, loops = loops)
        assertEquals(listOf("start"), loops.snapshot())
        lifecycle.enteredBackground()
        within(30_000, "the loops stopped") { "stop" in loops.snapshot() }
        within(30_000, "the shutdown ended") { lifecycle.protection.quiesceOutcomes.isNotEmpty() }
        lifecycle.enteredForeground()
        within(30_000, "the loops started again") { loops.snapshot().count { it == "start" } == 2 }
    }

    @Test
    fun aBackgroundLaunchStartsNoLoop() {
        val directory = tempReplica()
        val lifecycle = SharedReplicaLifecycle.create(Time(), offThread)
        lifecycle.attach(directory)
        val loops = Loops()
        lifecycle.install(isForeground = false, loops = loops)
        assertEquals(emptyList(), loops.snapshot().filter { it == "start" })
        assertFalse(lifecycle.isForeground)
    }

    @Test
    fun aWipeTheOpenHandedToTheWorkerIsToldToTheHostAndTheStoreOpensAfter() {
        val directory = tempReplica()
        val told = NSCondition()
        val outcomes = ArrayList<SharedReplicaJobOutcome>()
        val lifecycle = SharedReplicaLifecycle.create(Time(), offThread) { outcome ->
            told.lock()
            outcomes += outcome
            told.broadcast()
            told.unlock()
        }
        lifecycle.attach(directory)
        lifecycle.install(isForeground = true)
        val first = storeOf(open(directory))
        first.databaseLock.withLock { (1L..5L).forEach { first.storeObject(row(it)) } }
        first.close()

        // A build whose `tenshu/file` is above the store's and whose floor is its own version: below
        // the floor, a wipe (the binding's builds have floor = version, B.13 (167)).
        val newer = testBuild(services = mapOf("tenshu/file" to 4, "tenshu/sync" to 9), serviceFloors = mapOf("tenshu/file" to 4, "tenshu/sync" to 9))
        val updating = open(directory, newer)
        assertTrue(updating is ReplicaOpenResult.Updating && updating.scheduled && updating.job == ReplicaJob.WIPE, "$updating")

        told.lock()
        val deadline = NSDate.dateWithTimeIntervalSinceNow(30.0)
        while (outcomes.none { it.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED }) {
            if (!told.waitUntilDate(deadline)) break
        }
        val seen = outcomes.toList()
        told.unlock()
        val finished = seen.firstOrNull { it.kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED } ?: fail("no finish told: $seen")
        assertEquals("$directory/objects.db", finished.databasePath)
        assertTrue(finished.reopen)

        val after = storeOf(open(directory, newer))
        assertFalse(after.hasObject(1), "the wipe removed the rows")
        after.close()
    }

    private fun installed(directory: String, dispatch: (() -> Unit) -> Unit = offThread, onJobOutcome: (SharedReplicaJobOutcome) -> Unit = {}): SharedReplicaLifecycle =
        SharedReplicaLifecycle.create(Time(), dispatch, onJobOutcome).also {
            it.attach(directory)
            it.install(isForeground = true)
        }

    private fun storeWithRows(directory: String, build: ReplicaBuild = testBuild()) {
        val store = storeOf(open(directory, build))
        store.databaseLock.withLock { (1L..5L).forEach { store.storeObject(row(it)) } }
        store.close()
    }

    @Test
    fun aHandedOffWipeReturnsAtOnceAndTheWorkerFinishesIt() {
        val directory = tempReplica()
        val held = ArrayList<() -> Unit>()
        val told = ArrayList<SharedReplicaJobOutcome>()
        installed(directory, dispatch = { held += it }) { told += it }
        storeWithRows(directory)
        val started = SharedReplicaStore.startWipeSharedReplica(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", "test", { true })
        assertEquals(SharedReplicaWipeStart.HANDED_OFF, started)
        assertEquals(1, held.size, "the rest of the wipe is the worker's, not the caller's")
        assertTrue(told.isEmpty())
        // The worker's run, off this thread as the queue would.
        val done = NSCondition()
        var ran = false
        offThread {
            held.single()()
            done.lock(); ran = true; done.broadcast(); done.unlock()
        }
        within(30_000, "the worker ran the wipe") { done.lock(); val r = ran; done.unlock(); r }
        assertEquals(listOf(SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED), told.map { it.kind })
        val after = storeOf(open(directory))
        assertFalse(after.hasObject(1))
        after.close()
    }

    @Test
    fun aHandedOffWipeOfNothingIsAnsweredAtOnce() {
        val directory = tempReplica()
        installed(directory)
        assertEquals(
            SharedReplicaWipeStart.NOTHING_TO_WIPE,
            SharedReplicaStore.startWipeSharedReplica(directory, testBuild(), ReplicaMigrationSteps { _, _ -> null }, "binding-test", "test", { true }),
        )
    }

    @Test
    fun aHandOffWithNoWorkerAttachedIsRefused() {
        val process = ReplicaProcess(tempReplica())
        process.store().also { s -> s.databaseLock.withLock { s.storeObject(row(1)) } }.close()
        assertFailsWith<IllegalStateException> { process.opener().startWipe("test", handOff = true) {} }
        assertTrue(process.store().hasObject(1), "nothing was wiped")
    }

    @Test
    fun theFileDeletionIsRefusedWithNoProtection() {
        val directory = tempReplica()
        assertFailsWith<SharedReplicaUnprotected> { SharedReplicaStore.deleteSharedReplicaFiles(directory) }
    }

    @Test
    fun theFilesOfAStoreNobodyHasOpenAreDeleted() {
        val directory = tempReplica()
        installed(directory)
        storeWithRows(directory)
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/CORRUPT", null, null)
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        listOf("objects.db", "objects.db-wal", "objects.db-shm", "CORRUPT").forEach { assertFalse(fileExists("$directory/$it"), it) }
    }

    @Test
    fun aStoreAnotherConnectionHasOpenIsNotDeleted() {
        val directory = tempReplica()
        installed(directory)
        storeWithRows(directory)
        // Another process's connection, idle: no transaction, just open (WAL mode).
        val sibling = rawConnection("$directory/objects.db")
        try {
            sibling.queryLong("SELECT count(*) FROM objects")
            assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
            assertTrue(fileExists("$directory/objects.db"), "nothing touched")
            assertEquals(5L, sibling.queryLong("SELECT count(*) FROM objects"), "the sibling still reads its rows")
        } finally {
            sibling.close()
        }
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        assertFalse(fileExists("$directory/objects.db"))
    }

    @Test
    fun aStoreThisProcessHoldsAHandleOnIsNotDeleted() {
        val directory = tempReplica()
        installed(directory)
        val store = storeOf(open(directory))
        assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        assertTrue(fileExists("$directory/objects.db"))
        // ⚠ And with its connection closed (a quiesce), the handle still registered: it would
        // reopen on the deleted path, so it still counts.
        ReplicaHandles.forPath("$directory/objects.db", ReplicaLimits()).closeAllForQuiesce()
        assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        assertTrue(fileExists("$directory/objects.db"))
        store.close()
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
    }

    @Test
    fun aFileSqliteCannotOpenAsADatabaseIsDeleted() {
        val directory = tempReplica()
        installed(directory)
        val garbage = "made-up bytes that are not a SQLite header, long enough to be read as one".encodeToByteArray()
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", garbage.toNSData(), null)
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        assertFalse(fileExists("$directory/objects.db"))
    }

    @Test
    fun anOpenThatFailsKeepsTheFilesWhateverItsCode() {
        val directory = tempReplica()
        installed(directory)
        storeWithRows(directory)
        val registry = ReplicaHandles.forPath("$directory/objects.db", ReplicaLimits())
        listOf(SQLITE_CANTOPEN, SQLITE_IOERR, SQLITE_NOTADB).forEach { code ->
            assertEquals(
                SharedReplicaDeletion.BUSY,
                deleteReplicaFiles(registry, directory, openConnection = { throw SqliteException(code, "made up") }),
                "code $code",
            )
            assertTrue(fileExists("$directory/objects.db"), "nothing deleted ($code)")
        }
    }

    @Test
    fun anUnreadableHeaderWithAnotherProcessHoldingSqlitesLockIsNotDeleted() {
        val directory = tempReplica()
        installed(directory)
        val garbage = "made-up bytes that are not a SQLite header, long enough to be read as one".encodeToByteArray()
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", garbage.toNSData(), null)
        // SHARED_FIRST and SHARED_SIZE as sqlite3.c defines them, not this module's constants.
        withAnotherProcessHolding("$directory/objects.db", 0x40000002L, 510) {
            assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
            assertTrue(fileExists("$directory/objects.db"))
        }
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
    }

    @Test
    fun aShmWhoseDeadManSwitchAnotherProcessHoldsIsNotDeleted() {
        val directory = tempReplica()
        installed(directory)
        val garbage = "made-up bytes that are not a SQLite header, long enough to be read as one".encodeToByteArray()
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", garbage.toNSData(), null)
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/objects.db-shm", ByteArray(32_768).toNSData(), null)
        // UNIX_SHM_DMS as sqlite3.c defines it.
        withAnotherProcessHolding("$directory/objects.db-shm", 128L, 1) {
            assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
            assertTrue(fileExists("$directory/objects.db"))
        }
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
    }

    @Test
    fun aFileThatIsNotSqliteIsUnlinkedWithTheProbesLocksStillHeld() {
        val directory = tempReplica()
        installed(directory)
        val garbage = "made-up bytes that are not a SQLite header, long enough to be read as one".encodeToByteArray()
        platform.Foundation.NSFileManager.defaultManager.createFileAtPath("$directory/objects.db", garbage.toNSData(), null)
        val registry = ReplicaHandles.forPath("$directory/objects.db", ReplicaLimits())
        var anotherCouldLock: Boolean? = null
        var sqliteOpened = false
        val answer = deleteReplicaFiles(
            registry,
            directory,
            openConnection = { sqliteOpened = true; SqliteConnection.open(it) },
            // SHARED_FIRST, SHARED_SIZE as sqlite3.c defines them.
            beforeUnlink = { anotherCouldLock = anotherProcessCanLock("$directory/objects.db", 0x40000002L, 510) },
        )
        assertEquals(SharedReplicaDeletion.DELETED, answer)
        // ⚠ What this proves: the probe's locks are held when `beforeUnlink` runs, after every
        // SQLite decision. The helper's own descriptor, closed once the child has answered, then
        // drops them for the instant before the unlinks: a test artefact, not the production path.
        assertEquals(false, anotherCouldLock, "the probe's write locks are held up to the unlink")
        assertFalse(sqliteOpened, "SQLite never touched a file that is not a database")
        assertFalse(fileExists("$directory/objects.db"))
    }

    /**
     * A database with SQLite's magic intact and page 1's b-tree header destroyed (its page type,
     * byte 100): every schema read fails `CORRUPT`. Made in rollback mode, so page 1 is in the
     * file and not in a `-wal`.
     */
    private fun damagedSchemaDatabase(path: String) {
        val raw = SqliteConnection.open(path)
        raw.exec("PRAGMA journal_mode = DELETE")
        raw.exec("CREATE TABLE made_up (x)")
        raw.exec("INSERT INTO made_up VALUES (1)")
        raw.close()
        val fd = open(path, O_RDWR)
        check(fd >= 0)
        val bytes = ByteArray(8) { 0x7f }
        bytes.usePinned { platform.posix.pwrite(fd, it.addressOf(0), 8u, 100) }
        platform.posix.close(fd)
        val check = SqliteConnection.open(path)
        try {
            val failure = assertFailsWith<SqliteException> { check.queryLong("SELECT count(*) FROM sqlite_schema") }
            assertEquals(SQLITE_CORRUPT, failure.primaryCode, "the fixture's schema read fails CORRUPT")
        } finally {
            check.close()
        }
        assertTrue(bytesOf(path)!!.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".encodeToByteArray()), "the magic is intact")
    }

    @Test
    fun aFailedHeaderReadProvesNothing() {
        // pread on a descriptor that is not open fails (-1): not "not SQLite", which would delete.
        assertNull(looksLikeSqlite(-1))
    }

    @Test
    fun aValidMagicOverADamagedSchemaIsDeletedWhenNoOtherProcessHoldsALock() {
        val directory = tempReplica()
        installed(directory)
        damagedSchemaDatabase("$directory/objects.db")
        assertEquals(SharedReplicaDeletion.DELETED, SharedReplicaStore.deleteSharedReplicaFiles(directory))
        assertFalse(fileExists("$directory/objects.db"))
    }

    @Test
    fun aProcessThatLocksBetweenSqlitesCloseAndTheRelockKeepsTheFiles() {
        val directory = tempReplica()
        installed(directory)
        damagedSchemaDatabase("$directory/objects.db")
        val registry = ReplicaHandles.forPath("$directory/objects.db", ReplicaLimits())
        var release: (() -> Unit)? = null
        try {
            val answer = deleteReplicaFiles(
                registry,
                directory,
                // SHARED_FIRST and SHARED_SIZE as sqlite3.c defines them.
                beforeRelock = { release = anotherProcessHolds("$directory/objects.db", 0x40000002L, 510) },
            )
            assertEquals(SharedReplicaDeletion.BUSY, answer)
            assertTrue(fileExists("$directory/objects.db"))
        } finally {
            release?.invoke()
        }
    }

    /** A forked child takes a read lock on the range and holds it until the returned function is called. */
    private fun anotherProcessHolds(path: String, start: Long, length: Long): () -> Unit {
        val fds = nativeHeap.allocArray<IntVar>(4)
        val request = nativeHeap.alloc<flock>()
        val byte = nativeHeap.alloc<ByteVar>()
        check(pipe(fds) == 0 && pipe(fds + 2) == 0)
        request.l_type = F_RDLCK.toShort()
        request.l_whence = SEEK_SET.toShort()
        request.l_start = start
        request.l_len = length
        // ⚠ A descriptor of this process's on the file, inherited by the child, and closed only
        // after the deletion has answered (closing it drops this process's locks on the file).
        val fd = open(path, O_RDWR)
        check(fd >= 0)
        val pid = fork()
        if (pid == 0) {
            byte.value = (if (fcntl(fd, F_SETLK, request.ptr) == 0) 1 else 2).toByte()
            write(fds[1], byte.ptr, 1u)
            read(fds[2], byte.ptr, 1u)
            _exit(0)
        }
        check(pid > 0) { "fork failed" }
        check(read(fds[0], byte.ptr, 1u) == 1L && byte.value.toInt() == 1) { "the child did not take the lock" }
        return {
            write(fds[3], byte.ptr, 1u)
            memScoped { val status = alloc<IntVar>(); waitpid(pid, status.ptr, 0) }
            platform.posix.close(fd)
            nativeHeap.free(fds.rawValue)
            nativeHeap.free(request.rawPtr)
            nativeHeap.free(byte.rawPtr)
        }
    }

    @Test
    fun aValidMagicOverADamagedSchemaWithAnotherProcessHoldingTheRangeIsNotDeleted() {
        val directory = tempReplica()
        installed(directory)
        damagedSchemaDatabase("$directory/objects.db")
        // SHARED_FIRST and SHARED_SIZE as sqlite3.c defines them.
        withAnotherProcessHolding("$directory/objects.db", 0x40000002L, 510) {
            assertEquals(SharedReplicaDeletion.BUSY, SharedReplicaStore.deleteSharedReplicaFiles(directory))
            assertTrue(fileExists("$directory/objects.db"))
        }
    }

    /** Whether a forked child process can take a read lock on `[start, start+length)` of [path] now. */
    private fun anotherProcessCanLock(path: String, start: Long, length: Long): Boolean {
        val fds = nativeHeap.allocArray<IntVar>(2)
        val request = nativeHeap.alloc<flock>()
        val byte = nativeHeap.alloc<ByteVar>()
        // ⚠ Opened here and closed only once the child has answered. Closing it drops this process's
        // locks on the file, the probe's included: a caller learns "held until now", no more.
        val fd = open(path, O_RDWR)
        try {
            check(fd >= 0 && pipe(fds) == 0)
            request.l_type = F_RDLCK.toShort()
            request.l_whence = SEEK_SET.toShort()
            request.l_start = start
            request.l_len = length
            val pid = fork()
            if (pid == 0) {
                byte.value = (if (fcntl(fd, F_SETLK, request.ptr) == 0) 1 else 2).toByte()
                write(fds[1], byte.ptr, 1u)
                _exit(0)
            }
            check(pid > 0) { "fork failed" }
            check(read(fds[0], byte.ptr, 1u) == 1L)
            memScoped { val status = alloc<IntVar>(); waitpid(pid, status.ptr, 0) }
            return byte.value.toInt() == 1
        } finally {
            platform.posix.close(fd)
            nativeHeap.free(fds.rawValue)
            nativeHeap.free(request.rawPtr)
            nativeHeap.free(byte.rawPtr)
        }
    }

    /**
     * Runs [block] while a forked child process holds a POSIX read lock on `[start, start+length)`
     * of [path], as a sibling's SQLite would. The child only makes system calls.
     */
    private fun withAnotherProcessHolding(path: String, start: Long, length: Long, block: () -> Unit) {
        val fds = nativeHeap.allocArray<IntVar>(4)
        val request = nativeHeap.alloc<flock>()
        val byte = nativeHeap.alloc<ByteVar>()
        try {
            check(pipe(fds) == 0 && pipe(fds + 2) == 0)
            request.l_type = F_RDLCK.toShort()
            request.l_whence = SEEK_SET.toShort()
            request.l_start = start
            request.l_len = length
            // Opened here, inherited by the child: the lock the child takes is the child's own.
            val fd = open(path, O_RDWR)
            check(fd >= 0) { "open $path" }
            val pid = fork()
            if (pid == 0) {
                val ok = if (fcntl(fd, F_SETLK, request.ptr) == 0) 1 else 2
                byte.value = ok.toByte()
                write(fds[1], byte.ptr, 1u)
                read(fds[2], byte.ptr, 1u)
                _exit(0)
            }
            check(pid > 0) { "fork failed" }
            check(read(fds[0], byte.ptr, 1u) == 1L && byte.value.toInt() == 1) { "the child did not take the lock" }
            try {
                block()
            } finally {
                write(fds[3], byte.ptr, 1u)
                memScoped { val status = alloc<IntVar>(); waitpid(pid, status.ptr, 0) }
                platform.posix.close(fd)
            }
        } finally {
            nativeHeap.free(fds.rawValue)
            nativeHeap.free(request.rawPtr)
            nativeHeap.free(byte.rawPtr)
        }
    }

    @Test
    fun everyOutcomeMapsToItsKind() {
        val path = "/made/up/objects.db"
        val runs = mapOf(
            ReplicaRunOutcome.Finished to SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED,
            ReplicaRunOutcome.Stopped("made up") to SharedReplicaJobOutcome.Kind.REPLACEMENT_STOPPED,
            ReplicaRunOutcome.NeedsForeground to SharedReplicaJobOutcome.Kind.REPLACEMENT_NEEDS_FOREGROUND,
            ReplicaRunOutcome.Skew("made up") to SharedReplicaJobOutcome.Kind.REPLACEMENT_SKEW,
            ReplicaRunOutcome.Corrupt("made up") to SharedReplicaJobOutcome.Kind.REPLACEMENT_CORRUPT,
        )
        for ((run, kind) in runs) {
            val outcome = SharedReplicaLifecycle.replacementOutcome(path, run)
            assertEquals(kind, outcome.kind, "$run")
            assertEquals(path, outcome.databasePath)
            assertEquals(kind == SharedReplicaJobOutcome.Kind.REPLACEMENT_FINISHED, outcome.reopen, "$run")
        }
        val walks = mapOf(
            ReplicaWalkOutcome.Clean(3) to SharedReplicaJobOutcome.Kind.WALK_CLEAN,
            ReplicaWalkOutcome.Confirmed("made up") to SharedReplicaJobOutcome.Kind.WALK_CONFIRMED,
            ReplicaWalkOutcome.NothingPending to SharedReplicaJobOutcome.Kind.WALK_PENDING,
            ReplicaWalkOutcome.Deferred("made up") to SharedReplicaJobOutcome.Kind.WALK_PENDING,
            ReplicaWalkOutcome.NotMade("made up") to SharedReplicaJobOutcome.Kind.WALK_PENDING,
        )
        for ((walk, kind) in walks) {
            val outcome = SharedReplicaLifecycle.walkOutcome(path, walk)
            assertEquals(kind, outcome.kind, "$walk")
            assertEquals(kind == SharedReplicaJobOutcome.Kind.WALK_CLEAN, outcome.reopen, "$walk")
        }
    }

    /** Waits up to [timeoutMs] for [done], polling. Generous: each is "this happens at all" on a loaded host. */
    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }
}
