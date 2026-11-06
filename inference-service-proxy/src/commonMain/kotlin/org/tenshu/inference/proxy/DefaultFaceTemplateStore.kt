package org.tenshu.inference.proxy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.inference.ModelProvenance

/**
 * The durable [FaceTemplateStore] — `DefaultEmbeddingStore`'s shape.
 *
 * ⚠⚠ Device-local via [FileDatabase], and here that is not a storage preference but the protection
 * itself: these are **biometric** templates, and IN-D5 keeps them off the wire entirely
 * (`photos-people-and-pets.md` §3.1, §8). Nothing in this class touches the realm DB or the
 * preference service, because both persist to the *synced* realm.
 *
 * **Why a log rather than a slotted file** — the argument `DefaultEmbeddingStore` makes: clustering
 * reads every template anyway, so random access buys nothing and a log avoids the free-list and
 * compaction machinery deletions would otherwise need.
 *
 * ⚠⚠ **A torn tail is discarded, not an error.** An index pass runs for hours and can be killed at
 * any moment, so the last record is routinely half-written. Parsing stops at the first record that
 * does not fit; refusing to open would turn one interrupted pass into a store that never opens
 * again.
 *
 * ⚠ One record per **photograph**, holding all its faces — including **none**. A photo with no
 * faces is the majority case in a real library and must be recordable, or every pass re-reads the
 * whole library for ever ([FaceDetectionOutcome]).
 *
 * ⚠⚠ **A photograph is removed by striking its records in place, never by rewriting the file**
 * — [LogStrikes] is the argument. The log stays the only file this store ever writes, so
 * there is no copy of the templates for a crash to leave half-promoted and none for [clear] to miss.
 */
class DefaultFaceTemplateStore(
    private val fileDatabase: FileDatabase,
    private val path: String,
) : FaceTemplateStore {

    /** ⚠ Guards the map AND the append offset: two appends racing would interleave two records. */
    private val lock = Mutex()
    private val faces = LinkedHashMap<String, List<StoredFace>>()
    private var stamp: ModelProvenance? = null
    private var appendOffset: Long = 0

    /** ⚠ A torn tail `load` could not blank — see [finishAt]. No append may run over it. */
    private var tailNeedsBlanking = false

    /** Where each photograph's records sit, so a removal can strike and zero them in place. */
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
     * **The one recovery path**: throw memory away and rebuild it from the file.
     *
     * ⚠⚠ **The invariant every operation keeps:** after it returns *or throws*, memory is what a
     * reopen would parse, and every struck record whose bytes are not yet zero is queued. A write
     * whose outcome is not known — it threw, it tore, or the read that would confirm it failed —
     * is never patched around branch by branch: only the file knows what it holds, so the file is
     * read. This is the same pure function of the disk a process start runs.
     *
     * @return `false` when the re-read itself failed. The store is then [outOfSync]: it holds
     *   nothing, and [inStep] makes every later operation retry before it does anything.
     */
    private fun resync(): Boolean {
        fun forgetEverything() {
            faces.clear()
            stamp = null
            appendOffset = 0
            tailNeedsBlanking = false
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

    override suspend fun size(): Int = lock.withLock { inStep { faces.values.sumOf { it.size } } }

    override suspend fun put(
        filePath: String,
        faces: List<StoredFace>,
        producedBy: ModelProvenance,
    ): Boolean = lock.withLock {
        inStep { }
        val current = stamp
        if (current != null && current != producedBy) return@withLock false
        // ⚠⚠ Fall back to the BATCH's own first vector when the store holds none yet. Without that,
        // the very first non-empty write skips this check entirely, and `encodePut` sizes the record
        // from `held.first()` while writing each face's own width — so a longer later vector writes
        // past the array and takes the whole index pass down, and a shorter one leaves a malformed
        // trailing region that the replay then reads as garbage.
        val width = this.faces.values.firstNotNullOfOrNull { it.firstOrNull() }?.vector?.size
            ?: faces.firstOrNull()?.vector?.size
        if (width != null && faces.any { it.vector.size != width }) return@withLock false

        if (current == null) {
            // ⚠⚠ **Header first, stamp second, and the order is the whole point.** Setting `stamp`
            // before the write means a transient I/O failure inside `writeHeader` leaves the store
            // claiming a stamp it never persisted: the next `put` sees a non-null `current`, skips
            // the header, and appends a record at offset 0. That file has no magic, so the next
            // `load` rejects it outright and every template and visit recorded since is gone.
            // Assigning after the write leaves `stamp` null on failure, and a retry writes a
            // complete header.
            writeHeader(producedBy)
            stamp = producedBy
        }
        val record = encodePut(filePath, faces)
        strikes.noteStanding(filePath, append(record), record.size, PUT)
        this.faces[filePath] = faces
        true
    }

    override suspend fun facesOf(filePath: String): List<StoredFace> =
        lock.withLock { inStep { faces[filePath].orEmpty() } }

    override suspend fun rows(): List<FaceRow> = lock.withLock {
        inStep { }
        val current = stamp ?: return@withLock emptyList()
        faces.map { (path, held) -> FaceRow(path, held, current) }
    }

    override suspend fun visited(filePath: String): Boolean = lock.withLock { inStep { filePath in faces } }

    override suspend fun remove(filePath: String) {
        val removal = lock.withLock { forgetLocked(listOf(filePath)) }
        if (!removal.complete) throw RemovalIncomplete(removal)
    }

    override suspend fun remembered(): Set<String> = lock.withLock { inStep { faces.keys + strikes.paths() } }

    override suspend fun forget(paths: Collection<String>): Removal = lock.withLock { forgetLocked(paths) }

    /**
     * ⚠⚠ **Disk first, memory second, per photograph.** Dropping the row before the write means a
     * failed write leaves the log still holding the template while the map says it is gone — so a
     * retry does nothing, and the next reopen RESURRECTS it. For a biometric store that is a
     * deletion the user was told had happened and had not. [LogStrikes.strike] returns only once the
     * row is gone from the log, and throws with the row still present to retry.
     *
     * ⚠⚠ **No tombstone is appended.** A `REMOVE` record would hide a template from the replay and
     * leave its 512 floats in the file until a `clear`. A strike removes the row and the scrub that
     * follows takes the floats, the geometry and the path with it. Logs written before strikes
     * existed may hold `REMOVE` records, and they still replay.
     */
    private fun forgetLocked(paths: Collection<String>): Removal = inStep {
        val held = paths.filterTo(HashSet()) { it in faces }
        var refused: Throwable? = null
        for (filePath in paths) {
            try {
                strikes.strike(filePath)
                faces.remove(filePath)
            } catch (failure: Throwable) {
                // The others are still tried.
                if (refused == null) refused = failure
            }
        }
        // ⚠⚠ **A strike that failed may still have landed** — the write threw after the byte went
        // out, or the read-back that would confirm it failed. Memory cannot say which, so it is
        // rebuilt from the file ([resync]); what the batch removed is then counted from what the
        // store holds, not from which calls returned.
        if (refused != null && !resync()) throw LogOutOfSync(path, refused)
        // ⚠ Always — when nothing above matched, and when a strike above failed: this is the retry
        // for content an earlier call struck but could not zero, and what was struck in THIS call
        // must not keep its bytes because a later photograph's strike threw.
        val owed = try {
            strikes.scrub()
            null
        } catch (cause: Throwable) {
            cause
        }
        // ⚠⚠ Both failures leave in the same object. Reporting only the refusal would drop the
        // erasure debt of the rows that WERE removed — the half the threat model promises to report.
        Removal(
            removed = held.count { it !in faces },
            refused = refused,
            erasureOwed = owed,
            recordsNotErased = strikes.notErased,
        )
    }

    override suspend fun clear() {
        lock.withLock {
            // ⚠⚠ **Destroy the magic through a call that FAILS LOUDLY, before anything in memory is
            // reset.** `deleteFile` returns `Unit`, is allowed a no-op default, and the real
            // filesystem implementations do not report a refusal — so a wipe could return happily
            // with the file intact and the next `load` would replay every template it claimed to
            // remove. Overwriting the header first means even a silently-failed delete leaves a file
            // too short to carry the magic, which `load` rejects; and a write that throws aborts the
            // clear with memory re-read from the file rather than diverging from it ([replaceLog]).
            //
            // ⚠⚠ **And there is nothing else to wipe**. A removal strikes records inside this
            // file and never makes another, so no recovery copy can outlive a wipe and bring the
            // templates back — the worst outcome a biometric store has.
            replaceLog(ByteArray(0))
            faces.clear()
            stamp = null
            strikes.reset()
            // ⚠⚠ The FILE goes, header and all. Emptying the maps is not a clear: the log IS the
            // store, so the next process start replays it and every template comes back — and for a
            // biometric store surviving a realm reset, that is the defect §34.6 exists to prevent.
            // ⚠ Before the delete: the wipe has already happened, and a delete that throws must not
            // leave an offset pointing past the end of an empty file.
            appendOffset = 0
            tailNeedsBlanking = false
            outOfSync = false
            fileDatabase.deleteFile(path)
        }
    }

    // ---- the log ---------------------------------------------------------------------------------

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
            if (tailNeedsBlanking) dropStaleTail()
            fileDatabase.appendConfirmed(path, at, record)
        } catch (failure: Throwable) {
            // ⚠⚠ **A write that threw may have persisted nothing, a PREFIX, or the whole record** —
            // the last when it is the confirming read that failed. Each leaves a
            // different log, and the caller is about to be told the write failed. So memory is
            // rebuilt from the file ([resync]): a torn prefix is blanked or quarantined exactly as
            // one found at open is, and a record that did land is a row this store holds — which a
            // later `remove` can therefore strike, and which a reopen would have replayed anyway.
            resync()
            throw failure
        }
        appendOffset += record.size
        return at
    }

    /**
     * A struck record as the scrub leaves it: the flag and every length field untouched — path
     * length, face count, each vector's width — and the path, the geometry and the floats zero. The
     * replay can still step over it; nothing about a face can be read from it.
     */
    private fun blank(record: ByteArray): ByteArray {
        val out = ByteArray(record.size)
        out[0] = record[0]
        if (record.size < 3) return out
        record.copyInto(out, destinationOffset = 1, startIndex = 1, endIndex = 3)
        if ((record[0].toInt() and LogStrikes.STRUCK.inv()).toByte() != PUT) return out
        var at = 3 + readShort(record, 1)
        if (at + 2 > record.size) return out
        record.copyInto(out, destinationOffset = at, startIndex = at, endIndex = at + 2)
        val faceCount = readShort(record, at)
        at += 2
        repeat(faceCount) {
            val widthAt = at + FACE_GEOMETRY_BYTES
            if (widthAt + 2 > record.size) return out
            record.copyInto(out, destinationOffset = widthAt, startIndex = widthAt, endIndex = widthAt + 2)
            at = widthAt + 2 + readShort(record, widthAt) * 4
        }
        return out
    }

    private fun encodePut(filePath: String, held: List<StoredFace>): ByteArray {
        val pathBytes = filePath.encodeToByteArray()
        val vectorWidth = held.firstOrNull()?.vector?.size ?: 0
        val perFace = FACE_GEOMETRY_BYTES + 2 + vectorWidth * 4
        val out = ByteArray(1 + 2 + pathBytes.size + 2 + held.size * perFace)
        out[0] = PUT
        writeShort(out, 1, pathBytes.size)
        pathBytes.copyInto(out, 3)
        var at = 3 + pathBytes.size
        writeShort(out, at, held.size)
        at += 2
        held.forEach { face ->
            listOf(face.box.left, face.box.top, face.box.right, face.box.bottom, face.box.confidence)
                .forEach { value ->
                    writeInt(out, at, value.toRawBits())
                    at += 4
                }
            writeShort(out, at, face.vector.size)
            at += 2
            face.vector.forEach { value ->
                writeInt(out, at, value.toRawBits())
                at += 4
            }
        }
        return out
    }

    /**
     * Replays the log.
     *
     * ⚠⚠ Reads the whole file in one call and parses from a cursor, for `DefaultEmbeddingStore`'s
     * reason: a per-record read over twenty thousand photographs is twenty thousand syscalls, which
     * is invisible in a four-row test and seconds on a real launch.
     */
    private fun load() {
        // ⚠⚠ An ABSENT file is an empty store; a file that is there and will not read is not. Some
        // implementations answer a failed read with `null`, the same as "no such file" — so the
        // size is asked first, and a file of that size that comes back short or not at all throws.
        val size = fileDatabase.fileSize(path) ?: return
        if (size <= 0L) return
        val bytes = fileDatabase.readBytesRange(path, 0, size.toInt())
        check(bytes != null && bytes.size.toLong() == size) { "the log at $path could not be read" }
        if (bytes.size < MAGIC.size + 2) return
        if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return

        val stampLength = readShort(bytes, MAGIC.size)
        var at = MAGIC.size + 2 + stampLength
        if (at > bytes.size) return
        stamp = ModelProvenance.parse(bytes.decodeToString(MAGIC.size + 2, at)) ?: return

        while (at < bytes.size) {
            // ⚠ A struck record keeps its shape and loses its meaning: it is parsed for its
            // length exactly as the record it was, and then stepped over.
            val flag = (bytes[at].toInt() and LogStrikes.STRUCK.inv()).toByte()
            val afterFlag = at + 1
            // ⚠⚠ Through `finishAt`, NOT a bare `break`. These two are the tears that land inside a
            // record's HEADER — after the flag but before the length, or mid-path — and a `break`
            // sets the append offset while leaving those bytes on disk. A later, shorter record then
            // overwrites only their prefix and the remaining path bytes are parsed on the next
            // reopen. It is the same defect `finishAt` was added for, in the two branches that
            // reached the end of the loop instead of returning from it.
            if (afterFlag + 2 > bytes.size) return finishAt(at, bytes)
            val pathLength = readShort(bytes, afterFlag)
            val pathEnd = afterFlag + 2 + pathLength
            if (pathEnd > bytes.size) return finishAt(at, bytes)
            val filePath = bytes.decodeToString(afterFlag + 2, pathEnd)
            val struck = LogStrikes.isStruck(bytes, at, pathLength)

            when (flag) {
                REMOVE -> {
                    if (struck) {
                        strikes.noteStruck(at.toLong(), bytes.copyOfRange(at, pathEnd))
                    } else {
                        faces.remove(filePath)
                        strikes.noteStanding(filePath, at.toLong(), pathEnd - at, flag)
                    }
                    at = pathEnd
                }

                PUT -> {
                    if (pathEnd + 2 > bytes.size) return finishAt(at, bytes)
                    var cursor = pathEnd
                    val faceCount = readShort(bytes, cursor)
                    cursor += 2
                    val held = ArrayList<StoredFace>(faceCount)
                    var torn = false
                    repeat(faceCount) {
                        if (torn) return@repeat
                        if (cursor + FACE_GEOMETRY_BYTES + 2 > bytes.size) {
                            torn = true
                            return@repeat
                        }
                        val geometry = FloatArray(5) { index ->
                            Float.fromBits(readInt(bytes, cursor + index * 4))
                        }
                        cursor += FACE_GEOMETRY_BYTES
                        val width = readShort(bytes, cursor)
                        cursor += 2
                        if (cursor + width * 4 > bytes.size) {
                            torn = true
                            return@repeat
                        }
                        val vector = FloatArray(width) { index ->
                            Float.fromBits(readInt(bytes, cursor + index * 4))
                        }
                        cursor += width * 4
                        held += StoredFace(
                            box = FaceBox(geometry[0], geometry[1], geometry[2], geometry[3], geometry[4]),
                            vector = vector,
                        )
                    }
                    if (torn) return finishAt(at, bytes)
                    if (struck) {
                        strikes.noteStruck(at.toLong(), bytes.copyOfRange(at, cursor))
                    } else {
                        faces[filePath] = held
                        strikes.noteStanding(filePath, at.toLong(), cursor - at, flag)
                    }
                    at = cursor
                }

                else -> return finishAt(at, bytes)
            }
        }
        appendOffset = at.toLong()
    }

    /**
     * ⚠ The tail is where the kill landed; everything before it stands.
     *
     * ⚠⚠ **And the torn bytes are physically blanked, not just skipped.** Moving `appendOffset`
     * alone leaves them on disk, and `writeBytesRange` overwrites in place — so the next record, if
     * it is SHORTER than the one it lands on, leaves a stale suffix behind it. On the following
     * reopen that suffix is parsed as a record: arbitrary path bytes and vector floats can resemble
     * a valid `PUT` or `REMOVE`, and the store replays phantom state it was never told.
     *
     * ⚠⚠ **Blanked in place, with zeros — not truncated by rewriting the file.** `FileDatabase` has
     * no truncate, so writing the valid prefix back as a whole file would rewrite every live
     * template to drop a few torn bytes: a process killed inside it leaves half a log — and a cut
     * that falls between two records of one photograph brings the OLDER template back. Zeros do the
     * same job without touching a live row: a zero is not a record flag, so the replay stops at the
     * first one, and whatever a shorter record leaves behind it is more zeros. A tail that is
     * already all zeros is finished, and is left alone.
     */
    private fun finishAt(offset: Int, bytes: ByteArray) {
        appendOffset = offset.toLong()
        if ((offset until bytes.size).all { bytes[it] == 0.toByte() }) return
        // ⚠⚠ **Best-effort, because this runs from `init`.** Blanking makes `load` a writer, and an
        // unwritable disk would otherwise fail the CONSTRUCTOR — turning a store that could open
        // with a torn tail into one that cannot open at all. So a failure here is remembered rather
        // than raised: reads keep working.
        //
        // ⚠⚠⚠ **But an APPEND may not proceed over a tail that is still there.** Writing a record
        // shorter than the stale one it lands on leaves that record's suffix behind it, which the
        // next reopen parses — the phantom this exists to prevent, arriving by a slower road.
        // [append] retries the blanking first and refuses the write if it still cannot.
        tailNeedsBlanking = runCatching { blankTail(bytes.size.toLong()) }.isFailure
    }

    /**
     * Overwrites everything from [appendOffset] to [size] with zeros and reads it back, or throws.
     * ⚠ Nothing before [appendOffset] is written to, so no failure here can cost a live row.
     */
    private fun blankTail(size: Long) {
        val zeros = ByteArray((size - appendOffset).toInt())
        fileDatabase.writeBytesRange(path, appendOffset, zeros)
        val onDisk = fileDatabase.readBytesRange(path, appendOffset, zeros.size)
        check(onDisk != null && onDisk.contentEquals(zeros)) { "the torn tail of $path was not blanked" }
    }

    /**
     * ⚠ Blanks a tail [finishAt] could not, or throws.
     *
     * Throwing is the point: the caller is about to append, and appending over stale bytes is the
     * defect. A store on a permanently read-only disk stays readable and simply cannot be written
     * to, which is the honest outcome rather than a silently corrupted log.
     */
    private fun dropStaleTail() {
        val size = fileDatabase.fileSize(path) ?: 0L
        if (size > appendOffset) blankTail(size)
        tailNeedsBlanking = false
    }

    // ⚠⚠ LITTLE-endian, byte for byte with `DefaultEmbeddingStore`. Not a preference: these two logs
    // sit side by side in the same directory with headers of the same shape, so the MAGIC is what
    // must distinguish them — and it can only do that if nothing else accidentally does. Written
    // big-endian, the two formats would differ in a second, invisible way: a foreign log would fail
    // to parse on a wild length rather than on its magic, and a mutation deleting the magic check
    // would survive the whole suite because the byte order stood in for the guard.
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
        /** ⚠ Its own magic: a face log and an embedding log must never be read as each other. */
        val MAGIC = "TSHUFAC1".encodeToByteArray()
        const val PUT: Byte = 1

        /** ⚠ Read, never written: a removal strikes the record now ([LogStrikes]). Older logs hold it. */
        const val REMOVE: Byte = 2

        /** left, top, right, bottom, confidence. */
        const val FACE_GEOMETRY_BYTES = 5 * 4
    }
}
