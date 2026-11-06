package org.tenshu.inference.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CoalescedRuns]: what serialises the launch-and-foreground check (`ios-on-device-inference.md` §14.17). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalescedRunsTest {

    @Test
    fun requestsMadeWhileOneRunsCauseOneMoreRunAndNotOneEach() = runTest(UnconfinedTestDispatcher()) {
        val starts = mutableListOf<Boolean>()
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        var inFlight = 0
        var mostAtOnce = 0
        val runs = CoalescedRuns(backgroundScope) { launch ->
            starts += launch
            mostAtOnce = maxOf(mostAtOnce, ++inFlight)
            CompletableDeferred<Unit>().also { gates += it }.await()
            inFlight--
        }
        // The launch's check runs; three more requests arrive while it does.
        val first = backgroundScope.async { runs.request(launch = true) }
        val late = List(3) { backgroundScope.async { runs.request(launch = false) } }
        assertEquals(listOf(true), starts)

        gates[0].complete(Unit)
        first.await()
        // ⚠⚠ ONE more run, for all three: each arrived after the first had started, so what
        // it had already done is done again for them, once.
        assertEquals(listOf(true, false), starts)
        assertTrue(late.none { it.isCompleted }, "a request was dropped although it arrived after the run had started")
        gates[1].complete(Unit)
        late.forEach { it.await() }
        assertEquals(2, starts.size, "requests that one run covered each ran again")
        assertEquals(1, mostAtOnce, "two sequences ran side by side")
    }

    @Test
    fun aRequestThatArrivesAfterARunHasEndedRunsAgainAndALaunchIsNotLost() = runTest(UnconfinedTestDispatcher()) {
        val starts = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        val runs = CoalescedRuns(backgroundScope) { launch ->
            starts += launch
            if (starts.size == 1) gate.await()
        }
        val first = backgroundScope.async { runs.request(launch = false) }
        // A launch's request arrives during a foreground's run, and another foreground's after
        // it: the one run that covers both is a launch's.
        val launch = backgroundScope.async { runs.request(launch = true) }
        val another = backgroundScope.async { runs.request(launch = false) }
        gate.complete(Unit)
        first.await()
        launch.await()
        another.await()
        assertEquals(listOf(false, true), starts)

        runs.request(launch = false)
        assertEquals(listOf(false, true, false), starts)
    }

    @Test
    fun aRunThatThrowsHoldsUpNoLaterRequest() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val runs = CoalescedRuns(backgroundScope) { if (++calls == 1) error("the check failed") }

        assertFalse(runCatching { runs.request(launch = true) }.isSuccess)
        runs.request(launch = false)
        assertEquals(2, calls)
    }

    @Test
    fun aRequestNobodyWaitsForRunsAllTheSame() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val runs = CoalescedRuns(backgroundScope) { calls++ }

        // What a commit does, from inside the downloader: asked, not waited for.
        runs.requestLater()

        assertEquals(1, calls)
    }
}
