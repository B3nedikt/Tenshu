package org.tenshu.inference.proxy

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.tenshu.core.common.proxy.FileDatabase

/** How many arrived paths [ArrivalQueue] holds; beyond it the oldest fall to the scheduled pass. */
const val ARRIVAL_QUEUE_CAPACITY: Int = 500

/** How many batches may find an arrived photograph's bytes missing before it is left to the scheduled pass. */
const val ARRIVAL_MAX_UNAVAILABLE: Int = 3

/**
 * The photographs that **arrived** and no batch has finished with yet (IN-D6a) — the list a
 * new-arrivals batch indexes, instead of walking the library.
 *
 * ⚠⚠ Why a queue of paths, not "the library's unseen photographs": a batch that walks to find work
 * reads the whole tree on every arrival, and with any backlog its slots go to the first backlog
 * photographs in tree order — so the photograph that actually arrived may never be indexed.
 *
 * - **De-duplicated, in arrival order, bounded at [capacity]**: past it the OLDEST path is dropped —
 *   it is not lost, it is the backlog's, which the scheduled pass and *Index now* own.
 * - **[settle]** applies one batch's [PathOutcome]s: done leaves; unavailable (no bytes, a failed half, no row yet) stays for
 *   [maxUnavailable] batches and then leaves; a path the batch did not reach stays, untouched.
 * - **Persisted** to one small device-local file (`attempts<TAB>path` per line) by [flush] — a
 *   WorkManager input is limited to 10 KB, and the queue must outlive the process between an
 *   arrival and its job. ⚠ Not on every [add]: [add] is called on the saving thread, so the caller
 *   flushes on its own cadence (Android: before it arms the job). An arrival in the last seconds
 *   before the process dies is the scheduled pass's.
 * - Thread-safe: [add] comes from any saving thread, [settle] from the batch.
 */
class ArrivalQueue(
    private val files: FileDatabase?,
    private val path: String,
    private val capacity: Int = ARRIVAL_QUEUE_CAPACITY,
    private val maxUnavailable: Int = ARRIVAL_MAX_UNAVAILABLE,
) {
    private val lock = SynchronizedObject()

    /** Path → batches that found it unavailable. ⚠ Insertion order is arrival order. */
    private var entries: LinkedHashMap<String, Int>? = null
    private var dirty = false

    /** One arrival. ⚠ A path already queued keeps its place and its count. */
    fun add(filePath: String) {
        if ('\n' in filePath || '\t' in filePath) return
        synchronized(lock) {
            val queued = loaded()
            if (filePath in queued) return
            queued[filePath] = 0
            while (queued.size > capacity) queued.remove(queued.keys.first())
            dirty = true
        }
    }

    /** The queued paths, oldest first. */
    fun paths(): List<String> = synchronized(lock) { loaded().keys.toList() }

    val size: Int get() = synchronized(lock) { loaded().size }

    /** Applies one batch's outcomes; a path with none was not reached and stays as it is. */
    fun settle(outcomes: Map<String, PathOutcome>) {
        if (outcomes.isEmpty()) return
        synchronized(lock) {
            val queued = loaded()
            outcomes.forEach { (filePath, outcome) ->
                val attempts = queued[filePath] ?: return@forEach
                when (outcome) {
                    PathOutcome.DONE -> queued.remove(filePath)
                    PathOutcome.UNAVAILABLE ->
                        if (attempts + 1 >= maxUnavailable) queued.remove(filePath) else queued[filePath] = attempts + 1
                }
            }
            dirty = true
        }
    }

    /**
     * Writes the queue to its file if it changed. ⚠ A failed write is logged, never thrown: the queue
     * is a convenience over the scheduled pass, and losing it costs waiting for that pass.
     *
     * ⚠⚠ The write is made UNDER the lock. Snapshotting under it and writing after let two flushes
     * cross — the older snapshot landing last, with nothing marked dirty to correct it. The file is
     * a few hundred lines at most, so holding the lock for it costs nothing worth that.
     */
    fun flush() {
        val store = files ?: return
        synchronized(lock) {
            if (!dirty) return
            val text = loaded().entries.joinToString("") { (filePath, attempts) -> "$attempts\t$filePath\n" }
            runCatching { store.writeRawByteArray(path, text.encodeToByteArray()) }
                .onSuccess { dirty = false }
                .onFailure { println("ArrivalQueue -> flush failed: $it") }
        }
    }

    /**
     * Empties the queue, in memory and on disk. ⚠⚠ What a realm wipe, a re-pair, a custody's
     * relinquish and a withdrawal of every half owe: the queue is paths of the realm being left —
     * file names can say a lot — and the next realm's first batch would otherwise work them.
     * ⚠ The file is emptied, then unlinked, as the stores' logs are; a failure is logged, and the
     * memory is empty either way.
     */
    fun clear() {
        synchronized(lock) {
            entries = LinkedHashMap()
            dirty = false
            val store = files ?: return
            runCatching {
                if (store.fileSize(path) != null) {
                    store.writeRawByteArray(path, ByteArray(0))
                    store.deleteFile(path)
                }
            }.onFailure { println("ArrivalQueue -> clear of $path failed: $it") }
        }
    }

    /** ⚠ Under [lock]. Read from the file once, on first use; an unreadable file is an empty queue. */
    private fun loaded(): LinkedHashMap<String, Int> {
        entries?.let { return it }
        val read = LinkedHashMap<String, Int>()
        runCatching { files?.readBytes(path)?.get()?.decodeToString() }.getOrNull()
            ?.lineSequence()
            ?.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val attempts = line.substring(0, tab).toIntOrNull() ?: return@forEach
                read[line.substring(tab + 1)] = attempts
            }
        while (read.size > capacity) read.remove(read.keys.first())
        entries = read
        return read
    }
}

/**
 * One new-arrivals batch over [queue] (IN-D6a): the queued paths through
 * [MediaIndexRunner.runPathsBatch], then the queue settled with what became of them and flushed.
 * `null` when another pass is running — the queue is left as it was, for the next batch.
 * ⚠⚠ One batch: nothing here runs a second one for what is left.
 */
suspend fun MediaIndexRunner.runArrivalsBatch(
    executionContext: org.tenshu.core.common.proxy.ExecutionContext,
    queue: ArrivalQueue,
    cap: Int = NEW_ARRIVALS_BATCH_CAP,
): PathsBatch? {
    val batch = runPathsBatch(executionContext, queue.paths(), cap, PassBar.ARRIVALS) ?: return null
    // ⚠⚠ Every half's consent explicitly withdrawn: the user asked for none of this — the queue goes.
    if (batch.everyHalfWithdrawn) {
        queue.clear()
        return batch
    }
    queue.settle(batch.outcomes)
    queue.flush()
    return batch
}
