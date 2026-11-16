package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GR-7's caught-up gate: "a complete pull started after I looked", counted, not clocked. */
class PullProgressTest {

    @Test
    fun aCompletePullStartedAfterTheObservation_counts() {
        val pulls = PullProgress()
        val seen = pulls.observe()
        pulls.finish(pulls.begin(), complete = true)
        assertTrue(pulls.completeSince(seen))
    }

    @Test
    fun aPullAlreadyInFlightWhenObserved_doesNotCount() {
        val pulls = PullProgress()
        val inFlight = pulls.begin()
        val seen = pulls.observe()
        pulls.finish(inFlight, complete = true)
        assertFalse(pulls.completeSince(seen), "it may have read the stream before the observation")
    }

    @Test
    fun aPullThatLeftMoreBehind_doesNotCount_andClearsAnEarlierComplete() {
        val pulls = PullProgress()
        val seen = pulls.observe()
        pulls.finish(pulls.begin(), complete = true)
        pulls.finish(pulls.begin(), complete = false)
        assertFalse(pulls.completeSince(seen), "a backlog again means not caught up")
    }

    @Test
    fun aCompletePullBeforeTheObservation_doesNotCount() {
        val pulls = PullProgress()
        pulls.finish(pulls.begin(), complete = true)
        assertFalse(pulls.completeSince(pulls.observe()))
    }
}
