package org.tenshu.sync

/**
 * A scheduled op's way of saying **"not now — try me again in [retryAfterMs]"** (media pipeline P4,
 * `media-processing-pipeline-sweep.md` F3.1).
 *
 * The scheduler contract used to have two outcomes, *returned* and *threw*, and both reset a periodic
 * item's whole period. A maintenance op that declines because the device is busy — the media index
 * pass seeing the screen on at Control's startup — therefore waited 12 h of process uptime for its
 * next look. Throwing this is the third outcome:
 *
 *  - **in-process** (`ScheduleDrainLoop`): the item stays `PENDING`, `attempts` is unchanged, and its
 *    next fire is [retryAfterMs] from now instead of a full period. A run that completes afterwards
 *    puts the period back.
 *  - **Android** (`TenshuOperationWorker`): `Result.retry()`, so WorkManager re-runs the same request
 *    under the same constraints, with the backoff the item's `RetryPolicy` asks for. ⚠⚠ That is
 *    **not** [retryAfterMs] and not a fixed interval: WorkManager's backoff grows with the attempt
 *    count (linear: first wait × attempts, capped at 5 h). A deferral does not spend the worker's
 *    failure-retry budget.
 *
 * ⚠⚠ **Kotlin-only, never wire.** It is not a `TenshuException` and has no `TError` constant, so
 * nothing in a schema (and nothing `SchemaShapeBaseline` pins) changed to add it — no House Rule 9
 * bump. It is meaningful only to the two in-process dispatchers above. A `HeadlessSafe` op is
 * dispatched only by them; if one were ever reached over IPC, `LocalTaskExecutor`'s catch-all would
 * report it as an internal error, which is the honest answer to a caller that is not a scheduler.
 *
 * ⚠ **Not for "cannot ever run".** A refusal a retry cannot fix — no backend, no consent, the user
 * paused indexing — returns normally, so the period stands. Throwing this for those would wake the
 * device every [retryAfterMs] for nothing.
 *
 * ⚠ The constructor takes no `(String)`/`(String, Throwable)` shape on purpose: kotlinx-coroutines'
 * JVM stack-trace recovery copies an exception crossing an `await()` through such a constructor, and
 * the copy would lose [retryAfterMs]. With none to call, the original instance arrives.
 */
class ScheduledRunDeferred(
    val retryAfterMs: Long,
    reason: String,
) : Exception(reason) {

    init {
        require(retryAfterMs > 0) { "retryAfterMs must be positive, was $retryAfterMs" }
    }

    companion object {
        /**
         * The default "soon": WorkManager's own floor for a periodic interval, and short enough that
         * a pass declined at startup gets its next look while the user is still plugged in.
         */
        const val DEFAULT_RETRY_AFTER_MS: Long = 15L * 60 * 1000
    }
}
