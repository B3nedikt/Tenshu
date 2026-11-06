package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android worker's retry decision, without Android (media pipeline P4).
 *
 * Each test replays a chain the way WorkManager runs it: `runAttemptCount` is the number of
 * attempts before this one and goes up by one per attempt whatever its outcome, and every attempt
 * gets a **new** [ScheduledRetryAccounting] over the same store — a fresh worker instance, possibly
 * in a fresh process.
 *
 * ⚠ What this cannot show is WorkManager itself: that `runAttemptCount` behaves as modelled here,
 * and the waits between attempts. Those are device facts.
 */
class ScheduledRetryAccountingTest {

    private class MapStore : DeferralCountStore {
        val counts = mutableMapOf<String, Int>()
        override fun deferrals(workId: String): Int = counts[workId] ?: 0
        override fun setDeferrals(workId: String, count: Int) { counts[workId] = count }
        override fun clear(workId: String) { counts.remove(workId) }
    }

    private enum class Run { DEFER, FAIL, SUCCEED }
    private enum class Answer { RETRY, FAILURE, SUCCESS }

    /** One attempt, as `TenshuOperationWorker.doWork` sequences it. */
    private fun MapStore.attempt(runAttemptCount: Int, run: Run, workId: String = "work"): Answer {
        val retries = ScheduledRetryAccounting(this)
        retries.attemptStarted(workId, runAttemptCount)
        return when (run) {
            Run.DEFER -> { retries.deferred(workId, runAttemptCount); Answer.RETRY }
            Run.FAIL -> if (retries.failedMayRetry(workId, runAttemptCount)) Answer.RETRY else Answer.FAILURE
            Run.SUCCEED -> { retries.succeeded(workId); Answer.SUCCESS }
        }
    }

    private fun MapStore.chain(vararg runs: Run): List<Answer> =
        runs.mapIndexed { attempt, run -> attempt(runAttemptCount = attempt, run = run) }

    /** ⚠⚠ The defect: three deferrals used up `runAttemptCount < 3`, so the first real error failed outright. */
    @Test
    fun aFailureAfterThreeDeferralsIsStillRetried() {
        val answers = MapStore().chain(Run.DEFER, Run.DEFER, Run.DEFER, Run.FAIL)
        assertEquals(Answer.RETRY, answers.last(), "deferrals do not spend the failure budget")
    }

    @Test
    fun theFailureBudgetIsExactlyThreeRetriesHoweverManyDeferralsSurroundThem() {
        val answers = MapStore().chain(
            Run.DEFER, Run.FAIL, Run.DEFER, Run.DEFER, Run.FAIL, Run.DEFER, Run.FAIL, Run.DEFER, Run.FAIL,
        )
        assertEquals(
            listOf(Answer.RETRY, Answer.RETRY, Answer.RETRY, Answer.FAILURE),
            answers.filterIndexed { i, _ -> i in setOf(1, 4, 6, 8) },
            "the first three failures retry, the fourth fails the chain",
        )
    }

    /** The pre-P4 behaviour, unchanged for a chain that never defers. */
    @Test
    fun withoutDeferralsTheFourthFailureFails() {
        assertEquals(
            listOf(Answer.RETRY, Answer.RETRY, Answer.RETRY, Answer.FAILURE),
            MapStore().chain(Run.FAIL, Run.FAIL, Run.FAIL, Run.FAIL),
        )
    }

    @Test
    fun aDeferralIsAlwaysRetried() {
        val store = MapStore()
        repeat(40) { attempt ->
            assertEquals(Answer.RETRY, store.attempt(attempt, Run.DEFER), "deferral #${attempt + 1}")
        }
    }

    /**
     * Periodic work goes back to `runAttemptCount == 0` after a run completes. A count left over
     * from the previous period must not be credited to the next one.
     */
    @Test
    fun aNewChainStartsWithNoDeferralsHoweverTheLastOneEnded() {
        val store = MapStore()
        store.chain(Run.DEFER, Run.DEFER, Run.DEFER)
        // The store still says 3 — the chain was cancelled, or its count was never cleared.
        assertEquals(3, store.deferrals("work"))

        assertEquals(
            listOf(Answer.RETRY, Answer.RETRY, Answer.RETRY, Answer.FAILURE),
            store.chain(Run.FAIL, Run.FAIL, Run.FAIL, Run.FAIL),
            "a new chain gets three failure retries, not three plus the stale deferrals",
        )
    }

    /** A stored count can never excuse more attempts than there have been. */
    @Test
    fun aStoredCountLargerThanTheAttemptCountIsClamped() {
        val store = MapStore().apply { setDeferrals("work", 99) }
        val retries = ScheduledRetryAccounting(store)
        assertTrue(retries.failedMayRetry("work", runAttemptCount = 3), "3 attempts, all credited as deferrals")

        store.setDeferrals("work", 99)
        retries.deferred("work", runAttemptCount = 2)
        assertEquals(3, store.deferrals("work"), "recorded as one more than the attempts allow, not 100")
    }

    /** A lost count — the preferences file gone — falls back to the pre-P4 rule, never past it. */
    @Test
    fun aLostCountCostsOnlyThePrePFourBehaviour() {
        val store = MapStore()
        store.chain(Run.DEFER, Run.DEFER, Run.DEFER)
        store.counts.clear()
        assertFalse(
            ScheduledRetryAccounting(store).failedMayRetry("work", runAttemptCount = 3),
            "with no record of the deferrals, three prior attempts read as three failures",
        )
    }

    @Test
    fun successAndFinalFailureBothClearTheCount() {
        val store = MapStore()
        store.chain(Run.DEFER, Run.SUCCEED)
        assertEquals(emptyMap(), store.counts, "cleared on success")

        store.chain(Run.DEFER, Run.FAIL, Run.FAIL, Run.FAIL, Run.FAIL)
        assertEquals(emptyMap(), store.counts, "cleared when the chain fails for good")
    }

    @Test
    fun countsAreKeptPerWork() {
        val store = MapStore()
        store.attempt(0, Run.DEFER, workId = "a")
        store.attempt(1, Run.DEFER, workId = "a")
        assertEquals(0, store.deferrals("b"))
        assertEquals(Answer.RETRY, store.attempt(2, Run.FAIL, workId = "b"))
        assertEquals(Answer.FAILURE, store.attempt(3, Run.FAIL, workId = "b"), "b had no deferrals of its own")
    }
}
