package org.tenshu.core.common.proxy.db.sqlite

import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry

/**
 * The two job markers of `docs/proposals/ios-shared-store-sqlite.md`: `meta.wipe` (§5.1) and
 * `meta.migration` (§5.3). Each is one frame in a `meta` row, written inside a transaction, so
 * it is exactly as durable as the rows it describes.
 *
 * ⚠⚠ **The first byte is the frame's format, and it is the only thing a build that does not know
 * the format may read.** A frame of another format is a newer build's: the reader answers
 * `SKEW` and writes nothing. A frame of a format this build writes that does not parse is damage,
 * and "parse" is strict: every length inside the frame, every text valid UTF-8, no byte left over.
 */
internal const val JOB_MARKER_FORMAT: Int = 1

/**
 * The format of a `meta.migration` frame that carries a [MigrationFailure], and of no other frame
 * (Appendix B.3 (53)): [JOB_MARKER_FORMAT]'s fields, then the failure's. A migration that has
 * counted nothing is written in [JOB_MARKER_FORMAT], byte for byte as before the count existed, so
 * a build without the count reads it and may resume it; the frame with a count is another format
 * to that build, which answers `SKEW` and writes nothing, since it could not honour the count.
 */
internal const val MIGRATION_COUNTED_FORMAT: Int = 2

/** What reading a marker frame gave. */
internal sealed interface DecodedMarker<out T> {
    data class Value<T>(val marker: T) : DecodedMarker<T>

    /** Written by a build that knows a format this one does not: `SKEW`, nothing written. */
    data class OtherFormat(val format: Int) : DecodedMarker<Nothing>

    /** This build's format, and not a frame of it: damage. */
    data object Malformed : DecodedMarker<Nothing>
}

/**
 * `meta.wipe`: a wipe is in progress.
 *
 * [endStamps] are the version stamps the wipe's **last** chunk writes (a store is never stamped
 * before it is empty): a below-floor wipe's, or those of a wipe asked for on a store this build is
 * ahead of. Empty for a wipe of a store that is in step. A build below any of them may not resume.
 */
internal data class WipeMarker(
    val reason: String,
    val app: String,
    val startedSeconds: Long,
    val endStamps: Map<String, Int>,
) {
    fun encode(): ByteArray = FrameWriter().apply {
        byte(JOB_MARKER_FORMAT)
        text(reason)
        text(app)
        long(startedSeconds)
        stamps(endStamps)
    }.bytes()

    companion object {
        fun decode(frame: ByteArray): DecodedMarker<WipeMarker> = decodeFrame(frame, JOB_MARKER_FORMAT) {
            WipeMarker(reason = text(), app = text(), startedSeconds = long(), endStamps = stamps())
        }
    }
}

/** The phases of one migration step (§5.3). [VERIFY] is the verification, as a pass of its own. */
internal enum class MigrationPhase {
    ROWS, VERIFY, SWEEP, CLEANUP;

    /** True for a phase that hands a stored row to code that can fail on it: only there is a failure counted. */
    val readsRows: Boolean get() = this != CLEANUP
}

/**
 * The counted failures of the current step on one row (Appendix B.3 (41), (53)): the step could
 * not make its row of the stored one, in [count] runs of the job.
 *
 * The key is the position and the counting build: the marker's step and phase (a change of
 * either drops the whole record), [hash] (the row), and [version], **the counting build's declared
 * version of the step's service**. A build that declares another version starts at one and
 * replaces the record: the frame keeps the last counter's count only.
 *
 * [limit] is the counting build's `N` (`ReplicaLimits.migrationRowAttempts`), written beside the
 * count so the frame says for itself which counts a writer can have stored: at `N` the job becomes
 * a wipe, so a writer only ever stores `1 ≤ count < limit`. A reader compares [count] with its
 * **own** `N`.
 */
internal data class MigrationFailure(val hash: Long, val version: Int, val count: Int, val limit: Int)

/**
 * `meta.migration`: a migration chain is in progress, and the store's rows are mixed.
 *
 * - [targets]: the chain's plan, the final version of every service it moves. A build below any
 *   of them may not resume; a build above extends them at a step finish.
 * - [stepService], [stepFrom]: the current step, to `stepFrom + 1`. [UNSTAMPED] for a service
 *   that has no stamp yet: it has no rows to migrate and is stamped at its target.
 * - [cursor]: the hash of the last row a committed chunk of [phase] took, or **null** for "before
 *   the first row". Never a sentinel value: a row can hash to any `Long`.
 */
internal data class MigrationMarker(
    val app: String,
    val startedSeconds: Long,
    val targets: Map<String, Int>,
    val stepService: String,
    val stepFrom: Int,
    val phase: MigrationPhase,
    val cursor: Long?,
    /** The counted failures on the row the step is stopped at, or null: nothing is counted. */
    val failure: MigrationFailure? = null,
) {
    /** The version the current step's rows are part-way to. */
    val stepTo: Int get() = if (stepFrom == UNSTAMPED) targets.getValue(stepService) else stepFrom + 1

    /**
     * This marker with the cursor at [cursor], in the same phase. ⚠ The one way a cursor moves:
     * a count is kept only while the cursor is still before its row, so a chunk that commits past
     * the row (it migrated after all) drops the count in the transaction that moves the cursor.
     */
    fun at(cursor: Long?): MigrationMarker =
        copy(cursor = cursor, failure = failure?.takeIf { cursor == null || cursor < it.hash })

    /** This marker at the start of [phase]: no cursor, and no count, which was of a row of the phase left. */
    fun entering(phase: MigrationPhase): MigrationMarker = copy(phase = phase, cursor = null, failure = null)

    /**
     * Why no writer produces this marker, or null. ⚠ The decoder refuses exactly these as
     * malformed and [ReplicaJobSql.writeMigration] refuses to write one, so the two cannot drift:
     * a state that parses and is acted on is one a chunk can have committed.
     */
    fun defect(): String? {
        if (stepFrom < UNSTAMPED || stepService !in targets) return "the step is not one of the targets"
        if (stepFrom != UNSTAMPED && stepFrom >= targets.getValue(stepService)) return "the step is at or past its target"
        // An unstamped service is stamped with no row read, so its step is only ever "ROWS,
        // before the first row". With a phase or a cursor it is a damaged STAMPED step, and
        // read as unstamped the service would be stamped over rows nobody migrated.
        if (stepFrom == UNSTAMPED && (phase != MigrationPhase.ROWS || cursor != null)) return "an unstamped step with a phase or a cursor"
        // The cleanup has no cursor: deleting is idempotent.
        if (phase == MigrationPhase.CLEANUP && cursor != null) return "a cleanup with a cursor"
        val failure = failure ?: return null
        // A count is of a row a step or the sweep read: an unstamped step and the cleanup read none.
        if (stepFrom == UNSTAMPED || !phase.readsRows) return "a count where no row is read"
        // At its limit the job becomes a wipe, in the chunk that would have stored the count.
        if (failure.limit < 2 || failure.count !in 1 until failure.limit) return "a count no writer stores"
        // The counter passed the resumer rule: it declared at least the step's target.
        if (failure.version < targets.getValue(stepService)) return "a count by a build below the target"
        // The row is still ahead: a chunk that passes it drops the count as it moves the cursor.
        if (cursor != null && cursor >= failure.hash) return "a count for a row the cursor has passed"
        return null
    }

    fun encode(): ByteArray = FrameWriter().apply {
        byte(if (failure == null) JOB_MARKER_FORMAT else MIGRATION_COUNTED_FORMAT)
        text(app)
        long(startedSeconds)
        stamps(targets)
        text(stepService)
        int(stepFrom)
        byte(phase.ordinal)
        byte(if (cursor == null) 0 else 1)
        long(cursor ?: 0L)
        failure?.let {
            long(it.hash)
            int(it.version)
            int(it.count)
            int(it.limit)
        }
    }.bytes()

    companion object {
        /** [stepFrom] of a service with no stamp. */
        const val UNSTAMPED: Int = -1

        fun decode(frame: ByteArray): DecodedMarker<MigrationMarker> =
            decodeFrame(frame, JOB_MARKER_FORMAT, MIGRATION_COUNTED_FORMAT) { format ->
                val app = text()
                val started = long()
                val targets = stamps()
                val service = text()
                val from = int()
                val phase = MigrationPhase.entries.getOrNull(byte()) ?: malformed()
                val hasCursor = when (byte()) {
                    0 -> false
                    1 -> true
                    else -> malformed()
                }
                val cursor = long()
                // "No cursor" has one encoding.
                if (!hasCursor && cursor != 0L) malformed()
                // ⚠ The format says whether a count follows, and each state has one encoding: a
                // counted frame always carries a count, and a count in the other format is bytes
                // left over, which is malformed too.
                val failure =
                    if (format == MIGRATION_COUNTED_FORMAT) MigrationFailure(hash = long(), version = int(), count = int(), limit = int())
                    else null
                // ⚠ What no writer produces is damage, though every field parses ([defect]).
                MigrationMarker(app, started, targets, service, from, phase, cursor.takeIf { hasCursor }, failure)
                    .also { if (it.defect() != null) malformed() }
            }
    }
}

/**
 * One registered step of a migration chain, as the chunked migrator needs it: which rows it
 * reads and what it makes of each. The binding builds these from the schema registry's
 * `SchemaMigration` chains (`StoreRowMigrationCodec`, and the step's dropped properties).
 */
interface ReplicaMigrationStep {
    /** The types whose rows this step reads. Empty for a step that only moves the version. */
    val typeIds: Set<String>

    /**
     * The row as the step's `to` version stores it, **with the same hash**, or null when the
     * migration drops it by decision. Called exactly once per stored row: a migration is not
     * idempotent, which is why every processed row is recorded (`migration_done`).
     */
    fun migrate(row: DbEntry): DbEntry?
}

/** The migration chains this build registers. */
fun interface ReplicaMigrationSteps {
    /**
     * The step of [serviceId] from [fromVersion] to `fromVersion + 1`, or null when this build
     * has none: the rows can then not be read, and the store is wiped (§5.3's marker rule).
     */
    fun step(serviceId: String, fromVersion: Int): ReplicaMigrationStep?
}

// ── the frame ──

private class FrameMalformed : RuntimeException()

private fun malformed(): Nothing = throw FrameMalformed()

/** [formats]: the formats this build writes **for this marker**. A frame of any other is a newer build's. */
private inline fun <T> decodeFrame(frame: ByteArray, vararg formats: Int, body: FrameReader.(format: Int) -> T): DecodedMarker<T> {
    if (frame.isEmpty()) return DecodedMarker.Malformed
    val format = frame[0].toInt() and 0xff
    if (format !in formats) return DecodedMarker.OtherFormat(format)
    val reader = FrameReader(frame, 1)
    return try {
        val value = reader.body(format)
        if (reader.exhausted) DecodedMarker.Value(value) else DecodedMarker.Malformed
    } catch (_: FrameMalformed) {
        DecodedMarker.Malformed
    }
}

private class FrameWriter {
    private var out = ByteArray(64)
    private var size = 0

    private fun room(n: Int) {
        if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
    }

    fun byte(value: Int) {
        room(1)
        out[size++] = value.toByte()
    }

    fun int(value: Int) {
        for (shift in 24 downTo 0 step 8) byte(value ushr shift)
    }

    fun long(value: Long) {
        for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt())
    }

    fun text(value: String) {
        val bytes = value.encodeToByteArray()
        int(bytes.size)
        room(bytes.size)
        bytes.copyInto(out, size)
        size += bytes.size
    }

    /** Sorted by id, so one map has one encoding. */
    fun stamps(stamps: Map<String, Int>) {
        int(stamps.size)
        for (id in stamps.keys.sorted()) {
            text(id)
            int(stamps.getValue(id))
        }
    }

    fun bytes(): ByteArray = out.copyOf(size)
}

private class FrameReader(private val frame: ByteArray, private var at: Int) {
    val exhausted: Boolean get() = at == frame.size

    fun byte(): Int {
        if (at >= frame.size) malformed()
        return frame[at++].toInt() and 0xff
    }

    fun int(): Int {
        var value = 0
        repeat(4) { value = (value shl 8) or byte() }
        return value
    }

    fun long(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or byte().toLong() }
        return value
    }

    fun text(): String {
        val length = int()
        if (length < 0 || length > frame.size - at) malformed()
        // ⚠ Strictly: bytes that are not UTF-8, replaced by U+FFFD, would make a damaged frame
        // read as a well-formed one that names a service nobody declares, which is `SKEW`
        // ("update the app") for good, where the truth is damage.
        val text = try {
            frame.decodeToString(at, at + length, throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            malformed()
        }
        at += length
        return text
    }

    fun stamps(): Map<String, Int> {
        val count = int()
        if (count < 0 || count > frame.size) malformed()
        val stamps = LinkedHashMap<String, Int>()
        repeat(count) {
            val id = text()
            val version = int()
            if (version < 0 || stamps.put(id, version) != null) malformed()
        }
        return stamps
    }
}
