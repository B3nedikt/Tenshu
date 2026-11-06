package app.tenshu.timeline.data.capture

/**
 * The **event-model movement session** (M4, `docs/proposals/timeline-movement-capture.md` §6): the
 * pure movement-side companion to the iOS capture model, where the *platform* is the dwell
 * authority (`CLVisit` delivers arrivals/departures itself) and this session turns the sparse
 * significant-location-change fix stream into [CaptureEvent.Move]s via the shared
 * [MovementSegmenter].
 *
 * Contrast with Android's pull model (`fixes().segment()`), where the segmenter detects *both*
 * dwells and moves from one dense fix stream. Here:
 *
 *  - [onFix] feeds a fix through the segmenter and surfaces its `Move` and `MoveProgress`
 *    emissions. Dwell emissions are dropped — the platform's visit events are the dwell authority,
 *    and they are structurally near-absent anyway (significant-change fixes arrive ≥ hundreds of
 *    metres apart, so they never cluster inside the dwell radius).
 *  - [onDwellBoundary] is called when the platform reports an arrival: the open move (the transit
 *    that led here) is flushed — terminated at the dwell's [endpoint] so the path reaches the
 *    place — and the segmenter is **replaced**, because [MovementSegmenter]'s flush is
 *    non-mutating (its `onEnd` keeps the buffer, which is fine for its terminal Flow use but would
 *    make a reused instance extend the already-flushed move into the next trip).
 *  - [onEnd] flushes the tail when capture stops (mirrors the Flow operator's completion).
 *
 * ⚠ Without a platform dwell event (rural, no visit detected) an open move closes only via the
 * segmenter's `maxOpenMoveMs` cap or [onEnd] — the same long-journey backstop as Android.
 *
 * ⚠ A bound [journal] matters *more* here than on Android, not less: iOS does not hold a capture
 * process at all — the monitors relaunch the app per event and it is suspended again in between, so
 * without a durable open path every inter-event process death silently discarded the journey so far.
 * The reset in [flushAndReset] is safe against it by construction: [MovementSegmenter.onEnd] clears
 * the journal before the replacement segmenter is built, so the replacement cannot re-recover the
 * leg it just flushed.
 *
 * Pure `commonMain` so the segmenter interplay is unit-testable without CoreLocation.
 */
class EventDrivenCaptureSession(
    private val visitConfig: VisitDetectionConfig = VisitDetectionConfig(),
    private val moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    private val journal: OpenMoveJournal = OpenMoveJournal.None,
) {
    private var segmenter = MovementSegmenter(visitConfig, moveConfig, journal)
    private var lastFixMs = Long.MIN_VALUE

    /**
     * Feed one platform fix; returns the movement segments it closed (usually none) **and** the
     * [CaptureEvent.MoveProgress] describing the leg still open, if any.
     *
     * ⚠ Returns the wider `CaptureEvent` rather than `Move`, because progress is not a move: the
     * caller must dispatch it to the live overlay and never to a writer. Dwell emissions stay
     * filtered out — the platform's `CLVisit` events remain the dwell authority.
     */
    fun onFix(fix: CapturedFix): List<CaptureEvent> {
        lastFixMs = maxOf(lastFixMs, fix.timestampMs)
        return segmenter.onFix(fix).filter { it !is CaptureEvent.Dwell }
    }

    /**
     * The platform reported a dwell at [endpoint] — flush the transit that led there and reset for
     * the next trip. The endpoint's timestamp is coerced forward to the latest seen fix so a
     * retroactive arrival date (visit events are delivered *after* the stay is confirmed) cannot
     * hand the segmenter an out-of-order tail.
     */
    fun onDwellBoundary(endpoint: CapturedFix?): List<CaptureEvent.Move> {
        endpoint?.let {
            segmenter.onFix(it.copy(timestampMs = maxOf(it.timestampMs, lastFixMs)))
        }
        return flushAndReset()
    }

    /** Capture stopped — flush the movement tail. */
    fun onEnd(): List<CaptureEvent.Move> = flushAndReset()

    private fun flushAndReset(): List<CaptureEvent.Move> {
        val flushed = segmenter.onEnd().moves()
        segmenter = MovementSegmenter(visitConfig, moveConfig, journal)
        return flushed
    }

    private fun List<CaptureEvent>.moves(): List<CaptureEvent.Move> =
        filterIsInstance<CaptureEvent.Move>()
}
