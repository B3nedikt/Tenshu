package org.tenshu.sync.proxy

/**
 * How many times one chain of OS-scheduled work is retried after it **failed** — the budget
 * `TenshuOperationWorker` has always had (`runAttemptCount < 3`).
 */
const val MAX_FAILURE_RETRIES: Int = 3

/**
 * Where a chain's deferral count survives between attempts. An attempt is a fresh worker instance,
 * often in a fresh process, so the count cannot live in memory.
 */
interface DeferralCountStore {
    fun deferrals(workId: String): Int
    fun setDeferrals(workId: String, count: Int)
    fun clear(workId: String)
}

/**
 * Keeps a [org.tenshu.sync.ScheduledRunDeferred] from spending the failure budget (media pipeline
 * P4). The decision half of `TenshuOperationWorker`, kept here so it is testable without Android.
 *
 * ⚠⚠ **The OS scheduler has one attempt counter and it counts everything.** WorkManager's
 * `runAttemptCount` goes up on every start of a chain — a failure, a deferral, a run the system
 * interrupted — and it is the only state WorkManager carries from one attempt to the next. Read
 * alone, three deferrals look like three failures, and the first real error after them is refused
 * its retries. So the deferrals are counted separately, in a [DeferralCountStore], and subtracted.
 *
 * ⚠ What is **not** separated: a run the system stopped (constraints lost, process killed) still
 * counts against the failure budget, as it did before P4. And WorkManager's *backoff* still grows
 * with the one counter — see `WorkBackoff`.
 *
 * The stored count is only ever trusted as far as the counter allows: it is clamped to
 * `runAttemptCount`, and dropped at the first attempt of a chain (`runAttemptCount == 0`, which is
 * also every new period of periodic work). A stale or lost count therefore costs at most the
 * pre-P4 behaviour, never an unbounded retry.
 */
class ScheduledRetryAccounting(private val store: DeferralCountStore) {

    /** Call first on every attempt. [runAttemptCount] is the number of attempts before this one. */
    fun attemptStarted(workId: String, runAttemptCount: Int) {
        if (runAttemptCount == 0) store.clear(workId)
    }

    /** The attempt asked to be retried without having failed. Always retried. */
    fun deferred(workId: String, runAttemptCount: Int) {
        store.setDeferrals(workId, deferralsBefore(workId, runAttemptCount) + 1)
    }

    /** The attempt failed. `true` ⇒ retry; `false` ⇒ the budget is spent and the chain fails. */
    fun failedMayRetry(workId: String, runAttemptCount: Int): Boolean {
        val failuresBefore = runAttemptCount - deferralsBefore(workId, runAttemptCount)
        val retry = failuresBefore < MAX_FAILURE_RETRIES
        if (!retry) store.clear(workId)
        return retry
    }

    fun succeeded(workId: String) = store.clear(workId)

    private fun deferralsBefore(workId: String, runAttemptCount: Int): Int =
        store.deferrals(workId).coerceIn(0, runAttemptCount.coerceAtLeast(0))
}
