package app.tenshu.control.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit test on the host-pairing session → liveness mapping
 * (`control-pairing-ux.md` §3.5 / §3.6). Drives [HostPairingLiveness.onContent] directly —
 * no Android runtime, no coroutines — so the promote / refresh / demote contract is locked
 * independently of the foreground-service plumbing (Android not locally compilable, mirrors
 * `ForegroundTaskLivenessTest`). The combine/collect wiring is correctness-by-construction.
 */
class HostPairingLivenessTest {

    private sealed interface Event {
        data class Active(val content: HostPairingNotificationContent) : Event
        data class Changed(val content: HostPairingNotificationContent) : Event
        data object Idle : Event
    }

    private class RecordingHandle : HostPairingNotificationHandle {
        val events = mutableListOf<Event>()
        override fun onActive(content: HostPairingNotificationContent) {
            events += Event.Active(content)
        }

        override fun onContentChanged(content: HostPairingNotificationContent) {
            events += Event.Changed(content)
        }

        override fun onIdle() {
            events += Event.Idle
        }
    }

    private fun waiting(seconds: Int?) =
        HostPairingNotificationContent(requestPending = false, remainingSeconds = seconds)

    private fun requestPending() =
        HostPairingNotificationContent(requestPending = true, remainingSeconds = 90)

    private fun liveness(handle: RecordingHandle): HostPairingLiveness {
        // The flows are unused by onContent (the test drives it directly); pass benign stubs.
        return HostPairingLiveness(
            isPairing = kotlinx.coroutines.flow.MutableStateFlow(false),
            remainingSeconds = kotlinx.coroutines.flow.MutableStateFlow(null),
            activeRequest = kotlinx.coroutines.flow.MutableStateFlow(null),
            handle = handle,
        )
    }

    @Test
    fun starting_a_session_promotes_and_clearing_it_demotes() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onContent(waiting(120))
        live.onContent(null)

        assertEquals(listOf(Event.Active(waiting(120)), Event.Idle), handle.events)
    }

    @Test
    fun countdown_ticks_and_the_request_flip_only_refresh_the_notification() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onContent(waiting(120))
        live.onContent(waiting(119))
        live.onContent(requestPending()) // joiner arrived — flips copy, still active
        live.onContent(null)

        assertEquals(
            listOf(
                Event.Active(waiting(120)),
                Event.Changed(waiting(119)),
                Event.Changed(requestPending()),
                Event.Idle,
            ),
            handle.events,
        )
    }

    @Test
    fun idle_is_idempotent_and_re_activation_fires_onActive_again() {
        val handle = RecordingHandle()
        val live = liveness(handle)

        live.onContent(null) // never went active
        live.onContent(waiting(120))
        live.onContent(null)
        live.onContent(null) // already idle
        live.onContent(waiting(120)) // a fresh session

        assertEquals(
            listOf(
                Event.Active(waiting(120)),
                Event.Idle,
                Event.Active(waiting(120)),
            ),
            handle.events,
        )
    }
}
