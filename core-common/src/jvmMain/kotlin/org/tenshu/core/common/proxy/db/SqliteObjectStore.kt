package org.tenshu.core.common.proxy.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.tenshu.diagnostics.DiagnosticSink
import app.tenshu.diagnostics.FileDiagnosticSink
import app.tenshu.diagnostics.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.schema.migration.StoreSchemaMigrations
import org.tenshu.core.common.schema.migration.StoreSchemaMigrator
import org.tenshu.core.common.schema.migration.coreVersionReachable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

// Durable blob-KV `ObjectStore` for Android + desktop JVM, over SQLite via the
// first-party `androidx.sqlite` bundled driver. The JS `IndexedDbObjectStore`
// was the original template; the two have since **forked** — it still bulk-loads
// its whole dataset at open and cannot read through synchronously at all
// (`bounded-store-cache.md`, tech-debt).
//
// `ObjectStore` is synchronous and called inline by `DatabaseImpl` under
// `DatabaseLock`, so this is **cache-aside with lazy hydration and a bounded
// read cache** (docs/proposals/bounded-store-cache.md, "Metadata-at-scale end
// states"). Resident memory must not be O(entire dataset):
//
//   - **`open(...)` reads the `hash` column and nothing else.** No blob is
//     read, no entry decoded. That is the whole cost of opening a store of any
//     size: 8 bytes per row.
//   - **Every row is pinned only while its write is uncommitted** (a reader
//     cannot see it on disk yet), dropped once the writer commits, and after
//     that read through a blocking point-SELECT on a dedicated read connection
//     (`readConn` — WAL lets it see committed rows while the writer's
//     transaction is open) + decode, under `DatabaseLock` (proposal F1/F2).
//   - **A byte-bounded LRU (`CACHE_BUDGET_BYTES`) keeps hot metadata off
//     SQLite.** It holds committed rows only, so eviction can never lose a
//     write. Bounded by bytes rather than entries — see the `cache` field.
//   - **Absent-key probes stay O(1):** `knownHashes` gates the read-through —
//     hash ∉ pinned ∧ ∉ cache ∧ ∉ knownHashes → null with no SQLite hit.
//
//   - `storeObject` / `removeObject` mutate the cache + fire listeners
//     synchronously, then enqueue the durable write on a BOUNDED mailbox
//     with a blocking send. Bounding is in-contract: the `ObjectStore`
//     contract forbids *suspending*, not *blocking* — a full mailbox stalls
//     the producing thread under `DatabaseLock` until a batch commits, so a
//     bulk import sees bounded latency instead of an unbounded write backlog
//     growing the heap until process death
//     (docs/proposals/bounded-store-cache.md F3).
//   - A single writer coroutine drains the mailbox, dedupes superseded
//     writes by hash (upsert semantics make every earlier Put/Delete to the
//     same hash dead weight), and commits the batch as one SQLite
//     transaction. This keeps the synchronous `ObjectStore` / `DatabaseLock`
//     contract unchanged with zero `Database` / `DatabaseImpl` edits.
//   - The writer runs on its own dedicated thread by default. This is
//     load-bearing for deadlock freedom: producers blocked in the mailbox
//     send hold `DatabaseLock` on op-executor threads, so the writer must
//     not share a pool those threads can exhaust — and it never acquires
//     `DatabaseLock` itself (`applyBatch` touches only its own connection).
//
// Durability is NOT best-effort: `flush()` is a true barrier (its sentinel
// rides the same ordered mailbox, so it returns only after every prior write
// has committed). `DatabaseImpl.transaction` enqueues that barrier at each
// commit boundary, via `flushBarrier()`.
//
// ⚠⚠ **A batch whose COMMIT fails is retried, then dropped LOUDLY — never
// dropped silently.** Until 2026-09-13 `applyBatch` rolled a failed batch back,
// failed its (unobserved) barrier acks and moved on, with no log line and no
// retry: every row in that batch stayed readable from its pin for the life of
// the process and was simply *not on disk*. And the writer connection had no
// `busy_timeout`, so the one failure that is routine — `SQLITE_BUSY` from a
// second connection holding the write lock (a second Control process on the
// same store, a `sqlite3` shell mid-write) — failed on the first try. The shape
// that leaves behind is exactly the one measured on the desktop store that
// day (`drive-folder-files-dangling-member.md`): a relation row committed by a
// LATER batch referencing a member row the dropped batch carried. Now the
// writer waits on the lock (`WRITER_BUSY_TIMEOUT_MS`), retries a failed commit
// on a bounded backoff (`COMMIT_RETRY_BACKOFF_MS`), and says so on every
// attempt — and if it still has to drop the batch, says THAT, naming the count.
// ⚠ Bounded, not infinite: a permanently failing disk must not wedge every
// producer behind a full mailbox forever; a loud drop is the honest floor.
// ⚠ The bound is a real stall: six attempts × `WRITER_BUSY_TIMEOUT_MS` (5 s)
// plus the 3.1 s of backoff ≈ **33 s** in which this writer — the sole mailbox
// consumer — commits nothing. Producers block on a full mailbox, so a foreign
// lock held that long is a frozen desktop or an ANR wherever a producer sits on
// a main thread. Still the right trade against silent loss, and it only ever
// costs anything while something else holds the write lock — which is itself
// the condition the advisory lock below reports.
//
// ⚠⚠ Every one of those lines — a retry, the drop, a contended lock, a
// core-version wipe — is written to **`objects.db.log` beside the store** as
// well as to stdout (`trace`, a `FileDiagnosticSink`: one
// `timestampMs\tLEVEL\ttag\tcode\tmessage` line per item, a 500-item ring,
// `TRACE_CODE_*` as the code). Stdout alone announces a drop to nobody in
// production: under launchd the Control daemon's stdout goes nowhere
// (`control-desktop/Main.kt`, `ensureTrayRunning`) and a jpackage launcher
// swallows it (tech-debt) — so a drop that only printed would have been as
// silent as the one it replaces. The file lives beside the store because the
// reader who needs it is the one holding a store copy with a dangling
// reference, and because no proxy process installs the global `Diagnostics`
// façade today (the JS twin, which its runtime does wire, logs through the
// façade instead). Stdout stays because it is what a developer reads live —
// both, at one call site (`warn`), per `tech-debt.md` *"76 `println`s never
// reach a durable log"*. Best-effort by contract (`DiagnosticSink.append`
// never throws): a disk that cannot take a trace line could not have taken
// the batch either, and the stdout line still goes out.
//
// ⚠ Process death, by contrast, cannot produce the dangling shape: every
// writer enqueues a member row before the relation that names it (`addToArray`
// saves the value first; `writeValuesToDb` serialises members before the list),
// the mailbox is FIFO, and each batch commits atomically — so whatever a kill
// loses is a *suffix* of the enqueue order, i.e. a member without its
// membership (an orphan), never a membership without its member. Only a
// non-suffix loss — a dropped batch, which is exactly what this change closes —
// leaves a reference pointing at a row that never reached disk.
// `SqliteObjectStoreCommitFailureTest` pins both halves.
//
// ⚠ `open()` also takes an advisory lock beside the file (`objects.db.lock`).
// A second store over the same path — another process, or a second instance
// in this one — is reported, once, as `crossProcessContended`: two writers
// contend on every commit and each one's `knownHashes`/cache goes stale under
// the other's writes, a cross-process hazard this store was never built to
// take.
// The lock file is empty and is never deleted (deleting a lock file another
// process may hold is the classic race); the OS drops the lock with the
// process, so a stale file is inert. Store-copy recipes may skip it.
//
// The SQLite table is forever `(hash, type, data)` — schema evolution of
// `DbEntry`/`DbValue` happens in the binary protocol upstream (see
// `DbEntryCodec`), never via `ALTER TABLE`.
//
// ⚠⚠ **Why the earlier C-lite size tiering was not enough.** It kept rows
// ≤ LARGE_ROW_THRESHOLD_BYTES "resident forever, ~99% of rows" and tiered out
// only blobs, so residency stayed O(row COUNT). Measured on a real device
// 2026-08-03: Control's store held 233,930 rows / 172.7 MB, of which
// **233,914 (99.99%) were under the threshold and therefore all resident** —
// 156.6 MB of it a single leaked type (`tenshu/sync/block` at ~2.5 KB/row).
// Decoded, that exceeded the 256 MB Dalvik heap, and Control OOM-crash-looped
// every ~25 s, taking its clients down with it. Tiering by size cannot help
// when the problem is count; this is the same conclusion, and the same fix,
// that iOS reached (see `docs/tech-debt.md`, "The iOS shared replica keeps
// the whole realm").
//
// ⚠ It also removes a chicken-and-egg: the G3 orphan sweep that would shrink
// such a store runs per sync tick, and under bulk hydration the process died
// inside `open(...)` before the first tick could ever run.
internal class SqliteObjectStore private constructor(
    // Every row's hash — gates the read-through so absent-key probes never hit
    // SQLite, and is the ONLY thing open() hydrates. 8 bytes/row: a 234k-row
    // store costs ~1.9 MB here versus the ~172 MB its entries decode to.
    // Mutated only under `DatabaseLock`.
    private val knownHashes: MutableSet<Long>,
    private val conn: SQLiteConnection,
    private val readConn: SQLiteConnection,
    parentScope: CoroutineScope,
    writerContext: CoroutineContext?,
    private val mailboxCapacity: Int,
    private val cacheBudgetBytes: Long,
    /** Backoff between commit attempts of one batch; its size is the retry budget. */
    private val commitRetryBackoffMs: LongArray,
    /** The advisory lock beside the file, or null when it could not be taken at all. */
    private val storeLock: StoreLock?,
    /**
     * Whether another store — another process, or a second instance in this one — held the
     * advisory lock when this store opened. Reported once at open; a diagnostic, not a refusal.
     */
    val crossProcessContended: Boolean,
    /**
     * The file this store's warnings persist to — `objects.db.log` when it owns the advisory
     * lock, an `objects.db.contended-*.log` of its own when it does not. See [traceFileFor].
     */
    internal val traceFile: File,
    /** Where this store's warnings persist beside its file — the class comment's trace. */
    private val trace: DiagnosticSink,
) : ObjectStore, WipeableObjectStore {

    /** A cached row and what it cost on disk — the budget unit. */
    private class CachedRow(val entry: DbEntry, val bytes: Int)

    // Bounded read-through cache of COMMITTED entries, so hot metadata paths
    // do not pay a point-SELECT each time. ⚠ Populated only in [readRow] —
    // never at write time. That is what makes eviction unconditionally safe:
    // everything in here is already durable, so dropping it costs a re-read
    // and never a lost row. An uncommitted write lives in [pinnedLarge] instead.
    // Access-ordered, so eviction is true LRU; mutated on get as well as put,
    // under the same `DatabaseLock` discipline as every other field here.
    //
    // ⚠⚠ Bounded by BYTES, not by entry count, and that distinction is the whole
    // point. A count bound is blind to what it is holding: 16k rows is trivial for
    // metadata and enormous for blobs, so the only count that is safe for the worst
    // case starves the common one. Timeline's Home reads
    // `workouts.sortByDescending{start}.take(300)` over **10,232** rows — ordered-window
    // fusion makes that O(N) *thin* reads, which cost nothing against a resident map
    // but are a point-SELECT + decode each under lazy hydration. A count bound small
    // enough to be safe could not hold that working set, and Home took 15–18 s with
    // both processes CPU-pegged. Measured against the same store, the *legitimate*
    // working set is ~12 MB encoded (the 52.8 MB of `sync/block` above it is the leak
    // the G3 sweep is removing) — so a byte budget holds all of it and still cannot
    // grow with the dataset, which is what the OOM fix actually required.
    private val cache = object : LinkedHashMap<Long, CachedRow>(256, 0.75f, true) {}

    /** Running total of [CachedRow.bytes], so eviction never has to walk the map. */
    private var cacheBytes: Long = 0

    /** Insert under the byte budget, evicting least-recently-used rows until it fits. */
    private fun cachePut(hash: Long, entry: DbEntry, bytes: Int) {
        // A single row larger than the whole budget is simply not cached — caching it
        // would evict everything else to hold one item that no longer fits anyway.
        if (bytes > cacheBudgetBytes) return
        cache.put(hash, CachedRow(entry, bytes))?.let { cacheBytes -= it.bytes }
        cacheBytes += bytes
        if (cacheBytes <= cacheBudgetBytes) return
        val it = cache.entries.iterator() // access-ordered ⇒ eldest first
        while (cacheBytes > cacheBudgetBytes && it.hasNext()) {
            cacheBytes -= it.next().value.bytes
            it.remove()
        }
    }

    private fun cacheDrop(hash: Long) {
        cache.remove(hash)?.let { cacheBytes -= it.bytes }
    }

    // A row whose durable write hasn't committed is invisible to
    // `readConn`, so its entry is pinned here until the writer commits it.
    // Refcounted: a coalesced re-put of the same hash must not be dropped
    // when the OLDER write commits (or is deduped away) while a newer one is
    // still queued. `entry == null` marks "removed while a put is in flight"
    // — reads must see the removal immediately, but the refcount has to
    // survive until every enqueued op for the hash has been accounted for.
    // Shared between the `DatabaseLock` thread (pin/clear) and the writer
    // (unpin) → concurrent map with compute-based transitions.
    private data class Pin(val count: Int, val entry: DbEntry?)

    private val pinnedLarge = java.util.concurrent.ConcurrentHashMap<Long, Pin>()

    // Guards `readConn` — see [withReadConn].
    private val readLock = Any()

    // The one hot statement, compiled lazily and reused for the life of the store. Only ever
    // touched inside [withReadConn], so it needs no synchronisation of its own.
    private var pointRead: SQLiteStatement? = null

    // Test seam: number of point-SELECTs served by `readConn`, so tests can
    // assert both "absent probes stay off SQLite" and "a dropped row is
    // actually re-read from disk".
    @Volatile
    internal var readThroughReads: Int = 0

    // Test seams for the residency-bound assertions: pinned rows are the ONLY
    // uncommitted copies the store may hold, and their count is bounded by the
    // enqueued-uncommitted backlog (mailbox + in-flight batch), never by
    // dataset size. `isResident` now means "served without touching SQLite" —
    // a bounded, evictable property rather than the old forever-resident one.
    internal val pinnedLargeCount: Int get() = pinnedLarge.size
    internal fun isResident(dataIdHash: Long): Boolean =
        pinnedLarge.containsKey(dataIdHash) || cache.containsKey(dataIdHash)

    /** Test seam: bytes currently held by the read cache. */
    internal val cachedBytes: Long get() = cacheBytes

    // Dedicated writer thread unless a test injects a pacing dispatcher via
    // `writerContext` (the crash tests need "writer has not drained yet" to
    // be deterministic). See the class comment: blocked producers must not
    // be able to starve the writer's dispatcher.
    private val ownedWriterDispatcher: ExecutorCoroutineDispatcher? =
        if (writerContext == null) {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "tenshu-objectstore-writer").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        } else {
            null
        }

    private val writeScope: CoroutineScope =
        CoroutineScope(
            parentScope.coroutineContext +
                (writerContext ?: ownedWriterDispatcher!!) +
                SupervisorJob(),
        )

    private val listeners: MutableMap<Long, MutableList<(DbEntry) -> Unit>> = mutableMapOf()

    private val writeQueue: Channel<WriteOp> = Channel(mailboxCapacity)

    // Test seam: ops actually applied to SQLite (post-dedupe), so the
    // dedupe-by-hash behaviour is observable without exposing the batch.
    @Volatile
    internal var appliedWriteOps: Int = 0

    private sealed interface WriteOp {
        data class Put(val entry: DbEntry) : WriteOp
        data class Delete(val dataIdHash: Long) : WriteOp
        class Flush(val ack: CompletableDeferred<Unit>) : WriteOp
        class ClearAll(val ack: CompletableDeferred<Unit>) : WriteOp
    }

    // A pinned row's hash is added to `knownHashes` at storeObject, so this one
    // collection covers every live row, committed or not.
    override fun isEmpty(): Boolean = knownHashes.isEmpty()

    override fun storeObject(dbEntry: DbEntry) {
        val hash = dbEntry.dataIdHash
        knownHashes.add(hash)
        // ⚠ Drop rather than update: the cache holds only committed entries, and
        // this one is not committed yet. Its readable copy is the pin.
        cacheDrop(hash)
        pin(hash, dbEntry)
        notifyListeners(hash, dbEntry)
        enqueue(WriteOp.Put(dbEntry))
    }

    // O(1) in BOTH directions, which the default `getObject(...) != null` is not: `knownHashes`
    // is resident and is the same gate `getObject` consults, `storeObject` adds to it and
    // `removeObject` removes from it — so membership is exactly "a live row exists here",
    // committed or not, with no SQLite hit and no deserialisation either way.
    override fun hasObject(dataIdHash: Long): Boolean = dataIdHash in knownHashes

    override fun getObject(dataIdHash: Long): DbEntry? {
        // A pin holds the only readable copy of an uncommitted row — and a
        // null-entry pin marks a removal that disk hasn't seen yet. It is
        // checked FIRST because it is the only source that can be newer than
        // both the cache and disk.
        pinnedLarge[dataIdHash]?.let { return it.entry }
        cache[dataIdHash]?.let { return it.entry }
        if (dataIdHash !in knownHashes) return null // absent: no SQLite hit
        return readRow(dataIdHash)
    }

    override fun setListener(
        dataIdHash: Long,
        listener: (update: DbEntry) -> Unit,
    ) {
        val listenersForHash = listeners.getOrPut(dataIdHash) { mutableListOf() }
        listenersForHash.add(listener)

        // Contractual: fire immediately with the current entry if present.
        // `DatabaseImpl.setArrayListener`'s old/new diff depends on this
        // (matches InMemoryObjectStore.setListener). Via getObject so a
        // dropped-but-durable large row still fires.
        getObject(dataIdHash)?.let(listener)
    }

    override fun removeListener(dataIdHash: Long) {
        listeners.remove(dataIdHash)
    }

    /**
     * E4-3b (§8.3.4): the one runtime enumeration, over the `type` column the
     * store already indexes rows by for the migration seam. ⚠ Reads through the
     * dedicated read connection like any other point read, and deliberately does
     * NOT consult the resident cache: a large row may be present only on disk
     * (lazy hydration — any row may be disk-only), so a cache-only answer would silently omit
     * exactly the biggest rows.
     */
    override fun rowsOfTypes(typeIds: Set<String>): List<DbEntry> {
        if (typeIds.isEmpty()) return emptyList()
        flushBarrier()
        val placeholders = typeIds.joinToString(", ") { "?" }
        return withReadConn {
            val rows = ArrayList<DbEntry>()
            val stmt = readConn.prepare("SELECT data FROM objects WHERE type IN ($placeholders)")
            try {
                typeIds.forEachIndexed { index, typeId -> stmt.bindText(index + 1, typeId) }
                while (stmt.step()) rows += DbEntryCodec.decode(stmt.getBlob(0))
            } finally {
                stmt.close()
            }
            rows
        }
    }

    /**
     * G3 (`core-local-store-orphan-gc.md`): the bounded sibling of [rowsOfTypes]. Same read
     * connection and same deliberate cache bypass — a large row may live only on disk under the
     * C-lite size tiering, so a cache-only answer would omit exactly the biggest rows.
     *
     * `hash > ?` + `ORDER BY hash` + `LIMIT` makes the page a stable, resumable window: the caller
     * passes the last hash it saw and can never skip or re-see a row, however many rows are
     * deleted between pages.
     *
     * ⚠ This is the *cheap* side of the seam — the `type` index means only matching rows are ever
     * visited, so `examineLimit` has nothing to bound. See [ObjectStore.scanRowsOfType] for why
     * the parameter exists at all (the retired iOS file store was the store that needed it).
     */
    override fun scanRowsOfType(
        typeId: String,
        afterHash: Long,
        matchLimit: Int,
        examineLimit: Int,
    ): ObjectStore.TypeScanPage? {
        if (matchLimit <= 0) return null
        flushBarrier()
        return withReadConn {
            val rows = ArrayList<DbEntry>(minOf(matchLimit, 512))
            var lastHash = afterHash
            val stmt = readConn.prepare(
                "SELECT hash, data FROM objects WHERE type = ? AND hash > ? ORDER BY hash LIMIT ?"
            )
            try {
                stmt.bindText(1, typeId)
                stmt.bindLong(2, afterHash)
                stmt.bindLong(3, matchLimit.toLong())
                while (stmt.step()) {
                    lastHash = stmt.getLong(0)
                    rows += DbEntryCodec.decode(stmt.getBlob(1))
                }
            } finally {
                stmt.close()
            }
            // ⚠ [examineLimit] is deliberately ignored here, and that is not an oversight: the
            // `type` index means the engine only ever visits matching rows, so rows-examined and
            // rows-matched are the same budget. It exists for stores whose only index is the hash
            // (the retired iOS file store, where honouring it was the whole point).
            //
            // A short page IS exhaustion here, because the only bound that could have cut it
            // short is `matchLimit`.
            ObjectStore.TypeScanPage(
                rows = rows,
                // ⚠ On an empty page the cursor must not move — `afterHash` is exclusive, so
                // advancing it past a row nobody looked at would skip that row forever.
                nextCursor = lastHash,
                exhausted = rows.size < matchLimit,
            )
        }
    }

    override fun removeObject(dataIdHash: Long) {
        knownHashes.remove(dataIdHash)
        cacheDrop(dataIdHash)
        clearPinnedEntry(dataIdHash)
        enqueue(WriteOp.Delete(dataIdHash))
    }

    // Honest backpressure (bounded-store-cache.md, "The write side"): blocks
    // the calling thread when the mailbox is full, until the writer commits a
    // batch. Never suspends, so the synchronous `ObjectStore` contract holds.
    // A closed channel (store shut down / crashed) drops the op — same
    // semantics the old fire-and-forget trySend had after shutdown.
    private fun enqueue(op: WriteOp) {
        writeQueue.trySendBlocking(op)
    }

    // Transaction-commit barrier (see ObjectStore.flushBarrier KDoc).
    // Fire-and-forget: enqueues an ordered commit point and returns. On a
    // full mailbox the trySend fails — harmless: every drained batch commits
    // as one transaction anyway, so the barrier only marks a commit point
    // that batching already provides. The throwaway ack is never awaited —
    // its (exceptional) completion is inert on a Deferred no one observes.
    // Bounds the crash loss window to the unflushed tail.
    override fun flushBarrier() {
        writeQueue.trySend(WriteOp.Flush(CompletableDeferred()))
    }

    // Suspends until every write enqueued before this call has committed to
    // SQLite. The sentinel rides the same ordered mailbox, so completion
    // implies durability of all prior ops.
    suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        writeQueue.send(WriteOp.Flush(ack))
        ack.await()
    }

    // Wipes cache + on-disk store. Used by revocation purge. Flushes first so
    // an in-flight put can't race the delete and resurrect an entry.
    /** RG-3 / D3: the discard path — see [WipeableObjectStore]. */
    override suspend fun wipeAllObjects() = clearAll()

    suspend fun clearAll() {
        flush()
        knownHashes.clear()
        cache.clear()
        cacheBytes = 0
        pinnedLarge.clear() // empty after flush; cleared for symmetry
        val ack = CompletableDeferred<Unit>()
        writeQueue.send(WriteOp.ClearAll(ack))
        ack.await()
    }

    // Flushes, stops the writer, closes the connection. Call on shutdown.
    // Closing the mailbox first means a racing producer's blocking send
    // fails fast instead of parking forever on a channel no one drains.
    /**
     * ⚠⚠ The teardown runs in `finally`, because [flush] **throws** when the final batch exhausted
     * its retries — and that is precisely the close that must still release everything. Without
     * it the queue, the writer dispatcher, both connections and the advisory lock stayed live on
     * exactly the path where a caller is told the store is closed, so a later open of the same
     * path in this process was reported as contended and ran beside a leaked writer.
     *
     * ⚠ The flush failure is still what the caller sees: it is the loss being reported, and a
     * close that failed to tidy up must not replace it with its own noise. Cleanup failures are
     * swallowed individually for the same reason.
     */
    suspend fun close() {
        try {
            flush()
        } finally {
            // ⚠⚠ NonCancellable, and the release in a nested `finally`. A `finally` alone is not
            // enough here: `close()` suspends in `flush()`, so a cancellation lands while it is
            // parked, and the `withContext(Dispatchers.IO)` below inherits that cancelled job and
            // throws BEFORE running its block. Teardown would then be skipped and the release
            // after it never reached — the same leaked lock and connections this whole path
            // exists to prevent, arriving by cancellation instead of by failure.
            withContext(NonCancellable) {
                try {
                    runCatching { writeQueue.close() }
                    // ⚠⚠ Join, do not merely cancel. The writer's SQLite calls are not
                    // cancellable — it can be inside a five-second busy wait in `applyBatch` —
                    // so cancelling and pressing on closes `conn` underneath a running batch on a
                    // connection that is not thread-safe, and releases the lock while the old
                    // writer is still executing. Cancellation only takes effect at the writer's
                    // next suspension point either way; waiting for it is the difference between
                    // a torn-down store and two things using one connection.
                    runCatching { writerJob?.cancelAndJoin() }
                    runCatching { writeScope.cancel() }
                    runCatching { ownedWriterDispatcher?.close() }
                    withContext(Dispatchers.IO) {
                        runCatching { conn.close() }
                        // Same guard as every other use: closing under an in-flight read is
                        // the same unsynchronised access, just with a worse failure mode.
                        runCatching {
                            withReadConn {
                                pointRead?.close()
                                pointRead = null
                                readConn.close()
                            }
                        }
                    }
                } finally {
                    // ⚠ Last, and unconditional: the lock is what a later open misreports as
                    // contention, so it must survive any failure among the closes above.
                    storeLock?.release()
                }
            }
        }
    }

    // Test seam: models process death. Stops the writer with ops possibly
    // still queued and finalises the DB file with only what was committed —
    // i.e. NO flush. Not a production path (`close()` is the graceful one);
    // exists so the durability contract can be asserted deterministically.
    internal fun simulateCrash() {
        writeQueue.close()
        writeScope.cancel()
        ownedWriterDispatcher?.close()
        conn.close()
        withReadConn {
            pointRead?.close()
            pointRead = null
            readConn.close()
        }
        // The OS releases a dead process's lock; the model has to as well.
        storeLock?.release()
    }

    private fun notifyListeners(dataIdHash: Long, updatedEntry: DbEntry) {
        listeners[dataIdHash]?.toList()?.forEach { listener ->
            listener(updatedEntry)
        }
    }

    // --- large-row tiering: pin-until-commit + read-through ------------------

    private fun pin(hash: Long, entry: DbEntry) {
        pinnedLarge.compute(hash) { _, pin -> Pin((pin?.count ?: 0) + 1, entry) }
    }

    // Removal must be visible to reads immediately, but the refcount has to
    // outlive it: every enqueued Put still gets its unpin on commit/dedupe.
    private fun clearPinnedEntry(hash: Long) {
        pinnedLarge.computeIfPresent(hash) { _, pin -> Pin(pin.count, entry = null) }
    }

    // Writer side: called once per enqueued large Put — after its commit, or
    // when the batch dedupe drops it as superseded. At zero the row is
    // durable (or superseded by one that will be), so the pinned copy drops
    // and later reads go through `readConn`.
    private fun unpin(hash: Long) {
        pinnedLarge.compute(hash) { _, pin ->
            if (pin == null || pin.count <= 1) null else Pin(pin.count - 1, pin.entry)
        }
    }

    // Blocking point-SELECT under `DatabaseLock` (proposal F1). `readConn` sees
    // committed state regardless of the writer's open transaction (WAL, proposal
    // F2). The row read here IS committed by definition — `getObject` reaches
    // this only after missing the pin — so caching it is safe.
    private fun readRow(dataIdHash: Long): DbEntry? {
        readThroughReads++
        return withReadConn {
            // ⚠⚠ The statement is compiled ONCE and re-bound, never re-prepared. Under lazy
            // hydration this is the hot path for *every* row the cache misses, and
            // `SQLiteConnection.prepare` recompiles the SQL on each call — measured as the
            // dominant cost of a cold Timeline Home (~10k rollup rows in one combine), which took
            // 15 s on device. `reset()` returns the statement to its initial state and bindings
            // are overwritten on the next bind, so no `clearBindings()` is needed for a
            // fully-bound single-parameter query.
            //
            // Safe because [withReadConn] serialises every use of `readConn` — a shared statement
            // has exactly the thread-affinity its connection does, no more.
            val stmt = pointRead ?: readConn.prepare(POINT_READ_SQL).also { pointRead = it }
            // ⚠⚠ The `finally` reset is what keeps the WAL bounded. It is not redundant with the
            // `reset()` that opens the `try`: that one makes this call's *bindings* safe, this one
            // releases the transaction the call leaves behind — same API, two different jobs, and
            // only the second is load-bearing here.
            //
            // A stepped-but-unreset SELECT holds an implicit **read transaction**
            // open on its connection, and SQLite cannot *reset* the WAL while any reader holds a
            // snapshot — `wal_checkpoint` still copies every page into the db (so no data is at
            // risk) but returns `busy=1` and leaves the log to grow. Resetting only on the way IN
            // meant `readConn` sat inside a read transaction from the end of one point read until
            // the start of the next, which under lazy hydration is essentially always: observed as
            // a **124 MB WAL against a 3.6 MB database** (34x the whole file, ~1 MB/min while
            // otherwise idle) on a desktop Control left running for two days.
            //
            // ⚠ Keep the statement *cached* — re-preparing was the measured dominant cost of a
            // cold Timeline Home (~15 s on device). Resetting is not re-preparing: the compiled
            // plan survives, only the read transaction is released. The sibling paths
            // ([rowsOfTypes] / [scanRowsOfType]) already get this for free via `stmt.close()`;
            // this path opted out of closing for the cache and so has to do it by hand.
            try {
                stmt.reset()
                stmt.bindLong(1, dataIdHash)
                if (!stmt.step()) return@withReadConn null
                // The blob IS the budget unit — exact, and free to measure here.
                val blob = stmt.getBlob(0)
                return@withReadConn DbEntryCodec.decode(blob)
                    .also { cachePut(dataIdHash, it, blob.size) }
            } finally {
                stmt.reset()
            }
        }
    }

    /**
     * Serialises every use of [readConn].
     *
     * ⚠⚠ A `SQLiteConnection` is **not thread-safe**, and this one is reached from more than one
     * thread: point reads run on whatever op-executor thread holds `DatabaseLock`, while
     * [rowsOfTypes] / [scanRowsOfType] are driven by the sync scheduler (the G3 orphan sweep).
     * Concurrent use corrupts the connection's own state and surfaces as `disk I/O error` (10) or
     * `file is not a database` (26) — *not* as anything that names threading.
     *
     * This was latent before lazy hydration: read-through was reached only by the handful of
     * rows above the old size tier, so two threads almost never overlapped on it. Making every
     * read go through `readConn` turned "almost never" into "constantly", and
     * `HubPairingSystemTest` failed immediately.
     *
     * ⚠ No lock inversion: nothing inside this lock takes `DatabaseLock`, and the one caller that
     * needs a flush ([rowsOfTypes]/[scanRowsOfType]) issues its non-blocking `flushBarrier()`
     * *before* entering. Ordering is therefore always DatabaseLock → readLock, never the reverse.
     */
    private inline fun <T> withReadConn(block: () -> T): T = synchronized(readLock) { block() }

    // NOTE: the C-lite size-tier helpers (`isLargeEntry` / `estimateEncodedSize`)
    // are gone. Every row now takes the path that used to be the large-row path,
    // so there is nothing left to classify — see the class comment.

    /**
     * ⚠⚠ Retained, because [close] must **join** it. `writeScope.cancel()` does not wait, and the
     * writer's SQLite calls are not cancellable — it can sit in a five-second busy wait inside
     * `applyBatch`. Closing `conn` while that runs is concurrent use of a connection that is not
     * thread-safe, and the advisory lock would be handed back with the old writer still executing.
     */
    private var writerJob: Job? = null

    private fun startWriter() = writeScope.launch {
        for (first in writeQueue) {
            val batch = ArrayList<WriteOp>()
            batch.add(first)
            // Coalesce what is already queued into one transaction — but never more than one
            // mailbox-worth.
            //
            // ⚠ The cap is load-bearing, not tidiness. `enqueue` parks the producer thread on a
            // full mailbox, so **every `tryReceive` here frees a slot and immediately unblocks
            // it**. Without a bound the producer refills as fast as this loop drains and the two
            // ping-pong: the batch grows to "whatever the producer can push while the writer
            // spins", and every large Put in it stays pinned until `COMMIT`. Residency was then
            // bounded by that race rather than by the backlog — the opposite of what this store
            // exists to guarantee (`bounded-store-cache.md`), and it grew precisely when the
            // writer was starved of CPU. Measured before the cap: 141 and 90 pinned large rows
            // against a mailbox of 32.
            //
            // With the cap, peak pinned large rows ≤ (queued: ≤ capacity) + (this batch: ≤
            // capacity) + one per producer thread parked mid-enqueue — which is what
            // `SqliteObjectStoreSoakTest` asserts, now as a property of the design rather than a
            // guess about scheduling. The cost is more, smaller transactions when a producer
            // outruns the writer; that is the same trade Kafka makes with `batch.size` beside
            // `buffer.memory`, and RocksDB with a bounded group-commit batch beside its memtable
            // limit. A queue bound alone does not bound memory if the drainer's batch is
            // unbounded.
            while (batch.size < mailboxCapacity) {
                val next = writeQueue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            val keep = survivorMask(batch)
            // A dropped Put still holds a pin from its enqueue — release it
            // here; the surviving op for the hash keeps its own.
            // ⚠ Must match [storeObject]'s pin exactly: EVERY Put pins, so every
            // Put unpins. When this was conditional on a size tier and the pin
            // was not, small rows would pin forever and rebuild the unbounded
            // resident set this change exists to remove.
            batch.forEachIndexed { index, op ->
                if (!keep[index] && op is WriteOp.Put) {
                    unpin(op.entry.dataIdHash)
                }
            }
            commitWithRetry(batch.filterIndexed { index, _ -> keep[index] })
        }
    }

    private fun warn(code: String, message: String) = warn(trace, code, message)

    /**
     * A batch [commitWithRetry] dropped that no barrier in that batch was waiting on, and which no
     * barrier has been told about yet. Writer-confined — only the single mailbox consumer touches it.
     *
     * ⚠ `flush()` promises that every write enqueued before it has **committed**, so a barrier that
     * rides in a later batch than the loss still has to answer for it. ⚠ Consumed, never latched:
     * the first barrier after the drop reports it and the store is ordinary again — a permanently
     * failing barrier would make one lost batch look like a dead store.
     *
     * ⚠ Best-effort, and deliberately so: the first barrier after a drop may be a [flushBarrier]
     * whose ack nobody awaits, in which case the report is spent on no one. The **log** is the
     * guarantee (D1 — a store never loses a write without saying so); this only makes sure a
     * caller who *is* waiting is not told "committed" about writes that were not.
     */
    private var unreportedDrop: Throwable? = null

    /** Complete [acks], unless a drop is still owed an answer — see [unreportedDrop]. */
    private fun settleBarriers(acks: List<CompletableDeferred<Unit>>) {
        if (acks.isEmpty()) return
        val owed = unreportedDrop
        if (owed == null) {
            acks.forEach { it.complete(Unit) }
        } else {
            unreportedDrop = null
            acks.forEach { it.completeExceptionally(owed) }
        }
    }

    /**
     * Commit [batch], retrying a failed commit on [commitRetryBackoffMs] before giving up.
     *
     * ⚠⚠ Every attempt and the final drop are logged, because a silently dropped batch is the
     * worst state this store can be in: its rows stay readable from their pins, so nothing in the
     * process notices, and the loss surfaces only after a restart — as a relation that references
     * a row which "never existed" (see the class comment). ⚠ The barrier acks fail only on the
     * final drop, so `flush()` (and therefore `close()`) waits through the retries rather than
     * reporting a failure that is about to be recovered from. ⚠ A drop in a batch **no** barrier
     * rode in is carried to the next one ([unreportedDrop]): whether a `flush()`'s writes share
     * its batch is a scheduling detail, and its answer must not be.
     *
     * ⚠ In order, in place: the writer is the only consumer of the mailbox, so retrying here
     * keeps every later batch behind this one — the enqueue order that lets a member row always
     * reach disk no later than the relation that references it.
     */
    private suspend fun commitWithRetry(batch: List<WriteOp>) {
        // ⚠ A batch of nothing but barriers carries no durable work — everything a [WriteOp.Flush]
        // waits on was enqueued in an EARLIER batch — so there is nothing to wrap in a transaction.
        // Taking `BEGIN IMMEDIATE` for it is not merely wasteful: under a foreign write lock it
        // burns the whole retry ladder (~33 s) and then announces `DROPPED a batch of 0 write
        // op(s)` naming no rows, which is precisely the alarm this change exists to reserve for
        // real losses. ⚠ `ClearAll` IS durable work (`DELETE FROM objects`); only `Flush` is not.
        // ⚠⚠ Skipping the transaction must not skip the ANSWER: [unreportedDrop] is what keeps
        // `flush()`'s promise honest when the batch it was waiting on is the one that was dropped.
        if (batch.all { it is WriteOp.Flush }) {
            settleBarriers(batch.map { (it as WriteOp.Flush).ack })
            return
        }
        var attempt = 0
        while (true) {
            val failure = applyBatch(batch) ?: return
            attempt++
            // ⚠ ClearAll counts. It is the most destructive op in the set — `DELETE FROM
            // objects` — and excluding it made every retry of a contended `clearAll()` persist as
            // "commit of 0 write op(s)", with no final-drop line to correct the account if a later
            // attempt then succeeded. The drop branch below already reports it; this is its twin.
            val writes = batch.count {
                it is WriteOp.Put || it is WriteOp.Delete || it is WriteOp.ClearAll
            }
            if (attempt > commitRetryBackoffMs.size) {
                // ⚠⚠ The consequence is OPPOSITE for the two kinds, so the message reports them
                // separately. A dropped Put leaves its row readable in this process and absent
                // from disk. A dropped Delete (or ClearAll) leaves it hidden in this process and
                // STILL ON DISK — the data comes back at the next open. One unconditional
                // sentence claiming the Put story sent whoever read it looking for the wrong
                // failure, and a `ClearAll`-only batch was additionally reported as zero ops.
                //
                // The hashes are what lets a later reader match a dangling reference to the batch
                // that lost its row — the 2026-09-13 incident took a day to name two rows. ⚠ Both
                // kinds are sampled: a dropped delete was anonymous when only Puts were.
                val puts = batch.mapNotNull { (it as? WriteOp.Put)?.entry?.dataIdHash }
                val deletes = batch.mapNotNull { (it as? WriteOp.Delete)?.dataIdHash }
                val clears = batch.count { it is WriteOp.ClearAll }
                val lost = buildList {
                    if (puts.isNotEmpty()) {
                        add(
                            "${puts.size} put(s) readable in this process only and NOT on disk — a " +
                                "relation committed by a later batch may now reference a row this " +
                                "one carried; hashes ${puts.take(8)}"
                        )
                    }
                    if (deletes.isNotEmpty()) {
                        add(
                            "${deletes.size} delete(s) hidden in this process but still on disk — " +
                                "they return at the next open; hashes ${deletes.take(8)}"
                        )
                    }
                    if (clears > 0) {
                        add("$clears clearAll(s) applied in memory only — the store is still on disk")
                    }
                }
                warn(
                    TRACE_CODE_BATCH_DROPPED,
                    "DROPPED a batch after $attempt failed commit attempts — $failure. " +
                        lost.joinToString("; ") +
                        ". Check for another writer on this file — a second process, or a " +
                        "SQLite client.",
                )
                var reported = false
                for (op in batch) {
                    when (op) {
                        is WriteOp.Flush -> { op.ack.completeExceptionally(failure); reported = true }
                        is WriteOp.ClearAll -> { op.ack.completeExceptionally(failure); reported = true }
                        else -> {}
                    }
                }
                // Nobody was waiting on THIS batch, so the loss is still unannounced to any caller.
                // Hand it to the next barrier — whichever batch that rides in — or `flush()` would
                // report success for writes that never committed.
                if (!reported) unreportedDrop = failure
                return
            }
            val backoff = commitRetryBackoffMs[attempt - 1]
            warn(
                TRACE_CODE_COMMIT_RETRY,
                "commit of $writes write op(s) failed (attempt $attempt of " +
                    "${commitRetryBackoffMs.size + 1}) — $failure. Retrying in $backoff ms.",
            )
            delay(backoff)
        }
    }

    // Upsert semantics make every Put/Delete superseded by a later op on the
    // same hash dead weight — a bulk import re-storing a growing membership
    // array n times would otherwise upsert n copies. Keep only the last op
    // per hash, in the survivors' original order, so Put/Delete/ClearAll
    // interleavings commit the same final state as the un-deduped batch.
    // Flush/ClearAll are ordered markers and always survive in place.
    private fun survivorMask(batch: List<WriteOp>): BooleanArray {
        val keep = BooleanArray(batch.size) { true }
        if (batch.size < 2) return keep
        val lastOpIndexByHash = HashMap<Long, Int>()
        batch.forEachIndexed { index, op ->
            when (op) {
                is WriteOp.Put -> lastOpIndexByHash[op.entry.dataIdHash] = index
                is WriteOp.Delete -> lastOpIndexByHash[op.dataIdHash] = index
                else -> {}
            }
        }
        batch.forEachIndexed { index, op ->
            keep[index] = when (op) {
                is WriteOp.Put -> lastOpIndexByHash[op.entry.dataIdHash] == index
                is WriteOp.Delete -> lastOpIndexByHash[op.dataIdHash] == index
                else -> true
            }
        }
        return keep
    }

    /**
     * One commit attempt. Returns `null` when the batch committed, else the failure — the caller
     * ([commitWithRetry]) decides whether to try again. The batch is rolled back on failure, and
     * the pins of its Puts are deliberately **kept**: the pinned copy is the only readable truth
     * for a row disk has not received.
     */
    private fun applyBatch(batch: List<WriteOp>): Throwable? {
        val acks = ArrayList<CompletableDeferred<Unit>>()
        try {
            exec("BEGIN IMMEDIATE")
            for (op in batch) {
                when (op) {
                    is WriteOp.Put -> upsert(op.entry)
                    is WriteOp.Delete -> deleteRow(op.dataIdHash)
                    is WriteOp.ClearAll -> {
                        exec("DELETE FROM objects")
                        acks.add(op.ack)
                    }

                    is WriteOp.Flush -> acks.add(op.ack)
                }
            }
            exec("COMMIT")
            appliedWriteOps += batch.count { it is WriteOp.Put || it is WriteOp.Delete }
            // Committed → these rows are visible to `readConn`, so their
            // pinned copies may drop (read-through will find them). Unpin
            // strictly AFTER commit.
            // ⚠ Unconditional, mirroring [storeObject]'s unconditional pin.
            for (op in batch) {
                if (op is WriteOp.Put) unpin(op.entry.dataIdHash)
            }
            settleBarriers(acks)
            return null
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            runCatching { exec("ROLLBACK") }
            // ⚠ The acks are NOT failed here — [commitWithRetry] fails them only once it has
            // given up, so a barrier waits through a transient failure instead of reporting one.
            return t
        }
    }

    private fun upsert(entry: DbEntry) {
        statement(
            "INSERT INTO objects (hash, type, data) VALUES (?, ?, ?) " +
                "ON CONFLICT(hash) DO UPDATE SET type = excluded.type, data = excluded.data",
        ) { stmt ->
            stmt.bindLong(1, entry.dataIdHash)
            stmt.bindText(2, entry.type)
            stmt.bindBlob(3, DbEntryCodec.encode(entry))
            stmt.step()
        }
    }

    private fun deleteRow(dataIdHash: Long) {
        statement("DELETE FROM objects WHERE hash = ?") { stmt ->
            stmt.bindLong(1, dataIdHash)
            stmt.step()
        }
    }

    private fun exec(sql: String) {
        statement(sql) { it.step() }
    }

    private inline fun <R> statement(sql: String, block: (SQLiteStatement) -> R): R {
        val stmt = conn.prepare(sql)
        return try {
            block(stmt)
        } finally {
            stmt.close()
        }
    }

    companion object {
        // Bounds the unflushed-write backlog (the Fit-import crash path: an
        // importer outrunning the writer used to grow the UNLIMITED mailbox —
        // and, in Phase 2, its pinned cache entries — without bound). Sized
        // for organic burstiness: a full mailbox of ~56 KiB blob rows is
        // ~30 MB transient, and any producer that fills it is exactly the
        // bulk writer backpressure exists for.
        internal const val POINT_READ_SQL = "SELECT data FROM objects WHERE hash = ?"

        const val WRITE_MAILBOX_CAPACITY = 512

        /**
         * How long the writer connection waits for SQLite's write lock before a commit attempt
         * fails with `SQLITE_BUSY`. Matches `readConn`'s `busy_timeout`. ⚠ Was **0** until
         * 2026-09-13 — the writer failed on the first try whenever any other connection held the
         * lock, and (before [commitWithRetry]) that dropped the batch silently.
         */
        const val WRITER_BUSY_TIMEOUT_MS: Long = 5_000

        /**
         * Backoff between commit attempts of one batch — five retries, ~3 s in total on top of
         * the busy timeout each attempt already waits. Bounded on purpose: see the class comment.
         */
        val COMMIT_RETRY_BACKOFF_MS: LongArray = longArrayOf(100, 200, 400, 800, 1_600)

        /** Tag on every console line and trace item this store emits. */
        private const val TAG = "SqliteObjectStore"

        // `LogItem.code`s — stable and PII-free, so a reader (or a future upload filter) can
        // select the store's items without parsing the message.
        internal const val TRACE_CODE_COMMIT_RETRY = "store_commit_retry"
        internal const val TRACE_CODE_BATCH_DROPPED = "store_batch_dropped"
        internal const val TRACE_CODE_LOCK_CONTENDED = "store_lock_contended"
        internal const val TRACE_CODE_LOCK_UNAVAILABLE = "store_lock_unavailable"
        internal const val TRACE_CODE_CORE_WIPED = "store_core_version_wiped"

        /**
         * The trace of the store that **owns** [path]: `objects.db.log` next to `objects.db`.
         *
         * ⚠⚠ Only the holder of the advisory lock writes here. `FileDiagnosticSink.append` is a
         * read-all + `writeText` under a per-INSTANCE monitor — correct for the single writer it
         * documents itself for, and a truncating read-modify-write race between two. Two stores
         * on one path is precisely the condition this trace exists to diagnose, so letting them
         * share the file would let the contention erase its own evidence. A store that does not
         * hold the lock therefore writes [contendedTraceFileFor] instead, and the file name
         * carries the attribution §7.2 of `drive-folder-files-dangling-member.md` wanted.
         * ⚠ Read the whole set when investigating: `objects.db.log` + `objects.db.contended-*.log`.
         */
        internal fun traceFileFor(path: String): File = File("$path.log")

        /**
         * The trace of a store that opened [path] **without** getting the advisory lock — a second
         * process, a second instance in this one, or a store that could not open the lock file at
         * all. Distinct per opener, so no two of them write one file.
         */
        internal fun contendedTraceFileFor(path: String): File =
            File("$path.contended-${System.currentTimeMillis()}-${Random.nextInt(0x1000_0000)}.log")

        /**
         * Say it on stdout AND in the trace beside the store — both, at the one call site, for
         * the reasons in the class comment. [message] carries no prefix; the console line gets
         * the `WARN <tag>:` shape `grep` has always found, the trace item gets [code].
         */
        private fun warn(trace: DiagnosticSink, code: String, message: String) {
            println("WARN $TAG: $message")
            trace.append(LogLevel.WARN, TAG, message, code)
        }

        /**
         * The advisory lock beside the store file (`<path>.lock`), or `null` when the lock file
         * itself could not be opened — the lock is a diagnostic and must never fail an open.
         * [StoreLock.contended] is true when another holder already had it.
         */
        private fun acquireStoreLock(path: String): StoreLock? = try {
            // ⚠ `java.io`, not `java.nio.file`: this source set is shared with Android at
            // minSdk 24, where `Path.of` / `Files` do not exist. `FileChannel` itself is API 1.
            val channel = java.io.RandomAccessFile("$path.lock", "rw").channel
            val lock: FileLock? = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // A second store instance in THIS process — the same hazard as another process.
                null
            } catch (t: Throwable) {
                // ⚠⚠ Anything else means locking is unavailable here, not that the lock is
                // held — a filesystem without usable file locking throws from `tryLock`. Close
                // the channel before giving up: it is the only reference, so returning null
                // through the outer catch leaked a descriptor on every open, and a store that
                // reopens on such a filesystem leaks one each time.
                runCatching { channel.close() }
                throw t
            }
            StoreLock(channel, lock)
        } catch (_: Throwable) {
            null
        }

        // Bounds the read-through cache, in ENCODED bytes (see the `cache` field for
        // why bytes and not entries). Purely a latency knob: every entry is a committed
        // row, so a miss is a point-SELECT on the `hash` primary key, never a
        // correctness issue.
        //
        // ⚠ Decoded objects run several times their encoded size, so 16 MiB here is
        // roughly 40–60 MB of heap — bounded, and an order below the 256 MB Dalvik cap
        // that the unbounded resident map used to blow. Sized to hold a real device's
        // entire legitimate working set (~12 MB encoded, measured 2026-08-04) while
        // remaining a constant, not a function of dataset size.
        const val CACHE_BUDGET_BYTES: Long = 16L * 1024 * 1024

        // ⚠ DEAD: this store no longer tiers by size (see the note above `readRow` — every
        // row now takes what used to be the large-row path), and nothing references this
        // alias. Kept only to avoid a source change in a comment-only sweep; delete it and
        // its `ObjectStore` counterpart usage stays iOS-only.
        const val LARGE_ROW_THRESHOLD_BYTES = ObjectStore.LARGE_ROW_THRESHOLD_BYTES

        /**
         * @param writerContext test seam: overrides the writer coroutine's
         *   dispatcher so a test can pace it (e.g. `StandardTestDispatcher`
         *   for the crash/durability tests). Production leaves it null and
         *   gets a dedicated writer thread — see the class comment for why
         *   that is load-bearing.
         * @param mailboxCapacity test seam for exercising backpressure with
         *   a small bound; production uses [WRITE_MAILBOX_CAPACITY].
         * @param migrations the E2b store-open migration seam
         *   (`core-schema-evolution.md` §8.1/§8.4): when non-null, per-service
         *   schema-migration chains run between the format-version gate and
         *   cache hydration (see [StoreSchemaMigrator]). `null` keeps the
         *   pre-E2b behaviour (wipe-only gate, no per-service versions).
         */
        suspend fun open(
            path: String,
            scope: CoroutineScope,
            writerContext: CoroutineContext? = null,
            mailboxCapacity: Int = WRITE_MAILBOX_CAPACITY,
            migrations: StoreSchemaMigrations? = null,
            // Test seam only: shrink the read cache so bounded-residency is
            // assertable without writing a budget's worth of rows.
            cacheBudgetBytes: Long = CACHE_BUDGET_BYTES,
            // Test seams: shorten the lock wait and the retry schedule so a
            // contended commit is exercisable in well under a second.
            writerBusyTimeoutMs: Long = WRITER_BUSY_TIMEOUT_MS,
            commitRetryBackoffMs: LongArray = COMMIT_RETRY_BACKOFF_MS,
        ): SqliteObjectStore {
            // ⚠⚠ A store the block BUILT is not yet a store the caller HAS. `withContext` hands its
            // result back with a prompt-cancellation guarantee: a caller cancelled by the time its
            // own dispatcher runs the resume gets a CancellationException and the value is dropped
            // — after the block returned, so the block's own `catch` never sees it. By then the
            // writer is running under the instance's detached `SupervisorJob` and the advisory lock
            // is the instance's, so nobody could close either, and every later open of this path in
            // the process would be reported as contended.
            //
            // ⚠ Hence ownership is tracked OUT here, where the throw lands. `NonCancellable` inside
            // the block would not help: the block is on IO and the caller is not, so the value
            // still crosses a cancellable dispatch on the way back (`withContext`'s KDoc, "Returning
            // closeable resources"). `withContext` resumes only once the block has finished, so when
            // it throws, [built] is final: set means the caller never received that store.
            var built: SqliteObjectStore? = null
            try {
                return openOnIo(
                    path, scope, writerContext, mailboxCapacity, migrations, cacheBudgetBytes,
                    writerBusyTimeoutMs, commitRetryBackoffMs,
                ) { built = it }
            } catch (t: Throwable) {
                built?.let { orphan ->
                    // `close()` rather than a bespoke teardown: it already survives cancellation and
                    // a failing flush, and releases the lock last — in its own `finally`, so even a
                    // close that throws has released it. The orphan has taken no writes, so its flush
                    // is one barrier round-trip. ⚠ Its failure is not the news here, but it is kept:
                    // attached to the error being rethrown, not dropped, so a close that went wrong
                    // is visible to whoever reads that error.
                    withContext(NonCancellable) {
                        runCatching { orphan.close() }.onFailure { t.addSuppressed(it) }
                    }
                }
                throw t
            }
        }

        private suspend fun openOnIo(
            path: String,
            scope: CoroutineScope,
            writerContext: CoroutineContext?,
            mailboxCapacity: Int,
            migrations: StoreSchemaMigrations?,
            cacheBudgetBytes: Long,
            writerBusyTimeoutMs: Long,
            commitRetryBackoffMs: LongArray,
            /** Told of the store the moment it owns the lock and a running writer. */
            onBuilt: (SqliteObjectStore) -> Unit,
        ): SqliteObjectStore =
            withContext(Dispatchers.IO) {
                // ⚠ The lock FIRST, because it decides which file the trace is: only the owner
                // writes `objects.db.log`, or two contending stores would truncate each other's
                // evidence of the very contention being traced (see [traceFileFor]). Nothing
                // between these two lines warns, so nothing is lost by the ordering.
                val storeLock = acquireStoreLock(path)
                val owned = storeLock != null && !storeLock.contended
                val contended = storeLock != null && storeLock.contended
                // ⚠⚠ Three states, not two. A null lock means locking was UNAVAILABLE — no lock
                // file, or a filesystem whose `tryLock` throws — which is not the same as holding
                // it. Folding that into "uncontended" meant the store opened with no lock, reported
                // `crossProcessContended = false`, and said nothing: two writers could run with
                // exactly the diagnostic this change exists to provide switched off, silently.
                val lockUnavailable = storeLock == null
                val traceFile = if (owned) traceFileFor(path) else contendedTraceFileFor(path)
                val trace: DiagnosticSink = FileDiagnosticSink(traceFile)
                if (lockUnavailable) {
                    warn(
                        trace,
                        TRACE_CODE_LOCK_UNAVAILABLE,
                        "could not take an advisory lock on $path.lock — this store cannot tell " +
                            "whether another writer is open on the same file, so the contention " +
                            "warning below will never fire even if there is one. Expect " +
                            "SQLITE_BUSY retries and stale reads with no explanation for them.",
                    )
                }
                if (contended) {
                    warn(
                        trace,
                        TRACE_CODE_LOCK_CONTENDED,
                        "another store already holds $path.lock — a second process (or a second " +
                            "instance in this one) is open on this store. Its writes contend with " +
                            "ours on every commit, and neither side's row cache sees the other's " +
                            "writes. This store is not built for that; expect SQLITE_BUSY retries " +
                            "below and stale reads until one of them closes.",
                    )
                }
                // ⚠⚠ Everything from the first connection to the constructed store is guarded:
                // ownership of the lock and of both connections transfers to the instance ONLY if
                // construction completes. A throw in between — a corrupt file, a failing PRAGMA,
                // a migration, hash hydration — used to exit `open` with the advisory lock still
                // held by nobody and the connections unclosed, so the next in-process attempt was
                // reported as CONTENDED and file descriptors leaked until GC. Misreporting
                // contention is a poor failure for the one store whose trace exists to diagnose it.
                val opened = mutableListOf<SQLiteConnection>()
                try {
                    val conn = BundledSQLiteDriver().open(path).also { opened += it }
                    fun exec(sql: String) {
                        val s = conn.prepare(sql)
                        try {
                            s.step()
                        } finally {
                            s.close()
                        }
                    }
                    // First, before any statement that needs the write lock: the writer waits for a
                    // foreign holder instead of failing its commit on the spot (class comment).
                    exec("PRAGMA busy_timeout = $writerBusyTimeoutMs")
                    // WAL + NORMAL: crash-safe, one fsync per checkpoint.
                    exec("PRAGMA journal_mode=WAL")
                    exec("PRAGMA synchronous=NORMAL")
                    // One-shot reclaim of a log that a previous process left oversized. SQLite RESETS
                    // the WAL when a checkpoint completes but never SHRINKS the file, so a store that
                    // grew its log once keeps that space allocated for the life of the file — the
                    // 124 MB WAL beside a 3.6 MB db that [readRow]'s held read transaction produced
                    // would otherwise persist forever, fixed or not.
                    //
                    // ⚠ Safe and cheap precisely because it runs here: this is the first statement on
                    // a brand-new connection, before any reader exists to hold a snapshot. If another
                    // process does hold one, the pragma reports busy and changes nothing — hence no
                    // error handling and no `exec` failure path to consider.
                    exec("PRAGMA wal_checkpoint(TRUNCATE)")
                    exec(
                        "CREATE TABLE IF NOT EXISTS objects (" +
                            "hash INTEGER PRIMARY KEY NOT NULL, " +
                            "type TEXT NOT NULL, " +
                            "data BLOB NOT NULL)",
                    )

                    // Core-version gate (`core-schema-evolution.md` §8.9, E2j-2 —
                    // was the unconditional format-version wipe of Phase 0 /
                    // `file-content-addressing.md` §5.1).
                    //
                    // PRAGMA user_version records the TENSHU_STORAGE_FORMAT_VERSION this
                    // file was last written under — 0 on a genuinely fresh DB *and* on
                    // every pre-format-version store (they never stamped it). That constant
                    // is `:core`'s slot in the VersionVector (`currentVersionVector`), so
                    // this gate is the core service's twin of the per-service gate
                    // [StoreSchemaMigrator.migrateStoreAtOpen] applies below — and it now
                    // judges the same way: **migrate if the recorded version is reachable,
                    // wipe only if it is not.**
                    //
                    // ⚠⚠ Why this had to change: HR #9 bumps the format version on every
                    // reshape, and the old gate wiped the rows *and* dropped the recorded
                    // per-service versions BEFORE the migrator ran. The two halves of §11's
                    // "bump AND ship the migration" heuristic cancelled: no store-open
                    // migration could ever do work. See §8.9.
                    //
                    // ⚠ Reachability is DERIVED, never declared — [floorFor] over the
                    // registered core chain, exactly as services derive theirs. With no
                    // core migration registered the floor equals the current version and any
                    // mismatch wipes — the state at the release baseline (v1, no core step).
                    // `recorded > current` (store written by newer code) wipes for the same reason
                    // the per-service path does — current code cannot read forward-shaped rows.
                    //
                    // DELETE is a harmless no-op on an empty/fresh DB, so the wipe branch
                    // also serves as "stamp a brand-new store".
                    val recordedVersion = run {
                        val s = conn.prepare("PRAGMA user_version")
                        try {
                            if (s.step()) s.getLong(0).toInt() else 0
                        } finally {
                            s.close()
                        }
                    }
                    val coreFloor = migrations?.coreReadableFloor() ?: TENSHU_STORAGE_FORMAT_VERSION
                    // The shared rule (E2j-3) — the iOS/JS twins judge core the same way.
                    val coreUnreachable = !coreVersionReachable(
                        recorded = recordedVersion,
                        current = TENSHU_STORAGE_FORMAT_VERSION,
                        floor = coreFloor,
                    )
                    if (coreUnreachable) {
                        if (recordedVersion != 0) {
                            // Data loss is written down, never implied (§8.4) — the same
                            // posture StoreSchemaMigrator takes for a below-floor service.
                            warn(
                                trace,
                                TRACE_CODE_CORE_WIPED,
                                "core recorded v$recordedVersion is outside the readable range " +
                                    "[$coreFloor, $TENSHU_STORAGE_FORMAT_VERSION] — wiping store.",
                            )
                        }
                        exec("DELETE FROM objects")
                        // The per-service version + journal tables (E2b) describe
                        // rows that no longer exist — drop them so the migration
                        // seam below re-stamps the wiped store fresh.
                        exec("DROP TABLE IF EXISTS ${SqliteStoreMigrationAdapter.SERVICE_VERSIONS_TABLE}")
                        exec("DROP TABLE IF EXISTS ${SqliteStoreMigrationAdapter.JOURNAL_TABLE}")
                    }
                    if (recordedVersion != TENSHU_STORAGE_FORMAT_VERSION) {
                        // PRAGMA cannot bind params; the value is our own int constant.
                        exec("PRAGMA user_version = $TENSHU_STORAGE_FORMAT_VERSION")
                    }

                    // E2b store-open migration seam (core-schema-evolution.md
                    // §8.1/§8.4): resume crashed steps, then run any per-service
                    // chain whose recorded version trails the declared one —
                    // synchronously, on this connection, BEFORE the resident cache
                    // hydrates and before the writer exists. With zero drift this
                    // is a handful of tiny SELECTs and touches no rows.
                    if (migrations != null) {
                        val adapter = SqliteStoreMigrationAdapter(conn)
                        val migrator = StoreSchemaMigrator(adapter, adapter, migrations)
                        migrator.resumeIncompleteMigrations()
                        migrator.migrateStoreAtOpen()
                    }

                    // Hash-only hydration: read the `hash` column and nothing else, so
                    // open() costs O(rows × 8 B) instead of O(entire dataset). No blob is
                    // read and no entry is decoded here; rows load through on first
                    // getObject. See the class comment for why size tiering was not enough.
                    val knownHashes = HashSet<Long>()
                    val load = conn.prepare("SELECT hash FROM objects")
                    try {
                        while (load.step()) knownHashes.add(load.getLong(0))
                    } finally {
                        load.close()
                    }

                    // Dedicated read-through connection (proposal F2): WAL lets
                    // it see committed rows while the writer's transaction is
                    // open on `conn`. Opened after the schema + format gate ran.
                    val readConn = BundledSQLiteDriver().open(path).also { opened += it }
                    run {
                        val s = readConn.prepare("PRAGMA busy_timeout = 5000")
                        try {
                            s.step()
                        } finally {
                            s.close()
                        }
                    }

                    SqliteObjectStore(
                        knownHashes,
                        conn,
                        readConn,
                        scope,
                        writerContext,
                        mailboxCapacity,
                        cacheBudgetBytes,
                        commitRetryBackoffMs,
                        storeLock,
                        contended,
                        traceFile,
                        trace,
                    ).also {
                        it.writerJob = it.startWriter()
                        // ⚠ Last, because this is where ownership moves: before it the `catch`
                        // below releases the lock and connections, after it only `close()` can —
                        // and [open] must learn of the instance before the handoff can lose it.
                        onBuilt(it)
                    }
                } catch (t: Throwable) {
                    // Reverse order, best-effort: a close that itself fails must not mask the
                    // failure being reported, and the lock must go even if a close throws.
                    opened.asReversed().forEach { runCatching { it.close() } }
                    storeLock?.release()
                    throw t
                }
            }
    }
}

/**
 * The advisory lock beside a store file. [contended] means another holder — another process, or
 * a second instance in this one — already had it when this store opened; the channel is kept open
 * either way so the lock (when held) lives exactly as long as the store.
 */
internal class StoreLock(private val channel: FileChannel, private val lock: FileLock?) {
    val contended: Boolean get() = lock == null

    fun release() {
        runCatching { lock?.release() }
        runCatching { channel.close() }
    }
}

/**
 * Opens a durable SQLite-backed [ObjectStore] at [path], hydrating the
 * resident cache (small rows; large rows are read through on demand — see
 * `SqliteObjectStore`) before returning. [scope] owns the async
 * write-behind writer.
 *
 * Public factory over the `internal` implementation: `:control-shared`
 * platform DI binds this without the impl class leaking across the module
 * boundary (same convention as the `Interface.create(...)` factories).
 * Suspends because the cache hydration is I/O; callers resolve it during
 * proxy init (the `runBlocking` bridge in the platform Koin module).
 *
 * [migrations] wires the E2b store-open schema-migration seam
 * (`core-schema-evolution.md` §8.1); `null` (the default, and every current
 * production call site) keeps the pre-E2b wipe-only gate.
 */
suspend fun openSqliteObjectStore(
    path: String,
    scope: CoroutineScope,
    migrations: StoreSchemaMigrations? = null,
): ObjectStore =
    SqliteObjectStore.open(path, scope, migrations = migrations)
