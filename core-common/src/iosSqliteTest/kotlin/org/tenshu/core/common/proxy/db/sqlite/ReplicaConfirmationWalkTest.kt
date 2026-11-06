@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pwrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The confirmation walk (Q5, Appendix B.8): a corruption code handed off by the operation that met
 * it, read in runs of at most `CONFIRM_MAX` that carry their position across runs and processes,
 * ending in the marker or clean. Every store here is made up; "large" means larger than one run
 * reaches, which the tests arrange with a clock that a run's own steps move.
 */
class ReplicaConfirmationWalkTest {

    private fun corruption() = SqliteException(SQLITE_CORRUPT, "database disk image is malformed")

    private fun markerOf(directory: String) = "$directory/${ReplicaCorruptMarker.NAME}"

    /** A closed store of [rows] rows of [payload] bytes, everything checkpointed into `objects.db`. */
    private fun aStore(rows: Int = 300, payload: Int = 2_000, extra: List<Long> = emptyList()): String {
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        val store = process.store()
        ((1L..rows.toLong()) + extra).chunked(20).forEach { some -> store.databaseLock.withLock { some.forEach { store.storeObject(row(it, payloadBytes = payload)) } } }
        store.close()
        process.sql { connection ->
            connection.queryLong("SELECT count(*) FROM objects")
            val copied = connection.checkpoint(SqliteCheckpointMode.TRUNCATE)
            assertTrue(!copied.busy && copied.logFrames >= 0, "$copied")
        }
        return directory
    }

    /** [aStore] with four pages of garbage in the middle of the file; the damaged row it found. */
    private fun aDamagedStore(): Pair<String, Long> {
        val directory = aStore()
        val path = "$directory/${ReplicaOpener.DATABASE_NAME}"
        val pages = bytesOf(path)!!.size / ReplicaLimits.PAGE_BYTES
        val garbage = ByteArray(4 * ReplicaLimits.PAGE_BYTES) { 0x5a }
        val descriptor = open(path, O_WRONLY)
        assertTrue(descriptor >= 0)
        try {
            assertEquals(garbage.size.toLong(), pwrite(descriptor, garbage.refTo(0), garbage.size.convert(), (pages / 2).toLong() * ReplicaLimits.PAGE_BYTES))
        } finally {
            close(descriptor)
        }
        val damaged = ReplicaProcess(directory).sql { connection ->
            (1L..300L).firstOrNull { hash ->
                try {
                    connection.queryLong("SELECT length(data) FROM objects WHERE hash = $hash")
                    false
                } catch (e: SqliteException) {
                    true
                }
            }
        }
        return directory to (damaged ?: error("no row reads as corrupt"))
    }

    /** A code SQLite answers once at the access's first statement: the hand-off, on any store. */
    private fun handOff(process: ReplicaProcess) {
        val store = process.store()
        store.epochFault = { throw corruption() }
        assertEquals(ReplicaFence.COMMIT_FAILED, assertFailsWith<ReplicaAccessFailed> { store.getObject(1) }.fence)
        store.close()
    }

    /**
     * Makes every run "large": the clock moves [stepMs] at every look, so a run ends after about
     * `CONFIRM_MAX / stepMs` looks; and the whole-file check is stopped at once, so the walk goes
     * on ranged. Returns the units the reading began, in order.
     */
    /** The clock of [slowRuns]; [endTheRun] stops the run under way at its next look. */
    private class Slow(private val confirmMaxMs: Int) {
        var now = 0L
        var moving = false
        val begun = ArrayList<ReplicaWalkUnitKind>()
        fun endTheRun() {
            now += confirmMaxMs
        }
    }

    private fun ReplicaProcess.slowRuns(stepMs: Long = 50, askEvery: Int = 5, onRun: () -> Unit = {}): Slow {
        // The clock moves only once the units begin: the classification read before them is
        // the same few hundred steps in every run, and a run is never stopped there by this.
        val slow = Slow(limits.confirmMaxMs)
        registry.confirmClock = {
            if (slow.moving) slow.now += stepMs
            slow.now
        }
        registry.confirmAskEvery = askEvery
        registry.beforeConfirm = {
            slow.moving = false
            onRun()
        }
        walk().atUnit = { unit ->
            slow.moving = true
            slow.begun += unit.kind
            if (unit.kind == ReplicaWalkUnitKind.WHOLE) slow.endTheRun()
        }
        return slow
    }

    private fun positionOn(process: ReplicaProcess): ReplicaWalkPosition? =
        bytesOf(process.walk().positionPath)?.let { ReplicaWalkPosition.decode(it, process.name) }

    // ── the position file ──

    @Test
    fun aPositionDecodesOnlyWhenItIsWholeAndThisAppsAndThisLayoutsAndOtherwiseIsNoPosition() {
        val rows = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.ROWS }
        val index = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val atRows = ReplicaWalkPosition(epoch = -42, file = "16777232:9001", unit = rows, afterKey = Long.MIN_VALUE, afterType = null, runs = 7, error = "first code\nline")
        val atIndex = ReplicaWalkPosition(epoch = null, file = null, unit = index, afterKey = 9, afterType = hexOf("tenshu/fäke".encodeToByteArray()), runs = 1, error = "e")
        // A type that is not UTF-8 survives byte for byte (B.8.3 (112)).
        val malformed = atIndex.copy(afterType = "4669806c65")
        // An index key of every storage class survives in its class (B.9 (122)).
        val ofEveryClass = listOf(
            atIndex.copy(afterClass = ReplicaWalkKeyClass.NULL, afterType = ""),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.INTEGER, afterType = Long.MIN_VALUE.toString()),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.REAL, afterType = (-0.5).toRawBits().toULong().toString(16).padStart(16, '0')),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.BLOB, afterType = "00ff"),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.TEXT, afterType = ""),
        )
        for (position in listOf(atRows, atIndex, malformed) + ofEveryClass) assertEquals(position, ReplicaWalkPosition.decode(position.encode("app"), "app"))
        // The start of the next unit, reached from inside an index of any class, carries no class:
        // a position saved in the schema unit after the index decodes (B.9 (130)).
        val schema = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.SCHEMA }
        for (position in ofEveryClass) {
            val next = position.atUnitStart(schema)
            assertNull(next.afterClass)
            assertEquals(next, ReplicaWalkPosition.decode(next.encode("app"), "app"))
        }
        // A key that is not one of its class is no position.
        for (bad in listOf(
            atIndex.copy(afterClass = ReplicaWalkKeyClass.INTEGER, afterType = "00ff"),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.NULL, afterType = "00"),
            atIndex.copy(afterClass = ReplicaWalkKeyClass.REAL, afterType = "00ff"),
            atIndex.copy(afterClass = null),
        )) assertNull(ReplicaWalkPosition.decode(bad.encode("app"), "app"), "$bad")

        val bytes = atRows.encode("app")
        assertNull(ReplicaWalkPosition.decode(bytes, "another app"), "another app's file")
        assertNull(ReplicaWalkPosition.decode(bytes.copyOf(bytes.size - 7), "app"), "torn")
        assertNull(ReplicaWalkPosition.decode(ByteArray(0), "app"), "empty")
        for (at in bytes.indices) {
            val flipped = bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x04).toByte() }
            assertNull(ReplicaWalkPosition.decode(flipped, "app"), "one bit flipped at $at")
        }
        // Each field is checked, not only the checksum: a file that says another unit is no position.
        fun rewritten(edit: (String) -> String): ByteArray {
            val text = bytes.decodeToString()
            val body = edit(text.substring(0, text.lastIndexOf("check=")))
            var hash = -0x340d631b7bdddcdbL
            for (b in body.encodeToByteArray()) {
                hash = hash xor (b.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            return (body + "check=" + hash.toULong().toString(16) + "\n").encodeToByteArray()
        }
        assertEquals(atRows, ReplicaWalkPosition.decode(rewritten { it }, "app"), "the test's own checksum is the codec's")
        assertNull(ReplicaWalkPosition.decode(rewritten { it.replace("unit=$rows\n", "unit=${rows + 1}\n") }, "app"), "a unit index that names another unit")
        assertNull(ReplicaWalkPosition.decode(rewritten { it.replace("unit=$rows\n", "unit=99\n") }, "app"), "a unit past the list")
        // Two units of one kind, so nothing but the unit's own name tells them apart.
        val tables = ReplicaWalkUnit.all.withIndex().filter { it.value.kind == ReplicaWalkUnitKind.TABLE }.map { it.index }
        val atTable = ReplicaWalkPosition(epoch = 1, file = "1:2", unit = tables[0], afterKey = null, afterType = null, runs = 1, error = "e").encode("app").decodeToString()
        val shifted = atTable.substring(0, atTable.lastIndexOf("check=")).replace("unit=${tables[0]}\n", "unit=${tables[1]}\n")
        var h = -0x340d631b7bdddcdbL
        for (b in shifted.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        assertNull(ReplicaWalkPosition.decode((shifted + "check=" + h.toULong().toString(16) + "\n").encodeToByteArray(), "app"), "a unit index whose name is another table's")
        assertNull(ReplicaWalkPosition.decode(rewritten { it.replace("layout=${ReplicaSchema.LAYOUT}\n", "layout=${ReplicaSchema.LAYOUT + 1}\n") }, "app"), "another layout")
        assertNull(ReplicaWalkPosition.decode(rewritten { it.replace("format=${ReplicaWalkPosition.FORMAT}\n", "format=${ReplicaWalkPosition.FORMAT + 1}\n") }, "app"), "another format")
        assertNull(ReplicaWalkPosition.decode(rewritten { it.replace("units=${ReplicaWalkUnit.fingerprint}\n", "units=0\n") }, "app"), "another list of units")
        assertNull(ReplicaWalkPosition.decode(rewritten { it + "afterKey=3\n" }, "app"), "a field twice")
    }

    // ── the walk across runs ──

    @Test
    fun aDamagedStoreTooLargeForOneRunIsWalkedAcrossRunsAndProcessesAndItsDamageConfirmed() {
        val (directory, damaged) = aDamagedStore()
        val first = ReplicaProcess(directory)
        val store = first.store()
        assertFailsWith<ReplicaAccessFailed> { store.getObject(damaged) }
        first.slowRuns()

        // A few runs in this process, each further than the last and each saved.
        val reached = ArrayList<ReplicaWalkPosition>()
        repeat(3) {
            val outcome = assertIs<ReplicaWalkOutcome.Progressed>(first.walk().run(), "run ${it + 1}")
            assertTrue(outcome.advanced && outcome.saved, "$outcome")
            assertEquals(outcome.position, positionOn(first), "what was saved is what was reached")
            reached.lastOrNull()?.let { before -> assertTrue(outcome.position.isPast(before), "${outcome.position} is not past $before") }
            reached += outcome.position
        }
        assertFalse(fileExists(markerOf(directory)))

        // The process dies. The next one is fenced by the position alone, and goes on from it.
        val next = ReplicaProcess(directory)
        assertFailsWith<StoreBeingChecked> { next.open() }
        next.slowRuns()
        val resumed = assertIs<ReplicaWalkOutcome.Progressed>(next.walk().run())
        assertTrue(resumed.position.isPast(reached.last()), "${resumed.position} does not go on from ${reached.last()}")
        assertEquals(reached.last().runs + 1, resumed.position.runs)

        val outcome = next.walkToTheEnd()
        val confirmed = assertIs<ReplicaWalkOutcome.Confirmed>(outcome)
        assertFalse("quick_check: " == confirmed.detail.take(13), "it was the ranged walk that found it: ${confirmed.detail}")
        assertTrue(fileExists(markerOf(directory)))
        assertFalse(fileExists(next.walk().positionPath), "an ended walk leaves no position")
        assertIs<ReplicaOpenResult.Corrupt>(next.open())
        assertTrue(next.registry.handles().isEmpty())
        assertTrue(next.registry.gate.closeAndDrain(0))
    }

    @Test
    fun aHealthyStoreTooLargeForOneRunIsReadWholeAcrossRunsWhileASiblingWritesAndTheFenceLifts() {
        val directory = aStore()
        val process = ReplicaProcess(directory)
        handOff(process)
        val begun = process.slowRuns().begun
        var runs = 0
        var previous: ReplicaWalkPosition? = null
        var outcome: ReplicaWalkOutcome
        while (true) {
            outcome = process.walk().run()
            if (outcome !is ReplicaWalkOutcome.Progressed) break
            runs++
            previous?.let { assertTrue(outcome.position.isPast(it), "the walk went back: ${outcome.position} after $it") }
            previous = outcome.position
            // A sibling commits and checkpoints between runs: the walk goes on, it does not start over.
            if (runs % 3 == 0) {
                process.sql { connection ->
                    connection.exec("INSERT INTO objects (hash, type, data) VALUES (${100_000 + runs}, 'sibling', zeroblob(3000))")
                    connection.checkpoint(SqliteCheckpointMode.PASSIVE)
                }
            }
            assertFailsWith<StoreBeingChecked>("the fence holds across runs") { process.open() }
            if (runs > 2_000) fail("the walk does not end")
        }
        assertIs<ReplicaWalkOutcome.Clean>(outcome)
        assertTrue(runs > 5, "the store was meant to need several runs: $runs")
        // Every unit was read, the whole-file check first and once.
        assertEquals(ReplicaWalkUnitKind.WHOLE, begun.first())
        assertEquals(1, begun.count { it == ReplicaWalkUnitKind.WHOLE })
        assertEquals(ReplicaWalkUnit.all.map { it.kind }.toSet(), begun.toSet())
        assertEquals(ReplicaWalkUnitKind.SCHEMA, begun.last())
        assertFalse(fileExists(process.walk().positionPath))
        assertFalse(fileExists(markerOf(directory)))
        assertFalse(process.walk().isPending())
        // The fence is lifted: the open judges the store as any other.
        assertIs<ReplicaOpenResult.Ready>(process.open()).store.close()
    }

    @Test
    fun anEpochThatMovedBetweenRunsStartsTheWalkOver() {
        val directory = aStore()
        val process = ReplicaProcess(directory)
        handOff(process)
        val begun = process.slowRuns().begun
        repeat(4) { assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run()) }
        assertTrue(positionOn(process)!!.unit > 0)
        // A wipe or a migration by a sibling moves the epoch.
        process.sql { it.exec("UPDATE meta SET v = v + 1 WHERE k = 'epoch'") }
        begun.clear()
        val restarted = assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run())
        assertEquals(ReplicaWalkUnitKind.WHOLE, begun.first(), "the walk starts over on another epoch: $begun")
        assertEquals(1, restarted.position.runs, "and counts its runs from one")
    }

    @Test
    fun aTornOrForeignPositionFileIsNoPositionAndTheWalkStartsFromTheBeginning() {
        val directory = aStore(rows = 20)
        // Only a position from this store and of this app is believed.
        val strangers = listOf(
            "not a position".encodeToByteArray(),
            ReplicaWalkPosition(null, null, ReplicaWalkUnit.all.lastIndex, null, null, 3, "e").encode("another app"),
        )
        for (stranger in strangers) {
            val process = ReplicaProcess(directory)
            PosixReplicaFiles.createAndWrite(process.walk().positionPath + ".tmp", stranger).let(PosixReplicaFiles::closeDescriptor)
            PosixReplicaFiles.rename(process.walk().positionPath + ".tmp", process.walk().positionPath)
            assertFailsWith<StoreBeingChecked>("a file that is there is a walk owed, readable or not") { process.open() }
            val begun = ArrayList<ReplicaWalkUnitKind>()
            process.walk().atUnit = { begun += it.kind }
            assertIs<ReplicaWalkOutcome.Clean>(process.walk().run())
            assertEquals(listOf(ReplicaWalkUnitKind.WHOLE), begun)
            assertFalse(fileExists(process.walk().positionPath))
        }

        // A whole position of this app at the last unit, from another epoch, from another file with
        // the same epoch (an older copy restored), or with either unknown, is read from the start too.
        val process = ReplicaProcess(directory)
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        val file = PosixReplicaFiles.identity(process.databasePath)
        assertNotNull(file)
        for ((stalePosition, why) in listOf(
            ReplicaWalkPosition(epoch + 1, file, ReplicaWalkUnit.all.lastIndex, null, null, 3, "e") to "another epoch",
            ReplicaWalkPosition(epoch, "0:0", ReplicaWalkUnit.all.lastIndex, null, null, 3, "e") to "another file",
            ReplicaWalkPosition(epoch, null, ReplicaWalkUnit.all.lastIndex, null, null, 3, "e") to "an unknown file",
            ReplicaWalkPosition(null, file, ReplicaWalkUnit.all.lastIndex, null, null, 3, "e") to "an unknown epoch",
        )) {
            PosixReplicaFiles.createAndWrite(process.walk().positionPath, stalePosition.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
            val begun = ArrayList<ReplicaWalkUnitKind>()
            process.walk().atUnit = { begun += it.kind }
            assertIs<ReplicaWalkOutcome.Clean>(process.walk().run(), why)
            assertEquals(listOf(ReplicaWalkUnitKind.WHOLE), begun, why)
        }
        // And the same position on the same store is believed: the walk reads its last unit only.
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, ReplicaWalkPosition(epoch, file, ReplicaWalkUnit.all.lastIndex, null, null, 3, "e").encode(process.name))
            .let(PosixReplicaFiles::closeDescriptor)
        val begun = ArrayList<ReplicaWalkUnitKind>()
        process.walk().atUnit = { begun += it.kind }
        assertIs<ReplicaWalkOutcome.Clean>(process.walk().run())
        assertEquals(listOf(ReplicaWalkUnitKind.SCHEMA), begun)
    }

    @Test
    fun aRunStoppedByTheGateSavesNothingAndARunCannotSaveAPositionAheadOfWhatItRead() {
        val directory = aStore()
        val process = ReplicaProcess(directory)
        handOff(process)
        process.slowRuns()
        assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run())
        assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run())
        val saved = bytesOf(process.walk().positionPath)!!

        // The gate closes in the middle of the next run's reading.
        var looks = 0
        process.walk().onReached = { if (++looks == 3) process.registry.gate.closeAndDrain(0) }
        assertIs<ReplicaWalkOutcome.Deferred>(process.walk().run())
        assertTrue(saved.contentEquals(bytesOf(process.walk().positionPath)!!), "nothing was written once the gate had closed")
        process.registry.gate.reopen()
        process.walk().onReached = null

        // What a run saves is never past the last key SQLite returned to it.
        val keys = ArrayList<Long>()
        process.walk().onReached = { position -> position.afterKey?.let { keys += it } }
        val outcome = assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run())
        outcome.position.afterKey?.let { assertEquals(keys.last(), it) }
    }

    @Test
    fun aRowAtTheSmallestKeyIsWalkedLikeAnyOther() {
        val directory = aStore(rows = 30, payload = 100, extra = listOf(Long.MIN_VALUE))
        val process = ReplicaProcess(directory)
        handOff(process)
        process.slowRuns()
        val keys = HashSet<Long>()
        process.walk().onReached = { position ->
            if (ReplicaWalkUnit.all.getOrNull(position.unit)?.kind == ReplicaWalkUnitKind.ROWS) position.afterKey?.let { keys += it }
        }
        assertIs<ReplicaWalkOutcome.Clean>(process.walkToTheEnd())
        assertTrue(Long.MIN_VALUE in keys, "the row at Long.MIN_VALUE was never read")
        assertEquals((1L..30L).toSet() + Long.MIN_VALUE, keys)
    }

    @Test
    fun theKeysetsAreRangeScansOfTheirOwnTreeAndSortNothing() {
        val directory = aStore(rows = 5, payload = 100)
        fun plan(sql: String): String = ReplicaProcess(directory).sql { connection ->
            val lines = ArrayList<String>()
            connection.query("EXPLAIN QUERY PLAN $sql") { lines += it.getText(3) }
            lines.joinToString(" | ")
        }
        val rows = plan(ReplicaConfirmationWalk.rowsSql(ReplicaSchema.OBJECTS, resume = true).replace("?", "1"))
        assertTrue("SEARCH objects USING INTEGER PRIMARY KEY (rowid>?)" in rows, rows)
        assertFalse("TEMP B-TREE" in rows, rows)
        for (index in ReplicaSchema.indexes) {
            val column = index.columns.single()
            fun bound(sql: String, key: String) = sql.replace("?1", key).replace("?2", "1")
            for (keyClass in listOf(ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB)) {
                val (sameType, laterTypes) = ReplicaConfirmationWalk.indexSql(index.name, keyClass)
                val key = if (keyClass == ReplicaWalkKeyClass.TEXT) "'a'" else "x'01'"
                // Both columns of the key are sought, not the first alone with the rowid filtered.
                val first = plan(bound(sameType, key))
                assertTrue("COVERING INDEX ${index.name} ($column=? AND rowid>?)" in first, first)
                assertFalse("TEMP B-TREE" in first, first)
                val second = plan(bound(laterTypes, key))
                assertTrue("COVERING INDEX ${index.name} ($column>?)" in second, second)
                assertFalse("TEMP B-TREE" in second, second)
            }
            // The NULL and numeric classes resume on one plain scan of the index in its own order,
            // with no range the planner could change (B.9 (129)): they sort nothing.
            for (keyClass in listOf(ReplicaWalkKeyClass.NULL, ReplicaWalkKeyClass.INTEGER, ReplicaWalkKeyClass.REAL)) {
                val p = plan(bound(ReplicaConfirmationWalk.indexSql(index.name, keyClass).single(), "1"))
                assertTrue("SCAN objects USING COVERING INDEX ${index.name}" in p, "$keyClass: $p")
                assertFalse("TEMP B-TREE" in p, "$keyClass: $p")
            }
            val fresh = plan(ReplicaConfirmationWalk.indexSql(index.name, null).single())
            assertTrue("COVERING INDEX ${index.name}" in fresh && "TEMP B-TREE" !in fresh, fresh)
        }
        // The two named checks the walk relies on exist in this SQLite and answer "ok" on a healthy store.
        ReplicaProcess(directory).sql { connection ->
            assertNull(quickCheckFinding(connection, "sqlite_schema"))
            assertNull(quickCheckFinding(connection, ReplicaSchema.META))
        }
    }

    // ── the admission and the worker ──

    private class Assertions(var remainingMs: Long = 30_000) : ProtectionAssertions {
        private val lock = NSRecursiveLock()
        var begun = 0
        var ended = 0
        override fun begin(reason: String, onExpired: () -> Unit): Long? {
            lock.lock()
            try {
                return (++begun).toLong()
            } finally {
                lock.unlock()
            }
        }

        override fun end(id: Long) {
            lock.lock()
            try {
                ended++
            } finally {
                lock.unlock()
            }
        }

        override fun backgroundTimeRemainingMs(): Long = remainingMs
    }

    /** A dispatch onto a real background queue, and a wait for it. */
    private class Background {
        private val condition = NSCondition()
        private var running = 0
        val dispatch: (() -> Unit) -> Unit = { block ->
            condition.lock()
            running++
            condition.unlock()
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
                try {
                    block()
                } finally {
                    condition.lock()
                    running--
                    condition.broadcast()
                    condition.unlock()
                }
            }
        }

        fun await(timeoutMs: Long = 30_000) {
            condition.lock()
            try {
                val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutMs / 1000.0)
                while (running > 0) if (!condition.waitUntilDate(deadline)) fail("the worker still runs after $timeoutMs ms")
            } finally {
                condition.unlock()
            }
        }
    }

    @Test
    fun theWorkerRunsAHandedOffWalkToItsEndOffTheAccessUnderOneJobTokenPerRun() {
        val (directory, damaged) = aDamagedStore()
        val process = ReplicaProcess(directory)
        val assertions = Assertions()
        val protection = ReplicaProtection(assertions)
        protection.attach(process.registry)
        protection.install(isForeground = true)
        val background = Background()
        val outcomes = ArrayList<ReplicaWalkOutcome>()
        val lock = NSRecursiveLock()
        val worker = ReplicaWorker(protection, background.dispatch) { _, outcome ->
            lock.lock()
            outcomes += outcome
            lock.unlock()
        }
        worker.attach(process.registry)
        val store = process.store()
        var runs = 0
        process.slowRuns { runs++ }

        assertFailsWith<ReplicaAccessFailed> { store.getObject(damaged) }
        background.await()
        lock.lock()
        val ended = outcomes.toList()
        lock.unlock()
        assertIs<ReplicaWalkOutcome.Confirmed>(ended.single(), "$ended")
        assertTrue(runs > 1, "the walk needed several runs: $runs")
        assertEquals(runs, assertions.begun, "one job token per run")
        assertEquals(assertions.begun, assertions.ended, "each released")
        assertTrue(fileExists(markerOf(directory)))
        assertIs<ReplicaOpenResult.Corrupt>(process.open())
    }

    @Test
    fun aRunIsAdmittedOnlyWithItsTimeAndAJobTokenIsNeverTakenOnTheMainThread() {
        val directory = aStore(rows = 20)
        val process = ReplicaProcess(directory)
        handOff(process)
        val assertions = Assertions(remainingMs = 0)
        val protection = ReplicaProtection(assertions)
        protection.attach(process.registry)
        // Launched in the background: a wake's token is what protects.
        protection.install(isForeground = false)
        val token = assertNotNull(protection.acquire("a wake"))
        // In a wake with less than CONFIRM_MAX, R, the marker's syncs and the margin left: deferred.
        val required = ReplicaAdmissionBudget.requiredMs(ReplicaOperation.CONFIRM_WALK, process.limits, opensConnection = true)
        assertEquals(1_000L + 20 + 200 + 2 * 500 + 2_000, required)
        assertions.remainingMs = required - 1
        val deferred = assertIs<ReplicaWalkOutcome.Deferred>(process.walk().run())
        assertTrue("CONFIRM_WALK" in deferred.reason, deferred.reason)
        assertTrue(process.registry.handles().isEmpty(), "nothing was opened")
        assertions.remainingMs = required
        assertIs<ReplicaWalkOutcome.Clean>(process.walk().run())
        token.release()

        // A worker whose dispatch lands on the main thread is refused its token (86), and says so.
        val again = ReplicaProcess(aStore(rows = 5))
        handOff(again)
        val onMain = ReplicaProtection(Assertions(), isMainThread = { true })
        onMain.attach(again.registry)
        onMain.install(isForeground = true)
        var outcome: ReplicaWalkOutcome? = null
        ReplicaWorker(onMain, { it() }) { _, it -> outcome = it }.schedule(again.walk())
        val refused = assertIs<ReplicaWalkOutcome.NotMade>(outcome)
        assertTrue("main thread" in refused.reason, refused.reason)
        assertTrue(again.walk().isPending(), "still owed")
    }

    @Test
    fun anOpenRefusedForAnOwedWalkSchedulesIt() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        val queued = ArrayList<() -> Unit>()
        val worker = ReplicaWorker(null, { queued += it })
        worker.attach(process.registry)
        handOff(process)
        assertEquals(1, queued.size, "the hand-off scheduled the walk")
        assertFailsWith<StoreBeingChecked> { process.open() }
        assertEquals(1, queued.size, "a walk already dispatched is not dispatched twice")
        queued.removeAt(0).invoke()
        // The open while it was dispatched asked for a run again; the dispatch ran it before ending.
        assertTrue(queued.isEmpty())
        assertFalse(process.walk().isPending())
        assertIs<ReplicaOpenResult.Ready>(process.open()).store.close()
    }

    // ── every entry reached: malformed TEXT, every storage class, unknown layouts ──

    /** `Fi\x80le`: a type that is not UTF-8, which a decode would turn into `Fi\uFFFDle`. */
    private val malformedType = "4669806c65"

    @Test
    fun everyRowAndEveryIndexEntryIsReachedAcrossRunsWithAMalformedTypeSiblingWritesBehindAndATypeEndingAtARunBoundary() {
        // 120 rows at 10, 20, …, 1200: forty of type "A", forty of a type that is not UTF-8, forty of "Fz".
        val directory = tempReplica()
        val process = ReplicaProcess(directory)
        process.store().close()
        process.sql { connection ->
            for (i in 1..120) {
                val type = when {
                    i <= 40 -> "'A'"
                    i <= 80 -> "CAST(x'$malformedType' AS TEXT)"
                    else -> "'Fz'"
                }
                connection.exec("INSERT INTO objects (hash, type, data) VALUES (${i * 10}, $type, zeroblob(300))")
            }
        }
        val initial = (1..120).map { it * 10L }.toSet()
        handOff(process)
        val slow = process.slowRuns()
        val rowsUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.ROWS }
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val rows = HashSet<Long>()
        val entries = HashSet<Pair<String, Long>>()
        var indexAtItsStart: Set<Pair<String, Long>>? = null
        var stoppedAtTheTypesEnd = false
        var lastOfA: Long? = null
        var malformedReached = 0
        fun indexNow(): Set<Pair<String, Long>> = process.sql { connection ->
            val all = HashSet<Pair<String, Long>>()
            connection.query("SELECT hex(CAST(type AS BLOB)), hash FROM objects") { all += it.getText(0).lowercase() to it.getLong(1) }
            all
        }
        val atUnit = process.walk().atUnit
        process.walk().atUnit = { unit ->
            if (unit.kind == ReplicaWalkUnitKind.INDEX && indexAtItsStart == null) {
                indexAtItsStart = indexNow()
                lastOfA = indexAtItsStart!!.filter { it.first == hexOf("A".encodeToByteArray()) }.maxOf { it.second }
            }
            atUnit?.invoke(unit)
        }
        process.walk().onReached = { position ->
            when (position.unit) {
                rowsUnit -> position.afterKey?.let { rows += it }
                indexUnit -> if (position.afterKey != null) {
                    entries += position.afterType!! to position.afterKey
                    // And a run ends inside the malformed type, on its third entry, deterministically.
                    if (position.afterType == malformedType && ++malformedReached == 3) slow.endTheRun()
                    // The run ends exactly on the last entry of "A": the next one starts on another type.
                    if (position.afterKey == lastOfA && position.afterType == hexOf("A".encodeToByteArray()) && !stoppedAtTheTypesEnd) {
                        stoppedAtTheTypesEnd = true
                        slow.endTheRun()
                    }
                }
            }
        }
        var insertedAhead = false
        var behindWrites = 0
        val saved = ArrayList<ReplicaWalkPosition>()
        while (true) {
            val outcome = process.walk().run()
            if (outcome !is ReplicaWalkOutcome.Progressed) {
                assertIs<ReplicaWalkOutcome.Clean>(outcome)
                break
            }
            val at = outcome.position
            if (outcome.saved) saved += at
            val key = at.afterKey ?: continue
            // A sibling writes behind the cursor: it deletes what was read and inserts below it.
            process.sql { connection ->
                connection.exec("DELETE FROM objects WHERE hash = $key")
                connection.exec("INSERT OR IGNORE INTO objects (hash, type, data) VALUES (${key - 3}, 'A', zeroblob(10))")
                if (at.unit == rowsUnit && !insertedAhead) {
                    connection.exec("INSERT INTO objects (hash, type, data) VALUES (9000, 'Fz', zeroblob(10))")
                    insertedAhead = true
                }
            }
            behindWrites++
        }
        assertTrue(insertedAhead && behindWrites > 4 && stoppedAtTheTypesEnd, "the scenario ran: $behindWrites behind, ahead=$insertedAhead, boundary=$stoppedAtTheTypesEnd")
        // Every row there at the start was read, and the one inserted ahead; none inserted behind.
        assertEquals(initial + 9000L, rows)
        // Every entry of the index as it was when the index's walk began (writes after that were behind it).
        assertEquals(indexAtItsStart, entries)
        // ⚠ The case (112) is about: a run saved inside the malformed type, with entries of that
        // type still after it, which only the raw-bytes key reaches.
        val inside = saved.filter { it.unit == indexUnit && it.afterType == malformedType }
        assertTrue(inside.any { position -> indexAtItsStart!!.any { it.first == malformedType && it.second > position.afterKey!! } }, "a saved position inside the malformed type, with entries of it after: $inside")
    }

    /**
     * Runs [block] on a raw connection while `objects.type` is declared with no type and no NOT
     * NULL, so it can insert what no writer of this codebase can (an INTEGER, a REAL, a NULL);
     * the declaration is put back exactly as it was. Through `writable_schema`, each step on a
     * fresh connection so each reads the schema anew. Made-up rows only.
     */
    private fun withALooseType(directory: String, block: (SqliteConnection) -> Unit) {
        val process = ReplicaProcess(directory)
        val original = process.sql { it.queryText("SELECT sql FROM sqlite_schema WHERE name = '${ReplicaSchema.OBJECTS}'") }
        val loose = original.replace("type TEXT NOT NULL", "type")
        assertTrue(loose != original, original)
        fun declare(sql: String) = process.sql { connection ->
            connection.exec("PRAGMA writable_schema = ON")
            val update = connection.prepare("UPDATE sqlite_schema SET sql = ? WHERE name = '${ReplicaSchema.OBJECTS}'")
            try {
                update.bindText(1, sql)
                update.step()
            } finally {
                update.close()
            }
        }
        declare(loose)
        process.sql(block)
        declare(original)
        process.sql { assertEquals(original, it.queryText("SELECT sql FROM sqlite_schema WHERE name = '${ReplicaSchema.OBJECTS}'")) }
    }

    @Test
    fun indexEntriesOfEveryStorageClassAreSteppedPastAtEveryRunBoundaryAndEveryOneIsReached() {
        val directory = aStore(rows = 0)
        withALooseType(directory) { connection ->
            connection.exec(
                "INSERT INTO objects (hash, type, data) VALUES " +
                    "(1, 'Ab', x'00'), (2, x'01', x'00'), (3, NULL, x'00'), (4, NULL, x'00'), (5, 7, x'00'), (6, 7.0, x'00'), " +
                    "(7, -3, x'00'), (8, 2.5, x'00'), (9, 'Ab', x'00'), (10, x'01', x'00'), (11, 'Zz', x'00'), (12, x'ff00', x'00'), " +
                    "(13, '', x'00'), (14, x'', x'00'), (15, NULL, x'00')",
            )
        }
        val process = ReplicaProcess(directory)
        // Every entry of the index, in its class and in the position's own spelling of a key.
        val all = process.sql { connection ->
            val found = HashSet<Triple<String, String, Long>>()
            connection.query("SELECT typeof(type), type, hash FROM objects INDEXED BY objects_type") {
                val key = when (it.getText(0)) {
                    "null" -> ""
                    "integer" -> it.getLong(1).toString()
                    "real" -> it.getDouble(1).toRawBits().toULong().toString(16).padStart(16, '0')
                    else -> hexOf(it.getBlob(1))
                }
                found += Triple(it.getText(0), key, it.getLong(2))
            }
            found
        }
        assertEquals(15, all.size)
        assertEquals(ReplicaWalkKeyClass.entries.map { it.sqlName }.toSet(), all.map { it.first }.toSet())
        // The walk is owed from the index on: the rows unit, which would call a NULL or a number
        // in a row damage, is behind it (an index entry is not a row: B.9 (122)).
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        val at = ReplicaWalkPosition(epoch, PosixReplicaFiles.identity(process.databasePath), indexUnit, null, null, 1, "e")
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, at.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
        // A run has room for a thousand of SQLite's steps, asked at every one; every entry ends
        // its run, so the next run resumes after it: every class, and every change of class, is a
        // boundary.
        val slow = process.slowRuns(stepMs = 1)
        process.registry.confirmAskEvery = 1
        val reached = HashSet<Triple<String, String, Long>>()
        process.walk().onReached = { position ->
            if (position.unit == indexUnit && position.afterKey != null) {
                reached += Triple(position.afterClass!!.sqlName, position.afterType!!, position.afterKey)
                slow.endTheRun()
            }
        }
        val resumedIn = HashSet<ReplicaWalkKeyClass>()
        var runs = 0
        while (true) {
            val outcome = process.walk().run()
            if (outcome !is ReplicaWalkOutcome.Progressed) {
                assertIs<ReplicaWalkOutcome.Clean>(outcome)
                break
            }
            assertTrue(outcome.advanced, "run ${runs + 1} did not advance: $outcome")
            outcome.position.afterClass?.let { resumedIn += it }
            if (++runs > 100) fail("the walk does not end")
        }
        assertEquals(all, reached, "every entry, of every class, reached exactly as stored")
        assertEquals(ReplicaWalkKeyClass.entries.toSet(), resumedIn, "a run resumed inside every class")
        assertFalse(fileExists(markerOf(directory)), "an index entry of another class is no finding: quick_check does not check one")
    }

    @Test
    fun aNullOrANumberInARowsTypeIsDamageWhateverTheStoresSizeAndABlobIsNot() {
        for ((value, finding) in listOf("NULL" to "NULL value in objects.type", "7" to "NUMERIC value in objects.type", "2.5" to "NUMERIC value in objects.type", "x'0102'" to null)) {
            for (large in listOf(false, true)) {
                val directory = aStore(rows = 5, payload = 50)
                withALooseType(directory) { it.exec("INSERT INTO objects (hash, type, data) VALUES (77, $value, x'00')") }
                val process = ReplicaProcess(directory)
                handOff(process)
                if (large) process.slowRuns()
                val outcome = process.walkToTheEnd()
                val why = "$value, ${if (large) "ranged" else "whole"}"
                if (finding == null) {
                    // quick_check does not flag a BLOB in a non-STRICT TEXT column: neither does the ranged walk.
                    assertIs<ReplicaWalkOutcome.Clean>(outcome, why)
                    assertFalse(fileExists(markerOf(directory)), why)
                } else {
                    val confirmed = assertIs<ReplicaWalkOutcome.Confirmed>(outcome, why)
                    assertTrue(finding in confirmed.detail, "$why: ${confirmed.detail}")
                    assertTrue(confirmed.detail.startsWith(if (large) "ROWS:objects" else "quick_check: "), "$why: ${confirmed.detail}")
                }
            }
        }
    }

    @Test
    fun anUnreadableEpochIsDamageByTheOpensOwnRule() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        handOff(process)
        process.sql { it.exec("UPDATE meta SET v = 'not an epoch' WHERE k = 'epoch'") }
        val outcome = assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertTrue("meta.epoch" in outcome.detail, outcome.detail)
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(directory).open())
    }

    @Test
    fun anUnreadableEpochOnALayoutThisBuildDoesNotKnowIsNoVerdict() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        handOff(process)
        process.sql { connection ->
            connection.exec("UPDATE meta SET v = 'not an epoch' WHERE k = 'epoch'")
            connection.exec("UPDATE meta SET v = ${ReplicaSchema.LAYOUT + 1} WHERE k = 'layout'")
        }
        val outcome = assertIs<ReplicaWalkOutcome.NoVerdict>(process.walk().run())
        assertTrue("layout" in outcome.reason, outcome.reason)
        assertFalse(fileExists(markerOf(directory)), "a newer layout's meta is not this build's to call damaged")
        assertFalse(process.walk().isPending())
    }

    @Test
    fun aStoreOfALayoutThisBuildDoesNotKnowEndsTheWalkWithNoVerdictAndTheOpenJudgesIt() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        handOff(process)
        // The walk is at the index, and the store is now a newer layout's, without the index this
        // build walks: the unit could never even be prepared (NotMade at every run, before (123)).
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        val at = ReplicaWalkPosition(epoch, PosixReplicaFiles.identity(process.databasePath), indexUnit, null, null, 1, "e")
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, at.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
        process.sql { connection ->
            connection.exec("UPDATE meta SET v = ${ReplicaSchema.LAYOUT + 1} WHERE k = 'layout'")
            connection.exec("DROP INDEX objects_type")
        }
        val before = bytesOf(process.databasePath)
        val outcome = assertIs<ReplicaWalkOutcome.NoVerdict>(process.walk().run())
        assertTrue("layout ${ReplicaSchema.LAYOUT + 1}" in outcome.reason, outcome.reason)
        assertFalse(fileExists(markerOf(directory)))
        assertFalse(fileExists(process.walk().positionPath), "the walk is over")
        assertFalse(process.walk().isPending(), "and this app's fence lifted")
        // The open judges the store by its own rules: a newer layout is skew, and nothing is written.
        assertIs<ReplicaOpenResult.Skew>(process.open())
        assertTrue(before.contentEquals(bytesOf(process.databasePath)))
    }

    // ── Q16: the open's own rule before the units (Appendix B.10) ──

    @Test
    fun onThisLayoutAStoreMissingItsIndexIsDamageByTheOpensOwnRuleWhetherReadWholeOrRanged() {
        for (large in listOf(false, true)) {
            // Two identical stores: one walked after a hand-off, one only opened, so the two verdicts are compared.
            val (walked, opened) = List(2) { aStore(rows = 5, payload = 50) }
            val process = ReplicaProcess(walked)
            handOff(process)
            for (directory in listOf(walked, opened)) ReplicaProcess(directory).sql { it.exec("DROP INDEX objects_type") }
            if (large) process.slowRuns()
            val why = if (large) "ranged" else "whole"
            // Before Q16: `quick_check` passes a store with no index (whole: Clean, and the open then
            // marks it), and the ranged walk's index unit cannot be prepared (NotMade at every run).
            val confirmed = assertIs<ReplicaWalkOutcome.Confirmed>(process.walkToTheEnd(runs = 5), why)
            assertTrue("schema validation: missing schema object 'objects_type'" in confirmed.detail, "$why: ${confirmed.detail}")
            assertTrue(fileExists(markerOf(walked)), why)
            // The open, by its own rule on the twin store, says the same.
            val open = assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(opened).open(), "$why: the open agrees")
            assertTrue("missing schema object 'objects_type'" in open.detail, open.detail)
        }
    }

    @Test
    fun onThisLayoutBookkeepingTheClassificationCallsDamagedIsDamageAsTheOpenCallsIt() {
        // `quick_check` reads no meta value, so without the open's rule the walk would end Clean
        // on a store the open then marks.
        val (walked, opened) = List(2) { aStore(rows = 5, payload = 50) }
        val process = ReplicaProcess(walked)
        handOff(process)
        for (directory in listOf(walked, opened)) ReplicaProcess(directory).sql { it.exec("UPDATE meta SET v = -1 WHERE k = 'seq'") }
        val confirmed = assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertTrue("the classification read: meta.seq is negative" in confirmed.detail, confirmed.detail)
        val open = assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(opened).open(), "the open agrees")
        assertTrue("meta.seq is negative" in open.detail, open.detail)
    }

    @Test
    fun onThisLayoutAStoreAheadOfThisBuildIsNoVerdictEvenWithASchemaFindingAsTheOpenDecides() {
        val directory = aStore(rows = 5, payload = 50)
        val process = ReplicaProcess(directory)
        handOff(process)
        process.sql { connection ->
            // A newer build's service version, on this very layout, and a schema object this build does not make.
            connection.exec("UPDATE ${ReplicaSchema.SERVICE_VERSIONS} SET version = 99 WHERE service_id = 'tenshu/sync'")
            connection.exec("CREATE INDEX made_up_by_a_newer_build ON objects (hash)")
        }
        val before = bytesOf(process.databasePath)
        val outcome = assertIs<ReplicaWalkOutcome.NoVerdict>(process.walk().run())
        assertTrue("ahead of this build" in outcome.reason, outcome.reason)
        assertFalse(fileExists(markerOf(directory)), "nothing marked on a newer build's store")
        assertFalse(process.walk().isPending(), "the walk is over and the fence lifted")
        // The open, by its own rule: skew, before any validation, and nothing written.
        assertIs<ReplicaOpenResult.Skew>(process.open())
        assertTrue(before.contentEquals(bytesOf(process.databasePath)))
    }

    // ── a TEXT or BLOB resume under a plan that filters (Appendix B.10 (133)) ──

    @Test
    fun aTextResumeUnderAFilterPlanNeverReportsOrSavesAnEntryBehindItsStart() {
        val directory = aStore(rows = 0)
        withALooseType(directory) { it.exec("INSERT INTO objects (hash, type, data) VALUES (1, '1a', x'00'), (2, 5, x'00'), (3, 'zz', x'00'), (4, 7.5, x'00')") }
        val process = ReplicaProcess(directory)
        // The premise: evaluated as a filter, `type > '1a'` holds for both numbers too ('5' and
        // '7.5' sort after '1a' as text), which in the index's order are BEHIND '1a'.
        assertEquals(3L, process.sql { it.queryLong("SELECT count(*) FROM objects NOT INDEXED WHERE type > '1a'") }, "5, 7.5 and 'zz'")
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        val start = ReplicaWalkPosition(epoch, PosixReplicaFiles.identity(process.databasePath), indexUnit, 1L, hexOf("1a".encodeToByteArray()), 1, "e", ReplicaWalkKeyClass.TEXT)
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, start.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
        // A plan that evaluates the resume as a filter: the table scanned and sorted, not the index sought.
        process.walk().indexStatement = { it.replace("INDEXED BY objects_type", "NOT INDEXED") }
        val slow = process.slowRuns(stepMs = 1)
        process.registry.confirmAskEvery = 1
        val reached = ArrayList<ReplicaWalkPosition>()
        var endAtFirst = true
        process.walk().onReached = { position ->
            if (position.unit == indexUnit && position.afterKey != null) {
                reached += position
                if (endAtFirst) {
                    endAtFirst = false
                    slow.endTheRun()
                }
            }
        }
        // The run ends at the first entry it reports: the position it saves is ahead of the start.
        val first = assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run())
        assertEquals(ReplicaWalkKeyClass.TEXT, first.position.afterClass, "${first.position}")
        assertEquals(hexOf("zz".encodeToByteArray()), first.position.afterType)
        assertTrue(first.position.isPast(start) && first.advanced, "${first.position}")
        assertIs<ReplicaWalkOutcome.Clean>(process.walkToTheEnd())
        assertEquals(listOf(3L), reached.map { it.afterKey }, "only 'zz' is after '1a': no number was reported, or saved")
    }

    @Test
    fun aResumeAtZeroReachesTheMinusZeroAfterItWhichSqliteOrdersByRowid() {
        val directory = aStore(rows = 0)
        withALooseType(directory) { connection ->
            val insert = connection.prepare("INSERT INTO objects (hash, type, data) VALUES (?, ?, x'00')")
            try {
                for ((hash, value) in listOf(3L to -0.0, 5L to 0.0, 7L to -0.0, 9L to 0.0)) {
                    insert.bindLong(1, hash)
                    insert.bindDouble(2, value)
                    insert.step()
                    insert.reset()
                }
            } finally {
                insert.close()
            }
        }
        val process = ReplicaProcess(directory)
        assertEquals(listOf(3L, 5L, 7L, 9L), process.sql { c -> ArrayList<Long>().also { out -> c.query("SELECT hash FROM objects INDEXED BY objects_type ORDER BY type, hash") { out += it.getLong(0) } } }, "the premise: one key, ordered by rowid")
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        // A run ended at (0.0, 5): the next resumes there.
        val start = ReplicaWalkPosition(epoch, PosixReplicaFiles.identity(process.databasePath), indexUnit, 5L, 0.0.toRawBits().toULong().toString(16).padStart(16, '0'), 1, "e", ReplicaWalkKeyClass.REAL)
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, start.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
        val reached = ArrayList<Long>()
        process.walk().onReached = { if (it.unit == indexUnit && it.afterKey != null) reached += it.afterKey }
        assertIs<ReplicaWalkOutcome.Clean>(process.walkToTheEnd())
        assertEquals(listOf(7L, 9L), reached, "(-0.0, 7) is after (0.0, 5), and is read")
    }

    @Test
    fun aStoreOfOursWithNoReadableLayoutIsDamageInTheWalkAsAtTheOpen() {
        val (walked, opened) = List(2) { aStore(rows = 5, payload = 50) }
        val process = ReplicaProcess(walked)
        handOff(process)
        for (directory in listOf(walked, opened)) ReplicaProcess(directory).sql { it.exec("DELETE FROM meta WHERE k = 'layout'") }
        val confirmed = assertIs<ReplicaWalkOutcome.Confirmed>(process.walk().run())
        assertTrue("without meta.layout" in confirmed.detail, confirmed.detail)
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(opened).open(), "the open agrees")
    }

    @Test
    fun indexPositionsAreOrderedExactlyAsSqliteOrdersTheIndexAndOnlyOneAheadIsProgress() {
        val indexUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.INDEX }
        // Every value, as an SQL literal, with a rowid; ordered here by SQLite itself.
        val values = listOf(
            "NULL" to 9, "NULL" to 3, "-1e19" to 1, "${Long.MIN_VALUE}" to 1, "-3" to 1, "2.5" to 1, "5" to 3, "5.0" to 2, "5" to 1,
            "9007199254740992" to 1, "9007199254740992.0" to 2, "9007199254740993" to 1, "${Long.MAX_VALUE}" to 1, "9.223372036854775807e18" to 1,
            "''" to 1, "'Ab'" to 1, "'Abc'" to 1, "'Z'" to 1, "CAST(x'ff' AS TEXT)" to 1, "x''" to 1, "x'01'" to 1, "x'0100'" to 1, "x'ff00'" to 1,
        )
        val memory = SqliteConnection.open(":memory:")
        val sorted = try {
            memory.exec("CREATE TABLE t (k, r INTEGER)")
            for ((value, rowid) in values) memory.exec("INSERT INTO t VALUES ($value, $rowid)")
            // ⚠ -0.0 and 0.0 are EQUAL to SQLite, ordered by the rowid (B.10 (139)); bound, so
            // their bits are kept. And a NaN is stored as NULL: there is no NaN to order.
            val bound = memory.prepare("INSERT INTO t VALUES (?, ?)")
            try {
                for ((value, rowid) in listOf(-0.0 to 3L, 0.0 to 5L, -0.0 to 7L, Double.NaN to 11L)) {
                    bound.bindDouble(1, value)
                    bound.bindLong(2, rowid)
                    bound.step()
                    bound.reset()
                }
            } finally {
                bound.close()
            }
            assertEquals("null", memory.queryText("SELECT typeof(k) FROM t WHERE r = 11"), "SQLite stores NaN as NULL")
            var bits = 0L
            memory.query("SELECT k FROM t WHERE r = 7") { bits = it.getDouble(0).toRawBits() }
            assertEquals((-0.0).toRawBits(), bits, "the premise: -0.0 kept as -0.0")
            val out = ArrayList<ReplicaWalkPosition>()
            memory.query("SELECT typeof(k), k, r FROM t ORDER BY k, r") { row ->
                val keyClass = assertNotNull(ReplicaWalkKeyClass.of(row.getText(0)))
                val key = ReplicaIndexKey.read(row, 1, keyClass, row.getLong(2))
                out += ReplicaWalkPosition(1L, "file", indexUnit, key.rowid, key.keyText, 1, "e", keyClass)
            }
            out
        } finally {
            memory.close()
        }
        assertEquals(values.size + 4, sorted.size)
        // Shuffled, then sorted by the walk's own order: SQLite's order comes back.
        val byTheWalk = sorted.shuffled().sortedWith { a, b -> assertNotNull(ReplicaIndexKey.of(a)).compareTo(assertNotNull(ReplicaIndexKey.of(b))) }
        assertEquals(sorted, byTheWalk)
        for (i in sorted.indices) {
            for (j in sorted.indices) assertEquals(i > j, sorted[i].isPast(sorted[j]), "${sorted[i]} past ${sorted[j]}")
        }
        // The rows unit: by rowid alone.
        val rowsUnit = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.ROWS }
        val rowsAt = { key: Long -> ReplicaWalkPosition(1L, "file", rowsUnit, key, null, 1, "e") }
        assertTrue(rowsAt(5).isPast(rowsAt(3)))
        assertFalse(rowsAt(3).isPast(rowsAt(5)), "a rowid behind is not progress")
    }

    @Test
    fun aPositionFileThatCannotBeReadIsOwedFromTheStartAndReplaced() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        val big = ByteArray(ReplicaConfirmationWalk.MAX_POSITION_BYTES + 1) { 'x'.code.toByte() }
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, big).let(PosixReplicaFiles::closeDescriptor)
        assertFailsWith<StoreBeingChecked>("owed, not StoreUnavailable") { process.open() }
        val begun = ArrayList<ReplicaWalkUnitKind>()
        process.walk().atUnit = { begun += it.kind }
        assertIs<ReplicaWalkOutcome.Clean>(process.walk().run())
        assertEquals(listOf(ReplicaWalkUnitKind.WHOLE), begun)
        assertFalse(fileExists(process.walk().positionPath))
        assertIs<ReplicaOpenResult.Ready>(process.open()).store.close()
    }

    @Test
    fun aPositionFileThatCannotBeRemovedDoesNotFenceThisProcessForGood() {
        val directory = aStore(rows = 60)
        val files = RecordingFiles()
        val process = ReplicaProcess(directory, files = files)
        handOff(process)
        process.slowRuns()
        repeat(2) { assertIs<ReplicaWalkOutcome.Progressed>(process.walk().run()) }
        process.registry.confirmClock = { 0L }
        process.walk().atUnit = null
        files.failRemoveOf = ReplicaConfirmationWalk.POSITION_PREFIX
        assertIs<ReplicaWalkOutcome.Clean>(process.walkToTheEnd())
        assertTrue(fileExists(process.walk().positionPath), "the unlink failed")
        // This process ended on that file: it neither refuses its opens nor walks again for it.
        assertFalse(process.walk().isPending())
        assertIs<ReplicaWalkOutcome.NothingPending>(process.walk().run())
        assertIs<ReplicaOpenResult.Ready>(process.open()).store.close()
        // A new process cannot tell it from an owed walk: it walks it once more, and removes it.
        files.failRemoveOf = null
        val next = ReplicaProcess(directory, files = files)
        assertTrue(next.walk().isPending())
        assertIs<ReplicaWalkOutcome.Clean>(next.walkToTheEnd())
        assertFalse(fileExists(next.walk().positionPath))
    }

    @Test
    fun twoAppNamesThatSanitiseAlikeHaveTwoPositionFiles() {
        val directory = tempReplica()
        val one = ReplicaProcess(directory, name = "a/b")
        val two = ReplicaProcess(directory, name = "a_b")
        assertTrue(one.walk().positionPath != two.walk().positionPath)
    }

    @Test
    fun aWalkThatCannotAdvanceStallsNotCountingAnExpiryAndLiftsOnANewCodeOrAnotherStore() {
        // A unit that never fits in one run: `migration_done` large enough for its check to outlast every run.
        val directory = aStore(rows = 20)
        val process = ReplicaProcess(directory)
        handOff(process)
        process.sql { it.exec("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 3000) INSERT INTO migration_done SELECT x FROM c") }
        process.slowRuns()
        val stuck = ReplicaWalkUnit.all.indexOfFirst { it.kind == ReplicaWalkUnitKind.TABLE && it.name == ReplicaSchema.MIGRATION_DONE }
        var stop = ReplicaWalkStop()
        var expire = false
        val atUnit = process.walk().atUnit
        process.walk().atUnit = { unit ->
            atUnit?.invoke(unit)
            if (expire && unit.name == ReplicaSchema.MIGRATION_DONE) stop.ask()
        }
        fun run(): ReplicaWalkOutcome = process.walk().run(ReplicaWalkStop().also { stop = it })
        // Up to the unit that never fits.
        repeat(20) { if ((process.walk().progress()?.unit ?: 0) != stuck) assertIs<ReplicaWalkOutcome.Progressed>(run()) }
        assertEquals(stuck, process.walk().progress()!!.unit)

        // Runs cut short by an expiry never count.
        expire = true
        repeat(ReplicaConfirmationWalk.STALL_RUNS + 1) { assertIs<ReplicaWalkOutcome.Progressed>(run(), "an expiry is not a stall") }
        expire = false
        // Its own runs do: the STALL_RUNS-th is Stalled, and it stays so on the same store.
        repeat(ReplicaConfirmationWalk.STALL_RUNS - 1) { assertIs<ReplicaWalkOutcome.Progressed>(run()) }
        assertIs<ReplicaWalkOutcome.Stalled>(run())
        assertTrue(process.walk().isStalled && process.walk().isPending())
        assertTrue(assertFailsWith<StoreBeingChecked> { process.open() }.stalled)
        assertIs<ReplicaWalkOutcome.Stalled>(run(), "the same store: still stalled, nothing read past its epoch")

        // While stalled, an open's reschedule backs off (B.9 (125)): the window doubles per
        // schedule up to its cap, where it was a run every 5 s for as long as the host retried.
        var scheduled = 0
        process.registry.onWalkRequested = { scheduled++ }
        var t = 1_000_000_000L
        process.walk().nowMs = { t }
        process.walk().after = { _, _ -> }
        fun gapsOver(totalMs: Long): List<Long> {
            val due = ArrayList<Long>()
            val end = t + totalMs
            while (t <= end) {
                val before = scheduled
                process.walk().scheduleFromAnOpen()
                if (scheduled > before) due += t
                t += 5_000
            }
            return due.zipWithNext { a, b -> b - a }
        }
        val cap = ReplicaConfirmationWalk.OPEN_RESCHEDULE_MAX_MS
        val gaps = gapsOver(1_500_000)
        assertTrue(gaps.size >= 4 && gaps.first() >= 2 * ReplicaConfirmationWalk.OPEN_RESCHEDULE_MS, "$gaps")
        gaps.zipWithNext { a, b -> assertEquals(minOf(2 * a, cap), b, "$gaps") }
        assertEquals(cap, gaps.last(), "$gaps")

        // A new hand-off lifts it.
        process.walk().request("a new code")
        assertFalse(process.walk().isStalled)
        // And with it the back-off: an open schedules every 5 s again.
        assertEquals(List(3) { ReplicaConfirmationWalk.OPEN_RESCHEDULE_MS }, gapsOver(20_000).take(3))
        process.registry.onWalkRequested = null
        repeat(ReplicaConfirmationWalk.STALL_RUNS - 1) { assertIs<ReplicaWalkOutcome.Progressed>(run()) }
        assertIs<ReplicaWalkOutcome.Stalled>(run())

        // So does another store under it (a sibling's wipe moves the epoch).
        process.sql { it.exec("UPDATE meta SET v = v + 1 WHERE k = 'epoch'") }
        assertIs<ReplicaWalkOutcome.Progressed>(run(), "the walk starts over on the new store")
        assertFalse(process.walk().isStalled)
    }

    @Test
    fun anOpenSchedulesTheWalkAtMostOncePerIntervalAndASuppressedScheduleIsDispatchedWhenItEnds() {
        val process = ReplicaProcess(aStore(rows = 5))
        handOff(process)
        var scheduled = 0
        process.registry.onWalkRequested = { scheduled++ }
        var t = 0L
        process.walk().nowMs = { t }
        val later = ArrayList<Pair<Long, () -> Unit>>()
        process.walk().after = { delay, block -> later += delay to block }
        repeat(3) { assertFailsWith<StoreBeingChecked> { process.open() } }
        assertEquals(1, scheduled, "once in the window")
        assertEquals(1, later.size, "one schedule is owed, not three, and none is dropped")
        assertEquals(ReplicaConfirmationWalk.OPEN_RESCHEDULE_MS, later.single().first)
        t += ReplicaConfirmationWalk.OPEN_RESCHEDULE_MS
        later.removeAt(0).second.invoke()
        assertEquals(2, scheduled, "the owed one, when the window ended")
        assertFailsWith<StoreBeingChecked> { process.open() }
        assertEquals(2, scheduled)
        assertEquals(1, later.size)
    }

    @Test
    fun aCodeHandedOffWhileTheLastRunReadsIsRunAgainNotLost() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        val queued = ArrayList<() -> Unit>()
        val outcomes = ArrayList<ReplicaWalkOutcome>()
        ReplicaWorker(null, { queued += it }) { _, it -> outcomes += it }.attach(process.registry)
        handOff(process)
        var once = true
        // A second code is handed off while the run that will end the walk is reading.
        process.registry.beforeConfirm = {
            if (once) {
                once = false
                process.walk().request("a second code")
            }
        }
        while (queued.isNotEmpty()) queued.removeAt(0).invoke()
        assertEquals(2, outcomes.count { it is ReplicaWalkOutcome.Clean }, "$outcomes")
        assertFalse(process.walk().isPending(), "the second code was walked too")
    }

    @Test
    fun anExpiryBetweenTheTokenAndTheRunStopsTheRun() {
        val directory = aStore(rows = 5)
        val process = ReplicaProcess(directory)
        var expire: (() -> Unit)? = null
        val assertions = object : ProtectionAssertions {
            var next = 0L
            override fun begin(reason: String, onExpired: () -> Unit): Long {
                if (reason.startsWith("confirm-walk")) expire = onExpired
                return ++next
            }
            override fun end(id: Long) = Unit
            override fun backgroundTimeRemainingMs(): Long = 30_000
        }
        val protection = ReplicaProtection(assertions, isMainThread = { false })
        protection.attach(process.registry)
        protection.install(isForeground = true)
        handOff(process)
        process.registry.confirmAskEvery = 1
        var outcome: ReplicaWalkOutcome? = null
        val worker = ReplicaWorker(protection, { it() }) { _, it -> outcome = it }
        worker.afterToken = { expire!!.invoke() }
        worker.schedule(process.walk())
        val stopped = assertIs<ReplicaWalkOutcome.NotMade>(outcome)
        assertTrue("asked to stop" in stopped.reason, stopped.reason)
        assertTrue(process.walk().isPending())
    }

    @Test
    fun aWalkReadToItsEndDoesNotLookForAMarkerOnceTheGateHasClosed() {
        val directory = aStore(rows = 5)
        val files = RecordingFiles()
        val process = ReplicaProcess(directory, files = files)
        handOff(process)
        // A position at the last unit, so the run reads that unit and ends.
        val epoch = process.sql { it.queryLong("SELECT v FROM meta WHERE k = 'epoch'") }
        val last = ReplicaWalkPosition(epoch, PosixReplicaFiles.identity(process.databasePath), ReplicaWalkUnit.all.lastIndex, null, null, 1, "e")
        PosixReplicaFiles.createAndWrite(process.walk().positionPath, last.encode(process.name)).let(PosixReplicaFiles::closeDescriptor)
        // The gate closes as the reading ends; from then on a lookup of the marker fails, and is counted.
        process.walk().onReached = { position ->
            if (position.unit == ReplicaWalkUnit.all.size) {
                process.registry.gate.closeAndDrain(0)
                files.failLookupOf = ReplicaCorruptMarker.NAME
            }
        }
        assertIs<ReplicaWalkOutcome.Clean>(process.walk().run(), "a reading that got to its end is its verdict")
        assertEquals(0, files.lookupFailures, "the marker was looked for with the gate closed")
        files.failLookupOf = null
        process.registry.gate.reopen()
    }
}
