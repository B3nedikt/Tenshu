package org.tenshu.core.common.proxy.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteObjectStoreTest {

    private fun tempDbPath(): String =
        createTempDirectory("sqlite-objstore").resolve("objects.db").toString()

    private fun entry(
        hash: Long,
        type: String = "File",
        fields: List<DbValue> = listOf(
            DbValue.StringValue("name-$hash"),
            DbValue.LongValue(hash * 2),
            DbValue.BooleanValue(hash % 2 == 0L),
            DbValue.NullValue,
        ),
        properties: Map<String, DbValue> = mapOf(
            "parent" to DbValue.LongValue(hash - 1),
            "label" to DbValue.StringValue("p-$hash"),
        ),
    ) = DbEntry(type = type, dataIdHash = hash, fields = fields, properties = properties)

    @Test
    fun `store then get round-trips through the cache`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        val e = entry(42L)
        store.storeObject(e)

        assertEquals(e, store.getObject(42L))
        assertNull(store.getObject(99L))

        store.close()
    }

    @Test
    fun `cold reopen restores committed state from disk`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val first = SqliteObjectStore.open(path, scope)
        first.storeObject(entry(1L))
        first.storeObject(entry(2L))
        first.flush()
        first.close()

        val reopened = SqliteObjectStore.open(path, scope)
        assertEquals(entry(1L), reopened.getObject(1L))
        assertEquals(entry(2L), reopened.getObject(2L))
        reopened.close()
    }

    @Test
    fun `setListener fires immediately when an entry is already present`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        val e = entry(7L)
        store.storeObject(e)

        var received: DbEntry? = null
        store.setListener(7L) { received = it }

        assertEquals(e, received)
        store.close()
    }

    @Test
    fun `listener fires on subsequent mutations`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        val updates = mutableListOf<DbEntry>()
        store.setListener(5L) { updates.add(it) }

        store.storeObject(entry(5L, type = "v1"))
        store.storeObject(entry(5L, type = "v2"))

        assertEquals(listOf("v1", "v2"), updates.map { it.type })
        store.close()
    }

    @Test
    fun `removeObject clears the entry from cache and disk`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val store = SqliteObjectStore.open(path, scope)
        store.storeObject(entry(3L))
        store.flush()
        store.removeObject(3L)
        store.flush()

        assertNull(store.getObject(3L))
        store.close()

        val reopened = SqliteObjectStore.open(path, scope)
        assertNull(reopened.getObject(3L))
        reopened.close()
    }

    @Test
    fun `clearAll purges cache and disk`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val store = SqliteObjectStore.open(path, scope)
        store.storeObject(entry(1L))
        store.storeObject(entry(2L))
        store.clearAll()

        assertNull(store.getObject(1L))
        assertNull(store.getObject(2L))
        store.close()

        val reopened = SqliteObjectStore.open(path, scope)
        assertNull(reopened.getObject(1L))
        assertNull(reopened.getObject(2L))
        reopened.close()
    }

    @Test
    fun `GenericValue slots survive a reopen, type-id and payload intact`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // A genericData field's slot: a self-describing (typeId, payload) pair —
        // one inline primitive, one composite reference (LongValue).
        val e = DbEntry(
            type = "Setting",
            dataIdHash = 21L,
            fields = listOf(
                DbValue.GenericValue("tenshu/core/boolean", DbValue.BooleanValue(true)),
                DbValue.GenericValue("some/composite", DbValue.LongValue(1234L)),
            ),
        )

        val store = SqliteObjectStore.open(path, scope)
        store.storeObject(e)
        store.flush()
        store.close()

        val restored = SqliteObjectStore.open(path, scope).also { it }
        val got = restored.getObject(21L)
        assertTrue(got != null)
        val g0 = got.fields[0] as DbValue.GenericValue
        assertEquals("tenshu/core/boolean", g0.typeId)
        assertEquals(true, (g0.payload as DbValue.BooleanValue).value)
        val g1 = got.fields[1] as DbValue.GenericValue
        assertEquals("some/composite", g1.typeId)
        assertEquals(1234L, (g1.payload as DbValue.LongValue).value)
        restored.close()
    }

    @Test
    fun `ByteArrayValue blobs survive a reopen byte-for-byte`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Includes 0x00 and high bytes — must not be truncated or re-encoded.
        val payload = byteArrayOf(0, 1, 2, 127, -1, -128, 0, 65)
        val e = DbEntry(
            type = "Blob",
            dataIdHash = 8L,
            fields = listOf(DbValue.ByteArrayValue(payload)),
            properties = mapOf("thumb" to DbValue.ByteArrayValue(byteArrayOf(0, -42, 9))),
        )

        val store = SqliteObjectStore.open(path, scope)
        store.storeObject(e)
        store.flush()
        store.close()

        val reopened = SqliteObjectStore.open(path, scope)
        val restored = reopened.getObject(8L)
        assertTrue(restored != null)
        assertContentEquals(
            payload,
            (restored.fields[0] as DbValue.ByteArrayValue).value,
        )
        assertContentEquals(
            byteArrayOf(0, -42, 9),
            (restored.properties.getValue("thumb") as DbValue.ByteArrayValue).value,
        )
        reopened.close()
    }

    // ---- Phase 0 reset: storage-format-version gate ----

    // Rewrites PRAGMA user_version on a *closed* store's DB file, simulating a
    // store written under a different storage-format version. Uses the same
    // bundled driver the store does; a fresh connection to the (WAL) file is
    // safe because the store is closed before this runs.
    private fun rawSetUserVersion(path: String, version: Int) {
        val conn = BundledSQLiteDriver().open(path)
        try {
            val s = conn.prepare("PRAGMA user_version = $version")
            try { s.step() } finally { s.close() }
        } finally {
            conn.close()
        }
    }

    private fun rawReadUserVersion(path: String): Int {
        val conn = BundledSQLiteDriver().open(path)
        try {
            val s = conn.prepare("PRAGMA user_version")
            return try { if (s.step()) s.getLong(0).toInt() else 0 } finally { s.close() }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `a stale format-version store is wiped on open`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Write a valid entry (this stamps user_version = current), then rewind the
        // recorded version to model a store written under an older/different format.
        val first = SqliteObjectStore.open(path, scope)
        first.storeObject(entry(12345L))
        first.flush()
        first.close()
        rawSetUserVersion(path, TENSHU_STORAGE_FORMAT_VERSION - 1)

        // Reopening detects the mismatch and wipes — the stale entity is gone and the
        // store re-stamps itself at the current version.
        val reopened = SqliteObjectStore.open(path, scope)
        assertNull(reopened.getObject(12345L))
        reopened.close()
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, rawReadUserVersion(path))
    }

    @Test
    fun `a matching format-version store keeps its data on open`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // First open stamps the current version; a second open at the same version
        // must NOT wipe (regression guard against the gate firing on every open).
        val first = SqliteObjectStore.open(path, scope)
        first.storeObject(entry(55L))
        first.flush()
        first.close()
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION, rawReadUserVersion(path))

        val reopened = SqliteObjectStore.open(path, scope)
        assertEquals(entry(55L), reopened.getObject(55L))
        reopened.close()
    }

    // ---- Phase 2: transaction-boundary durability ----

    // A real scope for the post-crash store, so its graceful close()/flush()
    // run against a writer on an actual dispatcher.
    private fun recoveryScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val pendingBlocksHash = 777L

    private fun pendingBlocksEntry(refs: List<Long>) = DbEntry(
        // Models the outbound PENDING_BLOCKS list (a TList of LongValue refs).
        type = TList.ID,
        dataIdHash = pendingBlocksHash,
        fields = refs.map { DbValue.LongValue(it) },
        properties = emptyMap(),
    )

    @Test
    fun `flushBarrier commits the transaction and only the post-barrier tail is lost on crash`() =
        runTest {
            val path = tempDbPath()
            // StandardTestDispatcher via `writerContext`: the writer runs only
            // when we advance the scheduler, so "crash before the writer
            // drained X" is deterministic.
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val crashScope = CoroutineScope(testDispatcher)

            val store1 = SqliteObjectStore.open(path, crashScope, writerContext = testDispatcher)
            // Transaction 1: an entity + its PENDING_BLOCKS list, then the
            // transaction-commit barrier.
            store1.storeObject(entry(1L))
            store1.storeObject(pendingBlocksEntry(listOf(1L)))
            store1.flushBarrier()
            advanceUntilIdle() // writer drains tx1 + barrier → SQLite COMMIT

            // Transaction 2: a write whose barrier the process never reaches.
            store1.storeObject(entry(2L))
            store1.simulateCrash() // process death before the writer drained tx2
            crashScope.cancel()
            advanceUntilIdle()

            val store2 = SqliteObjectStore.open(path, recoveryScope())
            assertEquals(entry(1L), store2.getObject(1L))
            assertEquals(
                listOf(1L),
                store2.getObject(pendingBlocksHash)
                    ?.fields
                    ?.map { (it as DbValue.LongValue).value },
            )
            assertNull(store2.getObject(2L)) // un-barriered tail: the only loss
            store2.close()
        }

    @Test
    fun `without a reached barrier a crash loses the write`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val crashScope = CoroutineScope(testDispatcher)

        val store1 = SqliteObjectStore.open(path, crashScope, writerContext = testDispatcher)
        store1.storeObject(entry(1L)) // enqueued; writer never advanced
        store1.simulateCrash()
        crashScope.cancel()
        advanceUntilIdle()

        // Proves the barrier (advanced for in the test above) is what makes
        // the write durable — not open()/the cache.
        val store2 = SqliteObjectStore.open(path, recoveryScope())
        assertNull(store2.getObject(1L))
        store2.close()
    }

    // ---- Bounded mailbox + dedupe-by-hash (bounded-store-cache.md P1) ----

    @Test
    fun `superseded writes to the same hash are deduped to the last op per batch`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(path, scope, writerContext = testDispatcher)

        // Writer paced → all six ops coalesce into one batch. Five puts to
        // the same hash are dead weight under upsert semantics; only the
        // last (plus the unrelated hash) should reach SQLite.
        repeat(5) { i -> store.storeObject(entry(9L, type = "v$i")) }
        store.storeObject(entry(10L))
        advanceUntilIdle()

        assertEquals(2, store.appliedWriteOps)
        assertEquals("v4", store.getObject(9L)?.type)
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        assertEquals("v4", reopened.getObject(9L)?.type)
        assertEquals(entry(10L), reopened.getObject(10L))
        reopened.close()
    }

    @Test
    fun `a Put superseded by a Delete in the same batch commits the delete`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(path, scope, writerContext = testDispatcher)

        store.storeObject(entry(4L))
        store.removeObject(4L)
        advanceUntilIdle()

        assertEquals(1, store.appliedWriteOps)
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        assertNull(reopened.getObject(4L))
        reopened.close()
    }

    @Test
    fun `a full mailbox blocks the producer until the writer drains`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(
            path,
            scope,
            writerContext = testDispatcher,
            mailboxCapacity = 2,
        )

        val producerDone = CountDownLatch(1)
        val producer = Thread {
            repeat(4) { i -> store.storeObject(entry(i + 1L)) }
            producerDone.countDown()
        }
        producer.start()

        // The paced writer never drains. Capacity + one element handed to the
        // writer's parked receive = at most 3 sends succeed; the fourth store
        // parks the producer — bounded backlog, not growth.
        assertFalse(producerDone.await(250, TimeUnit.MILLISECONDS))

        advanceUntilIdle() // writer commits a batch → capacity frees
        assertTrue(producerDone.await(5, TimeUnit.SECONDS))
        producer.join()

        store.flush()
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        (1L..4L).forEach { assertEquals(entry(it), reopened.getObject(it)) }
        reopened.close()
    }

    // ---- C-lite size-tiered retention (bounded-store-cache.md P2) ----

    private fun largeEntry(hash: Long, marker: Byte = 7) = DbEntry(
        type = "Blob",
        dataIdHash = hash,
        fields = listOf(DbValue.ByteArrayValue(ByteArray(32 * 1024) { marker })),
        properties = emptyMap(),
    )

    @Test
    fun `a committed large row is dropped from memory and read through from disk`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        store.storeObject(largeEntry(100L))
        store.flush() // commit → pin released, no resident copy remains

        assertEquals(0, store.readThroughReads)
        val roundTripped = store.getObject(100L)
        assertEquals(1, store.readThroughReads, "read must come from SQLite, not memory")
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (roundTripped!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        store.close()
    }

    /**
     * ⚠ Replaces `small rows stay resident and absent probes never touch SQLite`. Small rows are
     * no longer resident-forever — that policy is exactly what let a 234k-row store exceed the
     * heap. The half that survives, and must, is the **absent** probe: `knownHashes` still answers
     * "no such row" without a SQLite hit, at 8 bytes per row instead of the whole entry.
     */
    @Test
    fun `an absent probe never touches SQLite, and a present row is read through once then cached`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        store.storeObject(entry(1L))
        store.flush() // commit → pin released

        assertNull(store.getObject(424242L)) // absent key
        assertEquals(0, store.readThroughReads, "an absent probe must not hit the read connection")

        assertEquals(entry(1L), store.getObject(1L))
        assertEquals(1, store.readThroughReads, "first read of a committed row comes from disk")
        assertEquals(entry(1L), store.getObject(1L))
        assertEquals(1, store.readThroughReads, "second read must be served by the cache")
        store.close()
    }

    @Test
    fun `an uncommitted large row is served from its pin until the writer commits`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(path, scope, writerContext = testDispatcher)

        store.storeObject(largeEntry(200L)) // writer paced → not committed
        val beforeCommit = store.getObject(200L)
        assertEquals(0, store.readThroughReads, "disk can't have it yet — must come from the pin")
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (beforeCommit!!.fields[0] as DbValue.ByteArrayValue).value,
        )

        advanceUntilIdle() // commit → pin drops
        val afterCommit = store.getObject(200L)
        assertEquals(1, store.readThroughReads, "post-commit read must come from disk")
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (afterCommit!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        store.close()
    }

    @Test
    fun `removing a large row with an in-flight put reads null immediately and durably`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(path, scope, writerContext = testDispatcher)

        store.storeObject(largeEntry(300L))
        store.removeObject(300L) // put not yet committed
        assertNull(store.getObject(300L), "removal must win over the pinned put")
        advanceUntilIdle()
        assertNull(store.getObject(300L))
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        assertNull(reopened.getObject(300L))
        reopened.close()
    }

    /**
     * ⚠ Replaces `reopen partitions tiers by on-disk size` — there are no tiers any more.
     * The property that matters now is that **`open(...)` decodes nothing at all**: whatever the
     * store's size, reopening costs one `hash` column scan, and every row (small or large) is
     * hydrated lazily on first access. This is the regression guard for the OOM crash-loop.
     */
    @Test
    fun `reopen hydrates hashes only, so every row is read through on first access`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val store = SqliteObjectStore.open(path, scope)
        store.storeObject(entry(1L))
        store.storeObject(largeEntry(2L))
        store.flush()
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        assertEquals(0, reopened.readThroughReads, "open() must not decode any entry")
        assertFalse(reopened.isResident(1L), "no row may be resident straight after open()")
        assertFalse(reopened.isResident(2L), "no row may be resident straight after open()")

        assertEquals(entry(1L), reopened.getObject(1L))
        assertEquals(1, reopened.readThroughReads, "the small row hydrates as a hash only too")
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (reopened.getObject(2L)!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        assertEquals(2, reopened.readThroughReads, "the large row likewise")
        reopened.close()
    }

    /**
     * The bound itself. With a deliberately tiny budget, reading far more distinct rows than it
     * holds must not grow residency — the least-recently-used are evicted, and because the cache
     * only ever holds *committed* rows, eviction costs a re-read and never a lost write.
     *
     * ⚠ The budget is in **bytes**, so this asserts bytes; an entry count would pass here while
     * still allowing a handful of blob rows to blow the heap, which is the whole reason the bound
     * is not a count.
     */
    @Test
    fun `the read cache is bounded by bytes, so residency does not grow with the dataset`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val budget = 2_048L
        val store = SqliteObjectStore.open(path, scope, cacheBudgetBytes = budget)

        repeat(200) { store.storeObject(entry(it.toLong())) }
        store.flush()
        repeat(200) { assertEquals(entry(it.toLong()), store.getObject(it.toLong())) }

        assertTrue(
            store.cachedBytes in 1..budget,
            "cache must hold something, and never exceed its budget — was ${store.cachedBytes}",
        )
        assertTrue(
            (0 until 200).count { store.isResident(it.toLong()) } < 200,
            "a 2 KiB budget cannot possibly hold all 200 rows",
        )
        // Evicted rows are still readable — the durable copy is on disk.
        assertEquals(entry(0L), store.getObject(0L))
        store.close()
    }

    /** A row bigger than the entire budget is served but never cached — it cannot fit by design. */
    @Test
    fun `a row larger than the whole budget is served without evicting everything`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(path, scope, cacheBudgetBytes = 4_096L)

        store.storeObject(entry(1L))
        store.storeObject(largeEntry(2L)) // 32 KiB, far over budget
        store.flush()

        assertEquals(entry(1L), store.getObject(1L))
        val before = store.cachedBytes
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (store.getObject(2L)!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        assertEquals(before, store.cachedBytes, "an over-budget row must not enter the cache")
        assertTrue(store.isResident(1L), "and must not have evicted the small row that fits")
        store.close()
    }

    /**
     * ⚠ Replaces `a row can cross the size tier in both directions`. Tier crossing no longer
     * exists, but the value-correctness it protected does: a row re-put at a very different size
     * must still round-trip, in both directions.
     */
    @Test
    fun `a row rewritten at a very different size still round-trips`() = runTest {
        val path = tempDbPath()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(path, scope)

        // big → small
        store.storeObject(largeEntry(500L))
        store.flush()
        store.storeObject(entry(500L))
        store.flush()
        assertEquals(entry(500L), store.getObject(500L))

        // small → large: the grown row is durable and read through.
        store.storeObject(largeEntry(500L, marker = 9))
        store.flush()
        assertContentEquals(
            ByteArray(32 * 1024) { 9 },
            (store.getObject(500L)!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        store.close()

        val reopened = SqliteObjectStore.open(path, recoveryScope())
        assertContentEquals(
            ByteArray(32 * 1024) { 9 },
            (reopened.getObject(500L)!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        reopened.close()
    }

    @Test
    fun `setListener fires immediately for a non-resident large row`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SqliteObjectStore.open(tempDbPath(), scope)

        store.storeObject(largeEntry(600L))
        store.flush() // pin dropped; only disk holds the row

        var received: DbEntry? = null
        store.setListener(600L) { received = it }
        assertContentEquals(
            ByteArray(32 * 1024) { 7 },
            (received!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        store.close()
    }

    @Test
    fun `superseded large puts release their pins when deduped`() = runTest {
        val path = tempDbPath()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val store = SqliteObjectStore.open(path, scope, writerContext = testDispatcher)

        // Three puts to one hash coalesce into one batch: two are deduped
        // away, one commits — all three pins must be released, or the stale
        // copy leaks and shadows the read-through forever.
        repeat(3) { i -> store.storeObject(largeEntry(700L, marker = i.toByte())) }
        advanceUntilIdle()

        assertEquals(1, store.appliedWriteOps)
        store.getObject(700L)
        assertEquals(1, store.readThroughReads, "no pin may survive the commit")
        assertContentEquals(
            ByteArray(32 * 1024) { 2 },
            (store.getObject(700L)!!.fields[0] as DbValue.ByteArrayValue).value,
        )
        store.close()
    }
}
