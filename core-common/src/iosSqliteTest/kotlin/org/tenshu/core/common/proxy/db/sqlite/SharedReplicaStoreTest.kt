package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `SharedReplicaStore` as an [ObjectStore]: the contract the JVM store (and the retired iOS file
 * store) is held to (there is no shared suite, so these mirrored the file store's tests), then what is this store's own: one transaction per access,
 * `meta.seq`, the pagers, the refusals and the fence.
 */
class SharedReplicaStoreTest {

    private fun entry(type: String, hash: Long, vararg fields: DbValue) = DbEntry(type, hash, fields.toList())

    private fun SharedReplicaStore.storedSeq(): Long = raw { it.queryLong("SELECT v FROM meta WHERE k = 'seq'") }

    private var directoryOf = HashMap<SharedReplicaStore, String>()

    private fun newStore(limits: ReplicaLimits = ReplicaLimits()): SharedReplicaStore {
        val process = ReplicaProcess(tempReplica(), limits)
        return process.store().also { directoryOf[it] = process.databasePath }
    }

    private fun <T> SharedReplicaStore.raw(block: (SqliteConnection) -> T): T {
        val connection = rawConnection(directoryOf.getValue(this))
        try {
            return block(connection)
        } finally {
            connection.close()
        }
    }

    // ── the ObjectStore contract ──

    @Test
    fun storeThenGetRoundTrips() {
        val store = newStore()
        val e = entry("t", 42L, DbValue.StringValue("hello"), DbValue.LongValue(7))
        store.storeObject(e)
        assertEquals(e, store.getObject(42L))
        assertTrue(store.hasObject(42L))
        assertFalse(store.hasObject(43L))
        assertNull(store.getObject(43L))
        assertFalse(store.isEmpty())
    }

    @Test
    fun anEmptyStoreSaysSo() {
        assertTrue(newStore().isEmpty())
    }

    @Test
    fun aRewriteReplacesTheRowAndItsType() {
        val store = newStore()
        store.storeObject(entry("old", 1L, DbValue.LongValue(1)))
        store.storeObject(entry("new", 1L, DbValue.LongValue(2)))
        assertEquals(entry("new", 1L, DbValue.LongValue(2)), store.getObject(1L))
        assertEquals(emptyList(), store.rowsOfTypes(setOf("old")))
    }

    @Test
    fun removeObjectDeletesTheRowDurably() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(entry("t", 1L, DbValue.LongValue(1)))
        store.removeObject(1L)
        assertNull(store.getObject(1L))
        store.close()
        assertNull(ReplicaProcess(process.directory).store().getObject(1L))
    }

    @Test
    fun everyValueKindSurvivesAReopenByteForByte() {
        val process = ReplicaProcess(tempReplica())
        val blob = ByteArray(40_000) { (it * 31).toByte() }
        val e = DbEntry(
            "t",
            Long.MIN_VALUE + 9,
            listOf(
                DbValue.StringValue("ü"),
                DbValue.LongValue(-1),
                DbValue.BooleanValue(true),
                DbValue.NullValue,
                DbValue.GenericValue("tenshu/string", DbValue.StringValue("x")),
            ),
            mapOf("p" to DbValue.LongValue(3)),
        )
        process.store().apply {
            storeObject(e)
            storeObject(entry("blob", -5L, DbValue.ByteArrayValue(blob)))
            close()
        }
        val reopened = ReplicaProcess(process.directory).store()
        assertEquals(e, reopened.getObject(Long.MIN_VALUE + 9))
        val read = assertNotNull(reopened.getObject(-5L)).fields.single() as DbValue.ByteArrayValue
        assertTrue(blob.contentEquals(read.value))
    }

    @Test
    fun aListenerFiresAtOnceWhenTheRowExistsAndOnEveryLaterWrite() {
        val store = newStore()
        val seen = ArrayList<DbEntry>()
        store.storeObject(entry("t", 1L, DbValue.LongValue(1)))
        store.setListener(1L) { seen += it }
        assertEquals(1, seen.size, "fired at once with the current row")
        store.storeObject(entry("t", 1L, DbValue.LongValue(2)))
        assertEquals(listOf(DbValue.LongValue(2)), seen.last().fields)
        store.removeListener(1L)
        store.storeObject(entry("t", 1L, DbValue.LongValue(3)))
        assertEquals(2, seen.size)
        // A listener on an absent row does not fire until it is written.
        store.setListener(2L) { seen += it }
        assertEquals(2, seen.size)
    }

    @Test
    fun theReservedCursorRowsWorkThroughTheInterfacesDefaults() {
        val store = newStore()
        assertEquals(0L, store.loadSyncCursor())
        assertNull(store.loadBackfillCursor())
        store.storeSyncCursor(99)
        store.storeBackfillCursor(0)
        assertEquals(99L, store.loadSyncCursor())
        assertEquals(0L, store.loadBackfillCursor())
        store.clearBackfillCursor()
        assertNull(store.loadBackfillCursor())
    }

    // ── enumeration and the pagers ──

    private fun SharedReplicaStore.fill(hashes: Iterable<Long>, type: String) = databaseLock.withLock {
        hashes.forEach { storeObject(row(it, type)) }
    }

    @Test
    fun rowsOfTypesReturnsExactlyTheNamedTypes() {
        val store = newStore()
        store.fill(1L..5L, "a")
        store.fill(6L..8L, "b")
        store.fill(9L..9L, "c")
        assertEquals((1L..8L).toSet(), store.rowsOfTypes(setOf("a", "b"))!!.map { it.dataIdHash }.toSet())
        assertEquals(emptyList(), store.rowsOfTypes(setOf("none")))
        assertEquals(emptyList(), store.rowsOfTypes(emptySet()))
    }

    @Test
    fun theTypeScanPagesLosslesslyInHashOrderAndSaysWhenItIsDone() {
        val store = newStore()
        val hashes = (0 until 25).map { it * 1_000_003L - 12_000_000L }
        store.fill(hashes, "wanted")
        store.fill((1L..40L).map { it * 7 + 2 }, "other")

        val seen = ArrayList<Long>()
        var cursor = Long.MIN_VALUE
        var pages = 0
        while (true) {
            val page = assertNotNull(store.scanRowsOfType("wanted", cursor, matchLimit = 10, examineLimit = 1))
            seen += page.rows.map { it.dataIdHash }
            assertTrue(page.nextCursor >= cursor)
            cursor = page.nextCursor
            pages++
            if (page.exhausted) break
        }
        assertEquals(hashes.sorted(), seen)
        assertEquals(3, pages)
    }

    @Test
    fun anEmptyScanPageLeavesTheCursorWhereItWas() {
        val store = newStore()
        val page = assertNotNull(store.scanRowsOfType("nothing", 77L, matchLimit = 10, examineLimit = 10))
        assertEquals(emptyList(), page.rows)
        assertEquals(77L, page.nextCursor)
        assertTrue(page.exhausted)
        assertNull(store.scanRowsOfType("nothing", 0L, matchLimit = 0, examineLimit = 10))
    }

    @Test
    fun aFullLastPageIsNotExhaustion() {
        val store = newStore()
        store.fill(1L..10L, "t")
        val first = assertNotNull(store.scanRowsOfType("t", Long.MIN_VALUE, matchLimit = 10, examineLimit = 0))
        assertFalse(first.exhausted, "exactly matchLimit rows: the scan cannot know it is done")
        val second = assertNotNull(store.scanRowsOfType("t", first.nextCursor, matchLimit = 10, examineLimit = 0))
        assertTrue(second.exhausted)
        assertEquals(first.nextCursor, second.nextCursor)
    }

    private fun SharedReplicaStore.pageAll(types: Set<String>, pageSize: Int, take: (List<DbEntry>) -> Int = { it.size }): List<Long> {
        val seen = ArrayList<Long>()
        var after: Long? = null
        while (true) {
            val page = pageOfTypes(types, after, pageSize)
            if (page.isEmpty()) break
            // The cursor is the last row TAKEN, which a budget may put before the page's last.
            val taken = page.take(take(page))
            seen += taken.map { it.dataIdHash }
            after = taken.last().dataIdHash
            if (page.size < pageSize && taken.size == page.size) break
        }
        return seen
    }

    @Test
    fun theKeysetPagerStartsWithNoLowerBoundSoTheSmallestHashIsNotSkipped() {
        val store = newStore()
        val hashes = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -3L, 0L, 5L, Long.MAX_VALUE)
        store.fill(hashes, "t")
        store.fill(listOf(-4L, 6L), "other")
        for (pageSize in listOf(1, 2, 7)) {
            assertEquals(hashes, store.pageAll(setOf("t"), pageSize), "page size $pageSize")
        }
        // The interface's scan, started at the sentinel as its callers do, cannot see that row.
        val scanned = store.scanRowsOfType("t", Long.MIN_VALUE, 100, 100)!!.rows.map { it.dataIdHash }
        assertEquals(hashes.drop(1), scanned)
    }

    @Test
    fun theKeysetPagerMergesSeveralTypesInHashOrderWithNoRowTwice() {
        val store = newStore()
        val a = (0 until 30).map { it * 3L - 40 }
        val b = (0 until 30).map { it * 3L - 39 }
        store.fill(a, "a")
        store.fill(b, "b")
        store.fill((0 until 30).map { it * 3L - 38 }, "c")
        val expected = (a + b).sorted()
        for (pageSize in listOf(1, 4, 60, 100)) {
            assertEquals(expected, store.pageAll(setOf("a", "b"), pageSize), "page size $pageSize")
        }
        // A caller whose budget cuts every page short resumes from the last row it took.
        assertEquals(expected, store.pageAll(setOf("a", "b"), 8) { 1 + it.size / 3 })
    }

    @Test
    fun theKeysetPagerOnAnEmptyTypeIsAnEmptyPage() {
        val store = newStore()
        assertEquals(emptyList(), store.pageOfTypes(setOf("none"), null, 10))
        assertEquals(emptyList(), store.pageOfTypes(setOf("none"), Long.MAX_VALUE, 10))
        assertFailsWith<IllegalArgumentException> { store.pageOfTypes(setOf("none"), null, 0) }
    }

    // ── one access, one transaction (S2, S4, D4) ──

    @Test
    fun aSiblingSeesNothingOfATransactionUntilItCommits() {
        val store = newStore()
        store.databaseLock.withLock {
            store.storeObject(row(1))
            store.storeObject(row(2))
            // BEGIN IMMEDIATE holds the write lock, so a sibling reads the last commit.
            assertEquals(0L, store.raw { it.queryLong("SELECT count(*) FROM objects") })
        }
        assertEquals(2L, store.raw { it.queryLong("SELECT count(*) FROM objects") })
    }

    @Test
    fun seqMovesOncePerTransactionThatWritesObjectsAndNotOtherwise() {
        val store = newStore()
        assertEquals(0L, store.seq)
        store.getObject(1)
        store.isEmpty()
        store.removeObject(1) // absent: nothing changes
        assertEquals(0L, store.seq)
        assertEquals(0L, store.storedSeq())

        store.storeObject(row(1))
        assertEquals(1L, store.seq)
        store.databaseLock.withLock {
            store.storeObject(row(2))
            store.storeObject(row(3))
            store.removeObject(1)
        }
        assertEquals(2L, store.seq)
        assertEquals(2L, store.storedSeq(), "the handle's seq is the stored one")
    }

    @Test
    fun anExceptionFromTheCallersBlockCommitsWhatWasWritten() {
        val store = newStore()
        assertFailsWith<IllegalStateException> {
            store.databaseLock.withLock {
                store.storeObject(row(1))
                error("the caller's own failure")
            }
        }
        assertNull(store.fencedBy, "not a statement error: the handle serves on")
        assertEquals(row(1), store.getObject(1))
        assertEquals(1L, store.storedSeq())
    }

    @Test
    fun noAccessLeavesATransactionOrAStatementOpen() {
        val store = newStore()
        store.fill(1L..50L, "t")
        store.rowsOfTypes(setOf("t"))
        store.scanRowsOfType("t", 0, 5, 5)
        store.pageOfTypes(setOf("t"), null, 5)
        store.setListener(1) {}
        assertEquals(0, store.transactionsLeftOpen)
        // A sibling can take the write lock at once: nothing of this handle's is still open.
        store.raw { sibling ->
            sibling.exec("BEGIN IMMEDIATE")
            sibling.exec("ROLLBACK")
        }
    }

    // ── refusals and the fence (rule (a)) ──

    private fun assertFenced(store: SharedReplicaStore, fence: ReplicaFence) {
        assertEquals(fence, store.fencedBy)
        // Reads answer absent, and never throw.
        assertNull(store.getObject(1))
        assertFalse(store.hasObject(1))
        assertFalse(store.isEmpty(), "never 'empty': that would tell the sync cursor the data was reset")
        assertNull(store.rowsOfTypes(setOf("t")), "null, 'do not ask me', never an empty list")
        assertNull(store.scanRowsOfType("t", 0, 5, 5))
        // Writes throw before touching the database.
        assertEquals(fence, assertFailsWith<ReplicaReplaced> { store.storeObject(row(9)) }.fence)
        assertFailsWith<ReplicaReplaced> { store.removeObject(1) }
        assertFailsWith<ReplicaReplaced> { store.pageOfTypes(setOf("t"), null, 5) }
        // B.14 (192): what `SyncManagerImpl.forgetThisDevice` tolerates is this refusal, as the
        // common type, for each of the cursor rows its reset writes.
        assertFailsWith<org.tenshu.core.common.proxy.db.StoreHandleFenced> { store.storeSyncCursor(0L) }
        assertFailsWith<org.tenshu.core.common.proxy.db.StoreHandleFenced> { store.storeTombstoneCursor(0L) }
        assertFailsWith<org.tenshu.core.common.proxy.db.StoreHandleFenced> { store.clearFirstPullMarker() }
    }

    @Test
    fun aRowOverRowMaxIsRefusedBeforeSqliteAndFencesTheHandle() {
        val process = ReplicaProcess(tempReplica(), ReplicaLimits(rowMaxBytes = 10_000))
        val store = process.store()
        store.storeObject(row(1))
        val refused = assertFailsWith<RowTooLarge> {
            store.databaseLock.withLock {
                store.storeObject(row(2))
                store.storeObject(row(3, payloadBytes = 10_001))
            }
        }
        assertEquals(10_000, refused.limit)
        assertFenced(store, ReplicaFence.COMMIT_FAILED)
        assertTrue(process.registry.handles().isEmpty(), "the fenced handle was released: no connection left behind")
        // Rolled back whole: the row written earlier in the same transaction is gone too.
        val after = ReplicaProcess(process.directory).store()
        assertEquals(row(1), after.getObject(1))
        assertNull(after.getObject(2))
        assertEquals(1L, after.seq)
    }

    @Test
    fun theFencedFlowIsNullWhileTheHandleServesThenItsFence() {
        // Appendix B.16 (212): the host's signal of §6.
        val process = ReplicaProcess(tempReplica(), ReplicaLimits(rowMaxBytes = 10_000))
        val store = process.store()
        store.storeObject(row(1))
        assertNull(store.fenced.value, "serving: no fence")
        assertFailsWith<RowTooLarge> { store.storeObject(row(2, payloadBytes = 10_001)) }
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fenced.value)
        assertEquals(store.fencedBy, store.fenced.value)
        val released = process.store()
        released.close()
        assertEquals(ReplicaFence.RELEASED, released.fenced.value, "a release is a fence too")
    }

    @Test
    fun aRowOfExactlyRowMaxIsAccepted() {
        val limits = ReplicaLimits()
        val store = newStore(limits)
        // DbEntryCodec's envelope for this shape is 4+1 + 8 + 4 + (1+4+n) + 4 bytes.
        val big = DbEntry("t", 1L, listOf(DbValue.ByteArrayValue(ByteArray(limits.rowMaxBytes - 26))))
        store.storeObject(big)
        store.storeObject(big) // ROW_MAX over ROW_MAX: the largest statement there is
        assertNull(store.fencedBy)
        assertEquals(limits.rowMaxBytes - 26, (store.getObject(1)!!.fields.single() as DbValue.ByteArrayValue).value.size)
    }

    @Test
    fun aStatementSqliteFailsFencesTheHandleAndRollsBack() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        // A sibling makes this handle's next statement fail: its prepared UPSERT no longer compiles.
        rawConnection(process.databasePath).apply {
            exec("ALTER TABLE objects RENAME COLUMN data TO renamed")
            close()
        }
        val failure = assertFailsWith<ReplicaAccessFailed> {
            store.databaseLock.withLock {
                store.removeObject(1)
            }
        }
        assertEquals(ReplicaFence.COMMIT_FAILED, failure.fence)
        assertIs<SqliteException>(failure.cause)
        assertFenced(store, ReplicaFence.COMMIT_FAILED)
        assertTrue(process.registry.handles().isEmpty())
        assertFalse(fileExists("${process.directory}/${ReplicaCorruptMarker.NAME}"), "an error is not corruption")
        // The write lock was given up with the rollback.
        rawConnection(process.databasePath).apply {
            exec("BEGIN IMMEDIATE")
            assertEquals(1L, queryLong("SELECT count(*) FROM objects"))
            exec("ROLLBACK")
            close()
        }
    }

    @Test
    fun aGrowingWriteIsRefusedWhenTheStoreIsFullButADeleteIsNot() {
        // Room for δ and a few dozen pages beyond the empty layout.
        val limits = ReplicaLimits(maxDbPages = ReplicaLimits().deltaPages + 60)
        val process = ReplicaProcess(tempReplica(), limits)
        var store = process.store()
        var written = 0L
        val full = assertFailsWith<StoreFull> {
            while (true) store.storeObject(row(written++, payloadBytes = 3_000))
        }
        assertEquals(limits.maxDbPages, full.limit)
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        assertTrue(written > 10, "it took rows until the limit: $written")

        store = ReplicaProcess(process.directory, limits).store()
        // A removal and a shrinking rewrite are always admitted, so a full store can be emptied.
        store.removeObject(0)
        store.storeObject(row(1, payloadBytes = 10))
        assertNull(store.fencedBy)
        assertFailsWith<StoreFull> { repeat(100) { store.storeObject(row(1_000L + it, payloadBytes = 3_000)) } }
    }

    @Test
    fun aClosedStoreIsFencedReleased() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store()
        store.storeObject(row(1))
        store.close()
        assertFenced(store, ReplicaFence.RELEASED)
        assertTrue(process.registry.handles().isEmpty())
    }

    // ── the corruption marker, per access (S20) ──

    @Test
    fun aMarkerWrittenByASiblingFencesThisHandleAtItsVeryNextAccess() {
        repeat(25) { round ->
            val process = ReplicaProcess(tempReplica())
            val store = process.store()
            store.storeObject(row(1))
            assertEquals(row(1), store.getObject(1))
            // A sibling detects corruption and writes the marker by the sidecar rule.
            ReplicaCorruptMarker(PosixReplicaFiles, process.directory).write("time=0\napp=sibling\nerror=test\n")
            assertNull(store.getObject(1), "round $round: fenced at the next access")
            assertEquals(ReplicaFence.CORRUPT, store.fencedBy, "round $round")
            assertFailsWith<ReplicaReplaced> { store.storeObject(row(2)) }
            assertTrue(process.registry.handles().isEmpty(), "round $round")
        }
    }

    @Test
    fun theMarkerCheckSettlesOnTheDirectorysTimeAndStillSeesALaterMarker() {
        val now = longArrayOf(0)
        val files = object : ReplicaFiles by PosixReplicaFiles {
            override fun now(): Pair<Long, Long> = PosixReplicaFiles.now().let { (it.first + now[0]) to it.second }
        }
        val directory = tempReplica()
        val marker = ReplicaCorruptMarker(files, directory)
        // A directory changed a moment ago is not trusted: every check looks by path.
        repeat(5) { assertEquals(ReplicaMarkerState.ABSENT, marker.state()) }
        assertEquals(5, marker.pathLookups)
        // Once its time is in the past, one more lookup settles it and the rest cost an fstat.
        now[0] = 10
        repeat(100) { assertEquals(ReplicaMarkerState.ABSENT, marker.state()) }
        assertEquals(6, marker.pathLookups)
        // The marker's creation moves the directory's time, so the next check sees it.
        ReplicaCorruptMarker(PosixReplicaFiles, directory).write("x")
        assertEquals(ReplicaMarkerState.DURABLE, marker.state())
        assertEquals(7, marker.pathLookups)
    }
}
