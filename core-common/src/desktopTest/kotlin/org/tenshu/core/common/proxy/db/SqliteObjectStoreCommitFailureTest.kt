package org.tenshu.core.common.proxy.db

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.tenshu.diagnostics.FileDiagnosticSink
import app.tenshu.diagnostics.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the store does when a batch's `COMMIT` fails — the durability hole behind the shape found
 * on the desktop Control store 2026-09-13 (`drive-folder-files-dangling-member.md`): a list row on
 * disk referencing a member row that was never written.
 *
 * Until then the writer connection had **no `busy_timeout`** and a failed batch was rolled back
 * and **dropped without a log line**. So the one routine failure — another connection holding
 * SQLite's write lock — lost the batch on its first attempt, silently, while every row in it stayed
 * readable from its pin for the rest of the process. A relation row committed by a *later* batch
 * then references a member row that reaches disk never.
 *
 * ⚠ The foreign holder here is a second raw connection in a background thread, which is exactly
 * what a second Control process on the same store (or a `sqlite3` shell mid-write) looks like to
 * this connection. These tests pin the three behaviours that close the hole: the writer waits out a
 * short contention, retries a longer one, and — when it must drop — drops loudly and keeps serving
 * later batches.
 */
class SqliteObjectStoreCommitFailureTest {

    private fun tempDbPath(): String =
        createTempDirectory("sqlite-objstore-commit").resolve("objects.db").toString()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun row(hash: Long) = DbEntry(
        type = "tenshu/file",
        dataIdHash = hash,
        fields = listOf(DbValue.StringValue("/bucket/$hash")),
        properties = emptyMap(),
    )

    /** A `Folder.FILES`-shaped list row: `LongValue` references to member rows. */
    private fun listRow(hash: Long, refs: List<Long>) = DbEntry(
        type = TList.ID,
        dataIdHash = hash,
        fields = refs.map { DbValue.LongValue(it) },
        properties = emptyMap(),
    )

    /**
     * SQLite's write lock on a store's file, held from a second connection — a foreign writer, as
     * far as the store's own connection can tell — until [release]. ⚠ Released on an event the
     * test has seen (a failed `flush()`, a traced retry), never after a guessed time: a hold sized
     * in milliseconds is only as long as the store's retries on an idle machine, and every case
     * paid it in full.
     */
    private class HeldWriteLock(val thread: Thread, private val released: CountDownLatch) {
        fun release() = released.countDown()

        /** [release], then wait for the foreign writer to commit and let go. */
        fun releaseAndJoin() {
            release()
            thread.join(5_000)
        }
    }

    /** Takes the lock on [path]; returns once it is held, so the caller's writes provably contend. */
    private fun holdWriteLock(path: String): HeldWriteLock {
        val held = CountDownLatch(1)
        val released = CountDownLatch(1)
        val thread = Thread {
            val conn = BundledSQLiteDriver().open(path)
            try {
                conn.prepare("BEGIN IMMEDIATE").let { s -> try { s.step() } finally { s.close() } }
                held.countDown()
                // The bound only turns a test that forgot to release into a red one.
                released.await(HOLD_HANG_GUARD_S, TimeUnit.SECONDS)
                conn.prepare("COMMIT").let { s -> try { s.step() } finally { s.close() } }
            } finally {
                conn.close()
            }
        }.apply { isDaemon = true; start() }
        assertTrue(held.await(5, TimeUnit.SECONDS), "the foreign writer must take the lock")
        return HeldWriteLock(thread, released)
    }

    /**
     * Holds the lock on [path] for [holdMs] of real time. ⚠ Only for the one case whose subject
     * is the WAIT itself — the writer waiting on a lock that clears within its `busy_timeout` —
     * where SQLite's busy wait leaves no trace to release on.
     */
    private fun holdWriteLockFor(path: String, holdMs: Long): HeldWriteLock {
        val lock = holdWriteLock(path)
        Thread {
            Thread.sleep(holdMs) // wall-clock: the hold's length is the case's subject (see KDoc)
            lock.release()
        }.apply { isDaemon = true; start() }
        return lock
    }

    /** Holds the lock on [path] until the store beside it has logged its first retry. */
    private fun holdWriteLockUntilFirstRetry(path: String): HeldWriteLock {
        val lock = holdWriteLock(path)
        Thread {
            // ⚠ Released even when no retry comes: the batch then drops, and the case fails on
            // that rather than hanging on a lock nobody lets go.
            try { awaitFirstRetry(path) } finally { lock.release() }
        }.apply { isDaemon = true; start() }
        return lock
    }

    private fun refsOf(entry: DbEntry?): List<Long>? =
        entry?.fields?.map { (it as DbValue.LongValue).value }

    /** Trace items beside the store at [path], newest window. */
    private fun traceOf(path: String) =
        FileDiagnosticSink(SqliteObjectStore.traceFileFor(path)).recent(50)

    /** Blocks until the store beside [path] has logged its first retry, so a batch is in flight. */
    private fun awaitFirstRetry(path: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (traceOf(path).any { it.code == SqliteObjectStore.TRACE_CODE_COMMIT_RETRY }) return
            Thread.sleep(5)
        }
        throw AssertionError("the writer never logged a retry — no batch is in flight")
    }

    @Test
    fun `a batch enqueued while a foreign writer holds the lock commits once the lock is released`() =
        runTest {
            val path = tempDbPath()
            val store = SqliteObjectStore.open(path, scope(), writerBusyTimeoutMs = 2_000)
            val holder = holdWriteLockFor(path, holdMs = 600)

            // A member row and the list row that references it — the `addToArray` shape.
            store.storeObject(row(1L))
            store.storeObject(listRow(100L, listOf(1L)))
            // The barrier must NOT fail: the writer waits on the lock instead of dropping the batch.
            store.flush()
            holder.thread.join(5_000)
            store.close()

            val reopened = SqliteObjectStore.open(path, scope())
            assertEquals(row(1L), reopened.getObject(1L), "the member row must be on disk")
            assertEquals(listOf(1L), refsOf(reopened.getObject(100L)), "…and the list row with it")
            reopened.close()
        }

    /**
     * A contention longer than one `busy_timeout` used to be a dropped batch. Now the writer
     * retries on the backoff schedule and the batch lands — the member row reaches disk no later
     * than the relation that references it, because the writer retries **in place** and every
     * later batch stays queued behind it.
     */
    @Test
    fun `a commit that fails its first attempts is retried and lands in order`() = runTest {
        val path = tempDbPath()
        val store = SqliteObjectStore.open(
            path,
            scope(),
            writerBusyTimeoutMs = 100,
            commitRetryBackoffMs = longArrayOf(50, 100, 200, 400, 800),
        )
        // Let go once the member row's batch has failed and been retried, so a retry is certain.
        val holder = holdWriteLockUntilFirstRetry(path)

        store.storeObject(row(1L))
        store.flushBarrier() // a commit boundary, so the member row is its own batch…
        store.storeObject(listRow(100L, listOf(1L))) // …and the list row lands in a later one.
        store.flush()
        holder.thread.join(5_000)
        store.close()

        val reopened = SqliteObjectStore.open(path, scope())
        assertEquals(row(1L), reopened.getObject(1L), "the retried batch must reach disk")
        assertEquals(listOf(1L), refsOf(reopened.getObject(100L)))
        reopened.close()
    }

    /**
     * The bounded floor: when every retry fails the batch is dropped — but the barrier says so,
     * the writer keeps serving later batches, and the dropped rows are readable in-process only.
     * ⚠ This is the residual hazard, pinned so it is at least visible: a list row committed later
     * can reference a member row that never reached disk. This test asserts the state; the
     * announcement that makes it diagnosable on a real device is pinned by the next one.
     */
    @Test
    fun `a batch that fails every attempt is dropped loudly and the writer keeps going`() = runTest {
        val path = tempDbPath()
        val store = SqliteObjectStore.open(
            path,
            scope(),
            writerBusyTimeoutMs = 20,
            commitRetryBackoffMs = longArrayOf(10, 10),
        )
        val holder = holdWriteLock(path)

        store.storeObject(row(1L))
        val dropped = assertFailsWith<Throwable>("the barrier must report the drop") { store.flush() }
        assertNotNull(dropped)
        // Readable here — the pin is the only copy — which is exactly why the drop is invisible
        // to the process that suffered it.
        assertEquals(row(1L), store.getObject(1L))

        holder.releaseAndJoin()
        // The writer is still alive: a later batch commits normally once the lock is gone.
        store.storeObject(listRow(100L, listOf(1L)))
        store.flush()
        store.close()

        val reopened = SqliteObjectStore.open(path, scope())
        assertNull(reopened.getObject(1L), "the dropped row never reached disk")
        assertEquals(
            listOf(1L),
            refsOf(reopened.getObject(100L)),
            "…while the later list row did: the dangling-reference shape, now at least announced",
        )
        reopened.close()
    }

    /**
     * The announcement has to outlive stdout. Under launchd the Control daemon's stdout goes
     * nowhere (`control-desktop/Main.kt`, `ensureTrayRunning`), so a drop announced only there
     * is announced to nobody — the 2026-09-13 loss would have stayed invisible with the retry
     * code in place. Every retry and the drop itself are therefore also written to
     * `objects.db.log` beside the store, where the person holding a store copy with a dangling
     * reference will look, as items selectable by code and carrying the SQLite error.
     */
    @Test
    fun `every retry and the drop are written to the trace beside the store, not only to stdout`() =
        runTest {
            val path = tempDbPath()
            val store = SqliteObjectStore.open(
                path,
                scope(),
                writerBusyTimeoutMs = 20,
                commitRetryBackoffMs = longArrayOf(10, 10),
            )
            val holder = holdWriteLock(path)

            store.storeObject(row(7L))
            assertFailsWith<Throwable> { store.flush() }
            holder.releaseAndJoin()
            store.close()

            val trace = traceOf(path)
            val retries = trace.filter { it.code == SqliteObjectStore.TRACE_CODE_COMMIT_RETRY }
            assertEquals(2, retries.size, "one item per retried attempt (budget of two): $trace")
            assertTrue(
                retries.all { it.level == LogLevel.WARN && it.message.contains("database is locked") },
                "each retry names the SQLite error: $retries",
            )
            val drop = trace.single { it.code == SqliteObjectStore.TRACE_CODE_BATCH_DROPPED }
            assertEquals(LogLevel.WARN, drop.level)
            assertTrue(drop.message.contains("[7]"), "the drop names the first row hashes: ${drop.message}")
            assertTrue(retries.all { it.timestampMs <= drop.timestampMs }, "retries precede the drop")
        }

    @Test
    fun `a dropped delete says the rows are still on disk, not that they are missing from it`() =
        runTest {
            // ⚠⚠ A dropped batch's consequence is OPPOSITE for a delete. A dropped Put leaves a row
            // readable in this process and absent from disk; a dropped Delete leaves it hidden in
            // this process and STILL ON DISK — so the data comes back at the next open, while the
            // durable trace claimed it was "NOT on disk". A message that states the Put story for
            // every op kind sends whoever reads it looking for the wrong failure.
            val path = tempDbPath()
            val store = SqliteObjectStore.open(
                path,
                scope(),
                writerBusyTimeoutMs = 20,
                commitRetryBackoffMs = longArrayOf(10, 10),
            )
            store.storeObject(row(11L))
            store.flush()

            val holder = holdWriteLock(path)
            store.removeObject(11L)
            assertFailsWith<Throwable> { store.flush() }
            holder.releaseAndJoin()
            store.close()

            val drop = traceOf(path).single { it.code == SqliteObjectStore.TRACE_CODE_BATCH_DROPPED }
            assertTrue(
                drop.message.contains("1 delete"),
                "the drop counts the deletes it lost: ${drop.message}",
            )
            assertTrue(
                drop.message.contains("[11]"),
                "and names them — sampling only Put hashes leaves a dropped delete anonymous: " +
                    "${drop.message}",
            )
            assertTrue(
                drop.message.contains("still on disk"),
                "and states the consequence that actually applies to a delete: ${drop.message}",
            )
            // The row really is still there — the claim above is not cosmetic.
            val reopened = SqliteObjectStore.open(path, scope())
            assertTrue(
                reopened.getObject(11L) != null,
                "a dropped delete leaves the row on disk, which is why the message must say so",
            )
            reopened.close()
        }

    /**
     * ⚠⚠ Whether a `flush()`'s writes ride in its own batch is a **scheduling detail** — the
     * writer may have drained them a moment earlier — and the answer `flush()` gives must not
     * depend on it. Here the Put is already in flight and failing when the barrier is enqueued,
     * so the barrier arrives in a batch of its own with nothing durable in it.
     *
     * Two things are pinned. The barrier still **fails**: the writes it promised were committed
     * were dropped, and `unreportedDrop` is what carries that across the batch boundary — without
     * it `flush()` (and `close()`) would report success over a loss, which is the silent-drop
     * failure this whole change exists to end, one layer up. And the barrier-only batch is
     * **not** itself retried or announced: it has no rows to lose, so taking `BEGIN IMMEDIATE`
     * for it would burn the ladder under the foreign lock and then log a `DROPPED` warning
     * naming nothing — an alarm reserved for real losses, spent on none.
     *
     * ⚠ This interleaving is the one the first version of the test above hit intermittently, so
     * it is pinned rather than left to the scheduler.
     */
    @Test
    fun `a barrier that arrives after the batch it waited on was dropped still fails, and is not itself retried`() =
        runTest {
            val path = tempDbPath()
            val store = SqliteObjectStore.open(
                path,
                scope(),
                writerBusyTimeoutMs = 20,
                commitRetryBackoffMs = longArrayOf(10, 10),
            )
            val holder = holdWriteLock(path)

            store.storeObject(row(7L))
            // The Put's batch is in flight and failing, so the barrier cannot join it.
            awaitFirstRetry(path)
            assertFailsWith<Throwable>("the barrier must answer for the batch it waited on") {
                store.flush()
            }

            // One dropped batch, one set of retries — the barrier's own batch added neither.
            val trace = traceOf(path)
            assertEquals(
                2,
                trace.count { it.code == SqliteObjectStore.TRACE_CODE_COMMIT_RETRY },
                "only the Put's batch is retried: $trace",
            )
            val drop = trace.single { it.code == SqliteObjectStore.TRACE_CODE_BATCH_DROPPED }
            assertTrue(drop.message.contains("[7]"), "the one drop is the Put's: ${drop.message}")

            // Consumed, not latched: once the lock is free the store commits and flushes normally.
            holder.releaseAndJoin()
            store.storeObject(row(8L))
            store.flush()
            store.close()

            val reopened = SqliteObjectStore.open(path, scope())
            assertNotNull(reopened.getObject(8L), "the store keeps working after a reported drop")
            assertNull(reopened.getObject(7L), "…and the dropped row is genuinely gone")
            reopened.close()
        }

    /**
     * The other way to lose writes — process death — cannot produce the dangling shape, and this
     * pins why: the mailbox is FIFO and every batch commits atomically, so a kill loses a
     * **suffix** of the enqueue order. Writers enqueue a member row before the list that names it
     * (`FolderFilesDanglingMemberTest.everyWriterStoresTheMemberRowBeforeTheListRowThatNamesIt`),
     * so the only partial state a kill leaves is a row without its membership — an orphan the
     * G4a sweep already handles — never a membership without its row. ⚠ `pkill` in
     * `scripts/reinstall-all-apps.sh` is exactly this path: nothing in Control flushes on SIGTERM.
     */
    @Test
    fun `a crash loses the tail, so it strands a member without its membership and never the reverse`() =
        runTest {
            val path = tempDbPath()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val crashScope = CoroutineScope(testDispatcher)
            val store = SqliteObjectStore.open(path, crashScope, writerContext = testDispatcher)

            store.storeObject(row(1L))
            store.flushBarrier()
            advanceUntilIdle() // the member row's batch commits

            store.storeObject(listRow(100L, listOf(1L)))
            store.simulateCrash() // process death before the list row's batch
            crashScope.cancel()
            advanceUntilIdle()

            val reopened = SqliteObjectStore.open(path, scope())
            assertEquals(row(1L), reopened.getObject(1L), "the member row committed before the kill")
            assertNull(reopened.getObject(100L), "the list row was the lost tail — an orphan, not a dangle")
            assertFalse(reopened.crossProcessContended, "a dead process's advisory lock is released")
            reopened.close()
        }

    /**
     * The production shape of the trigger: two Control processes on one store, both syncing, so
     * both write continuously and each one's `BEGIN IMMEDIATE` lands while the other's
     * transaction is open. On the old code every such collision dropped a batch on whichever
     * side lost — silently, and on *both* sides over time. Now both wait, and nothing is lost.
     *
     * ⚠ Two stores in one process stand in for two processes: the SQLite locking is the same
     * (two connections on one file), and the in-process case is the one the advisory lock can
     * only *report*, not prevent.
     */
    @Test
    fun `two stores writing the same file concurrently lose nothing`() = runTest {
        val path = tempDbPath()
        val a = SqliteObjectStore.open(path, scope())
        val b = SqliteObjectStore.open(path, scope())
        assertTrue(b.crossProcessContended)

        // Interleave: a member row and a list row per side, many times, from two threads, so the
        // two writers' transactions overlap on the file.
        val writers = (0 until 2).map { side ->
            val store = if (side == 0) a else b
            Thread {
                for (i in 0 until 200) {
                    val hash = side * 100_000L + i
                    store.storeObject(row(hash))
                    store.storeObject(listRow(500_000L + side * 1_000L + i, listOf(hash)))
                    store.flushBarrier()
                }
            }.apply { start() }
        }
        writers.forEach { it.join(30_000) }
        a.flush()
        b.flush()
        a.close()
        b.close()

        val reopened = SqliteObjectStore.open(path, scope())
        for (side in 0 until 2) {
            for (i in 0 until 200) {
                val hash = side * 100_000L + i
                assertEquals(row(hash), reopened.getObject(hash), "row $hash from writer $side is on disk")
                assertEquals(
                    listOf(hash),
                    refsOf(reopened.getObject(500_000L + side * 1_000L + i)),
                    "list $i from writer $side is on disk with its reference",
                )
            }
        }
        reopened.close()
    }

    @Test
    fun `a retried clearAll is not accounted as zero write ops`() = runTest {
        // ⚠ `clearAll()` is `DELETE FROM objects` — the most destructive op in the set. Counting
        // only puts and deletes persisted every retry of a contended one as "commit of 0 write
        // op(s)", and if a later attempt succeeded there was no drop line to correct the account.
        val path = tempDbPath()
        val store = SqliteObjectStore.open(
            path,
            scope(),
            writerBusyTimeoutMs = 20,
            commitRetryBackoffMs = longArrayOf(10, 10),
        )
        store.storeObject(row(9L))
        store.flush()

        val holder = holdWriteLock(path)
        runCatching { store.wipeAllObjects() }
        runCatching { store.flush() }
        holder.releaseAndJoin()
        runCatching { store.close() }

        val retries = traceOf(path).filter { it.code == SqliteObjectStore.TRACE_CODE_COMMIT_RETRY }
        assertTrue(retries.isNotEmpty(), "the contended clearAll retried at all")
        assertTrue(
            retries.none { it.message.contains("commit of 0 write op(s)") },
            "a retried clearAll must not be accounted as zero ops: $retries",
        )
    }

    @Test
    fun `a close cancelled while flushing still releases the lock`() = runTest {
        // ⚠⚠ The cancellation twin of the test below. `close()` suspends in `flush()`, so a cancel
        // arrives while it is parked — and a `finally` alone does not save it: the teardown's own
        // `withContext(Dispatchers.IO)` inherits the cancelled job and throws before running,
        // skipping every close and the release after them. The leaked lock then reports the next
        // in-process open as contended, which is the failure this whole path exists to prevent.
        val path = tempDbPath()
        val store = SqliteObjectStore.open(
            path,
            scope(),
            writerBusyTimeoutMs = 20,
            commitRetryBackoffMs = longArrayOf(10_000, 10_000),
        )
        val holder = holdWriteLock(path)
        store.storeObject(row(5L))

        // Cancel the close while it is parked in the retry backoff inside flush().
        val closing = launch { runCatching { store.close() } }
        withContext(Dispatchers.Default) { delay(200) }
        closing.cancelAndJoin()
        holder.releaseAndJoin()

        val reopened = SqliteObjectStore.open(path, scope())
        assertFalse(
            reopened.crossProcessContended,
            "a cancelled close must still hand back the lock",
        )
        reopened.close()
    }

    @Test
    fun `a close whose final flush fails still releases the lock`() = runTest {
        // ⚠⚠ `close()` calls `flush()` first, and `flush()` THROWS when the last batch exhausted
        // its retries — which is exactly the close that most needs to let go. Everything after it
        // used to be skipped: queue, dispatcher, both connections and the advisory lock stayed
        // live while the caller had been told the store was closed, so the next open in this
        // process was reported as contended and ran beside a leaked writer.
        val path = tempDbPath()
        val store = SqliteObjectStore.open(
            path,
            scope(),
            writerBusyTimeoutMs = 20,
            commitRetryBackoffMs = longArrayOf(10, 10),
        )
        val holder = holdWriteLock(path)
        store.storeObject(row(3L))

        // The close still reports the loss — that is the contract this must not trade away.
        assertFailsWith<Throwable> { store.close() }
        holder.releaseAndJoin()

        val reopened = SqliteObjectStore.open(path, scope())
        assertFalse(
            reopened.crossProcessContended,
            "a close that failed its flush must still hand back the lock",
        )
        reopened.close()
    }

    @Test
    fun `an open that fails releases its lock instead of reporting the retry as contended`() =
        runTest {
            // ⚠⚠ The advisory lock is taken before anything that can fail — it decides which file
            // the trace is — so a throw during initialisation (a corrupt file, a failing PRAGMA, a
            // migration, hash hydration) used to leave `open` with nobody owning the lock and the
            // connections unclosed. The next attempt in the same process then reported CONTENDED,
            // which is a poor failure for the one store whose trace exists to diagnose contention.
            val path = tempDbPath()
            File(path).writeText("this is not a database")

            assertFailsWith<Throwable> { SqliteObjectStore.open(path, scope()) }

            File(path).delete()
            val retry = SqliteObjectStore.open(path, scope())
            assertFalse(
                retry.crossProcessContended,
                "the failed open must hand its lock back, or every later open in this process " +
                    "is misreported as a second writer",
            )
            retry.close()
        }

    @Test
    fun `an open whose caller is cancelled while the built store is handed back releases it`() =
        runTest {
            // ⚠⚠ The last member of the family above, and the only one that is not a failure:
            // initialisation SUCCEEDS, and the value is then lost in the handoff. `open` builds the
            // store inside `withContext(Dispatchers.IO)`, and `withContext` hands its result back
            // with a prompt-cancellation guarantee — a caller cancelled by the time its dispatcher
            // runs the resume gets a CancellationException and the result is DISCARDED. By then the
            // writer is running under the store's detached `SupervisorJob` and the advisory lock
            // belongs to the instance nobody received, so nothing can close either.
            //
            // The race is made deterministic rather than hoped for, with two holds:
            //  - the caller runs on a single thread the test can block, so the resume queues
            //    behind a gate and the cancel lands strictly between "block returned" and "caller
            //    resumed" — the window the issue names;
            //  - the writer's launch (the block's last act, on the IO thread) is held until that
            //    gate is running, i.e. until the caller has provably SUSPENDED. Without it the block
            //    could finish before `withContext` parks, and the value would come back on the
            //    fast path with no dispatch to cancel — a green test that tested nothing.
            val path = tempDbPath()
            val callerThread = Executors.newSingleThreadExecutor { r ->
                Thread(r, "open-caller").apply { isDaemon = true }
            }
            val callerDispatches = AtomicInteger()
            val caller = Executor { task ->
                callerDispatches.incrementAndGet()
                callerThread.execute(task)
            }.asCoroutineDispatcher()

            val callerParked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writerThread = Executors.newSingleThreadExecutor { r ->
                Thread(r, "open-writer").apply { isDaemon = true }
            }
            val writerJob = AtomicReference<Job?>()
            val writer = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    // The context IS the writer coroutine's, so its Job is the writer itself —
                    // the one handle on it that survives the instance being lost.
                    if (writerJob.compareAndSet(null, context[Job])) {
                        check(callerParked.await(5, TimeUnit.SECONDS)) { "caller never parked" }
                    }
                    writerThread.execute(block)
                }
            }

            var received: SqliteObjectStore? = null
            val opening = CoroutineScope(caller).launch {
                received = SqliteObjectStore.open(path, scope(), writerContext = writer)
            }
            // FIFO behind the launch: runs only once the caller has suspended in `withContext`.
            callerThread.execute {
                callerParked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            // The launch was dispatch #1; #2 is the block's result coming back — the store is built.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (callerDispatches.get() < 2 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(2, callerDispatches.get(), "the built store must be on its way back")
            opening.cancel()
            release.countDown()
            withContext(Dispatchers.Default) { opening.join() }

            assertNull(received, "the caller was cancelled, so it must never have received a store")
            val builtWriter = assertNotNull(writerJob.get(), "the store was built and its writer started")

            val reopened = SqliteObjectStore.open(path, scope())
            assertFalse(
                reopened.crossProcessContended,
                "a store the caller never received must hand its lock back, or every later open " +
                    "in this process is misreported as a second writer",
            )
            reopened.close()
            withContext(Dispatchers.Default) { withTimeoutOrNull(5_000) { builtWriter.join() } }
            assertTrue(builtWriter.isCompleted, "…and its writer must stop, not idle forever")

            callerThread.shutdown()
            writerThread.shutdown()
        }

    @Test
    fun `a second store on the same path is reported as contended`() = runTest {
        val path = tempDbPath()
        val first = SqliteObjectStore.open(path, scope())
        assertFalse(first.crossProcessContended, "the first opener holds the lock")

        val second = SqliteObjectStore.open(path, scope())
        assertTrue(second.crossProcessContended, "a second opener must see the lock held")
        second.close()
        first.close()

        val afterwards = SqliteObjectStore.open(path, scope())
        assertFalse(afterwards.crossProcessContended, "closing releases the lock")
        afterwards.close()

        // …and the one contended open is on record beside the store, not only on stdout.
        //
        // ⚠⚠ In its OWN trace, not the owner's. `FileDiagnosticSink.append` is a read-all +
        // `writeText` under a per-instance monitor, so two stores sharing one file truncate each
        // other — and two stores on one path is exactly the condition this record exists to
        // diagnose, so sharing would let the contention erase its own evidence. The owner keeps
        // `objects.db.log`; every other opener writes an `objects.db.contended-*.log` of its own.
        val contendedTraces = File(path).parentFile
            .listFiles { f: File -> f.name.startsWith(File(path).name + ".contended-") }
            .orEmpty()
        val contended = contendedTraces
            .flatMap { FileDiagnosticSink(it).recent(50) }
            .filter { it.code == SqliteObjectStore.TRACE_CODE_LOCK_CONTENDED }
        assertEquals(
            1,
            contended.size,
            "exactly the second opener is recorded, in its own trace: " +
                "${contendedTraces.map { it.name }} -> $contended",
        )
        assertTrue(
            FileDiagnosticSink(SqliteObjectStore.traceFileFor(path)).recent(50)
                .none { it.code == SqliteObjectStore.TRACE_CODE_LOCK_CONTENDED },
            "and NOT in the owner's trace, which it would otherwise truncate",
        )
    }

    private companion object {
        /** How long a foreign writer may hold the lock before the test is deemed hung. */
        const val HOLD_HANG_GUARD_S = 30L
    }
}
