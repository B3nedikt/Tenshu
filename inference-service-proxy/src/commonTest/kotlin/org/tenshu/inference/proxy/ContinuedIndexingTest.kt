package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The continued-processing bridge (`ios-on-device-inference.md` §15) on a **fake runner**: a pass
 * is an outcome to complete and a bar to move. What the task is told, when, and that it is always
 * told once.
 */
class ContinuedIndexingTest {

    private val events = mutableListOf<String>()
    private val outcomes = mutableMapOf<Long, CompletableDeferred<PassOutcome>>()
    private val bar = MutableStateFlow<IndexProgressSnapshot?>(null)
    private val submitted = mutableListOf<String>()
    private var suffix = 0

    /** What the platform answers at submission: `null` answers later, by the case. */
    private var answerAtSubmit: Boolean? = null

    private class Task(val events: MutableList<String>) : ContinuedTask {
        val progress = mutableListOf<Pair<Long, Long>>()
        val completions = mutableListOf<Boolean>()

        override fun progress(done: Long, total: Long) {
            progress += done to total
        }

        override fun complete(success: Boolean) {
            completions += success
            events += "complete $success"
        }
    }

    private fun pass(number: Long): CompletableDeferred<PassOutcome> = CompletableDeferred<PassOutcome>().also { outcomes[number] = it }

    private fun TestScope.bridge(
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        scheduler: ContinuedTaskScheduler? = null,
    ): ContinuedIndexing {
        lateinit var bridge: ContinuedIndexing
        val platform: ContinuedTaskScheduler = scheduler ?: ContinuedTaskScheduler { identifier ->
            submitted += identifier
            events += "submit"
            answerAtSubmit?.let { bridge.answered(identifier, it) }
        }
        bridge = ContinuedIndexing(
            scope = scope,
            scheduler = { platform },
            outcomeOf = { outcomes[it] },
            progress = { bar },
            settled = { events += "settled" },
            released = { events += "released" },
            identifierPrefix = "app.test.indexnow.",
            newSuffix = { "s${++suffix}" },
        )
        return bridge
    }

    @Test
    fun theTasksProgressFollowsTheHolderAndAFinishedPassCompletesItWithSuccess() = runTest {
        val outcome = pass(1)
        val bridge = bridge()
        bridge.offer(1)
        assertEquals(listOf("app.test.indexnow.s1"), submitted)
        assertTrue(bridge.holdsPass(), "a submitted request does not hold the pass")

        val task = Task(events)
        bridge.launched("app.test.indexnow.s1", task)
        bar.value = IndexProgressSnapshot(done = 0, total = 0)
        bar.value = IndexProgressSnapshot(done = 0, total = 3)
        bar.value = IndexProgressSnapshot(done = 2, total = 3)
        runCurrent()
        assertEquals(listOf(0L to 0L, 0L to 3L, 2L to 3L), task.progress, "the task's progress did not follow the bar")
        assertTrue(task.completions.isEmpty(), "completed before the pass ended")

        bar.value = null
        outcome.complete(PassOutcome.COMPLETED)
        runCurrent()
        assertEquals(listOf(true), task.completions)
        assertFalse(bridge.holdsPass(), "a finished task still holds the pass")
        assertEquals(listOf("submit", "settled", "released", "complete true"), events, "released after the task was completed, or settled after the release")
    }

    /** ⚠⚠ The runner's outcome tells a throw from a finish; the holder's `null` cannot. */
    @Test
    fun aThrowingARefusedAndACancelledPassEachCompleteTheTaskWithFailure() = runTest {
        listOf(PassOutcome.FAILED, PassOutcome.REFUSED, PassOutcome.CANCELLED).forEachIndexed { i, ending ->
            val number = i + 1L
            val outcome = pass(number)
            val bridge = bridge()
            bridge.offer(number)
            val task = Task(events)
            bridge.launched(submitted.last(), task)
            bar.value = IndexProgressSnapshot(done = 1, total = 3)
            // The bar clears the same way for each of them: only the outcome says which.
            bar.value = null
            outcome.complete(ending)
            runCurrent()
            assertEquals(listOf(false), task.completions, "$ending completed the task with success")
        }
    }

    /**
     * ⚠⚠ An expiry releases the pass (the owner's stop, in the background) and completes the task
     * with `false` **at once**: a pass that goes on in front never leaves
     * an expired task waiting. Once only, whatever the pass does after.
     */
    @Test
    fun anExpiryReleasesThePassAndCompletesTheTaskWithFailureAtOnceAndOnce() = runTest {
        val outcome = pass(1)
        val bridge = bridge()
        bridge.offer(1)
        val task = Task(events)
        bridge.launched(submitted.single(), task)

        bridge.expired(submitted.single())
        runCurrent()
        assertFalse(bridge.holdsPass(), "an expired task still holds the pass")
        // ⚠ Settled first: a pass that already ended keeps its ending.
        assertEquals(listOf("submit", "settled", "released", "complete false"), events, "the expiry did not settle, release, or complete the task")

        outcome.complete(PassOutcome.COMPLETED)
        runCurrent()
        assertEquals(listOf(false), task.completions, "the task was completed again when its pass ended")
        assertEquals(1, events.count { it == "released" }, "released twice")
    }

    /**
     * ⚠⚠ An expiry that read the claim as *submitted* while the launch moved it to *live* is
     * retried, never lost — otherwise the pass goes on after its task expired.
     */
    @Test
    fun anExpiryThatRacesTheLaunchIsNotLost() = runTest {
        pass(1)
        val bridge = bridge()
        bridge.offer(1)
        val task = Task(events)
        bridge.betweenExpiryReadAndSwapForTests = {
            bridge.betweenExpiryReadAndSwapForTests = null
            bridge.launched(submitted.single(), task)
        }
        bridge.expired(submitted.single())
        runCurrent()
        assertFalse(bridge.holdsPass(), "the expiry was lost to the launch")
        assertEquals(listOf("submit", "released", "complete false"), events)
        assertEquals(listOf(false), task.completions)
    }

    /** What a leave held while the claim was only submitted ends when it resolves. */
    @Test
    fun aWindowHeldWhileSubmittedEndsAtTheLaunchOrAfterTheRelease() = runTest {
        pass(1)
        val launching = bridge()
        launching.offer(1)
        assertEquals(ContinuationStanding.SUBMITTED, launching.standing())
        assertTrue(launching.holdUntilResolved { events += "held end" })
        launching.launched(submitted.last(), Task(events))
        assertEquals(ContinuationStanding.LIVE, launching.standing())
        assertEquals(listOf("submit", "held end"), events, "the launch did not end the held window")
        assertFalse(launching.holdUntilResolved { events += "never" }, "a live claim held a window")

        events.clear()
        pass(2)
        val refused = bridge()
        refused.offer(2)
        assertTrue(refused.holdUntilResolved { events += "held end" })
        refused.answered(submitted.last(), accepted = false)
        assertEquals(listOf("submit", "released", "held end"), events, "the held window ended before the release's own stop began")

        events.clear()
        pass(3)
        val expiring = bridge()
        expiring.offer(3)
        assertTrue(expiring.holdUntilResolved { events += "held end" })
        expiring.expired(submitted.last())
        assertEquals(listOf("submit", "released", "held end"), events)
    }

    @Test
    fun aRefusedSubmissionLetsGoAtOnceAndItsLateLaunchIsCompletedWithFailure() = runTest {
        pass(1)
        answerAtSubmit = false
        val bridge = bridge()
        bridge.offer(1)
        assertFalse(bridge.holdsPass(), "a refused request holds the pass")
        assertEquals(listOf("submit", "released"), events)

        val late = Task(events)
        bridge.launched(submitted.single(), late)
        assertEquals(listOf(false), late.completions, "a launch nobody asked for was left uncompleted")
    }

    @Test
    fun anAcceptedRequestWhoseHandlerNeverRunsLetsGoAfterTheGrace() = runTest {
        pass(1)
        answerAtSubmit = true
        val bridge = bridge()
        bridge.offer(1)
        advanceTimeBy(ContinuedIndexing.LAUNCH_GRACE_MS - 1)
        runCurrent()
        assertTrue(bridge.holdsPass(), "let go before the grace")
        advanceTimeBy(2)
        runCurrent()
        assertFalse(bridge.holdsPass(), "a request that never launched holds the pass for good")
        assertEquals(listOf("submit", "released"), events)

        val late = Task(events)
        bridge.launched(submitted.single(), late)
        assertEquals(listOf(false), late.completions)
    }

    /** ⚠⚠ No task is ever left uncompleted, and none is completed twice. */
    @Test
    fun noTaskIsLeftUncompletedNorCompletedTwice() = runTest {
        // A launch of an identifier never submitted (another graph's, a stale one).
        val stranger = Task(events)
        bridge().launched("app.test.indexnow.nobody", stranger)
        assertEquals(listOf(false), stranger.completions)

        // A scope cancelled under a live task: the graph's teardown.
        pass(1)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val torn = bridge(scope = scope)
        torn.offer(1)
        val live = Task(events)
        torn.launched(submitted.last(), live)
        scope.cancel()
        runCurrent()
        assertEquals(listOf(false), live.completions, "a teardown left a live task uncompleted")

        // A pass the runner no longer knows.
        val forgotten = pass(2)
        val bridge = bridge()
        bridge.offer(2)
        outcomes.remove(2)
        val lost = Task(events)
        bridge.launched(submitted.last(), lost)
        runCurrent()
        assertEquals(listOf(false), lost.completions)
        forgotten.complete(PassOutcome.COMPLETED)

        // An expiry racing the end: completed once, by whichever lands.
        val racing = pass(3)
        val third = bridge()
        third.offer(3)
        val raced = Task(events)
        third.launched(submitted.last(), raced)
        racing.complete(PassOutcome.COMPLETED)
        third.expired(submitted.last())
        runCurrent()
        assertEquals(listOf(true), raced.completions)
    }

    @Test
    fun aPassThatAlreadyEndedIsNotOfferedAndOneTaskAtATime() = runTest {
        pass(1).complete(PassOutcome.COMPLETED)
        val bridge = bridge()
        bridge.offer(1)
        assertTrue(submitted.isEmpty(), "an ended pass was offered")
        bridge.offer(9)
        assertTrue(submitted.isEmpty(), "a pass the runner never started was offered")

        pass(2)
        bridge.offer(2)
        bridge.offer(2)
        assertEquals(1, submitted.size, "a second press submitted a second task over one pass")
    }

    @Test
    fun eachRequestHasAFreshIdentifierUnderThePrefix() = runTest {
        val bridge = bridge()
        repeat(3) { i ->
            val outcome = pass(i + 1L)
            bridge.offer(i + 1L)
            bridge.launched(submitted.last(), Task(events))
            outcome.complete(PassOutcome.COMPLETED)
            runCurrent()
        }
        assertEquals(listOf("app.test.indexnow.s1", "app.test.indexnow.s2", "app.test.indexnow.s3"), submitted)
        assertNull(bridge.identifier())
    }

    @Test
    fun withNoSchedulerNothingIsOffered() = runTest {
        pass(1)
        val bridge = ContinuedIndexing(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
            scheduler = { null },
            outcomeOf = { outcomes[it] },
            progress = { bar },
            settled = {},
            released = { events += "released" },
            identifierPrefix = "p.",
            newSuffix = { "x" },
        )
        bridge.offer(1)
        assertFalse(bridge.holdsPass())
        assertTrue(events.isEmpty())
    }

    @Test
    fun theTerminalOutcomeOfEachEnding() {
        assertEquals(PassOutcome.COMPLETED, passOutcomeOf(PassEnding.Finished(1, IndexPassResult(embedded = 1))))
        assertEquals(PassOutcome.REFUSED, passOutcomeOf(PassEnding.Finished(1, IndexPassResult(deferred = true))))
        assertEquals(PassOutcome.REFUSED, passOutcomeOf(PassEnding.Finished(1, IndexPassResult(paused = true))))
        assertEquals(PassOutcome.CANCELLED, passOutcomeOf(PassEnding.Stopped(1)))
        assertEquals(PassOutcome.FAILED, passOutcomeOf(PassEnding.Failed(1, "boom")))
    }

    /** A teardown while submitted still drops the claim, so a window a leave held ends. */
    @Test
    fun aTeardownWhileSubmittedEndsTheHeldWindow() = runTest {
        pass(1)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val bridge = bridge(scope = scope)
        bridge.offer(1)
        assertTrue(bridge.holdUntilResolved { events += "held end" })
        scope.cancel()
        runCurrent()
        assertEquals(listOf("submit", "released", "held end"), events, "a teardown left the held window open")
        assertFalse(bridge.holdsPass())
    }
}
