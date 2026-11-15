package org.tenshu.core.common.proxy.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Concurrency soak + bounded-residency assertions for the C-lite store
// (docs/proposals/bounded-store-cache.md §"If the kernel slice is built").
// The mutation side is sequential by contract (every call happens under
// `DatabaseLock`); the concurrency under test is the REAL writer thread
// committing, unpinning and deduping asynchronously against that sequential
// stream of store/remove/get calls.
class SqliteObjectStoreSoakTest {

    // Each database is ~48 MB, so a run that left them behind filled TMPDIR.
    private val tempDirs = mutableListOf<Path>()

    private fun tempDbPath(): String =
        createTempDirectory("sqlite-objstore-soak").also { tempDirs.add(it) }
            .resolve("objects.db").toString()

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun deleteTempDirs() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun smallEntry(hash: Long, version: Int) = DbEntry(
        type = "small-v$version",
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("name-$hash-$version"), DbValue.LongValue(version.toLong())),
        properties = mapOf("v" to DbValue.LongValue(version.toLong())),
    )

    private fun largeEntry(hash: Long, version: Int) = DbEntry(
        type = "large-v$version",
        dataIdHash = hash,
        fields = listOf(DbValue.ByteArrayValue(ByteArray(12 * 1024) { version.toByte() })),
        properties = mapOf("v" to DbValue.LongValue(version.toLong())),
    )

    private fun assertSameEntry(expected: DbEntry?, actual: DbEntry?, context: String) {
        if (expected == null) {
            assertNull(actual, context)
            return
        }
        assertEquals(expected.type, actual?.type, context)
        assertEquals(expected.properties["v"], actual?.properties?.get("v"), context)
    }

    @Test
    fun `soak - sequential mutations against the live writer stay linearizable and durable`() = runTest(
        timeout = kotlin.time.Duration.parse("120s"),
    ) {
        val path = tempDbPath()
        // Small mailbox so the soak actually exercises backpressure and
        // multi-batch dedupe, not just one giant coalesced batch.
        val store = SqliteObjectStore.open(path, scope(), mailboxCapacity = 16)

        val random = Random(seed = 20260723)
        val keyspace = 64L
        val model = HashMap<Long, DbEntry>()
        var version = 0

        repeat(4000) { iteration ->
            val hash = random.nextLong(keyspace)
            when (random.nextInt(100)) {
                in 0..39 -> {
                    val e = smallEntry(hash, ++version)
                    model[hash] = e
                    store.storeObject(e)
                }
                in 40..64 -> {
                    val e = largeEntry(hash, ++version)
                    model[hash] = e
                    store.storeObject(e)
                }
                in 65..79 -> {
                    model.remove(hash)
                    store.removeObject(hash)
                }
                else -> {
                    // Read-your-writes: whatever the commit/unpin/dedupe state
                    // on the writer thread, a read must see the latest value.
                    assertSameEntry(model[hash], store.getObject(hash), "mid-soak read of $hash")
                }
            }
            if (iteration % 500 == 499) store.flushBarrier()
        }

        // Full-state check against the model, then durability across reopen.
        store.flush()
        assertEquals(0, store.pinnedLargeCount, "all pins must release once everything committed")
        (0 until keyspace).forEach { hash ->
            assertSameEntry(model[hash], store.getObject(hash), "post-flush read of $hash")
        }
        store.close()

        val reopened = SqliteObjectStore.open(path, scope())
        (0 until keyspace).forEach { hash ->
            assertSameEntry(model[hash], reopened.getObject(hash), "post-reopen read of $hash")
        }
        reopened.close()
    }

    @Test
    fun `bulk import of large rows keeps residency bounded by the mailbox, not the dataset`() = runTest(
        timeout = kotlin.time.Duration.parse("120s"),
    ) {
        val path = tempDbPath()
        val mailboxCapacity = 32
        val store = SqliteObjectStore.open(path, scope(), mailboxCapacity = mailboxCapacity)

        // Models a photo-library backup: far more eager blob rows than any
        // bounded cache may retain. 1500 × 32 KiB ≈ 48 MB if wrongly resident.
        val rows = 1500
        var maxPinned = 0
        (1..rows).forEach { i ->
            store.storeObject(
                DbEntry(
                    type = "thumb",
                    dataIdHash = i.toLong(),
                    fields = listOf(DbValue.ByteArrayValue(ByteArray(32 * 1024) { (i % 127).toByte() })),
                    properties = emptyMap(),
                ),
            )
            if (store.pinnedLargeCount > maxPinned) maxPinned = store.pinnedLargeCount
        }

        // Peak pinned copies = queued (≤ mailboxCapacity) + the batch being committed
        // (≤ mailboxCapacity, the writer's coalescing cap) + one per producer thread parked
        // mid-enqueue. Derived from the two bounds the store enforces, not fitted to an observed
        // number.
        //
        // ⚠ This assertion used to fail whenever the machine was busy, and the constant was
        // blamed. The constant was right and the store was wrong: the writer coalesced *without
        // limit*, and because every `tryReceive` unblocks a parked producer, a starved writer let
        // the batch grow to whatever the producer could push (measured 141 and 90 here against a
        // mailbox of 32). Capping the coalescing loop made the bound true by construction; the
        // flake went away as a consequence, not as the goal. Do NOT "fix" a future failure here by
        // raising the constant or by measuring only after a `flushBarrier` — both would restore a
        // green test that bounds nothing. See docs/tech-debt.md.
        val residencyCeiling = 2 * mailboxCapacity + PRODUCER_SLACK
        assertTrue(
            maxPinned <= residencyCeiling,
            "peak pinned large rows ($maxPinned) must be bounded by the write backlog " +
                "(≤ $residencyCeiling), not the dataset ($rows rows)",
        )

        store.flush()
        assertEquals(0, store.pinnedLargeCount, "no pinned copies may survive the final commit")
        (1..rows).forEach { i ->
            assertTrue(!store.isResident(i.toLong()), "large row $i must not be resident after commit")
        }

        // The data is all still there — served through the read connection.
        val readsBefore = store.readThroughReads
        val sample = store.getObject(1234L)
        assertEquals(readsBefore + 1, store.readThroughReads)
        assertEquals(
            (1234 % 127).toByte(),
            (sample!!.fields[0] as DbValue.ByteArrayValue).value[0],
        )
        store.close()
    }

    private companion object {
        /**
         * Headroom for producer threads that have pinned a row and are parked in `enqueue`'s
         * `trySendBlocking` waiting for a mailbox slot: those rows are pinned but in neither the
         * queue nor the batch. One per producer thread; 8 covers this test's single producer with
         * room to spare.
         */
        const val PRODUCER_SLACK = 8
    }
}
