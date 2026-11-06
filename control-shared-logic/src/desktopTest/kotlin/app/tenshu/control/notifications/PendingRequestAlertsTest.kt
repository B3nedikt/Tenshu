package app.tenshu.control.notifications

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 3 OS elevation (`control-central-notifications.md` §4.3.4): unit test on the
 * arrived/resolved diff, driven directly — no platform runtime — so the contract is
 * locked independently of the (Android-only) notification plumbing.
 */
class PendingRequestAlertsTest {

    private sealed interface Event {
        data class Arrived(val id: String) : Event
        data class Resolved(val id: String) : Event
    }

    private class RecordingHandle : PendingRequestAlertHandle {
        val events = mutableListOf<Event>()
        override fun onRequestArrived(request: PendingRequest) {
            events += Event.Arrived(request.id)
        }

        override fun onRequestResolved(id: String) {
            events += Event.Resolved(id)
        }
    }

    private fun grant(fp: String) = PendingRequest.MembershipGrant(fp, timestampMillis = 1L)
    private fun invite(id: String) =
        PendingRequest.ContactInviteRedeemed(id, "Alice", "fp", "123456", timestampMillis = 1L)

    @Test
    fun a_new_request_alerts_once_a_persisting_one_does_not_re_alert() {
        val handle = RecordingHandle()
        val alerts = PendingRequestAlerts(handle)

        alerts.onRequests(listOf(grant("a")))
        alerts.onRequests(listOf(grant("a"))) // still pending — no re-alert

        assertEquals(listOf<Event>(Event.Arrived("grant:a")), handle.events.toList())
    }

    @Test
    fun a_request_leaving_the_feed_cancels_its_notification() {
        val handle = RecordingHandle()
        val alerts = PendingRequestAlerts(handle)

        alerts.onRequests(listOf(grant("a")))
        alerts.onRequests(emptyList()) // resolved / dismissed

        assertEquals(
            listOf<Event>(Event.Arrived("grant:a"), Event.Resolved("grant:a")),
            handle.events.toList(),
        )
    }

    @Test
    fun mixed_arrivals_and_removals_diff_against_the_known_set() {
        val handle = RecordingHandle()
        val alerts = PendingRequestAlerts(handle)

        alerts.onRequests(listOf(grant("a"), invite("i1")))
        handle.events.clear()

        // grant:a stays, invite:i1 leaves, grant:b arrives.
        alerts.onRequests(listOf(grant("a"), grant("b")))

        assertEquals(
            setOf<Event>(Event.Arrived("grant:b"), Event.Resolved("invite:i1")),
            handle.events.toSet(),
        )
        assertEquals(2, handle.events.size)
    }

    @Test
    fun the_empty_startup_snapshot_alerts_nothing() {
        val handle = RecordingHandle()
        val alerts = PendingRequestAlerts(handle)

        alerts.onRequests(emptyList()) // StateFlow's initial emptyList()

        assertEquals(emptyList<Event>(), handle.events.toList())
    }
}
