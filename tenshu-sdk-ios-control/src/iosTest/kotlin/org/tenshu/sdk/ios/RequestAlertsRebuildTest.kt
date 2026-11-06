package org.tenshu.sdk.ios

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestAlertHandle
import app.tenshu.control.notifications.PendingRequestAlerts
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSRecursiveLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins `ios-shared-store-sqlite.md` B.16 (228): Control's OS alerts follow one graph's request
 * feed. When the shared store's host stops that graph the collector is cancelled, and the rebuild
 * starts one over the new graph's feed, with the one diff of the process, so what was already
 * shown is not alerted again. Every name is made up.
 */
class RequestAlertsRebuildTest {

    private class Handle : PendingRequestAlertHandle {
        private val lock = NSRecursiveLock()
        private val seen = ArrayList<String>()
        override fun onRequestArrived(request: PendingRequest) {
            lock.lock(); try { seen += request.id } finally { lock.unlock() }
        }
        override fun onRequestResolved(id: String) = Unit
        fun arrived(): List<String> {
            lock.lock(); try { return seen.toList() } finally { lock.unlock() }
        }
    }

    private fun grant(name: String) = PendingRequest.MembershipGrant(grantorFingerprint = name, timestampMillis = 1L)

    private fun within(timeoutMs: Long, what: String, done: () -> Boolean) {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done()) {
            if (start.elapsedNow().inWholeMilliseconds > timeoutMs) fail("$what: not within $timeoutMs ms")
            platform.posix.usleep(10_000u)
        }
    }

    @Test
    fun aStoppedGraphsFeedIsNoLongerFollowedAndTheRebuiltGraphsIs() {
        val handle = Handle()
        val alerts = RequestAlerts { PendingRequestAlerts(handle) }
        val oldFeed = MutableStateFlow<List<PendingRequest>>(listOf(grant("made-up-a")))
        alerts.start(oldFeed)
        within(5_000, "the old feed's request alerted") { handle.arrived() == listOf("grant:made-up-a") }

        // The old graph stopped: its collector is gone.
        alerts.stop()
        assertFalse(alerts.isRunning)
        oldFeed.value = listOf(grant("made-up-a"), grant("made-up-late"))
        platform.posix.usleep(200_000u)
        assertEquals(listOf("grant:made-up-a"), handle.arrived(), "nothing of the stopped graph's feed")

        // The rebuild: the new graph's feed, and what was shown is not shown again.
        val newFeed = MutableStateFlow<List<PendingRequest>>(listOf(grant("made-up-a"), grant("made-up-b")))
        alerts.start(newFeed)
        assertTrue(alerts.isRunning)
        assertSame(newFeed, alerts.following)
        within(5_000, "the new feed's request alerted") { "grant:made-up-b" in handle.arrived() }
        assertEquals(listOf("grant:made-up-a", "grant:made-up-b"), handle.arrived(), "one diff for the process")
        alerts.stop()
    }
}
