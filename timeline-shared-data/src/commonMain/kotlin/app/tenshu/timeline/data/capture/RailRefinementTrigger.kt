package app.tenshu.timeline.data.capture

import app.tenshu.diagnostics.Diagnostics
import app.tenshu.timeline.capture.TimelineCaptureControl
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the [RailRefinementSweep] on app start, when capture is on
 * (`docs/proposals/timeline-movement-capture.md` M9-6). The counterpart to
 * `GeocodingBackfillTrigger` — an app-side, opt-in, network-touching enrichment that deliberately
 * does **not** wait for bgproc Phase 6 (the periodic scheduler is proxy-side and a poor fit for this
 * `TenshuClient` sweep; the timeline proposal picked "a manual/opt-in trigger suffices until then").
 *
 * **Why the capture opt-in is the right gate, not an arbitrary one.** `RailRefinement.needsRefinement`
 * skips externally-labelled traces (`MOVEMENT_CLASSIFIER_EXTERNAL` — Google's imported `activityType`),
 * so the *only* traces the sweep can ever refine are the ones our own classifier produced — i.e.
 * captured movement. A user who only imported history has nothing to refine, so there is nothing to
 * gate for them; a user who captures is exactly the population with candidates, and has already
 * accepted the background-location feature that produces them.
 *
 * **What actually egresses.** The sweep sends each trace's bounding box to Control, which fetches
 * public map tiles. Control already stores the trace (it is the proxy), and tiles carry no user data
 * (HR #1), so this reveals nothing to any third party — unlike geocoding, which sends coordinates to
 * an outside provider. The gate is therefore about spending data/battery on tile fetches, not about
 * a new disclosure.
 *
 * Runs **once per opt-in edge** rather than on a timer: the sweep is bounded ([RailRefinementSweep]'s
 * per-run limit) and idempotent (version-stamped, content-addressed upserts), so a launch-time pass
 * converges over history across sessions without a scheduler.
 *
 * ### ⚠⚠ Why a launch pass alone was not a schedule (§32.3)
 *
 * "Once per launch" silently assumes the app gets launched. On 2026-08-12 the user travelled 70 km by
 * train and never opened Timeline: `timeline.log` records a full day of clean capture and **not one
 * `app_start`**, so the sweep did not run at all. The consequences compound rather than merely
 * delaying a label — the first leg of the ride kept its speed-based `drive` verdict, and
 * `TimelineProjection.collapseTransitStationStops` requires *both* sides of a station stop to be
 * `transit`, so a single un-refined leg left the whole journey shredded into `Drive · 22.3 km` →
 * a stay at a station the user passed through → `Transit · 48.9 km`. The tier that fixes this had
 * been sitting behind an event that never fired.
 *
 * So the sweep is also driven by the thing that actually creates work for it: [onTraceRecorded],
 * called when capture makes a leg durable, with [runOnNewTraces] collecting those nudges for the
 * lifetime of the capture session. A new trace is precisely — and only — when a refinable trace comes
 * into existence, which makes this an exact schedule rather than a polling interval guessed at.
 *
 * ⚠ Nudges are **conflated and quiet-periodded** ([QUIET_PERIOD_MS]), because a flush is bursty by
 * construction: one dwell confirmation flushes every leg buffered behind it (six at once in the
 * recorded logs). Coalescing turns that burst into one pass over all of them, which is also the
 * cheaper shape — the sweep reads *all* candidate traces per run, so N passes would re-read the same
 * store N times.
 *
 * ⚠ This cannot feed back on itself: the sweep writes traces, but only *capture* nudges, so there is
 * no write → sweep → write loop to damp.
 *
 * ### ⚠⚠ Why "once per launch" needed a readiness rule to actually mean once per launch
 *
 * One pass per launch is only a *sufficient* schedule if that pass can see the store. It could not:
 * this trigger fired immediately in `onCreate`, and the sweep's first act is an **IPC read to
 * Control**, so on a cold start it raced the proxy binding. Measured on-device over three days of
 * real capture, the sweep logged exactly one line — `0 candidate(s) of 0 trace(s)` — one second after
 * `MY_PACKAGE_REPLACED`, against a store holding fifteen traces. Nothing retried, because an empty
 * answer looked like a complete one (`SweepResult.tracesUnreadable` is the other half of this fix).
 *
 * So readiness is now explicit, in two layers that cover each other's blind spot:
 *
 *  - **Wait for the channel before asking.** [proxyConnected] is `TenshuClient.proxyConnected`. This
 *    removes the race for the common cold-start case.
 *  - **Retry when the read did not answer.** ⚠ Necessary *in addition*, because connected is not
 *    usable: `ProxyConnectivity`'s own KDoc records that a connected proxy still refuses every call
 *    while the two sides disagree on a schema version, and a refusal has no edge to wait for. Only a
 *    timer covers that, so unreadable runs back off and try again.
 *
 * ⚠ A genuinely empty store is **not** retried — it is a settled answer, and re-reading it would burn
 * wakeups on every launch of a fresh install to re-learn nothing.
 */
class RailRefinementTrigger(
    private val control: TimelineCaptureControl,
    private val sweep: suspend () -> RailRefinementSweep.SweepResult,
    /**
     * Whether the client's channel to Control is up — `TenshuClient.proxyConnected`.
     *
     * Defaults to permanently-connected, which is the honest reading for an in-process proxy (iOS
     * route-1) and for tests: there is no channel to lose, so there is nothing to wait for.
     */
    private val proxyConnected: Flow<Boolean> = flowOf(true),
    /** Injected so tests do not sleep in real time. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Capture saying "a new leg is durable". Conflated on purpose — see the class KDoc: what matters
     * is *that* there is fresh work, never how many nudges announced it, and one sweep examines every
     * candidate anyway.
     */
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /**
     * A leg just became a durable trace — ask for a sweep. Non-suspending and never blocks the
     * caller: this is called from the capture write loop, which must not wait on tile fetches.
     */
    fun onTraceRecorded() {
        nudges.trySend(Unit)
    }

    /**
     * Sweep whenever capture records a trace, until the caller's scope is cancelled — the schedule
     * that makes refinement independent of the user ever opening the app.
     *
     * Bound to the capture session's lifetime rather than the process's: outside a capture session
     * nothing produces refinable traces, so there is nothing to listen for.
     */
    suspend fun runOnNewTraces() {
        for (nudge in nudges) {
            // Let the burst land before reading. A dwell confirmation flushes every leg buffered
            // behind it, and sweeping on the first of six would leave the other five for next time.
            sleep(QUIET_PERIOD_MS)
            // ⚠ Absorb what arrived during the wait into *this* pass. Conflation alone is not enough:
            // the first nudge of a burst is handed straight to the waiting receiver, so without this
            // drain every burst costs two sweeps — one for its head and one for the conflated tail,
            // the second finding nothing. Draining *before* the sweep is safe because the sweep reads
            // every candidate trace, so a leg written before this point is picked up regardless; one
            // written after it keeps its nudge and gets the next pass.
            while (nudges.tryReceive().isSuccess) { /* absorbed into this pass */ }
            runOnce()
        }
    }
    /**
     * Sweep once if capture is currently on, retrying only while the store could not be read.
     *
     * Returns immediately when capture is off — a user who has not opted into capture has no
     * refinable traces (see the class KDoc), so there is nothing to do and nothing to wait for.
     */
    suspend fun runOnce() {
        if (!control.enabled.first()) return

        // ⚠ Bounded: a device with no Control installed would otherwise hold this coroutine forever.
        // Giving up is safe — the next launch runs the pass again, which is the whole design.
        // ⚠ `firstOrNull`, not `first`: a *finite* flow that never emits `true` makes `first` throw
        // rather than wait, so the two "never connected" shapes — a live channel that stays down and a
        // flow that simply ends — must both fall through to the same skip, not one skip and one crash.
        val connected =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { proxyConnected.firstOrNull { it } } == true
        if (!connected) {
            Diagnostics.w(
                "sweep",
                "rail sweep skipped: proxy did not connect within ${CONNECT_TIMEOUT_MS / 1000}s",
                code = "rail_sweep_no_proxy",
            )
            return
        }

        repeat(MAX_ATTEMPTS) { attempt ->
            if (!sweep().tracesUnreadable) return
            // Linear back-off. The gap being retried is a startup transient, so the useful range is
            // seconds; an exponential curve would spend its budget waiting rather than retrying.
            if (attempt < MAX_ATTEMPTS - 1) sleep(RETRY_DELAY_MS * (attempt + 1))
        }
        Diagnostics.w(
            "sweep",
            "rail sweep gave up after $MAX_ATTEMPTS unreadable attempts",
            code = "rail_sweep_gave_up",
        )
    }

    private companion object {
        /** How long to wait for the proxy channel before deferring to the next launch. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** Total attempts when the read keeps not answering. */
        const val MAX_ATTEMPTS = 4

        /** Base back-off between unreadable attempts; multiplied by the attempt number. */
        const val RETRY_DELAY_MS = 2_000L

        /**
         * How long a nudge waits for the rest of its burst before [runOnNewTraces] sweeps.
         *
         * **Reasoned, not fitted.** It only has to outlast the *write* of the legs a single flush
         * produces — a handful of content-addressed upserts through the proxy, well under a second in
         * the recorded logs — so 30 s is an order of magnitude of headroom over the thing it waits
         * for. It is deliberately not longer: the sweep's whole value is that a ride is labelled
         * correctly by the time the user next looks, and they may well look right after arriving.
         */
        const val QUIET_PERIOD_MS = 30_000L
    }
}
