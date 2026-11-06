package app.tenshu.timeline.data.capture

import app.tenshu.timeline.data.geocode.PlaceLabelEnricher
import app.tenshu.timeline.data.wifi.WifiPlaceFingerprint
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.core.TContext
import app.tenshu.diagnostics.Diagnostics

/**
 * The **device-independent live-capture write loop** (I3b + movement capture M3,
 * `docs/proposals/timeline-movement-capture.md` §6): collects [CaptureEvent]s from a bound
 * [LocationCaptureSource] and dispatches each — a `Dwell` to [CapturedVisitWriter], a `Move` to
 * [CapturedMoveWriter] — persisting through the same shared [TimelineImportWriter] the bulk importers
 * use, so a captured dwell/move reads back through the projector identically. Idempotent
 * (content-addressed), so a re-emit folds instead of duplicating.
 *
 * Both writers share the one [CapturedVisitOutbox] — a `Dwell` timeline keys on its visit moment, a
 * `Move` timeline on its `MOVEMENT` moment, so entries never collide and either writer's drain
 * replays either kind (the payload is self-describing). The **pull model** (Android): while the
 * foreground service holds it, it forwards each event here; iOS's event model calls the writers
 * directly instead (§6). Pure `commonMain`; only the platform [LocationCaptureSource] is device code.
 *
 * Inert until [run] is collected, and [run] no-ops when the source is unavailable — the off-by-
 * default posture (§6): nothing captures until the platform starts the engine behind an explicit
 * opt-in (the Android foreground service, gated on the location grant).
 */
class LocationCaptureEngine(
    private val source: LocationCaptureSource,
    client: TenshuClient,
    context: TContext,
    private val visitConfig: VisitDetectionConfig = VisitDetectionConfig(),
    moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    enricher: PlaceLabelEnricher? = null,
    outbox: CapturedVisitOutbox? = null,
    /** Opt-in Wi-Fi place recognition (§4.5); `null` on platforms without a binding → grid-snap only. */
    private val wifi: WifiPlaceFingerprint? = null,
    /**
     * Called once a captured leg is a durable trace — the [RailRefinementTrigger] schedule (§32.3).
     *
     * ⚠ Must not suspend or block: this runs inside the capture write loop, and the sweep it starts
     * fetches map tiles. The trigger's own [RailRefinementTrigger.onTraceRecorded] is a `trySend` for
     * exactly that reason. Defaults to a no-op, so a platform that has not wired the sweep behaves
     * exactly as before.
     */
    private val onTraceRecorded: () -> Unit = {},
    /**
     * `ADR 0025` §Decision 8.6: while Control's device is known to have no realm (or the realm read
     * is pending), captured events are journalled to [outbox] but not written — the app-start drain
     * collector replays them when a realm appears. ⚠ Required, never defaulted.
     */
    private val writesHeld: () -> Boolean,
) {
    private val visitWriter =
        CapturedVisitWriter(client, context, visitConfig, enricher, outbox, writesHeld = this.writesHeld)
    private val moveWriter = CapturedMoveWriter(client, context, moveConfig, outbox, writesHeld = this.writesHeld)

    /**
     * Collect and persist captured events until the flow completes or the caller's coroutine is
     * cancelled (the foreground service owns the scope). Suspends for the lifetime of capture.
     */
    suspend fun run() {
        if (!source.isAvailable()) return
        try {
            source.events().collect { event ->
                when (event) {
                    is CaptureEvent.Dwell -> {
                        // Wi-Fi recognition (§4.5): offer the coordinate-derived placeId; if this
                        // network is known, the write folds onto the fingerprinted place instead.
                        // ⚠ ADR 0025 §8.6: resolving also LEARNS — a synced network-fingerprint
                        // write — so while the realm gate holds the dwell keeps its grid-snapped
                        // place and nothing is learned. The visit itself is journalled as usual.
                        val placeIdOverride = if (writesHeld()) null else wifi?.resolvePlaceId(
                            VisitDetector.placeIdOf(event.visit, visitConfig),
                            event.visit.latE7,
                            event.visit.lngE7,
                        )
                        visitWriter.write(event.visit, placeIdOverride)
                        Diagnostics.i(
                            "capture",
                            "dwell ${if (event.ongoing) "confirmed (ongoing)" else "closed"}",
                            code = if (event.ongoing) "dwell_ongoing" else "dwell_closed",
                        )
                        // A dwell of either flavour ends the journey that led here: the leg has been
                        // flushed (or was never recordable), so nothing is open any more.
                        OngoingMoveState.clear()
                        // Publish (or finalise) the device-local current-place state so the UI can tick
                        // the live duration without re-syncing the moment (§11.2).
                        if (event.ongoing) {
                            OngoingVisitState.set(
                                OngoingVisit(event.visit.latE7, event.visit.lngE7, event.visit.arrivalMs),
                            )
                        } else {
                            OngoingVisitState.clear()
                        }
                    }
                    is CaptureEvent.Move -> {
                        OngoingVisitState.clear() // moving → no longer at a place
                        // ⚠ Cleared *before* the write, not after: from here on the leg is a real
                        // row, and the live overlay must never be visible alongside it. Ordering it
                        // this way makes the no-duplicate guarantee independent of write latency.
                        OngoingMoveState.clear()
                        moveWriter.write(event.move)
                        Diagnostics.i(
                            "capture",
                            "move recorded: ${event.move.mode} ${event.move.distanceM.toInt()}m",
                            code = "move_recorded",
                        )
                        // A refinable trace now exists — the only event that creates work for the
                        // rail sweep, and therefore the honest time to schedule one (§32.3). ⚠ After
                        // the write, so the sweep cannot read the store before the leg is in it.
                        // ⚠ Not while the realm gate holds (ADR 0025 §8.6): the leg was journalled,
                        // not written, and the sweep's own writes are refused there anyway.
                        if (!writesHeld()) onTraceRecorded()
                    }
                    is CaptureEvent.MoveProgress -> {
                        // The one event that writes nothing: a device-local live overlay for the
                        // journey in progress (§12.6). Sets clear the visit state by construction.
                        OngoingMoveState.set(event.open)
                    }
                }
            }
        } finally {
            // Capture stopped / cancelled → neither dwelling nor moving.
            OngoingVisitState.clear()
            OngoingMoveState.clear()
        }
    }
}
