package org.tenshu.core.common.proxy.db.sqlite

/**
 * The operations of §4.2.1's operations table that this store runs under a token, each with the
 * protected time it must find left before it starts. An operation not listed does not run
 * outside the foreground.
 *
 * Not here, and why:
 * - `VACUUM INTO`, the backup copy, the byte copy, the `COMPLETE` marker: foreground only by the
 *   table. Control's snapshot (§6; [ReplicaDamagedSnapshot], Appendix B.18) stops at the first
 *   check after the foreground ends instead of asking for time.
 * - The `quick_check` promotion (§6): not built.
 * - `incremental_vacuum`: not built.
 *
 * The confirmation of a corruption code does not run inside the operation that met it (Q5): it
 * is [CONFIRM_WALK], a worker run of its own, with its own admission.
 */
internal enum class ReplicaOperation {
    /** An open (classification and its read/write half, reserved together), and so a possible WAL-index rebuild. */
    OPEN,

    /** An ordinary write transaction, every outermost access included (S2). */
    WRITE,

    /** A chunk of a migration or of a wipe, on its `REPLACEMENT` handle. */
    JOB_CHUNK,

    /** The opportunistic checkpoint and its rewind write. */
    CHECKPOINT_PASSIVE,

    /** The ceiling checkpoint, its slices of waiting and its rewind write. */
    CHECKPOINT_RESTART,

    /**
     * One run of the confirmation walk (Appendix B.8): its pause, up to `CONFIRM_MAX` of reading
     * on a connection it opens, and then either the marker or its own position file, each a
     * file sync and a directory sync (the `2 · S_full` every row reserves).
     */
    CONFIRM_WALK,
}

/**
 * §4.2.1's admission arithmetic, stated once. Every operation reserves, on top of its own bound:
 * the 2 s expiry margin, and `2 · S_full` for `replica/CORRUPT`'s file and directory syncs, since
 * each can produce a corruption verdict ("marker time is reserved up front"). An operation that
 * opens a connection also reserves `R(Wmax + δ)`, the index rebuild any open may run.
 */
internal object ReplicaAdmissionBudget {
    fun requiredMs(operation: ReplicaOperation, limits: ReplicaLimits, opensConnection: Boolean): Long {
        val own = when (operation) {
            // An open reserves its read/write half up front, so one that could
            // not finish does no step-0 file work and no classification first.
            ReplicaOperation.OPEN -> limits.holdMaxMs.toLong() + limits.commitSyncMs
            ReplicaOperation.WRITE, ReplicaOperation.JOB_CHUNK -> limits.holdMaxMs.toLong() + limits.commitSyncMs
            ReplicaOperation.CHECKPOINT_PASSIVE -> limits.checkpointMs.toLong() + limits.commitSyncMs
            ReplicaOperation.CHECKPOINT_RESTART -> limits.checkpointMs.toLong() + limits.ceilingWaitMs + limits.commitSyncMs
            ReplicaOperation.CONFIRM_WALK -> limits.confirmMaxMs.toLong() + CONFIRM_WALK_PAUSE_MS
        }
        val open = if (opensConnection || operation == ReplicaOperation.OPEN) limits.openRebuildMs.toLong() else 0L
        return own + open + 2L * limits.fullSyncMs + limits.expiryMarginMs
    }

    /** The walk's pause before it reads (the open's own, B.4 path 2), in ms. */
    const val CONFIRM_WALK_PAUSE_MS = 20L
}

/**
 * An operation refused because the protected time left would not cover it (§4.2.1's operations
 * table): deferred, never begun, retryable. Raised before the operation's first SQLite call.
 */
class ReplicaAdmissionDeferred(message: String) : StoreUnavailable(message)
