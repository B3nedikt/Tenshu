@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.DbEntryCodec
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_IOERR
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A relation array kept as a chain of segment rows (Appendix B.19): what is stored, what each
 * change writes, that no torn list is ever readable, legacy rows, and the migrator's sweep. Over
 * the real SQLite and real files.
 */
class ReplicaListSegmentsTest {

    private val seg = ReplicaLimits().listSegmentMembers

    /** This build: it declares the list-segment format (B.19.9 (343)). */
    private val segBuild = testBuild().withListSegments()
    private val head = 4_242L

    private fun members(range: IntRange): List<DbValue> = range.map { DbValue.LongValue(it.toLong() * 7 + 1) }
    private fun list(members: List<DbValue>, hash: Long = head) = DbEntry(TList.ID, hash, members)
    private fun list(n: Int, hash: Long = head) = list(members(0 until n), hash)

    /** Every row of `objects`, hash to (type, bytes). */
    private fun ReplicaProcess.rows(): Map<Long, Pair<String, ByteArray>> = sql { connection ->
        val rows = HashMap<Long, Pair<String, ByteArray>>()
        connection.query("SELECT hash, type, data FROM objects") { rows[it.getLong(0)] = it.getText(1) to it.getBlob(2) }
        rows
    }

    /** What changed between two snapshots of `objects`. */
    private class Changes(val written: Set<Long>, val added: Set<Long>, val deleted: Set<Long>) {
        val all: Int get() = written.size + added.size + deleted.size
        override fun toString() = "written=$written added=$added deleted=$deleted"
    }

    private fun changes(before: Map<Long, Pair<String, ByteArray>>, after: Map<Long, Pair<String, ByteArray>>) = Changes(
        written = before.keys.filter { it in after && !after.getValue(it).second.contentEquals(before.getValue(it).second) }.toSet(),
        added = (after.keys - before.keys),
        deleted = (before.keys - after.keys),
    )

    /**
     * The list at [hash] read straight from the file, as a sibling would see it committed: the
     * chain followed by hand. Also asserts it holds together.
     */
    private fun committedList(connection: SqliteConnection, hash: Long): List<DbValue>? {
        fun read(at: Long): DbEntry? {
            var bytes: ByteArray? = null
            connection.query("SELECT data FROM objects WHERE hash = $at") { bytes = it.getBlob(0) }
            return bytes?.let(DbEntryCodec::decode)
        }
        val first = read(hash) ?: return null
        val out = ArrayList(first.fields)
        var next = ReplicaListSegments.nextOf(first)
        while (next != null) {
            val segment = read(next) ?: fail("segment $next of $hash is missing")
            assertEquals(ReplicaListSegments.SEGMENT_TYPE, segment.type)
            assertEquals(hash, ReplicaListSegments.headOf(segment))
            out += segment.fields
            next = ReplicaListSegments.nextOf(segment)
        }
        return out
    }

    /** The decoded rows of the chain at [hash], head first. */
    private fun ReplicaProcess.chain(hash: Long = head): List<DbEntry> {
        val rows = rows()
        val chain = ArrayList<DbEntry>()
        var at: Long? = hash
        while (at != null) {
            val entry = DbEntryCodec.decode(rows.getValue(at).second)
            chain += entry
            at = ReplicaListSegments.nextOf(entry)
        }
        return chain
    }

    /** Segment rows no list's chain reaches. */
    private fun ReplicaProcess.orphans(): Set<Long> {
        val rows = rows()
        val segments = rows.filterValues { it.first == ReplicaListSegments.SEGMENT_TYPE }.keys
        val reached = HashSet<Long>()
        rows.filterValues { it.first == TList.ID }.keys.forEach { listHash -> chain(listHash).drop(1).forEach { reached += it.dataIdHash } }
        return segments - reached
    }

    // ── round trips ──

    @Test
    fun everySizeThatMattersRoundTripsAndIsStoredInTheSegmentsItShouldBe() {
        // 0, 1, SEG − 1 and SEG stay one row (the legacy form exactly); SEG + 1 is two segments.
        val expectations = listOf(0 to 1, 1 to 1, seg - 1 to 1, seg to 1, seg + 1 to 2, 2 * seg + 1 to 3, 100_000 to (100_000 + seg - 1) / seg)
        for ((n, rows) in expectations) {
            val process = ReplicaProcess(tempReplica())
            val store = process.store(segBuild)
            store.storeObject(list(n))
            assertEquals(list(n), store.getObject(head), "n = $n")
            assertTrue(store.hasObject(head))
            store.close()
            assertEquals(rows, process.rows().size, "n = $n: rows")
            assertEquals(rows, process.chain().size, "n = $n: one chain")
            assertTrue(process.chain().all { it.fields.size <= seg }, "n = $n: every segment within SEG")
            if (rows == 1) {
                assertTrue(process.rows().getValue(head).second.contentEquals(DbEntryCodec.encode(list(n))), "n = $n: the legacy bytes")
            }
            // A fresh process, as another app, reads the same list.
            assertEquals(list(n), ReplicaProcess(process.directory).store(segBuild).getObject(head), "n = $n: reopened")
        }
    }

    @Test
    fun aHundredThousandMemberListNeverMakesARowOverRowMaxOrOverOneSegment() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store(segBuild)
        store.storeObject(list(100_000))
        val largest = process.sql { it.queryLong("SELECT max(length(data)) FROM objects") }
        assertTrue(largest <= ReplicaLimits().rowMaxBytes, "largest row $largest")
        // A segment is SEG members of 9 bytes, plus its type, hash and two links.
        assertTrue(largest <= seg * 9L + 128, "largest row $largest is more than one segment")
        assertEquals(list(100_000), store.getObject(head))
        assertEquals(emptySet(), process.orphans())
    }

    // ── what each change writes ──

    private fun ReplicaProcess.withList(n: Int): SharedReplicaStore = store(segBuild).also { it.storeObject(list(n)) }

    @Test
    fun anAppendWritesTheLastSegmentAloneAndAFractionOfTheWalAWholeRowWould() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(100_000)
        val before = process.rows()
        val last = process.chain().last().dataIdHash
        val wal = store.walBytes
        store.storeObject(list(members(0 until 100_001)))
        val walOfOneAppend = store.walBytes - wal
        val changed = changes(before, process.rows())
        assertEquals(setOf(last), changed.written, "$changed")
        assertEquals(1, changed.all, "$changed")
        assertEquals(list(100_001), store.getObject(head))
        // One segment of ~70 KB, its tree and pointer-map pages: never the ~900 KB of the whole list.
        val frames = walOfOneAppend / ReplicaLimits.WAL_FRAME_BYTES
        assertTrue(frames in 1..40, "one append wrote $frames frames")
    }

    @Test
    fun anAppendToAFullLastSegmentWritesItsLinkAndOneNewSegment() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(2 * seg)
        val chainBefore = process.chain()
        assertEquals(2, chainBefore.size)
        val before = process.rows()
        store.storeObject(list(2 * seg + 1))
        val changed = changes(before, process.rows())
        assertEquals(setOf(chainBefore.last().dataIdHash), changed.written, "the full segment, for its link")
        assertEquals(1, changed.added.size)
        assertEquals(emptySet(), changed.deleted)
        assertEquals(list(2 * seg + 1), store.getObject(head))
        // The next append reopens the one-member segment, and writes it alone.
        val again = process.rows()
        store.storeObject(list(2 * seg + 2))
        assertEquals(changed.added, changes(again, process.rows()).written)
        assertEquals(1, changes(again, process.rows()).all)
    }

    @Test
    fun aRemovalInTheMiddleWritesTheOneSegmentItWasIn() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(5 * seg)
        val chain = process.chain()
        val before = process.rows()
        val all = members(0 until 5 * seg)
        val gone = all[2 * seg + 17]
        store.storeObject(list(all - gone))
        val changed = changes(before, process.rows())
        assertEquals(setOf(chain[2].dataIdHash), changed.written, "$changed: only the segment it was in, nothing after it shifts")
        assertEquals(1, changed.all)
        assertEquals(list(all - gone), store.getObject(head))
    }

    @Test
    fun anInsertionIntoAFullSegmentSplitsItInTwoAndTouchesNothingElse() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val chain = process.chain()
        val before = process.rows()
        val inserted = members(0 until 3 * seg).toMutableList().also { it.add(seg + 5, DbValue.LongValue(-1)) }
        store.storeObject(list(inserted))
        val changed = changes(before, process.rows())
        assertEquals(setOf(chain[1].dataIdHash), changed.written, "$changed")
        assertEquals(1, changed.added.size, "$changed")
        assertEquals(emptySet(), changed.deleted)
        assertEquals(list(inserted), store.getObject(head))
        assertTrue(process.chain().all { it.fields.size in seg / 2..seg }, "both halves at least half full")
    }

    @Test
    fun aReorderRewritesTheWholeListAndAReplaceByOtherMembersToo() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(100_000)
        val before = process.rows()
        val reversed = members(0 until 100_000).reversed()
        store.storeObject(list(reversed))
        val changed = changes(before, process.rows())
        assertEquals(before.keys, changed.written, "every segment differs")
        assertEquals(0, changed.added.size + changed.deleted.size)
        assertEquals(list(reversed), store.getObject(head))

        // A replace by a shorter list of other members: the head and what it needs, the rest deleted.
        val other = (0 until 3 * seg).map { DbValue.LongValue(-it.toLong() - 10) }
        store.storeObject(list(other))
        assertEquals(list(other), store.getObject(head))
        assertEquals(3, process.rows().size)
        assertEquals(emptySet(), process.orphans())
    }

    @Test
    fun theSameListWritesNothingAndMovesNoSeqButStillTellsItsListener() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val seq = store.seq
        val heard = ArrayList<DbEntry>()
        store.setListener(head) { heard += it }
        val before = process.rows()
        store.storeObject(list(3 * seg))
        assertEquals(0, changes(before, process.rows()).all)
        assertEquals(seq, store.seq)
        assertEquals(listOf(list(3 * seg), list(3 * seg)), heard, "fired at once, then by the write, with the whole list")
    }

    @Test
    fun aQueueDrainedFromTheFrontStaysWholeAndDoesNotFragment() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(4 * seg)
        var current = members(0 until 4 * seg)
        repeat(30) { round ->
            // As the sync queues change: a batch taken from the front, then one appended, each its own write.
            for (change in 0..1) {
                current = if (change == 0) current.drop(seg / 10) else current + members(10_000_000 + round * seg until 10_000_000 + round * seg + seg / 12)
                val statements = store.listStatements
                store.storeObject(list(current))
                assertTrue(store.listStatements - statements <= 2, "round $round, change $change: ${store.listStatements - statements} rows")
                assertEquals(list(current), store.getObject(head), "round $round")
            }
        }
        val chain = process.chain()
        assertTrue(chain.dropLast(1).all { it.fields.size >= seg / 4 }, "every segment but the last at least a quarter full: ${chain.map { it.fields.size }}")
        assertEquals(emptySet(), process.orphans())
    }

    // ── single-member changes: at most two statements, and the chain never fragments ──

    @Test
    fun everySingleMemberChangeWritesAtMostTwoRowsAndKeepsEverySegmentButTheLastAQuarterFull() {
        val small = 16
        val process = ReplicaProcess(tempReplica(), ReplicaLimits(listSegmentMembers = small))
        val store = process.store(segBuild)
        val random = Random(20261030)
        val model = members(0 until 200).toMutableList()
        store.storeObject(list(model))
        var next = 1_000_000L
        repeat(3_000) { round ->
            // Single-member changes most of the time; then a batch, a scattered removal, a swap,
            // and a member that is already in the list (an array may hold one twice).
            val single = when (random.nextInt(10)) {
                0, 1 -> { model.add(DbValue.LongValue(next++)); true }
                2, 3 -> { model.add(random.nextInt(model.size + 1), DbValue.LongValue(next++)); true }
                4, 5, 6 -> { if (model.isNotEmpty()) model.removeAt(random.nextInt(model.size)); true }
                7 -> {
                    if (model.isNotEmpty()) model.add(random.nextInt(model.size + 1), model[random.nextInt(model.size)])
                    true
                }
                8 -> {
                    if (random.nextBoolean()) repeat(1 + random.nextInt(40)) { model.add(DbValue.LongValue(next++)) }
                    // Scattered over a window of a few segments: a change over the whole list
                    // could pass the reset log's room at this `SEG` (B.19.4's spanning row).
                    else if (model.isNotEmpty()) {
                        val from = random.nextInt(model.size)
                        repeat(1 + random.nextInt(8)) {
                            val window = minOf(model.size - from, 4 * small)
                            if (window > 0) model.removeAt(from + random.nextInt(window))
                        }
                    }
                    false
                }
                else -> {
                    if (model.size >= 2) {
                        val a = random.nextInt(model.size)
                        val b = minOf(model.size - 1, a + random.nextInt(4 * small))
                        model[a] = model[b].also { model[b] = model[a] }
                    }
                    false
                }
            }
            val statements = store.listStatements
            store.storeObject(list(model))
            val written = store.listStatements - statements
            if (single) assertTrue(written <= 2, "round $round wrote $written rows")
            // ⚠ Read back every round: a later rewrite would hide an earlier wrong layout.
            assertEquals(list(model), store.getObject(head), "round $round")
            val sizes = process.chain().map { it.fields.size }
            assertTrue(sizes.all { it <= small }, "round $round: $sizes")
            assertTrue(sizes.dropLast(1).all { it >= small / 4 }, "round $round: a segment under a quarter: $sizes")
        }
        assertEquals(emptySet(), process.orphans())
    }

    /** A store with [n] members in a list, then other rows until its `-wal` is just under `Wmax − δ`, held there by a reader. */
    private fun aStoreAtTheCeiling(n: Int): Pair<ReplicaProcess, SharedReplicaStore> {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(n)
        val reader = rawConnection(process.databasePath)
        reader.exec("BEGIN")
        reader.query("SELECT count(*) FROM objects") { }
        var hash = 1L
        while (store.walBytes + 120_000 < process.limits.beginCeilingBytes) store.storeObject(row(hash++, payloadBytes = 30_000))
        reader.exec("COMMIT")
        reader.close()
        assertTrue(store.walBytes in process.limits.beginCeilingBytes - 200_000..process.limits.beginCeilingBytes, "the -wal at ${store.walBytes}")
        return process to store
    }

    @Test
    fun atTheCeilingASingleMemberChangeFitsAsItIsAndAWholeListRewriteResetsTheLogFirst() {
        // B.19.9 (344): a transaction begun just under `Wmax − δ` (as writer rule 1 allows).
        val (_, store) = aStoreAtTheCeiling(3 * seg)
        val resets = store.verifiedResets
        val split = members(0 until 3 * seg).toMutableList().also { it.add(seg + 5, DbValue.LongValue(-1)) }
        store.storeObject(list(split))
        assertEquals(list(split), store.getObject(head))
        assertEquals(resets, store.verifiedResets, "the worst single-member change fits the log as it stands")
        assertEquals(0, store.listResets)

        val (_, big) = aStoreAtTheCeiling(100_000)
        val reversed = members(0 until 100_000).reversed()
        big.storeObject(list(reversed))
        assertEquals(1, big.listResets, "the log was reset before the write, not the handle fenced")
        assertNull(big.fencedBy)
        assertEquals(list(reversed), big.getObject(head))
    }

    @Test
    fun aSiblingThatCommitsWhileTheLogIsResetRefusesTheWriteAsRuleASays() {
        val (process, store) = aStoreAtTheCeiling(100_000)
        var begins = 0
        process.registry.beginFault = {
            // The second BEGIN is the one after the reset: a sibling commits just before it.
            if (++begins == 2) {
                val sibling = rawConnection(process.databasePath)
                sibling.exec("BEGIN IMMEDIATE")
                sibling.exec("INSERT INTO objects (hash, type, data) VALUES (777, 'tenshu/file', ${blobLiteral(DbEntryCodec.encode(row(777)))})")
                sibling.exec("UPDATE meta SET v = v + 1 WHERE k = 'seq'")
                sibling.exec("COMMIT")
                sibling.close()
            }
        }
        assertFailsWith<TransactionBudgetExceeded> { store.storeObject(list(members(0 until 100_000).reversed())) }
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy)
        process.registry.beginFault = null
        assertEquals(list(100_000), ReplicaProcess(process.directory).store(segBuild).getObject(head))
    }

    // ── a failure or a crash inside a write ──

    @Test
    fun aWriteThatFailsHalfWayLeavesTheOldListAndNoOrphanAndASiblingNeverSeesItTorn() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val old = list(3 * seg)
        val before = process.rows()
        val sibling = rawConnection(process.databasePath)
        var upserts = 0
        var seenMidWrite: List<DbValue>? = null
        store.statementFault = { sql ->
            if (sql == SharedReplicaStore.UPSERT_SQL && ++upserts == 2) {
                // The first segment of the new list is written, the rest not yet: what a sibling reads.
                seenMidWrite = committedList(sibling, head)
                throw SqliteException(SQLITE_IOERR, "disk I/O error")
            }
        }
        // A reorder: every segment is rewritten, so the fault lands between two of them.
        assertFailsWith<ReplicaAccessFailed> { store.storeObject(list(members(0 until 3 * seg).reversed())) }
        sibling.close()
        assertEquals(old.fields, seenMidWrite, "a sibling reads the committed list while the write is under way")
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy, "rule (a)")
        val reopened = ReplicaProcess(process.directory)
        assertEquals(before.keys, reopened.rows().keys)
        assertEquals(old, reopened.store(segBuild).getObject(head))
        assertEquals(emptySet(), reopened.orphans())
    }

    @Test
    fun aWriteTheLogCouldNeverTakeIsRefusedBeforeItsFirstStatementAndTheListStays() {
        // Segments of 50 members: a reorder of 30,000 is 600 rewritten rows, far past any reset log.
        val limits = ReplicaLimits(listSegmentMembers = 50)
        val process = ReplicaProcess(tempReplica(), limits)
        val store = process.store(segBuild)
        // Built a thousand at a time: twenty new segments a write, which a log takes.
        (0 until 30_000 step 1_000).forEach { upTo -> store.storeObject(list(upTo + 1_000)) }
        val before = process.rows()
        val refused = assertFailsWith<ListRewriteTooLarge> { store.storeObject(list(members(0 until 30_000).reversed())) }
        assertTrue(refused.pages > limits.resetLogRoomPages)
        assertNull(store.fencedBy, "B.19.9 (345): not a fence")
        assertEquals(before.keys, process.rows().keys)
        assertEquals(list(30_000), ReplicaProcess(process.directory, limits).store(segBuild).getObject(head))
    }

    @Test
    fun aWriteTheLogCouldNeverTakeAfterTheAccessHasWrittenFencesAndCommitsNothing() {
        // B.19.10 (349): the access wrote a row first, so committing it would keep it without its list.
        val limits = ReplicaLimits(listSegmentMembers = 50)
        val process = ReplicaProcess(tempReplica(), limits)
        val store = process.store(segBuild)
        (0 until 30_000 step 1_000).forEach { upTo -> store.storeObject(list(upTo + 1_000)) }
        assertFailsWith<ListRewriteTooLarge> {
            store.databaseLock.withLock {
                store.storeObject(row(1))
                store.storeObject(list(members(0 until 30_000).reversed()))
            }
        }
        assertEquals(ReplicaFence.COMMIT_FAILED, store.fencedBy, "rule (a): the access had written")
        val fresh = ReplicaProcess(process.directory, limits).store(segBuild)
        assertNull(fresh.getObject(1), "the row written before the refused list is not committed")
        assertEquals(list(30_000), fresh.getObject(head))
    }

    @Test
    fun aResetAReaderBlocksIsWalResetPendingWithNoFenceAndTheNextTryWrites() {
        // B.19.10 (351): the reset the write needs cannot happen while a reader holds its mark.
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(100_000)
        val reader = rawConnection(process.databasePath)
        reader.exec("BEGIN")
        reader.query("SELECT count(*) FROM objects") { }
        var hash = 1L
        while (store.walBytes + 120_000 < process.limits.beginCeilingBytes) store.storeObject(row(hash++, payloadBytes = 30_000))
        val reversed = members(0 until 100_000).reversed()
        assertFailsWith<WalResetPending> { store.storeObject(list(reversed)) }
        assertNull(store.fencedBy, "nothing was written: a retry, not a fence")
        assertEquals(list(100_000), store.getObject(head))
        reader.exec("COMMIT")
        reader.close()
        store.storeObject(list(reversed))
        assertEquals(list(reversed), store.getObject(head))
    }

    @Test
    fun aRemovalTheLogCouldNeverTakeIsRefusedFencesNothingAndTheListStays() {
        val limits = ReplicaLimits(listSegmentMembers = 50)
        val process = ReplicaProcess(tempReplica(), limits)
        val store = process.store(segBuild)
        (0 until 30_000 step 1_000).forEach { upTo -> store.storeObject(list(upTo + 1_000)) }
        val refused = assertFailsWith<ListRewriteTooLarge> { store.removeObject(head) }
        assertEquals(head, refused.head)
        assertEquals(600, refused.segments)
        assertNull(store.fencedBy, "one write refused, not the store")
        assertEquals(list(30_000), store.getObject(head))
        store.storeObject(row(1))
        assertEquals(row(1), store.getObject(1))
    }

    // ── legacy rows ──

    @Test
    fun aLegacySingleRowListOverOneSegmentIsReadAndLaidOutInSegmentsAtItsNextWrite() {
        val process = ReplicaProcess(tempReplica())
        process.store(segBuild).close()
        val legacy = list(3 * seg + 5)
        // Written as every store before this one wrote it: one row, whatever its size.
        process.sql { it.exec("INSERT INTO objects (hash, type, data) VALUES ($head, '${TList.ID}', ${blobLiteral(DbEntryCodec.encode(legacy))})") }
        val store = process.store(segBuild)
        assertEquals(legacy, store.getObject(head))
        assertEquals(1, process.rows().size)

        val appended = list(members(0 until 3 * seg + 6))
        store.storeObject(appended)
        assertEquals(appended, store.getObject(head))
        val chain = process.chain()
        assertEquals(4, chain.size, "laid out again: ${chain.map { it.fields.size }}")
        assertTrue(chain.all { it.fields.size <= seg })
        assertEquals(emptySet(), process.orphans())
    }

    @Test
    fun aLegacyRowWithinOneSegmentIsWrittenAsTheSameOneRowAsBefore() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(seg - 10)
        store.storeObject(list(seg - 9))
        assertEquals(1, process.rows().size)
        assertTrue(process.rows().getValue(head).second.contentEquals(DbEntryCodec.encode(list(seg - 9))))
        assertEquals(0, store.listWrites, "the single-row path, as every other row")
    }

    // ── a segment is not an object ──

    @Test
    fun aSegmentIsNeverAnObjectAndARemovedListTakesEveryOneWithIt() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val segment = process.chain()[1].dataIdHash
        assertNull(store.getObject(segment))
        assertFalse(store.hasObject(segment))
        store.removeObject(segment)
        assertEquals(list(3 * seg), store.getObject(head), "removing a segment's hash removes nothing")
        assertEquals(emptyList(), store.rowsOfTypes(setOf(ReplicaListSegments.SEGMENT_TYPE)))
        assertEquals(listOf(list(3 * seg)), store.rowsOfTypes(setOf(TList.ID)))
        assertEquals(listOf(list(3 * seg)), store.scanRowsOfType(TList.ID, Long.MIN_VALUE, 10, 10)!!.rows)
        assertIs<SegmentHashCollision>(assertFailsWith<ReplicaAccessFailed> { store.storeObject(row(segment)) })

        val again = process.store(segBuild)
        again.removeObject(head)
        assertEquals(0, process.rows().size, "the list and every segment of it")
    }

    @Test
    fun aRowOfAnotherShapeOverASegmentedListDeletesItsSegments() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val replaced = DbEntry(TList.ID, head, listOf(DbValue.StringValue("not a member")))
        store.storeObject(replaced)
        assertEquals(replaced, store.getObject(head))
        assertEquals(1, process.rows().size)
        assertFailsWith<IllegalArgumentException> {
            store.storeObject(DbEntry(TList.ID, 7, emptyList(), mapOf(ReplicaListSegments.NEXT_KEY to DbValue.LongValue(1))))
        }
    }

    @Test
    fun aNewSegmentSkipsAHashAnotherRowHolds() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store(segBuild)
        // The first candidate a new segment of this list would take is somebody's row already.
        val taken = ReplicaListSegments.candidate(head, 1)
        store.storeObject(row(taken))
        store.storeObject(list(2 * seg))
        assertEquals(row(taken), store.getObject(taken))
        assertEquals(list(2 * seg), store.getObject(head))
        assertEquals(emptySet(), process.orphans())
    }

    @Test
    fun aBrokenChainIsReportedAsDamagedAndFencesNothing() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val segment = process.chain()[1].dataIdHash
        process.sql { it.exec("DELETE FROM objects WHERE hash = $segment") }
        assertFailsWith<ListSegmentsDamaged> { store.getObject(head) }
        assertNull(store.fencedBy)
        store.storeObject(row(1))
        assertEquals(row(1), store.getObject(1))
    }

    @Test
    fun aDamagedListIsLaidOutAfreshAtItsNextWriteAndCanStillBeRemoved() {
        val process = ReplicaProcess(tempReplica())
        val store = process.withList(3 * seg)
        val chain = process.chain()
        // The last segment is lost: the head and the middle one can still be reached.
        process.sql { it.exec("DELETE FROM objects WHERE hash = ${chain[2].dataIdHash}") }
        assertFailsWith<ListSegmentsDamaged> { store.getObject(head) }
        val rewritten = list(members(10 until 2 * seg + 30))
        store.storeObject(rewritten)
        assertEquals(rewritten, store.getObject(head))
        assertEquals(emptySet(), process.orphans())
        assertNull(store.fencedBy)

        // And a damaged list is removed with whatever of it can be reached.
        val now = process.chain()
        process.sql { it.exec("DELETE FROM objects WHERE hash = ${now.last().dataIdHash}") }
        store.removeObject(head)
        assertEquals(0, process.rows().size)
    }

    // ── a build without the format's stamp ──

    @Test
    fun aStoreWithSegmentsIsSkewForABuildWithoutTheirStamp() {
        val process = ReplicaProcess(tempReplica())
        process.withList(3 * seg).close()
        val older = testBuild() // a build before B.19: it does not declare the format
        val opened = ReplicaProcess(process.directory).open(older)
        assertIs<ReplicaOpenResult.Skew>(opened, "an older build must never read one segment as the whole list")
        assertTrue(opened.detail.contains(ReplicaListSegments.FORMAT_SERVICE), opened.detail)
    }

    @Test
    fun aStoreOfABuildBeforeSegmentsIsStampedByAnUpdateAndKeepsItsLists() {
        val older = testBuild()
        val directory = tempReplica()
        val legacy = list(3 * seg + 5)
        ReplicaProcess(directory).store(older).apply {
            storeObject(legacy)
            close()
        }
        assertEquals(1, ReplicaProcess(directory).rows().size, "an older build writes the list as one row")
        val process = ReplicaProcess(directory)
        val opened = assertIs<ReplicaOpenResult.Updating>(process.open(segBuild))
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())
        assertEquals(1L, process.sql { it.queryLong("SELECT version FROM schema_service_versions WHERE service_id = '${ReplicaListSegments.FORMAT_SERVICE}'") })
        val store = process.store(segBuild)
        assertEquals(legacy, store.getObject(head))
        store.storeObject(list(3 * seg + 6))
        assertEquals(list(3 * seg + 6), store.getObject(head))
        assertEquals(4, process.chain().size)
    }

    @Test
    fun aBuildWithoutTheStampWritesEveryListAsOneRow() {
        val process = ReplicaProcess(tempReplica())
        val store = process.store(testBuild())
        store.storeObject(list(3 * seg))
        assertEquals(1, process.rows().size)
        assertEquals(list(3 * seg), store.getObject(head))
        assertEquals(0, store.listWrites)
    }

    // ── the migrator's sweep ──

    private class Step(override val typeIds: Set<String>, private val migration: (DbEntry) -> DbEntry?) : ReplicaMigrationStep {
        override fun migrate(row: DbEntry): DbEntry? = migration(row)
    }

    @Test
    fun theMigrationSweepTakesADroppedMemberOutOfEverySegmentAndTheChainHoldsTogether() {
        val limits = ReplicaLimits(listSegmentMembers = 10, chunkMaxRows = 7)
        val file = "tenshu/file"
        val floors = mapOf(file to 1, "tenshu/sync" to 1)
        val oldBuild = testBuild(services = mapOf(file to 1, "tenshu/sync" to 9), serviceFloors = floors).withListSegments()
        val newBuild = testBuild(services = mapOf(file to 2, "tenshu/sync" to 9), serviceFloors = floors).withListSegments()
        val directory = tempReplica()
        val old = ReplicaProcess(directory, limits).store(oldBuild)
        val files = (1L..100L).map { row(it, file) }
        files.chunked(20).forEach { some -> old.databaseLock.withLock { some.forEach(old::storeObject) } }
        val naming = files.map { DbValue.LongValue(it.dataIdHash) }
        old.storeObject(list(naming))
        old.close()

        val process = ReplicaProcess(directory, limits).also { p ->
            p.steps = ReplicaMigrationSteps { service, from ->
                if (service == file && from == 1) Step(setOf(file)) { if (it.dataIdHash % 3 == 0L) null else it } else null
            }
        }
        val opened = assertIs<ReplicaOpenResult.Updating>(process.open(newBuild))
        assertEquals(ReplicaRunOutcome.Finished, opened.replacement.run())
        val kept = naming.filter { (it as DbValue.LongValue).value % 3 != 0L }
        val after = process.store(newBuild)
        assertEquals(list(kept), after.getObject(head))
        assertEquals(emptySet(), process.orphans())
        // A single-member change over the short segments the sweep left: the list stays exact.
        val model = kept.toMutableList()
        model.removeAt(20)
        after.storeObject(list(model))
        assertEquals(list(model), after.getObject(head))
        model.add(DbValue.LongValue(5_000))
        after.storeObject(list(model))
        assertEquals(list(model), after.getObject(head))
        assertEquals(emptySet(), process.orphans())
    }
}
