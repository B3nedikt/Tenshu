@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.db.DbEntryCodec
import org.tenshu.core.common.proxy.db.ObjectStore.DbValue
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_INTERRUPT
import org.tenshu.core.common.schema.migration.CORE_SERVICE_ID
import platform.Foundation.NSLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** What one transaction of a job did. */
internal sealed interface ChunkResult {
    /** Committed (or found) a job that is not finished: another chunk follows. */
    data class More(val job: ReplicaJob) : ChunkResult

    data class Ended(val outcome: ReplicaRunOutcome) : ChunkResult
}

/** The statements of a job's start, shared by every handle that can run one. */
internal object ReplicaJobSql {
    private const val META = ReplicaSchema.META
    const val UPSERT_META = "INSERT INTO $META (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v"
    const val DELETE_META = "DELETE FROM $META WHERE k = ?"
    const val BUMP_SEQ = SharedReplicaStore.BUMP_SEQ

    /**
     * A wipe's first chunk (§5.1): the marker, no migration marker beside it, a new epoch,
     * `seq + 1` and `replaced_by`. It deletes no row. [run] executes one statement in the
     * caller's transaction, under the caller's budget.
     */
    fun startWipe(marker: WipeMarker, run: (sql: String, bind: (SqliteStatement) -> Unit) -> Unit) {
        run(UPSERT_META) {
            it.bindText(1, ReplicaSchema.META_WIPE)
            it.bindBlob(2, marker.encode())
        }
        run(DELETE_META) { it.bindText(1, ReplicaSchema.META_MIGRATION) }
        moveEpoch(run)
        replacedBy("${marker.reason} ${marker.app}", run)
    }

    /** A migration chain's Start (§5.3): the marker, a new epoch, `seq + 1`, `replaced_by`. */
    fun startMigration(marker: MigrationMarker, run: (sql: String, bind: (SqliteStatement) -> Unit) -> Unit) {
        writeMigration(marker, run)
        moveEpoch(run)
        replacedBy("migrating ${marker.app}", run)
    }

    fun writeMigration(marker: MigrationMarker, run: (sql: String, bind: (SqliteStatement) -> Unit) -> Unit) {
        // A frame the decoder would call damage is never written: the next reader would mark the
        // store for good. A defect here is this code's, and it stops the chunk instead.
        marker.defect()?.let { error("[replica] refusing to write a migration marker no reader accepts: $it") }
        run(UPSERT_META) {
            it.bindText(1, ReplicaSchema.META_MIGRATION)
            it.bindBlob(2, marker.encode())
        }
    }

    /** ⚠⚠ Only a start and a finish call this (the epoch rule, §5.3). */
    fun moveEpoch(run: (sql: String, bind: (SqliteStatement) -> Unit) -> Unit) {
        run(UPSERT_META) {
            it.bindText(1, ReplicaSchema.META_EPOCH)
            it.bindLong(2, ReplicaOpener.newEpoch())
        }
        run(BUMP_SEQ) {}
    }

    private fun replacedBy(text: String, run: (sql: String, bind: (SqliteStatement) -> Unit) -> Unit) {
        run(UPSERT_META) {
            it.bindText(1, ReplicaSchema.META_REPLACED_BY)
            it.bindText(2, text)
        }
    }
}

/**
 * A wipe or a migration, as the job a `REPLACEMENT` handle runs (§2.8, §5.1, §5.3): bounded
 * transactions ("chunks") behind a marker in `meta`, resumable after a stop or a kill at any
 * point by any build that passes the resumer rule.
 *
 * ⚠⚠ **The marker rule: every decision a chunk makes is read from the marker and the stamps
 * under the write lock, in that chunk's own transaction** ([chunk]). Nothing is carried from an
 * earlier chunk or from the open that made this object, so a sibling that moved the cursor,
 * advanced the step, extended the targets, finished the job or replaced a migration by a wipe is
 * seen by the very next chunk. A chunk that finds this build no longer qualifies writes nothing.
 *
 * ⚠⚠ **The epoch rule: the epoch moves at a start and at a finish, never in between**, so two
 * resumers never fence each other, and a runtime handle whose epoch still matches
 * ([SharedReplicaStore]) is on a store no job has touched.
 *
 * **Every chunk is one transaction inside the budget and the hold ceiling:** it begins only with
 * the `-wal` at or below `Wmax − δ` (the ceiling checkpoint first, otherwise), admits each unit
 * of work by `fits` before its first statement, and commits at the first unit that does not fit,
 * at `chunkMaxRows` units, or after `chunkTimeStopMs`. A statement or a `COMMIT` reached after
 * `HOLD_MAX` is refused ([HoldCeilingExceeded]) and the chunk rolled back; one running when the
 * hold watchdog fires ([ReplicaHoldWatchdog]) is interrupted, with the same end. Either way the
 * run stops and its `REPLACEMENT` handle is released: there is no runtime to fence.
 *
 * A failed chunk is rolled back whole and ends the run ([ReplicaRunOutcome.Stopped]); there is no
 * runtime to fence. One instance is used by one thread at a time.
 *
 * ⚠⚠ **One failure is not a failed chunk: a migration step that fails on a row** ([rowFailed]).
 * It fails before any statement of that row, so the chunk commits: a count of the failure in the
 * marker, and the run stops; or, in the `N`th run to meet it, a wipe's first chunk (Appendix B.3
 * (41), (53)). Nothing else a chunk meets is ever counted ([rowWork]).
 */
class ReplicaReplacement internal constructor(
    internal val registry: ReplicaHandles,
    private val files: ReplicaFiles,
    directory: String,
    private val build: ReplicaBuild,
    private val steps: ReplicaMigrationSteps,
    internal val appName: String,
    private val protectedDataAvailable: () -> Boolean,
) {
    /**
     * This job object's identity in the process, for a host that waits on **this** job's outcome
     * and not another's of the same store (Appendix B.13 (183)). Never reused.
     */
    val jobId: Long = nextJobId()

    private val limits: ReplicaLimits get() = registry.limits
    private val marker = ReplicaCorruptMarker(files, directory)
    private var current: ReplicaHandle? = null
    private val walProbe = ReplicaWalProbe(files, registry.databasePath + "-wal") { current?.hasConnection == true }
    internal val checkpointer = ReplicaCheckpointer(registry, marker, walProbe, protectedDataAvailable)
    private val confirmation = ReplicaCorruptionConfirm(registry, files, marker, appName, build, protectedDataAvailable)

    /** This build's versions, `:core`'s under its service id (it is stamped in `user_version`). */
    private val declared: Map<String, Int> = build.services + (CORE_SERVICE_ID to build.coreVersion)

    private var validated = false

    /** True while a transaction of this job, or its log reset, holds a gate admission. */
    private var inAdmission = false

    /** Guards [running]: "one thread at a time" is enforced, not assumed (Appendix B.11 (150)). */
    private val runLock = NSLock()
    private var running = false

    /** Test seam: end-of-job checkpoints this object ran (Appendix B.11 (148)). */
    internal var endOfJobCheckpoints: Int = 0
        private set

    /** Milliseconds of a monotonic clock. A test moves it by hand. */
    internal var clock: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }

    /**
     * Fault injection for the kill tests: called with a transaction's label just before its
     * `COMMIT`; a throw rolls the transaction back, as a kill at that point would. Null in production.
     */
    internal var beforeCommit: ((String) -> Unit)? = null

    /**
     * Fault injection: called with the SQL of every statement of a job's transaction before it
     * runs; a throw is that statement's failure. Null in production.
     */
    internal var statementFault: ((String) -> Unit)? = null

    /** Test seam: called where a transaction was rolled back to reset the log, before the checkpoint. Null in production. */
    internal var beforeLogReset: (() -> Unit)? = null

    /** Test seam: the most bytes of stored rows a migration chunk read into memory at once. */
    internal var largestRowBytesHeld: Long = 0
        private set

    /** Test seam: transactions this object committed. */
    internal var commits: Int = 0
        private set

    /** Test seam: the largest `-wal` seen after a commit, and commits that left it past `Wmax + δ`. */
    internal var largestWalBytes: Long = 0
        private set
    internal var ceilingBreaches: Int = 0
        private set

    // ── entry points ──

    /**
     * Runs the job the marker names, chunk after chunk, until no marker is left
     * ([ReplicaRunOutcome.Finished]) or the run has to stop. [shouldStop] is asked between chunks,
     * with the gate (protection ending, S0b-2). The handle is this run's and is released when it
     * returns; a later run takes a new one.
     *
     * ⚠ For the process whose open (or wipe call) made this object. A later launch opens again:
     * that is where a newer store is turned away before any read/write connection.
     */
    fun run(shouldStop: () -> Boolean = { false }): ReplicaRunOutcome = guarded { runUnguarded(shouldStop) }

    /**
     * [run] and then [endOfJob], **as one guarded section** (B.11 (154)): the worker's run. A
     * second run of this object is refused until the end-of-job checkpoint has returned too, since
     * that checkpoint shares the probe, `inAdmission` and the checkpointer with any run.
     */
    internal fun runToEndOfJob(shouldStop: () -> Boolean = { false }): ReplicaRunOutcome =
        guarded {
            val outcome = runUnguarded(shouldStop)
            betweenRunAndEndOfJob?.invoke()
            endOfJobUnguarded(outcome)
        }

    /** Test seam: inside [runToEndOfJob]'s guard, after the run and before its end-of-job checkpoint. Null in production. */
    internal var betweenRunAndEndOfJob: (() -> Unit)? = null

    private fun runUnguarded(shouldStop: () -> Boolean): ReplicaRunOutcome =
        withHandle(ReplicaHandleKind.REPLACEMENT, "replacement:$appName") { handle -> drive(handle, shouldStop) }

    /**
     * ⚠ (150), (154): a second run of this object while one is going (the host running the job
     * the worker was handed, say), its end-of-job checkpoint included, is refused before it
     * touches anything. Two would share `current`, the probe, `inAdmission` and the checkpointer.
     */
    private inline fun <T> guarded(block: () -> T): T {
        runLock.lock()
        try {
            check(!running) { "[replica] the job of $appName is already running on this object" }
            running = true
        } finally {
            runLock.unlock()
        }
        try {
            return block()
        } finally {
            runLock.lock()
            running = false
            runLock.unlock()
        }
    }

    /**
     * ⚠⚠ §4.2.1's third opportunistic trigger (Appendix B.11 (148)): **at the end of a protected
     * background job, before its token is released, when the `-wal` has passed `Wmax/4`**, one
     * `PASSIVE` checkpoint, in an admission of its own. The worker runs it inside [runToEndOfJob],
     * with the run's token still held. In the foreground it does nothing: there the 30 s trigger is the
     * opportunistic one (with the UIKit adapter). [outcome] is the run's; what is returned is it,
     * or `Corrupt` when the checkpoint found the marker or its code was confirmed. A code it does
     * not confirm leaves [outcome] as it was: the job's own result stands, and the checkpoint's
     * rule (the second code in a row confirms, (52)) is kept by the counter the checkpointer moves.
     */
    internal fun endOfJob(outcome: ReplicaRunOutcome): ReplicaRunOutcome = guarded { endOfJobUnguarded(outcome) }

    private fun endOfJobUnguarded(outcome: ReplicaRunOutcome): ReplicaRunOutcome {
        if (outcome is ReplicaRunOutcome.Corrupt) return outcome
        if (registry.gate.inForeground()) return outcome
        registry.lock.lock()
        try {
            val wal = try {
                walProbe.sizeBytes()
            } catch (e: ReplicaFileException) {
                println("[replica] the -wal could not be measured at the end of the job of $appName: ${e.message}")
                return outcome
            }
            if (wal <= limits.journalSizeLimitBytes) return outcome
            // An access of its own (§4.2.1): the job's last admission was given back with its chunk.
            if (!registry.gate.enter()) return outcome
            inAdmission = true
            try {
                endOfJobCheckpoints++
                return when (val checkpoint = checkpointer.run(SqliteCheckpointMode.PASSIVE)) {
                    ReplicaCheckpointOutcome.Reset, ReplicaCheckpointOutcome.NotReset -> outcome
                    is ReplicaCheckpointOutcome.Marked ->
                        if (checkpoint.state == ReplicaMarkerState.DURABLE) ReplicaRunOutcome.Corrupt("replica/CORRUPT exists") else outcome
                    is ReplicaCheckpointOutcome.CorruptionCode ->
                        when (val ended = corruptionCode(checkpoint.message, checkpoint.inARow)) {
                            is ChunkResult.Ended -> ended.outcome.takeIf { it is ReplicaRunOutcome.Corrupt } ?: outcome
                            is ChunkResult.More -> outcome
                        }
                }
            } finally {
                inAdmission = false
                registry.gate.leave()
            }
        } finally {
            walProbe.close()
            registry.lock.unlock()
        }
    }

    private fun drive(handle: ReplicaHandle, shouldStop: () -> Boolean): ReplicaRunOutcome {
        while (true) {
            if (shouldStop()) return ReplicaRunOutcome.Stopped("asked to stop")
            if (!registry.gate.isOpen) return ReplicaRunOutcome.Stopped("the gate is closed")
            when (val result = transaction(handle, "chunk", ::chunk)) {
                is ChunkResult.More -> Unit
                is ChunkResult.Ended -> return result.outcome
            }
        }
    }

    /** One chunk on a handle of its own: for a test that interleaves two resumers. */
    internal fun runOneChunk(): ChunkResult {
        return withHandle(ReplicaHandleKind.REPLACEMENT, "replacement:$appName") { handle -> transaction(handle, "chunk", ::chunk) }
    }

    /**
     * The open's part, for a store its read-only classification found marked or behind (§5.2
     * step 3), in one transaction: the schema validated, the store judged again under the write
     * lock, and then
     * - **marked**: the resumer rule applied again under the lock ([classify]); nothing written.
     * - **behind**, in the foreground: the job started. Below a floor, a wipe's first chunk,
     *   carrying this build's stamps for its last; otherwise a migration chain's Start.
     *
     * [ChunkResult.More] names the job to [run]. `Finished` means the store is neither marked nor
     * behind any more (a sibling finished): the caller judges it again.
     */
    internal fun admit(foreground: Boolean): ChunkResult =
        withHandle(ReplicaHandleKind.REPLACEMENT, "open-job:$appName") { handle ->
            transaction(handle, "admit") { chunk ->
                val reading = chunk.held { readMarker(chunk.connection) }
                when (val verdict = classify(reading, build)) {
                    is ReplicaClassification.Skew -> return@transaction Txn.Rollback(ended(ReplicaRunOutcome.Skew(verdict.detail)))
                    is ReplicaClassification.OlderLayout -> return@transaction Txn.Rollback(stopped(verdict.detail))
                    is ReplicaClassification.Damaged -> throw ReplicaDamaged(verdict.detail)
                    ReplicaClassification.Fresh, ReplicaClassification.Compatible ->
                        return@transaction Txn.Rollback(ended(ReplicaRunOutcome.Finished))
                    is ReplicaClassification.Updating -> {
                        validate(chunk, reading)
                        if (verdict.job == ReplicaJob.MIGRATION && !foreground) {
                            return@transaction Txn.Rollback(ended(ReplicaRunOutcome.NeedsForeground))
                        }
                        Txn.Rollback(ChunkResult.More(verdict.job))
                    }
                    is ReplicaClassification.Behind -> {
                        validate(chunk, reading)
                        if (!foreground) return@transaction Txn.Rollback(ended(ReplicaRunOutcome.NeedsForeground))
                        if (verdict.belowFloor) {
                            startWipe(chunk, "below-floor", endStamps = declared)
                            Txn.Commit(ChunkResult.More(ReplicaJob.WIPE))
                        } else {
                            val recorded = recorded(reading)
                            val targets = declared.filter { (id, version) -> (recorded[id] ?: MigrationMarker.UNSTAMPED) < version }
                            val first = targets.keys.sorted().first()
                            val start = MigrationMarker(
                                app = appName,
                                startedSeconds = files.now().first,
                                targets = targets,
                                stepService = first,
                                stepFrom = recorded[first] ?: MigrationMarker.UNSTAMPED,
                                phase = MigrationPhase.ROWS,
                                cursor = null,
                            )
                            ReplicaJobSql.startMigration(start, chunk::metaWrite)
                            Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
                        }
                    }
                }
            }
        }

    /**
     * A wipe's first chunk with no live store (§5.1), on a short-lived handle of its own. A wipe
     * already in progress is joined: nothing is written, and the epoch does not move a third
     * time. A migration in progress is replaced (its marker deleted by the wipe's first chunk),
     * by a build that could have resumed it. `Finished`: there is no store here to wipe.
     *
     * ⚠ A store this build is ahead of is stamped with this build's versions by the wipe's last
     * chunk: it is empty by then, so there is nothing to migrate, and a store left at its old
     * stamps would be migrated, or wiped a second time, by the next open.
     */
    internal fun startWipe(reason: String): ChunkResult =
        withHandle(ReplicaHandleKind.STORELESS_WIPE, "wipe:$appName") { handle ->
            transaction(handle, "wipe-start") { chunk ->
                val reading = chunk.held { readMarker(chunk.connection) }
                val verdict = classify(reading, build)
                when (verdict) {
                    is ReplicaClassification.Skew -> return@transaction Txn.Rollback(ended(ReplicaRunOutcome.Skew(verdict.detail)))
                    is ReplicaClassification.OlderLayout -> return@transaction Txn.Rollback(stopped(verdict.detail))
                    is ReplicaClassification.Damaged -> throw ReplicaDamaged(verdict.detail)
                    ReplicaClassification.Fresh -> return@transaction Txn.Rollback(ended(ReplicaRunOutcome.Finished))
                    else -> Unit
                }
                validate(chunk, reading)
                if (reading.wipeMarker != null) return@transaction Txn.Rollback(ChunkResult.More(ReplicaJob.WIPE))
                val recorded = recorded(reading)
                val ahead = declared.filter { (id, version) -> (recorded[id] ?: MigrationMarker.UNSTAMPED) < version }
                startWipe(chunk, reason, endStamps = ahead)
                Txn.Commit(ChunkResult.More(ReplicaJob.WIPE))
            }
        }

    // ── one transaction ──

    private sealed interface Txn {
        class Commit(val result: ChunkResult) : Txn
        class Rollback(val result: ChunkResult) : Txn

        /** Not one unit of work fits the log as it stands: roll back, reset it, once more. */
        data object ResetLog : Txn
    }

    private class ChunkStop(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

    /** A start, a step finish or a finish met a log with no room for its next statement: as [Txn.ResetLog]. */
    private class NoRoom : RuntimeException()

    private fun ended(outcome: ReplicaRunOutcome): ChunkResult = ChunkResult.Ended(outcome)
    private fun stopped(reason: String, cause: Throwable? = null): ChunkResult = ended(ReplicaRunOutcome.Stopped(reason, cause))

    /** A chunk's stop by the watchdog: `HOLD_MAX`'s, or the token-expiry interrupt's (B.15). */
    private fun stopReasonOf(e: HoldCeilingExceeded): String = if (isProtectionExpiry(e)) "ProtectionExpired" else "HoldCeilingExceeded"

    /** The statements of one transaction: each under the hold ceiling, each reset before it returns. */
    private inner class Chunk(
        val handle: ReplicaHandle,
        val connection: SqliteConnection,
        /** When `BEGIN IMMEDIATE` returned: the hold is the write lock's, not the body's. */
        private val began: Long,
        /** The hold watchdog's watch on this transaction, armed at the same point. */
        private val watch: ReplicaHold,
    ) {
        private var seqBumped = false

        /** Units of work this transaction has taken. */
        var units = 0

        /** True once the transaction has something to commit. */
        var progressed = false

        /** A line for the log once this transaction has **committed**: a decision is reported when it is durable, not before. */
        var committedNote: String? = null

        fun timeToStop(): Boolean = clock() - began >= limits.chunkTimeStopMs

        /** Between statements: past `HOLD_MAX` by this job's clock, the next one is refused (the watchdog's own refusal is [held]'s). */
        fun hold() {
            val held = clock() - began
            if (held >= limits.holdMaxMs) throw ChunkStop("HoldCeilingExceeded", HoldCeilingExceeded(held, limits.holdMaxMs))
        }

        /**
         * One statement (or a few, as a reading of the marker) under the watchdog: refused if the
         * hold is past `HOLD_MAX`, interrupted if the watchdog fires while it runs. Either way a
         * [ChunkStop]: the chunk is rolled back and the run stops.
         */
        fun <T> held(block: () -> T): T {
            hold()
            try {
                watch.enterStatement()
            } catch (e: HoldCeilingExceeded) {
                throw ChunkStop(stopReasonOf(e), e)
            }
            try {
                return block()
            } catch (e: SqliteException) {
                if (e.primaryCode == SQLITE_INTERRUPT && watch.interruptedAStatement) {
                    val refusal = watch.exceededError(e)
                    throw ChunkStop(stopReasonOf(refusal), refusal)
                }
                throw e
            } finally {
                watch.exitStatement()
            }
        }

        /** The last check before `COMMIT`, which also disarms the watchdog: the commit and its sync are never interrupted. */
        fun beforeCommit() {
            hold()
            try {
                watch.beforeCommit()
            } catch (e: HoldCeilingExceeded) {
                throw ChunkStop(stopReasonOf(e), e)
            }
        }

        fun <T> read(sql: String, body: (SqliteStatement) -> T): T = held {
            val statement = handle.prepared(sql)
            try {
                statementFault?.invoke(sql)
                body(statement)
            } finally {
                statement.reset()
            }
        }

        /** One write statement. ⚠ The caller has asked [fits] for it. */
        fun write(sql: String, bind: (SqliteStatement) -> Unit) {
            read(sql) {
                bind(it)
                it.step()
            }
            progressed = true
        }

        /** `wal + C + h ≤ Wmax` for the statements of the next unit of work, [pages] in all. */
        fun fits(pages: Int): Boolean = limits.fits(walProbe.sizeBytes(), pages)

        /**
         * One single-row statement of a start, a step finish or a finish, admitted by `fits` on
         * its own like any statement. ⚠ These groups are all-or-nothing and run in transactions
         * that have done nothing else, so one that does not fit rolls the transaction back
         * ([NoRoom]): the log is reset and the transaction run again.
         */
        fun metaWrite(sql: String, bind: (SqliteStatement) -> Unit) {
            if (!fits(limits.statementPages)) throw NoRoom()
            write(sql, bind)
        }

        fun metaExec(sql: String) {
            if (!fits(limits.statementPages)) throw NoRoom()
            held { connection.exec(sql) }
            progressed = true
        }

        /** The pages `meta.seq`'s move still needs in this transaction (S4): asked with the first write to `objects`. */
        val seqPages: Int get() = if (seqBumped) 0 else limits.statementPages

        fun bumpSeq() {
            if (seqBumped) return
            write(ReplicaJobSql.BUMP_SEQ) {}
            seqBumped = true
        }
    }

    private inline fun <T> withHandle(kind: ReplicaHandleKind, label: String, block: (ReplicaHandle) -> T): T {
        val handle = registry.register(kind, label)
        handle.onOpened = ::requireStoreSettings
        handle.onClosed = { walProbe.close() }
        current = handle
        validated = false
        try {
            return block(handle)
        } finally {
            try {
                registry.release(handle)
            } catch (_: StoreUnavailable) {
                // Latched and logged by the registry, which finishes the release when it can.
            }
            current = null
            walProbe.close()
            marker.close()
        }
    }

    /**
     * One transaction of a job: `BEGIN IMMEDIATE` through the gate (§4.2), the corruption marker,
     * writer rule 1 (past `Wmax − δ` the ceiling checkpoint first), [body], `COMMIT`. Whatever
     * fails, the transaction is rolled back whole before this returns.
     */
    private fun transaction(handle: ReplicaHandle, label: String, body: (Chunk) -> Txn): ChunkResult {
        registry.lock.lock()
        try {
            var resets = 0
            while (true) {
                if (handle.isReleased) return stopped("the handle was released")
                val connection = try {
                    registry.acquire(handle, label, operation = ReplicaOperation.JOB_CHUNK) { !handle.isReleased } ?: return stopped("the handle was released")
                } catch (e: SqliteException) {
                    // Nothing was begun and the admission is given back (see `acquire`): the
                    // verdict, and no statement on the connection. It is closed with the handle.
                    return failed(handle, e, ReplicaPhase.OPEN_READ_WRITE, label, rollBack = false)
                } catch (e: StoreUnavailable) {
                    return stopped(e.message.orEmpty(), e)
                } catch (e: SqliteUnfixedLibraryException) {
                    return stopped(e.message.orEmpty(), e)
                } catch (e: IllegalStateException) {
                    return stopped("the handle is being released", e)
                }
                // ⚠ The hold starts HERE, with the write lock just obtained: the marker lookup and
                // the -wal probe below run under it, and a slow one counts against HOLD_MAX.
                val lockedAt = clock()
                val watch = registry.holdWatchdog.arm(label, connection, limits.holdMaxMs)
                inAdmission = true
                try {
                    when (marker.state()) {
                        ReplicaMarkerState.ABSENT -> Unit
                        ReplicaMarkerState.DURABLE -> {
                            rollback(handle)
                            return ended(ReplicaRunOutcome.Corrupt("replica/CORRUPT exists"))
                        }
                        ReplicaMarkerState.NOT_DURABLE -> {
                            rollback(handle)
                            return stopped("replica/CORRUPT is there and could not be made durable")
                        }
                    }
                    if (walProbe.sizeBytes() <= limits.beginCeilingBytes) {
                        val chunk = Chunk(handle, connection, lockedAt, watch)
                        when (val txn = body(chunk)) {
                            is Txn.Commit -> {
                                chunk.beforeCommit()
                                beforeCommit?.invoke(label)
                                try {
                                    connection.exec("COMMIT")
                                } catch (e: SqliteException) {
                                    return failed(handle, e, ReplicaPhase.COMMIT, "COMMIT of $label")
                                }
                                commits++
                                chunk.committedNote?.let(::println)
                                // The chunk stands. What the checkpoint after it found can still
                                // end the run: no next chunk on a store just found corrupt.
                                return afterCommit(label) ?: txn.result
                            }
                            is Txn.Rollback -> {
                                rollback(handle)
                                return txn.result
                            }
                            Txn.ResetLog -> Unit
                        }
                    }
                    rollback(handle)
                } catch (e: SqliteException) {
                    return failed(handle, e, ReplicaPhase.STATEMENT, label)
                } catch (e: ReplicaDamaged) {
                    // Read under the write lock: no sibling's commit can be half of it. The marker
                    // is written before the lock is given up (B.3 (27)).
                    return corrupt(e.detail).also { rollback(handle) }
                } catch (e: ReplicaFileException) {
                    rollback(handle)
                    return stopped(e.message.orEmpty(), e)
                } catch (e: ChunkStop) {
                    rollback(handle)
                    return stopped(e.reason, e.cause)
                } catch (_: NoRoom) {
                    rollback(handle)
                } catch (e: Exception) {
                    // A migration's own failure on a row: nothing of this chunk stays.
                    rollback(handle)
                    return stopped("$label failed: ${e.message}", e)
                } finally {
                    // After every rollback above has returned, or the COMMIT was issued.
                    watch.disarm()
                    inAdmission = false
                    registry.gate.leave()
                }
                // Only a log that must be reset comes here, with no transaction open.
                beforeLogReset?.invoke()
                if (handle.isReleased) return stopped("a ROLLBACK failed")
                if (++resets > MAX_RESETS) return stopped("WalResetPending: no unit of $label fits the -wal after $MAX_RESETS resets")
                // ⚠⚠ The checkpoint is an access of its own and has its own admission: the
                // transaction's was given back above, and a gate that has drained means quiesce
                // may be closing every connection. Every touch of the database a job makes (a
                // chunk, this checkpoint with its rewind write and its size probe, the
                // opportunistic one after a commit) is inside an admission.
                if (!registry.gate.enter()) return stopped("the gate is closed")
                inAdmission = true
                try {
                    when (val outcome = checkpointer.run(SqliteCheckpointMode.RESTART)) {
                        ReplicaCheckpointOutcome.Reset -> Unit
                        ReplicaCheckpointOutcome.NotReset -> return stopped("WalResetPending: the -wal is past the ceiling and its reset is not verified")
                        is ReplicaCheckpointOutcome.Marked -> return marked(outcome.state)
                        is ReplicaCheckpointOutcome.CorruptionCode -> return corruptionCode(outcome.message, outcome.inARow)
                    }
                } finally {
                    inAdmission = false
                    registry.gate.leave()
                }
            }
        } finally {
            registry.lock.unlock()
        }
    }

    /**
     * Belt and braces on the ceiling, then the opportunistic checkpoint past `Wmax/4`. Null, or
     * how the run ends instead of what the committed chunk answered: a corruption code from the
     * checkpoint ends the run (and marks the store if a second reading confirms it), and a marker
     * found fences it, exactly as on the reset path.
     */
    private fun afterCommit(label: String): ChunkResult? {
        val wal = try {
            walProbe.sizeBytes()
        } catch (e: ReplicaFileException) {
            println("[replica] the -wal could not be measured after $label: ${e.message}")
            return null
        }
        largestWalBytes = maxOf(largestWalBytes, wal)
        if (wal > limits.wmaxBytes + limits.deltaBytes) {
            ceilingBreaches++
            println("[replica] WAL ceiling exceeded: $label left the -wal at $wal bytes")
        }
        if (wal <= limits.journalSizeLimitBytes) return null
        return when (val outcome = checkpointer.run(SqliteCheckpointMode.PASSIVE)) {
            // Not reset is no failure of a PASSIVE checkpoint: the ceiling's RESTART will.
            ReplicaCheckpointOutcome.Reset, ReplicaCheckpointOutcome.NotReset -> null
            is ReplicaCheckpointOutcome.Marked -> marked(outcome.state)
            is ReplicaCheckpointOutcome.CorruptionCode -> corruptionCode(outcome.message, outcome.inARow)
        }
    }

    private fun marked(state: ReplicaMarkerState): ChunkResult =
        if (state == ReplicaMarkerState.DURABLE) ended(ReplicaRunOutcome.Corrupt("replica/CORRUPT exists"))
        else stopped("replica/CORRUPT is there and could not be made durable")

    /** A rollback that fails leaves an unknown connection: the handle is released, which closes it. */
    private fun rollback(handle: ReplicaHandle) {
        val connection = handle.connection ?: return
        try {
            handle.statements.values.forEach { it.reset() }
            if (!connection.inAutocommit()) connection.exec("ROLLBACK")
        } catch (e: SqliteException) {
            println("[replica] ROLLBACK of a job chunk failed: ${e.message}; its handle is released")
            try {
                registry.release(handle)
            } catch (_: StoreUnavailable) {
                // Latched and logged by the registry.
            }
        }
    }

    /** §5.2.2 for a job's transaction: a corruption code is confirmed first, anything else stops the run. */
    private fun failed(
        handle: ReplicaHandle,
        e: SqliteException,
        phase: ReplicaPhase,
        what: String,
        /** False only where no transaction can be open and the admission is gone: after a failed `BEGIN`. */
        rollBack: Boolean = true,
    ): ChunkResult {
        val verdict = ReplicaVerdicts.of(e.primaryCode, phase, protectedDataAvailable = protectedDataAvailable())
        val result = if (verdict == ReplicaVerdict.CORRUPT) corruptionCode(e.message.orEmpty())
        else stopped("$what failed (rc=${e.resultCode}): ${e.message}", e)
        if (rollBack) rollback(handle)
        return result
    }

    /**
     * SQLite answered a corruption code in this job's transaction, at its `BEGIN` or in a
     * checkpoint it ran. One answer does not write the permanent marker (B.3 (45)): the run
     * ends here either way; its connection is rolled back and closed by the confirmation; and
     * only a second reading that agrees makes it [ReplicaRunOutcome.Corrupt]. Otherwise
     * [ReplicaRunOutcome.Stopped]: every committed chunk stays, and the next run goes on.
     */
    private fun corruptionCode(detail: String, checkpointCodesInARow: Int? = null): ChunkResult =
        when (
            // A checkpoint's code follows the checkpoint's rule, whoever ran it: the second in a
            // row of this process, the runtime handle's included, is the confirmation.
            val outcome = if (checkpointCodesInARow == null) confirmation.run(detail, failing = current, alreadyAdmitted = inAdmission)
            else confirmation.runForCheckpoint(detail, checkpointCodesInARow, failing = current, alreadyAdmitted = inAdmission)
        ) {
            is ReplicaCorruptionConfirmation.Confirmed -> ended(ReplicaRunOutcome.Corrupt(outcome.detail))
            is ReplicaCorruptionConfirmation.Unconfirmed ->
                stopped("SQLite answered a corruption code that was not confirmed (${outcome.reason}): $detail", outcome.cause)
        }

    /** A damage verdict this job read for itself under the write lock (B.4 path 8): the marker, at once. */
    private fun corrupt(detail: String): ChunkResult =
        try {
            registry.admittedForMarker(inAdmission) { marker.write(corruptMarkerDetail(files, appName, detail)) }
            println("[replica] the store is CORRUPT: $detail")
            ended(ReplicaRunOutcome.Corrupt(detail))
        } catch (e: ReplicaFileException) {
            println("[replica] could NOT write the corruption marker: ${e.message}")
            stopped("corruption was detected and the marker could not be written: $detail", e)
        }

    // ── what every chunk reads first ──

    private fun recorded(reading: ReplicaReading): Map<String, Int> =
        reading.serviceVersions.orEmpty() + (CORE_SERVICE_ID to reading.userVersion)

    /**
     * §5.2 step 3: the schema of the recorded layout, once per handle. ⚠ Only ever of THIS
     * build's layout: [classify], run first in every job transaction, has turned away a newer
     * layout (`Skew`) and an older one ([ReplicaClassification.OlderLayout]) before this is
     * reached, so the schema this build knows is the schema the store is on.
     */
    private fun validate(chunk: Chunk, reading: ReplicaReading) {
        check(reading.layout == build.layout) { "a store of layout ${reading.layout} reached validation" }
        if (validated) return
        chunk.hold()
        chunk.held { ReplicaSchema.validate(chunk.connection) }?.let { throw ReplicaDamaged("schema validation: $it") }
        validated = true
    }

    private inline fun <T> decoded(key: String, decoded: DecodedMarker<T>, orElse: (ChunkResult) -> Nothing): T = when (decoded) {
        is DecodedMarker.Value -> decoded.marker
        is DecodedMarker.OtherFormat ->
            orElse(ended(ReplicaRunOutcome.Skew("meta.$key is a frame of format ${decoded.format}, which this build does not know")))
        DecodedMarker.Malformed -> throw ReplicaDamaged("meta.$key carries a format this build writes and is not a frame of it")
    }

    /**
     * One chunk of whichever job the marker names **now**: the store and its marker judged against
     * this build first ([classify], which holds the whole resumer rule: every recorded stamp, and
     * the marker's format, targets and end stamps), then the work.
     */
    private fun chunk(chunk: Chunk): Txn {
        val reading = chunk.held { readMarker(chunk.connection) }
        when (val verdict = classify(reading, build)) {
            is ReplicaClassification.Skew -> return Txn.Rollback(ended(ReplicaRunOutcome.Skew(verdict.detail)))
            is ReplicaClassification.OlderLayout -> return Txn.Rollback(stopped(verdict.detail))
            is ReplicaClassification.Damaged -> throw ReplicaDamaged(verdict.detail)
            // No marker: the job is done, by this run or by a sibling.
            ReplicaClassification.Fresh, ReplicaClassification.Compatible, is ReplicaClassification.Behind ->
                return Txn.Rollback(ended(ReplicaRunOutcome.Finished))
            is ReplicaClassification.Updating -> Unit
        }
        validate(chunk, reading)
        reading.wipeMarker?.let { frame ->
            return wipeChunk(chunk, decoded(ReplicaSchema.META_WIPE, WipeMarker.decode(frame)) { return Txn.Rollback(it) })
        }
        val migration = decoded(ReplicaSchema.META_MIGRATION, MigrationMarker.decode(reading.migrationMarker!!)) { return Txn.Rollback(it) }
        // ⚠⚠ S13, asked by EVERY chunk, under the lock (Appendix B.11 (146)): a migration is
        // started or resumed only in the foreground. Asked where a job is handed out (the open,
        // `admit`) it was not enough: a run obtained for a wipe in a wake can meet a migration a
        // sibling started meanwhile (B.3 (56)), and a run begun in the foreground goes on after the
        // app is backgrounded. Refused, nothing is written and the run ends `NeedsForeground`.
        if (!registry.gate.inForeground()) return Txn.Rollback(ended(ReplicaRunOutcome.NeedsForeground))
        return migrationChunk(chunk, migration, recorded(reading))
    }

    // ── the wipe ──

    private fun startWipe(chunk: Chunk, reason: String, endStamps: Map<String, Int>) {
        ReplicaJobSql.startWipe(WipeMarker(reason, appName, files.now().first, endStamps), chunk::metaWrite)
    }

    /** A table the wipe empties, with the statements derived from the layout ([ReplicaSchema]). */
    private class WipedTable(table: ReplicaTable) {
        val name = table.name
        private val key = table.columns.single { it.primaryKey }
        val integerKey = key.type == "INTEGER"

        // length() of a BLOB column reads the record header, never the overflow chain.
        private val size = table.columns.filter { it.type != "INTEGER" }
            .joinToString(" + ") { if (it.type == "BLOB") "length(${it.name})" else "length(CAST(${it.name} AS BLOB))" }
            .ifEmpty { "0" }
        val pageSql = "SELECT ${key.name}, $size FROM $name ORDER BY ${key.name} LIMIT ?"
        val deleteSql = "DELETE FROM $name WHERE ${key.name} = ?"
    }

    private val wipedTables: List<WipedTable> by lazy {
        ReplicaSchema.tables.filter { it.onWipe == ReplicaWipe.EMPTIED }.map(::WipedTable)
    }

    /**
     * A middle chunk (single-row deletes over a page of each emptied table, in the layout's
     * order) or, when every one of them is empty, the last: the marker cleared, its stamps
     * written, the epoch moved. There is no cursor: deleting is idempotent.
     */
    private fun wipeChunk(chunk: Chunk, wipe: WipeMarker): Txn {
        val more = Txn.Commit(ChunkResult.More(ReplicaJob.WIPE))
        for (table in wipedTables) {
            val room = limits.chunkMaxRows - chunk.units
            val page = chunk.read(table.pageSql) { rows ->
                rows.bindLong(1, room.toLong())
                val found = ArrayList<Pair<Any, Int>>(minOf(room, 1_024))
                while (rows.step()) found += (if (table.integerKey) rows.getLong(0) else rows.getText(0)) to rows.getLong(1).toInt()
                found
            }
            for ((key, bytes) in page) {
                if (chunk.units > 0 && chunk.timeToStop()) return more
                val objects = table.name == ReplicaSchema.OBJECTS
                val pages = limits.headroomPages(0, bytes + ROW_HEADER_BYTES) + if (objects) chunk.seqPages else 0
                if (!chunk.fits(pages)) return if (chunk.progressed) more else Txn.ResetLog
                if (objects) chunk.bumpSeq()
                chunk.write(table.deleteSql) { if (key is Long) it.bindLong(1, key) else it.bindText(1, key as String) }
                chunk.units++
            }
            // A full page: this table may hold more, and the chunk has its rows.
            if (page.size == room) return more
        }
        if (chunk.progressed) return more

        // The last chunk: it found every table empty.
        chunk.metaWrite(ReplicaJobSql.DELETE_META) { it.bindText(1, ReplicaSchema.META_WIPE) }
        for ((id, version) in wipe.endStamps) stamp(chunk, id, version)
        ReplicaJobSql.moveEpoch(chunk::metaWrite)
        return Txn.Commit(ended(ReplicaRunOutcome.Finished))
    }

    private fun stamp(chunk: Chunk, serviceId: String, version: Int) {
        if (serviceId == CORE_SERVICE_ID) {
            chunk.metaExec("PRAGMA user_version = $version")
        } else {
            chunk.metaWrite(STAMP_SQL) {
                it.bindText(1, serviceId)
                it.bindLong(2, version.toLong())
            }
        }
    }

    // ── the migration ──

    /** A stored row's key and type, the size of its payload and whether a committed chunk processed it. Never its bytes. */
    private class StoredRow(val hash: Long, val type: String, val payloadBytes: Int, val done: Boolean)

    /**
     * The next rows of [typeIds] in hash order, after [after] (null: from the first row, with no
     * lower bound at all), each with whether a committed chunk already processed it. Each type is
     * read through `objects_type` and the pages merged (B.3 (11)); a type's page that came back
     * full bounds the merge, so no row of another type is taken past a row that was not read.
     * Empty only when no row is left.
     *
     * ⚠⚠ **Keys only.** A page holds a hash, a size and a flag per row, a few dozen bytes each
     * whatever the rows weigh; the bytes of a row are read when its turn comes ([rowBytes]), one
     * row at a time. A page of blobs per type would be `types × page` of memory at once, and a
     * step over a broad schema would be killed for it on every retry, with the store left
     * behind its marker.
     */
    private fun rowsAfter(chunk: Chunk, typeIds: Set<String>, after: Long?): List<StoredRow> {
        var bound: Long? = null
        val all = ArrayList<StoredRow>()
        for (typeId in typeIds) {
            chunk.read(if (after == null) FIRST_ROWS_SQL else NEXT_ROWS_SQL) { rows ->
                var index = 1
                rows.bindText(index++, typeId)
                if (after != null) rows.bindLong(index++, after)
                rows.bindLong(index, limits.chunkMaxRows.toLong())
                var taken = 0
                var last = 0L
                while (rows.step()) {
                    last = rows.getLong(0)
                    all += StoredRow(last, typeId, rows.getLong(1).toInt() + ROW_HEADER_BYTES, rows.getLong(2) != 0L)
                    taken++
                }
                if (taken == limits.chunkMaxRows) bound = minOf(bound ?: Long.MAX_VALUE, last)
            }
        }
        val within = bound?.let { limit -> all.filter { it.hash <= limit } } ?: all
        return within.sortedBy { it.hash }.take(limits.chunkMaxRows)
    }

    /** The bytes of one stored row, held only while that row is worked on. */
    private fun rowBytes(chunk: Chunk, hash: Long): ByteArray {
        val data = chunk.read(SharedReplicaStore.POINT_READ_SQL) { read ->
            read.bindLong(1, hash)
            if (read.step()) read.getBlob(0) else null
        } ?: throw ChunkStop("row $hash was in the page and is not in the store")
        largestRowBytesHeld = maxOf(largestRowBytesHeld, data.size.toLong())
        return data
    }

    private fun isEmpty(chunk: Chunk, table: String): Boolean = chunk.read("SELECT 1 FROM $table LIMIT 1") { !it.step() }

    private fun migrationChunk(chunk: Chunk, marker: MigrationMarker, recorded: Map<String, Int>): Txn {
        val stamped = recorded[marker.stepService] ?: MigrationMarker.UNSTAMPED
        if (stamped != marker.stepFrom) {
            // The step finish stamps and rewrites the marker in one transaction.
            throw ReplicaDamaged("meta.migration is at '${marker.stepService}' v${marker.stepFrom} but the store is stamped v$stamped")
        }
        if (marker.stepFrom == MigrationMarker.UNSTAMPED) return stepFinish(chunk, marker, recorded)
        val step = steps.step(marker.stepService, marker.stepFrom)
        if (step == null) {
            // At or above every target, and no step for the current one: a chain cut out from
            // under the marker. Nothing can read these rows, so this is the below-floor case.
            println("[replica] no step for '${marker.stepService}' v${marker.stepFrom}: the store is wiped")
            startWipe(chunk, "no-step", endStamps = declared)
            return Txn.Commit(ChunkResult.More(ReplicaJob.WIPE))
        }
        return when (marker.phase) {
            MigrationPhase.ROWS, MigrationPhase.VERIFY -> rowsChunk(chunk, marker, recorded, step)
            MigrationPhase.SWEEP -> sweepChunk(chunk, marker, recorded)
            MigrationPhase.CLEANUP -> cleanupChunk(chunk, marker, recorded)
        }
    }

    private fun moveTo(chunk: Chunk, marker: MigrationMarker): Txn {
        ReplicaJobSql.writeMigration(marker, chunk::metaWrite)
        return Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
    }

    /**
     * The row phase and the verification after it, which are one loop read twice. `ROWS` walks
     * the step's rows by the cursor. `VERIFY` walks them all again from before the first row,
     * independent of that cursor, and migrates any row no committed chunk recorded in
     * `migration_done`; a row that is recorded is never migrated again, in either pass.
     *
     * An object's statements (its rewrite or delete, its `migration_done` row, its
     * `migration_dropped` row if dropped) are admitted together or not at all, and the cursor is
     * the last row this chunk took, never the end of the page it read.
     */
    private fun rowsChunk(chunk: Chunk, marker: MigrationMarker, recorded: Map<String, Int>, step: ReplicaMigrationStep): Txn {
        val more = Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
        val page = if (step.typeIds.isEmpty()) emptyList() else rowsAfter(chunk, step.typeIds, marker.cursor)
        if (page.isEmpty()) {
            val next = when {
                marker.phase == MigrationPhase.ROWS && step.typeIds.isNotEmpty() -> MigrationPhase.VERIFY
                isEmpty(chunk, ReplicaSchema.MIGRATION_DROPPED) -> return cleanupChunk(chunk, marker, recorded)
                else -> MigrationPhase.SWEEP
            }
            return moveTo(chunk, marker.entering(next))
        }
        var cursor = marker.cursor
        for (row in page) {
            if (chunk.units > 0 && chunk.timeToStop()) break
            if (!row.done) {
                // Read through SQLite, so outside the counted region: a failure here is SQLite's.
                val bytes = rowBytes(chunk, row.hash)
                val work = rowWork {
                    val stored = DbEntryCodec.decode(bytes)
                    // ⚠ A step would see one segment of a list as if it were the list (B.19 (340)).
                    if (ReplicaListSegments.isLinkedHead(stored)) throw SegmentedListNotMigratable(row.hash)
                    val made = step.migrate(stored)
                    check(made == null || made.dataIdHash == row.hash) { "a migration moved a row to another hash" }
                    val encoding = made?.let(DbEntryCodec::encode)
                    if (encoding != null && encoding.size > limits.rowMaxBytes) throw RowTooLarge(encoding.size, limits.rowMaxBytes)
                    Migrated(made?.type ?: row.type, encoding)
                }
                val (type, encoded) = work.value ?: return rowFailed(chunk, marker, cursor, row, work.failure)
                val payload = if (encoded == null) 0 else encoded.size + type.encodeToByteArray().size + ROW_HEADER_BYTES
                val pages = limits.headroomPages(payload, row.payloadBytes) + chunk.seqPages +
                    limits.statementPages * (if (encoded == null) 2 else 1) + limits.statementPages // the cursor
                if (!chunk.fits(pages)) {
                    if (cursor == marker.cursor && !chunk.progressed) return Txn.ResetLog
                    break
                }
                chunk.bumpSeq()
                if (encoded == null) {
                    chunk.write(DELETE_ROW_SQL) { it.bindLong(1, row.hash) }
                    chunk.write(INSERT_DROPPED_SQL) { it.bindLong(1, row.hash) }
                } else {
                    chunk.read(SharedReplicaStore.UPSERT_SQL) { upsert ->
                        upsert.bindLong(1, row.hash)
                        upsert.bindText(2, type)
                        upsert.bindBlob(3, encoded)
                        upsert.step()
                        upsert.reset()
                        upsert.bindNull(3)
                    }
                    chunk.progressed = true
                }
                chunk.write(INSERT_DONE_SQL) { it.bindLong(1, row.hash) }
                chunk.units++
            }
            cursor = row.hash
        }
        if (cursor == marker.cursor) return Txn.ResetLog // the time stop before the first row: not reachable, units > 0 guards it
        if (!chunk.progressed && !chunk.fits(limits.statementPages)) return Txn.ResetLog
        ReplicaJobSql.writeMigration(marker.at(cursor), chunk::write)
        return more
    }

    /** What a step made of one stored row: its type and its encoding as the step's `to` version stores it, or no encoding for a row dropped by decision. */
    private data class Migrated(val type: String, val encoded: ByteArray?)

    /** [value], or why the row could not be worked: exactly one of the two is set. */
    private class RowWork<T : Any>(val value: T?, val failure: Exception?)

    /**
     * ⚠⚠ **The counted region** (Appendix B.3 (41), (53)): what is done to one stored row between
     * reading its bytes and writing its result. The store's decode, the step, the hash rule, the
     * encode and `ROW_MAX` are in it, and **nothing else may be**: no statement, no file, no
     * gate, no clock. So whatever fails in here failed on the row itself and fails again on the
     * next run, which is the one kind of failure that is counted; everything a chunk meets
     * outside it (SQLite, the budget, the hold, the gate, a kill) is never counted.
     *
     * ⚠ A stored row that does not **decode** is counted too, not sent to the corruption path:
     * SQLite read its bytes without complaint, so no second reading could confirm anything (a
     * record malformed inside a well-formed page is what `quick_check` cannot see, B.3 (46)), and
     * the store would stay "updating" for good. A row SQLite itself cannot read answers a
     * corruption code from [rowBytes], outside this region, and is confirmed as any code is.
     */
    private inline fun <T : Any> rowWork(work: () -> T): RowWork<T> =
        try {
            RowWork(work(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RowWork(null, e)
        }

    /**
     * A step (or the sweep) failed on [row], with [cursor] the last row this chunk took before it.
     *
     * - **The chunk has written something:** it commits that, with its cursor, and counts
     *   nothing. The next chunk meets the row first, in a transaction that has done nothing else.
     * - **Otherwise this is the counted attempt**, in this same transaction, so the count commits
     *   or is lost with nothing else and can never be written twice for one failure: the marker
     *   with one more failure for (row, this build's declared version of the step's service), and
     *   **the run stops**: at most one count per run. A record of another row or of another
     *   version is replaced, at one.
     * - **At `N` no count is written: the job becomes a wipe here**, exactly as a chain with no
     *   step does (B.3 (40)): this transaction is a wipe's first chunk, so the epoch moves in it,
     *   and the wipe's last chunk stamps this build's versions.
     *
     * Both writes are a start's kind: all-or-nothing, admitted statement by statement, in a
     * transaction that has done nothing else ([Chunk.metaWrite]).
     */
    private fun rowFailed(chunk: Chunk, marker: MigrationMarker, cursor: Long?, row: StoredRow, cause: Exception?): Txn {
        if (chunk.progressed) {
            ReplicaJobSql.writeMigration(marker.at(cursor), chunk::write)
            return Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
        }
        val limit = limits.migrationRowAttempts
        val version = declared.getValue(marker.stepService)
        val counted = marker.failure?.takeIf { it.hash == row.hash && it.version == version }?.count ?: 0
        // Never the cause's message: it is the step's to word and may quote the row.
        val failed = MigrationRowFailed(marker.stepService, marker.stepFrom, row.type, counted + 1, limit, cause)
        if (counted + 1 >= limit) {
            startWipe(chunk, "migration-failed", endStamps = declared)
            chunk.committedNote = "[replica] ${failed.message}: the migration is given up and the store is wiped"
            return Txn.Commit(ChunkResult.More(ReplicaJob.WIPE))
        }
        val recorded = marker.at(cursor).copy(failure = MigrationFailure(row.hash, version, counted + 1, limit))
        ReplicaJobSql.writeMigration(recorded, chunk::metaWrite)
        chunk.committedNote = "[replica] ${failed.message}; the run stops"
        return Txn.Commit(stopped("a migration step failed on a row", failed))
    }

    /**
     * `TList` rows by keyset, each rewritten without the members a migration dropped. Idempotent.
     * ⚠⚠ **And every list segment** (B.19 (340)): a list of several segments keeps its members in
     * its head and its segment rows alike, and each is filtered in place, its link kept, so the
     * chain holds together and needs no planning here. A segment left short or empty is valid;
     * the list's next write lays it out again.
     */
    private fun sweepChunk(chunk: Chunk, marker: MigrationMarker, recorded: Map<String, Int>): Txn {
        val page = if (isEmpty(chunk, ReplicaSchema.MIGRATION_DROPPED)) emptyList() else rowsAfter(chunk, SWEPT_TYPES, marker.cursor)
        if (page.isEmpty()) return moveTo(chunk, marker.entering(MigrationPhase.CLEANUP))
        var cursor = marker.cursor
        for (row in page) {
            if (chunk.units > 0 && chunk.timeToStop()) break
            val bytes = rowBytes(chunk, row.hash)
            val decoding = rowWork { DbEntryCodec.decode(bytes) }
            val array = decoding.value ?: return rowFailed(chunk, marker, cursor, row, decoding.failure)
            val kept = array.fields.filterNot { member ->
                member is DbValue.LongValue && chunk.read(IS_DROPPED_SQL) {
                    it.bindLong(1, member.value)
                    it.step()
                }
            }
            if (kept.size != array.fields.size) {
                val encoding = rowWork { DbEntryCodec.encode(array.copy(fields = kept)) }
                val encoded = encoding.value ?: return rowFailed(chunk, marker, cursor, row, encoding.failure)
                val payload = encoded.size + array.type.encodeToByteArray().size + ROW_HEADER_BYTES
                val pages = limits.headroomPages(payload, row.payloadBytes) + chunk.seqPages + limits.statementPages
                if (!chunk.fits(pages)) {
                    if (cursor == marker.cursor && !chunk.progressed) return Txn.ResetLog
                    break
                }
                chunk.bumpSeq()
                chunk.read(SharedReplicaStore.UPSERT_SQL) { upsert ->
                    upsert.bindLong(1, row.hash)
                    upsert.bindText(2, array.type)
                    upsert.bindBlob(3, encoded)
                    upsert.step()
                    upsert.reset()
                    upsert.bindNull(3)
                }
                chunk.progressed = true
            }
            chunk.units++
            cursor = row.hash
        }
        if (!chunk.progressed && !chunk.fits(limits.statementPages)) return Txn.ResetLog
        ReplicaJobSql.writeMigration(marker.at(cursor), chunk::write)
        return Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
    }

    /** Empties the step's two bookkeeping tables by single-row deletes; then the step finish. */
    private fun cleanupChunk(chunk: Chunk, marker: MigrationMarker, recorded: Map<String, Int>): Txn {
        val more = Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
        for (table in listOf(ReplicaSchema.MIGRATION_DONE, ReplicaSchema.MIGRATION_DROPPED)) {
            val room = limits.chunkMaxRows - chunk.units
            val page = chunk.read("SELECT hash FROM $table ORDER BY hash LIMIT ?") { rows ->
                rows.bindLong(1, room.toLong())
                val found = ArrayList<Long>(minOf(room, 1_024))
                while (rows.step()) found += rows.getLong(0)
                found
            }
            for (hash in page) {
                if (chunk.units > 0 && chunk.timeToStop()) return more
                if (!chunk.fits(limits.statementPages)) return if (chunk.progressed) more else Txn.ResetLog
                chunk.write("DELETE FROM $table WHERE hash = ?") { it.bindLong(1, hash) }
                chunk.units++
            }
            if (page.size == room) return more
        }
        if (chunk.progressed) return more
        return stepFinish(chunk, marker, recorded)
    }

    /**
     * The step finish, O(1), with both bookkeeping tables empty: the step's stamp; then the
     * marker rewritten to the chain's next step with **the epoch left alone**, or, after the last
     * step, the chain's Finish: the marker cleared and the epoch moved.
     *
     * A build whose own chain goes further than the marker's targets extends them here, so the
     * marker is cleared only at this build's versions (§5.3, "a resumer whose chain differs").
     */
    private fun stepFinish(chunk: Chunk, marker: MigrationMarker, recorded: Map<String, Int>): Txn {
        stamp(chunk, marker.stepService, marker.stepTo)
        val stamps = recorded + (marker.stepService to marker.stepTo)
        val targets = marker.targets + declared.filter { (id, version) -> (stamps[id] ?: MigrationMarker.UNSTAMPED) < version }
        val next = targets.keys.sorted().firstOrNull { (stamps[it] ?: MigrationMarker.UNSTAMPED) < targets.getValue(it) }
        if (next != null) {
            ReplicaJobSql.writeMigration(
                marker.copy(
                    targets = targets,
                    stepService = next,
                    stepFrom = stamps[next] ?: MigrationMarker.UNSTAMPED,
                    phase = MigrationPhase.ROWS,
                    cursor = null,
                    failure = null,
                ),
                chunk::metaWrite,
            )
            return Txn.Commit(ChunkResult.More(ReplicaJob.MIGRATION))
        }
        chunk.metaWrite(ReplicaJobSql.DELETE_META) { it.bindText(1, ReplicaSchema.META_MIGRATION) }
        ReplicaJobSql.moveEpoch(chunk::metaWrite)
        return Txn.Commit(ended(ReplicaRunOutcome.Finished))
    }

    private companion object {
        const val MAX_RESETS = 3

        /** The rows the sweep filters: every list, and every segment of one (B.19 (340)). */
        val SWEPT_TYPES: Set<String> = setOf(TList.ID, ReplicaListSegments.SEGMENT_TYPE)

        /** A record's header and serial types: a loose few bytes, as the store's own. */
        const val ROW_HEADER_BYTES = 16

        const val STAMP_SQL =
            "INSERT INTO ${ReplicaSchema.SERVICE_VERSIONS} (service_id, version) VALUES (?, ?) " +
                "ON CONFLICT(service_id) DO UPDATE SET version = excluded.version"
        // length() of a BLOB column reads the record header, never the overflow chain.
        private const val ROW_COLUMNS =
            "hash, length(data) + length(CAST(type AS BLOB)), " +
                "EXISTS (SELECT 1 FROM ${ReplicaSchema.MIGRATION_DONE} d WHERE d.hash = objects.hash)"
        const val FIRST_ROWS_SQL = "SELECT $ROW_COLUMNS FROM objects WHERE type = ? ORDER BY hash LIMIT ?"
        const val NEXT_ROWS_SQL = "SELECT $ROW_COLUMNS FROM objects WHERE type = ? AND hash > ? ORDER BY hash LIMIT ?"
        const val DELETE_ROW_SQL = "DELETE FROM objects WHERE hash = ?"
        const val INSERT_DONE_SQL = "INSERT OR IGNORE INTO ${ReplicaSchema.MIGRATION_DONE} (hash) VALUES (?)"
        const val INSERT_DROPPED_SQL = "INSERT OR IGNORE INTO ${ReplicaSchema.MIGRATION_DROPPED} (hash) VALUES (?)"
        const val IS_DROPPED_SQL = "SELECT 1 FROM ${ReplicaSchema.MIGRATION_DROPPED} WHERE hash = ?"
    }
}

private val jobIds = kotlin.concurrent.AtomicLong(0L)

private fun nextJobId(): Long = jobIds.incrementAndGet()
