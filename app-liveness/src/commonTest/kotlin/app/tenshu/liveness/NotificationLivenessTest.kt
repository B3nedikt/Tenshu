package app.tenshu.liveness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The edge logic used to be tested only through Drive's `FolderUploadLivenessTest`; now that it is a
 * shared module it earns its own direct coverage. The load-bearing behaviour is the *edges*: promote
 * exactly once on idle→in-flight, refresh (not re-promote) while in flight, and — the subtle one —
 * still deliver the final clear after a terminal summary demoted the service.
 */
class NotificationLivenessTest {

    private val events = mutableListOf<String>()
    private val handle = object : LivenessNotificationHandle {
        override fun onActive(content: LivenessNotificationContent) { events += "active:${content.text}" }
        override fun onContentChanged(content: LivenessNotificationContent) { events += "change:${content.text}" }
        override fun onTerminal(content: LivenessNotificationContent) { events += "terminal:${content.text}" }
        override fun onIdle() { events += "idle" }
    }

    private fun content(text: String, phase: LivenessPhase) = LivenessNotificationContent("t", text, phase)

    @Test
    fun promotesOnceThenRefreshesWhileInFlight() {
        val liveness = NotificationLiveness<String?>(MutableStateFlow(null), { it?.let { s -> content(s, LivenessPhase.IN_FLIGHT) } }, handle)
        liveness.onState("1")
        liveness.onState("2")
        liveness.onState("3")
        assertEquals(listOf("active:1", "change:2", "change:3"), events)
    }

    @Test
    fun aTerminalSummaryStillDeliversTheFollowingClear() {
        // The bug this guards: `active` resets on terminal, so a naive impl swallows the clear→idle
        // edge. `shown` keeps it alive until the controller clears state.
        val liveness = NotificationLiveness<Pair<String, LivenessPhase>?>(
            MutableStateFlow(null),
            { it?.let { (text, phase) -> content(text, phase) } },
            handle,
        )
        liveness.onState("go" to LivenessPhase.IN_FLIGHT)
        liveness.onState("done" to LivenessPhase.TERMINAL)
        liveness.onState(null)
        assertEquals(listOf("active:go", "terminal:done", "idle"), events)
    }

    @Test
    fun initialIdleStillFiresOnIdle() {
        // The Fit launch crashloop: the launcher startForegroundService()s on the in-flight edge,
        // but a fast-failing import (permissions missing -> ABORT in ~1 ms) is over before the
        // service's collector attaches. The first observed state is then already idle — and the
        // old `if (shown)` gate swallowed it, so the service neither foregrounded nor stopped and
        // Android killed the app (ForegroundServiceDidNotStartInTimeException). Initial idle must
        // stop the service.
        val liveness = NotificationLiveness<String?>(
            MutableStateFlow(null),
            { it?.let { s -> content(s, LivenessPhase.IN_FLIGHT) } },
            handle,
        )
        liveness.onState(null)
        assertEquals(listOf("idle"), events)
    }

    @Test
    fun inFlightRefreshesAreThrottledButEdgesAlwaysGoThrough() {
        // Fit's import ticks once per record; Android drops notification updates past ~5/s, which
        // froze the card at "18 records so far" and shed the final summary with it (2026-10-30).
        val time = TestTimeSource()
        val liveness = NotificationLiveness<Pair<String, LivenessPhase>?>(
            MutableStateFlow(null),
            { it?.let { (text, phase) -> content(text, phase) } },
            handle,
            minContentChangeInterval = 1.seconds,
            timeSource = time,
        )
        liveness.onState("0" to LivenessPhase.IN_FLIGHT)
        for (n in 1..50) {
            time += 10.milliseconds
            liveness.onState("$n" to LivenessPhase.IN_FLIGHT)
        }
        time += 600.milliseconds // 1.1 s since the promotion
        liveness.onState("51" to LivenessPhase.IN_FLIGHT)
        liveness.onState("52" to LivenessPhase.IN_FLIGHT)
        // The terminal edge lands 0 ms after a refresh, and must not be throttled.
        liveness.onState("done" to LivenessPhase.TERMINAL)
        assertEquals(listOf("active:0", "change:51", "terminal:done"), events)
    }

    @Test
    fun noIntervalMeansEveryRefreshIsDelivered() {
        val liveness = NotificationLiveness<String?>(
            MutableStateFlow(null),
            { it?.let { s -> content(s, LivenessPhase.IN_FLIGHT) } },
            handle,
            timeSource = TestTimeSource(), // frozen clock: only the default ZERO interval lets these through
        )
        liveness.onState("1")
        liveness.onState("2")
        assertEquals(listOf("active:1", "change:2"), events)
    }
}
