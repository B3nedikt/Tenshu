package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.ModelProvenance

/**
 * The durable [EmbeddingStore] — an **append-only log** on the device's own filesystem,
 * read once into memory at construction.
 *
 * ⚠⚠ Device-local, via [FileDatabase], for the reason [EmbeddingStore] gives: IN-D5 refuses to sync
 * vectors. Nothing here touches the realm DB or the preference service, because both persist to the
 * *synced* realm.
 *
 * **Why a log and not a slotted file.** Ranking reads every vector anyway — IN-D12 says the op is a
 * scan until an index exists, and sizes the working set at ~40 MB for 20 000 photos, which is held
 * rather than re-read. Once the whole set is resident, random access buys nothing, and a log avoids
 * the free-list and compaction machinery a slotted file needs to survive deletions.
 *
 * ⚠⚠ **A torn tail is discarded, not an error.** An indexing pass runs for hours and can be killed
 * at any moment, so the last record is routinely half-written. Parsing stops at the first record
 * that does not fit and the file is treated as ending there — the alternative, refusing to open,
 * would turn one interrupted pass into a store that never opens again.
 *
 * ⚠ Every write is an append, so re-indexing a library grows the file even where nothing changed.
 * [rebase] truncates, and a model change is the only thing that makes the whole file garbage
 * anyway. A pass that re-derives the *same* model's vectors repeatedly would grow it without bound
 * — recorded in `inference-service.md` §16 rather than solved here, because `isCurrent` already
 * stops that pass from running.
 *
 * ⚠⚠ **A row is removed by striking its records in place, never by rewriting the file** —
 * [LogStrikes] is the argument. The log stays the only file this store ever writes, so there is no
 * copy for a crash to leave half-promoted and none for [clear] to miss.
 */
class DefaultEmbeddingStore(
    private val fileDatabase: FileDatabase,
    private val path: String,
) : EmbeddingStore {

    /** ⚠ Guards the map AND the append offset: two appends racing would interleave two records. */
    private val lock = Mutex()
    private val vectors = LinkedHashMap<String, FloatArray>()
    private val withoutVector = mutableSetOf<String>()
    private var stamp: ModelProvenance? = null
    private var appendOffset: Long = 0

    /** Where each path's records sit, so a removal can strike and zero them in place. */
    private val strikes = LogStrikes(fileDatabase, path, ::blank)

    /**
     * ⚠⚠ The log could not be re-read after a write whose outcome is unknown ([resync]). Memory is
     * empty and means nothing; every operation retries the re-read first ([inStep]).
     */
    private var outOfSync = false

    init {
        // ⚠ A log that cannot be read now is not an empty store: it is one that is out of step,
        // and says so on first use rather than failing the constructor or serving nothing.
        resync()
        // ⚠ Best-effort, because this is a constructor: a record a crash left struck but not yet
        // zeroed is already gone from every read. What is still owed is its bytes, and a disk that
        // cannot be written now leaves them queued for the next removal to retry.
        runCatching { strikes.scrub() }
    }

    /**
     * **The one recovery path** — throw memory away and rebuild it from the file. The face store's
     * `resync` carries the argument: after any operation returns *or throws*, memory is what a
     * reopen would parse, and a write whose outcome is not known is settled by reading the file,
     * never by patching memory to match a guess.
     *
     * @return `false` when the re-read itself failed; the store is then [outOfSync].
     */
    private fun resync(): Boolean {
        fun forgetEverything() {
            vectors.clear()
            withoutVector.clear()
            stamp = null
            appendOffset = 0
            strikes.reset()
        }
        return try {
            forgetEverything()
            load()
            outOfSync = false
            true
        } catch (failure: Throwable) {
            forgetEverything()
            outOfSync = true
            false
        }
    }

    /** Runs [block] on a store that is in step with its log, or throws [LogOutOfSync]. */
    private inline fun <T> inStep(block: () -> T): T {
        if (outOfSync && !resync()) throw LogOutOfSync(path, null)
        return block()
    }

    /** How many struck records still hold their content — what the next removal will retry. */
    internal suspend fun recordsNotErased(): Int = lock.withLock { inStep { strikes.notErased } }

    override suspend fun provenance(): ModelProvenance? = lock.withLock { inStep { stamp } }

    override suspend fun size(): Int = lock.withLock { inStep { vectors.size } }

    override suspend fun put(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean =
        lock.withLock { inStep { putLocked(filePath, vector, producedBy) } }

    private fun putLocked(filePath: String, vector: FloatArray, producedBy: ModelProvenance): Boolean {
        val current = stamp
        if (current != null && current != producedBy) return false
        val width = vectors.values.firstOrNull()?.size
        if (width != null && width != vector.size) return false

        if (current == null) {
            // ⚠ Header first, stamp second — the face store's rule. A stamp set before a header
            // write that then throws leaves the next `put` appending a record to a file with no
            // magic, which the next open rejects whole.
            writeHeader(producedBy)
            stamp = producedBy
        }
        val record = encodePut(filePath, vector)
        strikes.noteStanding(filePath, append(record), record.size, PUT)
        vectors[filePath] = vector
        withoutVector.remove(filePath)
        return true
    }

    override suspend fun visited(filePath: String): Boolean =
        lock.withLock { inStep { filePath in vectors || filePath in withoutVector } }

    override suspend fun markVisited(filePath: String): Boolean =
        lock.withLock { inStep { markVisitedLocked(filePath) } }

    private fun markVisitedLocked(filePath: String): Boolean {
        if (stamp == null) return false
        if (filePath in withoutVector) return true
        // ⚠ Same record shape as a removal, different flag. ⚠⚠ A build that predates the flag stops
        // replaying at it as it would at a torn tail — only a *downgrade* reads it, and what it loses
        // is re-derivable vectors, never content.
        val record = encodePathOnly(VISITED, filePath)
        strikes.noteStanding(filePath, append(record), record.size, VISITED)
        vectors.remove(filePath)
        withoutVector += filePath
        return true
    }

    override suspend fun remove(filePath: String) {
        val removal = lock.withLock { forgetLocked(listOf(filePath)) }
        if (!removal.complete) throw RemovalIncomplete(removal)
    }

    override suspend fun remembered(): Set<String> =
        lock.withLock { inStep { vectors.keys + withoutVector + strikes.paths() } }

    override suspend fun forget(paths: Collection<String>): Removal = lock.withLock { forgetLocked(paths) }

    /**
     * ⚠⚠ **Disk first, memory second, per path.** [LogStrikes.strike] returns only once the row is
     * gone from the log, so a failure leaves memory still holding exactly what a reopen would
     * replay. The old tombstone did it the other way round: it forgot the row and then appended, so
     * a failed append left a row memory denied and the next start brought back.
     *
     * ⚠ No tombstone is appended any more. A `REMOVE` record hid a vector from the replay and left
     * its floats in the file; a strike removes the row and the scrub that follows takes the floats
     * with it. Logs that already hold `REMOVE` records replay as before.
     */
    private fun forgetLocked(paths: Collection<String>): Removal = inStep {
        fun holds(filePath: String) = filePath in vectors || filePath in withoutVector
        val held = paths.filterTo(HashSet()) { holds(it) }
        var refused: Throwable? = null
        for (filePath in paths) {
            try {
                strikes.strike(filePath)
                vectors.remove(filePath)
                withoutVector.remove(filePath)
            } catch (failure: Throwable) {
                // The others are still tried.
                if (refused == null) refused = failure
            }
        }
        // ⚠⚠ A strike that failed may still have landed, and memory cannot say which: it is rebuilt
        // from the file, and what the batch removed is counted from what the store then holds.
        if (refused != null && !resync()) throw LogOutOfSync(path, refused)
        // ⚠ Always — when nothing above matched, and when a strike above failed: this is the retry
        // for content an earlier call struck but could not zero, and what was struck in THIS call
        // must not keep its bytes because a later path's strike threw.
        val owed = try {
            strikes.scrub()
            null
        } catch (cause: Throwable) {
            cause
        }
        // ⚠⚠ Both failures leave in the same object — see `DefaultFaceTemplateStore.forgetLocked`.
        Removal(
            removed = held.count { !holds(it) },
            refused = refused,
            erasureOwed = owed,
            recordsNotErased = strikes.notErased,
        )
    }

    override suspend fun entries(): List<Pair<String, FloatArray>> =
        lock.withLock { inStep { vectors.entries.map { it.key to it.value } } }

    override suspend fun rebase(producedBy: ModelProvenance) {
        lock.withLock { rebaseLocked(producedBy) }
    }

    override suspend fun clear() {
        lock.withLock {
            // ⚠⚠ **Emptied through a call that fails loudly, before memory is reset** — the face
            // store's rule. `deleteFile` reports nothing and is allowed to do nothing, so a wipe
            // that relied on it could return with every vector still on disk and the next start
            // would replay them. An emptied file carries no magic, which `load` rejects; and a write
            // that throws aborts the clear with memory re-read from the file ([replaceLog]).
            replaceLog(ByteArray(0))
            vectors.clear()
            withoutVector.clear()
            stamp = null
            strikes.reset()
            // ⚠ The FILE goes, header and all. Truncating to a header would leave the store owned by
            // a model whose vectors are gone, and the next `put` would be judged against it.
            // ⚠ Before the delete: the wipe has already happened, and a delete that throws must not
            // leave an offset pointing past the end of an empty file.
            appendOffset = 0
            outOfSync = false
            fileDatabase.deleteFile(path)
        }
    }

    /**
     * ⚠⚠ **One write, and it is the whole rebase.** [writeHeader] replaces the file's content with
     * the new header in a single call that fails loudly, so there is no moment at which the old
     * model's vectors sit under the new model's stamp. A `deleteFile` first would add a second step
     * and nothing else: it reports no refusal, and the replace does not need it.
     *
     * ⚠ Memory follows the file, not the other way round: it is emptied only once the write has
     * landed, and a write that throws re-reads the file ([replaceLog]) — the old log whole, or a
     * file too short to carry a header, which is an empty store with no model.
     */
    private fun rebaseLocked(producedBy: ModelProvenance) {
        writeHeader(producedBy)
        vectors.clear()
        withoutVector.clear()
        stamp = producedBy
        outOfSync = false
    }

    private fun writeHeader(producedBy: ModelProvenance) {
        val stampBytes = producedBy.asString().encodeToByteArray()
        val header = ByteArray(MAGIC.size + 2 + stampBytes.size)
        MAGIC.copyInto(header)
        writeShort(header, MAGIC.size, stampBytes.size)
        stampBytes.copyInto(header, MAGIC.size + 2)
        replaceLog(header)
        // ⚠ The file was just replaced, so every extent recorded against the old one is void — a
        // strike aimed at one would flip a byte in whatever is written there next.
        strikes.reset()
        appendOffset = header.size.toLong()
    }

    /**
     * Replaces the whole log with [content] — the header of a new log, or nothing at all for a wipe.
     *
     * ⚠⚠ **A replace that throws — or that returned and is not there when read back — may or may
     * not have emptied the file first**, and nothing here can tell which: the JVM and Android
     * truncate on open and then write. So memory is not patched to match a guess: [resync].
     */
    private fun replaceLog(content: ByteArray) {
        try {
            fileDatabase.replaceConfirmed(path, content)
        } catch (failure: Throwable) {
            resync()
            throw failure
        }
    }

    /** Appends [record] and returns the offset it was written at. */
    private fun append(record: ByteArray): Long {
        val at = appendOffset
        try {
            fileDatabase.appendConfirmed(path, at, record)
        } catch (failure: Throwable) {
            // ⚠⚠ Nothing, a prefix, or the whole record may be on disk — the last when it is the
            // confirming read that failed. Memory is rebuilt from the file, so a
            // record that did land is a row this store holds and can later strike.
            resync()
            throw failure
        }
        appendOffset += record.size
        return at
    }

    /**
     * A struck record as the scrub leaves it: the flag and both length fields untouched, the path
     * and the floats zero — so the replay can still step over it and nothing else can be read from it.
     */
    private fun blank(record: ByteArray): ByteArray {
        val out = ByteArray(record.size)
        out[0] = record[0]
        if (record.size < 3) return out
        record.copyInto(out, destinationOffset = 1, startIndex = 1, endIndex = 3)
        val widthAt = 3 + readShort(record, 1)
        val isPut = (record[0].toInt() and LogStrikes.STRUCK.inv()).toByte() == PUT
        if (isPut && widthAt + 2 <= record.size) {
            record.copyInto(out, destinationOffset = widthAt, startIndex = widthAt, endIndex = widthAt + 2)
        }
        return out
    }

    private fun encodePut(filePath: String, vector: FloatArray): ByteArray {
        val pathBytes = filePath.encodeToByteArray()
        val out = ByteArray(1 + 2 + pathBytes.size + 2 + vector.size * 4)
        out[0] = PUT
        writeShort(out, 1, pathBytes.size)
        pathBytes.copyInto(out, 3)
        var at = 3 + pathBytes.size
        writeShort(out, at, vector.size)
        at += 2
        vector.forEach { value ->
            writeInt(out, at, value.toRawBits())
            at += 4
        }
        return out
    }

    private fun encodePathOnly(flag: Byte, filePath: String): ByteArray {
        val pathBytes = filePath.encodeToByteArray()
        val out = ByteArray(1 + 2 + pathBytes.size)
        out[0] = flag
        writeShort(out, 1, pathBytes.size)
        pathBytes.copyInto(out, 3)
        return out
    }

    /**
     * Replays the log.
     *
     * ⚠⚠ Reads the whole file in one call and parses from a cursor. Every `readBytesRange` is a
     * syscall on some platforms, and a per-record read over 20 000 photos is 20 000 of them — the
     * kind of thing that is invisible in a test with four rows and adds seconds to a real launch.
     */
    private fun load() {
        // ⚠⚠ An ABSENT file is an empty store; a file that is there and will not read is not — see
        // `DefaultFaceTemplateStore.load`.
        val size = fileDatabase.fileSize(path) ?: return
        if (size <= 0L) return
        val bytes = fileDatabase.readBytesRange(path, 0, size.toInt())
        check(bytes != null && bytes.size.toLong() == size) { "the log at $path could not be read" }
        if (bytes.size < MAGIC.size + 2) return
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return

        val stampLength = readShort(bytes, MAGIC.size)
        var at = MAGIC.size + 2 + stampLength
        if (at > bytes.size) return
        stamp = ModelProvenance.parse(bytes.decodeToString(MAGIC.size + 2, at))

        while (at < bytes.size) {
            // ⚠ A struck record keeps its shape and loses its meaning: it is parsed for its
            // length exactly as the record it was, and then stepped over.
            val flag = (bytes[at].toInt() and LogStrikes.STRUCK.inv()).toByte()
            if (at + 3 > bytes.size) break
            val pathLength = readShort(bytes, at + 1)
            val pathEnd = at + 3 + pathLength
            if (pathEnd > bytes.size) break
            val filePath = bytes.decodeToString(at + 3, pathEnd)
            val struck = LogStrikes.isStruck(bytes, at, pathLength)
            when (flag) {
                REMOVE -> {
                    if (struck) {
                        strikes.noteStruck(at.toLong(), bytes.copyOfRange(at, pathEnd))
                    } else {
                        vectors.remove(filePath)
                        withoutVector.remove(filePath)
                        strikes.noteStanding(filePath, at.toLong(), pathEnd - at, flag)
                    }
                    at = pathEnd
                }
                VISITED -> {
                    if (struck) {
                        strikes.noteStruck(at.toLong(), bytes.copyOfRange(at, pathEnd))
                    } else {
                        vectors.remove(filePath)
                        withoutVector += filePath
                        strikes.noteStanding(filePath, at.toLong(), pathEnd - at, flag)
                    }
                    at = pathEnd
                }
                PUT -> {
                    if (pathEnd + 2 > bytes.size) break
                    val width = readShort(bytes, pathEnd)
                    val end = pathEnd + 2 + width * 4
                    // ⚠ The torn-tail case: a record that claims more bytes than the file holds is
                    // a pass that was killed mid-append.
                    if (end > bytes.size) break
                    if (struck) {
                        strikes.noteStruck(at.toLong(), bytes.copyOfRange(at, end))
                    } else {
                        val vector = FloatArray(width) { i ->
                            Float.fromBits(readInt(bytes, pathEnd + 2 + i * 4))
                        }
                        vectors[filePath] = vector
                        withoutVector.remove(filePath)
                        strikes.noteStanding(filePath, at.toLong(), end - at, flag)
                    }
                    at = end
                }
                // An unrecognised flag means the parse has lost alignment; anything after it is
                // guesswork, so stop rather than resynchronise onto plausible-looking noise.
                else -> break
            }
        }
        appendOffset = at.toLong()
    }

    private fun writeShort(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun readShort(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun writeInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value and 0xFF).toByte()
        out[at + 1] = ((value shr 8) and 0xFF).toByte()
        out[at + 2] = ((value shr 16) and 0xFF).toByte()
        out[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private companion object {
        /** ⚠ Versioned in the magic itself: a format change is a new magic, so an old file is not
         * parsed as a new one — it simply fails the check and is re-derived. */
        val MAGIC = "TSHUEMB1".encodeToByteArray()
        const val PUT: Byte = 1

        /** ⚠ Read, never written: a removal strikes the record now ([LogStrikes]). Older logs hold it. */
        const val REMOVE: Byte = 0

        /** Looked at, no vector — [markVisited]. ⚠ Additive: an older log never contains it. */
        const val VISITED: Byte = 2
    }
}
