package app.tenshu.timeline.data.capture

import app.tenshu.timeline.capture.TimelineCaptureControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The M9-6 launch trigger: sweep once when capture is on, do nothing when it is off — and, since the
 * on-device finding, only *after* the proxy channel is up, retrying while the store cannot be read.
 *
 * The capture gate is not arbitrary — `RailRefinement.needsRefinement` skips imported
 * (external-labelled) traces, so captured movement is the *only* source of refinable traces. These
 * cases pin that the trigger spends no egress for a user who never opted into capture.
 */
class RailRefinementTriggerTest {

    private class FakeCaptureControl(enabled: Boolean) : TimelineCaptureControl {
        override val enabled: StateFlow<Boolean> = MutableStateFlow(enabled)
        override fun setEnabled(enabled: Boolean) = Unit
    }

    private fun ok(examined: Int = 0) =
        RailRefinementSweep.SweepResult(examined = examined, refined = 0, unavailable = 0)

    private fun unreadable() =
        RailRefinementSweep.SweepResult(0, 0, 0, tracesUnreadable = true)

    @Test
    fun sweepsOnceWhenCaptureIsOn() = runTest {
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok() },
        ).runOnce()
        assertEquals(1, runs)
    }

    @Test
    fun doesNotSweepWhenCaptureIsOff() = runTest {
        // A user who never captured has no refinable traces; the trigger must not touch the network.
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = false),
            sweep = { runs++; ok() },
        ).runOnce()
        assertEquals(0, runs)
    }

    @Test
    fun waitsForTheProxyChannelBeforeReadingTheStore() = runTest {
        // The live defect: the sweep ran 1s into a cold start, read nothing, and never retried.
        val connected = MutableStateFlow(false)
        var runs = 0
        val trigger = RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok(examined = 3) },
            proxyConnected = connected,
            sleep = {},
        )
        val job = launch { trigger.runOnce() }
        runCurrent()
        assertEquals(0, runs, "must not read the store while the channel is down")

        connected.value = true
        job.join()
        assertEquals(1, runs, "and must sweep as soon as it comes up")
    }

    @Test
    fun retriesWhileTheStoreCannotBeRead() = runTest {
        // `connected` is not `usable` — an announce-gate refusal has no edge to wait for, so the only
        // cover is a timer. Third attempt answers; the trigger must have kept trying to get there.
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { if (++runs < 3) unreadable() else ok(examined = 15) },
            sleep = {},
        ).runOnce()
        assertEquals(3, runs)
    }

    @Test
    fun stopsRetryingEventually() = runTest {
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; unreadable() },
            sleep = {},
        ).runOnce()
        assertTrue(runs in 2..8, "bounded retries, actually retried: $runs")
    }

    @Test
    fun doesNotRetryAnEmptyStore() = runTest {
        // ⚠ The distinction this whole fix rests on: empty is a settled answer, unreadable is not.
        // Retrying empty would burn wakeups on every launch of a fresh install to re-learn nothing.
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok(examined = 0) },
            sleep = {},
        ).runOnce()
        assertEquals(1, runs)
    }

    // ── §32.3: capture, not app launches, is the schedule ──────────────────────

    @Test
    fun sweepsWhenCaptureRecordsATrace() = runTest {
        // ⚠⚠ The reported defect: 70 km by train, a full day of clean capture, and no `app_start` —
        // so the launch-only trigger never fired and the ride kept its unrefined `drive` leg.
        var runs = 0
        val trigger = RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok(examined = 3) },
            sleep = {},
        )
        val job = launch { trigger.runOnNewTraces() }
        runCurrent()
        assertEquals(0, runs, "nothing to sweep until capture produces a trace")

        trigger.onTraceRecorded()
        runCurrent()
        assertEquals(1, runs)
        job.cancel()
    }

    @Test
    fun aBurstOfTracesCollapsesToOneSweep() = runTest {
        // A dwell confirmation flushes every leg buffered behind it — six at once in the real logs.
        // One sweep examines all of them, so N passes would only re-read the same store N times.
        var runs = 0
        val trigger = RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok(examined = 6) },
            sleep = {},
        )
        val job = launch { trigger.runOnNewTraces() }
        runCurrent()
        repeat(6) { trigger.onTraceRecorded() }
        runCurrent()
        assertEquals(1, runs, "the burst is one unit of work, not six")
        job.cancel()
    }

    @Test
    fun aLaterTraceSweepsAgain() = runTest {
        // ⚠ The twin for the conflation above: coalescing a burst must not swallow the *next* journey.
        var runs = 0
        val trigger = RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok(examined = 1) },
            sleep = {},
        )
        val job = launch { trigger.runOnNewTraces() }
        runCurrent()
        trigger.onTraceRecorded()
        runCurrent()
        trigger.onTraceRecorded()
        runCurrent()
        assertEquals(2, runs)
        job.cancel()
    }

    @Test
    fun aNudgeWithCaptureOffSweepsNothing() = runTest {
        // The gate is re-read per nudge, not captured at construction: switching capture off must
        // stop the egress, and `runOnce` is the single place that decision lives.
        var runs = 0
        val trigger = RailRefinementTrigger(
            FakeCaptureControl(enabled = false),
            sweep = { runs++; ok() },
            sleep = {},
        )
        val job = launch { trigger.runOnNewTraces() }
        runCurrent()
        trigger.onTraceRecorded()
        runCurrent()
        assertEquals(0, runs)
        job.cancel()
    }

    @Test
    fun givesUpWhenTheProxyNeverConnects() = runTest {
        // A device with no Control installed must not hold the coroutine forever.
        var runs = 0
        RailRefinementTrigger(
            FakeCaptureControl(enabled = true),
            sweep = { runs++; ok() },
            proxyConnected = flowOf(false),
            sleep = {},
        ).runOnce()
        assertEquals(0, runs)
    }
}
