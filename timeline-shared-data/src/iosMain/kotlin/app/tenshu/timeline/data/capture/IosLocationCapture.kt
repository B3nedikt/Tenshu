@file:OptIn(ExperimentalForeignApi::class)

package app.tenshu.timeline.data.capture

import kotlinx.coroutines.cancel
import app.tenshu.diagnostics.Diagnostics
import app.tenshu.timeline.data.wifi.IosWifiRecognition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLVisit
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreLocation.startMonitoringVisits
import platform.CoreLocation.stopMonitoringVisits
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The **iOS live capture acquisition** (M4 / I4-2, `docs/proposals/timeline-movement-capture.md` §6
 * + `timeline-ingestion.md` §4): `CLVisit` monitoring for dwells plus significant-location-change
 * fixes for the movement between them, feeding the shared write primitives directly — the **event
 * model** the [CapturedVisitWriter] KDoc designed for iOS, not Android's long-lived
 * `Flow` + collector (which might not drain inside iOS's brief background execution windows).
 *
 *  - **Dwells:** Apple already clusters — a `CLVisit` maps almost directly onto [CapturedVisit]
 *    (`CaptureModels` KDoc). An arrival (open-ended departure) becomes the *confirmation* emit
 *    (`ongoing = true`, drives [OngoingVisitState]); the closed visit becomes the final emit —
 *    content-addressing `(place, arrival)` folds both to one row, exactly like the Android
 *    segmenter's two emits (§11.1).
 *  - **Moves:** significant-change fixes run through the pure [EventDrivenCaptureSession] (the
 *    shared [MovementSegmenter] with CLVisit as the dwell authority); a visit event flushes the
 *    transit that led to it, terminated at the visit's own centroid.
 *
 * Both monitors **relaunch the app** on events, so the Swift shell re-arms this at launch; neither
 * needs the `location` `UIBackgroundModes` (that is for continuous standard updates — deliberately
 * not requested: the significant-change granularity is the battery deal iOS offers, and short
 * walks may fall under its resolution; §6 accepts that for v1).
 *
 * Off by default (threat-model A35(a)): nothing here runs until the capture runtime starts it
 * behind the persisted [IosTimelineCaptureControl] opt-in, and [start] requests Always
 * authorization exactly then — the toggle tap *is* the consent moment (Android's
 * `MainActivity` shape). A later denial ([kCLAuthorizationStatusDenied]/Restricted) fires
 * [onAuthorizationDenied] so the runtime can reset the opt-in flag.
 *
 * Writes ride the same [CapturedVisitWriter]/[CapturedMoveWriter] as every other source — content-
 * addressed, never-throwing, ordered here through a single worker channel so a burst of delegate
 * callbacks cannot interleave partial writes. ⚠ No durable *write* outbox on iOS yet (Android's
 * `AndroidVisitOutboxStore` twin is future work): a write that fails while the proxy graph is
 * sealed is dropped, logged via [Diagnostics]. The **open move** is durable regardless
 * ([iosOpenMoveJournal]) — that one is not a failed write but an un-flushed journey, and losing it
 * is unrecoverable rather than merely un-replayed.
 */
class IosLocationCapture(
    client: TenshuClient,
    context: TContext,
    private val visitConfig: VisitDetectionConfig = VisitDetectionConfig(),
    moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    private val onAuthorizationDenied: () -> Unit = {},
    /**
     * Durable open-move journal — defaults to the Application Support store, so the journey in
     * progress survives the process teardown that happens between every pair of location events.
     */
    journal: OpenMoveJournal = iosOpenMoveJournal(),
    /**
     * Opt-in Wi-Fi place recognition (§4.5) — folds a dwell onto the place the user named on this
     * network instead of its grid-snapped one. `null` (the default, and every platform without the
     * seams bound) leaves the grid id untouched, exactly as before.
     */
    private val wifi: IosWifiRecognition? = null,
    /**
     * ⚠ What each **drain** of the queued tasks runs inside (`ios-shared-store-sqlite.md`
     * Appendix B.15 (210)): a location event relaunches or wakes the app in the background, and
     * its writes reach the shared store. The route-1 runtime passes the shared replica's entry
     * token here, taken once per burst, not once per task ([drainInEntries]); the default runs the
     * drain as it is, which is every build with the SQLite switch off.
     */
    private val entry: suspend (drain: suspend () -> Unit) -> Unit = { drain -> drain() },
) {
    // Explicitly open (ADR 0025 §8.6): iOS route-1 wires no realm probe, so there is no gate to hold.
    private val visitWriter = CapturedVisitWriter(client, context, visitConfig, writesHeld = { false })
    private val moveWriter = CapturedMoveWriter(client, context, moveConfig, writesHeld = { false })
    private val session = EventDrivenCaptureSession(visitConfig, moveConfig, journal)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Ordered write worker: delegate callbacks enqueue, one coroutine drains — visit/move writes
    // land in event order and a callback burst can't interleave the segmenter state.
    // ⚠ The backstop for a task a cancelled receive could not hand over (the entry's expiry
    // cancels the drain mid-select): said, never silent.
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED) {
        Diagnostics.i("capture", "a queued capture task was not delivered (its entry was cancelled)", code = "task_undelivered")
    }

    // The manager must be created (and its delegate retained — CLLocationManager.delegate is weak)
    // on the main queue; both references live for the capture's lifetime.
    private var manager: CLLocationManager? = null
    private var delegate: CaptureDelegate? = null

    /** Begin monitoring (idempotent). Requests Always authorization when still undetermined. */
    fun start() {
        scope.launch { drainInEntries(work, entry) }
        dispatch_async(dispatch_get_main_queue()) {
            if (manager != null) return@dispatch_async
            val d = CaptureDelegate()
            val m = CLLocationManager()
            delegate = d
            manager = m
            m.delegate = d
            if (m.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
                m.requestAlwaysAuthorization()
            }
            m.startMonitoringVisits()
            m.startMonitoringSignificantLocationChanges()
            // A detached capture's manager kept the registration until now (B.16 (233)): stopped
            // and released, so no two managers are alive (238).
            parkedLocationMonitor.stopAndRelease()
        }
    }

    /**
     * ⚠ S0b-3 (`ios-shared-store-sqlite.md` B.16 (228)): this capture's graph was stopped (its
     * store fenced or released). The delegate, the worker and its scope go **without** the
     * session's end, which would write the open move through the stopped graph; the open move stays
     * in its durable journal, so the capture built over the new graph picks it up as a relaunch
     * does. A task queued at this moment is dropped (logged as undelivered), as one queued at a
     * process death is. The instance is spent.
     *
     * ⚠⚠ **Monitoring stays registered**: stopping visit and
     * significant-change monitoring would also end iOS's relaunch and wake registration, and a fence
     * inside a background location entry would turn capture off until the user opened Timeline. The
     * manager is parked, still monitoring, with no delegate, until the next capture's manager has
     * started monitoring ([start] stops and releases it), or until no successor needs it
     * ([stopParkedLocationMonitoring]: an opt-out, a denial, a graph given up; (238)).
     */
    fun detach() {
        dispatch_async(dispatch_get_main_queue()) {
            manager?.let {
                it.delegate = null
                parkedLocationMonitor.park(it)
            }
            manager = null
            delegate = null
            work.close()
            scope.cancel()
        }
    }

    /** Stop monitoring and flush the open movement tail; the instance is single-use after this. */
    fun stop() {
        // ⚠⚠ (238) An opt-out stops a parked manager too, never through the stopped graph.
        stopParkedLocationMonitoring()
        dispatch_async(dispatch_get_main_queue()) {
            manager?.let {
                it.stopMonitoringVisits()
                it.stopMonitoringSignificantLocationChanges()
                it.delegate = null
            }
            manager = null
            delegate = null
            work.trySend {
                session.onEnd().forEach { event -> moveWriter.write(event.move) }
                OngoingVisitState.clear()
                OngoingMoveState.clear()
                work.close() // the worker loop drains and exits; the instance is spent
            }
        }
    }

    private inner class CaptureDelegate : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val fixes = didUpdateLocations.filterIsInstance<CLLocation>().map { it.toFix() }
            work.trySend {
                fixes.forEach { fix ->
                    session.onFix(fix).forEach { event ->
                        when (event) {
                            is CaptureEvent.Move -> recordMove(event)
                            // The live overlay for the leg still open (§12.6) — never written.
                            is CaptureEvent.MoveProgress -> OngoingMoveState.set(event.open)
                            is CaptureEvent.Dwell -> Unit // CLVisit is the dwell authority here
                        }
                    }
                }
            }
        }

        override fun locationManager(manager: CLLocationManager, didVisit: CLVisit) {
            val visit = didVisit.toVisitEvent() ?: return
            work.trySend { onVisitEvent(visit) }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            val status: CLAuthorizationStatus = manager.authorizationStatus
            if (status == kCLAuthorizationStatusDenied || status == kCLAuthorizationStatusRestricted) {
                Diagnostics.i("capture", "location authorization denied", code = "auth_denied")
                onAuthorizationDenied()
            }
        }
    }

    /**
     * One `CLVisit` delegate event, in event order. Uniform for arrival and departure — iOS can
     * skip the arrival delivery and hand us only the closed visit, so both flush the led-up-to
     * transit; only the closed one re-seeds movement from the place (the segmenter's
     * "movement begins from the dwell's departure" shape).
     */
    private suspend fun onVisitEvent(visit: VisitEvent) {
        val centroid = CapturedFix(
            latE7 = visit.latE7,
            lngE7 = visit.lngE7,
            timestampMs = visit.arrivalMs,
            accuracyM = visit.accuracyM,
        )
        session.onDwellBoundary(centroid).forEach { recordMove(it) }
        // A dwell of either flavour ends the journey that led here (engine parity).
        OngoingMoveState.clear()
        val captured = CapturedVisit(
            latE7 = visit.latE7,
            lngE7 = visit.lngE7,
            arrivalMs = visit.arrivalMs,
            departureMs = visit.departureMs,
            accuracyM = visit.accuracyM,
        )
        // Wi-Fi recognition (§4.5), the `LocationCaptureEngine` hook's iOS twin: offer the
        // grid-snapped candidate to the fingerprint store and take back whatever place the network
        // says this is. ⚠ Both emits of one visit run this — they are the same content-addressed
        // row, so both must resolve to the same id or the confirmation and the close would land as
        // two places. They do: the confirmation's `learn` binds the network, and the close then
        // recognises it.
        val placeId = wifi?.resolvePlaceId(
            candidatePlaceId = VisitDetector.placeIdOf(captured, visitConfig),
            latE7 = visit.latE7,
            lngE7 = visit.lngE7,
        )
        visitWriter.write(captured, placeIdOverride = placeId)
        if (visit.ongoing) {
            Diagnostics.i("capture", "dwell confirmed (ongoing)", code = "dwell_ongoing")
            OngoingVisitState.set(OngoingVisit(visit.latE7, visit.lngE7, visit.arrivalMs))
        } else {
            Diagnostics.i("capture", "dwell closed", code = "dwell_closed")
            OngoingVisitState.clear()
            session.onFix(centroid.copy(timestampMs = visit.departureMs))
        }
    }

    private suspend fun recordMove(event: CaptureEvent.Move) {
        OngoingVisitState.clear() // moving → no longer at a place (engine parity)
        OngoingMoveState.clear()  // the leg is a real row now — clear before the write, never after
        moveWriter.write(event.move)
        Diagnostics.i(
            "capture",
            "move recorded: ${event.move.mode} ${event.move.distanceM.toInt()}m",
            code = "move_recorded",
        )
    }

    // ── CoreLocation → neutral DTO mapping ───────────────────────────────────

    private data class VisitEvent(
        val latE7: Long,
        val lngE7: Long,
        val arrivalMs: Long,
        val departureMs: Long,
        val accuracyM: Int,
        val ongoing: Boolean,
    )

    /**
     * Map a `CLVisit` to the neutral event, normalising Apple's open-ended sentinels: a distant-
     * future departure marks the **arrival** delivery (still there → `ongoing`, departure clamps to
     * now, exactly the segmenter's confirmation emit whose final departure later folds over it); a
     * distant-past arrival on a closed visit (monitoring started mid-stay) is clamped to one
     * `minDwellMs` before departure; a visit with *neither* bound carries no usable time → dropped.
     */
    private fun CLVisit.toVisitEvent(): VisitEvent? {
        val nowMs = (NSDate().timeIntervalSince1970 * 1000).roundToLong()
        val arrivalRaw = (arrivalDate.timeIntervalSince1970 * 1000).roundToLong()
        val departureRaw = (departureDate.timeIntervalSince1970 * 1000).roundToLong()
        val ongoing = departureRaw > nowMs + SENTINEL_SLACK_MS
        val arrivalKnown = arrivalRaw > SENTINEL_SLACK_MS // distantPast ≈ negative epoch
        if (ongoing && !arrivalKnown) return null
        val arrivalMs = if (arrivalKnown) arrivalRaw else departureRaw - visitConfig.minDwellMs
        val departureMs = if (ongoing) nowMs else departureRaw
        val (lat, lng) = coordinate.useContents { latitude to longitude }
        return VisitEvent(
            latE7 = (lat * 1e7).roundToLong(),
            lngE7 = (lng * 1e7).roundToLong(),
            arrivalMs = arrivalMs,
            departureMs = departureMs,
            accuracyM = horizontalAccuracy.roundToInt().coerceAtLeast(0),
            ongoing = ongoing,
        )
    }

    private fun CLLocation.toFix(): CapturedFix {
        val (lat, lng) = coordinate.useContents { latitude to longitude }
        return CapturedFix(
            latE7 = (lat * 1e7).roundToLong(),
            lngE7 = (lng * 1e7).roundToLong(),
            timestampMs = (timestamp.timeIntervalSince1970 * 1000).roundToLong(),
            accuracyM = horizontalAccuracy.takeIf { it >= 0 }?.roundToInt() ?: 0,
        )
    }

    private companion object {
        /** Anything this far past "now" is Apple's distant-future sentinel, not a real departure. */
        const val SENTINEL_SLACK_MS = 365L * 24 * 60 * 60 * 1000

    }
}

/**
 * ⚠⚠ **One entry per burst, not per task** (`ios-shared-store-sqlite.md` Appendix B.15 (210)).
 * Waits for a task, then, inside one [entry], runs it and every task queued behind it, and goes on
 * taking tasks that arrive within [idleMs] of the last; only then does the entry end. A
 * significant-change wake delivers its fixes in a burst of callbacks: an entry token per task
 * would be released after each one, and in the background, with nothing else protecting, every
 * such release is the last one and quiesces (the loops stopped, up to 1 s of waiting, the gates
 * closed and drained), so the burst would spend its ~10 s of background time on quiesces. Returns
 * when [work] is closed and drained.
 */
internal suspend fun drainInEntries(
    work: ReceiveChannel<suspend () -> Unit>,
    entry: suspend (drain: suspend () -> Unit) -> Unit,
    idleMs: Long = CAPTURE_ENTRY_IDLE_MS,
) {
    while (true) {
        val first = work.receiveCatching().getOrNull() ?: return
        entry {
            var next: (suspend () -> Unit)? = first
            while (next != null) {
                next()
                next = work.tryReceive().getOrNull() ?: nextWithin(work, idleMs)
            }
        }
    }
}

/**
 * ⚠⚠ The next task within [idleMs], or null. A `select` with `onTimeout`, never
 * `withTimeoutOrNull { receive() }`: by prompt cancellation, a receive cancelled by the timeout
 * after the element was already handed over throws, and the element is lost.
 * The select is atomic: the task is either taken (and then run) or left in the channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun nextWithin(work: ReceiveChannel<suspend () -> Unit>, idleMs: Long): (suspend () -> Unit)? =
    select {
        work.onReceiveCatching { it.getOrNull() }
        onTimeout(idleMs) { null }
    }

/** How long one capture entry waits for the next task of its burst before it ends (B.15 (210)). */
internal const val CAPTURE_ENTRY_IDLE_MS: Long = 500L

/** The process's one parked manager (B.16 (233), (238)). */
val parkedLocationMonitor: ParkedLocationMonitor<CLLocationManager> = ParkedLocationMonitor { manager ->
    manager.stopMonitoringVisits()
    manager.stopMonitoringSignificantLocationChanges()
    manager.delegate = null
}

/**
 * ⚠⚠ Stops and releases the manager a detached capture parked (B.16 (238)): at an opt-out, a
 * denial, or a graph given up with no rebuild to follow. On the main queue, as every manager call.
 */
fun stopParkedLocationMonitoring() {
    dispatch_async(dispatch_get_main_queue()) { parkedLocationMonitor.stopAndRelease() }
}
