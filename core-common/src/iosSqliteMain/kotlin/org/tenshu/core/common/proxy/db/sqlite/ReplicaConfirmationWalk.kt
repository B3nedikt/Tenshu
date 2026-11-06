@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_ERROR
import platform.Foundation.NSRecursiveLock
import platform.posix.arc4random
import platform.posix.getpid
import platform.posix.usleep
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * One piece of the confirmation walk (Appendix B.8, (103)). The walk is these, in this order,
 * derived from [ReplicaSchema] so a table added there is walked without a second list:
 *
 * 1. [WHOLE]: `PRAGMA quick_check(1)` over the whole file, as the inline second reading did. A
 *    store that fits inside one run (all of a cached store, about 47,000 pages cold) is judged by
 *    exactly the check (46) chose, and the walk is over in one run.
 * 2. Only if that was stopped by `CONFIRM_MAX`, the **ranged** walk:
 *    - [TABLE] for every table but `objects`: `PRAGMA quick_check(<table>)`, the table's B-tree
 *      and its indexes, whole. These tables are small by construction (B.8.3 (104)).
 *    - [ROWS]: every row of `objects` in key order by keyset, each column read whole, so every
 *      leaf, interior and overflow page of the table is loaded, with `cell_size_check` on.
 *    - [INDEX] for each index: every entry in key order by keyset, a covering scan of its pages.
 *    - [SCHEMA]: `PRAGMA quick_check(sqlite_schema)`, the schema B-tree and the free list. Last,
 *      so the free list is the one the walk ends on.
 */
internal enum class ReplicaWalkUnitKind { WHOLE, TABLE, ROWS, INDEX, SCHEMA }

internal data class ReplicaWalkUnit(val kind: ReplicaWalkUnitKind, val name: String) {
    /** What the position file records, so a file written for another list of units is not believed. */
    val id: String get() = "${kind.name}:$name"

    companion object {
        /** The units of this layout, in walking order. */
        val all: List<ReplicaWalkUnit> by lazy {
            val units = ArrayList<ReplicaWalkUnit>()
            units += ReplicaWalkUnit(ReplicaWalkUnitKind.WHOLE, "file")
            ReplicaSchema.tables.filter { it.name != ReplicaSchema.OBJECTS }.forEach { units += ReplicaWalkUnit(ReplicaWalkUnitKind.TABLE, it.name) }
            units += ReplicaWalkUnit(ReplicaWalkUnitKind.ROWS, ReplicaSchema.OBJECTS)
            ReplicaSchema.indexes.forEach { index ->
                // The keyset below is (one TEXT column, the rowid): the one index this layout has.
                // A second shape needs its own keyset, and this says so before it walks wrongly.
                check(index.table == ReplicaSchema.OBJECTS && index.columns.size == 1) { "[walk] no keyset for index ${index.name}" }
                units += ReplicaWalkUnit(ReplicaWalkUnitKind.INDEX, index.name)
            }
            units += ReplicaWalkUnit(ReplicaWalkUnitKind.SCHEMA, "sqlite_schema")
            units
        }

        /** The whole list's fingerprint, in the position file: a file written for another list is no position. */
        val fingerprint: String by lazy { fnv64(all.joinToString("\n") { it.id }.encodeToByteArray()).toULong().toString(16) }
    }
}

/**
 * The storage class of an index entry's key, in SQLite's sort order: NULL, then the numbers
 * (INTEGER and REAL compared by value), then TEXT (BINARY), then BLOB (`sqlite3VdbeRecordCompare`
 * and `sqlite3MemCompare`, SQLite 3.53.4). An index position carries its entry's class, so a walk
 * can resume inside any class (B.9 (122)).
 */
internal enum class ReplicaWalkKeyClass(val sqlName: String) {
    NULL("null"),
    INTEGER("integer"),
    REAL("real"),
    TEXT("text"),
    BLOB("blob"),
    ;

    companion object {
        /** The class `typeof()` names, or null for a name SQLite does not give. */
        fun of(typeofName: String): ReplicaWalkKeyClass? = entries.firstOrNull { it.sqlName == typeofName }

        /** Whether [text] is a key of [keyClass] as [ReplicaWalkPosition.afterType] keeps it. */
        fun isKeyText(keyClass: ReplicaWalkKeyClass, text: String): Boolean = when (keyClass) {
            NULL -> text.isEmpty()
            INTEGER -> text.toLongOrNull() != null
            REAL -> text.length == 16 && bytesOfHex(text) != null
            TEXT, BLOB -> bytesOfHex(text) != null
        }
    }
}

/**
 * An index entry, its key and its rowid, **compared in the index's own order** (B.10 (133)):
 * NULL, then INTEGER and REAL by value across both, then TEXT by its bytes (BINARY: `memcmp`, then
 * the length), then BLOB the same way; equal keys by rowid (`sqlite3VdbeRecordCompare`,
 * `sqlite3MemCompare`, `sqlite3IntFloatCompare`, SQLite 3.53.4). What lets [walkIndex] step over,
 * in Kotlin, every entry at or before its resume point whatever the statement returned, and
 * [ReplicaWalkPosition.isPast] tell progress from a step back.
 */
internal class ReplicaIndexKey(
    val keyClass: ReplicaWalkKeyClass,
    private val integer: Long,
    private val real: Double,
    private val bytes: ByteArray?,
    val rowid: Long,
) : Comparable<ReplicaIndexKey> {
    override fun compareTo(other: ReplicaIndexKey): Int {
        val byKey = compareKeys(other)
        return if (byKey != 0) byKey else rowid.compareTo(other.rowid)
    }

    private fun compareKeys(other: ReplicaIndexKey): Int {
        val byClass = rank(keyClass).compareTo(rank(other.keyClass))
        if (byClass != 0) return byClass
        return when (keyClass) {
            ReplicaWalkKeyClass.NULL -> 0
            ReplicaWalkKeyClass.INTEGER -> when (other.keyClass) {
                ReplicaWalkKeyClass.INTEGER -> integer.compareTo(other.integer)
                else -> integerAgainstReal(integer, other.real)
            }
            ReplicaWalkKeyClass.REAL -> when (other.keyClass) {
                ReplicaWalkKeyClass.INTEGER -> -integerAgainstReal(other.integer, real)
                // ⚠⚠ Not `compareTo`: Kotlin's is a total order (-0.0 before 0.0, NaN last), and
                // SQLite's (`sqlite3MemCompare`) calls -0.0 and 0.0 equal, leaving the rowid to
                // decide. Read by `compareTo`, (-0.0, 7) is "before" (0.0, 5) and a resume there
                // would step over it (B.10 (139)). SQLite stores no NaN: it becomes NULL.
                else -> if (real < other.real) -1 else if (real > other.real) 1 else 0
            }
            ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> compareBytes(bytes!!, other.bytes!!)
        }
    }

    /** The key as [ReplicaWalkPosition.afterType] keeps it, for its class. */
    val keyText: String
        get() = when (keyClass) {
            ReplicaWalkKeyClass.NULL -> ""
            ReplicaWalkKeyClass.INTEGER -> integer.toString()
            ReplicaWalkKeyClass.REAL -> real.toRawBits().toULong().toString(16).padStart(16, '0')
            ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> hexOf(bytes!!)
        }

    companion object {
        private fun rank(keyClass: ReplicaWalkKeyClass): Int = when (keyClass) {
            ReplicaWalkKeyClass.NULL -> 0
            ReplicaWalkKeyClass.INTEGER, ReplicaWalkKeyClass.REAL -> 1
            ReplicaWalkKeyClass.TEXT -> 2
            ReplicaWalkKeyClass.BLOB -> 3
        }

        /** [i] against [r], exactly (no rounding of a large integer to a double): `sqlite3IntFloatCompare`. */
        fun integerAgainstReal(i: Long, r: Double): Int {
            if (r < -9.223372036854775808E18) return 1
            if (r >= 9.223372036854775808E18) return -1
            // In range, so the truncation and its double are both exact.
            val t = r.toLong()
            if (i != t) return i.compareTo(t)
            val fraction = r - t.toDouble()
            return if (fraction > 0) -1 else if (fraction < 0) 1 else 0
        }

        /** Unsigned bytes, then the length: BINARY collation for TEXT, and a BLOB's own order. */
        fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val byByte = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
                if (byByte != 0) return byByte
            }
            return a.size.compareTo(b.size)
        }

        fun of(keyClass: ReplicaWalkKeyClass, integer: Long = 0, real: Double = 0.0, bytes: ByteArray? = null, rowid: Long): ReplicaIndexKey =
            ReplicaIndexKey(keyClass, integer, real, bytes, rowid)

        /** The entry a position names, or null when it names none (no key, or one that does not decode in its class). */
        fun of(position: ReplicaWalkPosition): ReplicaIndexKey? {
            val keyClass = position.afterClass ?: return null
            val key = position.afterType ?: return null
            val rowid = position.afterKey ?: return null
            return when (keyClass) {
                ReplicaWalkKeyClass.NULL -> of(keyClass, rowid = rowid)
                ReplicaWalkKeyClass.INTEGER -> key.toLongOrNull()?.let { of(keyClass, integer = it, rowid = rowid) }
                ReplicaWalkKeyClass.REAL -> bytesOfHex(key)?.takeIf { it.size == 8 }?.let { bits ->
                    of(keyClass, real = Double.fromBits(bits.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }), rowid = rowid)
                }
                ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> bytesOfHex(key)?.let { of(keyClass, bytes = it, rowid = rowid) }
            }
        }

        /** The entry [statement] holds: its key in [column], of [keyClass], and [rowid]. */
        fun read(statement: SqliteStatement, column: Int, keyClass: ReplicaWalkKeyClass, rowid: Long): ReplicaIndexKey = when (keyClass) {
            ReplicaWalkKeyClass.NULL -> of(keyClass, rowid = rowid)
            ReplicaWalkKeyClass.INTEGER -> of(keyClass, integer = statement.getLong(column), rowid = rowid)
            ReplicaWalkKeyClass.REAL -> of(keyClass, real = statement.getDouble(column), rowid = rowid)
            // The raw bytes: `sqlite3_column_blob` of a TEXT value hands over its stored UTF-8 as it is.
            ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> of(keyClass, bytes = statement.getBlob(column), rowid = rowid)
        }
    }
}

/**
 * Where a walk is (B.8.3 (102)).
 * - [epoch] is `meta.epoch` and [file] the database file's `dev:ino`, as the walk last read them.
 *   Either one different, or either unknown (null), on either side, and the walk starts over: the
 *   store was wiped, migrated or replaced (an older copy restored with the same epoch has
 *   another inode).
 * - [unit] indexes [ReplicaWalkUnit.all].
 * - [afterKey] is the last rowid a keyset unit read whole; for an index, [afterClass] is that
 *   entry's storage class and [afterType] its `type` in that class: TEXT and BLOB as the **raw
 *   bytes SQLite stored**, hex-encoded (never decoded and re-encoded, (112)); INTEGER in decimal;
 *   REAL as the hex of its IEEE bits; NULL as the empty string (B.9 (122)). All null: that unit
 *   has not begun.
 * - [runs] counts the runs that read.
 */
internal data class ReplicaWalkPosition(
    val epoch: Long?,
    val file: String?,
    val unit: Int,
    val afterKey: Long?,
    val afterType: String?,
    val runs: Int,
    val error: String,
    val afterClass: ReplicaWalkKeyClass? = if (afterType != null) ReplicaWalkKeyClass.TEXT else null,
) {
    /**
     * Strictly further than [other] on the same store: what "a run advanced" means. ⚠⚠ **In the
     * unit's own order** (B.10 (133)): a position behind [other] is not progress, whatever else
     * changed. A run that saved one could otherwise go back and forth between two positions with
     * every run "advancing", and the stall rule (115) would never stop it. [walkIndex] never
     * reports a key behind its start; this does not rely on that.
     */
    fun isPast(other: ReplicaWalkPosition): Boolean = when {
        unit != other.unit -> unit > other.unit
        other.afterKey == null -> afterKey != null
        afterKey == null -> false
        afterClass == null && other.afterClass == null -> afterKey > other.afterKey
        else -> {
            val mine = ReplicaIndexKey.of(this)
            val theirs = ReplicaIndexKey.of(other)
            // A key that does not decode never passes the decoder ([decode]); unordered, it is no progress.
            mine != null && theirs != null && mine > theirs
        }
    }

    /**
     * The start of unit [next], every key cleared. ⚠ The class too (B.9 (130)): a class left on a
     * unit with no key is a position that does not decode, which after a process death sends the
     * walk back to `WHOLE`.
     */
    fun atUnitStart(next: Int): ReplicaWalkPosition = copy(unit = next, afterKey = null, afterType = null, afterClass = null)

    /** True when [epoch] and [file] are both known and both equal [other]'s: the same store. */
    fun isSameStoreAs(epoch: Long?, file: String?): Boolean =
        this.epoch != null && this.file != null && this.epoch == epoch && this.file == file

    fun encode(appName: String): ByteArray {
        val body = buildString {
            append("format=").append(FORMAT).append('\n')
            append("app=").append(hexOf(appName.encodeToByteArray())).append('\n')
            append("layout=").append(ReplicaSchema.LAYOUT).append('\n')
            append("units=").append(ReplicaWalkUnit.fingerprint).append('\n')
            append("epoch=").append(epoch?.toString() ?: NONE).append('\n')
            append("file=").append(file?.let { hexOf(it.encodeToByteArray()) } ?: NONE).append('\n')
            append("unit=").append(unit).append('\n')
            append("unitId=").append(hexOf(ReplicaWalkUnit.all[unit].id.encodeToByteArray())).append('\n')
            append("afterKey=").append(afterKey?.toString() ?: NONE).append('\n')
            append("afterClass=").append(afterClass?.sqlName ?: NONE).append('\n')
            append("afterType=").append(afterType ?: NONE).append('\n')
            append("runs=").append(runs).append('\n')
            append("error=").append(hexOf(error.encodeToByteArray())).append('\n')
        }
        return (body + "check=" + fnv64(body.encodeToByteArray()).toULong().toString(16) + "\n").encodeToByteArray()
    }

    companion object {
        /** 3 since the index key carries its storage class (B.9 (122)): a format-2 file is no position, and its walk starts over. */
        const val FORMAT = 3
        private const val NONE = "-"

        /**
         * ⚠⚠ **Null for anything but a whole, checksummed position of this app, this layout and
         * this list of units.** A torn write, a file of another format, another app's file, a
         * unit index past the list or naming another unit: each is "no position", and the walk
         * starts from its beginning. Nothing here can make unread pages count as read: the worst
         * a bad file does is cost the runs it would have saved.
         */
        fun decode(bytes: ByteArray, appName: String): ReplicaWalkPosition? {
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return null
            }
            val checkAt = text.lastIndexOf("check=")
            if (checkAt < 0 || !text.endsWith("\n")) return null
            val body = text.substring(0, checkAt)
            if (text.substring(checkAt + "check=".length, text.length - 1) != fnv64(body.encodeToByteArray()).toULong().toString(16)) return null
            val fields = HashMap<String, String>()
            for (line in body.split('\n')) {
                if (line.isEmpty()) continue
                val at = line.indexOf('=')
                if (at <= 0 || fields.put(line.substring(0, at), line.substring(at + 1)) != null) return null
            }
            if (fields["format"] != FORMAT.toString()) return null
            if (fields["app"]?.let(::utf8OfHex) != appName) return null
            if (fields["layout"] != ReplicaSchema.LAYOUT.toString()) return null
            if (fields["units"] != ReplicaWalkUnit.fingerprint) return null
            val unit = fields["unit"]?.toIntOrNull() ?: return null
            if (unit !in ReplicaWalkUnit.all.indices || fields["unitId"]?.let(::utf8OfHex) != ReplicaWalkUnit.all[unit].id) return null
            fun optionalLong(name: String): Result<Long?> = when (val value = fields[name]) {
                null -> Result.failure(IllegalArgumentException(name))
                NONE -> Result.success(null)
                else -> value.toLongOrNull()?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException(name))
            }
            val epoch = optionalLong("epoch").getOrElse { return null }
            val file = when (val value = fields["file"]) {
                null -> return null
                NONE -> null
                else -> utf8OfHex(value) ?: return null
            }
            val afterKey = optionalLong("afterKey").getOrElse { return null }
            val afterClass = when (val value = fields["afterClass"]) {
                null -> return null
                NONE -> null
                else -> ReplicaWalkKeyClass.of(value) ?: return null
            }
            val afterType = when (val value = fields["afterType"]) {
                null -> return null
                NONE -> null
                else -> value
            }
            // A keyset unit's type key is set exactly when its integer key is, and only for an
            // index; its class exactly with it, and the key well formed for that class.
            val kind = ReplicaWalkUnit.all[unit].kind
            if ((afterClass == null) != (afterType == null)) return null
            if (afterClass != null && !ReplicaWalkKeyClass.isKeyText(afterClass, afterType!!)) return null
            if (afterType != null && (afterKey == null || kind != ReplicaWalkUnitKind.INDEX)) return null
            if (afterKey != null && kind != ReplicaWalkUnitKind.ROWS && kind != ReplicaWalkUnitKind.INDEX) return null
            if (kind == ReplicaWalkUnitKind.INDEX && afterKey != null && afterType == null) return null
            val runs = fields["runs"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val error = fields["error"]?.let(::utf8OfHex) ?: return null
            return ReplicaWalkPosition(epoch, file, unit, afterKey, afterType, runs, error, afterClass)
        }
    }
}

/** Lowercase hex of [bytes]. */
internal fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The bytes of lowercase or uppercase hex, or null for anything else. */
internal fun bytesOfHex(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    val bytes = ByteArray(text.length / 2)
    for (i in bytes.indices) bytes[i] = text.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
    return bytes
}

private fun utf8OfHex(text: String): String? {
    val bytes = bytesOfHex(text) ?: return null
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

/** FNV-1a, 64 bits: a torn or bit-flipped file, not an adversary (the file is the store's own). */
internal fun fnv64(bytes: ByteArray): Long {
    var hash = -0x340d631b7bdddcdbL
    for (b in bytes) {
        hash = hash xor (b.toLong() and 0xff)
        hash *= 0x100000001b3L
    }
    return hash
}

/** How one run of the walk ended ([ReplicaConfirmationWalk.run]). */
internal sealed interface ReplicaWalkOutcome {
    /** No walk is owed by this app: nothing was requested in this process and no position is on disk. */
    data object NothingPending : ReplicaWalkOutcome

    /** Not run now, nothing read and nothing written; still owed. */
    data class Deferred(val reason: String) : ReplicaWalkOutcome

    /**
     * The run read until its bound and the walk is not over. [advanced]: it got further than it
     * started. [saved]: the position it reached is on disk (else the next process starts from the
     * last one that is, which is behind, never ahead).
     */
    data class Progressed(val position: ReplicaWalkPosition, val advanced: Boolean, val saved: Boolean) : ReplicaWalkOutcome

    /** `replica/CORRUPT` is durable: this walk found damage and wrote it, or found a sibling's. The walk is over. */
    data class Confirmed(val detail: String) : ReplicaWalkOutcome

    /** Every unit was read without a finding. The walk is over and owed no more: the fence may be lifted. */
    data class Clean(val runs: Int) : ReplicaWalkOutcome

    /** The run read and could not reach a verdict it may act on (an I/O error, the phone locked, the marker unwritable). Still owed. */
    data class NotMade(val reason: String, val cause: Throwable? = null) : ReplicaWalkOutcome

    /**
     * ⚠ **The walk cannot advance** (B.8.3 (115)): [ReplicaConfirmationWalk.STALL_RUNS] runs in a
     * row ended where they began. **No verdict:** no marker, the fence holds, and the surface says
     * the store could not be checked. A run while stalled reads only the store's epoch and file:
     * it lifts the stall when either changed (a sibling's wipe or replacement), as does a new
     * hand-off ((119)); the next launch tries again too. An open reschedules it with a growing
     * interval ([ReplicaConfirmationWalk.OPEN_RESCHEDULE_MAX_MS], B.9 (125)).
     */
    data class Stalled(val position: ReplicaWalkPosition, val runs: Int, val reason: String) : ReplicaWalkOutcome

    /**
     * ⚠ **The store is not one this build can judge** (B.9 (123)): its `meta.layout` is not this
     * build's (newer, older, or unreadable), so neither the epoch's rule nor the unit list applies
     * to it. **No verdict and the walk is over:** no marker, the position removed, and this app's
     * fence lifted, so the next open judges the store by its own rules, which refuse a layout they
     * do not know (`SKEW`, `OlderLayout`, or damage for no layout at all) and write nothing.
     */
    data class NoVerdict(val reason: String) : ReplicaWalkOutcome
}

/** One run's stop request (a token's expiry), made before the run exists and never lost to it ((117)). */
internal class ReplicaWalkStop {
    @Volatile
    var asked: Boolean = false
        private set

    fun ask() {
        asked = true
    }
}

/**
 * ⚠⚠ **The confirmation of a corruption code, as a resumable walk** (Q5; Appendix B.8).
 * The access, open or job that met a code does not read the store a second time: it is fenced
 * and **hands off** here ([request]); the reading is this walk, run by the worker
 * ([ReplicaWorker]) in runs of at most `CONFIRM_MAX`, under a job token of its own, carrying its
 * position across runs, wakes and process deaths in `replica/confirm-walk.<hex of the app's
 * name>`. It ends in one of two verdicts: damage, and `replica/CORRUPT` written (only while
 * protected data is available); or every unit read without a finding, and the walk is over (the
 * fence may be lifted). ⚠ A checkpoint's code never comes here ((113)): (52) is its whole handling.
 *
 * **Until it ends, this app's opens are refused** with [StoreBeingChecked] (the fence held across
 * runs and wakes, a cost Q5 accepts). Siblings are not held back by it: only the marker fences
 * another process.
 *
 * One per registry and app ([ReplicaHandles.confirmationWalk]). Never run on the main thread in
 * production: [run] holds the registry's lock for up to `CONFIRM_MAX`.
 */
internal class ReplicaConfirmationWalk(
    private val registry: ReplicaHandles,
    private val files: ReplicaFiles,
    val appName: String,
    /** What the open's own rule judges the store against, before any unit (Q16, Appendix B.10). */
    private val build: ReplicaBuild,
    private val protectedDataAvailable: () -> Boolean,
) {
    private val directory = registry.databasePath.substringBeforeLast('/')

    /** The store this walk reads, for the host's outcome (Appendix B.13). */
    internal val databasePath: String get() = registry.databasePath
    private val marker = ReplicaCorruptMarker(files, directory)
    private val limits: ReplicaLimits get() = registry.limits

    /** `replica/confirm-walk.<hex of the app's name>`: no two names map to one file ((114)). */
    val positionPath: String = "$directory/$POSITION_PREFIX" + hexOf(appName.encodeToByteArray())

    private val lock = NSRecursiveLock()

    /** Requested in this process and not over. Read and written under [lock]. */
    private var requested = false
    private var requestedError: String? = null

    /** Moves at every [request]: a run ends only the requests it started under ((116)). */
    private var requestSerial = 0L

    /** The furthest position this process knows, or null (none, or the walk is over). */
    private var position: ReplicaWalkPosition? = null
    private var running = false

    /** The bytes of a position file this process ended on and could not remove: not owed, by this process ((114)). */
    private var endedBytes: ByteArray? = null

    /** Runs in a row that ended where they began ((115)); [stalled] once it reaches [STALL_RUNS]. */
    private var stalls = 0
    private var stalled = false

    /** The store (epoch, file) the walk stalled on, and why ((119)). */
    private var stalledOn: Pair<Long?, String?>? = null
    private var stallReason: String? = null

    /** A position file this process ended on that could not even be read, nor removed ((120)). */
    private var endedUnreadable = false

    /** An open's schedule suppressed by the rate limit is owed, and dispatched when the window ends ((115)). */
    private var openScheduleOwed = false

    /** Schedules an open has dispatched while this walk is stalled: the window doubles with each (B.9 (125)). */
    private var stalledOpenSchedules = 0

    /** Runs a block once after a delay, off the caller's thread. A test runs them by hand. */
    internal var after: (Long, () -> Unit) -> Unit = { delayMs, block -> DispatchProtectionTimer.after(delayMs, block) }
    /** When an open last scheduled this walk, monotonic ms; null for never ((115)). */
    private var lastOpenScheduleMs: Long? = null

    /** Monotonic ms, for the open's reschedule rate. A test moves it by hand. */
    internal var nowMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }

    /** Test seam: called with the position just before it is written. Null in production. */
    internal var beforeSave: ((ReplicaWalkPosition) -> Unit)? = null

    /** Test seam: called as each unit begins, inside the reading. Null in production. */
    internal var atUnit: ((ReplicaWalkUnit) -> Unit)? = null

    /** Test seam: called with every position the reading reaches. Null in production. */
    internal var onReached: ((ReplicaWalkPosition) -> Unit)? = null

    /**
     * Test seam: rewrites each index statement before it is prepared, so a test can force a plan
     * that evaluates the resume's comparison as a filter (B.10 (133)). Null in production.
     */
    internal var indexStatement: ((String) -> String)? = null

    /**
     * A code was met and handed here. In memory only, no I/O: allowed with the gate closed and
     * under the registry's lock. Nothing durable records it until the first run reads: a process
     * killed before then leaves nothing, and the next open judges the store itself.
     */
    fun request(error: String) {
        locked {
            requested = true
            requestSerial++
            if (requestedError == null) requestedError = error
            // A new code is new evidence: a stall in this process does not outlive it ((119)).
            clearStall()
        }
        registry.onWalkRequested?.invoke(this)
    }

    /**
     * True while a walk is owed: requested in this process, a position known here, stalled here,
     * or a position on disk that is not the one this process ended on and could not remove. ⚠ The
     * same test [startingPosition] makes, so the two never disagree ((114)). File work when
     * nothing is owed in memory.
     */
    fun isPending(): Boolean = locked { requested || position != null || stalled } || owedOnDisk() != null

    /** True once this process gave up on the walk ([ReplicaWalkOutcome.Stalled]). */
    val isStalled: Boolean get() = locked { stalled }

    /** Under [lock]. */
    private fun clearStall() {
        stalled = false
        stalls = 0
        stalledOn = null
        stallReason = null
        stalledOpenSchedules = 0
    }

    /**
     * Under [lock]. The least time between two runs an open schedules: [OPEN_RESCHEDULE_MS], and
     * while the walk is stalled that doubled per schedule up to [OPEN_RESCHEDULE_MAX_MS] (B.9
     * (125)). A stalled run is cheap (the classification and the epoch), but it still costs a job
     * token, a connection and that read each time, for as long as a host retries its open.
     */
    private fun openRescheduleWindowMs(): Long =
        if (!stalled) OPEN_RESCHEDULE_MS
        else minOf(OPEN_RESCHEDULE_MS shl minOf(stalledOpenSchedules, 20), OPEN_RESCHEDULE_MAX_MS)

    /** What the walk has reached, for the surface (Appendix B.8.5): null before any run read. */
    fun progress(): ReplicaWalkPosition? = locked { position }

    /**
     * An open was refused for this walk: schedule it, **at most once per window**
     * ([OPEN_RESCHEDULE_MS], growing while the walk is stalled, [openRescheduleWindowMs]), so a
     * host that retries its open in a loop does not drive a run per retry ((115)).
     */
    fun scheduleFromAnOpen() {
        val now = nowMs()
        // Null: due now. Otherwise the ms until the window ends, for one owed schedule; -1 when one is owed already.
        val wait = locked {
            val last = lastOpenScheduleMs
            val window = openRescheduleWindowMs()
            when {
                last == null || now - last >= window -> {
                    lastOpenScheduleMs = now
                    if (stalled) stalledOpenSchedules++
                    null
                }
                openScheduleOwed -> -1L
                else -> {
                    openScheduleOwed = true
                    window - (now - last)
                }
            }
        }
        when {
            wait == null -> registry.onWalkRequested?.invoke(this)
            // ⚠ Suppressed, not dropped: dispatched once the window ends.
            wait >= 0 -> after(wait) {
                locked {
                    openScheduleOwed = false
                    lastOpenScheduleMs = nowMs()
                    if (stalled) stalledOpenSchedules++
                }
                registry.onWalkRequested?.invoke(this)
            }
        }
    }

    /**
     * One run (Appendix B.8.2): admitted at the gate and by the time left, the marker looked for,
     * up to `CONFIRM_MAX` of reading on a new read-only connection from where the walk is, then
     * the verdict or the position made durable. Never throws; never runs twice at once. [stop] is
     * the run's own: an expiry that came before the run began still stops it ((117)).
     */
    fun run(stop: ReplicaWalkStop = ReplicaWalkStop()): ReplicaWalkOutcome {
        if (!locked { if (running) false else true.also { running = true } }) return ReplicaWalkOutcome.Deferred("a run is already under way")
        try {
            return runOnce(stop)
        } finally {
            locked { running = false }
        }
    }

    private fun runOnce(stop: ReplicaWalkStop): ReplicaWalkOutcome {
        val gate = registry.gate
        if (!gate.enter("confirm-walk:$appName")) return ReplicaWalkOutcome.Deferred("the gate is closed")
        try {
            if (!gate.isOpen) return ReplicaWalkOutcome.Deferred("the gate is closing")
            gate.refusal(ReplicaOperation.CONFIRM_WALK, limits, opensConnection = true)?.let { return ReplicaWalkOutcome.Deferred(it) }
            val serial = locked { requestSerial }
            val start: ReplicaWalkPosition
            val identity: String?
            try {
                // Whether a walk is owed, where it is, and which file it is on: file work, inside the admission.
                start = startingPosition() ?: return ReplicaWalkOutcome.NothingPending
                identity = files.identity(registry.databasePath)
            } catch (e: ReplicaFileException) {
                return ReplicaWalkOutcome.NotMade("the walk's position or the store's identity could not be read: ${e.message}", e)
            }
            try {
                // A sibling may have reached its own verdict already: the marker is the answer then.
                marked()?.let { return finished(it, serial) }
            } catch (e: ReplicaFileException) {
                return ReplicaWalkOutcome.NotMade("replica/CORRUPT could not be looked for: ${e.message}", e)
            }
            usleep((ReplicaAdmissionBudget.CONFIRM_WALK_PAUSE_MS * 1_000).toUInt())
            registry.beforeConfirm?.invoke()
            if (!gate.isOpen) return ReplicaWalkOutcome.Deferred("the gate closed before the walk read")
            // A reading of up to CONFIRM_MAX is never made over a write lock of this process's own.
            registry.transactionHolder()?.let { return ReplicaWalkOutcome.Deferred("'${it.label}' holds a transaction") }
            val read = read(start, identity, stop)
            return conclude(start, read, serial)
        } finally {
            // Its directory descriptor is not kept between runs, which may be hours apart.
            marker.close()
            gate.leave()
        }
    }

    private fun fresh(error: String? = null): ReplicaWalkPosition =
        ReplicaWalkPosition(epoch = null, file = null, unit = 0, afterKey = null, afterType = null, runs = 0, error = error ?: "a corruption code")

    /**
     * Where this run starts, or null when no walk is owed: the position in memory, else the one
     * on disk (read again whenever memory knows none, so a file another process of this name
     * wrote, or one this process could not remove and then rewrote, is seen), else a fresh one
     * for a request. Under the admission.
     */
    private fun startingPosition(): ReplicaWalkPosition? {
        locked { position }?.let { return it }
        owedOnDisk()?.let { onDisk ->
            locked {
                position = onDisk
                if (requestedError == null) requestedError = onDisk.error
            }
            return onDisk
        }
        val (isRequested, error) = locked { requested to requestedError }
        return if (isRequested) fresh(error) else null
    }

    /**
     * The position on disk this process owes, or null. A file that does not decode is owed too
     * (as a fresh start: the next save replaces it); the file this process ended on and could not
     * remove is not.
     */
    private fun owedOnDisk(): ReplicaWalkPosition? {
        val bytes = try {
            files.readAll(positionPath, MAX_POSITION_BYTES) ?: return null
        } catch (e: ReplicaFileException) {
            // ⚠ (120): a file that is there and cannot be read (over its size bound, an I/O error)
            // is a position that does not decode: owed, from the start, and the next save replaces it.
            if (locked { endedUnreadable }) return null
            println("[walk] $positionPath could not be read (${e.message}): the walk starts over")
            return fresh("a position that could not be read")
        }
        if (locked { endedBytes }?.contentEquals(bytes) == true) return null
        val decoded = ReplicaWalkPosition.decode(bytes, appName)
        if (decoded == null) println("[walk] $positionPath does not decode: the walk starts over")
        return decoded ?: fresh("a position that did not decode")
    }

    private sealed interface Read {
        /** Every unit from the start position to the end was read without a finding. */
        data object Clean : Read

        data class Damaged(val detail: String) : Read

        /** Stopped by `CONFIRM_MAX` (or, [byExpiry], an expiry) at [reached]. */
        data class Bounded(val reached: ReplicaWalkPosition, val byExpiry: Boolean) : Read

        /** A stalled walk on the store it stalled on: nothing was read past the epoch ((119)). */
        data object StillStalled : Read

        /** The store's layout is not this build's: nothing past the classification read applies to it (B.9 (123)). */
        data class NoVerdict(val reason: String) : Read

        /** Stopped because the gate closed: nothing it reached is saved. */
        data class GateClosed(val reason: String) : Read
        data class NotMade(val reason: String, val cause: Throwable? = null) : Read
    }

    private fun read(start: ReplicaWalkPosition, identity: String?, stop: ReplicaWalkStop): Read {
        val deadline = registry.confirmClock() + limits.confirmMaxMs
        var stopped: String? = null
        var byGate = false
        var byExpiry = false
        var cursor = start
        // Until the store's epoch is read the run has no position it may save (B.8.3 (102)).
        var epochKnown = false
        return try {
            registry.readOnly("confirm-walk:$appName", alreadyAdmitted = true) { connection ->
                connection.stoppableBy(
                    everyOps = registry.confirmAskEvery,
                    shouldStop = {
                        if (stopped == null) {
                            if (!registry.gate.isOpen) {
                                stopped = "the gate closed"
                                byGate = true
                            } else if (stop.asked) {
                                stopped = "the run was asked to stop"
                                byExpiry = true
                            } else if (registry.confirmClock() >= deadline) {
                                stopped = "it ran past CONFIRM_MAX (${limits.confirmMaxMs} ms)"
                            }
                        }
                        stopped != null
                    },
                ) {
                    registry.confirmFault?.invoke()
                    // The open's classification read first, as the inline reading did: page 1,
                    // the schema, `meta`, the stamps. A corruption code there is a finding.
                    val reading = readMarker(connection)
                    // ⚠⚠ (123) and Q16 (Appendix B.10 (132), (141)): the open's own judgement of
                    // this reading, before anything else. Only a store of this build's layout is
                    // judged by the walk: `classify` answers another layout `Skew` or `OlderLayout`
                    // (no verdict here, as the open refuses it), and a layout that cannot be read
                    // `Damaged`, as the open marks it. On this layout, a unit that cannot even be
                    // prepared (a table or the index gone) is damage as the open calls it, never
                    // NotMade at every run. (A separate gate on `ReplicaSchema.LAYOUT` before this
                    // answered an unreadable layout with no verdict where the open marks it.)
                    opensJudgement(connection, reading)?.let { return@stoppableBy it }
                    // ⚠⚠ (121): an unreadable `meta.epoch` is damage, by the open's own rule
                    // (`metaInteger` → `ReplicaDamaged` → `Corrupt`), which never runs while this
                    // walk fences the open. Read as "unknown", it restarted every run and stalled
                    // on every launch: B.3 (47) again, for one input. ⚠ It marks on this one
                    // read-only snapshot: defensible because the run is itself the second reading
                    // of a code already met (B.9 (126)).
                    val epoch: Long
                    try {
                        epoch = ReplicaSchema.metaInteger(connection, ReplicaSchema.META_EPOCH)
                    } catch (e: ReplicaDamaged) {
                        return@stoppableBy Read.Damaged("the classification read: ${e.detail}")
                    }
                    // A stalled walk reads no further on the store it stalled on ((119)).
                    val stuckOn = locked { stalledOn }
                    if (stuckOn != null) {
                        if (stuckOn == (epoch to identity)) return@stoppableBy Read.StillStalled
                        println("[walk] the store changed under a stalled walk (epoch or file): the stall is lifted")
                        locked { clearStall() }
                    }
                    // From `start` on every attempt (a busy BEGIN runs this block again). ⚠ An
                    // unknown epoch or file never matches, on either side.
                    val from = if (start.runs > 0 && start.isSameStoreAs(epoch, identity)) {
                        start
                    } else {
                        if (start.runs > 0) println("[walk] the store's epoch or file is not the one the walk began on: it starts over")
                        ReplicaWalkPosition(epoch, identity, 0, null, null, 0, start.error)
                    }.let { it.copy(runs = it.runs + 1) }
                    cursor = from
                    epochKnown = true
                    // Every page this walk loads is checked cell by cell (B.8.3 (103)).
                    connection.exec("PRAGMA cell_size_check = ON")
                    walkUnits(connection, from) {
                        cursor = it
                        onReached?.invoke(it)
                    } ?: Read.Clean
                }
            }.let { finding ->
                when (finding) {
                    // A reading that got to its end, or to a finding, is its answer, whatever was asked of it after.
                    is Read.Damaged, Read.Clean, Read.StillStalled, is Read.NoVerdict -> finding
                    is Read.Bounded, is Read.GateClosed, is Read.NotMade -> when {
                        byGate -> Read.GateClosed("the walk was stopped: the gate closed")
                        stopped != null -> bounded(cursor, epochKnown, stopped, byExpiry)
                        else -> finding
                    }
                }
            }
        } catch (e: SqliteException) {
            val verdict = ReplicaVerdicts.of(e.primaryCode, ReplicaPhase.OPEN_CLASSIFY, protectedDataAvailable = protectedDataAvailable())
            when {
                byGate -> Read.GateClosed("the walk was stopped: the gate closed")
                stopped != null -> bounded(cursor, epochKnown, stopped, byExpiry)
                verdict == ReplicaVerdict.CORRUPT -> Read.Damaged("${ReplicaWalkUnit.all.getOrNull(cursor.unit)?.id ?: "the end"}: ${e.message.orEmpty()}")
                else -> Read.NotMade("the walk's reading failed (rc=${e.resultCode}): ${e.message}", e)
            }
        } catch (e: StoreUnavailable) {
            Read.NotMade("the walk's reading could not be made: ${e.message}", e)
        } catch (e: SqliteUnfixedLibraryException) {
            Read.NotMade("the walk's reading could not be made: ${e.message}", e)
        }
    }

    /**
     * ⚠⚠ **The open's own rule, applied to this run's reading** (Q16, Appendix B.10 (132)), or
     * null when the open would go on to use the store. The same two functions the open's write
     * half runs, in the same order, with the same consequence, so the walk and the open cannot
     * disagree on one store:
     * - [classify] against this process's build first. `Skew` (a version above this build, on this
     *   layout) and `OlderLayout` are refused by the open with nothing judged damaged and nothing
     *   written, so here they are **no verdict**, as another layout is ((123)); `Damaged` is the
     *   open's marker, so it is damage here.
     * - Then, for every verdict the open goes on from (`Compatible`, `Behind`, `Updating`: the
     *   write half, or the job's admission), [ReplicaSchema.validate], whose finding the open
     *   marks `CORRUPT`.
     *
     * ⚠ On one read-only snapshot, defended as (126) is: the run is itself the second reading of a
     * code already met. The open reads a classification's damage twice; this reading is that
     * second one.
     */
    private fun opensJudgement(connection: SqliteConnection, reading: ReplicaReading): Read? =
        when (val verdict = classify(reading, build)) {
            is ReplicaClassification.Skew -> Read.NoVerdict("the store is ahead of this build: ${verdict.detail}")
            is ReplicaClassification.OlderLayout -> Read.NoVerdict(verdict.detail)
            // An empty file: the open creates the store in it, so there is nothing to judge.
            ReplicaClassification.Fresh -> Read.NoVerdict("the store holds no schema")
            is ReplicaClassification.Damaged -> Read.Damaged("the classification read: ${verdict.detail}")
            ReplicaClassification.Compatible, is ReplicaClassification.Behind, is ReplicaClassification.Updating ->
                ReplicaSchema.validate(connection)?.let { Read.Damaged("schema validation: $it") }
        }

    /**
     * Stopped by its bound at [cursor]. ⚠ A whole-file check that did not finish in one run
     * never will on this store, cold (B.3 (47)): the walk goes on ranged from the next unit, so
     * a run is never spent on it twice.
     */
    private fun bounded(cursor: ReplicaWalkPosition, epochKnown: Boolean, why: String?, byExpiry: Boolean): Read =
        if (!epochKnown) Read.NotMade("the walk was stopped before it had read the store's epoch: $why")
        else Read.Bounded(if (ReplicaWalkUnit.all.getOrNull(cursor.unit)?.kind == ReplicaWalkUnitKind.WHOLE) cursor.atUnitStart(cursor.unit + 1) else cursor, byExpiry)

    /**
     * Reads from [from] to the end. Returns a finding, or null when every unit was read clean;
     * reports every position it reaches through [reached], so a run stopped by the progress
     * handler knows the last key it read **whole**. ⚠ A key is reported only once its row (or
     * entry) has been returned by SQLite, never the key it was reading when it was stopped.
     */
    private fun walkUnits(connection: SqliteConnection, from: ReplicaWalkPosition, reached: (ReplicaWalkPosition) -> Unit): Read? {
        var at = from
        while (at.unit < ReplicaWalkUnit.all.size) {
            val unit = ReplicaWalkUnit.all[at.unit]
            atUnit?.invoke(unit)
            when (unit.kind) {
                ReplicaWalkUnitKind.WHOLE -> {
                    quickCheckFinding(connection, null)?.let { return Read.Damaged("quick_check: $it") }
                    // The whole file was read: no further unit is owed.
                    return null
                }
                ReplicaWalkUnitKind.TABLE, ReplicaWalkUnitKind.SCHEMA ->
                    quickCheckFinding(connection, unit.name)?.let { return Read.Damaged("quick_check(${unit.name}): $it") }
                ReplicaWalkUnitKind.ROWS -> walkRows(connection, unit.name, at, reached)?.let { return Read.Damaged("${unit.id}: $it") }
                ReplicaWalkUnitKind.INDEX -> walkIndex(connection, unit.name, at, reached)
            }
            at = at.atUnitStart(at.unit + 1)
            reached(at)
        }
        return null
    }

    /**
     * The table's rows from [at] on; `quick_check`'s finding on a row's datatypes, or null. ⚠⚠
     * Each row is held to the two checks `quick_check` makes of it (B.9 (122)): a NOT NULL column
     * holds no NULL, and a TEXT column of this non-STRICT table holds no number. So a row that a
     * small store's `WHOLE` would call damage is damage here too, and the verdict does not depend
     * on the store's size; the index is then free to step past such an entry ([walkIndex]).
     */
    private fun walkRows(connection: SqliteConnection, table: String, at: ReplicaWalkPosition, reached: (ReplicaWalkPosition) -> Unit): String? {
        val checked = rowChecks(table)
        val statement = connection.prepare(rowsSql(table, resume = at.afterKey != null))
        try {
            at.afterKey?.let { statement.bindLong(1, it) }
            // A row is returned only once every column was loaded, `data`'s overflow chain
            // included (OP_Column reads a result column's whole payload): the row is read.
            // The typeof() columns follow every column of the table (rowsSql).
            val first = ReplicaSchema.tables.single { it.name == table }.columns.size
            while (statement.step()) {
                for ((i, check) in checked.withIndex()) {
                    val type = statement.getText(first + i)
                    if (check.notNull && type == "null") return "NULL value in $table.${check.column} (rowid ${statement.getLong(0)})"
                    if (check.text && (type == "integer" || type == "real")) return "NUMERIC value in $table.${check.column} (rowid ${statement.getLong(0)})"
                }
                reached(at.copy(afterKey = statement.getLong(0)))
            }
        } finally {
            statement.close()
        }
        return null
    }

    /**
     * The index's entries from [at] on, **of every storage class** (B.9 (122)). An index entry is
     * not a row: `quick_check` checks no index entry's datatype, so a small store's `WHOLE` passes
     * one, and the ranged walk must step past it too, never stop at it. The position carries the
     * entry's class ([ReplicaWalkPosition.afterClass]) and is bound back in it ([indexSql] says how
     * each class
     * resumes, against SQLite 3.53.4's own rules). ⚠⚠ TEXT keys are carried as the raw bytes SQLite
     * stored and bound back as TEXT of exactly those bytes ((112)): decoding would turn a malformed
     * byte into U+FFFD, which sorts elsewhere, and `type > ?` would then skip entries.
     */
    private fun walkIndex(connection: SqliteConnection, index: String, at: ReplicaWalkPosition, reached: (ReplicaWalkPosition) -> Unit) {
        val resumeIn = if (at.afterKey != null) at.afterClass ?: error("[walk] a decoded index position has a class") else null
        // ⚠⚠ Every entry at or before the resume point, in the index's own order, is stepped over
        // HERE, whatever the statement returned (B.10 (133)). Inside the NULL class that is the
        // NULLs already read, since the walk resumes on the plain scan (B.9 (129): no SQL can
        // seek a NULL of a NOT NULL column). For TEXT and BLOB it is what a plan that evaluated
        // `type > ?` as a filter would add: read as SQL it also holds for a number whose text
        // sorts after the key, an entry behind the start. Reported, it would save a position
        // behind the start, and the walk could go back and forth.
        val resumeAfter = if (resumeIn != null) ReplicaIndexKey.of(at) ?: error("[walk] a decoded index position names an entry") else null
        for ((i, sql) in indexSql(index, resumeIn).withIndex()) {
            val statement = connection.prepare(indexStatement?.invoke(sql) ?: sql)
            try {
                if (resumeIn != null) bindResume(statement, i, resumeIn, at)
                while (statement.step()) {
                    val keyClass = ReplicaWalkKeyClass.of(statement.getText(0)) ?: error("[walk] typeof() answered ${statement.getText(0)}")
                    val entry = ReplicaIndexKey.read(statement, 1, keyClass, statement.getLong(2))
                    if (resumeAfter != null && entry <= resumeAfter) continue
                    reached(at.copy(afterClass = keyClass, afterType = entry.keyText, afterKey = entry.rowid))
                }
            } finally {
                statement.close()
            }
        }
    }

    /** The [i]th resume statement's parameters, in the position's class (see [indexSql]). */
    private fun bindResume(statement: SqliteStatement, i: Int, keyClass: ReplicaWalkKeyClass, at: ReplicaWalkPosition) {
        val key = at.afterType!!
        val rowid = at.afterKey!!
        fun bindKey() = when (keyClass) {
            ReplicaWalkKeyClass.NULL -> Unit
            ReplicaWalkKeyClass.INTEGER -> statement.bindLong(1, key.toLong())
            ReplicaWalkKeyClass.REAL -> statement.bindDouble(1, Double.fromBits(bytesOfHex(key)!!.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }))
            ReplicaWalkKeyClass.TEXT -> statement.bindTextBytes(1, bytesOfHex(key)!!)
            ReplicaWalkKeyClass.BLOB -> statement.bindBlob(1, bytesOfHex(key)!!)
        }
        when (keyClass) {
            // The plain scan from the first entry: nothing to bind ([walkIndex] steps over what was read).
            ReplicaWalkKeyClass.NULL -> Unit
            ReplicaWalkKeyClass.INTEGER, ReplicaWalkKeyClass.REAL -> if (i == 0) {
                bindKey()
                statement.bindLong(2, rowid)
            }
            ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> {
                bindKey()
                if (i == 0) statement.bindLong(2, rowid)
            }
        }
    }

    /** What the run read becomes: the marker, the end of the walk, or the position made durable. Under the admission. */
    private fun conclude(start: ReplicaWalkPosition, read: Read, serial: Long): ReplicaWalkOutcome = when (read) {
        is Read.Damaged -> {
            if (!protectedDataAvailable()) {
                // B.3 (4): nothing gathered while the phone is locked marks the store.
                ReplicaWalkOutcome.NotMade("protected data is unavailable")
            } else {
                try {
                    registry.admittedForMarker(alreadyAdmitted = true) {
                        marker.write(corruptMarkerDetail(files, appName, "${start.error}; confirmed by a second reading (the confirmation walk, run ${start.runs + 1}): ${read.detail}"))
                    }
                    finished(ReplicaCorruptionConfirmation.Confirmed(read.detail), serial)
                } catch (e: ReplicaFileException) {
                    ReplicaWalkOutcome.NotMade("replica/CORRUPT could not be written: ${e.message}", e)
                }
            }
        }
        is Read.Clean -> {
            // A sibling that reached its verdict meanwhile has fenced the store for good, whatever
            // this walk made of it. ⚠ Not once the gate has closed: finding a marker syncs its
            // directory, and nothing new is started then (as `siblingsVerdictOr` had it).
            val sibling = if (!registry.gate.isOpen) {
                null
            } else {
                try {
                    marked()
                } catch (_: ReplicaFileException) {
                    null
                }
            }
            if (sibling != null) {
                finished(sibling, serial)
            } else {
                println("[walk] the store was read whole without a finding (${start.runs + 1} runs): the code is not confirmed")
                over(serial)
                ReplicaWalkOutcome.Clean(start.runs + 1)
            }
        }
        is Read.Bounded -> save(start, read.reached, read.byExpiry)
        is Read.GateClosed -> ReplicaWalkOutcome.Deferred(read.reason)
        is Read.NotMade -> ReplicaWalkOutcome.NotMade(read.reason, read.cause)
        Read.StillStalled -> locked { ReplicaWalkOutcome.Stalled(position ?: start, start.runs, stallReason ?: "stalled") }
        is Read.NoVerdict -> {
            // ⚠ Over, with nothing marked: the fence is this walk's, and lifting it hands the
            // store to the open's own judgement, which refuses a layout it does not know (B.9 (123)).
            println("[walk] ⚠ $appName's walk ends with no verdict: ${read.reason}. Nothing is marked; the open judges the store")
            over(serial)
            ReplicaWalkOutcome.NoVerdict(read.reason)
        }
    }

    private fun save(start: ReplicaWalkPosition, reached: ReplicaWalkPosition, byExpiry: Boolean): ReplicaWalkOutcome {
        // A restart counts as progress only when the position named a known store that changed:
        // one with an unknown epoch or file restarts every run and is a walk that cannot advance.
        val restartedOnAnotherStore = start.epoch != null && start.file != null && !start.isSameStoreAs(reached.epoch, reached.file)
        val advanced = start.runs == 0 || restartedOnAnotherStore || reached.isPast(start)
        val stuck = locked {
            position = reached
            // An expiry's run is cut short by the platform, not by the store: it never counts ((119)).
            if (advanced) stalls = 0 else if (!byExpiry) stalls++
            if (stalls >= STALL_RUNS && !stalled) {
                stalled = true
                stalledOn = reached.epoch to reached.file
                stallReason = "$STALL_RUNS runs in a row ended where they began, in ${ReplicaWalkUnit.all.getOrNull(reached.unit)?.id}"
            }
            stalled
        }
        if (!advanced) println("[walk] run ${reached.runs} of $appName's walk did not advance past ${ReplicaWalkUnit.all.getOrNull(reached.unit)?.id}, key ${reached.afterKey} (${locked { stalls }} in a row)")
        if (stuck) {
            println("[walk] ⚠ $appName's walk cannot advance: $STALL_RUNS runs in a row ended where they began. No further run in this process; the store stays fenced and is reported as not checkable")
            return ReplicaWalkOutcome.Stalled(reached, reached.runs, locked { stallReason } ?: "stalled")
        }
        // ⚠ Not once the gate has begun to close: nothing new is written then (B.3 (48)).
        if (!registry.gate.isOpen) return ReplicaWalkOutcome.Progressed(reached, advanced, saved = false)
        return try {
            beforeSave?.invoke(reached)
            writeDurably(reached.encode(appName))
            locked {
                endedBytes = null
                endedUnreadable = false
            }
            ReplicaWalkOutcome.Progressed(reached, advanced, saved = true)
        } catch (e: ReplicaFileException) {
            println("[walk] the position could not be saved: ${e.message}; this process goes on from it, a new one from the last saved")
            ReplicaWalkOutcome.Progressed(reached, advanced, saved = false)
        }
    }

    /** The sidecar rule (§6): a temporary file of this writer's own, `F_FULLFSYNC`, `rename` over the old, the directory synced. */
    private fun writeDurably(bytes: ByteArray) {
        val temporary = "$positionPath.tmp.${getpid()}.${arc4random().toString(16)}"
        try {
            val descriptor = files.createAndWrite(temporary, bytes)
            try {
                files.fullSync(descriptor)
            } finally {
                files.closeDescriptor(descriptor)
            }
            files.rename(temporary, positionPath)
        } catch (e: ReplicaFileException) {
            files.remove(temporary)
            throw e
        }
        syncDirectory(files, directory)
    }

    /**
     * The walk is over (a verdict): owed no more, here or on disk. ⚠ A request made while this
     * run read (its serial moved) stays owed: it is another code ((116)). ⚠ A position file that
     * cannot be removed is remembered by its bytes, so this process neither refuses its opens nor
     * walks again for it ((114)); a later save replaces it, and a new process walks it once more.
     */
    private fun over(serial: Long) {
        var unreadable = false
        val bytes = try {
            files.readAll(positionPath, MAX_POSITION_BYTES)
        } catch (_: ReplicaFileException) {
            unreadable = true
            null
        }
        locked {
            position = null
            clearStall()
            if (requestSerial == serial) {
                requested = false
                requestedError = null
            }
        }
        if (bytes == null && !unreadable) return
        // ⚠ Not synced: a removal a power loss undoes brings back a position, and the walk then
        // reads its last units again and ends again. Never the other way round.
        files.remove(positionPath)
        val stillThere = try {
            files.exists(positionPath)
        } catch (_: ReplicaFileException) {
            true
        }
        if (stillThere) {
            println("[walk] ⚠ $positionPath could not be removed: this process does not treat it as owed")
            locked {
                if (bytes != null) endedBytes = bytes else endedUnreadable = true
            }
        }
    }

    private fun finished(confirmation: ReplicaCorruptionConfirmation, serial: Long): ReplicaWalkOutcome = when (confirmation) {
        is ReplicaCorruptionConfirmation.Confirmed -> {
            over(serial)
            println("[replica] the store is CORRUPT: ${confirmation.detail}")
            ReplicaWalkOutcome.Confirmed(confirmation.detail)
        }
        is ReplicaCorruptionConfirmation.Unconfirmed -> ReplicaWalkOutcome.NotMade(confirmation.reason, confirmation.cause)
    }

    private fun marked(): ReplicaCorruptionConfirmation? = when (marker.state()) {
        ReplicaMarkerState.ABSENT -> null
        ReplicaMarkerState.DURABLE -> ReplicaCorruptionConfirmation.Confirmed("replica/CORRUPT exists")
        ReplicaMarkerState.NOT_DURABLE -> ReplicaCorruptionConfirmation.Unconfirmed("replica/CORRUPT is there and could not be made durable")
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        const val POSITION_PREFIX = "confirm-walk."

        /** A position is a few hundred bytes; anything far larger is not one. */
        const val MAX_POSITION_BYTES = 16 * 1024

        /** ⚠ Placeholder ((115)): runs in a row that did not advance before the walk is [ReplicaWalkOutcome.Stalled]. */
        const val STALL_RUNS = 3

        /** ⚠ Placeholder ((115)): the least time between two runs an open's refusal schedules. */
        const val OPEN_RESCHEDULE_MS = 5_000L

        /** ⚠ Placeholder (B.9 (125)): the most a stalled walk's open-driven reschedule backs off to. */
        const val OPEN_RESCHEDULE_MAX_MS = 5 * 60_000L

        /** The `objects` table's integer primary key (its rowid), named once from [ReplicaSchema]. */
        private fun keyOf(table: String): String {
            val columns = ReplicaSchema.tables.single { it.name == table }.columns
            val key = columns.single { it.primaryKey }
            check(key.type == "INTEGER") { "[walk] the keyset needs an INTEGER PRIMARY KEY (the rowid), $table has ${key.type}" }
            return key.name
        }

        /** One of `quick_check`'s two per-row datatype checks on a non-STRICT table's column (B.9 (122)). */
        internal data class RowCheck(val column: String, val notNull: Boolean, val text: Boolean)

        /**
         * The row checks `quick_check` makes of [table]'s columns other than its rowid (SQLite
         * 3.53.4, `sqlite3.c`, the integrity check's "Verify datatypes for all columns"): (1) a NOT
         * NULL column holds no NULL; (3) a TEXT column of a non-STRICT table holds no INTEGER or
         * REAL. ⚠ Its (4), a numeric column holding a TEXT that converts, is not mirrored: this
         * layout's walked table has no such column, and this says so before it walks one wrongly.
         */
        fun rowChecks(table: String): List<RowCheck> {
            val key = keyOf(table)
            val columns = ReplicaSchema.tables.single { it.name == table }.columns.filter { it.name != key }
            check(columns.none { it.type == "INTEGER" || it.type == "REAL" || it.type == "NUMERIC" }) { "[walk] no row check for a numeric column of $table" }
            return columns.filter { it.notNull || it.type == "TEXT" }.map { RowCheck(it.name, it.notNull, it.type == "TEXT") }
        }

        /**
         * The keyset over a table's rows, **every column** (from [ReplicaSchema], so a column added
         * there is read too), then `typeof()` of each column [rowChecks] names. ⚠ Two statements,
         * not `key > ?` from `Long.MIN_VALUE`: a row at `Long.MIN_VALUE` is a real key.
         */
        fun rowsSql(table: String, resume: Boolean): String {
            val key = keyOf(table)
            val columns = listOf(key) + ReplicaSchema.tables.single { it.name == table }.columns.map { it.name }.filter { it != key }
            val types = rowChecks(table).map { "typeof(${it.column})" }
            val select = "SELECT ${(columns + types).joinToString(", ")} FROM $table"
            return if (!resume) "$select ORDER BY $key" else "$select WHERE $key > ? ORDER BY $key"
        }

        /**
         * The keyset over an index's entries, a covering scan in its own order (`INDEXED BY`: it
         * fails to prepare rather than walk the table), each row `typeof(column), column, rowid`,
         * resumed in the class of the entry it stopped on ([resumeIn]; null: from the first entry).
         *
         * ⚠⚠ **Resumed as two statements, not `(type, hash) > (?, ?)`**: SQLite 3.53 seeks that
         * row value on `type` alone and filters the rowid ((110)). `type = ? AND hash > ?` seeks
         * on both, then `type > ?` goes on.
         *
         * ⚠⚠ **And by class** (B.9 (122)), each read in SQLite 3.53.4's own source:
         * - The index orders NULL < INTEGER/REAL (by value) < TEXT (BINARY) < BLOB.
         * - **TEXT and BLOB** resume as above. `type` has TEXT affinity, and comparing it with a
         *   bound value of no affinity applies TEXT affinity to the value (`sqlite3CompareAffinity`),
         *   which leaves a TEXT or a BLOB as it is (`applyAffinity` converts numbers only). A BLOB
         *   sorts after every TEXT, so `type > ?` with a TEXT key reaches the BLOBs too. ⚠ Read as
         *   SQL rather than as the seek, `type > ?` with a TEXT key would also hold for a stored
         *   number whose text sorts after the key (`OP_Gt` stringifies it): a superset, never a
         *   subset, so a planner that evaluated it as a filter could never make the walk skip an
         *   entry. ⚠ *Corrected in B.10 (133):* not "a stall". The extra entries are numbers,
         *   **behind** the start; reported, they saved a position behind it, which `isPast`
         *   counted as progress, so the walk could go back and forth with no stall. [walkIndex]
         *   now steps over every entry at or before the resume point, and `isPast` is order-aware.
         * - **INTEGER and REAL** cannot be bound that way: the affinity turns a bound 5 into '5'
         *   (measured: `type = 5` finds no INTEGER 5). They resume on one statement,
         *   `+type > ?1 OR (+type = ?1 AND rowid > ?2)` over the whole index in its order. `+type`
         *   has no affinity (`sqlite3ExprAffinity` returns the `TK_UPLUS` node's own, none), so each
         *   comparison is `sqlite3MemCompare`'s, the index's own order: numeric across INTEGER and
         *   REAL, every TEXT and BLOB after every number, a NULL never. So it returns exactly the
         *   entries after the key, whatever the plan (B.9 (129)). ⚠ No range bounds it: a
         *   `type < ''` bound would be semantically empty for a number (`OP_Lt` under TEXT affinity
         *   stringifies it, `sqlite3VdbeMemStringify`) and right only while the planner consumes the
         *   term. ⚠ **Its cost:** `+type` is not sargable, so each run rescans the NULLs and the
         *   numbers before the key. A large numeric region (only damage writes one) can therefore
         *   make runs that do not advance, which stall under (115): never a false `Clean`.
         * - ⚠⚠ **NULL** resumes on the plain scan from the index's first entry, with no WHERE, and
         *   [walkIndex] steps over the NULLs up to the key in Kotlin, then goes straight on into
         *   the numbers, TEXT and BLOB (B.9 (129)). No SQL finds them soundly: `type IS NULL` is
         *   folded to false on a NOT NULL column (`sqlite3ExprCanBeNull`), `+type` included, and
         *   the earlier `type < '' AND typeof(type) = 'null'` is semantically empty, returning the
         *   NULLs only through two planner details a later SQLite may change (`bSeekPastNull` only
         *   for a nullable column; the range-end term not evaluated again). Had either changed,
         *   the walk would have skipped the NULLs and ended `Clean` on the very damage it is for.
         */
        fun indexSql(index: String, resumeIn: ReplicaWalkKeyClass?): List<String> {
            val column = ReplicaSchema.indexes.single { it.name == index }.columns.single()
            val table = ReplicaSchema.OBJECTS
            val key = keyOf(table)
            val select = "SELECT typeof($column), $column, $key FROM $table INDEXED BY $index"
            return when (resumeIn) {
                null -> listOf("$select ORDER BY $column, $key")
                ReplicaWalkKeyClass.NULL -> listOf("$select ORDER BY $column, $key")
                ReplicaWalkKeyClass.INTEGER, ReplicaWalkKeyClass.REAL -> listOf(
                    "$select WHERE +$column > ?1 OR (+$column = ?1 AND $key > ?2) ORDER BY $column, $key",
                )
                ReplicaWalkKeyClass.TEXT, ReplicaWalkKeyClass.BLOB -> listOf(
                    "$select WHERE $column = ?1 AND $key > ?2 ORDER BY $key",
                    "$select WHERE $column > ?1 ORDER BY $column, $key",
                )
            }
        }
    }
}

/**
 * `PRAGMA quick_check`'s first finding, or null for `ok`. [table] null: the whole file
 * (`quick_check(1)`, stopping at the first finding); otherwise that one table's B-trees (the
 * table and its indexes), and for `sqlite_schema` the free list too (SQLite 3.53.4, `sqlite3.c`
 * `sqlite3BtreeIntegrityCheck`: "the freelist is only verified if the named table is
 * sqlite_schema"). ⚠ A named check skips the "every page is referenced" pass (`bPartial`).
 */
internal fun quickCheckFinding(connection: SqliteConnection, table: String?): String? {
    var first: String? = null
    connection.query(if (table == null) "PRAGMA quick_check(1)" else "PRAGMA quick_check($table)") { row -> if (first == null) first = row.getText(0) }
    // No row is not "ok": an answer nobody gave is never read as a healthy store.
    val answer = first ?: throw SqliteException(SQLITE_ERROR, "PRAGMA quick_check returned no row")
    return answer.takeUnless { it == "ok" }
}

/**
 * ⚠⚠ **The worker** (the worker's slice, Appendix B.8): runs each owed confirmation walk off the
 * main thread, one run at a time per walk, **each run under a job token of its own** taken with
 * [ReplicaProtection.acquireForJob] (so off the main thread and with no registry lock held, (86),
 * asserted there), released when the run returns. Runs follow each other while each advances;
 * any other outcome ends the dispatch, and the walk waits for its next [schedule] or [kick].
 *
 * What schedules a walk: a new hand-off ([ReplicaHandles.onWalkRequested]), an open refused
 * because a walk is owed (rate-limited, [ReplicaConfirmationWalk.scheduleFromAnOpen]), and the
 * host's [kick] at each foreground and each wake (`SharedReplicaUiKitAdapter`).
 *
 * ⚠⚠ **And the `REPLACEMENT` run** (Appendix B.11): the job an open answered `Updating` with
 * ([ReplicaHandles.onReplacementRequested], [scheduleReplacement]), a wipe's or a migration's
 * chunks, one run per dispatch under one job token, with the end-of-job checkpoint before that
 * token is released ((148)). One job per store: a newer one handed over while a run goes on
 * replaces it for the next run. A run that **counted** a migration failure
 * ([MigrationRowFailed]) holds that store's job until the next foreground ((147)). The outcome
 * goes to [onReplacementOutcome]; installing the runtime after `Finished` is the host's open,
 * as for the walk.
 */
internal class ReplicaWorker(
    /** Null: no token is taken (every test that does not attach protection, and nothing else). */
    private val protection: ReplicaProtection?,
    /** Runs a block once, off the main thread. ⚠ In production a serial queue of the worker's own. */
    private val dispatch: (() -> Unit) -> Unit,
    /** Told every outcome that ends a `REPLACEMENT` run, for the host's surface and its next open. */
    private val onReplacementOutcome: (ReplicaReplacement, ReplicaRunOutcome) -> Unit = { _, _ -> },
    /** Told every outcome that ends a dispatch, for the host's surface and rebuild. */
    private val onOutcome: (ReplicaConfirmationWalk, ReplicaWalkOutcome) -> Unit = { _, _ -> },
) {
    private val lock = NSRecursiveLock()
    private val known = LinkedHashSet<ReplicaConfirmationWalk>()
    private val dispatched = HashSet<ReplicaConfirmationWalk>()
    private val again = HashSet<ReplicaConfirmationWalk>()

    /** The job each store's next run takes: the latest handed over, until it ends for good. */
    private val jobs = LinkedHashMap<ReplicaHandles, ReplicaReplacement>()
    private val replacing = HashSet<ReplicaHandles>()
    private val replaceAgain = HashSet<ReplicaHandles>()

    /** Stores whose last run counted a migration failure, with [ReplicaProtection.foregroundEntries] then. */
    private val heldUntilForeground = HashMap<ReplicaHandles, Long>()

    /** Test seam: between a run's token and the run. Null in production. */
    internal var afterToken: (() -> Unit)? = null

    /** Every hand-off on [registry] schedules its walk here, and every job its open answered. */
    fun attach(registry: ReplicaHandles) {
        check(registry.onWalkRequested == null) { "[worker] ${registry.databasePath} already has a worker" }
        registry.onWalkRequested = ::schedule
        registry.onReplacementRequested = ::scheduleReplacement
    }

    /** Runs [replacement] off the caller's thread, or once the run of its store's job going on now ends. */
    fun scheduleReplacement(replacement: ReplicaReplacement) {
        val registry = replacement.registry
        val first = locked {
            jobs[registry] = replacement
            if (registry in replacing) {
                replaceAgain += registry
                false
            } else {
                replacing += registry
                true
            }
        }
        if (first) dispatch { driveReplacement(registry) }
    }

    private fun driveReplacement(registry: ReplicaHandles) {
        while (true) {
            val replacement = locked { jobs[registry] }
            if (replacement != null) {
                val outcome = heldOutcome(registry) ?: oneReplacementRun(replacement)
                // Judged outside the lock's try/finally (K/N: no type test of a value in that shape).
                val counted = countedAFailure(outcome)
                val forGood = endedForGood(outcome)
                locked {
                    // ⚠ (147): one count per run is the store's; that runs are foregrounds is this.
                    if (counted) heldUntilForeground[registry] = foregroundEntries()
                    // Ended for good: nothing to run again at a kick. A newer job handed over meanwhile stays.
                    if (forGood && jobs[registry] === replacement) jobs -= registry
                }
                onReplacementOutcome(replacement, outcome)
            }
            val more = locked {
                if (registry in replaceAgain) {
                    replaceAgain -= registry
                    true
                } else {
                    replacing -= registry
                    false
                }
            }
            if (!more) return
        }
    }

    /** Not before the next foreground, after a run that counted (B.3 (56)): the hold, or null once it is lifted. */
    private fun heldOutcome(registry: ReplicaHandles): ReplicaRunOutcome? = locked {
        val countedAt = heldUntilForeground[registry] ?: return@locked null
        if (foregroundEntries() != countedAt) {
            heldUntilForeground -= registry
            null
        } else {
            ReplicaRunOutcome.Stopped("a run counted a migration failure: not again before the next foreground")
        }
    }

    private fun countedAFailure(outcome: ReplicaRunOutcome): Boolean = when (outcome) {
        is ReplicaRunOutcome.Stopped -> outcome.cause is MigrationRowFailed
        else -> false
    }

    private fun endedForGood(outcome: ReplicaRunOutcome): Boolean = when (outcome) {
        ReplicaRunOutcome.Finished, is ReplicaRunOutcome.Skew, is ReplicaRunOutcome.Corrupt -> true
        is ReplicaRunOutcome.Stopped, ReplicaRunOutcome.NeedsForeground -> false
    }

    /** With no protection attached nothing counts foregrounds: a hold then lasts the process. */
    private fun foregroundEntries(): Long = protection?.foregroundEntries ?: 0L

    /** One run under one job token, the end-of-job checkpoint included; in its own function (K/N). */
    private fun oneReplacementRun(replacement: ReplicaReplacement): ReplicaRunOutcome {
        val stop = ReplicaWalkStop()
        // ⚠⚠ B.15 (203): the job's token reaches its own run's statements, the chunks' and the
        // checkpointer's, not only the stop between chunks. Made before the token, as the stop is.
        val scope = ReplicaExpiryScope("the job token of replacement:${replacement.appName}")
        val token = if (protection == null) {
            null
        } else {
            try {
                protection.acquireForJob("replacement:${replacement.appName}", onExpired = {
                    stop.ask()
                    scope.expire()
                })
            } catch (e: IllegalStateException) {
                // (86), as for the walk: logged, never thrown on a dispatch queue.
                println("[worker] ${e.message}")
                return ReplicaRunOutcome.Stopped("the job token was refused: ${e.message}", e)
            } ?: return ReplicaRunOutcome.Stopped("no protected time was granted")
        }
        try {
            afterToken?.invoke()
            // ⚠⚠ (148): the end-of-job checkpoint BEFORE the token is released, which is what the
            // trigger is for; (154): inside the run's guard.
            return scope.around { replacement.runToEndOfJob { stop.asked } }
        } catch (t: Throwable) {
            println("[worker] a run of the job of ${replacement.appName} threw: ${t.message}")
            return ReplicaRunOutcome.Stopped("the run threw: ${t.message}", t)
        } finally {
            token?.release()
        }
    }

    fun schedule(walk: ReplicaConfirmationWalk) {
        val first = locked {
            known += walk
            if (walk in dispatched) {
                // Asked while it runs: run it again once the dispatch is done, or the request is lost.
                again += walk
                false
            } else {
                dispatched += walk
                true
            }
        }
        if (first) dispatch { drive(walk) }
    }

    /**
     * The host's trigger at a foreground and a wake: every walk this worker knows, run if owed, and
     * every store's job that has not ended for good (a held one is answered held, not run).
     */
    fun kick() {
        locked { known.toList() }.forEach(::schedule)
        locked { jobs.values.toList() }.forEach(::scheduleReplacement)
    }

    private fun drive(walk: ReplicaConfirmationWalk) {
        while (true) {
            var outcome: ReplicaWalkOutcome
            do {
                outcome = oneRun(walk)
            } while (outcome is ReplicaWalkOutcome.Progressed && outcome.advanced)
            onOutcome(walk, outcome)
            val more = locked {
                if (walk in again) {
                    again -= walk
                    true
                } else {
                    dispatched -= walk
                    false
                }
            }
            if (!more) return
        }
    }

    /** One run under one job token; kept in its own function (K/N: no `var` typed by its initializer in a loop's try). */
    private fun oneRun(walk: ReplicaConfirmationWalk): ReplicaWalkOutcome {
        // ⚠ The stop exists before the token, so an expiry that lands before the run begins is
        // not lost to it ((117)).
        val stop = ReplicaWalkStop()
        // B.15 (203): the walk's reading stops itself on `stop` (its progress handler); the scope
        // reaches any watched read of its judgement too.
        val scope = ReplicaExpiryScope("the job token of confirm-walk:${walk.appName}")
        val token = if (protection == null) {
            null
        } else {
            try {
                protection.acquireForJob("confirm-walk:${walk.appName}", onExpired = {
                    stop.ask()
                    scope.expire()
                })
            } catch (e: IllegalStateException) {
                // (86): a dispatch onto the main thread or under a registry's lock. A programming
                // error, logged and not thrown: an uncaught throw here ends the process.
                println("[worker] ${e.message}")
                return ReplicaWalkOutcome.NotMade("the job token was refused: ${e.message}", e)
            } ?: return ReplicaWalkOutcome.Deferred("no protected time was granted")
        }
        try {
            afterToken?.invoke()
            return scope.around { walk.run(stop) }
        } catch (t: Throwable) {
            println("[worker] a run of the walk of ${walk.appName} threw: ${t.message}")
            return ReplicaWalkOutcome.NotMade("the run threw: ${t.message}", t)
        } finally {
            token?.release()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
