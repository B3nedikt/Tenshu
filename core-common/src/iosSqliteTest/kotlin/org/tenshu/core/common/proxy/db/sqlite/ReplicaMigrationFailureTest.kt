@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_FULL
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A migration step that fails on one and the same row becomes a wipe in the `N`th run that meets
 * it (Appendix B.3 (41), (53)): a durable count in `meta.migration`, at most one per run, keyed
 * to the row and to the counting build's declared version of the step's service.
 *
 * The chain is slice 2's, whose steps are not idempotent (they append), so a row migrated twice
 * around the failing one shows.
 */
class ReplicaMigrationFailureTest {

    /** Seven rows a chunk: the rows before the failing one are a chunk of their own. */
    private val chunky = ReplicaLimits(chunkMaxRows = 7)

    // C = 8, G = 8, δ = 20 frames, Wmax = 120 pages: a few hundred kilobytes reach the ceiling.
    private val tight = ReplicaLimits(
        wmaxPages = 120,
        cachePages = 8,
        statementPages = 8,
        rowMaxBytes = 8_000,
        ceilingWaitMs = 150,
        chunkMaxRows = 1_000_000,
    )

    private val attempts = ReplicaLimits().migrationRowAttempts

    private val file = "tenshu/file"
    private val sync = "tenshu/sync"
    private val other = "tenshu/other"
    private val floors = mapOf(file to 1, sync to 1)
    private val oldBuild = testBuild(services = mapOf(file to 1, sync to 8), serviceFloors = floors)
    private val newBuild = testBuild(services = mapOf(file to 3, sync to 9), serviceFloors = floors)

    /** A build that declares another version of the failing step's service, and may resume the same chain. */
    private val laterBuild = testBuild(services = mapOf(file to 4, sync to 9), serviceFloors = floors)

    private fun ReplicaProcess.count(table: String): Long = sql { it.queryLong("SELECT count(*) FROM $table") }
    private fun ReplicaProcess.metaInt(key: String): Long = sql { it.queryLong("SELECT v FROM meta WHERE k = '$key'") }
    private fun ReplicaProcess.hasMeta(key: String): Boolean = sql { it.queryLong("SELECT count(*) FROM meta WHERE k = '$key'") } == 1L
    private fun ReplicaProcess.stamp(service: String): Long =
        sql { it.queryLong("SELECT version FROM schema_service_versions WHERE service_id = '$service'") }

    private fun ReplicaProcess.metaBlob(key: String): ByteArray? = sql { connection ->
        var blob: ByteArray? = null
        connection.query("SELECT v FROM meta WHERE k = '$key'") { blob = it.getBlob(0) }
        blob
    }

    /** The migration marker as it is on disk, or null when there is none. */
    private fun markerOf(directory: String): MigrationMarker? =
        ReplicaProcess(directory).metaBlob("migration")?.let { assertIs<DecodedMarker.Value<MigrationMarker>>(MigrationMarker.decode(it)).marker }

    private fun SharedReplicaStore.fill(rows: List<DbEntry>, batch: Int = 20) {
        rows.chunked(batch).forEach { some -> databaseLock.withLock { some.forEach(::storeObject) } }
    }

    private class Step(override val typeIds: Set<String>, private val migration: (DbEntry) -> DbEntry?) : ReplicaMigrationStep {
        override fun migrate(row: DbEntry): DbEntry? = migration(row)
    }

    /** The text a failing step puts in its exception: the row's content, which must reach no log line and no outcome. */
    private val secret = "the row says: do-not-log"

    /**
     * Slice 2's chain: `tenshu/file` 1 → 2 appends a 2, 2 → 3 drops every fifth row and appends a
     * 3, `tenshu/sync` 8 → 9 moves no row. The first step does [onBad] to the rows in [bad].
     */
    private fun chain(bad: Set<Long> = emptySet(), onBad: (DbEntry) -> DbEntry? = { throw IllegalStateException(secret) }) =
        ReplicaMigrationSteps { service, from ->
            when (service to from) {
                file to 1 -> Step(setOf(file)) { if (it.dataIdHash in bad) onBad(it) else it.copy(fields = it.fields + DbValue.LongValue(2)) }
                file to 2 -> Step(setOf(file)) { if (it.dataIdHash % 5 == 0L) null else it.copy(fields = it.fields + DbValue.LongValue(3)) }
                file to 3 -> Step(setOf(file)) { it.copy(fields = it.fields + DbValue.LongValue(4)) }
                sync to 8 -> Step(emptySet()) { it }
                else -> null
            }
        }

    private val arrays = listOf(9_001L, 9_002L, 9_003L)

    /** A store of the old build: [rows] file rows, rows of a type no step reads, and arrays naming the file rows. */
    private fun anOldStore(limits: ReplicaLimits, rows: Int): String {
        val directory = tempReplica()
        val store = ReplicaProcess(directory, limits).store(oldBuild)
        store.fill((1..rows).map { row(it.toLong(), file) })
        store.fill((1..10).map { row(5_000L + it, other) })
        store.fill(arrays.map { hash -> DbEntry(TList.ID, hash, (1..rows).filter { it % (hash - 9_000) == 0L }.map { DbValue.LongValue(it.toLong()) }) })
        store.close()
        return directory
    }

    /** One foreground launch of [build]: the open, and the run of the job it answered with. Null when a store was handed out. */
    private fun launch(
        directory: String,
        steps: ReplicaMigrationSteps,
        limits: ReplicaLimits = chunky,
        build: ReplicaBuild = newBuild,
    ): ReplicaRunOutcome? {
        val process = ReplicaProcess(directory, limits).also { it.steps = steps }
        return when (val opened = process.open(build)) {
            is ReplicaOpenResult.Updating -> opened.replacement.run().also { assertEquals(0, process.registry.handles().size) }
            is ReplicaOpenResult.Ready -> null.also { opened.store.close() }
            else -> fail("$opened")
        }
    }

    private fun counted(outcome: ReplicaRunOutcome?): MigrationRowFailed =
        assertIs<MigrationRowFailed>(assertIs<ReplicaRunOutcome.Stopped>(outcome).cause)

    /** Empty, at [build]'s versions, with no marker left, and handed out by an open. */
    private fun assertWipedAt(directory: String, build: ReplicaBuild, limits: ReplicaLimits = chunky) {
        val process = ReplicaProcess(directory, limits)
        for (table in ReplicaSchema.tablesEmptiedByWipe) assertEquals(0, process.count(table), table)
        assertFalse(process.hasMeta("wipe"))
        assertFalse(process.hasMeta("migration"))
        for ((service, version) in build.services) assertEquals(version.toLong(), process.stamp(service), service)
        assertEquals(build.coreVersion.toLong(), process.sql { it.queryLong("PRAGMA user_version") })
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"))
        val store = assertIs<ReplicaOpenResult.Ready>(process.open(build)).store
        assertTrue(store.isEmpty())
        store.close()
    }

    private fun assertMigrated(directory: String, rows: Int) {
        val process = ReplicaProcess(directory, chunky).also { it.steps = chain() }
        assertFalse(process.hasMeta("migration"), "the marker is gone")
        assertFalse(process.hasMeta("wipe"))
        assertEquals(0, process.count("migration_done"))
        assertEquals(3, process.stamp(file))
        val store = assertIs<ReplicaOpenResult.Ready>(process.open(newBuild)).store
        for (hash in 1..rows.toLong()) {
            val original = row(hash, file)
            val expected = if (hash % 5 == 0L) null else original.copy(fields = original.fields + DbValue.LongValue(2) + DbValue.LongValue(3))
            assertEquals(expected, store.getObject(hash), "row $hash: migrated exactly once by each step")
        }
        for (hash in 1..10L) assertEquals(row(5_000 + hash, other), store.getObject(5_000 + hash))
        for (hash in arrays) {
            val members = (1..rows).filter { it % (hash - 9_000) == 0L && it % 5 != 0 }.map { DbValue.LongValue(it.toLong()) }
            assertEquals(members, store.getObject(hash)?.fields, "array $hash names no dropped row")
        }
        store.close()
    }

    // ── the count, run by run ──

    @Test
    fun aStepThatFailsOnOneRowIsCountedOnceARunAndTheNthRunWipesTheStore() {
        val directory = anOldStore(chunky, rows = 20)
        val broken = chain(bad = setOf(9))
        val process = ReplicaProcess(directory, chunky)

        // The first launch starts the chain, migrates the rows before the failing one, and counts.
        val first = counted(launch(directory, broken))
        assertEquals(listOf(file, 1, file, 1, attempts), listOf(first.serviceId, first.fromVersion, first.typeId, first.attempts, first.limit))
        // ⚠ Neither the outcome nor its cause's own message carries what the step said of the row.
        assertFalse(secret in first.message.orEmpty(), first.message)
        assertIs<IllegalStateException>(first.cause)
        val started = process.metaInt("epoch")
        val marker = markerOf(directory)!!
        assertEquals(MigrationFailure(hash = 9, version = 3, count = 1, limit = attempts), marker.failure)
        assertEquals(MigrationPhase.ROWS to 8L, marker.phase to marker.cursor)
        assertEquals(8, process.count("migration_done"), "the rows before the failing one are migrated and stay so")
        assertEquals(MIGRATION_COUNTED_FORMAT, process.metaBlob("migration")!![0].toInt())

        // Still "updating", for every build and every kind of open: no store is handed out.
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).open(oldBuild))
        assertFailsWith<StoreNeedsForeground> { ReplicaProcess(directory, chunky).open(newBuild, foreground = false) }
        assertEquals(marker, markerOf(directory), "an open counts nothing")

        // Every run before the Nth counts one more and changes nothing else.
        for (run in 2 until attempts) {
            assertEquals(run, counted(launch(directory, broken)).attempts)
            assertEquals(marker.copy(failure = marker.failure!!.copy(count = run)), markerOf(directory))
            assertEquals(8, process.count("migration_done"))
            assertEquals(started, process.metaInt("epoch"), "a count does not move the epoch")
            assertEquals(1, process.stamp(file))
        }

        // The Nth: no count is written. The job becomes a wipe in the chunk that met the row, and
        // the same run carries the wipe to its end.
        assertEquals(ReplicaRunOutcome.Finished, launch(directory, broken))
        assertNotEquals(started, process.metaInt("epoch"))
        assertEquals("migration-failed test", process.sql { it.queryText("SELECT v FROM meta WHERE k = 'replaced_by'") })
        assertWipedAt(directory, newBuild)
    }

    @Test
    fun theRunThatConvertsMovesTheEpochInTheChunkThatFoundTheNthFailureAndItsWipeIsResumable() {
        val directory = anOldStore(chunky, rows = 20)
        val broken = chain(bad = setOf(9))
        repeat(attempts - 1) { counted(launch(directory, broken)) }
        val process = ReplicaProcess(directory, chunky).also { it.steps = broken }
        val before = process.metaInt("epoch")

        // One chunk: the conversion. A wipe's first chunk, which deletes no row.
        val job = process.replacement(newBuild)
        assertEquals(ChunkResult.More(ReplicaJob.WIPE), job.runOneChunk())
        assertNotEquals(before, process.metaInt("epoch"), "the epoch moves where the job becomes a wipe")
        assertFalse(process.hasMeta("migration"), "the migration marker and its count are gone with the conversion")
        val wipe = assertIs<DecodedMarker.Value<WipeMarker>>(WipeMarker.decode(process.metaBlob("wipe")!!)).marker
        assertEquals("migration-failed", wipe.reason)
        assertEquals(mapOf("tenshu/core" to 22, file to 3, sync to 9), wipe.endStamps, "the last chunk stamps this build's versions")
        assertEquals(33, process.count("objects"))
        assertEquals(1, process.stamp(file), "nothing is stamped before the store is empty")

        // From here it is any wipe: a wake may resume it, and a build below its end stamps may not.
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).open(oldBuild))
        val resumed = assertIs<ReplicaOpenResult.Updating>(ReplicaProcess(directory, chunky).open(newBuild, foreground = false))
        assertEquals(ReplicaJob.WIPE, resumed.job)
        assertEquals(ReplicaRunOutcome.Finished, resumed.replacement.run())
        assertWipedAt(directory, newBuild)
    }

    @Test
    fun aFailingChainKilledAtEveryCommitNeverCountsTwiceForOneRunAndConvergesToAnEmptyStore() {
        var commitsAllowed = 0
        while (true) {
            val what = "killed at commit $commitsAllowed"
            val directory = anOldStore(chunky, rows = 20)
            val broken = chain(bad = setOf(9))
            // One kill, at the [commitsAllowed]-th commit of the whole sequence of launches: the
            // Start, a chunk of rows, a count, the conversion, a chunk of the wipe, its last.
            var commitsLeft = commitsAllowed
            var killed = false
            var launches = 0
            val epochs = LinkedHashSet<Long>()
            val counts = ArrayList<Int>()
            var sawWipe = false
            while (true) {
                launches++
                if (launches > attempts + 2) fail("$what: the job did not converge in $launches launches")
                val before = markerOf(directory)?.failure?.count ?: 0
                val process = ReplicaProcess(directory, chunky).also { it.steps = broken }
                val job = process.replacement(newBuild)
                job.beforeCommit = {
                    if (commitsLeft-- == 0) {
                        killed = true
                        throw RuntimeException("killed")
                    }
                }
                val admitted = job.admit(foreground = true)
                val outcome = if (admitted is ChunkResult.More) job.run() else (admitted as ChunkResult.Ended).outcome
                assertEquals(0, process.registry.handles().size, what)
                if (outcome == ReplicaRunOutcome.Finished) break
                assertIs<ReplicaRunOutcome.Stopped>(outcome, what)

                // ⚠ One run, at most one count: a killed count is lost, never written twice.
                val marker = markerOf(directory)
                val after = marker?.failure?.count ?: 0
                if (process.hasMeta("wipe")) sawWipe = true else assertTrue(after - before in 0..1, "$what: counted $before, then $after")
                assertFalse(sawWipe && marker != null, "$what: a migration marker after the conversion")
                if (marker != null) {
                    counts += after
                    assertTrue(after < attempts, "$what: a count of $after is never stored")
                    assertEquals(marker.stepFrom.toLong(), process.stamp(marker.stepService), what)
                }
                if (marker != null || sawWipe) epochs += process.metaInt("epoch")

                // ⚠ Never a readable half-state: no build is handed a store before the wipe's end,
                // in the foreground or in a wake.
                assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"), what)
                if (marker != null || sawWipe) assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(directory, chunky).open(oldBuild), what)
                try {
                    val wake = ReplicaProcess(directory, chunky).open(newBuild, foreground = false)
                    assertEquals(ReplicaJob.WIPE, assertIs<ReplicaOpenResult.Updating>(wake, what).job, what)
                    assertTrue(sawWipe, what)
                } catch (_: StoreNeedsForeground) {
                    assertFalse(sawWipe, "$what: a wipe in progress is resumable in a wake")
                }
            }

            // Exactly N runs meet the failure: N − 1 count, the Nth converts. A kill costs one run.
            assertEquals(attempts + if (killed) 1 else 0, launches, what)
            // The epoch moved at the Start, at the conversion and at the wipe's end, and nowhere else.
            assertTrue(epochs.size <= 2, "$what: epochs seen while the job ran: $epochs")
            assertFalse(ReplicaProcess(directory).metaInt("epoch") in epochs, what)
            assertEquals(counts.sorted(), counts, "$what: the count never went back")
            assertWipedAt(directory, newBuild)

            if (!killed) {
                assertTrue(commitsAllowed >= 10, "the sequence was many commits: $commitsAllowed")
                return
            }
            commitsAllowed++
        }
    }

    @Test
    fun aBuildWhoseStepSucceedsAfterTwoCountedFailuresMigratesTheRowDropsTheCountAndNothingIsWiped() {
        val directory = anOldStore(chunky, rows = 20)
        val broken = chain(bad = setOf(9))
        repeat(attempts - 1) { counted(launch(directory, broken)) }
        assertEquals(attempts - 1, markerOf(directory)!!.failure!!.count)
        val process = ReplicaProcess(directory, chunky).also { it.steps = chain() }
        val epoch = process.metaInt("epoch")

        // The fixed build's first chunk takes the row, and the count goes with the cursor's move:
        // the frame is the uncounted one again, which a build without the count can read.
        assertEquals(ChunkResult.More(ReplicaJob.MIGRATION), process.replacement(newBuild).runOneChunk())
        val marker = markerOf(directory)!!
        assertNull(marker.failure)
        assertTrue(marker.cursor!! >= 9, "the cursor is past the row: ${marker.cursor}")
        assertEquals(JOB_MARKER_FORMAT, process.metaBlob("migration")!![0].toInt())
        assertEquals(epoch, process.metaInt("epoch"), "neither a count nor its end moves the epoch")

        // A failure on a later row starts at one: nothing of the first row's count is inherited.
        assertEquals(1, counted(launch(directory, chain(bad = setOf(17)))).attempts)
        assertEquals(MigrationFailure(17, 3, 1, attempts), markerOf(directory)!!.failure)

        assertEquals(ReplicaRunOutcome.Finished, launch(directory, chain()))
        assertFalse(process.hasMeta("wipe"))
        // ⚠ The steps append, so a row migrated twice around the failing one would show here:
        // every row, the one that failed included, exactly once by each step.
        assertMigrated(directory, rows = 20)
    }

    @Test
    fun aBuildThatDeclaresAnotherVersionOfTheServiceStartsItsOwnCountAndTheFrameKeepsTheLastCountersOnly() {
        val directory = anOldStore(chunky, rows = 20)
        val broken = chain(bad = setOf(9))
        repeat(attempts - 1) { counted(launch(directory, broken)) }

        // Another build, another declared version of the step's service: one, not N.
        assertEquals(1, counted(launch(directory, broken, build = laterBuild)).attempts)
        assertEquals(MigrationFailure(9, version = 4, count = 1, limit = attempts), markerOf(directory)!!.failure)
        // And the first build starts over in turn (accepted: two broken builds taking turns never reach N).
        assertEquals(1, counted(launch(directory, broken)).attempts)
        assertEquals(MigrationFailure(9, version = 3, count = 1, limit = attempts), markerOf(directory)!!.failure)

        for (run in 2 until attempts) assertEquals(run, counted(launch(directory, broken)).attempts)
        assertEquals(ReplicaRunOutcome.Finished, launch(directory, broken))
        assertWipedAt(directory, newBuild)
    }

    @Test
    fun aReaderComparesAStoredCountWithItsOwnLimitAndAtALimitOfOneNoCountIsEverStored() {
        // N = 1: the first failure is the wipe.
        val once = chunky.copy(migrationRowAttempts = 1)
        val first = anOldStore(once, rows = 20)
        assertEquals(ReplicaRunOutcome.Finished, launch(first, chain(bad = setOf(9)), limits = once))
        assertWipedAt(first, newBuild, once)

        // A count stored by a build whose N is larger is read by one whose N it has reached.
        val patient = chunky.copy(migrationRowAttempts = 5)
        val directory = anOldStore(patient, rows = 20)
        repeat(3) { counted(launch(directory, chain(bad = setOf(9)), limits = patient)) }
        assertEquals(MigrationFailure(9, 3, count = 3, limit = 5), markerOf(directory)!!.failure)
        assertEquals(ReplicaRunOutcome.Finished, launch(directory, chain(bad = setOf(9))))
        assertWipedAt(directory, newBuild)

        assertFailsWith<IllegalArgumentException> { ReplicaLimits(migrationRowAttempts = 0) }
    }

    @Test
    fun anArrayTheSweepCannotDecodeIsCountedLikeAStepsRowAndWipedAtTheNth() {
        val directory = anOldStore(chunky, rows = 20)
        // SQLite reads these bytes without complaint; they are not a row.
        ReplicaProcess(directory).sql { it.exec("UPDATE objects SET data = x'ff' WHERE hash = 9002") }

        val first = counted(launch(directory, chain()))
        assertEquals(listOf(file, 2, TList.ID, 1), listOf(first.serviceId, first.fromVersion, first.typeId, first.attempts))
        val marker = markerOf(directory)!!
        assertEquals(MigrationPhase.SWEEP to 9_001L, marker.phase to marker.cursor)
        assertEquals(MigrationFailure(9_002, 3, 1, attempts), marker.failure)
        assertFalse(fileExists("$directory/${ReplicaCorruptMarker.NAME}"), "bytes SQLite could read are not the corruption path's")

        for (run in 2 until attempts) assertEquals(run, counted(launch(directory, chain())).attempts)
        assertEquals(ReplicaRunOutcome.Finished, launch(directory, chain()))
        assertWipedAt(directory, newBuild)
    }

    // ── the frame ──

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun aCountedFrameIsAnotherFormatAndEveryShapeNoWriterProducesIsMalformed() {
        val plain = MigrationMarker("Drive", 5L, mapOf(file to 3, sync to 9), file, 1, MigrationPhase.ROWS, 8L)
        val counted = plain.copy(failure = MigrationFailure(hash = 9, version = 3, count = 1, limit = 3))

        // ⚠ A marker that has counted nothing is byte for byte the frame slices 2 and 3 wrote, so
        // a build without the count still reads it. Spelled out, not derived from the encoder.
        assertEquals(
            "01" + "00000001" + "61" + "0000000000000001" + "00000001" + "00000001" + "73" + "00000002" +
                "00000001" + "73" + "00000001" + "00" + "00" + "0000000000000000",
            hex(MigrationMarker("a", 1L, mapOf("s" to 2), "s", 1, MigrationPhase.ROWS, null).encode()),
        )
        assertEquals(JOB_MARKER_FORMAT, plain.encode()[0].toInt())
        assertEquals(1, JOB_MARKER_FORMAT, "slices 1 to 3 read this format and no other")
        // With a count it is a format those builds do not know: SKEW to them, nothing written.
        assertEquals(MIGRATION_COUNTED_FORMAT, counted.encode()[0].toInt())
        assertNotEquals(JOB_MARKER_FORMAT, MIGRATION_COUNTED_FORMAT)
        assertContentEquals(plain.encode().drop(1), counted.encode().drop(1).dropLast(20), "the count is appended to the same fields")

        for (valid in listOf(
            counted,
            counted.copy(cursor = null),
            counted.copy(cursor = Long.MIN_VALUE, failure = counted.failure!!.copy(hash = Long.MIN_VALUE + 1)),
            counted.copy(phase = MigrationPhase.VERIFY),
            counted.copy(phase = MigrationPhase.SWEEP, cursor = null, failure = counted.failure.copy(hash = Long.MAX_VALUE)),
            counted.copy(failure = counted.failure.copy(count = 2)),
            // The counter declared more than the target: a later build that may resume.
            counted.copy(failure = counted.failure.copy(version = 4)),
            counted.copy(failure = counted.failure.copy(count = 6, limit = 7)),
        )) {
            assertNull(valid.defect(), "$valid")
            assertEquals(DecodedMarker.Value(valid), MigrationMarker.decode(valid.encode()), "$valid")
        }

        // ⚠ Every field parses in each of these, and no writer produces one.
        val failure = counted.failure
        val impossible = mapOf(
            "a count of zero" to counted.copy(failure = failure.copy(count = 0)),
            "a negative count" to counted.copy(failure = failure.copy(count = -1)),
            "a count at its limit, where the job is a wipe instead" to counted.copy(failure = failure.copy(count = 3)),
            "a count past its limit" to counted.copy(failure = failure.copy(count = 4)),
            "a limit of one, at which nothing is ever stored" to counted.copy(failure = failure.copy(count = 1, limit = 1)),
            "a limit of zero" to counted.copy(failure = failure.copy(count = 1, limit = 0)),
            "a count in the cleanup, which reads no row" to counted.copy(phase = MigrationPhase.CLEANUP, cursor = null),
            "a count on an unstamped step, which reads no row" to counted.copy(stepFrom = MigrationMarker.UNSTAMPED, cursor = null),
            "a count by a build below the step's target, which could not have resumed" to counted.copy(failure = failure.copy(version = 2)),
            "a count for the row the cursor is on" to counted.copy(cursor = 9),
            "a count for a row the cursor has passed" to counted.copy(cursor = 10),
        )
        for ((what, marker) in impossible) {
            assertTrue(marker.defect() != null, what)
            assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(marker.encode()), what)
        }

        val frame = counted.encode()
        // The counted format always carries a count, and a count is never in the other format.
        assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(plain.encode().also { it[0] = MIGRATION_COUNTED_FORMAT.toByte() }))
        assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(frame.copyOf().also { it[0] = JOB_MARKER_FORMAT.toByte() }))
        for (cut in 1..20) assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(frame.copyOf(frame.size - cut)), "cut by $cut")
        assertEquals(DecodedMarker.Malformed, MigrationMarker.decode(frame + 0.toByte()))
        // A later format still, and the counted format on a marker that has none: a newer build's.
        assertEquals(DecodedMarker.OtherFormat(3), MigrationMarker.decode(frame.copyOf().also { it[0] = 3 }))
        val wipe = WipeMarker("forget", "Control", 0L, emptyMap()).encode()
        assertEquals(DecodedMarker.OtherFormat(2), WipeMarker.decode(wipe.also { it[0] = MIGRATION_COUNTED_FORMAT.toByte() }))

        // The one way a cursor moves keeps a count only while its row is ahead.
        assertEquals(failure, counted.at(null).failure)
        assertEquals(failure, counted.at(8).failure)
        assertNull(counted.at(9).failure)
        assertNull(counted.at(Long.MAX_VALUE).failure)
        assertEquals(plain.copy(phase = MigrationPhase.VERIFY, cursor = null), counted.entering(MigrationPhase.VERIFY))
    }

    @Test
    fun aMalformedCountOnDiskIsDamageAndACountedFrameOfALaterFormatIsSkewWithNothingWritten() {
        fun marked(frame: ByteArray): String {
            val directory = anOldStore(chunky, rows = 5)
            ReplicaProcess(directory).sql { it.exec("INSERT INTO meta (k, v) VALUES ('migration', ${blobLiteral(frame)})") }
            return directory
        }
        val counted = MigrationMarker("test", 0L, mapOf(file to 3, sync to 9), file, 1, MigrationPhase.ROWS, null, MigrationFailure(3, 3, 1, 3))

        // What a build without the count sees in a counted frame is a format it does not know.
        // This build meets the same thing in the format after its own, by the same rule.
        val later = marked(counted.encode().also { it[0] = 3 })
        val database = bytesOf("$later/objects.db")
        val wal = bytesOf("$later/objects.db-wal")
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(later, chunky).open(newBuild))
        assertIs<ReplicaOpenResult.Skew>(ReplicaProcess(later, chunky).open(newBuild, foreground = false))
        assertIs<ReplicaRunOutcome.Skew>(assertFailsWith<WipeRefused> { ReplicaProcess(later, chunky).opener(newBuild).wipe("discard") }.outcome)
        assertContentEquals(database, bytesOf("$later/objects.db"))
        assertContentEquals(wal, bytesOf("$later/objects.db-wal"))

        // A count past its limit is damage, found by the read-only classification.
        val damaged = marked(counted.copy(failure = counted.failure!!.copy(count = 3)).encode())
        assertIs<ReplicaOpenResult.Corrupt>(ReplicaProcess(damaged, chunky).open(newBuild, foreground = false))
        assertTrue(fileExists("$damaged/${ReplicaCorruptMarker.NAME}"))
    }

    // ── every kind of failure, every run, every relation of build to marker, every kind of open ──

    /** What the run meets at the row its step fails on. */
    private enum class Kind(val counted: Boolean) {
        STEP_THROWS(true),
        ROW_OVER_ROW_MAX(true),
        ROW_MOVED_TO_ANOTHER_HASH(true),

        /** The stored bytes are not a row: SQLite reads them, the store's own decode fails. */
        ROW_DOES_NOT_DECODE(true),

        // Never counted: the run did not get to say what the step makes of the row, or could
        // not write what it found.
        ASKED_TO_STOP(false),
        GATE_CLOSED(false),

        /** The step ran past `HOLD_MAX` before it failed: nothing may be written under that lock. */
        HOLD_CEILING(false),
        INTERRUPTED_READING_THE_ROW(false),
        BUSY_READING_THE_ROW(false),
        DISK_FULL_WRITING_THE_COUNT(false),
        IO_ERROR_WRITING_THE_COUNT(false),
        PROTECTED_DATA_GONE(false),
        WAL_RESET_PENDING(false),

        /** A corruption code reading the row: the confirmation's, which finds a healthy file. */
        CORRUPTION_CODE_READING_THE_ROW(false),
        KILLED_AT_THE_COUNTS_COMMIT(false),
    }

    /** How the build that comes to the store relates to the marker it finds. */
    private enum class Relation {
        /** Earlier runs were this build's. */
        THE_COUNTERS_OWN,

        /** Earlier runs were a build's that declares another version of the step's service, and may resume the same chain. */
        ANOTHER_VERSION_OF_THE_SERVICE,

        /** The marker's targets are past this build: a newer build's migration, with its count. */
        A_NEWER_BUILDS_MARKER,

        /**
         * The frame is of a format this build does not know. ⚠ That is what a **build without the
         * count** finds in a counted frame (format 2 to its 1); no such build can be run here, so
         * this build is given the format after its own, which it judges by the same one rule.
         */
        A_FORMAT_THIS_BUILD_DOES_NOT_KNOW,
    }

    private enum class Open { BACKGROUND, FOREGROUND, WIPE_CALL }

    private class Expected(
        val verdict: String,
        /** `objects.db` and its `-wal` may differ afterwards. */
        val writes: Boolean,
        /** The count this build has stored afterwards, or null: no count of its own to check. */
        val count: Int? = null,
        val wiped: Boolean = false,
    )

    /**
     * Written from the rules, in their order, not from the code:
     * 1. a marker above this build, or of a format it does not know, is `SKEW` for every kind of
     *    open, whatever the run would have met: nothing judged, nothing written;
     * 2. after the run that converted, the store is an ordinary empty one;
     * 3. a wipe call wipes, count and all;
     * 4. a migration is not resumed outside the foreground: nothing can count in a wake;
     * 5. a failure that is not the step's on the row stops the run and writes nothing;
     * 6. the step's failure on the row is counted once: this build's count for this row and its
     *    own declared version plus one, starting at one after another version's; and at `N` the
     *    store is wiped instead, in the same run.
     */
    private fun expected(kind: Kind, run: Int, relation: Relation, open: Open): Expected {
        if (relation == Relation.A_NEWER_BUILDS_MARKER || relation == Relation.A_FORMAT_THIS_BUILD_DOES_NOT_KNOW) {
            return Expected("Skew", writes = false)
        }
        if (relation == Relation.THE_COUNTERS_OWN && run > attempts) {
            return if (open == Open.WIPE_CALL) Expected("Wiped", writes = true, wiped = true) else Expected("Ready", writes = false, wiped = true)
        }
        if (open == Open.WIPE_CALL) return Expected("Wiped", writes = true, wiped = true)
        if (open == Open.BACKGROUND) return Expected("NeedsForeground", writes = false)
        if (!kind.counted) return Expected("Stopped", writes = false)
        val mine = if (relation == Relation.THE_COUNTERS_OWN) priorCounts(run) else 0
        return if (mine + 1 >= attempts) Expected("Finished", writes = true, wiped = true)
        else Expected("Counted", writes = true, count = mine + 1)
    }

    /** The counts stored before run [run]: one per earlier run, and never `N` (that run wiped instead). */
    private fun priorCounts(run: Int): Int = minOf(run - 1, attempts - 1)

    /** The one row the step fails on: the first of its rows, so the run meets it in its first chunk. */
    private val badRow = 1L

    private inner class Scene(val kind: Kind, val run: Int, val relation: Relation, val open: Open) {
        val directory = anOldStore(tight, rows = 12)
        val problems = ArrayList<String>()
        lateinit var before: Before
        private var now = 0L
        private var asked = false

        private val steps = chain(bad = if (kind == Kind.ROW_DOES_NOT_DECODE) emptySet() else setOf(badRow)) { row ->
            when (kind) {
                Kind.ROW_OVER_ROW_MAX -> row.copy(fields = row.fields + DbValue.StringValue("x".repeat(tight.rowMaxBytes)))
                Kind.ROW_MOVED_TO_ANOTHER_HASH -> row.copy(dataIdHash = row.dataIdHash + 100_000)
                Kind.HOLD_CEILING -> {
                    now += tight.holdMaxMs
                    throw IllegalStateException(secret)
                }
                else -> throw IllegalStateException(secret)
            }
        }

        /** The store as run [run] finds it: the chain started, and the earlier runs' count in its marker. */
        fun prepare() {
            val starter = ReplicaProcess(directory, tight).also { it.steps = steps }
            if (kind == Kind.ROW_DOES_NOT_DECODE) starter.sql { it.exec("UPDATE objects SET data = x'ff' WHERE hash = $badRow") }
            if (starter.replacement(newBuild).admit(foreground = true) != ChunkResult.More(ReplicaJob.MIGRATION)) problems += "the chain did not start"
            val started = markerOf(directory)!!
            val prior = priorCounts(run)
            fun counted(version: Int) = if (prior == 0) null else MigrationFailure(badRow, version, prior, attempts)
            val frame = when (relation) {
                Relation.THE_COUNTERS_OWN -> started.copy(failure = counted(3)).encode()
                Relation.ANOTHER_VERSION_OF_THE_SERVICE -> started.copy(failure = counted(4)).encode()
                Relation.A_NEWER_BUILDS_MARKER -> started.copy(targets = mapOf(file to 4, sync to 9), failure = counted(4)).encode()
                Relation.A_FORMAT_THIS_BUILD_DOES_NOT_KNOW -> started.copy(failure = counted(3)).encode().also { it[0] = 3 }
            }
            starter.sql { it.exec("UPDATE meta SET v = ${blobLiteral(frame)} WHERE k = 'migration'") }
            if (relation == Relation.THE_COUNTERS_OWN && run > attempts) {
                // The Nth run, which converted and wiped.
                val outcome = launch(directory, steps, limits = tight)
                if (outcome != ReplicaRunOutcome.Finished) problems += "the run before this one did not convert: $outcome"
            }
            snapshot()
        }

        /** The files and the marker as the run is about to find them. */
        private fun snapshot() {
            val process = ReplicaProcess(directory, tight)
            val marker = process.metaBlob("migration")?.let { (MigrationMarker.decode(it) as? DecodedMarker.Value<MigrationMarker>)?.marker }
            before = Before(bytesOf("$directory/objects.db")!!, bytesOf("$directory/objects.db-wal")!!, process.metaInt("epoch"), marker)
        }

        /** What stands between this run and its count. */
        private fun arm(process: ReplicaProcess, job: ReplicaReplacement) {
            fun onStatement(sql: String, code: Int) {
                job.statementFault = { if (it == sql) throw SqliteException(code, "injected") }
            }
            when (kind) {
                Kind.ASKED_TO_STOP -> asked = true
                Kind.GATE_CLOSED -> process.registry.gate.closeAndDrain(0)
                Kind.HOLD_CEILING -> job.clock = { now }
                Kind.INTERRUPTED_READING_THE_ROW -> onStatement(SharedReplicaStore.POINT_READ_SQL, SQLITE_INTERRUPT)
                Kind.BUSY_READING_THE_ROW -> onStatement(SharedReplicaStore.POINT_READ_SQL, SQLITE_BUSY)
                Kind.DISK_FULL_WRITING_THE_COUNT -> onStatement(ReplicaJobSql.UPSERT_META, SQLITE_FULL)
                Kind.IO_ERROR_WRITING_THE_COUNT -> onStatement(ReplicaJobSql.UPSERT_META, SQLITE_IOERR)
                Kind.PROTECTED_DATA_GONE -> {
                    process.protectedData = false
                    onStatement(SharedReplicaStore.POINT_READ_SQL, SQLITE_IOERR)
                }
                Kind.WAL_RESET_PENDING -> {
                    // A sibling that knows none of the store's rules grows the log past the ceiling
                    // (after the open, whose own transaction would have reset it), and the reset
                    // this run then needs is kept out.
                    process.sql { connection ->
                        var next = 100_000L
                        while (bytesOf("${process.databasePath}-wal")!!.size <= tight.beginCeilingBytes) {
                            connection.exec("INSERT INTO objects (hash, type, data) VALUES (${next++}, 'x', zeroblob(3000))")
                        }
                    }
                    snapshot()
                    job.checkpointer.fault = { throw SqliteException(SQLITE_BUSY, "database is locked") }
                }
                Kind.CORRUPTION_CODE_READING_THE_ROW -> onStatement(SharedReplicaStore.POINT_READ_SQL, SQLITE_CORRUPT)
                Kind.KILLED_AT_THE_COUNTS_COMMIT -> job.beforeCommit = { throw RuntimeException("killed") }
                Kind.STEP_THROWS, Kind.ROW_OVER_ROW_MAX, Kind.ROW_MOVED_TO_ANOTHER_HASH, Kind.ROW_DOES_NOT_DECODE -> Unit
            }
        }

        fun verdict(): String {
            val process = ReplicaProcess(directory, tight).also { it.steps = steps }
            val verdict = try {
                if (open == Open.WIPE_CALL) {
                    if (process.opener(newBuild).wipe("discard")) "Wiped" else "Nothing"
                } else {
                    when (val result = process.open(newBuild, foreground = open == Open.FOREGROUND)) {
                        is ReplicaOpenResult.Ready -> "Ready".also { result.store.close() }
                        is ReplicaOpenResult.Skew -> "Skew"
                        is ReplicaOpenResult.Corrupt -> "Corrupt"
                        is ReplicaOpenResult.Updating -> {
                            arm(process, result.replacement)
                            when (val outcome = result.replacement.run { asked }) {
                                ReplicaRunOutcome.Finished -> "Finished"
                                is ReplicaRunOutcome.Stopped -> {
                                    if (secret in outcome.reason) problems += "the outcome quotes the row: ${outcome.reason}"
                                    val cause = outcome.cause
                                    if (cause is MigrationRowFailed) {
                                        if (secret in cause.message.orEmpty()) problems += "the cause's message quotes the row"
                                        if (cause.typeId != file || cause.serviceId != file || cause.fromVersion != 1) problems += "the cause names $cause"
                                        "Counted"
                                    } else {
                                        "Stopped"
                                    }
                                }
                                else -> "$outcome"
                            }
                        }
                    }
                }
            } catch (e: WipeRefused) {
                if (e.outcome is ReplicaRunOutcome.Skew) "Skew" else "WipeRefused(${e.outcome})"
            } catch (_: StoreNeedsForeground) {
                "NeedsForeground"
            } catch (e: StoreUnavailable) {
                "Unavailable(${e.message})"
            }
            // Handles: none is left registered, whatever the run met.
            if (process.registry.handles().isNotEmpty()) problems += "handles left: ${process.registry.handles().map { it.label }}"
            // Admissions: every one taken was given back.
            process.registry.gate.reopen()
            if (!process.registry.gate.closeAndDrain(0)) problems += "an admission was not given back"
            return verdict
        }

        fun check(expected: Expected) {
            val process = ReplicaProcess(directory, tight)
            if (fileExists("$directory/${ReplicaCorruptMarker.NAME}")) problems += "replica/CORRUPT was written"
            if (!expected.writes) {
                // Byte for byte: no count, no cursor, no epoch, no stamp.
                if (!before.database.contentEquals(bytesOf("$directory/objects.db"))) problems += "objects.db was written"
                if (!before.wal.contentEquals(bytesOf("$directory/objects.db-wal"))) problems += "the -wal was written"
            }
            val epoch = process.metaInt("epoch")
            // Fences: the epoch is what fences every handle, and only a conversion or a wipe moves it here.
            val moved = expected.verdict == "Finished" || expected.verdict == "Wiped"
            if ((epoch != before.epoch) != moved) problems += "the epoch ${if (moved) "did not move" else "moved"}"
            expected.count?.let { count ->
                val marker = markerOf(directory)
                // Exactly one more, for this row and this build's declared version, and nothing else of the marker.
                val wanted = before.marker!!.copy(failure = MigrationFailure(badRow, 3, count, attempts))
                if (marker != wanted) problems += "the marker is $marker, not $wanted"
                if (process.metaBlob("migration")!![0].toInt() != MIGRATION_COUNTED_FORMAT) problems += "a counted frame of another format"
                if (process.count("migration_done") != 0L) problems += "a row was recorded as migrated"
            }
            if (expected.wiped) {
                for (table in ReplicaSchema.tablesEmptiedByWipe) if (process.count(table) != 0L) problems += "$table is not empty"
                if (process.hasMeta("wipe") || process.hasMeta("migration")) problems += "a marker is left"
                if (process.stamp(file) != 3L || process.stamp(sync) != 9L) problems += "not at this build's versions"
            }
        }
    }

    private class Before(val database: ByteArray, val wal: ByteArray, val epoch: Long, val marker: MigrationMarker?)

    @Test
    fun everyKindOfFailureInEveryRunForEveryRelationAndEveryOpenCountsOnlyWhatTheRulesCount() {
        val wrong = ArrayList<String>()
        var cases = 0
        for (kind in Kind.entries) for (run in 1..attempts + 1) for (relation in Relation.entries) for (open in Open.entries) {
            cases++
            val case = "$kind / run $run / $relation / $open"
            val expected = expected(kind, run, relation, open)
            val scene = Scene(kind, run, relation, open)
            scene.prepare()
            val descriptors = openDescriptorCount()

            val verdict = scene.verdict()
            if (verdict != expected.verdict) wrong += "$case: expected ${expected.verdict}, got $verdict"
            scene.check(expected)
            if (openDescriptorCount() != descriptors) wrong += "$case: descriptors went from $descriptors to ${openDescriptorCount()}"
            scene.problems.forEach { wrong += "$case: $it" }
        }
        assertEquals(Kind.entries.size * (attempts + 1) * Relation.entries.size * Open.entries.size, cases)
        assertEquals(emptyList<String>(), wrong, "${wrong.size} of $cases combinations")
    }
}
