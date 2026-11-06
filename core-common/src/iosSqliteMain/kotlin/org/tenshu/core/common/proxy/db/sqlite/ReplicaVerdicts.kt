@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalObjCRefinement::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_CORRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_LOCKED
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_NOTADB
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_READONLY

/** Why a handle stopped serving: reads answer absent and writes throw [ReplicaReplaced] (§6). */
enum class ReplicaFence {
    /**
     * A statement or a `COMMIT` failed, or the store refused a statement (§2.5 rule (a)). In
     * memory only: the next foreground rebuilds.
     */
    COMMIT_FAILED,

    /** `replica/CORRUPT` exists, written by this process or found. Durable, for every process. */
    CORRUPT,

    /** The handle was released through the registry (a quiesce of a job handle, a teardown). */
    RELEASED,

    /**
     * `meta.epoch` is not the one this handle opened on: a wipe or a migration started (or
     * finished) since, this handle's own wipe included. The host releases and opens again (§6).
     */
    REPLACED,
}

/** How one run of a wipe or a migration on a `REPLACEMENT` handle ended (§2.8, §5.1, §5.3). */
sealed interface ReplicaRunOutcome {
    /** No job marker is left: this run, or a sibling's, committed the finish. */
    data object Finished : ReplicaRunOutcome

    /**
     * The run stopped with the marker still there: the gate closed, the handle was released, the
     * caller asked, a chunk failed or ran past the hold ceiling. Every committed chunk stays;
     * the next qualifying open or run goes on from there. With a [MigrationRowFailed] for its
     * [cause], the run counted a migration step's failure on a row, and is not to be repeated
     * before the next foreground.
     */
    data class Stopped(val reason: String, val cause: Throwable? = null) : ReplicaRunOutcome

    /** This build may not resume the job (the resumer rule). Nothing was written by it. */
    data class Skew(val detail: String) : ReplicaRunOutcome

    /** `replica/CORRUPT` exists, found or just written. */
    data class Corrupt(val detail: String) : ReplicaRunOutcome

    /**
     * A migration may be started or resumed only in the foreground (S13). Nothing was written by
     * the transaction that answered it; chunks a run committed before it (a wipe's, or a
     * migration's before the app was backgrounded, Appendix B.11 (146)) stand.
     */
    data object NeedsForeground : ReplicaRunOutcome
}

/**
 * ⚠⚠ The production open or store-less wipe in a process whose registry for the path has no
 * installed protection (Appendix B.13, B.11.4 (1), Q24). Nothing was opened or created. A
 * programming error, never retried: the host attaches and installs before its first open.
 */
@kotlin.native.HiddenFromObjC
class SharedReplicaUnprotected(message: String) : IllegalStateException(message)

/** How a store-less wipe that may hand its chunks to the worker began (Appendix B.13 (178)). */
@kotlin.native.HiddenFromObjC
enum class SharedReplicaWipeStart {
    /** No store, or one never made into a store, or a sibling's wipe finished first. */
    NOTHING_TO_WIPE,

    /** Wiped to its last chunk on the caller. */
    FINISHED,

    /** The first chunk committed (`meta.wipe` written, the epoch moved); the worker runs the rest and tells the outcome. */
    HANDED_OFF,
}

/** How a deletion of an unwipeable store's files ended (Appendix B.13 (179)). */
@kotlin.native.HiddenFromObjC
enum class SharedReplicaDeletion {
    /** Every file of the directory was removed (or there were none). */
    DELETED,

    /** Not deleted, nothing touched: a connection is open on the store (here or in a sibling). */
    BUSY,
}

/**
 * A wipe **started** and did not reach its last chunk (§5.1): `meta.wipe` and the committed
 * chunks are still there, the store is "updating (wipe)" for every process, and any qualifying
 * open finishes the wipe. ⚠⚠ Never to be folded into "not wiped": the caller must not go on to
 * the steps that follow a completed wipe (the custody purge), and reports "not finished".
 */
class WipeNotFinished(val outcome: ReplicaRunOutcome) : org.tenshu.core.common.proxy.db.StoreWipeNotFinished("the wipe did not finish: $outcome")

/**
 * The cause of a [ReplicaRunOutcome.Stopped] whose run **counted** a migration step's failure on
 * a row (Appendix B.3 (41), (53)): the step threw, moved the row to another hash or made a row
 * over `ROW_MAX`, or the stored row did not decode. [attempts] of [limit] are now recorded; the
 * run that would record the [limit]th wipes the store instead.
 *
 * ⚠⚠ **A run that counted has stopped, and its host must not run the job again until the next
 * foreground**: one count per run is what spreads the attempts over launches.
 *
 * ⚠ It names the service, the step and the row's **type**, and its message says no more: never the
 * row's content or key. [cause] is the step's own exception, whose message is the step's to word
 * and may quote the row, so it is kept for a debugger and must not be logged.
 */
class MigrationRowFailed(
    val serviceId: String,
    val fromVersion: Int,
    val typeId: String,
    val attempts: Int,
    val limit: Int,
    cause: Throwable?,
) : RuntimeException(
    "the step of '$serviceId' from v$fromVersion failed on a row of type '$typeId' " +
        "(${cause?.let { it::class.simpleName } ?: "no exception"}): attempt $attempts of $limit",
    cause,
)

/** A store-less wipe that wrote nothing: the store is a newer build's, or is marked corrupt. */
class WipeRefused(val outcome: ReplicaRunOutcome) : RuntimeException("the store was not wiped: $outcome")

/**
 * The store cannot be used right now and nothing was changed. Retryable: the host shows "storage
 * unavailable" and tries again at the next foreground.
 */
/**
 * A row of the store's own bookkeeping is not what this layout writes (absent, or of another
 * storage class). Thrown by a strict read instead of a coerced value; the open reports `CORRUPT`.
 */
internal class ReplicaDamaged(val detail: String) : RuntimeException(detail)

open class StoreUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The `-wal` is past `Wmax − δ` and its reset could not be verified (writer rule 1, §4.2.1). */
class WalResetPending(message: String) : StoreUnavailable(message)

/** The read-only classification could not build the WAL index. Never escalated to read/write. */
class StoreUnclassifiable(message: String, cause: Throwable? = null) : StoreUnavailable(message, cause)

/**
 * SQLite answered the open a corruption code that is not confirmed. Nothing was marked:
 * `replica/CORRUPT` does not exist (Appendix B.3 (45)). Retryable.
 */
open class StoreCorruptionUnconfirmed(message: String, cause: Throwable? = null) : StoreUnavailable(message, cause)

/**
 * ⚠ **This app's confirmation walk is owed** (Q5, Appendix B.8): a corruption code met by this app
 * is being checked in runs of at most `CONFIRM_MAX`, and until the walk ends the app opens no
 * store (the fence held across runs and wakes). Retryable; the surface is "Checking storage…",
 * never an offer to discard (B.8.5). The walk ends in `replica/CORRUPT` (the next open answers
 * `Corrupt`) or clean (the next open judges the store as any other). [stalled]: the walk cannot
 * advance in this process (B.8.3 (115)); the surface says the store could not be checked.
 */
class StoreBeingChecked(message: String, cause: Throwable? = null, val stalled: Boolean = false) : StoreCorruptionUnconfirmed(message, cause)

/** Protected data is unavailable (before first unlock): the open or the access failed for that. */
class ProtectedDataUnavailable(message: String, cause: Throwable? = null) : StoreUnavailable(message, cause)

/**
 * D3 (§2.4, S0b-4 slice 2): `replica/`'s protection class or its backup exclusion could not be set,
 * or did not read back. The store is not handed out without them; a retryable "not ready".
 */
class ReplicaNotProtected(message: String, cause: Throwable? = null) : StoreUnavailable(message, cause)

/** The store could not be put in the mode this design requires (`journal_mode`, page size). */
class StoreUnusable(message: String) : StoreUnavailable(message)

/** The in-process gate is closed: the access was not admitted and touched nothing. */
class ReplicaGateClosed(message: String) : StoreUnavailable(message)

/** The store needs a migration or a wipe, which only a foreground open may start (S13). */
class StoreNeedsForeground(message: String) : RuntimeException(message)

/** A write reached a fenced handle. Thrown before the database is touched. */
class ReplicaReplaced(val fence: ReplicaFence) : org.tenshu.core.common.proxy.db.StoreHandleFenced("the shared store handle is fenced: $fence")

/**
 * A statement or a `COMMIT` failed inside an access. The transaction was rolled back and the
 * handle is fenced [fence] (§2.5 rule (a), §5.2.2).
 */
open class ReplicaAccessFailed(val fence: ReplicaFence, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The store refused a row whose encoded size is over `ROW_MAX`, before SQLite. Rule (a). */
class RowTooLarge(val bytes: Int, val limit: Int) :
    ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "a row of $bytes bytes exceeds ROW_MAX ($limit)")

/** The store refused a statement that would pass `wal + C + h ≤ Wmax`, before SQLite. Rule (a). */
class TransactionBudgetExceeded(val walBytes: Long, val headroomPages: Int) :
    ReplicaAccessFailed(
        ReplicaFence.COMMIT_FAILED,
        "the transaction budget is spent: -wal at $walBytes bytes, statement headroom $headroomPages pages",
    )

/**
 * The hold watchdog (§4.2.1, [ReplicaHoldWatchdog]): the transaction held the write lock past
 * `HOLD_MAX`. Either a statement was interrupted while it ran ([cause] is SQLite's
 * `SQLITE_INTERRUPT`), or the next statement or the `COMMIT` was refused before SQLite. Rolled
 * back; rule (a) for an access, a stopped run for a job's chunk.
 */
open class HoldCeilingExceeded internal constructor(val heldMs: Long, val limitMs: Int, message: String, cause: Throwable?) :
    ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, message, cause) {
    constructor(heldMs: Long, limitMs: Int, cause: Throwable? = null) :
        this(heldMs, limitMs, "the transaction held the write lock for $heldMs ms, past HOLD_MAX ($limitMs ms)", cause)
}

/**
 * ⚠⚠ **The token-expiry interrupt** (Appendix B.15): the protection covering this transaction
 * ended ([why]: the expiry of the last protection, the background entered with no time granted,
 * or a job's own token), so its running statement was interrupted, or its next statement or its
 * `COMMIT` refused before SQLite. A [HoldCeilingExceeded] by kind, so every handler of the watchdog
 * already treats it right: rolled back, rule (a) for an access, a stopped run for a job's chunk, a
 * retryable `StoreUnavailable` for the open, "not reset" for a checkpoint.
 */
class ProtectionExpired(val why: String, heldMs: Long, limitMs: Int, cause: Throwable? = null) :
    HoldCeilingExceeded(heldMs, limitMs, "protection ended ($why) while the transaction held its lock ($heldMs ms): stopped", cause)

/** True when [e] is the token-expiry interrupt, not `HOLD_MAX`. A function of its own (K/N: no type test in a catch's shape). */
internal fun isProtectionExpiry(e: HoldCeilingExceeded): Boolean = e is ProtectionExpired

/** The store refused a growing statement with the used database at `MAX_DB_PAGES`. Rule (a). */
class StoreFull(val usedPages: Long, val limit: Int) :
    ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "the store is full: $usedPages used pages of $limit")

/** Where a SQLite result code was met: the columns of §5.2.2's table that this slice has. */
internal enum class ReplicaPhase {
    /** Open, steps 0–2: the read-only classification. */
    OPEN_CLASSIFY,

    /** Open, steps 2a–3: the read/write connection and its write transaction. */
    OPEN_READ_WRITE,

    /** A statement inside an access. */
    STATEMENT,

    /** The `COMMIT` of an access. */
    COMMIT,

    /** A checkpoint or its rewind write. */
    CHECKPOINT,
}

/** What a result code means in a phase: the cells of §5.2.2's table. */
internal enum class ReplicaVerdict {
    /** Write `replica/CORRUPT` and fence durably. */
    CORRUPT,

    /** Retry: [StoreUnavailable]. */
    RETRY_UNAVAILABLE,

    /** Retry: [StoreUnclassifiable]. */
    RETRY_UNCLASSIFIABLE,

    /** Retry: [ProtectedDataUnavailable]. */
    RETRY_PROTECTED_DATA,

    /** `SQLITE_BUSY` at a `BEGIN`: the slice loop of §4.2. */
    RETRY_SLICE,

    /** Roll back and fence [ReplicaFence.COMMIT_FAILED]. */
    COMMIT_FAILED,

    /** A checkpoint that did not happen: retried at the next trigger. */
    SKIPPED,
}

/**
 * ⚠⚠ §5.2.2's table, and the **only** place a SQLite result code becomes a verdict. Every caller
 * passes the primary code ([SqliteException.primaryCode]); extended codes map through it.
 *
 * Protected data being unavailable is judged first, before the code: while it is, any failure is
 * the phone being locked, and a `CORRUPT` marker written on that evidence would fence a healthy
 * store for good.
 */
internal object ReplicaVerdicts {

    fun of(
        primaryCode: Int,
        phase: ReplicaPhase,
        atBegin: Boolean = false,
        // No default: "is the phone locked" is never assumed to be no.
        protectedDataAvailable: Boolean,
    ): ReplicaVerdict {
        if (!protectedDataAvailable) {
            return when (phase) {
                ReplicaPhase.OPEN_CLASSIFY, ReplicaPhase.OPEN_READ_WRITE -> ReplicaVerdict.RETRY_PROTECTED_DATA
                ReplicaPhase.STATEMENT, ReplicaPhase.COMMIT -> ReplicaVerdict.COMMIT_FAILED
                ReplicaPhase.CHECKPOINT -> ReplicaVerdict.SKIPPED
            }
        }
        return when (primaryCode) {
            SQLITE_CORRUPT, SQLITE_NOTADB -> ReplicaVerdict.CORRUPT

            SQLITE_BUSY, SQLITE_LOCKED -> when (phase) {
                ReplicaPhase.OPEN_CLASSIFY, ReplicaPhase.OPEN_READ_WRITE ->
                    if (atBegin) ReplicaVerdict.RETRY_SLICE else ReplicaVerdict.RETRY_UNAVAILABLE
                ReplicaPhase.STATEMENT, ReplicaPhase.COMMIT -> ReplicaVerdict.COMMIT_FAILED
                ReplicaPhase.CHECKPOINT -> ReplicaVerdict.SKIPPED
            }

            SQLITE_READONLY -> when (phase) {
                ReplicaPhase.OPEN_CLASSIFY -> ReplicaVerdict.RETRY_UNCLASSIFIABLE
                else -> other(phase)
            }

            // SQLITE_IOERR, SQLITE_FULL, SQLITE_NOMEM, SQLITE_CANTOPEN, SQLITE_INTERRUPT and
            // SQLITE_PROTOCOL have a row of their own in the table and every other code (a
            // programming error) another, with the same cells; the second is only logged louder.
            else -> other(phase)
        }
    }

    private fun other(phase: ReplicaPhase): ReplicaVerdict = when (phase) {
        ReplicaPhase.OPEN_CLASSIFY, ReplicaPhase.OPEN_READ_WRITE -> ReplicaVerdict.RETRY_UNAVAILABLE
        ReplicaPhase.STATEMENT, ReplicaPhase.COMMIT -> ReplicaVerdict.COMMIT_FAILED
        ReplicaPhase.CHECKPOINT -> ReplicaVerdict.SKIPPED
    }
}
