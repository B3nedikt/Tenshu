package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToLong

/**
 * The journey the user is currently *on* — the open [MovementSegmenter] buffer, summarised.
 *
 * The movement twin of [OngoingVisit]. [startMs] is the first fix of the open leg, [lastFixMs] the
 * most recent one (which is how a journey is told from a *stalled* one — see
 * `TimelineProjectionRepository`'s staleness bound), and [distanceM] the raw-path length so far.
 *
 * ⚠ [classification] is **provisional**. The stored verdict is computed once, over the whole path,
 * when the leg flushes; this one is computed over however much of the path exists right now, so it
 * can (and does) change as the journey goes on. That is why the mode the feed may render is
 * [provisionalMode] rather than `classification.mode` — see its doc.
 */
data class OngoingMove(
    val startMs: Long,
    val lastFixMs: Long,
    val distanceM: Double,
    val classification: ModeClassification,
) {
    val durationMs: Long get() = (lastFixMs - startMs).coerceAtLeast(0)

    /** [distanceM] in whole metres, the unit every presentation surface uses. */
    val distanceMeters: Long get() = distanceM.roundToLong()

    /**
     * The mode the feed may **name**, or [MovementMode.UNKNOWN] while the classifier is still
     * guessing.
     *
     * An in-flight classification is a guess presented in a place the user reads as fact, so it is
     * gated on [MIN_NAMEABLE_CONFIDENCE] rather than shown as soon as a bucket is picked. Below the
     * gate the row still appears — with its live distance and duration — it simply says "Movement"
     * instead of naming a mode it cannot yet stand behind. An honest unknown that resolves itself
     * is a far cheaper failure than a confident "Train" that turns out to be a car.
     */
    val provisionalMode: String
        get() = if (classification.confidence >= MIN_NAMEABLE_CONFIDENCE) {
            classification.mode
        } else {
            MovementMode.UNKNOWN
        }

    companion object {
        /**
         * How sure [classifyMode] must be before an *in-flight* journey names its mode.
         *
         * Derived from the classifier's own arithmetic rather than picked: `confidence` is
         * `margin × sample × accuracy × 100`, and `sample` saturates at `CONFIDENT_LEG_COUNT`
         * (20) legs. 35 is therefore reached at roughly **ten legs** of unambiguous, well-fixed
         * movement (`0.5 × 0.8 × 0.87 ≈ 0.35`) — half the classifier's own confidence horizon,
         * which at Android's one-fix-per-minute cadence is about ten minutes in.
         *
         * ⚠ This gate makes an early verdict *silent*, not *right*: a train's promotion to
         * [MovementMode.TRANSIT] needs stop structure (`TransitKinematics`) that a partial path
         * rarely shows, so a long rail journey may read "Driving" for a while and settle to
         * "Train" only when the leg flushes and is classified whole. Delaying the first label is
         * the part we can fix cheaply; re-deriving transit shape mid-ride is not.
         */
        const val MIN_NAMEABLE_CONFIDENCE = 35
    }
}

/**
 * Device-local live-capture state: the journey the user is *currently on*, or `null`
 * (`docs/proposals/timeline-movement-capture.md` §12.6 — the deferred half this closes).
 *
 * The exact sibling of [OngoingVisitState], and deliberately shaped like it: a process-local
 * `MutableStateFlow` that **never crosses the wire**, published by the capture write loop and read
 * by the projector so the feed can show the ride you are on with a live-ticking duration.
 *
 * ⚠⚠ Unlike the ongoing *visit*, an ongoing move is **not** also a row. §9's emit-on-close decision
 * (Option A) keeps movement moments immutable — a `MovementTrace` is written once, when the leg
 * closes — so this state is the *only* representation of an in-flight journey, and the projector
 * renders it as a synthetic feed row that vanishes the instant the real one lands
 * (`TimelineProjection.days`). Nothing here is ever emitted, synced or reconciled.
 *
 * ### Mutual exclusion with [OngoingVisitState]
 * You are either dwelling or moving, never both. That invariant is enforced **by construction**
 * here rather than by every call site remembering it: [set] clears the visit state, and
 * [OngoingVisitState.set] clears this one. The capture loops still clear explicitly where they mean
 * *stop* (capture ended, the leg closed) rather than *switch*.
 */
object OngoingMoveState {
    private val _current = MutableStateFlow<OngoingMove?>(null)
    val current: StateFlow<OngoingMove?> = _current.asStateFlow()

    fun set(move: OngoingMove) {
        OngoingVisitState.clearMoveExclusive()
        _current.value = move
    }

    fun clear() {
        _current.value = null
    }

    /** Cleared because a dwell took over. Separate entry point so the two objects can't recurse. */
    internal fun clearVisitExclusive() {
        _current.value = null
    }

    /**
     * Republish the leg a **previous process** left open, from its durable [OpenMoveJournal]
     * (§12.6) — the app-open half of this feature.
     *
     * This state dies with the process, but the journal does not: an OEM force-kill mid-walk (or,
     * on iOS, the ordinary inter-event suspension) leaves a perfectly good open path on disk. Read
     * at launch, it makes the ride the user is *still on* visible immediately, instead of the feed
     * staying blank until the leg finally flushes.
     *
     * Guards, all failing closed:
     *  - the path must clear the ordinary recordability thresholds ([toCapturedMoveOrNull]) — the
     *    same bar a live leg passes before it surfaces, so recovery cannot show something capture
     *    itself would have discarded;
     *  - its last fix must be within [MovementDetectionConfig.maxResumeGapMs] of [nowMs] — the
     *    identical continuity rule [MovementSegmenter] applies when it decides whether to *resume*
     *    that path or flush it as a finished leg. Stale (or clock-stepped-backwards) ⇒ not a
     *    journey you are on;
     *  - live state always wins: if capture has already published something in this process,
     *    recovery leaves it alone rather than overwriting fresher truth with the journal's.
     *
     * ⚠ Deliberately does **not** clear or rewrite the journal. The segmenter owns that record; a
     * reader that consumed it would destroy the very durability §12.6 exists to provide.
     */
    fun restoreFrom(
        journal: OpenMoveJournal,
        nowMs: Long,
        config: MovementDetectionConfig = MovementDetectionConfig(),
    ): OngoingMove? {
        if (_current.value != null) return null
        val path = journal.read()
        val move = path.toCapturedMoveOrNull(config) ?: return null
        val sinceLastFix = nowMs - move.endMs
        if (sinceLastFix < 0 || sinceLastFix > config.maxResumeGapMs) return null
        val restored = move.toOngoing()
        set(restored)
        return restored
    }
}

/** The open-leg summary of a (possibly still-growing) [CapturedMove]. */
internal fun CapturedMove.toOngoing(): OngoingMove = OngoingMove(
    startMs = startMs,
    lastFixMs = endMs,
    distanceM = distanceM,
    classification = classification,
)
