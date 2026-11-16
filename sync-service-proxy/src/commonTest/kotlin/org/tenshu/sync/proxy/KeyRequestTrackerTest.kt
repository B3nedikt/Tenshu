package org.tenshu.sync.proxy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GR-7 key requests: the member's outstanding set and the two rate limits. */
class KeyRequestTrackerTest {

    private val hour = 60L * 60L * 1_000L

    /** ⚠ A request does not settle a realm: a lost request or answer must be retried once the interval passes. */
    @Test
    fun aRealmStaysBehindAfterARequest_untilAReKeyIsAdopted() {
        val tracker = KeyRequestTracker()
        tracker.markBehind("a", blockPhysicalTime = 1L)
        tracker.requested("a", nowMs = 0L)
        assertEquals(emptySet(), tracker.due(nowMs = hour), "throttled")
        assertEquals(setOf("a"), tracker.due(nowMs = 7 * hour), "retried after the interval")
        tracker.adopted("a")
        assertEquals(emptySet(), tracker.due(nowMs = 14 * hour))
    }

    /** ⚠ What a request names is every outstanding realm, so a later one never drops an earlier one's realm. */
    @Test
    fun theOutstandingSetKeepsARealmAlreadyAskedAbout() {
        val tracker = KeyRequestTracker()
        tracker.markBehind("a", blockPhysicalTime = 1L)
        tracker.requested("a", nowMs = 0L)
        tracker.markBehind("b", blockPhysicalTime = 1L)
        assertEquals(setOf("b"), tracker.due(nowMs = hour), "b is what makes a request due")
        assertEquals(setOf("a", "b"), tracker.behindRealms(), "but a is still named")
    }

    @Test
    fun anOwnerAnswersAMemberOncePerInterval() {
        val tracker = KeyRequestTracker()
        assertTrue(tracker.mayAnswer("r", "m", nowMs = 0L))
        assertTrue(tracker.mayAnswer("r", "m", nowMs = 1L), "nothing recorded until an answer went out")
        tracker.answered("r", "m", nowMs = 0L)
        assertFalse(tracker.mayAnswer("r", "m", nowMs = hour))
        assertTrue(tracker.mayAnswer("r", "other", nowMs = hour))
        assertTrue(tracker.mayAnswer("r", "m", nowMs = 7 * hour))
    }
}
