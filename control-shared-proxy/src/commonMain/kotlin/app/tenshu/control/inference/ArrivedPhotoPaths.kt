package app.tenshu.control.inference

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.relation.relationReference
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.FileModification
import org.tenshu.core.file.FileService
import org.tenshu.core.file.MovedFileModification
import org.tenshu.core.file.RenamedFileModification
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.inference.proxy.PassStanding
import kotlin.concurrent.Volatile

/**
 * **Which photographs arrived** since the last read (IN-D6a, `ios-on-device-inference.md` §17): read
 * from the rows the file service's realm-wide modification log gained past a persisted [cursor],
 * never from the tree.
 *
 * ⚠⚠ Why the log. Every file write stamps a [FileModification] row and a peer's row syncs in with
 * its file, so a local upload and a file another device added are both a row here. The library
 * watch only says *that* something changed; this says *which paths*. A [CreatedFileModification],
 * [MovedFileModification] or [RenamedFileModification] for an image outside the trash is an arrival
 * (the inference stores are keyed by path, so a photograph at a new path is one no pass has seen) —
 * the same rows and the same test as Android's `NewArrivalsObserver`. ⚠ iOS cannot observe the save:
 * the upload or the sync that wrote the row ran in another app's process over the shared store.
 *
 * ⚠⚠ **Only rows past the cursor.** The cursor is the member hashes of the last [CURSOR_TAIL] rows read
 * and the newest one's index, kept on disk ([ArrivalsLogCursor]), so a fresh process queues only what
 * arrived since the last look — never the log's history, whose unindexed photographs would otherwise
 * fill the queue ahead of the upload the user is waiting for (the batch takes the queue oldest first,
 * 50 at a time).
 * - ⚠ **The newest remembered row still in the log** is where reading resumes: a move re-keys a file's
 *   history (its old rows reaped from the middle, new ones appended) and a file's 51st change trims its
 *   oldest, so the very last row read may be gone — what is past the newest survivor is new.
 * - **Rows inserted ahead of it** (a peer's rows merged in out of order: the survivor sits further down
 *   than it did) are not seen: the new rows are still queued, and [Look.missed].
 * - **No cursor yet** (the first look ever on this device, or after an erase), or **none of its rows
 *   left** (another realm's log, a long gap): the log's end becomes the cursor and **nothing is
 *   queued**; [Look.missed] — what came before is the wake's (PR-6).
 * - **More new rows than [window]** (a sync burst): the newest [window] are read, [Look.missed].
 *
 * Cost: one read of the log's member hashes (no deserialisation) and a window of the **new** rows only.
 * ⚠ The hashes and the window are two reads: a row appended between them is the next look's; a row
 * trimmed between them shifts the window by one and may lose a row (rare: a trim needs a file's 51st
 * modification).
 *
 * ⚠ Not thread-safe: one caller at a time (on iOS, `ForegroundArrivals`' one batch). What it returns
 * goes into the shared `ArrivalQueue`, which the batch drains.
 */
class ArrivedPhotoPaths(
    private val cursor: ArrivalsLogCursor,
    private val window: Int = ARRIVALS_LOG_WINDOW,
) {

    /** What one look found: [paths] arrived (newest last, de-duplicated); [missed] — some may not be in it. */
    data class Look(val paths: List<String>, val missed: Boolean)

    /**
     * With no cursor yet, marks the log's end as the cursor — reading its member hashes only, never a
     * row — so the first [read] after consent offers what arrived from now on. ⚠ No-op with a cursor.
     */
    fun markEndIfUnmarked(executionContext: ExecutionContext) {
        if (cursor.read() != null) return
        val log = executionContext.relationReference(FileService.Relations.MODIFICATIONS)
        // ⚠ An empty log is a cursor too — "from its start" — or the first upload is what the first look seeds past.
        // ⚠ A log never written to answers no hashes at all: the same start.
        cursor.write(endOf(executionContext.getArrayMemberHashes(log).orEmpty()))
    }

    /** ⚠ Store reads and a small file write: never on the main thread. */
    fun read(executionContext: ExecutionContext): Look {
        val log = executionContext.relationReference(FileService.Relations.MODIFICATIONS)
        // ⚠ A log never written to answers no hashes at all: an empty log.
        val hashes: List<Long> = executionContext.getArrayMemberHashes(log).orEmpty()
        val end: ArrivalsLogCursor.Position = endOf(hashes)
        val last: ArrivalsLogCursor.Position? = cursor.read()
        if (hashes.isEmpty()) {
            if (last == null) cursor.write(end)
            return Look(emptyList(), missed = false)
        }
        // The newest remembered row still in the log, and where it was then. ⚠ An empty tail is the
        // log's start: every row is past it.
        var at = -1
        var was = -1
        var found: Boolean = last != null && last.tail.isEmpty()
        if (last != null && !found) {
            for (back in last.tail.indices.reversed()) {
                val index: Int = hashes.lastIndexOf(last.tail[back])
                if (index >= 0) {
                    found = true
                    at = index
                    was = last.index - (last.tail.lastIndex - back)
                    break
                }
            }
        }
        // ⚠⚠ No cursor, or none of its rows left: nothing known about what lies past it — the wake's.
        if (!found) {
            cursor.write(end)
            return Look(emptyList(), missed = true)
        }
        // ⚠ Further down than it was: rows were merged in ahead of it, which this does not read.
        val insertedAhead: Boolean = at > was
        val fresh: Int = hashes.lastIndex - at
        if (fresh == 0) {
            if (insertedAhead) cursor.write(end)
            return Look(emptyList(), missed = insertedAhead)
        }
        val from: Int = maxOf(at + 1, hashes.size - window)
        val rows: List<Data> = executionContext.getArrayDataWindow(log, offset = from, limit = hashes.size - from)?.toList().orEmpty()
        val arrived = LinkedHashSet<String>()
        for (row in rows) {
            if (!isArrival(executionContext, row)) continue
            val path: String = fieldOf(row, FileModification.Field.FILE_ID) ?: continue
            if (isArrivingImagePath(path)) {
                arrived.remove(path)
                arrived += path
            }
        }
        cursor.write(end)
        return Look(arrived.toList(), missed = insertedAhead || fresh > window)
    }

    private fun endOf(hashes: List<Long>) =
        ArrivalsLogCursor.Position(tail = hashes.takeLast(CURSOR_TAIL), index = hashes.lastIndex)

    private fun isArrival(executionContext: ExecutionContext, row: Data): Boolean {
        val typeId: String = (row as? Entity<*>)?.type?.get() ?: row.id
        val schema = executionContext.schemaProvider.getSchema(typeId) as? CompositeSchema ?: return false
        return ARRIVING_KINDS.any { schema.isSubtypeOf(it) }
    }

    private fun fieldOf(row: Data, field: String): String? = runCatching {
        when (row) {
            is Entity<*> -> row.optionalFieldValue<TString>(field)?.get()
            is Composite -> row.getField<TString>(field).get()
            else -> null
        }
    }.getOrNull()

    private companion object {
        val ARRIVING_KINDS = listOf(CreatedFileModification.ID, MovedFileModification.ID, RenamedFileModification.ID)
    }
}

/**
 * Where [ArrivedPhotoPaths] last read the modification log to: a row's member hash and its index, so a
 * fresh process starts past what an earlier one looked at. ⚠ Device-local, beside the arrivals queue.
 * Not a path, and meaningless in another realm (the hash is not found there, which reads as *no cursor*).
 */
interface ArrivalsLogCursor {
    /** The member hashes of the last rows read, oldest first, and the newest one's [index]. */
    data class Position(val tail: List<Long>, val index: Int)

    fun read(): Position?
    fun write(position: Position)

    /** In memory: a test's, or a graph with no inference file store. */
    class InMemory(private var position: Position? = null) : ArrivalsLogCursor {
        override fun read(): Position? = position
        override fun write(position: Position) {
            this.position = position
        }
    }

    /** A line `index\th1,h2,…` in [files] at [path]. ⚠ An unreadable file is no cursor; a failed write is logged. */
    class InFile(private val files: FileDatabase, private val path: String = ARRIVALS_CURSOR_PATH) : ArrivalsLogCursor {
        override fun read(): Position? = runCatching {
            // ⚠ Not `trim()`: an empty tail ends the line in its tab.
            val text: String = files.readBytes(path)?.get()?.decodeToString()?.substringBefore('\n') ?: return null
            val tab: Int = text.indexOf('\t')
            val tail: String = text.substring(tab + 1)
            Position(tail = if (tail.isEmpty()) emptyList() else tail.split(',').map { it.toLong() }, index = text.substring(0, tab).toInt())
        }.getOrNull()

        override fun write(position: Position) {
            runCatching { files.writeRawByteArray(path, "${position.index}\t${position.tail.joinToString(",")}".encodeToByteArray()) }
                .onFailure { println("ArrivalsLogCursor -> write failed: $it") }
        }
    }
}

/**
 * A look said arrivals may have been missed ([ArrivedPhotoPaths.Look.missed]): until a library walk
 * that **started after it** completes, the wake has work (`PhotosOnDeviceAi.workLeft`). ⚠ In memory: a
 * fresh process arms the wake once anyway.
 */
class ArrivalsMissed {
    /** The newest pass number when the miss was recorded; a walk numbered past it answers it. */
    @Volatile
    var since: Long? = null
        private set

    fun record(standing: PassStanding) {
        since = when (standing) {
            is PassStanding.Running -> standing.pass
            is PassStanding.Idle -> standing.last?.pass ?: 0L
        }
    }
}

/** How many of the last rows read the cursor remembers, so a move or a trim of the very last one loses nothing. */
const val CURSOR_TAIL: Int = 32

/** [ArrivalsLogCursor.InFile]'s file, in the device-local inference store. */
const val ARRIVALS_CURSOR_PATH: String = "/inference/arrivals.cursor"

/**
 * At most how many new rows one look reads. ⚠ A placeholder: a sync burst of more new rows than this
 * between two looks leaves the oldest of them to the wake (a batch takes 50 anyway).
 */
const val ARRIVALS_LOG_WINDOW: Int = 500
