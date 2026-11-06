package org.tenshu.inference.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase
import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.CrashingFiles.Kill
import org.tenshu.inference.proxy.CrashingFiles.ReadFault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The crash-point table for the two device-local logs** (`media-processing-pipeline-sweep.md` F0)
 * — the argument that a removal, a wipe and a rebase each leave exactly one consistent store
 * behind, wherever they are stopped.
 *
 * ⚠⚠ **What is being claimed.** After a kill or an I/O failure at any file operation, a reopen
 * yields every row that should stay, byte for byte; each row being removed either whole or gone;
 * nothing else; and never a row a wipe destroyed. Recovery is the ordinary parse of the one log —
 * there is no second file, so there is nothing whose presence has to be interpreted.
 *
 * Two layers, on purpose:
 *  - the **named** tests are the table — one crash point each, saying what must be true there;
 *  - the **sweeps** stop every mutating call of each operation in every way [CrashingFiles] knows
 *    and check the invariant, so a call added later is covered before anyone thinks to name it.
 *
 * ⭐ Every test runs against **both** stores: they share [LogStrikes] and nothing else, and a guard
 * present in one and missing in the other is the defect this file exists to catch.
 */
class LogStrikesCrashPointsTest {

    private val model = ModelProvenance.Fake
    private val nextModel = ModelProvenance("fake", 1)

    /** A store, reduced to what the table asks of it. */
    private interface Opened {
        suspend fun put(photo: String, content: Float): Boolean
        suspend fun visit(photo: String)

        /** Every row, by what it holds — `""` for a visit that derived nothing. */
        suspend fun held(): Map<String, String>
        suspend fun remembered(): Set<String>
        suspend fun forget(photos: Collection<String>): Removal
        suspend fun clear()
        suspend fun rebase()
        suspend fun provenance(): ModelProvenance?

        /** Struck records whose bytes the store still has queued for erasure. */
        suspend fun notErased(): Int
    }

    private abstract class Subject(val name: String, val path: String) {
        abstract fun open(files: FileDatabase): Opened

        /** A `REMOVE` tombstone as builds before P0f-a appended it. Nothing writes one any more. */
        abstract fun legacyRemove(photo: String): ByteArray
        override fun toString() = name
    }

    private val faces = object : Subject("faces", "inference/faces.log") {
        override fun open(files: FileDatabase): Opened {
            val store = DefaultFaceTemplateStore(files, path)
            return object : Opened {
                override suspend fun put(photo: String, content: Float) = store.put(
                    photo,
                    listOf(StoredFace(FaceBox(content, 0.1f, 0.2f, 0.3f), floatArrayOf(content, content, content, content))),
                    model,
                )

                override suspend fun visit(photo: String) {
                    store.put(photo, emptyList(), model)
                }

                override suspend fun held() = store.rows().associate { row ->
                    row.filePath to row.faces.joinToString { "${it.box}${it.vector.toList()}" }
                }

                override suspend fun remembered() = store.remembered()
                override suspend fun forget(photos: Collection<String>) = store.forget(photos)
                override suspend fun clear() = store.clear()
                override suspend fun rebase() = error("the face store has no rebase; a model change clears it")
                override suspend fun provenance() = store.provenance()
                override suspend fun notErased() = store.recordsNotErased()
            }
        }

        override fun legacyRemove(photo: String) = pathOnly(flag = 2, photo)
    }

    private val vectors = object : Subject("vectors", "inference/vectors.log") {
        override fun open(files: FileDatabase): Opened {
            val store = DefaultEmbeddingStore(files, path)
            return object : Opened {
                override suspend fun put(photo: String, content: Float) =
                    store.put(photo, floatArrayOf(content, content, content, content), model)

                override suspend fun visit(photo: String) {
                    store.markVisited(photo)
                }

                override suspend fun held(): Map<String, String> {
                    val withVector = store.entries().associate { it.first to it.second.toList().toString() }
                    val visits = store.remembered().filter { it !in withVector && store.visited(it) }
                    return withVector + visits.associateWith { "" }
                }

                override suspend fun remembered() = store.remembered()
                override suspend fun forget(photos: Collection<String>) = store.forget(photos)
                override suspend fun clear() = store.clear()
                override suspend fun rebase() = store.rebase(nextModel)
                override suspend fun provenance() = store.provenance()
                override suspend fun notErased() = store.recordsNotErased()
            }
        }

        override fun legacyRemove(photo: String) = pathOnly(flag = 0, photo)
    }

    private val stores = listOf(faces, vectors)

    // ---- the fixture -------------------------------------------------------------------------------

    /**
     * ⚠ The dead rows sit BETWEEN live ones, and one dead photograph has two records. A removal that
     * damaged the record after it, or a replay that lost its place after a struck record, shows as a
     * live row gone; a strike in the wrong order shows as the older template coming back.
     */
    private suspend fun Opened.seed() {
        put("/keep/a.jpg", 1.25f)
        put(TWICE, OLDER)
        put(GONE, GONE_CONTENT)
        visit(GONE_VISIT)
        put(TWICE, LATEST)
        visit("/keep/v.raw")
        put("/keep/b.jpg", 2.5f)
    }

    private val dead = listOf(GONE, GONE_VISIT, TWICE)

    private fun raw(disk: InMemoryFileDatabase, subject: Subject): ByteArray =
        disk.readBytes(subject.path)?.get()?.copyOf() ?: ByteArray(0)

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun ByteArray.holds(needle: ByteArray) = indexOf(needle) >= 0

    private fun floatBytes(value: Float): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(4) { ((bits shr (it * 8)) and 0xFF).toByte() }
    }

    /** What is left of [photo] in the log's bytes: its path, or the floats derived from it. */
    private fun leftovers(raw: ByteArray, photo: String, vararg contents: Float): List<String> = buildList {
        if (raw.holds(photo.encodeToByteArray())) add("the path $photo")
        contents.forEach { if (raw.holds(floatBytes(it))) add("floats of $photo") }
    }

    private fun assertErased(raw: ByteArray, what: String) {
        val left = leftovers(raw, GONE, GONE_CONTENT) + leftovers(raw, GONE_VISIT) + leftovers(raw, TWICE, OLDER, LATEST)
        assertEquals(emptyList(), left, "$what: still on disk")
    }

    /**
     * The invariant: every row that should stay is there unchanged, and nothing is there that was
     * not — so a row being removed is whole or gone, never altered and never an older version.
     */
    private suspend fun Opened.assertOneConsistentState(before: Map<String, String>, what: String) {
        val now = held()
        before.filterKeys { it !in dead }.forEach { (photo, content) ->
            assertEquals(content, now[photo], "$what: a row that must stay was lost or changed — $photo")
        }
        now.forEach { (photo, content) ->
            assertEquals(before[photo], content, "$what: $photo holds something nobody wrote")
        }
    }

    private suspend fun eachStore(block: suspend (Subject) -> Unit) = stores.forEach { block(it) }

    private class Seeded(val files: CrashingFiles, val store: Opened, val before: Map<String, String>)

    private suspend fun seeded(subject: Subject): Seeded {
        val files = CrashingFiles()
        val store = subject.open(files).apply { seed() }
        return Seeded(files, store, store.held())
    }

    // ---- the table: a removal ----------------------------------------------------------------------
    // forget(one record) is two writes: [0] the strike, one byte; [1] the scrub, the record's content.

    @Test
    fun killedBeforeTheStrikeLandsTheRowIsStillWhole() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.DIES_BEFORE)

            assertTrue(runCatching { store.forget(listOf(GONE)) }.isFailure)

            assertEquals(before, subject.open(files.disk).held(), "$subject")
        }
    }

    @Test
    fun aStrikeThatFailsLeavesTheRowInMemoryAndOnDiskAndARetryRemovesIt() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.FAILS)

            val refusal = store.forget(listOf(GONE))

            assertEquals(0, refusal.removed, "$subject")
            assertTrue(refusal.refused is CrashingFiles.IoFailure, "$subject: ${refusal.refused}")
            // ⚠⚠ Disk first, memory second: a row memory had already dropped could not be retried,
            // and the next start would bring it back.
            assertEquals(before, store.held(), "$subject: memory forgot a row the log still holds")
            assertEquals(before, subject.open(files.disk).held(), "$subject")

            files.watch()
            val retried = store.forget(listOf(GONE))
            assertTrue(retried.complete && retried.removed == 1, "$subject: $retried")
            assertEquals(before - GONE, subject.open(files.disk).held(), "$subject")
        }
    }

    /**
     * ⚠⚠ One photograph's strike failing must not leave another's bytes on disk: what this call
     * already struck is scrubbed before the failure is raised.
     */
    @Test
    fun aStrikeThatFailsStillErasesWhatTheSameCallAlreadyStruck() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            // [0] strikes GONE; [1] is the first strike of TWICE.
            files.arm(call = 1, Kill.FAILS)

            val removal = store.forget(listOf(GONE, TWICE))

            assertEquals(1, removal.removed, "$subject: the row that WAS removed must count")
            assertTrue(removal.refused is CrashingFiles.IoFailure, "$subject: ${removal.refused}")
            assertEquals(0, removal.recordsNotErased, "$subject")
            assertEquals(before - GONE, store.held(), "$subject")
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject: struck, and left unzeroed")
        }
    }

    /** ⚠⚠ A write that returned having done nothing — the read-back is the only thing that sees it. */
    @Test
    fun aStrikeThatSilentlyDidNothingIsNotTakenForARemoval() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.IGNORED)

            val removal = store.forget(listOf(GONE))

            assertEquals(0, removal.removed, "$subject")
            assertTrue(removal.refused is IllegalStateException, "$subject: ${removal.refused}")
            assertEquals(before, store.held(), "$subject: reported removed, and the next start would bring it back")
            assertEquals(before, subject.open(files.disk).held(), "$subject")
        }
    }

    @Test
    fun killedBetweenTheStrikeAndTheScrubTheRowIsGoneAndTheNextOpenErasesItsBytes() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.DIES_AFTER)

            assertTrue(runCatching { store.forget(listOf(GONE)) }.isFailure)
            assertTrue(leftovers(raw(files.disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition: struck, not yet zeroed")

            val reopened = subject.open(files.disk)

            assertEquals(before - GONE, reopened.held(), "$subject: the strike IS the removal")
            // ⚠⚠ No call was made on the reopened store: opening it is what erased the bytes.
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    @Test
    fun aScrubThatFailsReportsTheErasureOwedAndTheNextRemovalFinishesIt() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 1, Kill.FAILS)

            val owed = store.forget(listOf(GONE))

            assertEquals(1, owed.removed, "$subject: the row IS removed; only its bytes are owed")
            assertTrue(owed.erasureOwed is CrashingFiles.IoFailure && owed.recordsNotErased == 1, "$subject: $owed")
            assertFalse(owed.complete, "$subject")
            assertEquals(before - GONE, store.held(), "$subject")
            assertEquals(before - GONE, subject.open(InMemoryFileDatabase().also { copy(files.disk, it, subject) }).held(), "$subject")
            assertTrue(leftovers(raw(files.disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition: the bytes are still there")

            // ⚠ A removal of nothing is still the retry.
            files.watch()
            assertTrue(store.forget(emptyList()).complete, "$subject")
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    /**
     * ⚠⚠ The strike's failure mode, one write later: a scrub that returned having written nothing.
     * Dequeued on the strength of the call coming back, the record would be reported erased and
     * never retried by this process — its path and floats in the log until a restart.
     */
    @Test
    fun aScrubThatSilentlyDidNothingStaysOwedIsReportedAndTheNextRemovalErasesIt() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 1, Kill.IGNORED)

            val owed = store.forget(listOf(GONE))

            assertEquals(1, owed.removed, "$subject: the row IS removed; only its bytes are owed")
            assertTrue(owed.erasureOwed != null && owed.recordsNotErased == 1, "$subject: $owed")
            assertEquals(before - GONE, store.held(), "$subject")
            assertTrue(leftovers(raw(files.disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition: nothing was written")

            // ⚠ The same process, no reopen: the record must still be queued.
            files.watch()
            assertTrue(store.forget(emptyList()).complete, "$subject")
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    @Test
    fun killedHalfWayThroughTheScrubTheRowsAfterItStillParse() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 1, Kill.DIES_TORN)

            runCatching { store.forget(listOf(GONE)) }

            // ⚠⚠ The scrub rewrites the record's length fields with the values they already had, so
            // any prefix of it leaves a record the replay can still step over.
            val reopened = subject.open(files.disk)
            assertEquals(before - GONE, reopened.held(), "$subject: a half-zeroed record cost the rows after it")
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    @Test
    fun anOpenThatCannotWriteStillOpensAndTheErasureWaitsForTheNextRemoval() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.DIES_AFTER)
            assertTrue(runCatching { store.forget(listOf(GONE)) }.isFailure)

            val readOnly = CrashingFiles(files.disk).apply { refuseWrites = true }
            val reopened = subject.open(readOnly)

            // ⚠ The constructor's scrub is best-effort: a disk that cannot be written must not turn
            // a store that reads fine into one that will not open.
            assertEquals(before - GONE, reopened.held(), "$subject")
            assertTrue(leftovers(raw(files.disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition")

            readOnly.refuseWrites = false
            reopened.forget(emptyList())
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    /**
     * ⚠⚠ A photograph indexed twice has two records, and the replay takes the last one standing.
     * Struck newest first, a kill between the two would bring the OLDER template back.
     */
    @Test
    fun killedBetweenTwoRecordsOfOnePhotographNeverBringsBackTheOlderTemplate() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.DIES_AFTER)

            assertTrue(runCatching { store.forget(listOf(TWICE)) }.isFailure)

            assertEquals(before, subject.open(files.disk).held(), "$subject: the row must be exactly what it was")
        }
    }

    /**
     * ⚠ Not reachable by killing a process — the strike is written first — but a power cut can
     * persist the scrub's page and lose the strike's: `FileDatabase` has no `fsync`. The record then
     * stands, flag and all, over a path of zeros.
     */
    @Test
    fun aScrubThatOutlivedItsStrikeIsReadAsARemoval() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            val recordAt = raw(files.disk, subject).indexOf(GONE.encodeToByteArray()) - 3
            val flag = raw(files.disk, subject)[recordAt]
            store.forget(listOf(GONE))
            files.disk.writeBytesRange(subject.path, recordAt.toLong(), byteArrayOf(flag))

            val reopened = subject.open(files.disk)

            assertEquals(before - GONE, reopened.held(), "$subject: a row of zeros came back")
            assertTrue(reopened.put("/new.jpg", 9.5f))
            assertEquals((before - GONE).keys + "/new.jpg", subject.open(files.disk).held().keys, "$subject")
        }
    }

    /**
     * ⚠⚠ A removal writes inside the dead records and nowhere else — no second file, no copy of the
     * log, no read of more than one record. Rewriting the whole log to drop a row would build it in
     * memory twice over.
     */
    @Test
    fun aRemovalWritesOnlyInsideTheDeadRecordsAndNeverCopiesTheLog() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            val log = raw(files.disk, subject)
            files.watch()

            store.forget(dead)

            assertTrue(files.calls.all { it.name == "range" && it.path == subject.path }, "$subject: ${files.calls}")
            // ⚠ The whole cost, per record removed: the strike and the scrub, and three reads — the
            // strike's byte back, the record to blank, the record back as zeros. Four records here
            // (one photograph has two). A read-back that grew with the log would show in this count.
            assertEquals(2 * 4, files.calls.size, "$subject: writes")
            assertEquals(3 * 4, files.rangeReads, "$subject: reads")
            assertEquals(setOf(subject.path), files.touched, "$subject: a second file")
            assertEquals(0, files.wholeReads, "$subject: the log was read whole")
            assertTrue(files.largestRead < log.size / 2, "$subject: read ${files.largestRead} of ${log.size} bytes at once")
            listOf("/keep/a.jpg", "/keep/v.raw", "/keep/b.jpg").forEach { live ->
                val at = log.indexOf(live.encodeToByteArray()).toLong()
                assertTrue(
                    files.calls.none { at >= it.offset && at < it.offset + it.length },
                    "$subject: a write landed inside the live record of $live",
                )
            }
            assertEquals(log.size, raw(files.disk, subject).size, "$subject: the log changed length")
        }
    }

    // ---- the table: a wipe -------------------------------------------------------------------------
    // clear() is two calls: [0] the log is replaced by nothing; [1] the file is deleted.

    @Test
    fun aWipeWhoseFirstWriteFailsWipesNothingAndSaysSo() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.FAILS)

            assertFailsWith<CrashingFiles.IoFailure> { store.clear() }

            assertEquals(before, store.held(), "$subject")
            assertEquals(before, subject.open(files.disk).held(), "$subject")
        }
    }

    /** ⚠⚠ The write that fails may have emptied the file first; memory must then say empty too. */
    @Test
    fun aWipeWhoseFirstWriteFailsAfterEmptyingTheFileLeavesMemoryAgreeing() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 0, Kill.FAILS_TORN)

            assertFailsWith<CrashingFiles.IoFailure> { store.clear() }

            assertEquals(emptyMap(), subject.open(files.disk).held(), "$subject")
            assertEquals(emptyMap(), store.held(), "$subject: memory still serves rows the file no longer has")
            assertNull(store.provenance(), "$subject")
        }
    }

    @Test
    fun aWipeKilledAfterItsFirstWriteReopensEmpty() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 0, Kill.DIES_AFTER)

            assertFailsWith<CrashingFiles.Killed> { store.clear() }

            val reopened = subject.open(files.disk)
            assertEquals(emptyMap(), reopened.held(), "$subject")
            assertEquals(emptySet(), reopened.remembered(), "$subject")
            assertNull(reopened.provenance(), "$subject")
        }
    }

    @Test
    fun aWipeWhoseDeleteDoesNothingReopensEmptyAndTheStoreStillWorks() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            // ⚠ A standing refusal, not "the second call": the test must still bite when the wipe
            // is changed to make fewer calls — which is exactly the change it guards against.
            files.ignoreDeletes = true

            store.clear()

            assertTrue(files.calls.any { it.name == "delete" }, "$subject: precondition — a delete was attempted and refused")
            assertEquals(emptyMap(), subject.open(files.disk).held(), "$subject: the wipe relied on a delete that did nothing")
            assertTrue(store.put("/new.jpg", 9.5f))
            assertEquals(setOf("/new.jpg"), subject.open(files.disk).held().keys, "$subject")
        }
    }

    @Test
    fun aWipeKilledAfterItsDeleteReopensEmpty() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 1, Kill.DIES_AFTER)

            assertFailsWith<CrashingFiles.Killed> { store.clear() }

            assertNull(files.disk.fileSize(subject.path), "$subject")
            assertEquals(emptyMap(), subject.open(files.disk).held(), "$subject")
        }
    }

    /**
     * ⚠⚠ The outcome the earlier design could produce and this one cannot: a reconcile killed
     * part-way left a recovery copy, and a wipe that missed it was undone on the next open. There is
     * no copy here. The log is the only file either operation ever names.
     */
    @Test
    fun aWipeAfterAHalfFinishedRemovalLeavesNothingToComeBack() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 0, Kill.DIES_AFTER)
            assertTrue(runCatching { store.forget(dead) }.isFailure)

            val next = CrashingFiles(files.disk)
            subject.open(next).clear()

            assertEquals(setOf(subject.path), files.touched + next.touched, "$subject: another file exists to resurrect from")
            assertNull(files.disk.fileSize(subject.path), "$subject")
            assertEquals(emptyMap(), subject.open(files.disk).held(), "$subject")
        }
    }

    // ---- the table: a rebase (the vector store's model change) --------------------------------------
    // rebase() is one call: [0] the log is replaced by the new model's header.

    private suspend fun rebased(kill: Kill): Triple<CrashingFiles, DefaultEmbeddingStore, Result<Unit>> {
        val files = CrashingFiles()
        val store = DefaultEmbeddingStore(files, vectors.path)
        store.put("/keep/a.jpg", floatArrayOf(1f, 2f), model)
        store.markVisited("/keep/v.raw")
        files.arm(call = 0, kill)
        return Triple(files, store, runCatching { store.rebase(nextModel) })
    }

    @Test
    fun aRebaseKilledBeforeItsWriteKeepsTheOldModelsLogWhole() = runTest {
        val (files, _, outcome) = rebased(Kill.DIES_BEFORE)

        assertTrue(outcome.isFailure)
        val reopened = DefaultEmbeddingStore(files.disk, vectors.path)
        assertEquals(model, reopened.provenance())
        assertEquals(setOf("/keep/a.jpg", "/keep/v.raw"), reopened.remembered())
    }

    @Test
    fun aRebaseWhoseWriteTearsReopensEmptyNeverOldVectorsUnderTheNewModel() = runTest {
        val (files, _, outcome) = rebased(Kill.DIES_TORN)

        assertTrue(outcome.isFailure)
        val reopened = DefaultEmbeddingStore(files.disk, vectors.path)
        assertNull(reopened.provenance())
        assertEquals(emptySet(), reopened.remembered())
        // …and it is a store again as soon as something is written.
        assertTrue(reopened.put("/new.jpg", floatArrayOf(1f, 2f), nextModel))
        assertEquals(nextModel, DefaultEmbeddingStore(files.disk, vectors.path).provenance())
    }

    @Test
    fun aRebaseThatFailsLeavesMemoryAgreeingWithTheLog() = runTest {
        for (kill in listOf(Kill.FAILS, Kill.FAILS_TORN)) {
            val (files, store, outcome) = rebased(kill)

            assertTrue(outcome.isFailure, "$kill")
            val reopened = DefaultEmbeddingStore(files.disk, vectors.path)
            assertEquals(reopened.provenance(), store.provenance(), "$kill: memory claims a model the log does not")
            assertEquals(reopened.remembered(), store.remembered(), "$kill")
            assertEquals(if (kill == Kill.FAILS) 1 else 0, store.size(), "$kill")
        }
    }

    // ---- a failure path may not lose what is on disk --------------------------------------------

    /**
     * ⚠⚠ The append LANDS and the read that would confirm it fails. The caller is told the write
     * failed — and a complete record is in the log. Left at that, a `remove` could not strike a
     * record the store does not know about, and the next open would replay a `put` that "failed".
     * Memory is rebuilt from the file instead, so the store holds the row and can remove it.
     */
    @Test
    fun anAppendThatLandedButCouldNotBeConfirmedIsARowTheStoreHoldsAndCanRemove() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            // An append reads twice: the length before the write, and the length after it.
            files.armRead(call = 1, ReadFault.FAILS)

            assertFailsWith<CrashingFiles.IoFailure> { store.put("/new.jpg", 9.5f) }

            assertTrue("/new.jpg" in subject.open(readOnlyCopy(files.disk, subject)).held(), "precondition: the record landed")
            assertEquals(before.keys + "/new.jpg", store.held().keys, "$subject: the log holds a row memory does not")

            val removal = store.forget(listOf("/new.jpg"))
            assertTrue(removal.complete && removal.removed == 1, "$subject: $removal")
            assertEquals(before, subject.open(files.disk).held(), "$subject: the row came back on reopen")
            assertFalse(raw(files.disk, subject).holds("/new.jpg".encodeToByteArray()), "$subject: its bytes are still in the log")
        }
    }

    /**
     * ⚠⚠ The same ambiguity, for a removal: the strike LANDS and its read-back fails. The call must
     * report a refusal — it could not confirm — and yet the row is gone from the log. Memory is
     * rebuilt from the file, so the store does not go on serving a template a reopen would not.
     */
    @Test
    fun aStrikeThatLandedButCouldNotBeReadBackIsGoneFromMemoryToo() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            // A removal's first read is the strike's read-back.
            files.armRead(call = 0, ReadFault.FAILS)

            val removal = store.forget(listOf(GONE))

            assertTrue(removal.refused is CrashingFiles.IoFailure, "$subject: $removal")
            assertEquals(1, removal.removed, "$subject: counted from what the store holds, not from which call returned")
            assertEquals(before - GONE, store.held(), "$subject: memory still serves a row the log has struck")
            assertEquals(0, removal.recordsNotErased, "$subject: and its bytes were scrubbed in the same call")
            assertEquals(emptyList(), leftovers(raw(files.disk, subject), GONE, GONE_CONTENT), "$subject")
        }
    }

    /**
     * ⚠⚠ One batch: a photograph is struck, the next strike is refused, and the scrub of the first
     * fails too. Both failures must leave the call — the row that WAS removed, and the bytes it
     * still holds. Reporting only the refusal is reporting "nothing removed" over a removed row
     * whose biometric bytes are on disk.
     */
    @Test
    fun aRefusedStrikeDoesNotHideTheErasureDebtOfARowTheSameBatchRemoved() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            // [0] strikes GONE; [1], the first strike of TWICE, fails — and so does every write after.
            files.arm(call = 1, Kill.FAILS, thenWritesFail = true)

            val removal = store.forget(listOf(GONE, TWICE))

            assertEquals(1, removal.removed, "$subject: $removal")
            assertTrue(removal.refused != null, "$subject: the refusal was dropped — $removal")
            assertTrue(removal.erasureOwed != null, "$subject: the erasure debt was dropped — $removal")
            assertEquals(1, removal.recordsNotErased, "$subject")
            assertEquals(before - GONE, store.held(), "$subject")
            assertTrue(leftovers(raw(files.disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition: the bytes really are there")

            files.watch()
            assertTrue(store.forget(listOf(GONE, TWICE)).complete, "$subject")
            assertEquals(
                emptyList(),
                leftovers(raw(files.disk, subject), GONE, GONE_CONTENT) + leftovers(raw(files.disk, subject), TWICE, OLDER, LATEST),
                "$subject",
            )
        }
    }

    /** ⚠⚠ The debt survives across calls too: a later batch that is refused still reports it. */
    @Test
    fun anErasureOwedByAnEarlierCallIsStillReportedByALaterOneThatIsRefused() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 1, Kill.FAILS)
            assertEquals(1, store.forget(listOf(GONE)).recordsNotErased, "precondition: one record owed")

            files.refuseWrites = true
            val later = store.forget(listOf(TWICE))

            assertEquals(0, later.removed, "$subject")
            assertTrue(later.refused != null && later.erasureOwed != null, "$subject: $later")
            assertEquals(1, later.recordsNotErased, "$subject: the earlier call's debt went unreported")
        }
    }

    /**
     * ⚠⚠ **When recovery itself fails.** A write of unknown outcome, and then the file cannot be
     * re-read. The store does not know what it holds, so it holds nothing and refuses — reads and
     * writes alike — until a re-read succeeds; then it is in step again without a restart.
     */
    @Test
    fun aStoreThatCannotReReadItsLogRefusesRatherThanActingOnAGuess() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.FAILS_TORN, thenReadsFail = true)

            // The caller sees the failure that made the re-read necessary…
            assertFailsWith<CrashingFiles.IoFailure> { store.put("/new.jpg", 9.5f) }
            // …and from then on, the store saying it is out of step — never an empty answer.
            assertFailsWith<LogOutOfSync> { store.held() }
            assertFailsWith<LogOutOfSync> { store.put("/other.jpg", 1.5f) }
            assertFailsWith<LogOutOfSync> { store.forget(listOf(GONE)) }
            assertFailsWith<LogOutOfSync> { store.remembered() }

            files.watch()
            assertEquals(before, store.held(), "$subject: back in step, with no restart")
            assertTrue(store.put("/other.jpg", 1.5f))
            assertEquals(before.keys + "/other.jpg", subject.open(files.disk).held().keys, "$subject")
        }
    }

    /** ⚠⚠ A wipe needs no knowledge of what the log held, so a store that is out of step can still be wiped. */
    @Test
    fun aStoreThatIsOutOfStepCanStillBeWiped() = runTest {
        eachStore { subject ->
            val (files, store) = seeded(subject).let { it.files to it.store }
            files.arm(call = 0, Kill.FAILS_TORN, thenReadsFail = true)
            runCatching { store.put("/new.jpg", 9.5f) }
            assertFailsWith<LogOutOfSync> { store.held() }

            files.watch()
            store.clear()

            assertEquals(emptyMap(), store.held(), "$subject")
            assertEquals(emptyMap(), subject.open(files.disk).held(), "$subject")
        }
    }

    /** ⚠⚠ A log that will not read at open is out of step, not empty — and is not written over. */
    @Test
    fun aLogThatCannotBeReadAtOpenIsNotTakenForAnEmptyStore() = runTest {
        eachStore { subject ->
            val (files, _, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            val unreadable = CrashingFiles(files.disk).apply { readsFail = true }

            val store = subject.open(unreadable)

            assertFailsWith<LogOutOfSync> { store.held() }
            // ⚠ A `put` on a store that believed itself empty would write a header over the log.
            assertFailsWith<LogOutOfSync> { store.put("/new.jpg", 9.5f) }
            assertEquals(before, subject.open(readOnlyCopy(files.disk, subject)).held(), "$subject: the log was written over")

            unreadable.readsFail = false
            assertEquals(before, store.held(), "$subject")

            // ⚠ The same for a read that answers `null` instead of throwing — what Android's ranged
            // read does on an IOException, and indistinguishable from "no such file" unless the
            // size was asked first.
            val answersNull = CrashingFiles(files.disk)
            answersNull.armRead(call = 1, ReadFault.RETURNS_NULL)
            assertEquals(before, subject.open(answersNull).held(), "$subject: an unreadable log was taken for an empty store")
        }
    }

    // ---- "returned normally" is not "persisted" ---------------------------------------------------

    /** ⚠⚠ The file's length is what says an append landed; the call coming back says nothing. */
    @Test
    fun anAppendThatSilentlyDidNothingIsNotTakenForAWrite() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.IGNORED)

            assertFailsWith<IllegalStateException> { store.put("/new.jpg", 9.5f) }

            assertEquals(before, store.held(), "$subject: memory holds a row the log never got")
            // …and the store is not left writing past a hole: the next append lands and replays.
            files.watch()
            assertTrue(store.put("/new.jpg", 9.5f))
            assertEquals(before.keys + "/new.jpg", subject.open(files.disk).held().keys, "$subject")
        }
    }

    /**
     * ⚠⚠ The vector log leaves a torn tail in place and writes over it, so its length cannot say
     * whether an append landed: here the tail is exactly as long as the record. Only the bytes can.
     */
    @Test
    fun anAppendOverATornTailIsConfirmedByItsBytesNotByTheLength() = runTest {
        val files = CrashingFiles()
        val before = vectors.open(files).apply { seed() }.held()
        // What `put("/new.jpg", …)` writes is 29 bytes: flag, length, 8 of path, width, 4 floats.
        val torn = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(26)
        files.disk.writeBytesRange(vectors.path, files.disk.fileSize(vectors.path)!!, torn)
        val store = vectors.open(files)
        assertEquals(before, store.held(), "precondition: the tail is skipped")
        files.arm(call = 0, Kill.IGNORED)

        assertFailsWith<IllegalStateException> { store.put("/new.jpg", 9.5f) }

        assertEquals(before, store.held(), "memory holds a row the log never got")
        files.watch()
        assertTrue(store.put("/new.jpg", 9.5f))
        assertEquals(before.keys + "/new.jpg", vectors.open(files.disk).held().keys)
    }

    /**
     * ⚠⚠ The face log blanks a torn tail when it opens, and no append may run over one that is
     * still there. A blanking that returned having done nothing must leave the tail quarantined: a
     * shorter record written over it would leave a suffix the next open parses.
     */
    @Test
    fun aBlankingThatSilentlyDidNothingKeepsTheTornTailQuarantined() = runTest {
        val clean = InMemoryFileDatabase()
        faces.open(clean).apply {
            put("/keep/a.jpg", 1.25f)
            put("/new.jpg", 9.5f)
        }
        val files = CrashingFiles()
        faces.open(files).put("/keep/a.jpg", 1.25f)
        val torn = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(300) { 1 }
        files.disk.writeBytesRange(faces.path, files.disk.fileSize(faces.path)!!, torn)

        files.arm(call = 0, Kill.IGNORED)
        val store = faces.open(files)
        assertTrue(store.put("/new.jpg", 9.5f))

        val expected = raw(clean, faces)
        val actual = raw(files.disk, faces)
        assertEquals(expected.toList(), actual.copyOf(expected.size).toList())
        assertTrue((expected.size until actual.size).all { actual[it] == 0.toByte() }, "a stale suffix was left behind the new record")
    }

    /** ⚠⚠ A wipe that reported success over a file it never emptied is the worst of these. */
    @Test
    fun aWipeWhoseEmptyingSilentlyDidNothingIsNotReportedAsAWipe() = runTest {
        eachStore { subject ->
            val (files, store, before) = seeded(subject).let { Triple(it.files, it.store, it.before) }
            files.arm(call = 0, Kill.IGNORED)
            files.ignoreDeletes = true

            assertFailsWith<IllegalStateException> { store.clear() }

            assertEquals(before, store.held(), "$subject: memory says wiped, the log still holds everything")
            assertEquals(before, subject.open(files.disk).held(), "$subject")
        }
    }

    @Test
    fun aRebaseWhoseWriteSilentlyDidNothingIsNotReportedAsARebase() = runTest {
        val (files, store, outcome) = rebased(Kill.IGNORED)

        assertTrue(outcome.isFailure, "reported rebased over the old model's log")
        assertEquals(model, store.provenance())
        assertEquals(1, store.size())
        assertEquals(model, DefaultEmbeddingStore(files.disk, vectors.path).provenance())
    }

    // ---- logs written before this change -----------------------------------------------------------

    /**
     * ⚠⚠ A log from a build before P0f-a is a plain log: no struck record, and possibly `REMOVE`
     * tombstones. It opens exactly as it did — and the bytes a tombstone hid, which that build left
     * in the file, are erased by the first removal that names the path.
     */
    @Test
    fun aLogWrittenBeforeStrikesOpensTheSameAndItsTombstonedBytesAreErased() = runTest {
        eachStore { subject ->
            val disk = InMemoryFileDatabase()
            subject.open(disk).apply {
                put("/keep/a.jpg", 1.25f)
                put(GONE, GONE_CONTENT)
            }
            disk.writeBytesRange(subject.path, disk.fileSize(subject.path)!!, subject.legacyRemove(GONE))

            val reopened = subject.open(disk)

            assertEquals(setOf("/keep/a.jpg"), reopened.held().keys, "$subject: the tombstone still hides the row")
            assertTrue(GONE in reopened.remembered(), "$subject: a reconcile must be able to see it")
            assertTrue(leftovers(raw(disk, subject), GONE, GONE_CONTENT).isNotEmpty(), "precondition: hidden, not erased")

            reopened.forget(listOf(GONE))

            assertEquals(emptyList(), leftovers(raw(disk, subject), GONE, GONE_CONTENT), "$subject")
            assertEquals(setOf("/keep/a.jpg"), subject.open(disk).held().keys, "$subject")
            assertFalse(GONE in subject.open(disk).remembered(), "$subject")
        }
    }

    /**
     * ⚠⚠ A header written over a log the store could not attribute replaces the file, so where the
     * old records sat means nothing any more. A strike aimed at one of those offsets would flip a
     * byte in whatever was written there since.
     */
    @Test
    fun aHeaderWrittenOverAnUnattributableLogForgetsWhereItsRecordsWere() = runTest {
        val disk = InMemoryFileDatabase()
        val unparseable = "?".encodeToByteArray()
        disk.writeRawByteArray(
            vectors.path,
            "TSHUEMB1".encodeToByteArray() + short(unparseable.size) + unparseable +
                byteArrayOf(1) + short(GONE.length) + GONE.encodeToByteArray() + short(2) +
                floatBytes(GONE_CONTENT) + floatBytes(GONE_CONTENT),
        )
        val store = DefaultEmbeddingStore(disk, vectors.path)
        assertNull(store.provenance(), "precondition: a stamp this build cannot read")

        assertTrue(store.put("/keep/a.jpg", floatArrayOf(1f, 2f), model))
        store.forget(listOf(GONE))

        val reopened = DefaultEmbeddingStore(disk, vectors.path)
        assertEquals(model, reopened.provenance())
        assertEquals(listOf(1f, 2f), reopened.entries().single { it.first == "/keep/a.jpg" }.second.toList())
    }

    // ---- the sweeps: every call of every operation, stopped every way ------------------------------

    /** One injected fault: where, and what happens there. */
    private class Fault(val name: String, val arm: (CrashingFiles) -> Unit)

    /**
     * Every fault for an operation that makes [writes] mutating calls and [reads] non-mutating ones:
     *  - each write, stopped each of the six ways;
     *  - each write failing, tearing or silently doing nothing — **and every read after it failing**,
     *    so the re-read that recovery needs fails too;
     *  - each write failing **and every write after it failing**, a second failure inside the
     *    handling of the first;
     *  - each read failing once, answering `null`, failing from there on, or the process dying at it
     *    — the confirmation reads and the recovery reads are all among them.
     */
    private fun faults(writes: Int, reads: Int): List<Fault> = buildList {
        for (call in 0 until writes) for (kill in Kill.entries) {
            add(Fault("write $call ${kill.name}") { it.arm(call, kill) })
        }
        for (call in 0 until writes) for (kill in listOf(Kill.FAILS, Kill.FAILS_TORN, Kill.IGNORED)) {
            add(Fault("write $call ${kill.name}, then no read works") { it.arm(call, kill, thenReadsFail = true) })
        }
        for (call in 0 until writes) {
            add(Fault("write $call FAILS, then no write works") { it.arm(call, Kill.FAILS, thenWritesFail = true) })
        }
        for (call in 0 until reads) for (fault in ReadFault.entries) {
            add(Fault("read $call ${fault.name}") { it.armRead(call, fault) })
        }
    }

    /** What the log really holds, read by a store that cannot change it. */
    private class Truth(val held: Map<String, String>, val notErased: Int)

    private fun readOnlyCopy(disk: InMemoryFileDatabase, subject: Subject): CrashingFiles {
        val copy = InMemoryFileDatabase()
        disk.readBytes(subject.path)?.get()?.let { copy.writeRawByteArray(subject.path, it.copyOf()) }
        return CrashingFiles(copy).apply { refuseWrites = true }
    }

    private suspend fun truth(disk: InMemoryFileDatabase, subject: Subject): Truth {
        val store = subject.open(readOnlyCopy(disk, subject))
        return Truth(store.held(), store.notErased())
    }

    /**
     * **The invariant, for a process that is still running:** memory is what a reopen would parse,
     * and every struck record whose bytes are not zero is queued.
     *
     * ⚠⚠ Asked twice. First with the injected fault still standing, where the store may either
     * answer from the file's truth or refuse with [LogOutOfSync] — anything else is a guess. Then
     * with the disk healthy again, where it must answer, and be right.
     */
    private suspend fun assertInStep(subject: Subject, at: Seeded, what: String) {
        val whileFaulty = runCatching { at.store.held() }
        whileFaulty.exceptionOrNull()?.let { assertTrue(it is LogOutOfSync, "$what: refused with $it, not LogOutOfSync") }
        whileFaulty.getOrNull()?.let {
            assertEquals(truth(at.files.disk, subject).held, it, "$what: memory and the log disagree, fault standing")
        }
        at.files.watch()
        val memory = at.store.held()
        val onDisk = truth(at.files.disk, subject)
        assertEquals(onDisk.held, memory, "$what: memory and the log disagree")
        // ⚠ `>=`, not `==`: a scrub whose bytes landed but whose read-back failed stays queued, and
        // is retried. Owing more than is owed is safe; owing less is the defect.
        assertTrue(
            at.store.notErased() >= onDisk.notErased,
            "$what: ${onDisk.notErased} struck record(s) still hold bytes and only ${at.store.notErased()} are queued",
        )
    }

    private suspend fun eachFault(
        operation: String,
        subject: Subject,
        act: suspend Opened.() -> Any?,
        check: suspend (what: String, at: Seeded, outcome: Result<Any?>) -> Unit,
    ) {
        val (writes, reads) = seeded(subject).let { at ->
            at.files.watch()
            at.store.act()
            at.files.calls.size to at.files.reads
        }
        assertTrue(writes > 0, "$subject: the operation wrote nothing, so the sweep proves nothing")
        for (fault in faults(writes, reads)) {
            val at = seeded(subject)
            fault.arm(at.files)
            val outcome = runCatching { at.store.act() }
            val what = "$subject $operation, ${fault.name}"
            assertEquals(setOf(subject.path), at.files.touched, "$what: a second file")
            check(what, at, outcome)
            if (!at.files.dead) assertInStep(subject, at, what)
        }
    }

    @Test
    fun aRemovalStoppedAtAnyCallReopensAsOneConsistentStateAndFinishesOnTheNextRemoval() = runTest {
        eachStore { subject ->
            eachFault("removal", subject, act = { forget(dead) }) { what, at, outcome ->
                (outcome.getOrNull() as Removal?)?.let { removal ->
                    val onDisk = truth(at.files.disk, subject)
                    // ⚠⚠ What the call REPORTED against what is on disk, before anything can tidy up.
                    assertEquals(dead.count { it in at.before && it !in onDisk.held }, removal.removed, "$what: $removal")
                    assertTrue(removal.recordsNotErased >= onDisk.notErased, "$what: under-reported erasure debt — $removal")
                    assertEquals(removal.recordsNotErased > 0, removal.erasureOwed != null, "$what: $removal")
                    if (removal.complete) {
                        assertEquals(at.before - dead.toSet(), onDisk.held, "$what: reported complete")
                        assertErased(raw(at.files.disk, subject), "$what: reported erased")
                    }
                }
                if (at.files.dead) {
                    val reopened = subject.open(at.files.disk)
                    reopened.assertOneConsistentState(at.before, what)
                    reopened.finishesTheRemoval(subject, at, what)
                } else {
                    assertInStep(subject, at, what)
                    at.store.assertOneConsistentState(at.before, what)
                    at.store.finishesTheRemoval(subject, at, what)
                }
            }
        }
    }

    /** From wherever it stopped, one more removal finishes the job — the bytes go, and the store works. */
    private suspend fun Opened.finishesTheRemoval(subject: Subject, at: Seeded, what: String) {
        val live = at.before - dead.toSet()
        assertTrue(forget(dead).complete, "$what: the next removal did not finish")
        assertEquals(live, held(), what)
        assertEquals(0, notErased(), what)
        assertErased(raw(at.files.disk, subject), what)
        assertTrue(put("/new.jpg", 9.5f), what)
        assertEquals(live.keys + "/new.jpg", subject.open(at.files.disk).held().keys, what)
    }

    @Test
    fun aWipeStoppedAtAnyCallReopensWholeOrEmptyNeverPartAndNeverComesBack() = runTest {
        eachStore { subject ->
            eachFault("wipe", subject, act = { clear() }) { what, at, outcome ->
                val now = truth(at.files.disk, subject).held
                assertTrue(now == at.before || now.isEmpty(), "$what: a wipe left part of the store — ${now.keys}")
                // ⚠⚠ A wipe that RETURNED has wiped, whatever the file database quietly did not do.
                if (outcome.isSuccess) assertEquals(emptyMap(), now, "$what: the wipe returned and the rows are still there")
            }
        }
    }

    @Test
    fun anAppendStoppedAtAnyCallLosesAtMostTheRowBeingWritten() = runTest {
        eachStore { subject ->
            eachFault("append", subject, act = { put("/new.jpg", 9.5f) }) { what, at, outcome ->
                val now = truth(at.files.disk, subject).held
                assertEquals(at.before, now - "/new.jpg", what)
                // ⚠⚠ An append that RETURNED is in the log.
                if (outcome.isSuccess) assertTrue("/new.jpg" in now, "$what: reported written, and not in the log")
            }
        }
    }

    @Test
    fun aRebaseStoppedAnyWayIsTheOldLogOrAnEmptyOne() = runTest {
        eachFault("rebase", vectors, act = { rebase() }) { what, at, outcome ->
            val reopened = vectors.open(readOnlyCopy(at.files.disk, vectors))
            val stamp = reopened.provenance()
            val rows = reopened.held()
            assertTrue((stamp == model && rows == at.before) || (rows.isEmpty() && stamp != model), "$what: ${rows.keys} under $stamp")
            if (outcome.isSuccess) assertEquals(nextModel, stamp, "$what: reported rebased, and the log is the old model's")
            if (!at.files.dead && !at.files.readsFail) assertEquals(stamp, at.store.provenance(), "$what: memory claims another model")
        }
    }

    /**
     * ⚠⚠ Opening is an operation too: it reads the log, and it writes — the scrub of records a
     * crash left struck but not zeroed, and the face log's blanking of a torn tail. The log opened
     * here has both.
     *
     * ⚠⚠ There is no exception to allow for. The face log blanks a torn tail in place: removing it
     * by rewriting the whole file lets a kill inside that write cut the log and bring an older
     * template back.
     */
    @Test
    fun anOpenStoppedAtAnyCallIsInStepWithItsLogOrSaysItIsNot() = runTest {
        eachStore { subject ->
            val original = InMemoryFileDatabase()
            val crashed = CrashingFiles(original)
            subject.open(crashed).apply {
                seed()
                crashed.arm(call = 0, Kill.DIES_AFTER)
                runCatching { forget(listOf(GONE)) }
            }
            val torn = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(40) { 7 }
            original.writeBytesRange(subject.path, original.fileSize(subject.path)!!, torn)
            val expected = truth(original, subject)
            assertEquals(1, expected.notErased, "precondition: one struck record still holds its bytes")

            fun fresh() = CrashingFiles(InMemoryFileDatabase().also { it.writeRawByteArray(subject.path, raw(original, subject)) })
            val (writes, reads) = fresh().let { files ->
                subject.open(files)
                files.calls.size to files.reads
            }
            for (fault in faults(writes, reads)) {
                val files = fresh()
                fault.arm(files)
                val opened = runCatching { subject.open(files) }
                val what = "$subject open, ${fault.name}"
                assertEquals(setOf(subject.path), files.touched + subject.path, "$what: a second file")
                assertTrue(files.calls.none { it.name == "raw" }, "$what: opening rewrote the whole log")
                if (files.dead) {
                    assertEquals(expected.held, subject.open(files.disk).held(), what)
                } else {
                    // ⚠ A constructor that throws is not an option: the store opens, and says so
                    // on first use if it could not read its log.
                    assertInStep(subject, Seeded(files, opened.getOrThrow(), expected.held), what)
                    assertEquals(expected.held, opened.getOrThrow().held(), what)
                }
            }
        }
    }

    /**
     * ⚠ Not an assertion about the stores: it pins how much the sweeps above actually inject, so a
     * sweep that quietly shrank to nothing — an operation that stopped making calls, a counter that
     * stopped counting — fails here instead of passing vacuously.
     */
    @Test
    fun theSweepsInjectEveryWriteAndEveryReadOfEveryOperation() = runTest {
        suspend fun counted(subject: Subject, operation: String, act: suspend Opened.() -> Any?) {
            val at = seeded(subject)
            at.files.watch()
            at.store.act()
            val (writes, reads) = at.files.calls.size to at.files.reads
            assertEquals(writes * 10 + reads * 4, faults(writes, reads).size, "$subject $operation")
            assertTrue(reads > 0, "$subject $operation: nothing is read back, so no confirmation is injected")
        }
        for (subject in stores) {
            counted(subject, "removal") { forget(dead) }
            counted(subject, "wipe") { clear() }
            counted(subject, "append") { put("/new.jpg", 9.5f) }
        }
        counted(vectors, "rebase") { rebase() }
        // A removal of four records: 8 writes, 12 reads. An append: 1 write, 2 reads. A wipe: 2 writes, 1 read.
        val removal = seeded(faces).also { it.files.watch(); it.store.forget(dead) }.files
        assertEquals(8 to 12, removal.calls.size to removal.reads)
        val append = seeded(faces).also { it.files.watch(); it.store.put("/new.jpg", 9.5f) }.files
        assertEquals(1 to 2, append.calls.size to append.reads)
        val wipe = seeded(faces).also { it.files.watch(); it.store.clear() }.files
        assertEquals(2 to 1, wipe.calls.size to wipe.reads)
    }

    private fun copy(from: InMemoryFileDatabase, to: InMemoryFileDatabase, subject: Subject) {
        to.writeRawByteArray(subject.path, raw(from, subject))
    }

    private companion object {
        const val GONE = "/gone/x.jpg"
        const val GONE_VISIT = "/gone/v.raw"
        const val TWICE = "/gone/twice.jpg"

        // ⚠ Bit patterns that cannot occur by accident, so "are its floats still in the file" is a
        // byte search rather than a guess.
        val GONE_CONTENT = Float.fromBits(0x5A5A5A5A)
        val OLDER = Float.fromBits(0x6B6B6B6B)
        val LATEST = Float.fromBits(0x4C4C4C4C)

        fun short(value: Int) = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

        fun pathOnly(flag: Int, photo: String): ByteArray =
            byteArrayOf(flag.toByte()) + short(photo.length) + photo.encodeToByteArray()
    }
}
