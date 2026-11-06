@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.time.TimeSource

/** How one checkpoint ended. Only [Reset] lets a deferred write go on. */
internal sealed interface ReplicaCheckpointOutcome {
    /** The `-wal` is at or below `Wmax − δ`: only a restarted log can be. */
    data object Reset : ReplicaCheckpointOutcome

    /** Skipped, busy, failed or unverifiable: the next trigger retries. */
    data object NotReset : ReplicaCheckpointOutcome

    /** `replica/CORRUPT` is there: no checkpoint and no rewind write started (S20). */
    data class Marked(val state: ReplicaMarkerState) : ReplicaCheckpointOutcome

    /**
     * SQLite answered a corruption code, once. Nothing is marked on it: the caller has it
     * confirmed on a second connection first ([ReplicaCorruptionConfirm]). This checkpoint's own
     * connection is closed and its handle released by the time the caller sees this.
     */
    data class CorruptionCode(
        val message: String,
        /**
         * How many checkpoints of this process in a row have now answered one, this one
         * included ([ReplicaHandles.checkpointCodesInARow]). From 2 the code is its own second
         * reading: the same operation, asked again on another connection.
         */
        val inARow: Int,
    ) : ReplicaCheckpointOutcome
}

/**
 * One checkpoint and the rewind write after it (§4.2.1's table), on a `CHECKPOINT` connection of
 * its own, outside any transaction. Shared by the runtime handle's accesses and by the chunks of
 * a wipe or a migration, so there is one implementation of the ceiling.
 *
 * - Step 0, and again inside the rewind write's transaction, once it holds the write lock: with
 *   `replica/CORRUPT` present nothing starts and nothing is committed.
 * - `RESTART` is repeated in slices for at most `B_ckpt`, with the gate re-met between them and
 *   a pause after a slice that came back at once (a sibling checkpointing answers `SQLITE_BUSY`
 *   without waiting). `PASSIVE` runs once and never waits.
 * - The rewind write runs only when every frame was copied.
 *
 * ⚠ **Any failure is "skipped", the marker lookup of step 0 included**: nothing escapes this as an
 * exception, so a caller at the ceiling answers `WalResetPending` for every way a reset can fail
 * to be verified. The caller holds the registry's lock and no transaction.
 *
 * ⚠⚠ **The caller holds a gate admission for the whole call.** A checkpoint is an access (§4.2.1):
 * it, its rewind write and the size probe after it all touch the database, so none may run once
 * `closeAndDrain` has reported the gate drained. This class does not enter the gate itself (its
 * callers are already inside one, the runtime handle's access or a job's own admission); it only
 * stops waiting between `RESTART` slices once the gate has closed.
 */
internal class ReplicaCheckpointer(
    private val registry: ReplicaHandles,
    private val marker: ReplicaCorruptMarker,
    private val walProbe: ReplicaWalProbe,
    private val protectedDataAvailable: () -> Boolean,
) {
    private val limits: ReplicaLimits get() = registry.limits

    /** Fault injection: called with the mode before the checkpoint itself; a throw is its failure. Null in production. */
    internal var fault: ((SqliteCheckpointMode) -> Unit)? = null

    /** Fault injection: thrown from the `ROLLBACK` of a rewind that found the marker. Null in production. */
    internal var rollbackFault: (() -> Unit)? = null

    /** Test seam: called when every frame was copied, before the rewind write takes its lock. Null in production. */
    internal var beforeRewind: (() -> Unit)? = null

    /** Test seam: checkpoints the token-expiry interrupt stopped (B.15), which no ceiling count includes. */
    internal var expiryStops: Int = 0
        private set

    fun run(mode: SqliteCheckpointMode): ReplicaCheckpointOutcome {
        var job: ReplicaHandle? = null
        var watch: ReplicaHold? = null
        try {
            // §4.2.1: a checkpoint (on its own new connection, so a possible index rebuild too)
            // starts only with its time left; refused, it is "skipped" like any other way it can
            // fail to reset, so at the ceiling the waiting write answers WalResetPending.
            // ⚠ Checked BEFORE the marker: looking for an untrusted marker can
            // F_FULLFSYNC the directory, the very work the check reserves 2·S_full for. With the
            // time there, a marked store still answers Marked, never "skipped".
            val operation = if (mode == SqliteCheckpointMode.RESTART) ReplicaOperation.CHECKPOINT_RESTART else ReplicaOperation.CHECKPOINT_PASSIVE
            registry.gate.refusal(operation, limits, opensConnection = true)?.let { why ->
                println("[replica] checkpoint ${mode.name}: $why")
                return ReplicaCheckpointOutcome.NotReset
            }
            marked()?.let { return it }
            job = registry.register(ReplicaHandleKind.CHECKPOINT, "checkpoint:${mode.name}")
            val connection = registry.connectionFor(job)
            // ⚠⚠ The checkpoint's OWN deadline, CKPT_MAX (§4.2.1's watchdog table, Appendix
            // B.10 (136), (138), (143)), on the same watchdog, and ⚠⚠ ONLY IN PROTECTED BACKGROUND
            // TIME, judged WHEN IT PASSES: always armed, deferred while still in the foreground,
            // so a copy begun in the foreground and backgrounded mid-copy is still bounded (no
            // quiesce can stop a running checkpoint; the token-expiry interrupt, B.15, stops it
            // when protection ends, and this bounds it while protection lasts).
            // An interrupted copy keeps NOTHING (3.53.4's walCheckpoint stores nBackfill only
            // after the whole loop and its sync), so a log whose copy takes longer than CKPT_MAX
            // would be stopped at every attempt, for good, and every write at the ceiling refused.
            // In the foreground there is no deadline (B.7): the copy runs to its end. In the
            // background it must not outlive the time admitted for it (0xdead10cc), so there it is
            // bounded, and a stopped one is counted (checkpointCeilingStopsInARow). Armed once the
            // connection is open (an open's index rebuild is exempt), over every slice and the
            // rewind write.
            val ckpt = registry.holdWatchdog.arm(
                "checkpoint:${mode.name}", connection, limits.checkpointMaxMs, bound = "CKPT_MAX", holding = "a checkpoint",
                deferWhile = { registry.gate.inForeground() },
            )
            watch = ckpt
            var result = ckpt.bracket {
                fault?.invoke(mode)
                connection.checkpoint(mode)
            }
            if (mode == SqliteCheckpointMode.RESTART) {
                val started = TimeSource.Monotonic.markNow()
                while (result.busy && started.elapsedNow().inWholeMilliseconds < limits.ceilingWaitMs) {
                    if (!registry.gate.isOpen) return ReplicaCheckpointOutcome.NotReset
                    val slice = TimeSource.Monotonic.markNow()
                    result = ckpt.bracket { connection.checkpoint(mode) }
                    if (result.busy && slice.elapsedNow().inWholeMilliseconds < QUICK_SLICE_MS) usleep(SLICE_PAUSE_MICROS)
                }
            }
            // Every frame copied (none at all included: a log that is empty but whose file is
            // still large, after a rolled-back spill, is truncated by the same first commit).
            if (!result.busy && result.logFrames >= 0 && result.logFrames == result.checkpointedFrames) {
                beforeRewind?.invoke()
                ckpt.bracket { connection.exec("BEGIN IMMEDIATE") }
                // ⚠⚠ Looked for with the write lock HELD, as every access does (§3.1 step 0). The
                // marker is a file, and SQLite's lock orders nothing about it: looked for before
                // the lock, a sibling could fence the store while this waited for the lock, and
                // the rewind would then be committed to a store that is fenced for good.
                marked()?.let { found ->
                    // ⚠ The answer is the marker whatever becomes of this ROLLBACK: a failure
                    // here must not fall through to the size check below, which could answer
                    // "reset" for a store that is fenced. The connection is closed on the way
                    // out, which rolls back again (or latches the process, the registry's rule).
                    // ⚠ Disarmed before the ROLLBACK: the watchdog never interrupts one (B.9 (128)).
                    ckpt?.disarm()
                    try {
                        rollbackFault?.invoke()
                        connection.exec("ROLLBACK")
                    } catch (e: SqliteException) {
                        println("[replica] the ROLLBACK of a refused rewind failed: ${e.message}")
                    }
                    return found
                }
                ckpt.bracket { connection.exec(SharedReplicaStore.REWIND_SQL) }
                // Refused past CKPT_MAX; otherwise disarmed as the COMMIT is issued (its sync is R2).
                ckpt?.beforeCommit()
                connection.exec("COMMIT")
                // ⚠⚠ Only here, with the WHOLE path behind it: every frame copied and the rewind
                // write committed, without a code. Whatever corruption codes came before are not
                // "in a row" with the next. Cleared any earlier (after the copy, before the
                // rewind), a store whose rewind write answers a corruption code every time
                // would count 1, be cleared, count 1 again, and never be marked. And one that
                // was kept out, in whole or in part (busy; a PASSIVE one a reader stopped short),
                // or that found the marker, proved nothing about what it did not do.
                registry.checkpointRan()
            }
        } catch (e: SqliteException) {
            // "Skipped": a sibling that got in first restarts the log by its own commit, so the
            // size below is still the answer.
            if (ReplicaVerdicts.of(e.primaryCode, ReplicaPhase.CHECKPOINT, protectedDataAvailable = protectedDataAvailable()) ==
                ReplicaVerdict.CORRUPT
            ) {
                // ⚠⚠ Every checkpoint is on a connection of its own: `job` is registered, opened
                // and released inside this call, and a release that fails latches the process, so
                // no later checkpoint opens at all. Two codes in a row are therefore two
                // connections' answers, never one connection asked twice.
                return ReplicaCheckpointOutcome.CorruptionCode(e.message.orEmpty(), registry.checkpointAnsweredCorruption())
            }
        } catch (e: HoldCeilingExceeded) {
            // B.15: the token-expiry interrupt is not a ceiling stop: not counted, and said.
            if (isProtectionExpiry(e)) {
                expiryStops++
                println("[replica] checkpoint ${mode.name} stopped by the token-expiry interrupt, keeping none of its copy: ${e.message}")
                return ReplicaCheckpointOutcome.NotReset
            }
            // Interrupted or refused at CKPT_MAX: no corruption code, nothing counted, and no
            // reset. The connection is released below, rolling back a rewind still open.
            val inARow = registry.checkpointStoppedAtCeiling()
            println("[replica] checkpoint ${mode.name} stopped at CKPT_MAX in protected background time, keeping none of its copy ($inARow in a row): ${e.message}")
            return ReplicaCheckpointOutcome.NotReset
        } catch (_: StoreUnavailable) {
            return ReplicaCheckpointOutcome.NotReset
        } catch (e: ReplicaFileException) {
            // The marker could not be looked for: no checkpoint or no rewind write, no reset.
            println("[replica] checkpoint skipped: ${e.message}")
            return ReplicaCheckpointOutcome.NotReset
        } finally {
            // Before the release's close, which rolls back a rewind still open.
            watch?.disarm()
            if (job != null) {
                try {
                    registry.release(job)
                } catch (_: StoreUnavailable) {
                    // Latched and logged by the registry.
                }
            }
        }
        // The file never shrinks except when the log restarts, which truncates it to
        // `journal_size_limit`: at or below `Wmax − δ` is the proof, whoever restarted it.
        // ⚠ A size that cannot be read verifies nothing: the reset is reported as not done.
        val reset = try {
            walProbe.sizeBytes() <= limits.beginCeilingBytes
        } catch (e: ReplicaFileException) {
            println("[replica] the reset could not be verified: ${e.message}")
            false
        }
        return if (reset) ReplicaCheckpointOutcome.Reset else ReplicaCheckpointOutcome.NotReset
    }

    private fun marked(): ReplicaCheckpointOutcome.Marked? = when (val state = marker.state()) {
        ReplicaMarkerState.ABSENT -> null
        else -> ReplicaCheckpointOutcome.Marked(state)
    }

    private companion object {
        const val QUICK_SLICE_MS = 5
        const val SLICE_PAUSE_MICROS = 1_000u
    }
}

/** [block] under [this] watch as one statement, or plainly when there is no watch (the foreground). */
private inline fun <T> ReplicaHold?.bracket(block: () -> T): T = if (this == null) block() else statement(block)
