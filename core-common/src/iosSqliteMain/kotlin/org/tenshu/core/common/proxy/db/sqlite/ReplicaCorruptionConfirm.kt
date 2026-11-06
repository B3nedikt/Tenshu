@file:OptIn(ExperimentalForeignApi::class)

package org.tenshu.core.common.proxy.db.sqlite

import kotlinx.cinterop.ExperimentalForeignApi
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_BUSY
import org.tenshu.core.common.proxy.db.sqlite.c.SQLITE_LOCKED
import platform.posix.usleep

/** What became of a corruption code SQLite answered once ([ReplicaCorruptionConfirm]). */
internal sealed interface ReplicaCorruptionConfirmation {
    /**
     * `replica/CORRUPT` exists and this process has made it durable: a second reading agreed and
     * the marker was written, or a sibling's marker was found. The store is `CORRUPT` for good.
     */
    data class Confirmed(val detail: String) : ReplicaCorruptionConfirmation

    /**
     * No marker was written by this call. **Retryable.** [handedOff]: the code was handed to this
     * app's confirmation walk ([ReplicaConfirmationWalk], Q5), which reads the store in runs of
     * its own and is owed until it ends; this app's opens are refused meanwhile
     * ([StoreBeingChecked]). Otherwise (a checkpoint's repeated code that could not be marked, a
     * failing connection that could not be closed) nothing records the code at all.
     */
    data class Unconfirmed(val reason: String, val cause: Throwable? = null, val handedOff: Boolean = false) : ReplicaCorruptionConfirmation
}

/**
 * ⚠⚠ **The one place a corruption code from SQLite is acted on** (Appendix B.4 paths 5, 6, 7 and
 * 9): the read/write open, an access, a checkpoint and a job's transaction all come here, and
 * nothing else writes `replica/CORRUPT` on a result code. The marker is permanent, so one answer
 * is not believed (decided 2026-10-07, B.3 (45)):
 *
 * 1. **What the failing handle holds is given up first**: its transaction rolled back and its
 *    connection closed, here, through the registry. The caller has fenced it in memory, so
 *    nothing more is written through it.
 * 2. ⚠⚠ **No second reading here** (Q5, decided by the maintainer 2026-10-15; Appendix B.8): the
 *    code is **handed off** to this app's [ReplicaConfirmationWalk], which the worker runs in
 *    runs of at most `CONFIRM_MAX`, each under a job token of its own, carrying its position
 *    across runs, wakes and process deaths. So the operation that met the code no longer reads
 *    for up to a second on a thread holding the registry's lock, and a store too large to check
 *    in one second is still checked in the end (the B.3 (47) ship blocker).
 * 3. **A sibling's marker, found here, is the verdict at once**, as before; and from the second
 *    checkpoint code in a row the code is itself the second reading ((52), [runForCheckpoint]).
 *    ⚠ A checkpoint's code is never handed to the walk (B.8.3 (113)): (52) is its whole handling.
 *
 * The hand-off is in memory only, so it is made also with the gate closed; the marker lookup and
 * any write are inside one gate admission, the caller's or one taken here, and not once the gate
 * has begun to close.
 */
internal class ReplicaCorruptionConfirm(
    private val registry: ReplicaHandles,
    private val files: ReplicaFiles,
    private val marker: ReplicaCorruptMarker,
    private val appName: String,
    /** This process's build: what the walk a code is handed to judges the store against (Q16, Appendix B.10). */
    private val build: ReplicaBuild,
    private val protectedDataAvailable: () -> Boolean,
) {
    /**
     * [error] is what SQLite said the first time. [failing] is the handle whose connection said
     * it, or null when that connection is already closed (a failed acquisition, a checkpoint's
     * own handle). [alreadyAdmitted]: the caller is inside a gate admission now.
     *
     * The caller holds the registry's lock. Never throws.
     */
    fun run(error: String, failing: ReplicaHandle?, alreadyAdmitted: Boolean): ReplicaCorruptionConfirmation {
        registry.lock.lock()
        try {
            return confirm(error, failing, alreadyAdmitted).also { report(error, it) }
        } finally {
            registry.lock.unlock()
        }
    }

    /**
     * A corruption code from a **checkpoint** ([ReplicaCheckpointOutcome.CorruptionCode]).
     *
     * The first in a row is confirmed like any other code ([run]). ⚠⚠ **From the second in a row
     * the code is itself the second reading** (decided 2026-10-10, B.3 (52)): the same operation,
     * asked again on another connection, with no checkpoint of this process in between that ran
     * without one. `quick_check` reads **through** the log and its index, which is what a
     * checkpoint's code is about, so it cannot confirm such a code and is not asked again. The
     * marker is then written under the rules of every confirmed path: inside one gate admission,
     * not once the gate has closed, not while protected data is unavailable.
     */
    fun runForCheckpoint(error: String, inARow: Int, failing: ReplicaHandle?, alreadyAdmitted: Boolean): ReplicaCorruptionConfirmation {
        // ⚠⚠ (113): a checkpoint's first code is NOT handed to the walk. The walk reads through
        // the log and its index, which is what the code is about, so it could never confirm it;
        // it would only fence this app's opens for many runs and hold the registry's lock for a
        // second a run under a live runtime. (52) is the whole handling: the next code in a row
        // on another connection confirms.
        if (inARow < CHECKPOINT_CODES_THAT_CONFIRM) {
            registry.lock.lock()
            try {
                return firstCheckpointCode(error, failing, alreadyAdmitted).also { report(error, it) }
            } finally {
                registry.lock.unlock()
            }
        }
        registry.lock.lock()
        try {
            return repeated(error, inARow, failing, alreadyAdmitted).also { report(error, it) }
        } finally {
            registry.lock.unlock()
        }
    }

    /** The first checkpoint code in a row: the failing connection given up, a sibling's marker looked for, nothing handed off. */
    private fun firstCheckpointCode(error: String, failing: ReplicaHandle?, alreadyAdmitted: Boolean): ReplicaCorruptionConfirmation {
        if (failing != null) {
            try {
                registry.closeConnection(failing)
            } catch (e: StoreUnavailable) {
                return ReplicaCorruptionConfirmation.Unconfirmed("the failing connection could not be closed", e)
            }
        }
        var entered = false
        if (!alreadyAdmitted) {
            if (!registry.gate.enter()) return ReplicaCorruptionConfirmation.Unconfirmed("the gate is closed")
            entered = true
        }
        try {
            if (!registry.gate.isOpen) return ReplicaCorruptionConfirmation.Unconfirmed("the gate is closing")
            marked()?.let { return it }
            return ReplicaCorruptionConfirmation.Unconfirmed("the first checkpoint code in a row: the next one confirms (B.3 (52)), no walk")
        } catch (e: ReplicaFileException) {
            return ReplicaCorruptionConfirmation.Unconfirmed("replica/CORRUPT could not be looked for: ${e.message}", e)
        } finally {
            if (entered) registry.gate.leave()
        }
    }

    private fun repeated(error: String, inARow: Int, failing: ReplicaHandle?, alreadyAdmitted: Boolean): ReplicaCorruptionConfirmation {
        if (failing != null) {
            try {
                registry.closeConnection(failing)
            } catch (e: StoreUnavailable) {
                return ReplicaCorruptionConfirmation.Unconfirmed("the failing connection could not be closed", e)
            }
        }
        var entered = false
        if (!alreadyAdmitted) {
            if (!registry.gate.enter()) return ReplicaCorruptionConfirmation.Unconfirmed("the gate is closed")
            entered = true
        }
        try {
            if (!registry.gate.isOpen) return ReplicaCorruptionConfirmation.Unconfirmed("the gate is closing")
            marked()?.let { return it }
            if (!protectedDataAvailable()) return ReplicaCorruptionConfirmation.Unconfirmed("protected data is unavailable")
            marker.write(
                corruptMarkerDetail(files, appName, "$error; repeated by the next checkpoint on another connection ($inARow in a row)"),
            )
            return ReplicaCorruptionConfirmation.Confirmed(error)
        } catch (e: ReplicaFileException) {
            return ReplicaCorruptionConfirmation.Unconfirmed("replica/CORRUPT could not be looked for or written: ${e.message}", e)
        } finally {
            if (entered) registry.gate.leave()
        }
    }

    private fun report(error: String, outcome: ReplicaCorruptionConfirmation) {
        when (outcome) {
            is ReplicaCorruptionConfirmation.Confirmed -> println("[replica] the store is CORRUPT: ${outcome.detail}")
            is ReplicaCorruptionConfirmation.Unconfirmed ->
                println("[replica] SQLite answered a corruption code ($error) and it is NOT confirmed: ${outcome.reason}. No marker yet")
        }
    }

    private fun confirm(error: String, failing: ReplicaHandle?, alreadyAdmitted: Boolean): ReplicaCorruptionConfirmation {
        if (failing != null) {
            try {
                registry.closeConnection(failing)
            } catch (e: StoreUnavailable) {
                // Latched by the registry: the connection may still hold its transaction, and
                // nothing is opened in this process until that close succeeds.
                return ReplicaCorruptionConfirmation.Unconfirmed("the failing connection could not be closed", e)
            }
        }
        val walk = registry.confirmationWalk(appName, files, build, protectedDataAvailable)
        var entered = false
        if (!alreadyAdmitted) {
            if (!registry.gate.enter()) return handOff(walk, error, "the gate is closed")
            entered = true
        }
        try {
            // Also for a caller that is admitted: an admission taken before the gate began to
            // close does not make this the moment for a lookup that may sync a directory.
            if (!registry.gate.isOpen) return handOff(walk, error, "the gate is closing")
            // A sibling may have reached its own verdict already; the marker is the answer then.
            marked()?.let { return it }
            return handOff(walk, error, null)
        } catch (e: ReplicaFileException) {
            return handOff(walk, error, "replica/CORRUPT could not be looked for: ${e.message}")
        } finally {
            if (entered) registry.gate.leave()
        }
    }

    /** The code goes to the walk: in memory, no I/O, never refused. */
    private fun handOff(walk: ReplicaConfirmationWalk, error: String, why: String?): ReplicaCorruptionConfirmation {
        registry.onHandOff?.invoke()
        walk.request(error)
        val reason = "handed to the confirmation walk" + (why?.let { " ($it)" } ?: "")
        return ReplicaCorruptionConfirmation.Unconfirmed(reason, handedOff = true)
    }

    /** `replica/CORRUPT`, if it is there: the verdict when this process made it durable, a retry when it could not. */
    private fun marked(): ReplicaCorruptionConfirmation? = when (marker.state()) {
        ReplicaMarkerState.ABSENT -> null
        ReplicaMarkerState.DURABLE -> ReplicaCorruptionConfirmation.Confirmed("replica/CORRUPT exists")
        ReplicaMarkerState.NOT_DURABLE ->
            ReplicaCorruptionConfirmation.Unconfirmed("replica/CORRUPT is there and could not be made durable")
    }

    private companion object {
        /** A checkpoint's corruption code confirms itself from this many in a row. */
        const val CHECKPOINT_CODES_THAT_CONFIRM = 2
    }
}

/**
 * One read-only reading of the store, the mechanism of §5.2 steps 1 and 2: a `CLASSIFY` handle
 * through the registry (a `SQLITE_OPEN_READONLY` connection, which never checkpoints and never
 * writes `objects.db` or an existing `-wal`), inside a gate admission, one deferred read
 * transaction around [read], and the handle released before the admission is given back. Used by
 * the open's classification and by [ReplicaCorruptionConfirm]: there is one way to read a store
 * this build has not judged, not two.
 *
 * [alreadyAdmitted]: the caller holds an admission that covers the whole call. Otherwise one is
 * taken here, and with the gate closed this throws [ReplicaGateClosed] with nothing opened.
 * Throws whatever the open or the read threw.
 */
internal fun <T> ReplicaHandles.readOnly(
    label: String,
    alreadyAdmitted: Boolean = false,
    /**
     * ⚠ The read under the hold watchdog at `HOLD_MAX` (§4.2.1's table: "read-only statements that
     * hold a read mark", Q15, Appendix B.10): armed once the read transaction has begun, and an
     * interrupt or a refusal is [HoldCeilingExceeded]. The open's classification asks for it; the
     * confirmation walk does not, being bounded by `CONFIRM_MAX` (at most `HOLD_MAX`) through its
     * own progress handler.
     */
    watched: Boolean = false,
    read: (SqliteConnection) -> T,
): T {
    lock.lock()
    try {
        val handle = register(ReplicaHandleKind.CLASSIFY, label)
        var entered = false
        try {
            if (!alreadyAdmitted) {
                if (!gate.enter()) throw ReplicaGateClosed("[replica] $label: the gate is closed")
                entered = true
            }
            return readInSlices(connectionFor(handle), if (watched) { connection -> holdWatchdog.arm(label, connection, limits.holdMaxMs, holding = "a read mark") } else null, read)
        } finally {
            // No connection of a reading outlives it, and it is closed before the gate is left.
            try {
                release(handle)
            } finally {
                if (entered) gate.leave()
            }
        }
    } finally {
        lock.unlock()
    }
}

/**
 * `BEGIN` (deferred), the reads, `ROLLBACK`; a busy start is retried for a few slices. [arm], when
 * given, arms a watch once `BEGIN` has returned, around the reads as one statement, and it is
 * disarmed (and the statement marked ended) before the `ROLLBACK` is issued (B.9 (128)).
 */
private fun <T> readInSlices(connection: SqliteConnection, arm: ((SqliteConnection) -> ReplicaHold)?, read: (SqliteConnection) -> T): T {
    var attempt = 0
    while (true) {
        try {
            connection.exec("BEGIN")
            var failed = false
            val watch = arm?.invoke(connection)
            try {
                return if (watch == null) read(connection) else watch.statement { read(connection) }
            } catch (t: Throwable) {
                failed = true
                throw t
            } finally {
                watch?.disarm()
                try {
                    connection.exec("ROLLBACK")
                } catch (e: SqliteException) {
                    // What the read itself failed with is the answer; the close rolls back again.
                    if (!failed) throw e
                }
            }
        } catch (e: SqliteException) {
            val busy = e.primaryCode == SQLITE_BUSY || e.primaryCode == SQLITE_LOCKED
            if (!busy || ++attempt >= CLASSIFY_BUSY_SLICES) throw e
            if (!connection.inAutocommit()) connection.exec("ROLLBACK")
            usleep(1_000u)
        }
    }
}

private const val CLASSIFY_BUSY_SLICES = 20
