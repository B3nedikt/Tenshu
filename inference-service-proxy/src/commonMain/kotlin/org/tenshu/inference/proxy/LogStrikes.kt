package org.tenshu.inference.proxy

import org.tenshu.core.common.proxy.FileDatabase

/**
 * **How a row leaves an append-only log: struck in place, never rewritten**
 * (`media-processing-pipeline-sweep.md` F0).
 *
 * ⚠⚠ **There is no second file, and that is the design.** Removing rows by writing a compacted
 * copy and swapping it in needs an atomic replace, and [FileDatabase] has none: `moveFile` deletes
 * its destination before it renames (JVM, Android, iOS), may fall back to a copy, and `deleteFile`
 * can fail without saying so. Every scheme built on that needs a recovery routine that infers which
 * step a crash interrupted from which files exist — and a leftover copy is a second place the
 * templates live, which a wipe can miss and a later open can bring back. So nothing here copies,
 * renames or deletes. A removal is two positional writes inside the one log:
 *
 *  1. **the strike** — one byte: the record's flag gains [STRUCK]. A struck record is parsed for
 *     its length and otherwise ignored, so this byte *is* the removal. One byte cannot be torn: after
 *     any crash the record is either the row it was or gone.
 *  2. **the scrub** — the record's content (path, geometry, vector) is overwritten with zeros, its
 *     length fields left exactly as they were. This is what takes the bytes off the disk. It changes
 *     nothing a reader can see, so a crash before, during or after it leaves the same log.
 *
 * ⚠⚠ **A live row is never written to.** Both writes land inside the extent of a record that is
 * being removed, so no crash and no I/O failure can cost a row that should stay — which is the
 * property a rewrite has to earn with staging and recovery, and here holds because the bytes are
 * never touched.
 *
 * ⚠ **Opening needs no recovery step.** The parse that reads the log is the whole of it: a struck
 * record is skipped, and one whose content is not yet zero is queued to be scrubbed again
 * ([noteStruck]). Both are read off the record itself; nothing is inferred from what else is on disk.
 *
 * ⚠ The cost: a removed row keeps its length in the file — as zeros — until the store is cleared or
 * rebased. Reclaiming that space is compaction — an optimisation, with no privacy duty riding on it,
 * and the thing that would need a real atomic replace in `FileDatabase`. It is deliberately absent.
 *
 * ⚠⚠ **What this relies on, and what it cannot promise.**
 *  - `writeBytesRange` must be a true positional write. It is on the JVM, Android and iOS
 *    (`RandomAccessFile` / `NSFileHandle`); the interface's default is a whole-file
 *    read-modify-write, which would make a one-byte strike a rewrite again.
 *  - "Off the disk" means the file's bytes were overwritten. What flash wear-levelling or a
 *    copy-on-write filesystem keeps of the old blocks is below anything an app can reach — exactly
 *    as it is for a deleted file.
 *  - [FileDatabase] has no `fsync`. A killed process loses nothing it wrote; a power cut can lose
 *    recent writes in any order. A lost strike leaves the row as it was, for the next reconcile; a
 *    scrub that outlived its strike is read as a removal ([isStruck]).
 *
 * Not thread-safe: the owning store calls it under its own lock.
 *
 * @param blank a struck record as it must finally read — same length, every length field as it
 *   was, all content zero. Only the owning store knows its record layout.
 */
internal class LogStrikes(
    private val fileDatabase: FileDatabase,
    private val path: String,
    private val blank: (record: ByteArray) -> ByteArray,
) {
    private class Extent(val offset: Long, val length: Int, val flag: Byte)

    /** Every record that still stands, per path, **in file order** — [strike] depends on the order. */
    private val standing = LinkedHashMap<String, MutableList<Extent>>()

    /** Struck records whose content is still on disk. */
    private val unscrubbed = ArrayList<Extent>()

    /** How many struck records still hold their content — the erasure this log owes. */
    val notErased: Int get() = unscrubbed.size

    /** Every path a standing record names — including one whose last record is a legacy `REMOVE`. */
    fun paths(): Set<String> = standing.keys

    fun reset() {
        standing.clear()
        unscrubbed.clear()
    }

    /** A record that stands, as the parse or an append found it. ⚠ Must be called in file order. */
    fun noteStanding(filePath: String, offset: Long, length: Int, flag: Byte) {
        standing.getOrPut(filePath) { ArrayList(1) } += Extent(offset, length, flag)
    }

    /**
     * A struck record the parse found. Queued for [scrub] when its content is still there — a
     * crash between the strike and the scrub, or a scrub that failed.
     */
    fun noteStruck(offset: Long, record: ByteArray) {
        if (!blank(record).contentEquals(record)) unscrubbed += Extent(offset, record.size, record[0])
    }

    /**
     * Strikes every record of [filePath]. **When this returns, the log no longer holds the row.**
     *
     * ⚠⚠ **Oldest first.** A path can have several records — a `PUT` a later `PUT` replaced — and
     * the replay takes the last one that stands. Struck in file order, the last one stands until
     * the final write, so a crash part-way leaves the row exactly as it was; struck newest first, a
     * crash would bring an *older* template back.
     *
     * ⚠⚠ **Read back, because a write that silently did nothing must not look like a removal.** The
     * caller forgets the row in memory on the strength of this call; were the byte not on disk, the
     * next open would bring the row back. Throws with that record still standing.
     */
    fun strike(filePath: String) {
        val records = standing[filePath] ?: return
        while (records.isNotEmpty()) {
            val record = records.first()
            val struck = (record.flag.toInt() or STRUCK).toByte()
            fileDatabase.writeBytesRange(path, record.offset, byteArrayOf(struck))
            val onDisk = fileDatabase.readBytesRange(path, record.offset, 1)
            check(onDisk != null && onDisk.size == 1 && onDisk[0] == struck) {
                "the strike at ${record.offset} of $path did not reach the file"
            }
            records.removeAt(0)
            unscrubbed += record
        }
        standing.remove(filePath)
    }

    /**
     * Zeroes the content of every struck record that still holds it, and throws if any could not be.
     *
     * ⚠ A failure changes nothing a read can see — the rows are already gone — but their bytes are
     * still on disk, so it is raised rather than swallowed. What failed stays queued: the next call
     * retries it, and so does the next open, which finds it by parsing. ⚠ "Failed" includes a write
     * that returned normally and left the bytes as they were: a record leaves the queue only once
     * the file has been read back as zeros.
     */
    fun scrub() {
        var failure: Throwable? = null
        val pending = unscrubbed.iterator()
        while (pending.hasNext()) {
            val record = pending.next()
            try {
                val bytes = fileDatabase.readBytesRange(path, record.offset, record.length)
                check(bytes != null && bytes.size == record.length) {
                    "the struck record at ${record.offset} of $path could not be read"
                }
                val zeroed = blank(bytes)
                // ⚠ From the second byte: the flag is the strike's, and is already on disk.
                fileDatabase.writeBytesRange(path, record.offset + 1, zeroed.copyOfRange(1, zeroed.size))
                // ⚠⚠ **Read back, for the strike's reason**. A write that returned
                // having done nothing would otherwise be dequeued here: the caller would be told the
                // bytes were erased, and nothing in this process would try again.
                val onDisk = fileDatabase.readBytesRange(path, record.offset, record.length)
                check(onDisk != null && onDisk.contentEquals(zeroed)) {
                    "the scrub at ${record.offset} of $path did not reach the file"
                }
                pending.remove()
            } catch (cause: Throwable) {
                if (failure == null) failure = cause
            }
        }
        failure?.let { throw it }
    }

    companion object {
        /**
         * The flag bit that strikes a record. ⚠ Additive, like the embedding log's `VISITED`: a build
         * that predates it stops replaying at the first struck record as it would at a torn tail. Only
         * a *downgrade* meets one, and what it loses is re-derivable.
         */
        const val STRUCK: Int = 0x40

        /**
         * Whether the record at [at] has been removed: its flag carries [STRUCK], **or its path
         * begins with a zero byte**.
         *
         * ⚠ The second half is for a machine that lost power, not for a process that died. The
         * strike is written before the scrub, and a killed process keeps that order. But
         * [FileDatabase] has no `fsync`, so after a power cut the OS may have persisted the scrub's
         * page and not the strike's — a record whose flag still stands over a path of zeros. No
         * path a store is given starts with a zero byte, so that record is read as what it was on
         * its way to being: removed. Read off the record itself, like the flag.
         */
        fun isStruck(bytes: ByteArray, at: Int, pathLength: Int): Boolean =
            bytes[at].toInt() and STRUCK != 0 || (pathLength > 0 && bytes[at + 3] == 0.toByte())
    }
}

/**
 * Appends [record] at [at] and confirms the file holds it, or throws.
 *
 * ⚠⚠ **"Returned normally" is not "persisted"**. Every write these logs make is
 * confirmed by something other than the call coming back, because a store that believes a write a
 * file never saw has memory and log disagreeing — and the next open sides with the log:
 *
 * | write | confirmed by |
 * |---|---|
 * | the strike | reading the flag byte back ([LogStrikes.strike]) |
 * | the scrub | reading the record back as zeros ([LogStrikes.scrub]) |
 * | an append | the file's length — this function |
 * | a header, a rebase, a wipe's emptying | the file's length and content (`replaceLog` in each store) |
 * | the face log's blanking of a torn tail | reading the tail back as zeros |
 * | a wipe's `deleteFile` | nothing, and nothing rides on it: an emptied file opens empty |
 *
 * ⚠ The length is enough for an append — one `stat`, not a read of the record — because the log
 * ended exactly at [at] before the write, so it is [record] longer afterwards only if every byte
 * landed. Where it did not end there (the vector log leaves a torn tail in place and writes over
 * it; the face log blanks one to zeros and writes over those), length proves nothing and the
 * record is read back instead.
 */
internal fun FileDatabase.appendConfirmed(path: String, at: Long, record: ByteArray) {
    val endedHere = (fileSize(path) ?: 0L) == at
    writeBytesRange(path, at, record)
    val landed = if (endedHere) {
        fileSize(path) == at + record.size
    } else {
        readBytesRange(path, at, record.size)?.contentEquals(record) == true
    }
    check(landed) { "the append at $at of $path did not reach the file" }
}

/**
 * Replaces the whole file at [path] with [content] and confirms it, or throws — see
 * [appendConfirmed] for why a normal return is not taken as proof.
 */
internal fun FileDatabase.replaceConfirmed(path: String, content: ByteArray) {
    writeRawByteArray(path, content)
    val landed = if (content.isEmpty()) {
        (fileSize(path) ?: 0L) == 0L
    } else {
        fileSize(path) == content.size.toLong() &&
            readBytesRange(path, 0, content.size)?.contentEquals(content) == true
    }
    check(landed) { "the log at $path was not replaced" }
}

/**
 * What a removal did — **all of it, in one object, so no failure path can drop a part**. A batch
 * can remove some rows, be refused others and fail to erase a third, in one call; an exception can
 * carry one of those and a count cannot carry any.
 *
 * @property removed how many of the paths asked for the store held before and no longer holds.
 * @property refused the first strike that did not land. Those rows are still in the store, in
 *   memory as in the log, for the next removal.
 * @property erasureOwed why bytes of struck records could not be confirmed zero. ⚠ The rows are
 *   gone from every read; this is the privacy half, and it survives alongside [refused].
 * @property recordsNotErased how many struck records still hold their content after this call —
 *   queued, and retried by every later removal and by the next open.
 */
class Removal(
    val removed: Int,
    val refused: Throwable? = null,
    val erasureOwed: Throwable? = null,
    val recordsNotErased: Int = 0,
) {
    /** Everything asked for is gone from the store and from the disk. */
    val complete: Boolean get() = refused == null && erasureOwed == null && recordsNotErased == 0

    override fun toString(): String = buildString {
        append("removed $removed row(s)")
        if (refused != null) append("; some were refused and remain: $refused")
        if (recordsNotErased > 0 || erasureOwed != null) {
            append("; $recordsNotErased removed record(s) still hold their bytes: $erasureOwed")
        }
    }
}

/** A single-path `remove` that was not [Removal.complete] — the whole [removal] rides along. */
class RemovalIncomplete(val removal: Removal) :
    RuntimeException(removal.toString(), removal.refused ?: removal.erasureOwed)

/**
 * The log could not be re-read after a write whose outcome is unknown, so the store does not know
 * what it holds — and says so rather than serving or extending a guess.
 *
 * ⚠⚠ **What a caller sees.** The call during which the re-read failed throws the failure that made
 * the re-read necessary (or this, for a removal). From then on every read and every write first
 * retries the re-read and throws this while it keeps failing; the first one that succeeds puts the
 * store back in step with its file and proceeds normally. `clear` — and `rebase` — are exempt:
 * they replace the file, so they need no knowledge of what was in it.
 */
class LogOutOfSync(path: String, cause: Throwable?) :
    IllegalStateException("the log at $path could not be re-read; the store will not act on a guess", cause)
