@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tenshu.core.common.proxy.db.DatabaseLock
import org.tenshu.core.common.proxy.db.DbEntryCodec
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore.DbEntry
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.WipeableObjectStore
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_LOCKED
import platform.posix.usleep
import kotlin.time.TimeSource

/**
 * The iOS shared replica's object store on SQLite: one logical handle on
 * `<group container>/replica/objects.db` (`docs/proposals/ios-shared-store-sqlite.md`).
 *
 * Bound by every first-party iOS app and by Control, in every build (`SharedReplicaBinding`'s
 * `sharedReplicaStoreModule`, `:tenshu-sdk-ios-core`). This is the store core: the open, the row
 * operations, the WAL ceiling, the wipe. It has no row cache and no catch-up (§3.3). It does read
 * `meta.epoch` at every outermost access, so a sibling's wipe or migration fences it
 * ([ReplicaFence.REPLACED]) before it can read a row of a store that is being replaced.
 *
 * **Every outermost access is one transaction** (§2.5, S2): [access] takes the registry's
 * in-process lock, passes the gate, runs `BEGIN IMMEDIATE`, and commits at its exit; nested
 * entries join. [databaseLock] is that same entry, so `Database.transaction { }` and a caller
 * that reaches the store directly both get exactly one transaction.
 *
 * **What ends an access otherwise:**
 * - An exception thrown by the caller's own code: what was written is **committed** and the
 *   exception propagates, as on every platform (D4).
 * - ⚠⚠ Any statement SQLite fails, any statement the store refuses ([RowTooLarge],
 *   [TransactionBudgetExceeded], [StoreFull]) and a failed `COMMIT`: rolled back at once, the
 *   handle **fenced** and released, and [ReplicaAccessFailed] thrown (rule (a)). Memory above the
 *   store may already be ahead of the database, so nothing short of a rebuild is safe.
 *
 * **Once fenced** ([fencedBy]): reads answer absent, writes throw [ReplicaReplaced] before
 * touching the database, and the handle never asks for a connection again.
 *
 * The row bytes are [DbEntryCodec]'s, the JVM store's (and the retired iOS file store's), so nothing here
 * reshapes a stored row.
 */
class SharedReplicaStore internal constructor(
    private val registry: ReplicaHandles,
    private val handle: ReplicaHandle,
    private val files: ReplicaFiles,
    private val marker: ReplicaCorruptMarker,
    private val appName: String,
    /** The build that opened it: what a corruption code's walk judges the store against (Appendix B.10). */
    private val build: ReplicaBuild,
    private val protectedDataAvailable: () -> Boolean,
    /** `meta.epoch` as the open read it. */
    val epoch: Long,
    seq: Long,
    /** Makes the job that drives a wipe's later chunks, on a `REPLACEMENT` handle (§5.1). */
    private val replacement: () -> ReplicaReplacement,
) : ObjectStore, WipeableObjectStore {

    private val limits: ReplicaLimits get() = registry.limits
    private val walProbe = ReplicaWalProbe(files, registry.databasePath + "-wal") { handle.hasConnection }
    internal val checkpointer = ReplicaCheckpointer(registry, marker, walProbe, protectedDataAvailable)
    private val confirmation = ReplicaCorruptionConfirm(registry, files, marker, appName, build, protectedDataAvailable)

    // List per hash and fire-on-present, as every other store (DatabaseImpl's array diff depends
    // on the immediate fire).
    private val listeners = HashMap<Long, MutableList<(DbEntry) -> Unit>>()

    /**
     * The fence as a flow, for the host (§6's `replaced`, Appendix B.16 (212)). ⚠ Declared before
     * [fence], whose accessors read it, and before `init` (K/N).
     */
    private val fenceState = MutableStateFlow<ReplicaFence?>(null)

    private var fence: ReplicaFence?
        get() = fenceState.value
        set(value) {
            fenceState.value = value
        }
    private var depth = 0
    private var admitted = false
    private var seqBumped = false

    /** The hold watchdog's watch on the open transaction (§4.2.1, Appendix B.9): armed at `BEGIN`, gone at `COMMIT` or the rollback. */
    private var hold: ReplicaHold? = null

    /** Set by a wipe's first chunk: the fence its handle takes once that chunk has committed. */
    private var fenceAtCommit: ReplicaFence? = null

    /** `meta.seq` as of this handle's last commit (S4). */
    var seq: Long = seq
        private set

    /** Why this handle stopped serving, or null while it serves. */
    val fencedBy: ReplicaFence? get() = fence

    /**
     * ⚠⚠ **§6's signal** (Appendix B.16 (212)): null while the handle serves, then the fence it
     * took, **once** (a handle is never unfenced; only `COMMIT_FAILED` may still become `CORRUPT`
     * when its marker is made durable). The host collects it to stop and rebuild its graph. An
     * emission never runs the collector inline: whoever fenced (often under the registry's lock)
     * only sets the value.
     */
    val fenced: StateFlow<ReplicaFence?> get() = fenceState

    /** The `-wal`'s physical size now. */
    internal val walBytes: Long
        get() {
            registry.lock.lock()
            try {
                return walProbe.sizeBytes()
            } finally {
                registry.lock.unlock()
            }
        }

    /** Test seam: commits after which the `-wal` was found past `Wmax + δ`. Never expected. */
    internal var ceilingBreaches: Int = 0
        private set

    /** Test seam: accesses whose exit found a transaction still open. Never expected. */
    internal var transactionsLeftOpen: Int = 0
        private set

    /** Fault injection: thrown from the epoch read of an access. Null in production. */
    internal var epochFault: (() -> Unit)? = null

    /**
     * Fault injection: called with the SQL of every statement of an access before it runs, and
     * with `COMMIT` before the access's commit; a throw is that statement's failure. Null in production.
     */
    internal var statementFault: ((String) -> Unit)? = null

    /** Test seam: handed the job that drives a wipe's later chunks, before it runs. Null in production. */
    internal var onReplacement: ((ReplicaReplacement) -> Unit)? = null

    /** Test seam: checkpoints that ran to a verified reset. */
    internal var verifiedResets: Int = 0
        private set

    /**
     * A commit that wrote has happened since the last 30 s foreground trigger (§4.2.1, Appendix
     * B.12 (159)). Read and cleared under the registry's lock.
     */
    private var committedSinceTick = false

    /** Test seam: checkpoints the 30 s foreground trigger ran. */
    internal var foregroundCheckpoints: Int = 0
        private set

    /** The `DatabaseLock` to bind beside this store: its outermost entry is the transaction. */
    val databaseLock: DatabaseLock = object : DatabaseLock {
        override fun <R> withLock(block: () -> R): R = access("withLock", block)
    }

    init {
        handle.onClosed = { walProbe.close() }
        handle.onReleased = {
            if (fence == null) fence = ReplicaFence.RELEASED
            marker.close()
        }
        handle.onForegroundTick = ::foregroundCheckpoint
    }

    /** Releases the handle through the registry: a teardown. The store is fenced from then on. */
    fun close() {
        registry.release(handle)
    }

    // ── the access ──

    internal fun <R> access(label: String, block: () -> R): R {
        registry.lock.lock()
        try {
            if (depth > 0) {
                depth++
                try {
                    return block()
                } finally {
                    depth--
                }
            }
            if (fence == null) begin(label)
            depth = 1
            var failure: Throwable? = null
            try {
                return block()
            } catch (t: Throwable) {
                failure = t
                throw t
            } finally {
                depth = 0
                freshCandidates.clear()
                end(label, failure)
            }
        } finally {
            registry.lock.unlock()
        }
    }

    /**
     * Opens the transaction of an outermost access (§3.1, §4.2, §4.2.1 writer rule 1), or fences
     * the handle, or throws with nothing begun. In order, once `BEGIN IMMEDIATE` has succeeded:
     * the corruption marker; then the `-wal`'s size, and past `Wmax − δ` the transaction is rolled
     * back and the ceiling checkpoint runs before another is begun.
     */
    private fun begin(label: String) {
        var resets = 0
        while (true) {
            val connection = try {
                registry.acquire(handle, label, waitForProtection = true) { fence == null }
            } catch (e: SqliteException) {
                throw beginFailed(e)
            } ?: return // fenced by another thread while this one waited on a sibling
            admitted = true
            // ⚠ HOLD_MAX starts HERE, with the write lock just obtained: the marker lookup and the
            // -wal probe below run under it (as a job's chunk counts them).
            hold = registry.holdWatchdog.arm(label, connection, limits.holdMaxMs)
            try {
                if (fencedByMarker()) return
                val wal = walProbe.sizeBytes()
                if (wal <= limits.beginCeilingBytes) {
                    seqBumped = false
                    fenceAtCommit = null
                    fenceIfReplaced(label)
                    return
                }
                // A checkpoint cannot run inside a transaction, and RESTART needs the writer lock.
                // ⚠ If the ROLLBACK fails this connection may still hold that lock: no checkpoint
                // is attempted over it, the handle is fenced (below).
                rollbackOrFence(label)
                val reset = checkpoint(SqliteCheckpointMode.RESTART)
                if (fence != null) return
                if (!reset || ++resets >= MAX_RESETS_PER_ACCESS) {
                    throw WalResetPending(
                        "[replica] $label: the -wal is at $wal bytes, past ${limits.beginCeilingBytes}, and its reset is not verified"
                    )
                }
            } catch (e: SqliteException) {
                // The epoch read failed: rule (a), as for any statement of an access. ⚠ The
                // fence rolls back and closes the connection, which are touches of the database:
                // the gate is left only once they are done.
                val failure = try {
                    failed(e, ReplicaPhase.STATEMENT, "the epoch read of $label")
                } finally {
                    leaveGate()
                }
                throw failure
            } catch (e: ReplicaFileException) {
                // The marker could not be looked for, or the -wal not measured. Neither is read
                // as "no marker" or "an empty log": nothing is begun, and the caller retries.
                try {
                    rollbackOrFence(label)
                } finally {
                    leaveGate()
                }
                throw probeFailed(label, e)
            } catch (t: Throwable) {
                leaveGate()
                throw t
            }
            leaveGate()
        }
    }

    /**
     * §3.1 step 2, without its `data_version` fast path: `meta.epoch` is read under the write
     * lock at every outermost access, and one that is not the open's fences the handle
     * [ReplicaFence.REPLACED]. Every start of a wipe or a migration moves the epoch in the
     * transaction that writes its marker, and every finish moves it again, so a handle whose
     * epoch still matches is on a store that no job has touched since its open.
     */
    private fun fenceIfReplaced(label: String) {
        val watch = hold
        val read = handle.prepared(EPOCH_SQL)
        try {
            watch?.enterStatement()
        } catch (e: HoldCeilingExceeded) {
            refuse(e)
        }
        // ⚠⚠ The statement is marked ended BEFORE any failure is handled: the handling rolls back,
        // and a watch still counting a statement could interrupt that ROLLBACK (B.9 (128)).
        var failure: SqliteException? = null
        val current = try {
            epochFault?.invoke()
            if (read.step() && read.getText(1) == "integer") read.getLong(0) else null
        } catch (e: SqliteException) {
            failure = e
            null
        } finally {
            watch?.exitStatement()
            read.reset()
        }
        failure?.let { e ->
            heldPastTheCeiling(watch, e)?.let(::refuse)
            throw e
        }
        if (current == epoch) return
        if (current == null) println("[replica] $label: meta.epoch is missing or not an integer; the handle is fenced")
        fenceNow(if (current == null) ReplicaFence.COMMIT_FAILED else ReplicaFence.REPLACED)
    }

    private fun probeFailed(label: String, e: ReplicaFileException): StoreUnavailable =
        if (protectedDataAvailable()) StoreUnavailable("[replica] $label: ${e.message}", e)
        else ProtectedDataUnavailable("[replica] protected data is unavailable", e)

    /** A connection or a `BEGIN` failed before anything was started: a retry, or corruption. */
    private fun beginFailed(e: SqliteException): Throwable =
        when (ReplicaVerdicts.of(e.primaryCode, ReplicaPhase.OPEN_READ_WRITE, protectedDataAvailable = protectedDataAvailable())) {
            ReplicaVerdict.CORRUPT -> corruptionCode(e, "the store could not be reached")
            ReplicaVerdict.RETRY_PROTECTED_DATA -> ProtectedDataUnavailable("[replica] protected data is unavailable", e)
            else -> StoreUnavailable("[replica] the store could not be reached: ${e.message}", e)
        }

    /** Commits the outermost access, or finishes a fenced one. */
    private fun end(label: String, blockFailure: Throwable?) {
        try {
            if (fence != null) {
                releaseFenced()
                return
            }
            val connection = handle.connection
            if (connection == null) {
                // An access that began a transaction and has no connection to commit it on did
                // not commit: it must never return as if it had.
                println("[replica] $label ended with no connection; the handle is fenced")
                fenceNow(ReplicaFence.COMMIT_FAILED)
                val failed = ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "[replica] $label: the connection was closed under the access")
                blockFailure?.let(failed::addSuppressed)
                throw failed
            }
            // ⚠⚠ Refused if the hold watchdog has fired; otherwise disarmed before the COMMIT is
            // issued, so its sync is never interrupted (§4.2.1).
            try {
                hold?.beforeCommit()
            } catch (e: HoldCeilingExceeded) {
                println("[replica] refused: the COMMIT of $label: ${e.message}; the handle is fenced")
                fenceNow(ReplicaFence.COMMIT_FAILED)
                blockFailure?.let(e::addSuppressed)
                throw e
            }
            hold = null
            try {
                val commit = handle.prepared("COMMIT")
                try {
                    statementFault?.invoke("COMMIT")
                    commit.step()
                } finally {
                    commit.reset()
                }
            } catch (e: SqliteException) {
                val failed = failed(e, ReplicaPhase.COMMIT, "COMMIT of $label")
                releaseFenced()
                blockFailure?.let(failed::addSuppressed)
                throw failed
            }
            if (seqBumped) {
                seq++
                committedSinceTick = true
            }
            fenceAtCommit?.let { reason ->
                // A wipe's first chunk has committed: this handle never serves again, and it
                // says so before the in-process lock is released (§5.1). Nothing is rolled back.
                fenceAtCommit = null
                fence = reason
                listeners.clear()
                releaseFenced()
                return
            }
            if (!connection.inAutocommit()) {
                // A statement or a transaction outlived the access (§2.5): a bug in this class.
                transactionsLeftOpen++
                println("[replica] transaction left open: $label")
                rollbackOrFence(label)
            }
            afterCommit(label)
        } finally {
            // Every way out of a transaction disarms its watch (a fenced one rolled back already).
            disarmHold()
            leaveGate()
        }
    }

    private fun disarmHold() {
        hold?.disarm()
        hold = null
    }

    /**
     * [HoldCeilingExceeded] when [e] is the `SQLITE_INTERRUPT` the hold watchdog caused (§4.2.1),
     * else null: that statement failed because the transaction held the lock past `HOLD_MAX`.
     */
    private fun heldPastTheCeiling(watch: ReplicaHold?, e: SqliteException): HoldCeilingExceeded? =
        if (watch != null && e.primaryCode == SQLITE_INTERRUPT && watch.interruptedAStatement) watch.exceededError(e) else null

    private fun leaveGate() {
        if (admitted) {
            admitted = false
            registry.gate.leave()
        }
    }

    /** Belt and braces on the ceiling, then the opportunistic checkpoint past `Wmax/4`. */
    private fun afterCommit(label: String) {
        val wal = try {
            walProbe.sizeBytes()
        } catch (e: ReplicaFileException) {
            // The commit stands; there is nothing left to refuse. The size is not assumed small:
            // no checkpoint is decided on it, and the next access measures again before it begins.
            println("[replica] the -wal could not be measured after $label: ${e.message}")
            return
        }
        if (wal > limits.wmaxBytes + limits.deltaBytes) {
            ceilingBreaches++
            println("[replica] WAL ceiling exceeded: $label left the -wal at $wal bytes, past ${limits.wmaxBytes + limits.deltaBytes}")
        }
        if (wal > limits.journalSizeLimitBytes) {
            try {
                checkpoint(SqliteCheckpointMode.PASSIVE)
            } catch (e: ReplicaFileException) {
                println("[replica] the opportunistic checkpoint after $label was skipped: ${e.message}")
            }
        }
    }

    /**
     * ⚠⚠ §4.2.1's second opportunistic trigger, **every 30 s while foregrounded, if anything was
     * committed since** (Appendix B.12 (159)): one `PASSIVE` checkpoint, in an admission of its
     * own, under the registry's lock, as `afterCommit`'s. The UIKit adapter's timer asks it through
     * [ReplicaProtection.foregroundCheckpointTick], on the worker's queue. Nothing happens (false)
     * when the handle is fenced, inside an access of this thread, outside the foreground (the
     * protection's state, never the platform: B.11 (153)), with nothing written since the last
     * tick, or at a closed gate. The "since" is cleared by a tick that runs, whatever the
     * checkpoint's outcome: a `PASSIVE` a reader kept short is left to the next commit's
     * `Wmax/4` trigger and, at the ceiling, to the `RESTART`.
     */
    internal fun foregroundCheckpoint(): Boolean {
        registry.lock.lock()
        try {
            if (fence != null || depth > 0 || !committedSinceTick) return false
            if (!registry.gate.inForeground()) return false
            if (!registry.gate.enter("foreground-checkpoint:$appName")) return false
            // ⚠ Set on the worker's thread, not an access's: safe because every reader and writer
            // of `admitted` (each access, `checkpoint`'s confirmation) holds the registry's lock,
            // as this does, and `depth == 0` above means no access of this handle is open.
            admitted = true
            committedSinceTick = false
            try {
                foregroundCheckpoints++
                checkpoint(SqliteCheckpointMode.PASSIVE)
            } catch (e: ReplicaFileException) {
                println("[replica] the 30 s foreground checkpoint of $appName was skipped: ${e.message}")
            } finally {
                leaveGate()
            }
            return true
        } finally {
            registry.lock.unlock()
        }
    }

    // ── fencing ──

    /** Fences the handle and rolls back at once, so the write lock is not held a moment longer. */
    private fun fenceNow(reason: ReplicaFence) {
        if (fence == null) fence = reason
        rollbackQuietly()
        if (depth == 0) releaseFenced()
    }

    /** Fault injection for the rollback's tests: thrown from inside a `ROLLBACK`. Null in production. */
    internal var rollbackFault: (() -> Unit)? = null

    /** True when the connection is known to hold no transaction afterwards. */
    private fun rollback(): Boolean {
        // ⚠⚠ Disarmed BEFORE the ROLLBACK is issued: the watchdog must never interrupt it, or the
        // rollback fails and the write lock stays held (B.9 (128)).
        disarmHold()
        val connection = handle.connection ?: return true
        return try {
            rollbackFault?.invoke()
            if (!connection.inAutocommit()) connection.exec("ROLLBACK")
            true
        } catch (e: SqliteException) {
            println("[replica] ROLLBACK failed: ${e.message}")
            false
        }
    }

    /**
     * ⚠ Only for a handle that is being fenced: a failure is logged and left to the release that
     * follows at once, which rolls back again as it closes the connection and latches the process
     * if that fails too (the registry's rule). Nothing may go on to use the connection.
     */
    private fun rollbackQuietly() {
        rollback()
    }

    /**
     * A rollback after which the access means to go on (a checkpoint, a retry by the caller). If
     * it fails, the connection may still hold its transaction and the write lock, so nothing goes
     * on: the handle is fenced `COMMIT_FAILED` (which releases it and closes the connection, or
     * latches the process) and the access fails. Never a retryable answer on an unknown connection.
     */
    /**
     * The marker, for an access or a checkpoint: true when it is there, with the handle fenced.
     * `CORRUPT` only for a marker this process has made durable; one it could not make durable
     * fences `COMMIT_FAILED`, in memory, exactly as a marker it could not write does
     * ([ReplicaCorruptionConfirm]). Either way nothing more is read or written through this handle.
     */
    private fun fencedByMarker(): Boolean = when (marker.state()) {
        ReplicaMarkerState.ABSENT -> false
        ReplicaMarkerState.DURABLE -> {
            fenceNow(ReplicaFence.CORRUPT)
            true
        }
        ReplicaMarkerState.NOT_DURABLE -> {
            fenceNow(ReplicaFence.COMMIT_FAILED)
            true
        }
    }

    private fun rollbackOrFence(label: String) {
        if (rollback()) return
        println("[replica] $label: the ROLLBACK failed; the handle is fenced")
        fenceNow(ReplicaFence.COMMIT_FAILED)
        throw ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "[replica] $label: a ROLLBACK failed, the connection's state is unknown")
    }

    private fun releaseFenced() {
        try {
            registry.release(handle)
        } catch (_: StoreUnavailable) {
            // The registry is latched unusable and has said so; the handle stays registered.
        }
    }

    /**
     * SQLite answered this handle's own connection a corruption code (the connection, `BEGIN`, a
     * statement, `COMMIT`). ⚠⚠ One answer does not write the permanent marker (B.3 (45)):
     *
     * 1. the handle is **fenced at once**, in memory, as rule (a) fences it for any failed
     *    statement: nothing more is read or written through it, whatever follows;
     * 2. [ReplicaCorruptionConfirm] rolls back and closes its connection, then reads the store a
     *    second time on a new one;
     * 3. only if that reading agrees is `replica/CORRUPT` written and the fence
     *    [ReplicaFence.CORRUPT]. Otherwise the fence stays [ReplicaFence.COMMIT_FAILED], which
     *    is in memory only: the host rebuilds, and the next open judges the store from the start.
     *
     * Either way this handle never serves again, and the access fails.
     */
    private fun corruptionCode(e: SqliteException, what: String): ReplicaAccessFailed {
        if (fence == null) fence = ReplicaFence.COMMIT_FAILED
        // The confirmation rolls back and closes this connection: nothing may interrupt that (B.9 (128)).
        disarmHold()
        val outcome = confirmation.run(e.message.orEmpty(), failing = handle, alreadyAdmitted = admitted)
        val message = when (outcome) {
            is ReplicaCorruptionConfirmation.Confirmed -> {
                if (fence == ReplicaFence.COMMIT_FAILED) fence = ReplicaFence.CORRUPT
                "[replica] $what: the store is corrupt: ${e.message}"
            }
            is ReplicaCorruptionConfirmation.Unconfirmed ->
                "[replica] $what: SQLite answered a corruption code that was not confirmed (${outcome.reason}): ${e.message}"
        }
        // The confirmation closed the connection; a close that failed is tried again by the release.
        if (depth == 0) releaseFenced()
        return ReplicaAccessFailed(fence ?: ReplicaFence.COMMIT_FAILED, message, e)
    }

    /** §5.2.2 for a statement or a `COMMIT` inside an access: fence, then say why. */
    private fun failed(e: SqliteException, phase: ReplicaPhase, what: String): ReplicaAccessFailed {
        when (ReplicaVerdicts.of(e.primaryCode, phase, protectedDataAvailable = protectedDataAvailable())) {
            ReplicaVerdict.CORRUPT -> return corruptionCode(e, "$what failed")
            else -> {
                println("[replica] $what failed (rc=${e.resultCode}): ${e.message}; the handle is fenced")
                fenceNow(ReplicaFence.COMMIT_FAILED)
            }
        }
        return ReplicaAccessFailed(fence ?: ReplicaFence.COMMIT_FAILED, "[replica] $what failed: ${e.message}", e)
    }

    /** A statement the store itself refuses, before SQLite: rule (a) all the same. */
    private fun refuse(refusal: ReplicaAccessFailed): Nothing {
        println("[replica] refused: ${refusal.message}; the handle is fenced")
        fenceNow(ReplicaFence.COMMIT_FAILED)
        throw refusal
    }

    private fun requireServing() {
        fence?.let { throw ReplicaReplaced(it) }
    }

    // ── statements ──

    /**
     * Runs one statement of an access. It is reset before this returns, so no statement outlives
     * the access (§2.5); any SQLite error from it is rule (a).
     */
    private inline fun <T> statement(sql: String, body: (SqliteStatement) -> T): T {
        // ⚠⚠ The hold watchdog (§4.2.1): a statement reached after HOLD_MAX is refused before
        // SQLite, and one running when it fires is interrupted. Either way rule (a).
        val watch = hold
        val prepared = try {
            handle.prepared(sql)
        } catch (e: SqliteException) {
            throw failed(e, ReplicaPhase.STATEMENT, sql)
        }
        try {
            watch?.enterStatement()
        } catch (e: HoldCeilingExceeded) {
            refuse(e)
        }
        // ⚠⚠ The statement is marked ended (and reset: a corruption code closes this connection,
        // statements and all) BEFORE its failure is handled, since the handling rolls back and a
        // watch still counting a statement could interrupt that ROLLBACK (B.9 (128)).
        val failure: SqliteException = try {
            statementFault?.invoke(sql)
            return body(prepared)
        } catch (e: SqliteException) {
            e
        } finally {
            watch?.exitStatement()
            prepared.reset()
        }
        heldPastTheCeiling(watch, failure)?.let(::refuse)
        throw failed(failure, ReplicaPhase.STATEMENT, sql)
    }

    /**
     * The measured budget (§4.2.1 item 2): admits a write statement needing [headroomPages] only
     * if `wal + C + h ≤ Wmax`, and moves `meta.seq` before the access's first write to `objects`
     * (S4), under the same check.
     */
    private fun admit(headroomPages: Int) {
        if (!seqBumped) {
            requireFits(limits.statementPages)
            statement(BUMP_SEQ) { it.step() }
            seqBumped = true
        }
        requireFits(headroomPages)
    }

    private fun requireFits(headroomPages: Int) {
        val wal = try {
            walProbe.sizeBytes()
        } catch (e: ReplicaFileException) {
            // Unmeasured is not "fits": the write is refused, by rule (a) like any other refusal.
            refuse(ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "[replica] the -wal could not be measured: ${e.message}", e))
        }
        if (!limits.fits(wal, headroomPages)) refuse(TransactionBudgetExceeded(wal, headroomPages))
    }

    /** `used + δ ≤ MAX_DB_PAGES`, asked only of a statement that grows the store. */
    private fun requireRoom() {
        // A pragma that answers nothing is not "0 pages used".
        fun pragma(sql: String): Long = statement(sql) { if (it.step()) it.getLong(0) else -1L }.also {
            if (it < 0) refuse(ReplicaAccessFailed(ReplicaFence.COMMIT_FAILED, "[replica] $sql returned no row"))
        }
        val pages = pragma("PRAGMA page_count")
        val free = pragma("PRAGMA freelist_count")
        val used = pages - free
        if (used + limits.deltaPages > limits.maxDbPages) refuse(StoreFull(used, limits.maxDbPages))
    }

    // ── ObjectStore ──

    override fun storeObject(dbEntry: DbEntry) = access("storeObject") {
        requireServing()
        require(!ReplicaListSegments.usesReservedNames(dbEntry)) { "a row may not use the store's reserved list-segment names" }
        val hash = dbEntry.dataIdHash
        val existing = storedRow(hash)
        if (existing?.type == ReplicaListSegments.SEGMENT_TYPE) refuse(SegmentHashCollision(hash))
        // A list's chain, read only when the row there is a list (B.19 (331)).
        val read = if (existing?.type == TList.ID) chainForChange(hash) else null
        val chain = read?.takeUnless { it.damaged }?.segments
        // ⚠ A damaged chain is laid out afresh over what can still be reached (B.19.9 (346)).
        val stale = read?.takeIf { it.damaged }?.segments?.drop(1).orEmpty()
        val linked = chain != null && chain.size > 1
        val plain = ReplicaListSegments.isPlainList(dbEntry)
        // ⚠⚠ Segments only in a store that holds their stamp (B.19.9 (343)): one opened by a build
        // that declares the format, which the open judged against it.
        val segmented = plain && segmentsAllowed && dbEntry.fields.size > limits.listSegmentMembers
        val deletes = stale.map { ReplicaListSegments.Op.Delete(it.hash, it.payloadBytes) }
        when {
            plain && (segmented || linked || stale.isNotEmpty()) -> writeChain(
                hash,
                read?.segments?.size ?: 0,
                ReplicaListSegments.plan(hash, chain.orEmpty(), dbEntry.fields, perSegment) {
                    freshSegmentHash(hash, read?.segments.orEmpty())
                }.map { op ->
                    // A row of another type, or a damaged head, was there: the head replaces it, and the budget counts it.
                    if (existing != null && chain == null && op is ReplicaListSegments.Op.Write && op.hash == hash) {
                        ReplicaListSegments.Op.Write(op.entry, existing.payloadBytes)
                    } else {
                        op
                    }
                } + deletes,
            )
            // Any other row over a list of several segments: the row, and the segments deleted.
            linked || stale.isNotEmpty() -> writeChain(
                hash,
                read!!.segments.size,
                listOf(ReplicaListSegments.Op.Write(dbEntry, read.segments[0].payloadBytes)) +
                    (chain?.drop(1)?.map { ReplicaListSegments.Op.Delete(it.hash, it.payloadBytes) } ?: deletes),
            )
            else -> writeRow(dbEntry, existing?.payloadBytes?.coerceAtLeast(0) ?: 0)
        }
        // Only once SQLite has taken the row (§2.6), and always the logical row.
        listeners[hash]?.toList()?.forEach { it(dbEntry) }
        Unit
    }

    /**
     * Whether this handle may write a list in segments (B.19.9 (343)): its build declares the
     * format, so the open that handed it out judged the store compatible **with the stamp**. A
     * handle of a build without it writes every list as one row, as every store before B.19.
     */
    private val segmentsAllowed: Boolean = build.declaresListSegments()

    /** `SEG`, as large as one segment within `ROW_MAX` can be when a test shrinks the row limit. */
    private val perSegment: Int get() = limits.listSegmentMembers

    /** One row, as every store writes it: the path of every row but a list of several segments. */
    private fun writeRow(dbEntry: DbEntry, stored: Int) {
        val encoded = DbEntryCodec.encode(dbEntry)
        if (encoded.size > limits.rowMaxBytes) refuse(RowTooLarge(encoded.size, limits.rowMaxBytes))
        val payload = encoded.size + dbEntry.type.encodeToByteArray().size + ROW_HEADER_BYTES
        if (payload > stored) requireRoom()
        admit(limits.headroomPages(payload, stored))
        upsert(dbEntry, encoded)
    }

    private fun upsert(dbEntry: DbEntry, encoded: ByteArray) {
        statement(UPSERT_SQL) { upsert ->
            upsert.bindLong(1, dbEntry.dataIdHash)
            upsert.bindText(2, dbEntry.type)
            upsert.bindBlob(3, encoded)
            upsert.step()
            // The statement is kept prepared: do not let it keep a copy of the row too. (A
            // parameter can only be rebound once the statement is reset.)
            upsert.reset()
            upsert.bindNull(3)
        }
    }

    /**
     * ⚠⚠ The statements of one list write (B.19 (338)), all inside this access's one transaction,
     * so a failure anywhere rolls back every one of them and no torn chain is ever committed.
     * Admitted as a whole before the first: refused with [ListRewriteTooLarge] when even a log
     * just reset could not take it, with [TransactionBudgetExceeded] when this one cannot now.
     * Then every statement is admitted on its own as any is (`G`), and `StoreFull` asked of each
     * that grows a row.
     */
    private fun writeChain(head: Long, storedSegments: Int, ops: List<ReplicaListSegments.Op>) {
        if (ops.isEmpty()) return // the same list: nothing to write, and `seq` does not move
        val encodings = ops.map { op -> if (op is ReplicaListSegments.Op.Write) DbEntryCodec.encode(op.entry) else null }
        val rows = ops.mapIndexed { index, op ->
            when (op) {
                is ReplicaListSegments.Op.Write -> {
                    val encoded = encodings[index]!!
                    if (encoded.size > limits.rowMaxBytes) refuse(RowTooLarge(encoded.size, limits.rowMaxBytes))
                    payloadOf(op.entry.type, encoded) to op.oldPayloadBytes.coerceAtLeast(0)
                }
                is ReplicaListSegments.Op.Delete -> 0 to op.oldPayloadBytes
            }
        }
        val predicted = limits.listWritePages(rows)
        if (predicted > limits.resetLogRoomPages) {
            // ⚠ Not a fence (B.19.9 (345)): nothing of the list is written, and a fence would take
            // the store down at every attempt. Logged with what Q0 (y) needs to see it in the field.
            println("[replica] refused: a rewrite of the list at $head ($storedSegments segments stored, ${ops.size} rows) needs $predicted pages, past ${limits.resetLogRoomPages}")
            val refused = ListRewriteTooLarge(head, storedSegments, predicted, limits.resetLogRoomPages)
            // ⚠⚠ Fence-free only while the access has written nothing (B.19.10 (349)): then its
            // commit is its rollback. Once it has written (a transaction's entity rows before their
            // list), committing would keep them without the list: rule (a), as before.
            if (seqBumped) {
                fenceNow(ReplicaFence.COMMIT_FAILED)
                println("[replica] the access had already written: the handle is fenced")
            }
            throw refused
        }
        // ⚠⚠ The access's first write, and the log cannot take it now: reset the log first, rather
        // than fence the handle for a write that fits a reset log (B.19.9 (344)).
        if (!seqBumped && !fitsNow(predicted)) resetTheLogBeforeTheFirstWrite(predicted)
        admit(predicted)
        ops.forEachIndexed { index, op ->
            val (payload, stored) = rows[index]
            when (op) {
                is ReplicaListSegments.Op.Write -> {
                    if (payload > stored) requireRoom()
                    requireFits(limits.headroomPages(payload, stored))
                    upsert(op.entry, encodings[index]!!)
                }
                is ReplicaListSegments.Op.Delete -> {
                    requireFits(limits.headroomPages(0, stored))
                    statement(DELETE_SQL) { delete ->
                        delete.bindLong(1, op.hash)
                        delete.step()
                    }
                }
            }
        }
        listWrites++
        listStatements += ops.size
    }

    private fun fitsNow(pages: Int): Boolean = try {
        limits.fits(walProbe.sizeBytes(), pages)
    } catch (e: ReplicaFileException) {
        true // unmeasured: left to admit(), which refuses it as rule (a) says
    }

    private fun storedSeq(): Long = statement(SEQ_SQL) { if (it.step()) it.getLong(0) else -1L }

    /**
     * ⚠⚠ B.19.9 (344): the access has written nothing, and its first write is a list whose
     * prediction fits a reset log but not the `-wal` as it stands. So the (read-only) transaction
     * is rolled back, the ceiling checkpoint runs, and a new one is begun, exactly as writer rule 1
     * does before an access (`begin`), instead of refusing with `TransactionBudgetExceeded`,
     * which fences the handle.
     *
     * ⚠ **Only if no sibling committed in between**: the caller read the list (and maybe more) in
     * the first transaction, and computed the write from it. `meta.seq` moves with every commit
     * that writes `objects` (S4), so a new transaction that reads the same `seq` sees the same
     * rows; one that does not is refused, rule (a), as the write's refusal would have been. A
     * reset that cannot be had, or a new transaction that cannot be begun, fences the handle too:
     * the access has no transaction to go on in.
     */
    private fun resetTheLogBeforeTheFirstWrite(predicted: Int) {
        val label = "a list write"
        val seqBefore = storedSeq()
        println("[replica] $label of $predicted pages does not fit the -wal at ${walProbe.sizeBytes()} bytes: the log is reset first")
        rollbackOrFence(label)
        val reset = checkpoint(SqliteCheckpointMode.RESTART)
        fence?.let { throw ReplicaReplaced(it) }
        leaveGate()
        try {
            begin(label)
        } catch (t: Throwable) {
            fenceNow(ReplicaFence.COMMIT_FAILED)
            throw t
        }
        fence?.let { throw ReplicaReplaced(it) }
        if (!reset || !fitsNow(predicted)) {
            // ⚠ B.19.10 (351): a reader kept the log from restarting. Nothing is written in this
            // new transaction, so the write is refused as writer rule 1 refuses one at the ceiling:
            // `WalResetPending`, retryable, no fence (the access commits nothing).
            throw WalResetPending("[replica] $label of $predicted pages: the -wal did not reset (${walProbe.sizeBytes()} bytes)")
        }
        listResets++
        if (storedSeq() != seqBefore) {
            refuse(TransactionBudgetExceeded(walProbe.sizeBytes(), predicted).also {
                println("[replica] a sibling committed while the log was reset for $label: what the caller read is stale")
            })
        }
    }

    /** Segment rows that name [head] and are not in [reached]: what a damaged chain leaves behind (logged only). */
    private fun unreachableSegmentsOf(head: Long, reached: Set<Long>): Int = statement(SEGMENTS_SQL) { rows ->
        var left = 0
        while (rows.step()) {
            val segment = DbEntryCodec.decode(rows.getBlob(0))
            if (ReplicaListSegments.headOf(segment) == head && segment.dataIdHash !in reached) left++
        }
        left
    }

    /** Test seam: list writes that reset the log before their first write (B.19.9 (344)). */
    internal var listResets: Int = 0
        private set

    /** A free hash for a new segment of [head]: no row there, and none of its own chain's. */
    private fun freshSegmentHash(head: Long, chain: List<ReplicaListSegments.Stored>): Long {
        val taken = chain.mapTo(HashSet()) { it.hash }
        var i = freshCandidates.getOrPut(head) { 0 }
        while (true) {
            i++
            val hash = ReplicaListSegments.candidate(head, i)
            if (hash == head || hash in taken) continue
            if (storedRow(hash) != null) continue
            freshCandidates[head] = i
            return hash
        }
    }

    /**
     * The candidate counter per list for the access that is planning (B.19 (333)): two new
     * segments of one write must not both be handed the first free candidate, since neither is
     * in the store yet when the second is asked for. Cleared at every access's end.
     */
    private val freshCandidates = HashMap<Long, Int>()

    /** Test seams: list writes that took the chain's path, and their statements. */
    internal var listWrites: Int = 0
        private set
    internal var listStatements: Int = 0
        private set

    /** The type and payload of the row at [hash], without reading its overflow chain; null for none. */
    private class StoredRowInfo(val type: String, val payloadBytes: Int)

    private fun storedRow(hash: Long): StoredRowInfo? = statement(ROW_INFO_SQL) { row ->
        row.bindLong(1, hash)
        if (row.step()) StoredRowInfo(row.getText(0), row.getLong(1).toInt() + ROW_HEADER_BYTES) else null
    }

    private fun payloadOf(type: String, encoded: ByteArray): Int = encoded.size + type.encodeToByteArray().size + ROW_HEADER_BYTES

    /** The raw row at [hash], decoded, with its payload; null for none. */
    private fun rawRow(hash: Long): Pair<DbEntry, Int>? {
        // The one point read every store's get has always been (tests hook it by its SQL); the
        // type is in the row's own bytes.
        val bytes = statement(POINT_READ_SQL) { read ->
            read.bindLong(1, hash)
            if (read.step()) read.getBlob(0) else null
        } ?: return null
        val entry = DbEntryCodec.decode(bytes)
        return entry to payloadOf(entry.type, bytes)
    }

    /**
     * ⚠⚠ The chain of a stored list (B.19 (331)), head first. A head with no link is a chain of
     * one: every legacy row, every list of at most one segment. ⚠ Each link is checked: present, a
     * segment, of this list, not seen before, so a damaged chain is [ListSegmentsDamaged] on a
     * read, never a shorter list.
     */
    private fun chainFrom(first: DbEntry, payload: Int): List<ReplicaListSegments.Stored> {
        val read = readChain(first, payload)
        read.damage?.let { throw ListSegmentsDamaged(first.dataIdHash, it) }
        return read.segments
    }

    /** A chain as far as it holds together: [segments] head first, and why it stops early, or null. */
    private class ChainRead(val segments: List<ReplicaListSegments.Stored>, val damage: String?) {
        val damaged: Boolean get() = damage != null
    }

    private fun readChain(first: DbEntry, payload: Int): ChainRead {
        val head = first.dataIdHash
        val chain = ArrayList<ReplicaListSegments.Stored>()
        val firstNext = try {
            ReplicaListSegments.nextOf(first)
        } catch (e: ListSegmentsDamaged) {
            return ChainRead(listOf(ReplicaListSegments.Stored(head, first.fields, null, payload)), "its link is not a hash")
        }
        chain += ReplicaListSegments.Stored(head, first.fields, firstNext, payload)
        val seen = HashSet<Long>().also { it += head }
        var next = firstNext
        while (next != null) {
            if (!seen.add(next)) return ChainRead(chain, "its chain links back to $next")
            if (chain.size >= ReplicaListSegments.MAX_SEGMENTS) return ChainRead(chain, "its chain is longer than ${ReplicaListSegments.MAX_SEGMENTS}")
            val (segment, bytes) = rawRow(next) ?: return ChainRead(chain, "segment $next is missing")
            if (segment.type != ReplicaListSegments.SEGMENT_TYPE || ReplicaListSegments.headOf(segment) != head) {
                return ChainRead(chain, "the row at $next is not one of its segments")
            }
            val link = try {
                ReplicaListSegments.nextOf(segment)
            } catch (e: ListSegmentsDamaged) {
                return ChainRead(chain, "the link of segment $next is not a hash")
            }
            chain += ReplicaListSegments.Stored(next, segment.fields, link, bytes)
            next = link
        }
        return ChainRead(chain, null)
    }

    /**
     * ⚠ The chain at [head] for a write or a removal (B.19.9 (346)). A damaged one is not an
     * error here: what can still be reached is returned, marked damaged, and the write lays the
     * list out afresh and deletes it, so a damaged list (a sync queue) never stalls every write
     * of it. Logged, since its unreachable rest is lost to the list. Null when no row is there.
     */
    private fun chainForChange(head: Long): ChainRead? {
        val (first, payload) = rawRow(head) ?: return null
        val read = readChain(first, payload)
        read.damage?.let {
            val reached = read.segments.mapTo(HashSet()) { segment -> segment.hash }
            println(
                "[replica] the list at $head is damaged ($it): it is laid out afresh, ${read.segments.size - 1} reachable " +
                    "segments deleted, ${unreachableSegmentsOf(head, reached)} left unreachable until a wipe",
            )
        }
        return read
    }

    /**
     * A stored row as the caller sees it: a list assembled from its chain, its reserved link gone;
     * any other row as it is; a segment never (null).
     */
    private fun logical(stored: DbEntry, payload: Int): DbEntry? = when {
        stored.type == ReplicaListSegments.SEGMENT_TYPE -> null
        ReplicaListSegments.isLinkedHead(stored) -> {
            val chain = chainFrom(stored, payload)
            val members = ArrayList<DbValue>(chain.sumOf { it.size })
            chain.forEach { members.addAll(it.members) }
            DbEntry(stored.type, stored.dataIdHash, members, emptyMap())
        }
        else -> stored
    }

    override fun getObject(dataIdHash: Long): DbEntry? = access("getObject") {
        if (fence != null) return@access null
        val (stored, payload) = rawRow(dataIdHash) ?: return@access null
        logical(stored, payload)
    }

    override fun hasObject(dataIdHash: Long): Boolean = access("hasObject") {
        if (fence != null) return@access false
        val stored = storedRow(dataIdHash)
        stored != null && stored.type != ReplicaListSegments.SEGMENT_TYPE
    }

    override fun removeObject(dataIdHash: Long) = access("removeObject") {
        requireServing()
        val stored = storedRow(dataIdHash) ?: return@access // nothing changes, so `seq` does not move
        when (stored.type) {
            // Not an object: a segment answers to its list alone.
            ReplicaListSegments.SEGMENT_TYPE -> return@access
            // The list and every segment of it, in one transaction.
            TList.ID -> {
                // A damaged chain too: what can be reached goes with it (B.19.9 (346)).
                val chain = chainForChange(dataIdHash)?.segments.orEmpty()
                if (chain.size > 1) {
                    writeChain(dataIdHash, chain.size, chain.map { ReplicaListSegments.Op.Delete(it.hash, it.payloadBytes) })
                    return@access
                }
            }
        }
        admit(limits.headroomPages(0, stored.payloadBytes))
        statement(DELETE_SQL) { delete ->
            delete.bindLong(1, dataIdHash)
            delete.step()
        }
        Unit
    }

    override fun setListener(dataIdHash: Long, listener: (update: DbEntry) -> Unit) = access("setListener") {
        listeners.getOrPut(dataIdHash) { mutableListOf() }.add(listener)
        // Contractual: fire at once with the current row if there is one.
        getObject(dataIdHash)?.let(listener)
        Unit
    }

    override fun removeListener(dataIdHash: Long) = access("removeListener") {
        listeners.remove(dataIdHash)
        Unit
    }

    /**
     * ⚠ A fenced handle answers `false`, "not known to be empty": `true` would tell the sync
     * cursor's self-heal that the data was reset.
     */
    override fun isEmpty(): Boolean = access("isEmpty") {
        if (fence != null) return@access false
        statement("SELECT 1 FROM objects LIMIT 1") { !it.step() }
    }

    /** ⚠ A fenced handle answers null, "do not ask me", never an empty list (see the interface). */
    override fun rowsOfTypes(typeIds: Set<String>): List<DbEntry>? = access("rowsOfTypes") {
        if (fence != null) return@access null
        if (typeIds.isEmpty()) return@access emptyList()
        val placeholders = typeIds.joinToString(", ") { "?" }
        val found = statement("SELECT data, type FROM objects WHERE type IN ($placeholders)") { rows ->
            typeIds.forEachIndexed { index, typeId -> rows.bindText(index + 1, typeId) }
            val found = ArrayList<Pair<DbEntry, Int>>()
            while (rows.step()) {
                val bytes = rows.getBlob(0)
                found += DbEntryCodec.decode(bytes) to payloadOf(rows.getText(1), bytes)
            }
            found
        }
        // Assembled after the statement has ended: a chain is read by statements of its own.
        found.mapNotNull { (stored, payload) -> logical(stored, payload) }
    }

    /**
     * The interface's bounded scan, with its semantics kept: strictly after [afterHash], so a row
     * at `Long.MIN_VALUE` is never returned to a caller that starts there (pre-existing on every
     * store, §5.3). [examineLimit] bounds nothing here: through `objects_type` only matching rows
     * are visited, so a short page is exhaustion.
     */
    override fun scanRowsOfType(
        typeId: String,
        afterHash: Long,
        matchLimit: Int,
        examineLimit: Int,
    ): ObjectStore.TypeScanPage? = access("scanRowsOfType") {
        if (fence != null || matchLimit <= 0) return@access null
        val rows = typePage(typeId, afterHash, matchLimit)
        ObjectStore.TypeScanPage(
            rows = rows,
            // On an empty page the cursor must not move.
            nextCursor = rows.lastOrNull()?.dataIdHash ?: afterHash,
            exhausted = rows.size < matchLimit,
        )
    }

    /**
     * One page of the keyset pager every chunked job uses (§5.3): rows of [typeIds] in hash
     * order, at most [limit]. ⚠ [after] is the hash of the last row **taken**, or **null for
     * "before the first row"**: the first page carries no lower bound at all. A sentinel such as
     * `Long.MIN_VALUE` is never used, because a row can hash to it. A page shorter than [limit]
     * is the end.
     *
     * Each type is read through `objects_type`, already in hash order, and the pages are merged;
     * one statement over several types would sort every matching row for every page.
     */
    internal fun pageOfTypes(typeIds: Set<String>, after: Long?, limit: Int): List<DbEntry> = access("pageOfTypes") {
        requireServing()
        require(limit > 0) { "limit must be positive" }
        val pages = typeIds.map { typePage(it, after, limit) }
        if (pages.size == 1) pages[0] else pages.flatten().sortedBy { it.dataIdHash }.take(limit)
    }

    private fun typePage(typeId: String, after: Long?, limit: Int): List<DbEntry> {
        // Segments are not objects: a page of them is always empty (B.19 (332)).
        if (typeId == ReplicaListSegments.SEGMENT_TYPE) return emptyList()
        val sql = if (after == null) TYPE_FIRST_PAGE_SQL else TYPE_NEXT_PAGE_SQL
        val rows = statement(sql) { page ->
            var index = 1
            page.bindText(index++, typeId)
            if (after != null) page.bindLong(index++, after)
            page.bindLong(index, limit.toLong())
            val rows = ArrayList<Pair<DbEntry, Int>>(minOf(limit, 512))
            while (page.step()) {
                val bytes = page.getBlob(0)
                rows += DbEntryCodec.decode(bytes) to payloadOf(typeId, bytes)
            }
            rows
        }
        // Assembled after the page's statement has ended, so a chain's reads never nest in it.
        return rows.mapNotNull { (stored, payload) -> logical(stored, payload) }
    }

    // flushBarrier keeps the interface's no-op: the commit is the barrier.

    // ── the wipe ──

    /**
     * §5.1's wipe, in its only form: the first chunk here, on this handle, which it fences; every
     * later chunk on a `REPLACEMENT` handle, driven by this same call. ⚠⚠ **Returns only after the
     * last chunk has committed** (the tables empty, `meta.wipe` gone, the epoch moved a second
     * time). If it cannot get there it throws [WipeNotFinished]: the marker and the committed
     * chunks stay, this handle stays fenced, and any qualifying open resumes the wipe.
     *
     * If the first chunk does not commit nothing changed, and the call fails as any failed access
     * does (rule (a), or [ReplicaReplaced] on a handle that was already fenced).
     */
    override suspend fun wipeAllObjects() = wipe("wipe")

    /** [wipeAllObjects], with the reason `meta.replaced_by` records. */
    fun wipe(reason: String) {
        access("wipe") {
            requireServing()
            val started = files.now().first
            ReplicaJobSql.startWipe(WipeMarker(reason, appName, started, endStamps = emptyMap())) { sql, bind ->
                requireFits(limits.statementPages)
                statement(sql) {
                    bind(it)
                    it.step()
                }
            }
            fenceAtCommit = ReplicaFence.REPLACED
        }
        when (val outcome = replacement().also { onReplacement?.invoke(it) }.run()) {
            ReplicaRunOutcome.Finished -> Unit
            else -> throw WipeNotFinished(outcome)
        }
    }

    // ── checkpoints ──

    /**
     * One checkpoint ([ReplicaCheckpointer]). True only when the reset is **verified**; a marker
     * found, or a corruption code that a second reading confirms, fences this handle on the way.
     *
     * ⚠ A corruption code here was met by the checkpoint's own connection, which is closed and
     * its handle released before this sees the outcome; this handle's connection met nothing and
     * holds no transaction at either call site. So an **unconfirmed** code is a checkpoint that
     * did not happen ("skipped", §5.2.2): this handle goes on serving, the next trigger opens a
     * new checkpoint connection, and at the ceiling the access is refused with `WalResetPending`.
     *
     * ⚠⚠ **The second code in a row is the confirmation** ([ReplicaCorruptionConfirm.runForCheckpoint]):
     * the marker is written and this handle fenced `CORRUPT`. If it cannot be (the gate, the
     * marker's own write), the handle is fenced `COMMIT_FAILED` instead, in memory: a checkpoint
     * that answers a code at every trigger must not charge every access for it.
     */
    private fun checkpoint(mode: SqliteCheckpointMode): Boolean = when (val outcome = checkpointer.run(mode)) {
        ReplicaCheckpointOutcome.Reset -> {
            if (mode == SqliteCheckpointMode.RESTART) verifiedResets++
            true
        }
        ReplicaCheckpointOutcome.NotReset -> false
        is ReplicaCheckpointOutcome.Marked -> {
            fenceNow(if (outcome.state == ReplicaMarkerState.DURABLE) ReplicaFence.CORRUPT else ReplicaFence.COMMIT_FAILED)
            false
        }
        is ReplicaCheckpointOutcome.CorruptionCode -> {
            when (confirmation.runForCheckpoint(outcome.message, outcome.inARow, failing = null, alreadyAdmitted = admitted)) {
                is ReplicaCorruptionConfirmation.Confirmed -> fenceNow(ReplicaFence.CORRUPT)
                is ReplicaCorruptionConfirmation.Unconfirmed -> if (outcome.inARow >= 2) {
                    println("[replica] ${outcome.inARow} checkpoints in a row answered a corruption code and the store could not be marked; the handle is fenced")
                    fenceNow(ReplicaFence.COMMIT_FAILED)
                }
            }
            false
        }
    }

    companion object {
        /**
         * Opens the shared store in [directory] (`<group container>/replica`) for a build
         * declaring [build], through the process's registry for that path (§5.2).
         *
         * [foreground]: only a foreground open may find a store that needs a migration or a wipe
         * started; a background one throws [StoreNeedsForeground]. [protectedDataAvailable] is
         * `UIApplication.isProtectedDataAvailable`, asked whenever a failure has to be told apart
         * from the phone being locked. Throws [StoreUnavailable] for everything retryable.
         *
         * ⚠⚠ **Refused, with nothing touched, unless the path's registry is under an installed
         * protection** ([SharedReplicaUnprotected], Appendix B.13, B.11.4 (1), Q24): a process with
         * none counts as foreground everywhere, so it would get no `CKPT_MAX`, no admission check
         * and no refusal of a migration chunk. The tests open through `ReplicaOpener` on registries
         * of their own and never reach this.
         */
        fun open(
            directory: String,
            build: ReplicaBuild,
            migrations: ReplicaMigrationSteps,
            appName: String,
            foreground: Boolean,
            protectedDataAvailable: () -> Boolean,
            limits: ReplicaLimits = ReplicaLimits(),
        ): ReplicaOpenResult {
            val opener = protectedOpener(directory, build, migrations, appName, foreground, protectedDataAvailable, limits)
            // ⚠⚠ D3 (§2.4, S0b-4 slice 2): `replica/` and the store's files set explicitly to
            // `CompleteUntilFirstUserAuthentication` and excluded from backup, **at every open**, and
            // read back. ⚠⚠ Fix round 1 (B.21.6 (410)): **not** gated on `isProtectedDataAvailable`,
            // which is false whenever the phone is locked — a locked wake's open would have made the
            // store with neither flag. Only before the first unlock is anything deferred, and then
            // the open's own verdict (`ProtectedDataUnavailable`) is the answer.
            val db = "$directory/${ReplicaOpener.DATABASE_NAME}"
            try {
                atRest().protect(directory, files = listOf(db, "$db-wal", "$db-shm"), protectedDataAvailable = protectedDataAvailable)
            } catch (e: org.tenshu.core.common.proxy.db.NotProtectedAtRest) {
                throw ReplicaNotProtected(e.message ?: "not protected at rest", e)
            }
            return opener.open()
        }

        /** D3's setter. A test seam. */
        internal var atRest: () -> org.tenshu.core.common.proxy.db.ContainerAtRest = { org.tenshu.core.common.proxy.db.ContainerAtRest() }

        /**
         * The store-less wipe (§5.1): the same wipe with no live store, for a caller that must
         * work before a runtime exists. It classifies read-only first, so a newer store is never
         * opened read/write, let alone wiped ([WipeRefused]); then the first chunk on a
         * short-lived handle and the rest on a `REPLACEMENT` handle, **returning only after the
         * last chunk** or throwing [WipeNotFinished]. A wipe already in progress is joined.
         *
         * False when there was no store to wipe (no file, or one never created).
         */
        fun wipeSharedReplica(
            directory: String,
            build: ReplicaBuild,
            migrations: ReplicaMigrationSteps,
            appName: String,
            reason: String,
            protectedDataAvailable: () -> Boolean,
            limits: ReplicaLimits = ReplicaLimits(),
        ): Boolean = protectedOpener(directory, build, migrations, appName, foreground = true, protectedDataAvailable, limits).wipe(reason)

        /**
         * [wipeSharedReplica], but its chunks after the first **handed to the worker** (Appendix
         * B.13 (178)): for a caller on the main thread at launch (the custody purge), which must
         * not run a whole wipe under the launch watchdog. The worker tells the outcome through
         * the lifecycle's `onJobOutcome`. ⚠ The path's registry needs a worker attached.
         */
        fun startWipeSharedReplica(
            directory: String,
            build: ReplicaBuild,
            migrations: ReplicaMigrationSteps,
            appName: String,
            reason: String,
            protectedDataAvailable: () -> Boolean,
            limits: ReplicaLimits = ReplicaLimits(),
            /** Told the handed-off job's id before it is dispatched; its outcome carries the same id. */
            beforeHandOff: (jobId: Long) -> Unit = {},
        ): SharedReplicaWipeStart =
            protectedOpener(directory, build, migrations, appName, foreground = true, protectedDataAvailable, limits).startWipe(reason, handOff = true, beforeHandOff)

        /**
         * ⚠⚠ Deletes the files of a store that can never be wiped, a corrupt one or a newer
         * build's (Appendix B.13 (179)), **only when no connection is open on it anywhere**
         * ([deleteReplicaFiles]); else [SharedReplicaDeletion.BUSY] with nothing touched. The
         * caller releases its own runtime handle first. Refused, like the open, with no installed
         * protection.
         */
        fun deleteSharedReplicaFiles(directory: String, limits: ReplicaLimits = ReplicaLimits()): SharedReplicaDeletion {
            val path = "$directory/${ReplicaOpener.DATABASE_NAME}"
            val registry = ReplicaHandles.forPath(path, limits)
            requireProtected(registry, path)
            return registry.gate.admittedFor("delete-files", null, null) { deleteReplicaFiles(registry, directory) }
        }

        /**
         * The production entries' opener: [opener] over the process's registry for [directory],
         * once that registry is known to be under an installed protection (B.11.4 (1), Q24).
         */
        private fun protectedOpener(
            directory: String,
            build: ReplicaBuild,
            migrations: ReplicaMigrationSteps,
            appName: String,
            foreground: Boolean,
            protectedDataAvailable: () -> Boolean,
            limits: ReplicaLimits,
        ): ReplicaOpener {
            val path = "$directory/${ReplicaOpener.DATABASE_NAME}"
            requireProtected(ReplicaHandles.forPath(path, limits), path)
            return opener(directory, build, migrations, appName, foreground, protectedDataAvailable, limits)
        }

        /**
         * ⚠⚠ B.11.4 (1) and Q24 (decided 2026-10-18): every process that opens the store attaches a
         * protection to its registry and installs it first. An app extension has no `UIApplication`
         * to back one, so until it has its own `SharedReplicaBackgroundTime` it is refused here.
         */
        internal fun requireProtected(registry: ReplicaHandles, path: String) {
            val protection = registry.gate.protection
                ?: throw SharedReplicaUnprotected("[replica] $path: no protection is attached to this process's registry (B.11.4 (1), Q24)")
            if (!protection.isInstalled) {
                throw SharedReplicaUnprotected("[replica] $path: the protection is attached but not installed (attach, install, then open)")
            }
        }

        private fun opener(
            directory: String,
            build: ReplicaBuild,
            migrations: ReplicaMigrationSteps,
            appName: String,
            foreground: Boolean,
            protectedDataAvailable: () -> Boolean,
            limits: ReplicaLimits,
        ) = ReplicaOpener(
            directory = directory,
            build = build,
            steps = migrations,
            appName = appName,
            foreground = foreground,
            protectedDataAvailable = protectedDataAvailable,
            files = PosixReplicaFiles,
            registry = ReplicaHandles.forPath("$directory/${ReplicaOpener.DATABASE_NAME}", limits),
        )

        private const val EPOCH_SQL = "SELECT v, typeof(v) FROM meta WHERE k = '${ReplicaSchema.META_EPOCH}'"

        internal const val POINT_READ_SQL = "SELECT data FROM objects WHERE hash = ?"
        internal const val UPSERT_SQL =
            "INSERT INTO objects (hash, type, data) VALUES (?, ?, ?) " +
                "ON CONFLICT(hash) DO UPDATE SET type = excluded.type, data = excluded.data"
        internal const val BUMP_SEQ = "UPDATE meta SET v = v + 1 WHERE k = '${ReplicaSchema.META_SEQ}'"
        internal const val REWIND_SQL =
            "INSERT INTO meta (k, v) VALUES ('${ReplicaSchema.META_WAL_REWIND}', 1) " +
                "ON CONFLICT(k) DO UPDATE SET v = v + 1"

        // length() of a BLOB reads the record header, not the overflow chain.
        private const val ROW_INFO_SQL = "SELECT type, length(data) + length(CAST(type AS BLOB)) FROM objects WHERE hash = ?"
        private const val DELETE_SQL = "DELETE FROM objects WHERE hash = ?"
        private const val SEGMENTS_SQL = "SELECT data FROM objects WHERE type = '${ReplicaListSegments.SEGMENT_TYPE}'"
        private const val SEQ_SQL = "SELECT v FROM meta WHERE k = '${ReplicaSchema.META_SEQ}'"
        private const val TYPE_FIRST_PAGE_SQL = "SELECT data FROM objects WHERE type = ? ORDER BY hash LIMIT ?"
        private const val TYPE_NEXT_PAGE_SQL = "SELECT data FROM objects WHERE type = ? AND hash > ? ORDER BY hash LIMIT ?"

        /** A record's header and serial types, over its two values: a loose few bytes. */
        private const val ROW_HEADER_BYTES = 16

        private const val MAX_RESETS_PER_ACCESS = 3
    }
}

/**
 * §4.2's acquisition, shared by the open and by every access: admit through the gate, open the
 * handle's connection if it is closed, `BEGIN IMMEDIATE`. On `SQLITE_BUSY` the thread leaves the
 * gate and the in-process lock, sleeps 1 ms doubling to 20 ms, and starts again, so a thread
 * waiting on a sibling holds nothing between slices and is counted by the gate for one
 * `busy_timeout` at most (S7).
 *
 * The caller holds the registry's lock exactly once. Returns the connection **with the admission
 * held** (the caller leaves the gate), or null with nothing held once [stillWanted] says no after
 * a wait. Throws with nothing held: [ReplicaGateClosed], or whatever the open or `BEGIN` threw.
 *
 * ⚠⚠ **After a throw the handle has no connection.** Whatever failed (the open, a setting,
 * `BEGIN`, the gate closing after the lock was obtained), the connection is rolled back if it
 * had begun and closed before the admission is given back, so nothing of SQLite's is touched by
 * the caller's cleanup after the gate stopped counting it. The handle stays registered.
 */
internal fun ReplicaHandles.acquire(
    handle: ReplicaHandle,
    label: String,
    waitForProtection: Boolean = false,
    operation: ReplicaOperation = ReplicaOperation.WRITE,
    stillWanted: () -> Boolean,
): SqliteConnection? {
    var backoffMicros = 1_000u
    val started = TimeSource.Monotonic.markNow()
    var reported = false
    while (true) {
        if (!gate.enter(label)) {
            // S0b-2: an access refused at the gate asks for protection, parks, or (the main
            // thread) retries, holding nothing; a job ([waitForProtection] false) stops instead.
            // With no protection attached this throws ReplicaGateClosed, as before.
            if (!waitForProtection) throw ReplicaGateClosed("[replica] $label: the gate is closed")
            check(lock.holdDepth == 1) { "[replica] $label: the registry's lock is held ${lock.holdDepth} times; waiting would keep it" }
            lock.unlock()
            try {
                gate.awaitAdmission(label)
            } finally {
                lock.lock()
            }
            if (!stillWanted()) return null
            continue
        }
        // §4.2.1: in protected background time the operation starts only if the time left covers
        // it (and the index rebuild, if this opens the connection). Deferred before any SQLite
        // call, with nothing held: retryable, as WalResetPending is.
        // A deferred close owed to this very handle is made first (connectionFor would make it
        // anyway), so "does this open the connection" is asked of the state the open will meet.
        closeDeferredNow()
        // That close may have finished this very handle's pending release: then it is wanted no
        // longer (a store's is fenced RELEASED), and it is answered as one fenced while waiting.
        if (handle.released || !stillWanted()) {
            gate.leave()
            return null
        }
        gate.refusal(operation, limits, opensConnection = handle.connection == null)?.let { why ->
            gate.leave()
            throw ReplicaAdmissionDeferred("[replica] $label: $why")
        }
        var held = true
        try {
            val connection = connectionFor(handle)
            val begin = handle.prepared("BEGIN IMMEDIATE")
            val busy = try {
                beginFault?.invoke(handle)
                begin.step()
                false
            } catch (e: SqliteException) {
                if (e.primaryCode != SQLITE_BUSY && e.primaryCode != SQLITE_LOCKED) throw e
                true
            } finally {
                begin.reset()
            }
            if (!busy) {
                var gateClosedAfterBegin = false
                if (!gate.isOpen) {
                    // Obtained, but the gate closed meanwhile: as if never admitted (§4.2 step 5).
                    try {
                        connection.exec("ROLLBACK")
                    } catch (e: SqliteException) {
                        // "Never admitted" must not leave a transaction behind: the connection is
                        // closed (which rolls back again, and latches the process if it cannot).
                        println("[replica] $label: ROLLBACK failed after the gate closed: ${e.message}")
                        closeConnection(handle)
                    }
                    // Not admitted (§4.2 step 5): an access goes back to the gate, which now
                    // refuses it and asks for protection; a job stops.
                    if (!waitForProtection) throw ReplicaGateClosed("[replica] $label: the gate closed while waiting")
                    gateClosedAfterBegin = true
                }
                if (!gateClosedAfterBegin) {
                    held = false
                    return connection
                }
            }
        } catch (t: Throwable) {
            // ⚠⚠ Nothing of a failed acquisition outlives its admission. Whatever threw (the
            // open, a setting, `BEGIN`, the gate closing), the connection is closed HERE, still
            // counted by the gate, so a caller is handed a handle with no connection: its own
            // cleanup (a fence, a release) then has nothing of SQLite's left to touch. The
            // handle itself stays registered and reopens on its next access.
            try {
                closeConnection(handle)
            } catch (_: StoreUnavailable) {
                // Latched and logged by the registry.
            }
            throw t
        } finally {
            if (held) gate.leave()
        }
        check(lock.holdDepth == 1) { "[replica] $label: the registry's lock is held ${lock.holdDepth} times; sleeping would keep it" }
        lock.unlock()
        try {
            usleep(backoffMicros)
        } finally {
            lock.lock()
        }
        backoffMicros = minOf(backoffMicros * 2u, 20_000u)
        if (!reported && started.elapsedNow().inWholeSeconds >= 10) {
            reported = true
            println("[replica] waiting on a sibling: $label")
        }
        if (!stillWanted()) return null
    }
}

/** What `replica/CORRUPT` holds: the time, the detecting app and the SQLite error (§6). */
internal fun corruptMarkerDetail(files: ReplicaFiles, appName: String, error: String): String =
    "time=${files.now().first}\napp=$appName\nerror=$error\n"
