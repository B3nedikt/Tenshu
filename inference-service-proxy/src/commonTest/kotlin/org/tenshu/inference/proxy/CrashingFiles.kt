package org.tenshu.inference.proxy

import org.tenshu.core.TByteArray
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.InMemoryFileDatabase

/**
 * A [FileDatabase] that stops at one chosen mutating call — the double behind the crash-point table
 * in `LogStrikesCrashPointsTest`.
 *
 * ⚠⚠ **A kill is the death of the process, so nothing after it reaches the disk.** Once a `DIES_*`
 * kill has fired, every later call — read or write — throws [Killed]. That matters: a store that
 * catches the failure and tidies up would otherwise "recover" in the test with writes a dead
 * process never makes. What a reopen sees is [disk], exactly as the kill left it.
 *
 * ⚠ The `FAILS*` and `IGNORED` kills are the other half — an I/O error, or a call that silently
 * does nothing, with the process still running — where what must agree is the store's memory and
 * its file.
 */
internal class CrashingFiles(val disk: InMemoryFileDatabase = InMemoryFileDatabase()) : FileDatabase {

    enum class Kill {
        /** The process dies before the call changes anything. */
        DIES_BEFORE,

        /** The call lands whole, and the process dies before the next line runs. */
        DIES_AFTER,

        /** Half of the call's bytes land, and the process dies. */
        DIES_TORN,

        /** The call throws having changed nothing; the process lives. */
        FAILS,

        /** The call throws having written half its bytes; the process lives. */
        FAILS_TORN,

        /** The call returns normally having done nothing — what a refused delete looks like. */
        IGNORED,
    }

    /** What happens to one non-mutating call — `fileSize`, `readBytesRange`, `readBytes`. */
    enum class ReadFault {
        /** The read throws; the next one works. A confirmation that could not be read. */
        FAILS,

        /**
         * The read answers `null` — what Android's ranged read does on an `IOException`. ⚠ Not
         * applied to `fileSize`: there `null` MEANS "no such file", by the interface's contract,
         * and nothing can tell a failed `stat` from an absent file.
         */
        RETURNS_NULL,

        /** This read and every later one throws — so the re-read that recovery needs fails too. */
        FAILS_FROM_HERE,

        /** The process dies at the read. */
        DIES,
    }

    class Killed : RuntimeException("the process is dead")

    class IoFailure : RuntimeException("I/O error")

    /** One mutating call: which, where, and how many bytes. */
    data class Call(val name: String, val path: String, val offset: Long = 0, val length: Int = 0)

    /** Every mutating call since the last [arm] or [watch], in order. */
    val calls = mutableListOf<Call>()

    /** Every path a mutating call has ever named — a second file would show here. */
    val touched = mutableSetOf<String>()

    /** Whole-file reads since the last [arm] or [watch]. */
    var wholeReads = 0
        private set

    /** The longest ranged read since the last [arm] or [watch]. */
    var largestRead = 0
        private set

    /** Non-mutating calls of any kind since the last [arm] or [watch]. */
    var reads = 0
        private set

    /** Every non-mutating call throws — a file that cannot be read back. */
    var readsFail = false

    /** Ranged reads since the last [arm] or [watch] — what a read-back costs. */
    var rangeReads = 0
        private set

    /**
     * Every `deleteFile` returns normally having done nothing — what the real implementations do
     * when the filesystem refuses. ⚠ A standing condition rather than an armed call, so a test of
     * it does not depend on how many calls come before the delete.
     */
    var ignoreDeletes = false

    /** Every mutating call throws — a disk that cannot be written. */
    var refuseWrites = false

    var dead = false
        private set

    private var killAt = -1
    private var kill: Kill? = null
    private var thenWritesFail = false
    private var thenReadsFail = false
    private var readFaultAt = -1
    private var readFault: ReadFault? = null

    /**
     * The [call]th mutating call from now is stopped as [how] says.
     *
     * @param thenWritesFail every mutating call after it throws — a second failure inside the
     *   handling of the first (a scrub that fails after a strike failed).
     * @param thenReadsFail every read after it throws — the recovery re-read fails too.
     */
    fun arm(call: Int, how: Kill, thenWritesFail: Boolean = false, thenReadsFail: Boolean = false) {
        watch()
        killAt = call
        kill = how
        this.thenWritesFail = thenWritesFail
        this.thenReadsFail = thenReadsFail
    }

    /** The [call]th non-mutating call from now is stopped as [how] says. */
    fun armRead(call: Int, how: ReadFault) {
        watch()
        readFaultAt = call
        readFault = how
    }

    /** Stops nothing, heals every standing fault, and starts counting from here. */
    fun watch() {
        calls.clear()
        reads = 0
        wholeReads = 0
        largestRead = 0
        rangeReads = 0
        killAt = -1
        kill = null
        thenWritesFail = false
        thenReadsFail = false
        readFaultAt = -1
        readFault = null
        readsFail = false
        refuseWrites = false
        ignoreDeletes = false
    }

    private fun mutate(call: Call, apply: () -> Unit, torn: () -> Unit) {
        if (dead) throw Killed()
        if (refuseWrites) throw IoFailure()
        touched += call.path
        val index = calls.size
        calls += call
        if (index != killAt) return apply()
        if (thenWritesFail) refuseWrites = true
        if (thenReadsFail) readsFail = true
        when (kill!!) {
            Kill.DIES_BEFORE -> {
                dead = true
                throw Killed()
            }
            Kill.DIES_AFTER -> {
                apply()
                dead = true
                throw Killed()
            }
            Kill.DIES_TORN -> {
                torn()
                dead = true
                throw Killed()
            }
            Kill.FAILS -> throw IoFailure()
            Kill.FAILS_TORN -> {
                torn()
                throw IoFailure()
            }
            Kill.IGNORED -> Unit
        }
    }

    private fun <T> read(nullable: Boolean = true, block: () -> T?): T? {
        if (dead) throw Killed()
        if (readsFail) throw IoFailure()
        val index = reads++
        if (index == readFaultAt) {
            when (readFault!!) {
                ReadFault.FAILS -> throw IoFailure()
                ReadFault.RETURNS_NULL -> if (nullable) return null
                ReadFault.FAILS_FROM_HERE -> {
                    readsFail = true
                    throw IoFailure()
                }
                ReadFault.DIES -> {
                    dead = true
                    throw Killed()
                }
            }
        }
        return block()
    }

    override fun writeRawByteArray(filePath: String, bytes: ByteArray) = mutate(
        Call("raw", filePath, length = bytes.size),
        apply = { disk.writeRawByteArray(filePath, bytes) },
        // ⚠ What the JVM and Android do: the file is truncated when it is opened, then written.
        torn = { disk.writeRawByteArray(filePath, bytes.copyOf(bytes.size / 2)) },
    )

    override fun writeByteArray(filePath: String, bytes: TByteArray) = writeRawByteArray(filePath, bytes.get())

    override fun writeBytesRange(filePath: String, offset: Long, bytes: ByteArray) = mutate(
        Call("range", filePath, offset, bytes.size),
        apply = { disk.writeBytesRange(filePath, offset, bytes) },
        torn = { disk.writeBytesRange(filePath, offset, bytes.copyOf(bytes.size / 2)) },
    )

    override fun deleteFile(filePath: String) = mutate(
        Call("delete", filePath),
        apply = { if (!ignoreDeletes) disk.deleteFile(filePath) },
        torn = { },
    )

    override fun moveFile(fromPath: String, toPath: String) = mutate(
        Call("move", toPath),
        apply = { disk.moveFile(fromPath, toPath) },
        torn = { disk.deleteFile(toPath) },
    )

    override fun readBytes(filePath: String): TByteArray? = read {
        wholeReads++
        disk.readBytes(filePath)
    }

    override fun fileSize(filePath: String): Long? = read(nullable = false) { disk.fileSize(filePath) }

    override fun readBytesRange(filePath: String, offset: Long, length: Int): ByteArray? = read {
        largestRead = maxOf(largestRead, length)
        rangeReads++
        disk.readBytesRange(filePath, offset, length)
    }
}
