package app.tenshu.reachability

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlReachabilityTest {

    @Test
    fun probeOwnsDetectionAndRecovery() = runTest {
        var alive = false
        var recovered = 0
        val reach = ControlReachability(
            probe = { alive },
            scope = backgroundScope,
            probeIntervalMs = 100,
            onRecovered = { recovered += 1 },
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        assertNull(reach.state.value, "unknown until a first outcome")

        // Dead: the first probe tick flips to false — no read ever had to fail (streams hang).
        advanceTimeBy(150); runCurrent()
        assertEquals(false, reach.state.value)
        assertEquals(0, reach.readEpoch.value)

        // Still dead: keeps probing, state stays false.
        advanceTimeBy(300); runCurrent()
        assertEquals(false, reach.state.value)

        // Control returns: state flips, epoch bumps, the recovery hook runs exactly once.
        alive = true
        advanceTimeBy(150); runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(1, reach.readEpoch.value)
        assertEquals(1, recovered)

        // Healthy: the loop has stopped — no further bumps or hook runs.
        advanceTimeBy(500); runCurrent()
        assertEquals(1, reach.readEpoch.value)
        assertEquals(1, recovered)
    }

    /**
     * ⚠⚠ **What this pins is a hazard, not a feature, and it is why [ControlReachability
     * .markReachable] is `internal`.** Setting the state directly stops the probe loop *and*
     * skips the recovery — no epoch bump, so a hung `resubscribing` stream stays hung and
     * `onRecovered` never runs. Every app used to do this from its read folds. Nothing outside
     * this module can any more; `answered` wakes the probe instead
     * ([anAnsweredCallWakesTheProbeButDoesNotItselfAnswer]).
     */
    @Test
    fun organicSuccessCancelsTheProbeLoopAndSkipsTheRecovery() = runTest {
        var probed = 0
        val reach = ControlReachability(
            probe = { probed += 1; false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(150); runCurrent()
        assertEquals(false, reach.state.value)
        val probesWhileDown = probed

        reach.markReachable()
        runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(probesWhileDown, probed, "the loop stopped — nothing re-checks the claim")
        assertEquals(0, reach.readEpoch.value, "and the recovery it beat the probe to is lost")
    }

    /**
     * The replacement for the organic setter above: an answered call is evidence, and the probe
     * still decides.
     *
     * ⚠⚠ The refusing arm is the whole point. A proxy that refuses the session on a schema
     * mismatch still *answers* its announce, so if this signal set the state the banner would
     * clear on a device that can read nothing — the SK-1 defect. `CallOutcome` keeps that case out
     * (a refusal is `AnsweredWithError`), and waking rather than answering keeps it out twice.
     */
    @Test
    fun anAnsweredCallWakesTheProbeButDoesNotItselfAnswer() = runTest {
        var probed = 0
        var refusing = true
        val answered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reach = ControlReachability(
            probe = { probed += 1; !refusing },
            scope = backgroundScope,
            probeIntervalMs = 60_000,
            answered = answered,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(60_001); runCurrent()
        assertEquals(false, reach.state.value)
        val probesWhileDown = probed

        // A call the proxy served, well inside the probe interval.
        answered.tryEmit(Unit)
        runCurrent()
        assertEquals(probesWhileDown + 1, probed, "the answered call woke the probe early")
        assertEquals(false, reach.state.value, "a served call is not an answer about reads")
        assertEquals(0, reach.readEpoch.value)
    }

    /**
     * And the half [organicSuccessCancelsTheProbeLoopAndSkipsTheRecovery] gets wrong: because the
     * flip goes through the probe, the recovery actually runs.
     */
    @Test
    fun anAnsweredCallRecoversThroughTheProbe_soTheEpochBumps() = runTest {
        var refusing = true
        var recovered = 0
        val answered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reach = ControlReachability(
            probe = { !refusing },
            scope = backgroundScope,
            probeIntervalMs = 60_000,
            onRecovered = { recovered += 1 },
            answered = answered,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(60_001); runCurrent()
        assertEquals(false, reach.state.value)

        refusing = false
        answered.tryEmit(Unit)
        runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(1, reach.readEpoch.value, "hung streams re-subscribe")
        assertEquals(1, recovered, "and Fit's outbox drains")
    }

    /** ⚠ Absent, the engine is exactly as it was — no wake source, so the plain interval stands. */
    @Test
    fun withoutAnAnsweredSourceTheLoopStillTicksOnItsInterval() = runTest {
        var probed = 0
        val reach = ControlReachability(
            probe = { probed += 1; false },
            scope = backgroundScope,
            probeIntervalMs = 100,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(350); runCurrent()
        assertTrue(probed >= 3, "probed $probed times")
        assertEquals(false, reach.state.value)
    }

    @Test
    fun organicFailureAcceleratesDetection() = runTest {
        val reach = ControlReachability(probe = { false }, scope = backgroundScope, probeIntervalMs = 10_000, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        reach.markUnreachable()
        assertEquals(false, reach.state.value, "a folded transport failure flips the state immediately")
    }

    @Test
    fun initialHealthyProbeDoesNotBumpTheEpoch() = runTest {
        val reach = ControlReachability(probe = { true }, scope = backgroundScope, probeIntervalMs = 100, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        advanceTimeBy(150); runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(0, reach.readEpoch.value, "null → true is a first answer, not a recovery")
    }

    @Test
    fun resubscribingRebuildsTheStreamOnRecovery() = runTest {
        var alive = false
        val reach = ControlReachability(probe = { alive }, scope = backgroundScope, probeIntervalMs = 100, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        var builds = 0
        val latest = MutableStateFlow<String?>(null)
        backgroundScope.launch {
            reach.resubscribing {
                builds += 1
                // A dead stream: emits nothing, never completes — the production hang.
                if (!alive) flow { kotlinx.coroutines.awaitCancellation() } else flow { emit("data") }
            }.collect { latest.value = it }
        }
        runCurrent()
        assertEquals(1, builds)
        advanceTimeBy(150); runCurrent()
        assertNull(latest.value, "hung stream delivered nothing")

        alive = true
        advanceTimeBy(150); runCurrent()
        assertEquals(2, builds, "recovery re-subscribed the stream")
        assertEquals("data", latest.value, "the live screen repopulated without re-entry")
    }

    @Test
    fun aDeadTransportIsReportedWithoutWaitingForAProbe() = runTest {
        var probed = 0
        val connected = MutableStateFlow(true)
        val reach = ControlReachability(
            probe = { probed += 1; true },
            scope = backgroundScope,
            probeIntervalMs = 5_000,
            transportConnected = connected,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )

        connected.value = false
        runCurrent()
        assertEquals(false, reach.state.value, "the transport answers immediately — no probe wait")
        assertEquals(0, probed)

        // And it keeps not probing: a round trip across a dead channel can only fail.
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, probed, "no probing while the channel is known down")
        assertEquals(false, reach.state.value)
    }

    @Test
    fun aReconnectWakesTheProbeEarlyButDoesNotItselfAnswer() = runTest {
        var probed = 0
        var refusing = true
        val connected = MutableStateFlow(true)
        val reach = ControlReachability(
            probe = { probed += 1; !refusing },
            scope = backgroundScope,
            probeIntervalMs = 60_000,
            transportConnected = connected,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        connected.value = false
        runCurrent()
        assertEquals(false, reach.state.value)

        // Reconnect: the probe runs at once rather than after the 60s interval — but the proxy is
        // refusing reads (announce-gate skew), so a live channel is NOT taken as an answer.
        connected.value = true
        runCurrent()
        assertEquals(1, probed, "the reconnect edge woke the probe")
        assertEquals(false, reach.state.value, "connected is not the same as usable")
        assertEquals(0, reach.readEpoch.value)

        // The app updates and the gate admits the session: the next probe recovers as usual.
        refusing = false
        advanceTimeBy(60_001); runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(1, reach.readEpoch.value, "recovery re-subscribed the reads")
    }

    @Test
    fun aTransportDropWhileHealthyReopensTheProbeLoop() = runTest {
        val connected = MutableStateFlow(true)
        val reach = ControlReachability(
            probe = { true },
            scope = backgroundScope,
            probeIntervalMs = 100,
            transportConnected = connected,
            controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"),
        )
        advanceTimeBy(150); runCurrent()
        assertEquals(true, reach.state.value)

        // Control is killed. Without the transport input this stayed `true` — the loop only runs
        // while state != true, and nothing was reading to fold a failure into markUnreachable.
        connected.value = false
        runCurrent()
        assertEquals(false, reach.state.value)

        connected.value = true
        advanceTimeBy(150); runCurrent()
        assertEquals(true, reach.state.value)
        assertEquals(1, reach.readEpoch.value, "the round trip counts as a recovery")
    }

    @Test
    fun nullScopeDisablesTheProbe() = runTest {
        var probed = false
        val reach = ControlReachability(probe = { probed = true; true }, scope = null, probeIntervalMs = 10, controlHasRealm = app.tenshu.reachability.RealmProbe.None("test: no realm probe"))
        advanceTimeBy(1_000); runCurrent()
        assertFalse(probed)
        assertNull(reach.state.value)
        reach.markUnreachable()
        assertEquals(false, reach.state.first())
        assertTrue(true)
    }
}
