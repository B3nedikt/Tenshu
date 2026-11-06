@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOTADB
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pwrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A corruption code from SQLite is confirmed before the permanent marker is written (Appendix
 * B.3 (45), B.4 paths 5, 6, 7 and 9): fenced at once and handed to the confirmation walk (Q5,
 * B.8), whose run reads the store a second time on a new connection; marked only if that reading
 * agrees. The operation that met the code never reads a second time itself.
 *
 * The first code is injected at each place the store can meet one, **once**: SQLite then answers
 * as the file really is, unless the case says what the second reading meets. Three tests at the
 * end use a file that really is damaged, where both readings are SQLite's own.
 */
class ReplicaCorruptionConfirmTest {

    // C = 8, G = 8, δ = 20 frames, Wmax = 120 pages, journal_size_limit = 30: a few hundred
    // kilobytes reach the ceiling.
    private val tight = ReplicaLimits(
        wmaxPages = 120,
        cachePages = 8,
        statementPages = 8,
        rowMaxBytes = 8_000,
        ceilingWaitMs = 150,
        chunkMaxRows = 1_000_000,
    )

    private fun corruption() = SqliteException(SQLITE_CORRUPT, "database disk image is malformed")

    private fun markerOf(directory: String) = "$directory/${ReplicaCorruptMarker.NAME}"

    /** A store with five rows, closed. */
    private fun aStore(limits: ReplicaLimits = tight): String {
        val directory = tempReplica()
        val store = ReplicaProcess(directory, limits).store()
        store.databaseLock.withLock { repeat(5) { store.storeObject(row(it.toLong())) } }
        store.close()
        return directory
    }

    /**
     * A sibling that knows none of the store's rules grows the log just past [bytes]. ⚠ At least
     * one row, whatever the file's size: after a checkpoint that copied every frame and was not
     * followed by its rewind write, the file is still large but the next commit restarts the log
     * and truncates it, and the size is only then what a trigger will find.
     */
    private fun ReplicaProcess.growLogPast(bytes: Long) = sql { connection ->
        var next = connection.queryLong("SELECT coalesce(max(hash), 0) FROM objects") + 100_000L
        do {
            connection.exec("INSERT INTO objects (hash, type, data) VALUES (${next++}, 'x', zeroblob(3000))")
        } while (bytesOf("$databasePath-wal")!!.size <= bytes)
    }

    private fun aSiblingWritesTheMarker(directory: String) {
        ReplicaCorruptMarker(PosixReplicaFiles, directory).write("time=0\napp=sibling\nerror=test\n")
    }

    // ── every path, every second reading, the gate, a sibling ──

    /** Where the first code is met. */
    private enum class Path {
        /** B.4 path 5: the read/write open, at its `BEGIN`, before it holds an admission of its own. */
        OPEN_BEGIN,

        /** Path 5: inside the open's write transaction. */
        OPEN_WRITE_TRANSACTION,

        /** Path 6: an access, at its `BEGIN`. */
        ACCESS_BEGIN,

        /** Path 6: the first statement of an access, under the write lock. */
        ACCESS_EPOCH_READ,

        /** Path 6: a statement of the caller's block, in a nested entry. */
        ACCESS_STATEMENT,

        /** Path 6: the `COMMIT`. */
        ACCESS_COMMIT,

        /** Path 7: the ceiling checkpoint an access runs before it may begin. */
        STORE_CEILING_CHECKPOINT,

        /** Path 7: the opportunistic checkpoint after an access's commit. */
        STORE_CHECKPOINT_AFTER_COMMIT,

        /** Path 9: a job's transaction, at its `BEGIN`. */
        JOB_BEGIN,

        /** Path 9: inside a job's transaction. */
        JOB_TRANSACTION,

        /** Path 9 (and 7): the ceiling checkpoint a job runs to reset the log. */
        JOB_CEILING_CHECKPOINT,

        /** Path 9 (and 7): the opportunistic checkpoint after a job's commit. */
        JOB_CHECKPOINT_AFTER_COMMIT;

        val anAccess get() = this in setOf(ACCESS_BEGIN, ACCESS_EPOCH_READ, ACCESS_STATEMENT, ACCESS_COMMIT)
        val aStoresCheckpoint get() = this in setOf(STORE_CEILING_CHECKPOINT, STORE_CHECKPOINT_AFTER_COMMIT)

        /** A checkpoint met the code: (52) is its whole handling, and nothing goes to the walk (B.8.3 (113)). */
        val aCheckpoint get() = this in setOf(STORE_CEILING_CHECKPOINT, STORE_CHECKPOINT_AFTER_COMMIT, JOB_CEILING_CHECKPOINT, JOB_CHECKPOINT_AFTER_COMMIT)
    }

    /** What the second reading meets. */
    private enum class Second {
        /** Nothing is injected: the file is healthy, and `quick_check` says so. */
        HEALTHY,

        /** SQLite answers a corruption code again (another one: the two need not match). */
        CORRUPT_AGAIN,

        /** It agrees, and the marker's rename fails. */
        CORRUPT_AGAIN_AND_THE_MARKER_CANNOT_BE_WRITTEN,
        IO_ERROR,
        BUSY,

        /** The phone locked in between: a corruption code read then is no evidence. */
        A_CORRUPTION_CODE_WITH_PROTECTED_DATA_GONE,

        /** The reading is still running at `CONFIRM_MAX`. */
        PAST_THE_DEADLINE,

        /** A sibling reached its own verdict before this confirmation looked. */
        A_SIBLINGS_MARKER_ALREADY_THERE,

        /** A sibling reached its verdict while this one read, and this one read a healthy store. */
        A_SIBLINGS_MARKER_MEANWHILE,
    }

    private enum class Gate { STAYS_OPEN, CLOSES_WITH_THE_FIRST_CODE, CLOSES_BEFORE_THE_SECOND_READING }

    /** What another process does between the two readings. */
    private enum class Sibling { IDLE, COMMITS_A_ROW, CHECKPOINTS }

    private class Expected(val verdict: String, val markerOnDisk: Boolean, val secondReadingMade: Boolean)

    /**
     * Written from the rules, not from the code:
     * 1. nothing is read, looked for or written once the gate has closed: unconfirmed;
     * 2. a sibling's marker is the verdict when it is found (one already there is found first,
     *    before a gate that closes later has closed);
     * 3. otherwise only a second reading that says damage, while protected data is available,
     *    and a marker that could be written, is `CORRUPT`;
     * 4. everything else is unconfirmed, and this process writes no marker;
     * 5. a checkpoint's first code is not read again at all (B.8.3 (113)).
     */
    private fun expected(second: Second, gate: Gate, path: Path): Expected {
        // A checkpoint's first code reads nothing and hands nothing off: only a sibling's marker,
        // looked for with the gate open, is a verdict (5., B.8.3 (113)).
        if (path.aCheckpoint) {
            val atOnce = second == Second.A_SIBLINGS_MARKER_ALREADY_THERE && gate != Gate.CLOSES_WITH_THE_FIRST_CODE
            return Expected(
                verdict = if (atOnce) "Corrupt" else "Unconfirmed",
                markerOnDisk = second == Second.A_SIBLINGS_MARKER_ALREADY_THERE,
                secondReadingMade = false,
            )
        }
        val open = gate == Gate.STAYS_OPEN
        val confirmed = when (second) {
            Second.A_SIBLINGS_MARKER_ALREADY_THERE -> gate != Gate.CLOSES_WITH_THE_FIRST_CODE
            Second.CORRUPT_AGAIN, Second.A_SIBLINGS_MARKER_MEANWHILE -> open
            else -> false
        }
        val aSiblingWroteOne = second == Second.A_SIBLINGS_MARKER_ALREADY_THERE ||
            (second == Second.A_SIBLINGS_MARKER_MEANWHILE && gate != Gate.CLOSES_WITH_THE_FIRST_CODE)
        return Expected(
            verdict = if (confirmed) "Corrupt" else "Unconfirmed",
            markerOnDisk = confirmed || aSiblingWroteOne,
            secondReadingMade = open && second != Second.A_SIBLINGS_MARKER_ALREADY_THERE,
        )
    }

    private inner class Scene(val path: Path, val second: Second, val gate: Gate, val sibling: Sibling) {
        val directory = aStore()
        val files = RecordingFiles()
        val process = ReplicaProcess(directory, tight, files)
        val problems = ArrayList<String>()
        var store: SharedReplicaStore? = null
        var secondReadings = 0
        var handOffs = 0
        private var now = 0L
        private var fired = false
        private var atFirstCode: Pair<ByteArray, ByteArray>? = null
        private var aSiblingWrote = false

        private fun files() = bytesOf(process.databasePath)!! to bytesOf("${process.databasePath}-wal")!!

        /** SQLite answers a corruption code here, once. */
        private fun firstCode() {
            if (fired) return
            fired = true
            atFirstCode = files()
            if (second == Second.A_SIBLINGS_MARKER_ALREADY_THERE) aSiblingWritesTheMarker(directory)
            if (gate == Gate.CLOSES_WITH_THE_FIRST_CODE) process.registry.gate.closeAndDrain(0)
            throw corruption()
        }

        private fun betweenTheReadings() {
            // The second reading never runs over a write lock of this process's own, and the
            // connection that failed is closed.
            process.registry.transactionHolder()?.let { problems += "'${it.label}' held a transaction at the second reading" }
            if (!path.aStoresCheckpoint) {
                process.registry.handles().filter { it.hasConnection }.forEach { problems += "'${it.label}' still had a connection at the second reading" }
            }
            try {
                process.sql { connection ->
                    when (sibling) {
                        // Takes the write lock and gives it back: free, or this throws SQLITE_BUSY.
                        Sibling.IDLE -> {
                            connection.exec("BEGIN IMMEDIATE")
                            connection.exec("ROLLBACK")
                        }
                        Sibling.COMMITS_A_ROW -> {
                            connection.exec("INSERT INTO objects (hash, type, data) VALUES (777777, 'sibling', x'01')")
                            aSiblingWrote = true
                        }
                        Sibling.CHECKPOINTS -> {
                            // A connection that has read nothing has not opened the log yet, and
                            // its checkpoint is then a no-op that reports no frames at all.
                            connection.queryLong("SELECT count(*) FROM objects")
                            val copied = connection.checkpoint(SqliteCheckpointMode.TRUNCATE)
                            if (copied.busy || copied.logFrames < 0) problems += "a sibling's checkpoint was kept out: $copied"
                            aSiblingWrote = true
                        }
                    }
                }
            } catch (e: SqliteException) {
                problems += "a sibling was kept out between the readings: ${e.message}"
            }
            when (second) {
                Second.A_SIBLINGS_MARKER_MEANWHILE -> aSiblingWritesTheMarker(directory)
                Second.CORRUPT_AGAIN_AND_THE_MARKER_CANNOT_BE_WRITTEN -> files.failOn = "rename"
                else -> Unit
            }
            if (gate == Gate.CLOSES_BEFORE_THE_SECOND_READING) process.registry.gate.closeAndDrain(0)
        }

        private fun insideTheSecondReading() {
            secondReadings++
            when (second) {
                Second.CORRUPT_AGAIN, Second.CORRUPT_AGAIN_AND_THE_MARKER_CANNOT_BE_WRITTEN ->
                    throw SqliteException(SQLITE_NOTADB, "file is not a database")
                Second.IO_ERROR -> throw SqliteException(SQLITE_IOERR, "disk I/O error")
                Second.BUSY -> throw SqliteException(SQLITE_BUSY, "database is locked")
                Second.A_CORRUPTION_CODE_WITH_PROTECTED_DATA_GONE -> {
                    process.protectedData = false
                    throw corruption()
                }
                Second.PAST_THE_DEADLINE -> now += tight.confirmMaxMs
                else -> Unit
            }
        }

        private fun openVerdict(open: () -> ReplicaOpenResult): String = try {
            when (val result = open()) {
                is ReplicaOpenResult.Ready -> "Ready".also { result.store.close() }
                is ReplicaOpenResult.Corrupt -> "Corrupt"
                else -> "$result"
            }
        } catch (_: StoreCorruptionUnconfirmed) {
            "Unconfirmed"
        } catch (e: ProtectedDataUnavailable) {
            if (second == Second.A_CORRUPTION_CODE_WITH_PROTECTED_DATA_GONE) "Unconfirmed" else "$e"
        } catch (e: StoreUnavailable) {
            "$e"
        }

        private fun accessVerdict(store: SharedReplicaStore, access: () -> Unit): String = try {
            access()
            "returned as if it had succeeded"
        } catch (e: ReplicaAccessFailed) {
            when {
                e.fence == ReplicaFence.CORRUPT && store.fencedBy == ReplicaFence.CORRUPT -> "Corrupt"
                e.fence == ReplicaFence.COMMIT_FAILED && store.fencedBy == ReplicaFence.COMMIT_FAILED -> "Unconfirmed"
                else -> "threw ${e.fence}, fenced ${store.fencedBy}"
            }
        } catch (e: StoreUnavailable) {
            "$e"
        }

        private fun jobVerdict(result: ChunkResult): String = when (val outcome = (result as? ChunkResult.Ended)?.outcome) {
            is ReplicaRunOutcome.Corrupt -> "Corrupt"
            is ReplicaRunOutcome.Stopped -> "Unconfirmed"
            else -> "$result"
        }

        /**
         * The second reading, which is now the walk's run (Q5): what the code's caller saw is not
         * the verdict any more, the walk's is. "Corrupt" once the marker is durable, otherwise
         * "Unconfirmed".
         */
        fun walk(atTheCaller: String): String {
            // Nothing handed off: the caller's answer is the verdict.
            if (handOffs == 0) return atTheCaller
            return when (val outcome = process.walk().run()) {
                is ReplicaWalkOutcome.Confirmed -> "Corrupt"
                ReplicaWalkOutcome.NothingPending -> "nothing was handed to the walk"
                else -> "Unconfirmed"
            }
        }

        /** Arms the first code on [path], runs the operation that meets it, and names what the caller saw. */
        fun run(): String {
            process.registry.onHandOff = { handOffs++ }
            val registry = process.registry
            registry.beforeConfirm = ::betweenTheReadings
            registry.confirmFault = ::insideTheSecondReading
            if (second == Second.PAST_THE_DEADLINE) {
                registry.confirmClock = { now }
                registry.confirmAskEvery = 1
            }
            return when (path) {
                Path.OPEN_BEGIN -> {
                    registry.beginFault = { if (it.kind == ReplicaHandleKind.RUNTIME) firstCode() }
                    openVerdict { process.open() }
                }
                Path.OPEN_WRITE_TRANSACTION -> {
                    val opener = process.opener()
                    opener.writeTransactionFault = ::firstCode
                    openVerdict { opener.open() }
                }
                Path.ACCESS_BEGIN -> process.store().let { store ->
                    this.store = store
                    registry.beginFault = { if (it.kind == ReplicaHandleKind.RUNTIME) firstCode() }
                    accessVerdict(store) { store.getObject(1) }
                }
                Path.ACCESS_EPOCH_READ -> process.store().let { store ->
                    this.store = store
                    store.epochFault = ::firstCode
                    accessVerdict(store) { store.getObject(1) }
                }
                Path.ACCESS_STATEMENT -> process.store().let { store ->
                    this.store = store
                    store.statementFault = { sql -> if (sql == SharedReplicaStore.UPSERT_SQL) firstCode() }
                    accessVerdict(store) { store.databaseLock.withLock { store.storeObject(row(100)) } }
                }
                Path.ACCESS_COMMIT -> process.store().let { store ->
                    this.store = store
                    store.statementFault = { sql -> if (sql == "COMMIT") firstCode() }
                    accessVerdict(store) { store.storeObject(row(100)) }
                }
                Path.STORE_CEILING_CHECKPOINT -> process.store().let { store ->
                    this.store = store
                    process.growLogPast(tight.beginCeilingBytes)
                    store.checkpointer.fault = { firstCode() }
                    // An access that finds the handle fenced on the way in answers "absent".
                    val saw = try {
                        store.getObject(1)
                        "returned"
                    } catch (_: WalResetPending) {
                        "WalResetPending"
                    }
                    when {
                        store.fencedBy == ReplicaFence.CORRUPT && saw == "returned" -> "Corrupt"
                        store.fencedBy == null && saw == "WalResetPending" -> "Unconfirmed"
                        else -> "$saw, fenced ${store.fencedBy}"
                    }
                }
                Path.STORE_CHECKPOINT_AFTER_COMMIT -> process.store().let { store ->
                    this.store = store
                    process.growLogPast(tight.journalSizeLimitBytes)
                    store.checkpointer.fault = { firstCode() }
                    // The commit stands either way: the checkpoint after it is not the access.
                    store.storeObject(row(100))
                    when (store.fencedBy) {
                        ReplicaFence.CORRUPT -> "Corrupt"
                        null -> "Unconfirmed"
                        else -> "fenced ${store.fencedBy}"
                    }
                }
                Path.JOB_BEGIN -> {
                    registry.beginFault = { if (it.kind == ReplicaHandleKind.STORELESS_WIPE) firstCode() }
                    jobVerdict(process.replacement().startWipe("discard"))
                }
                Path.JOB_TRANSACTION -> {
                    val job = process.replacement()
                    job.beforeCommit = { firstCode() }
                    jobVerdict(job.startWipe("discard"))
                }
                Path.JOB_CEILING_CHECKPOINT -> {
                    process.growLogPast(tight.beginCeilingBytes)
                    val job = process.replacement()
                    job.checkpointer.fault = { firstCode() }
                    jobVerdict(job.startWipe("discard"))
                }
                Path.JOB_CHECKPOINT_AFTER_COMMIT -> {
                    process.growLogPast(tight.journalSizeLimitBytes)
                    val job = process.replacement()
                    job.checkpointer.fault = { firstCode() }
                    jobVerdict(job.startWipe("discard"))
                }
            }
        }

        /** Everything but the verdict: the files, the marker, the handle that saw the code, what is left open. */
        fun check(expected: Expected) {
            val registry = process.registry
            if (!fired) problems += "the first code was never met"
            if ((secondReadings > 0) != expected.secondReadingMade) problems += "second readings made: $secondReadings"
            // What the walk saved, if anything: no case here reads past the store's epoch and stops.
            if (fileExists(process.walk().positionPath)) problems += "a walk position was left: ${bytesOf(process.walk().positionPath)!!.decodeToString()}"
            if (fileExists(markerOf(directory)) != expected.markerOnDisk) {
                problems += "replica/CORRUPT ${if (expected.markerOnDisk) "is missing" else "was written"}"
            }
            // From the first code to the verdict this process writes nothing to the database: not
            // through the handle that failed, and not by the second reading.
            if (!aSiblingWrote) {
                val (database, wal) = files()
                if (!atFirstCode!!.first.contentEquals(database)) problems += "objects.db was written after the first code"
                if (!atFirstCode!!.second.contentEquals(wal)) problems += "the -wal was written after the first code"
            }

            registry.beforeConfirm = null
            registry.confirmFault = null
            registry.beginFault = null
            files.failOn = null
            process.protectedData = true
            registry.gate.reopen()

            store?.let { store ->
                val confirmed = expected.verdict == "Corrupt"
                when {
                    // The handle that saw the code never serves again, confirmed or not. It is
                    // fenced COMMIT_FAILED at the hand-off, and the walk's verdict reaches the
                    // next open, never this released handle (Q5); only a sibling's marker found
                    // at the hand-off fences it CORRUPT at once.
                    path.anAccess -> {
                        val atOnce = second == Second.A_SIBLINGS_MARKER_ALREADY_THERE && gate != Gate.CLOSES_WITH_THE_FIRST_CODE
                        val fence = if (atOnce) ReplicaFence.CORRUPT else ReplicaFence.COMMIT_FAILED
                        if (store.fencedBy != fence) problems += "the handle is fenced ${store.fencedBy}"
                        if (store.getObject(1) != null) problems += "a fenced handle answered a read"
                        if (runCatching { store.storeObject(row(5)) }.exceptionOrNull() !is ReplicaReplaced) problems += "a fenced handle took a write"
                    }
                    // A checkpoint's own handle saw it, and that one is gone. This one serves on.
                    !confirmed && !expected.markerOnDisk -> {
                        store.checkpointer.fault = null
                        if (store.getObject(1) != row(1)) problems += "the handle no longer serves after a skipped checkpoint"
                        if (store.fencedBy != null) problems += "the handle is fenced ${store.fencedBy} after a skipped checkpoint"
                    }
                }
                store.close()
            }
            if (registry.handles().isNotEmpty()) problems += "handles left registered: ${registry.handles().map { it.label }}"
            if (!registry.gate.closeAndDrain(0)) problems += "an admission was never given back"
            if (files.openDescriptors.isNotEmpty()) problems += "descriptors left open: ${files.openDescriptors.values}"

            // No marker: nothing durable records the code, and the next open judges the store.
            val next = ReplicaProcess(directory, tight)
            val opened = try {
                when (val result = next.open()) {
                    is ReplicaOpenResult.Ready -> {
                        if (result.store.getObject(1) != row(1)) problems += "the next open does not find the store's rows"
                        result.store.close()
                        "Ready"
                    }
                    is ReplicaOpenResult.Corrupt -> "Corrupt"
                    is ReplicaOpenResult.Updating -> "Updating(${result.job})"
                    else -> "$result"
                }
            } catch (e: StoreUnavailable) {
                "$e"
            }
            val wanted = when {
                expected.markerOnDisk -> "Corrupt"
                // The job's first chunk committed before its checkpoint met the code.
                path == Path.JOB_CHECKPOINT_AFTER_COMMIT -> "Updating(WIPE)"
                else -> "Ready"
            }
            if (opened != wanted) problems += "the next open: expected $wanted, got $opened"
        }
    }

    @Test
    fun everyPathConfirmsACorruptionCodeBeforeItMarksAndAnUnconfirmedOneLeavesNoMarkerAndNothingOpen() {
        // Whatever the process opens lazily, before descriptors are counted.
        Scene(Path.ACCESS_STATEMENT, Second.CORRUPT_AGAIN, Gate.STAYS_OPEN, Sibling.IDLE).run()
        val descriptors = openDescriptorCount()

        val wrong = ArrayList<String>()
        var cases = 0
        for (path in Path.entries) for (second in Second.entries) for (gate in Gate.entries) for (sibling in Sibling.entries) {
            cases++
            val case = "$path / $second / $gate / $sibling"
            val expected = expected(second, gate, path)
            val scene = Scene(path, second, gate, sibling)
            // The caller sees the hand-off; only a sibling's marker found then is a verdict at once.
            val atTheCaller = scene.run()
            val wantedAtTheCaller =
                if (second == Second.A_SIBLINGS_MARKER_ALREADY_THERE && gate != Gate.CLOSES_WITH_THE_FIRST_CODE) "Corrupt" else "Unconfirmed"
            if (atTheCaller != wantedAtTheCaller) wrong += "$case: the caller expected $wantedAtTheCaller, got $atTheCaller"
            if (scene.secondReadings != 0) wrong += "$case: the operation that met the code read the store again itself"
            if (path.aCheckpoint && scene.handOffs != 0) wrong += "$case: a checkpoint's code was handed to the walk"
            val verdict = scene.walk(atTheCaller)
            if (verdict != expected.verdict) wrong += "$case: expected ${expected.verdict}, got $verdict"
            scene.check(expected)
            scene.problems.forEach { wrong += "$case: $it" }
        }
        assertEquals(Path.entries.size * Second.entries.size * Gate.entries.size * Sibling.entries.size, cases)
        assertEquals(emptyList<String>(), wrong.take(40), "${wrong.size} findings in $cases combinations")
        assertEquals(descriptors, openDescriptorCount(), "descriptors open in the process after $cases combinations")
    }

    // ── a file that really is damaged: both readings are SQLite's own ──

    /**
     * A store whose `objects` tree has four pages of garbage in the middle of the file. The
     * pages an open reads (page 1, the schema, `meta`, the stamps) are whole, so it opens; a read
     * of a row on a damaged page is answered `SQLITE_CORRUPT`. Returns the directory and such a row.
     */
    private fun aStoreWithDamagedPages(): Pair<String, Long> {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        (1L..300L).chunked(20).forEach { some -> store.databaseLock.withLock { some.forEach { store.storeObject(row(it, payloadBytes = 2_000)) } } }
        store.close()
        // Everything into objects.db, so the damage is not hidden behind the log. (A connection
        // that has read nothing has not opened the log yet: its checkpoint would be a no-op.)
        process.sql { connection ->
            connection.queryLong("SELECT count(*) FROM objects")
            val copied = connection.checkpoint(SqliteCheckpointMode.TRUNCATE)
            assertTrue(!copied.busy && copied.logFrames >= 0, "$copied")
        }
        val pages = bytesOf(process.databasePath)!!.size / ReplicaLimits.PAGE_BYTES
        assertTrue(pages > 100, "the store has $pages pages")
        val garbage = ByteArray(4 * ReplicaLimits.PAGE_BYTES) { 0x5a }
        val descriptor = open(process.databasePath, O_WRONLY)
        assertTrue(descriptor >= 0)
        try {
            val offset = (pages / 2).toLong() * ReplicaLimits.PAGE_BYTES
            assertEquals(garbage.size.toLong(), pwrite(descriptor, garbage.refTo(0), garbage.size.convert(), offset))
        } finally {
            close(descriptor)
        }
        val damaged = process.sql { connection ->
            (1L..300L).firstOrNull { hash ->
                try {
                    connection.queryLong("SELECT length(data) FROM objects WHERE hash = $hash")
                    false
                } catch (e: SqliteException) {
                    assertEquals(SQLITE_CORRUPT, e.primaryCode)
                    true
                }
            }
        }
        return directory to (damaged ?: error("no row reads as corrupt"))
    }

    @Test
    fun aCodeFromAFileThatReallyIsDamagedIsHandedToTheWalkWhichConfirmsItWithQuickCheckAndMarks() {
        val (directory, damaged) = aStoreWithDamagedPages()
        val process = ReplicaProcess(directory)
        val store = process.store()
        assertEquals(row(1, payloadBytes = 2_000), store.getObject(1), "the undamaged part reads")
        var readings = 0
        process.registry.confirmFault = { readings++ }

        // The access is fenced and hands off: it reads nothing a second time itself (Q5).
        val failed = assertFailsWith<ReplicaAccessFailed> { store.getObject(damaged) }
        assertEquals(ReplicaFence.COMMIT_FAILED, failed.fence, failed.message)
        assertTrue("handed to the confirmation walk" in failed.message.orEmpty(), failed.message)
        assertEquals(0, readings)
        assertFalse(fileExists(markerOf(directory)))
        assertTrue(process.registry.handles().isEmpty())
        // Until the walk ends this app opens no store.
        assertFailsWith<StoreBeingChecked> { process.open() }

        // The walk's run is the second reading.
        val outcome = assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertEquals(1, readings)
        val marker = bytesOf(markerOf(directory))!!.decodeToString()
        assertTrue("confirmed by a second reading (the confirmation walk, run 1): quick_check: " in marker, marker)
        assertTrue("quick_check" in outcome.detail, outcome.detail)
        assertFalse(fileExists(process.walk().positionPath), "a walk that ended leaves no position")
        assertTrue(process.registry.handles().isEmpty())
        assertIs<ReplicaOpenResult.Corrupt>(process.open())
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory).open())
    }

    @Test
    fun aWalkStoppedBeforeItReadTheStoresEpochSavesNothingAndTheNextRunReadsAgain() {
        val (directory, damaged) = aStoreWithDamagedPages()
        val process = ReplicaProcess(directory)
        val store = process.store()
        // The clock passes CONFIRM_MAX as the reading starts, and the reading asks at every step.
        var now = 0L
        process.registry.confirmClock = { now }
        process.registry.confirmAskEvery = 1
        process.registry.confirmFault = { now += process.limits.confirmMaxMs }
        assertEquals(ReplicaFence.COMMIT_FAILED, assertFailsWith<ReplicaAccessFailed> { store.getObject(damaged) }.fence)

        val stopped = assertIs<ReplicaWalkOutcome.NotMade>(process.walk().run())
        assertTrue("CONFIRM_MAX" in stopped.reason, stopped.reason)
        assertFalse(fileExists(markerOf(directory)))
        assertFalse(fileExists(process.walk().positionPath), "no position before the epoch is known")
        assertTrue(process.registry.handles().isEmpty())
        assertTrue(process.registry.gate.closeAndDrain(0), "no admission is left")
        process.registry.gate.reopen()

        // The walk is still owed in this process; with the time, its next run confirms.
        process.registry.confirmFault = null
        process.registry.confirmClock = { 0L }
        assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertTrue(fileExists(markerOf(directory)))
    }

    @Test
    fun aGateThatClosesDuringTheWalksReadingStopsItAndNothingIsMarkedOrSaved() {
        val (directory, damaged) = aStoreWithDamagedPages()
        val process = ReplicaProcess(directory)
        val store = process.store()
        assertEquals(ReplicaFence.COMMIT_FAILED, assertFailsWith<ReplicaAccessFailed> { store.getObject(damaged) }.fence)
        process.registry.confirmAskEvery = 1
        // Quiesce begins once the reading is under way: the gate closes, and waits for it.
        process.registry.confirmFault = { process.registry.gate.closeAndDrain(0) }

        val outcome = assertIs<ReplicaWalkOutcome.Deferred>(process.walk().run())
        assertTrue("the gate closed" in outcome.reason, outcome.reason)
        assertFalse(fileExists(markerOf(directory)))
        assertFalse(fileExists(process.walk().positionPath))
        assertTrue(process.registry.handles().isEmpty(), "quiesce finds no connection of the walk")
        assertTrue(process.registry.gate.closeAndDrain(0), "and the gate drains")

        process.registry.confirmFault = null
        process.registry.gate.reopen()
        assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
    }

    @Test
    fun aKillBetweenTheHandOffAndTheWalksFirstRunLeavesNothingDurableAndTheNextOpenJudgesTheStore() {
        // A healthy store that SQLite called corrupt once: the next process opens and uses it.
        val healthy = aStore()
        val first = ReplicaProcess(healthy, tight)
        val store = first.store()
        store.epochFault = { throw corruption() }
        assertFailsWith<ReplicaAccessFailed> { store.getObject(1) }
        assertTrue(first.walk().isPending())
        // Killed here: the hand-off is in memory only.
        assertFalse(fileExists(markerOf(healthy)))
        assertFalse(fileExists(first.walk().positionPath))
        val after = ReplicaProcess(healthy, tight).store()
        assertEquals(row(1), after.getObject(1))
        after.storeObject(row(50))
        assertNull(after.fencedBy)
        after.close()
        assertFalse(fileExists(markerOf(healthy)))

        // A damaged one: nothing was marked by the process that died, and the next one to meet
        // the damage hands it to its own walk, which confirms it.
        val (directory, damaged) = aStoreWithDamagedPages()
        val doomed = ReplicaProcess(directory).store()
        assertFailsWith<ReplicaAccessFailed> { doomed.getObject(damaged) }
        assertFalse(fileExists(markerOf(directory)))
        val next = ReplicaProcess(directory)
        assertEquals(ReplicaFence.COMMIT_FAILED, assertFailsWith<ReplicaAccessFailed> { next.store().getObject(damaged) }.fence)
        assertIs<ReplicaWalkOutcome.Confirmed>(next.walk().run())
        assertTrue(fileExists(markerOf(directory)))
    }

    // ── the rules of the confirmation itself ──

    @Test
    fun noWalkReadsWhileAHandleOfThisProcessHoldsATransaction() {
        val directory = aStore()
        val process = ReplicaProcess(directory, tight)
        // A caller that forgot to give up its write lock: the reading would run for up to
        // CONFIRM_MAX with every sibling waiting on it.
        val holder = process.registry.register(ReplicaHandleKind.REPLACEMENT, "holder")
        process.registry.connectionFor(holder).exec("BEGIN IMMEDIATE")
        var readings = 0
        process.registry.confirmFault = {
            readings++
            throw corruption()
        }
        val confirmation = ReplicaCorruptionConfirm(process.registry, process.files, ReplicaCorruptMarker(process.files, directory), process.name, testBuild()) { true }
        // The failing handle holds a read transaction (the holder has the write lock).
        val failing = process.registry.register(ReplicaHandleKind.REPLACEMENT, "failing")
        process.registry.connectionFor(failing).let { connection ->
            connection.exec("BEGIN")
            connection.queryLong("SELECT count(*) FROM objects")
        }
        process.registry.lock.lock()
        val outcome = try {
            confirmation.run("database disk image is malformed", failing = failing, alreadyAdmitted = false)
        } finally {
            process.registry.lock.unlock()
        }
        val unconfirmed = assertIs<ReplicaCorruptionConfirmation.Unconfirmed>(outcome)
        assertTrue(unconfirmed.handedOff)
        assertFalse(failing.hasConnection, "the failing connection was closed at the hand-off")
        assertEquals(0, readings, "the hand-off reads nothing")

        assertIs<ReplicaWalkOutcome.Deferred>(process.walk().run(), "a walk does not read over this process's write lock")
        assertEquals(0, readings)
        assertFalse(fileExists(markerOf(directory)))
        process.registry.release(holder)

        assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertEquals(1, readings)
        assertTrue(fileExists(markerOf(directory)))
        process.registry.release(failing)
    }

    // ── a checkpoint's code, repeated by the next checkpoint (B.3 (52)) ──

    /** Who runs the checkpoints that answer the two codes. */
    private enum class Runs {
        /** The runtime handle's opportunistic checkpoint, after a commit. */
        STORE_AFTER_A_COMMIT,

        /** The runtime handle's ceiling checkpoint, before an access may begin. */
        STORE_AT_THE_CEILING,

        /** A job's ceiling checkpoint (B.4 path 9), both times. */
        JOB,

        /** The first by the runtime handle, the second by a job of the same process. */
        STORE_THEN_JOB,
    }

    /** Where in a checkpoint's path SQLite answers. */
    private enum class Where {
        /** The checkpoint itself. */
        THE_CHECKPOINT,

        /** After every frame was copied: the rewind write's transaction, on the same connection. */
        ITS_REWIND_WRITE,
    }

    /** What a checkpoint of this process does between the two codes. */
    private enum class Between {
        NOTHING,

        /** `SQLITE_BUSY`: skipped. It neither counts nor starts the count again. */
        A_CHECKPOINT_KEPT_OUT,

        /** `SQLITE_IOERR`: skipped, and never counted as corruption. */
        AN_IO_ERROR,

        /** A corruption code read while protected data is unavailable is no evidence: skipped. */
        A_CODE_WHILE_THE_PHONE_IS_LOCKED,

        /** A checkpoint that ran to its end: the codes are no longer in a row. */
        A_CHECKPOINT_THAT_RAN,
    }

    /** What the second code meets. */
    private enum class AtTheSecond { NOTHING_IN_THE_WAY, THE_GATE_CLOSED, THE_PHONE_LOCKED, THE_MARKER_CANNOT_BE_WRITTEN }

    private inner class Repeated(val runs: Runs, val where: Where, val between: Between, val second: AtTheSecond) {
        val directory = aStore()
        val files = RecordingFiles()
        val process = ReplicaProcess(directory, tight, files)
        val registry = process.registry
        val problems = ArrayList<String>()
        var store: SharedReplicaStore? = null
        var handOffs = 0

        /** The handle of every checkpoint that was attempted, in order. */
        val checkpoints = ArrayList<ReplicaHandle>()
        private var meets: (() -> Unit)? = null
        private var nextRow = 100L

        private fun met() {
            val what = meets
            meets = null
            what?.invoke()
        }

        private fun hook(checkpointer: ReplicaCheckpointer) {
            checkpointer.fault = {
                checkpoints += registry.handles().single { it.kind == ReplicaHandleKind.CHECKPOINT }
                if (where == Where.THE_CHECKPOINT) met()
            }
            // The checkpoint itself runs and copies every frame; the answer comes after it.
            checkpointer.beforeRewind = { if (where == Where.ITS_REWIND_WRITE) met() }
        }

        /** One checkpoint attempt that meets [what] (null: nothing, it runs). The verdict its caller is left with. */
        private fun attempt(byJob: Boolean, what: (() -> Unit)?): String {
            meets = what
            val attempted = checkpoints.size
            val verdict = if (byJob) {
                process.growLogPast(tight.beginCeilingBytes)
                val job = process.replacement()
                hook(job.checkpointer)
                when (val outcome = (job.startWipe("discard") as? ChunkResult.Ended)?.outcome) {
                    is ReplicaRunOutcome.Corrupt -> "Corrupt"
                    else -> "Unconfirmed"
                }
            } else {
                val store = store ?: process.store().also { store = it; hook(it.checkpointer) }
                if (runs == Runs.STORE_AT_THE_CEILING) {
                    process.growLogPast(tight.beginCeilingBytes)
                    try {
                        store.getObject(1)
                    } catch (_: WalResetPending) {
                    }
                } else {
                    process.growLogPast(tight.journalSizeLimitBytes)
                    store.storeObject(row(nextRow++))
                }
                if (store.fencedBy == ReplicaFence.CORRUPT) "Corrupt" else "Unconfirmed"
            }
            if (checkpoints.size != attempted + 1) problems += "an attempt ran ${checkpoints.size - attempted} checkpoints"
            if (meets != null) problems += "an attempt never reached ${where.name}".also { meets = null }
            return verdict
        }

        fun run(): String {
            registry.onHandOff = { handOffs++ }
            val firstByJob = runs == Runs.JOB
            val secondByJob = runs == Runs.JOB || runs == Runs.STORE_THEN_JOB

            if (attempt(firstByJob) { throw corruption() } != "Unconfirmed") problems += "the first code was believed"
            if (fileExists(markerOf(directory))) problems += "the first code wrote the marker"
            if (handOffs != 0) problems += "the first code was handed to the walk $handOffs times"

            when (between) {
                Between.NOTHING -> Unit
                Between.A_CHECKPOINT_KEPT_OUT -> attempt(firstByJob) { throw SqliteException(SQLITE_BUSY, "database is locked") }
                Between.AN_IO_ERROR -> attempt(firstByJob) { throw SqliteException(SQLITE_IOERR, "disk I/O error") }
                Between.A_CODE_WHILE_THE_PHONE_IS_LOCKED -> {
                    attempt(firstByJob) {
                        process.protectedData = false
                        throw corruption()
                    }
                    process.protectedData = true
                }
                Between.A_CHECKPOINT_THAT_RAN -> attempt(firstByJob, null)
            }
            if (fileExists(markerOf(directory))) problems += "what came between wrote the marker"

            return attempt(secondByJob) {
                when (second) {
                    AtTheSecond.NOTHING_IN_THE_WAY -> Unit
                    AtTheSecond.THE_GATE_CLOSED -> registry.gate.closeAndDrain(0)
                    AtTheSecond.THE_PHONE_LOCKED -> process.protectedData = false
                    AtTheSecond.THE_MARKER_CANNOT_BE_WRITTEN -> files.failOn = "rename"
                }
                throw corruption()
            }
        }

        fun check() {
            val inARow = between != Between.A_CHECKPOINT_THAT_RAN
            // A code read while the phone is locked is not a code: nothing is counted for it.
            val secondCounted = second != AtTheSecond.THE_PHONE_LOCKED
            val confirmed = inARow && second == AtTheSecond.NOTHING_IN_THE_WAY

            if (fileExists(markerOf(directory)) != confirmed) problems += "replica/CORRUPT ${if (confirmed) "is missing" else "was written"}"
            // The repeated code is the second reading, and no checkpoint code goes to the walk.
            if (handOffs != 0) problems += "a checkpoint's code was handed to the walk $handOffs times (B.8.3 (113))"
            if (runs == Runs.STORE_AFTER_A_COMMIT || runs == Runs.STORE_AT_THE_CEILING) {
                val fence = when {
                    confirmed -> ReplicaFence.CORRUPT
                    // Two in a row that could not be marked: no third confirmation on this handle.
                    inARow && secondCounted -> ReplicaFence.COMMIT_FAILED
                    else -> null
                }
                if (store!!.fencedBy != fence) problems += "the runtime handle is fenced ${store!!.fencedBy}, expected $fence"
            }

            registry.gate.reopen()
            process.protectedData = true
            files.failOn = null

            // Whatever stood in the way is gone, and the codes counted so far still count: the
            // next checkpoint of this process that answers one is the confirmation, unless
            // nothing has been counted since a checkpoint ran.
            if (!confirmed) {
                val counted = (if (inARow) 1 else 0) + (if (secondCounted) 1 else 0)
                val third = attempt(byJob = true) { throw corruption() }
                val wantedThird = if (counted >= 1) "Corrupt" else "Unconfirmed"
                if (third != wantedThird) problems += "a third code: expected $wantedThird, got $third"
                if (fileExists(markerOf(directory)) != (counted >= 1)) problems += "after a third code the marker is ${if (counted >= 1) "missing" else "there"}"
            }

            // Every checkpoint had a handle, and so a connection, of its own, released before the next.
            if (checkpoints.toSet().size != checkpoints.size) problems += "two checkpoints shared a handle"
            checkpoints.filterNot { it.isReleased }.forEach { problems += "'${it.label}' was not released" }
            store?.close()
            if (registry.handles().isNotEmpty()) problems += "handles left registered: ${registry.handles().map { it.label }}"
            if (!registry.gate.closeAndDrain(0)) problems += "an admission was never given back"
            if (files.openDescriptors.isNotEmpty()) problems += "descriptors left open: ${files.openDescriptors.values}"
        }
    }

    @Test
    fun aCheckpointsCodeRepeatedByTheNextCheckpointOfThisProcessIsTheConfirmationAndNothingElseIs() {
        val wrong = ArrayList<String>()
        var cases = 0
        for (runs in Runs.entries) for (where in Where.entries) for (between in Between.entries) for (second in AtTheSecond.entries) {
            cases++
            val case = "$runs / $where / $between / $second"
            val scene = Repeated(runs, where, between, second)
            val verdict = scene.run()
            val expected = if (between != Between.A_CHECKPOINT_THAT_RAN && second == AtTheSecond.NOTHING_IN_THE_WAY) "Corrupt" else "Unconfirmed"
            if (verdict != expected) wrong += "$case: expected $expected, got $verdict"
            scene.check()
            scene.problems.forEach { wrong += "$case: $it" }
        }
        assertEquals(Runs.entries.size * Where.entries.size * Between.entries.size * AtTheSecond.entries.size, cases)
        assertEquals(emptyList<String>(), wrong.take(40), "${wrong.size} findings in $cases combinations")
    }

    @Test
    fun theCountOfCheckpointCodesIsThisProcesssOwnAndANewProcessStartsOver() {
        val directory = aStore()
        fun aCheckpointCode(process: ReplicaProcess): SharedReplicaStore {
            val store = process.store()
            process.growLogPast(tight.journalSizeLimitBytes)
            store.checkpointer.fault = { throw corruption() }
            store.storeObject(row(process.sql { it.queryLong("SELECT max(hash) FROM objects") } + 1))
            return store
        }
        // One code in one process, one in another: neither has asked twice, and a sibling's
        // answer is not this process's second reading. (After a restart it is the same.)
        val first = ReplicaProcess(directory, tight)
        assertNull(aCheckpointCode(first).fencedBy)
        assertEquals(1, first.registry.checkpointCodesInARow)
        val other = ReplicaProcess(directory, tight)
        val store = aCheckpointCode(other)
        assertNull(store.fencedBy)
        assertFalse(fileExists(markerOf(directory)))

        // The second process's own second code, on another connection, is.
        store.storeObject(row(9_000))
        assertEquals(ReplicaFence.CORRUPT, store.fencedBy)
        val marker = bytesOf(markerOf(directory))!!.decodeToString()
        assertTrue("repeated by the next checkpoint on another connection (2 in a row)" in marker, marker)
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory, tight).open())
    }

    @Test
    fun anUnconfirmedCodeUnderAnOpenOrAWipeCallIsARetryWithNoMarker() {
        // The store is behind this build: the open's write transaction is a job's.
        val behind = aStore()
        val opening = ReplicaProcess(behind, tight)
        var fired = false
        opening.registry.beginFault = {
            if (it.kind == ReplicaHandleKind.REPLACEMENT && !fired) {
                fired = true
                throw corruption()
            }
        }
        assertFailsWith<StoreUnavailable> { opening.open(testBuild(core = 23)) }
        assertFalse(fileExists(markerOf(behind)))
        assertTrue(opening.registry.handles().isEmpty())
        // The code went to the walk: this app opens nothing until it ends (Q5), and then retries.
        assertFailsWith<StoreBeingChecked> { opening.open(testBuild(core = 23)) }
        assertIs<ReplicaWalkOutcome.Clean>(opening.walk().run())
        assertIs<ReplicaOpenResult.Updating>(opening.open(testBuild(core = 23)), "the retry starts the migration")

        val wiped = aStore()
        val wiping = ReplicaProcess(wiped, tight)
        fired = false
        wiping.registry.beginFault = {
            if (it.kind == ReplicaHandleKind.STORELESS_WIPE && !fired) {
                fired = true
                throw corruption()
            }
        }
        assertFailsWith<StoreUnavailable> { wiping.opener().wipe("discard") }
        assertFalse(fileExists(markerOf(wiped)))
        assertTrue(wiping.registry.handles().isEmpty())
        assertTrue(wiping.opener().wipe("discard"), "the retry wipes")
    }

    @Test
    fun aReadOnlyReadingGivesItsAdmissionBackWhenItsConnectionCannotBeClosed() {
        val directory = aStore()
        val process = ReplicaProcess(directory, tight)
        process.registry.closeFault = { if (it.kind == ReplicaHandleKind.CLASSIFY) throw SqliteException(SQLITE_IOERR, "injected close failure") }
        assertFailsWith<StoreUnavailable> { process.open() }
        assertTrue(process.registry.isUnusable)
        // The latch holds the handle; the gate must not hold an admission for it as well, or
        // quiesce would wait out its whole timeout for a reading that is long over.
        assertTrue(process.registry.gate.closeAndDrain(0), "the classification's admission was given back")
        process.registry.gate.reopen()
        process.registry.closeFault = null
        assertTrue(process.registry.retryUnusable())
        assertTrue(process.registry.handles().isEmpty())
    }

    @Test
    fun theSecondReadingsBoundIsAReadMarksHold() {
        assertEquals(1_000, ReplicaLimits().confirmMaxMs)
        assertFailsWith<IllegalArgumentException> { ReplicaLimits(confirmMaxMs = 0) }
        assertFailsWith<IllegalArgumentException> { ReplicaLimits(holdMaxMs = 800, confirmMaxMs = 801) }
    }
}
