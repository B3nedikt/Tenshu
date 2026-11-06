package app.tenshu.timeline.data.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Tuning for the movement half of capture (`docs/proposals/timeline-movement-capture.md` §3.1).
 * Conservative first guesses — the honest values need real captured traces (§9 "tuning corpus").
 */
data class MovementDetectionConfig(
    /** A move must cover at least this far to be recorded — suppresses GPS wander (metres). */
    val minMoveDistanceM: Double = 250.0,
    /** A move must span at least this long to be recorded (millis). Default 3 min. */
    val minMoveDurationMs: Long = 3 * 60 * 1000L,
    /** An open (dwell-less) move is force-closed after this long so long journeys surface (millis). */
    val maxOpenMoveMs: Long = 2 * 60 * 60 * 1000L,
    /**
     * Cut a flushed leg where the travel mode changes, not only where the user stops (S2, §17.3).
     *
     * ⚠ A kill switch, not a tuning knob: `false` restores exactly the pre-S2 one-move-per-flush
     * behaviour, which is what makes every S2 test twinnable against the rule it replaced without a
     * test-only code path.
     */
    val splitOnModeChange: Boolean = true,
    /**
     * Consecutive voting legs a new mode must hold before a boundary is accepted (S2 hysteresis).
     *
     * **Reasoned, not fitted.** 3 legs is ~3 minutes at today's on-foot cadence and ~30 s at S1's
     * vehicle cadence — long enough that a train braking into a station, a bus in traffic or one
     * mis-measured fix cannot cut a journey, short enough that a real platform change still lands in
     * the right leg. ⚠ Lowering it toward 1 makes the splitter trust a single leg, and a single leg
     * is exactly what GPS noise produces.
     */
    val modeChangeHysteresisLegs: Int = 3,
    /**
     * Let a **pinned, blind anchor** open a boundary the speed vote cannot (§34.2, [ModeSplitter]).
     *
     * ⚠ A kill switch on the same terms as [splitOnModeChange], and for the same reason: `false`
     * restores the speed-only splitter exactly, so every pre-§34 test can be twinned against the rule
     * it replaced without a test-only code path. It is subordinate to [splitOnModeChange] — with
     * splitting off there are no boundaries for either voter to open.
     */
    val splitOnPinnedAnchor: Boolean = true,
    /**
     * Accuracy (metres) above which a fix is **presence evidence but not route geometry** (§22).
     *
     * **Reasoned, not fitted — and the distinction matters here.** The median step between fixes is
     * ~800 m (§21.4), so a fix uncertain by 200 m already carries a quarter of the leg it sits in;
     * past that its displacement off the true path dominates the leg, and as a route vertex it adds
     * a there-and-back excursion rather than travel. ⚠ On the reported journey a 300 m bar happened
     * to land almost exactly on the true rail distance (85.1 km vs ≈85 km) — **one journey cannot
     * fit a constant**, so the tighter, reasoned bar is kept and the coincidence is recorded rather
     * than adopted.
     *
     * ⚠⚠ This never removes a fix from **dwell** clustering (§11.1: indoors, coarse NETWORK fixes are
     * all there is, and listening on both providers is what makes an indoor place record at all).
     */
    val maxGeometryAccuracyM: Int = 200,
    /** Decimation cap for the stored polyline (§4). */
    val maxPolylinePoints: Int = 500,
    /** Douglas–Peucker tolerance for polyline simplification (metres, §4). */
    val simplifyEpsilonM: Double = 15.0,
    /**
     * Floor on a move's **extent** — how far it actually got from where it started (metres, S6b,
     * `timeline-movement-capture.md` §18.3/§18.7). See [toCapturedMoveOrNull].
     *
     * **Reasoned, not fitted.** It is bounded above by the shortest trip [minMoveDistanceM] already
     * admits: 250 m of path in the degenerate perfect out-and-back reaches only 125 m of extent, so
     * anything at or above that would start deleting trips the *path* gate accepted. 100 m keeps
     * margin under it while still being a real bar. The indoor jitter case is not this constant's
     * job — [extentAccuracyFactor] handles it, because a jitter cloud's extent scales with the
     * fixes' accuracy and not with any fixed number.
     */
    val minExtentM: Double = 100.0,
    /**
     * Multiple of a path's **mean reported accuracy** its extent must clear (S6b).
     *
     * **Reasoned, not fitted.** `accuracyM` is a ~68 % confidence radius, so two fixes on opposite
     * sides of one stationary point are routinely ~2× that apart with neither having moved. A bar of
     * 1× would therefore admit an ordinary ±100 m indoor cloud; 2× says *you must have got further
     * from the start than two fixes' worth of error*, which is the weakest claim that still means
     * "moved". ⚠ The honest consequence: at ±100 m indoors we refuse to record a real 150 m walk.
     * That is not information lost — at that accuracy the fixes never distinguished it from sitting
     * still — and the alternative is §18.3's phantom kilometre.
     */
    val extentAccuracyFactor: Double = 2.0,
    /**
     * How long a gap in the fix stream may be and still count as the **same journey** when an
     * [OpenMoveJournal]-recovered path is picked up after a process death (millis). Default 15 min.
     *
     * Sized against the Android capture watchdog's recovery interval (§11.4, ~15 min): that is the
     * realistic worst case for kill→restart, so anything inside it plausibly *is* the leg we were
     * on, and stitching preserves one whole journey. Beyond it we cannot honestly claim continuity
     * — the straight line across the gap would inflate `distanceM` and inject a synthetic high-speed
     * leg into [classifyMode] — so the recovered path is flushed as its own completed leg instead.
     */
    val maxResumeGapMs: Long = 15 * 60 * 1000L,
)

/**
 * Checkpoint the open path every N fixes. **3 is the recordability floor**: [toCapturedMoveOrNull]
 * needs ≥3 fixes for a path to be a move at all, so at this stride the journal is never more than
 * one *unrecordable* fragment behind the truth. Writing per fix would cost a file write per sensor
 * sample (~1/min on Android) to buy back a fragment that could never have been recorded anyway.
 * The time arm ([MovementDetectionConfig.minMoveDurationMs]) covers the sparse-fix platforms — on
 * iOS significant-change fixes can be far apart, and a count-only rule would leave the journal
 * hours stale there.
 */
private const val CHECKPOINT_FIX_STRIDE = 3

/**
 * The **online segmenter** (`timeline-movement-capture.md` §3): one pass over the fix stream that
 * emits *both* [CaptureEvent.Dwell]s (unchanged from I3's [detectVisits]) and [CaptureEvent.Move]s —
 * the path between dwells. Movement is the negative space of dwell detection: the fixes that never
 * form a ≥`minDwellMs` cluster, plus the transit leading up to each dwell.
 *
 * State machine (pure, testable without a `Flow` — [onFix]/[onEnd] return the events a step triggers).
 * It uses the same anchor-radius clustering as [VisitDetector.detect]; unlike the batch detector it
 * emits a dwell **twice** — once on *confirmation* (`ongoing = true`, the moment the stay crosses
 * `minDwellMs` while still present, §11.1) and once on *departure* (`ongoing = false`, final duration) —
 * both the same content-addressed visit, so they fold to one place (the batch detector's single dwell
 * equals the departure emit). It additionally accumulates a `moveBuffer` of the fixes since the last
 * dwell's departure and flushes it as a [CaptureEvent.Move] when:
 *  - a dwell is confirmed (flush the transit that led up to it, then reset the buffer at the dwell's
 *    departure point),
 *  - the buffer spans past [MovementDetectionConfig.maxOpenMoveMs] (a long journey with no dwell),
 *  - capture stops ([onEnd] flushes the tail).
 *
 * A sub-threshold pause (a traffic light, a shop window) never forms a dwell, so its fixes fold into
 * the buffer and the move stays whole — this is what makes a continuous walk one move, not dozens.
 * A flushed move is dropped unless it clears the §3 thresholds ([toCapturedMoveOrNull]).
 *
 * Alongside those it emits a [CaptureEvent.MoveProgress] on every fix for which the *open* buffer
 * would already be recordable — the movement counterpart of the dwell's `ongoing = true`
 * confirmation. It is a read-only view of state that is going to be flushed anyway: nothing is
 * written, nothing is content-addressed, and a consumer that ignores it loses nothing.
 *
 * ### Surviving process death (the [OpenMoveJournal])
 * Because a leg only becomes durable *at* a flush, an in-progress journey used to live nowhere but
 * this object's heap — so an OEM force-kill mid-walk destroyed it permanently (§12.0: capture loss
 * is not re-runnable). With a journal bound, the open path is checkpointed as it grows
 * ([CHECKPOINT_FIX_STRIDE]) and the *next* segmenter picks it up on its first fix:
 *  - within [MovementDetectionConfig.maxResumeGapMs] → the path is prepended to the buffer and the
 *    journey **continues as one leg**;
 *  - beyond it → the recovered path is **flushed as its own leg** (subject to the ordinary
 *    thresholds) and the new leg starts clean, so a long outage never fabricates a straight-line
 *    jump across it.
 *
 * Recovery is idempotent: moves are content-addressed by `startMs`, so re-emitting a leg that did in
 * fact get written before the kill folds onto the same rows. What a kill can still cost is a
 * **bounded tail** — at most [CHECKPOINT_FIX_STRIDE] − 1 fixes, less than one recordable fragment —
 * never the journey. ⚠ Residual window: a flush rewrites the
 * journal to the *new* open path in the same call that returns the event, so a kill in the few
 * microseconds between that return and `CapturedMoveWriter`'s outbox persist still loses that one
 * leg. That window is the same one the dwell path has always had, and is ~9 orders of magnitude
 * smaller than the hours-long one this closes.
 *
 * The journal is injected, defaulting to [OpenMoveJournal.None] — pure algorithm tests, fixtures and
 * platforms without a store binding behave exactly as before.
 *
 * **Assumes fixes arrive in time order** (live sensors do), like [detectVisits].
 */
class MovementSegmenter(
    private val visitConfig: VisitDetectionConfig = VisitDetectionConfig(),
    private val moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    private val journal: OpenMoveJournal = OpenMoveJournal.None,
    /**
     * The **open dwell**'s durable twin (§29). Defaults to [OpenMoveJournal.None], i.e. today's
     * in-memory-only behaviour.
     *
     * ⚠⚠ Without it a stay does not survive process death, and the failure is not merely a lost
     * fragment: a dwell is emitted on *confirmation* with a ~`minDwellMs` duration and corrected on
     * *departure*, so a process killed in between leaves a row **stuck at five minutes** — and the
     * next process, starting with no anchor, mints a fresh `cap-visit:<place>:<arrival>` id and
     * therefore a **second row for the same stay**. Measured on a real device: eight `dwell_ongoing`
     * events against one `dwell_closed` across a day of OEM kills.
     *
     * ⚠ The open *move* has had this since M3 ([journal]); the dwell never did. That asymmetry is the
     * whole bug — the two halves of the same state machine had different durability.
     */
    private val dwellJournal: OpenMoveJournal = OpenMoveJournal.None,
) {
    private var anchor: CapturedFix? = null
    private val cluster = ArrayList<CapturedFix>()
    private val moveBuffer = ArrayList<CapturedFix>()
    private var currentDwellEmitted = false

    /**
     * The tail of the dwell we most recently left (§27) — the *departure* platform of a metro hop.
     *
     * ⚠ Kept because [flushMove] clears `moveBuffer` and [closeCluster] drops the cluster, so by the
     * time a move is judged the evidence on the far side of it is already gone. Bounded to
     * [DWELL_TAIL_FIXES]: an overnight dwell is thousands of stationary fixes and none of them past
     * the last few say anything about the departure.
     */
    private var previousDwellTail: List<CapturedFix> = emptyList()


    /**
     * The path a previous (killed) segmenter left behind, applied by [resumeOrFlush] on the first
     * fix — not at construction, because the resume-or-flush verdict needs a *real* timestamp to
     * measure the gap against and this class deliberately has no clock.
     */
    private var pendingRecovery: List<CapturedFix>? = journal.read().takeIf { it.isNotEmpty() }

    /**
     * The cluster a previous (killed) segmenter left open, applied by [resumeDwellIfSameStay] on the
     * first fix. Held rather than restored eagerly because whether it *is* still the same stay is a
     * question only the next fix can answer.
     */
    private var pendingDwell: List<CapturedFix>? = dwellJournal.read().takeIf { it.isNotEmpty() }
    private var fixesSinceCheckpoint = 0
    private var lastCheckpointMs = Long.MIN_VALUE
    private var checkpointDue = false

    /** Feed one fix; returns the events it triggers, in emit order. */
    fun onFix(fix: CapturedFix): List<CaptureEvent> {
        resumeDwellIfSameStay(fix)
        val events = ArrayList<CaptureEvent>(resumeOrFlush(fix.timestampMs))
        events += step(fix)
        noteFix(fix)
        // Last, deliberately: a flush in this same call empties the buffer, so the progress event
        // describes what is open *after* everything else has been handed out. Any consumer that
        // clears its live state on a Move/Dwell therefore cannot wipe a still-valid progress.
        openMoveProgress()?.let { events += it }
        return events
    }

    /**
     * The open leg as it stands, iff it would already be recordable ([toCapturedMoveOrNull]) —
     * otherwise `null` (there is nothing to show yet).
     *
     * Reusing the flush-time builder is the point: the bar for *surfacing* an in-flight journey is
     * exactly the bar for recording one, so the feed can never advertise a ride that capture would
     * then discard as noise. It is also monotone — distance and span only grow — so a leg that has
     * surfaced stays surfaced until the buffer resets, and the row cannot flicker.
     *
     * ⚠ Emitted per fix (~1/min on Android), and each emission re-runs [classifyMode] over the open
     * path. That is O(n) per fix over a leg capped at [MovementDetectionConfig.maxOpenMoveMs] — a
     * few thousand great-circle terms across a two-hour journey, against no I/O and no row.
     */
    private fun openMoveProgress(): CaptureEvent.MoveProgress? =
        openPath().toCapturedMoveOrNull(moveConfig)?.let { CaptureEvent.MoveProgress(it.toOngoing()) }

    private fun step(fix: CapturedFix): List<CaptureEvent> {
        val a = anchor
        return when {
            a == null -> {
                anchor = fix; cluster.clear(); cluster += fix; currentDwellEmitted = false
                emptyList()
            }
            visitConfig.holdsInCluster(a, fix) -> {
                cluster += fix
                dwellJournal.checkpoint(cluster)
                maybeEmitConfirmedDwell()
            }
            else -> {
                // Left the anchor radius → close the run, then start a fresh one here.
                val events = closeCluster()
                anchor = fix; cluster.clear(); cluster += fix; currentDwellEmitted = false
                events
            }
        }
    }

    /**
     * Apply a journalled path from a killed capture process, once, ahead of everything else.
     * [nextFixMs] is the fix about to be processed, or `null` at [onEnd] (nothing more is coming, so
     * there is nothing to be continuous *with* — restore and let the tail flush carry it out).
     *
     * A negative gap (a clock step back, an out-of-order delivery) is treated as non-continuable for
     * the same reason an over-long one is: the resulting path would not be time-ordered, which every
     * downstream computation here assumes.
     */
    /**
     * Continue the dwell a killed process left open, when this fix says it is still the same stay.
     *
     * ⚠⚠ **Resuming the cluster is what makes one stay one row.** A visit is content-addressed by
     * `cap-visit:<placeId>:<arrivalMs>`, so keeping the original cluster keeps the original arrival,
     * and the re-emitted dwell folds onto the existing row *and corrects its duration* — which is the
     * other half of the bug, since a confirmation-only emit leaves the stay reading five minutes.
     *
     * ⚠ Two conditions, both necessary. The gap must be short enough that we can still claim
     * continuity ([MovementDetectionConfig.maxResumeGapMs], the rule the open *move* already uses),
     * and the new fix must still hold in the recovered cluster's own anchor — otherwise we would be
     * asserting the user never left a place they have in fact left, which is a worse error than an
     * extra row.
     */
    private fun resumeDwellIfSameStay(fix: CapturedFix) {
        val recovered = pendingDwell ?: return
        pendingDwell = null
        val anchorFix = recovered.firstOrNull() ?: return
        val gap = fix.timestampMs - recovered.last().timestampMs
        if (gap < 0 || gap > moveConfig.maxResumeGapMs) {
            dwellJournal.clear()
            return
        }
        if (!visitConfig.holdsInCluster(anchorFix, fix)) {
            dwellJournal.clear()
            return
        }
        anchor = anchorFix
        cluster.clear()
        cluster += recovered
        // ⚠ Deliberately left `false`: the resumed cluster re-emits its confirmation, which is the
        // same content-addressed visit with a *longer* duration. That re-emit is the repair.
        currentDwellEmitted = false
    }

    private fun resumeOrFlush(nextFixMs: Long?): List<CaptureEvent> {
        val recovered = pendingRecovery ?: return emptyList()
        pendingRecovery = null
        checkpointDue = true
        val gap = nextFixMs?.minus(recovered.last().timestampMs)
        if (gap != null && (gap < 0 || gap > moveConfig.maxResumeGapMs)) {
            return recovered.toCapturedMoveOrNull(moveConfig)
                ?.let { listOf(CaptureEvent.Move(it)) }
                ?: emptyList()
        }
        // Recovery is consumed on the first fix (or at onEnd), so the buffer is still empty here.
        moveBuffer.addAll(0, recovered)
        return emptyList()
    }

    /**
     * Persist the open path if the stride, the time arm, or a buffer-changing flush says so. Runs
     * *after* the fix has been folded in, so what lands is the settled state — never a half-step.
     */
    private fun noteFix(fix: CapturedFix) {
        fixesSinceCheckpoint++
        val sinceCheckpointMs =
            if (lastCheckpointMs == Long.MIN_VALUE) Long.MAX_VALUE else fix.timestampMs - lastCheckpointMs
        if (checkpointDue ||
            fixesSinceCheckpoint >= CHECKPOINT_FIX_STRIDE ||
            sinceCheckpointMs >= moveConfig.minMoveDurationMs
        ) {
            journal.checkpoint(openPath())
            fixesSinceCheckpoint = 0
            lastCheckpointMs = fix.timestampMs
            checkpointDue = false
        }
    }

    /**
     * Everything not yet handed out as an event: the movement buffer, plus the open cluster when it
     * is still *undecided* (it may yet fold into the move). Once the cluster has been emitted as a
     * confirmed dwell it is the visit path's business — and the buffer is empty at that point — so
     * the journal correctly holds nothing while the user is sitting somewhere.
     *
     * ⚠ **Raw fixes, never the decimated polyline** — `distanceM` and [classifyMode] are computed
     * over the raw path (§4), so a resumed buffer must restore exactly what was captured or the
     * recovered leg would be classified over a simplified geometry.
     */
    private fun openPath(): List<CapturedFix> =
        if (currentDwellEmitted) moveBuffer.toList() else moveBuffer + cluster

    /**
     * Emit the dwell the moment the open cluster first crosses [VisitDetectionConfig.minDwellMs] —
     * while the user is *still there* — so a place they haven't left yet is recorded. The streaming
     * detector otherwise only emitted on departure, so **sitting somewhere recorded nothing until you
     * left or stopped capture** (the on-device finding, §11.1). Fires once (`ongoing = true`);
     * [closeCluster] re-emits on departure with the final duration (`ongoing = false`), and
     * content-addressing `(place, arrival)` folds both to one place row. The live duration between the
     * two emits is computed in the UI from [OngoingVisitState] — not re-synced (§11.2).
     */
    private fun maybeEmitConfirmedDwell(): List<CaptureEvent> {
        if (currentDwellEmitted) return emptyList()
        if (cluster.size < 2 ||
            cluster.last().timestampMs - cluster.first().timestampMs < visitConfig.minDwellMs
        ) {
            return emptyList()
        }
        val events = ArrayList<CaptureEvent>()
        events += flushMove(endpoint = cluster.first()) // the transit that led here
        moveBuffer.clear()
        checkpointDue = true
        currentDwellEmitted = true
        events += CaptureEvent.Dwell(cluster.toCentroidVisit(), ongoing = true)
        return events
    }

    /**
     * Stream ended (capture stopped): close the open cluster, then flush the movement tail — and
     * **clear the journal**, because everything durable-worthy has now been handed to the caller.
     *
     * ⚠ That clear is exactly why a *deliberate* stop and a *kill* diverge correctly: `onEnd` runs on
     * a completed flow (`segment()`) and on `EventDrivenCaptureSession`'s reset, but a cancelled
     * collector or a SIGKILL never reaches it — so the journal survives precisely the cases that
     * need recovery. Any path recovered but not yet applied is restored first, so a capture that
     * stops before its first fix still flushes the leg rather than erasing it.
     */
    fun onEnd(): List<CaptureEvent> {
        val events = ArrayList<CaptureEvent>(resumeOrFlush(nextFixMs = null))
        events += closeCluster()
        events += flushMove(endpoint = null)
        journal.clear()
        return events
    }

    private fun closeCluster(): List<CaptureEvent> {
        if (cluster.isEmpty()) return emptyList()
        val isDwell = cluster.size >= 2 &&
            cluster.last().timestampMs - cluster.first().timestampMs >= visitConfig.minDwellMs
        if (!isDwell) {
            // The run never became a dwell → it was movement; fold it in. Deliberately *not* a
            // forced checkpoint: while moving, every single fix closes a cluster this way, so
            // forcing here would silently collapse the cadence back to one write per fix.
            moveBuffer += cluster
            return maybeCapOpenMove()
        }
        // A confirmed dwell. If it was already emitted mid-cluster (maybeEmitConfirmedDwell), the
        // transit is flushed and this is just the final departure emit (real duration, folds to the
        // same place); otherwise it crossed minDwell exactly at close, so flush the transit now.
        val events = ArrayList<CaptureEvent>()
        if (!currentDwellEmitted) {
            events += flushMove(endpoint = cluster.first())
        }
        events += CaptureEvent.Dwell(cluster.toCentroidVisit(), ongoing = false)
        // The stay is over: its record must not outlive it, or the next process would resume a dwell
        // the user has already left.
        dwellJournal.clear()
        // §27: keep the tail of the platform we are leaving — the next move is judged across it.
        // Captured here, after the flush above, so this dwell cannot vouch for the leg that *arrived*
        // at it; it is evidence for the leg that departs.
        previousDwellTail = cluster.takeLast(DWELL_TAIL_FIXES)
        // New movement begins from the dwell's departure (its last in-radius fix).
        moveBuffer.clear()
        moveBuffer += cluster.last()
        checkpointDue = true
        return events
    }

    /** Force-close the open move once it outruns the cap, keeping its tail as the next start. */
    private fun maybeCapOpenMove(): List<CaptureEvent> {
        if (moveBuffer.size < 2) return emptyList()
        val span = moveBuffer.last().timestampMs - moveBuffer.first().timestampMs
        if (span < moveConfig.maxOpenMoveMs) return emptyList()
        val tail = moveBuffer.last()
        val moves = flushMove(endpoint = null)
        moveBuffer.clear()
        moveBuffer += tail // continuity: the next leg starts where this one ended
        checkpointDue = true
        return moves
    }

    /**
     * Build the move(s) the buffer (+ optional endpoint) yields, in time order. Non-mutating.
     *
     * ⚠ **Plural since S2** (§17.3): the buffer is cut into runs of one travel mode by [ModeSplitter]
     * before each run goes through the ordinary [toCapturedMoveOrNull] gate. A path with no mode
     * change yields exactly one run, so this is byte-identical to the pre-S2 single-move path in
     * every case that used to produce a move — the split is additive, never a re-shaping of the
     * simple case.
     *
     * ⚠⚠ **…and re-joined by [ModeCoalescer] since S9** (§32.2). [ModeSplitter] cuts on [LegMode]'s
     * raw speed bands, but the verdict a row actually carries comes from [classifyMode] +
     * [promoteIfUnderground] *afterwards* — so the splitter can cut on a boundary those tiers then
     * overrule, and nothing used to put the journey back together. That is one ride rendered as two
     * consecutive `Transit` rows, which no read-time rule can merge (see [ModeCoalescer]'s KDoc).
     */
    private fun flushMove(endpoint: CapturedFix?): List<CaptureEvent.Move> {
        val path = ArrayList(moveBuffer)
        if (endpoint != null && (path.isEmpty() || path.last() != endpoint)) path += endpoint
        // §27: the dwell → move → dwell evidence window. `endpoint` is the arrival platform's first
        // fix and `previousDwellTail` the departure platform's last few, so this is the whole hop —
        // which is the only span on which an underground ride is visible at all.
        // ⚠ Computed once, over the WHOLE path: it is the evidence window the hop sits in, not a
        // property of how the path was cut, so every candidate run is judged against the same span.
        val span = previousDwellTail + path
        val runs = ModeSplitter.split(path, moveConfig)
        return ModeCoalescer
            .coalesce(runs) { candidate ->
                candidate.toCapturedMoveOrNull(moveConfig)?.let { promoteIfUnderground(it, span) }
            }
            .map { CaptureEvent.Move(it) }
    }

    /**
     * Promote a leg whose middle is blind to [MovementMode.TRANSIT] (§27).
     *
     * ⚠ Only ever a *promotion*, and only from a non-transit verdict — the same posture every other
     * tier here takes. A false "Train" on a commute is a confident lie; a missed one just leaves the
     * speed-based label, which is what shipped before this existed.
     *
     * ⚠⚠ The stamped [MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND] is what lets `RailRefinementSweep`
     * revisit the verdict with map tiles later and **demote** it if the endpoints do not sit on
     * subway alignment (the M9-3 rule that the tile tier is the one allowed to demote). Without the
     * stamp the promotion would be indistinguishable from a speed verdict and could never be undone.
     */
    private fun promoteIfUnderground(move: CapturedMove, span: List<CapturedFix>): CapturedMove {
        if (move.classification.mode == MovementMode.TRANSIT) return move
        if (!UndergroundHop.promotes(move.fixes, span)) return move
        return move.copy(
            classification = move.classification.copy(
                mode = MovementMode.TRANSIT,
                version = MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND,
            ),
        )
    }

    private companion object {
        /** How much of the departure dwell is kept as evidence. */
        const val DWELL_TAIL_FIXES = 40
    }
}

/**
 * Stream operator over [MovementSegmenter] — the Android capture source composes this as
 * `fixes().segment()` (M3), keeping the platform layer a thin fix emitter and the segmentation pure.
 *
 * With a [journal] bound, an open journey survives the foreground service being force-killed: the
 * cold flow builds a fresh segmenter on each collection, which recovers whatever the killed one left
 * behind. Note that `onEnd` — and therefore the journal clear — is reached only when the *source*
 * completes; a cancelled collector (the OEM kill path) skips it by construction.
 */
fun Flow<CapturedFix>.segment(
    visitConfig: VisitDetectionConfig = VisitDetectionConfig(),
    moveConfig: MovementDetectionConfig = MovementDetectionConfig(),
    journal: OpenMoveJournal = OpenMoveJournal.None,
    /** The open dwell's durable record (§29) — see [MovementSegmenter]'s `dwellJournal`. */
    dwellJournal: OpenMoveJournal = OpenMoveJournal.None,
): Flow<CaptureEvent> = flow {
    val segmenter = MovementSegmenter(visitConfig, moveConfig, journal, dwellJournal)
    collect { fix -> segmenter.onFix(fix).forEach { emit(it) } }
    segmenter.onEnd().forEach { emit(it) }
}

/**
 * Shape an ordered path into a [CapturedMove] iff it clears the §3 thresholds (min distance, min
 * duration, ≥3 fixes) **and the S6b extent gate** — otherwise `null` (it was noise, not a trip).
 * Computes distance + mode over the raw path so both stay accurate independent of later decimation
 * (§4).
 *
 * ### The extent gate (S6b, §18.3)
 * [pathDistanceMeters] sums **raw consecutive legs**, so indoor jitter accumulates monotonically:
 * ten ±100 m NETWORK fixes a minute apart is on the order of **a kilometre of "path" from a phone
 * that never moved** — comfortably past [MovementDetectionConfig.minMoveDistanceM], and written as a
 * real `Walk`. That phantom is not merely a spurious row: at `ABSORB_MOVE_MAX_M = 300` it is
 * *precisely* what vetoes the read-time merge that would have rejoined the dwell fragments the same
 * noise produced. One noise source, three symptoms.
 *
 * So a path must also have **got somewhere**: its [pathExtentMeters] must exceed
 * `max(minExtentM, extentAccuracyFactor × mean reported accuracy)`.
 *
 * ⚠⚠ **Extent, not net displacement** — a real out-and-back walk finishes where it started, so a
 * displacement gate would delete genuine trips. "Large path, small extent" is the jitter signature.
 *
 * ⚠ This is deliberately **not** "raise `ABSORB_MOVE_MAX_M`" (§18.7): that constant would have to
 * grow past a real 300 m walk to suppress the phantom, and would then start swallowing genuine short
 * trips at read time. Kill it at the source and the read-time constant stays honest.
 */
/**
 * The extent a path must exceed to count as travel rather than jitter (S6b).
 *
 * ⚠ Extracted rather than inlined at its one call site because the **replay** applies the same bar
 * to already-stored traces (`SegmentationReplay`, §18.9), and a bar computed in two places is a bar
 * that drifts — at which point the replay would quietly answer a question about a rule that is not
 * the one shipping.
 */
internal fun MovementDetectionConfig.extentFloorFor(path: List<CapturedFix>): Double =
    maxOf(minExtentM, extentAccuracyFactor * path.meanReportedAccuracyM())

internal fun List<CapturedFix>.toCapturedMoveOrNull(config: MovementDetectionConfig): CapturedMove? {
    if (size < 3) return null
    // §22 hygiene, in this order and for different reasons: time order because the two providers
    // interleave by arrival (⚠ DROP, never sort — sorting measured worse), then geometry credibility
    // because a coarse fix is displaced OFF the path and injects a there-and-back excursion.
    // ⚠ Both apply to the MOVE path only. Dwell clustering keeps every fix (§11.1).
    val ordered = FixHygiene.inTimeOrder(this)
    val path = FixHygiene.geometryPath(ordered, config.maxGeometryAccuracyM)
    if (path.size < 3) return null
    val span = path.last().timestampMs - path.first().timestampMs
    if (span < config.minMoveDurationMs) return null
    val distance = path.pathDistanceMeters()
    if (distance < config.minMoveDistanceM) return null
    if (path.pathExtentMeters() <= config.extentFloorFor(path)) return null
    return CapturedMove(
        fixes = path,
        startMs = path.first().timestampMs,
        endMs = path.last().timestampMs,
        distanceM = distance,
        classification = classifyMode(path),
    )
}

/** Bucket boundaries in m/s: walk | run | cycle | drive. Shared by the verdict and the margin. */
private val MODE_BOUNDARIES = doubleArrayOf(2.5, 4.0, 8.0)

/**
 * De-Googled travel-mode heuristic (§5): the **time-weighted** median of the per-leg speeds
 * (metres/second), bucketed. Median (not max) resists GPS spikes; weighting by each leg's duration
 * (§37) makes the statistic a fact about the journey rather than about how often the location
 * providers happened to deliver — see [timeWeightedMedianOrNull] for the measurement that forced it.
 * Fewer than two legs, or a degenerate span, → [MovementMode.UNKNOWN].
 *
 * Returns a [ModeClassification], **not a bare string** (§12.1 / D-F2): the median speed, a 0–100
 * confidence, and the classifier [MOVEMENT_CLASSIFIER_VERSION] are persisted alongside the verdict so
 * a later, better rule (the §13 transit tiers) can re-label old traces instead of being stuck with
 * an un-attributable label. Discarding the evidence is irreversible; the trace's fixes are not kept.
 *
 * **Confidence** combines three signals, each in 0..1, multiplied:
 *  - *margin* — how far the median sits from the nearest bucket boundary, relative to that bucket's
 *    width. A median of 2.49 m/s ("walk", one hair from "run") scores near 0; mid-bucket scores 1.
 *    ⚠ Margin measures distance from a *boundary*, not agreement among the legs, so it cannot see
 *    the bimodal case §37 describes — a median in an empty valley is mid-bucket and scores high on
 *    this term. Low confidence there came from the accuracy term instead, by luck, not by design.
 *  - *sample* — leg count, saturating at [CONFIDENT_LEG_COUNT]. Three legs is a guess; twenty is not.
 *  - *accuracy* — mean horizontal accuracy of the fixes. Coarse network fixes (±100 m) inflate the
 *    apparent per-leg distance and therefore the speed, so they must discount the verdict. Fixes
 *    reporting no accuracy (0) are treated as neutral rather than perfect.
 *
 * **Transit refinement (T0, §13.3).** A `drive` verdict is then offered to
 * [refineWithTransitKinematics], which may promote it to [MovementMode.TRANSIT] on the *shape* of the
 * movement (turn density + station-like stop spacing, or a rail-only cruise speed). That tier only
 * ever promotes `drive`; every other bucket, and every ambiguous case, is returned untouched.
 *
 * ATA-based refinement is the deferred, opt-in enhancement behind this same seam; §13's remaining
 * map-matching tiers slot in here too, bumping [MOVEMENT_CLASSIFIER_VERSION] as they land.
 */
fun classifyMode(fixes: List<CapturedFix>): ModeClassification {
    val legs = LegMode.legsOf(fixes)
    val speeds = legs.map { it.speedMps }
    if (speeds.isEmpty()) {
        return ModeClassification(mode = MovementMode.UNKNOWN, confidence = 0, medianSpeedMmPerS = 0)
    }
    // §37: weighted by each leg's duration, so the answer is about the journey and not about how
    // often the providers happened to deliver. See [timeWeightedMedianOrNull].
    val median = timeWeightedMedianOrNull(legs.map { it.speedMps to it.durationMs }) ?: 0.0
    // S3 (§17.4): cadence decides pedestrian-vs-wheeled, speed decides within each — through the
    // SAME LegMode rule the splitter cuts on, so a journey can never be cut on a boundary the
    // whole-leg classifier then disagrees with.
    // ⚠ With no pedometer this is byte-identical to the pre-S3 speed-only bands (LegMode.speedOnly
    // holds MODE_BOUNDARIES verbatim), so a device without the sensor loses nothing.
    // ⚠ A median below LegMode's voting floor yields null; fall back to the speed bands rather than
    // UNKNOWN — a very slow wander was a `walk` before S3 and must stay one.
    // ⚠ Weighted on the same basis as the speed above, and that is load-bearing rather than tidy:
    // `withoutImplausibleRun` divides the one by the other to get a stride, and a stride computed
    // from two medians over differently-weighted populations is not a length.
    val medianCadence =
        timeWeightedMedianOrNull(legs.mapNotNull { leg -> leg.cadenceSpm?.let { it to leg.durationMs } })
    val voted = LegMode.of(median, medianCadence) ?: LegMode.speedOnly(median)
    // §35.8: withdraw a `run` whose implied stride no human has. See [LegMode.withoutImplausibleRun].
    val mode = LegMode.withoutImplausibleRun(voted, median, medianCadence)
    val baseline = ModeClassification(
        mode = mode,
        confidence = confidenceOf(median, speeds.size, fixes),
        medianSpeedMmPerS = (median * 1000.0).roundToLong(),
    )
    // T0 (§13.3): a DRIVE baseline may be a train. Promotion only, never demotion — see
    // TransitKinematics' KDoc for why the bias runs that way.
    return refineWithTransitKinematics(fixes, baseline)
}

/** Median of a sample, or `null` when empty. Shared so speed and cadence are reduced identically. */
private fun medianOrNull(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * The median of `(value, weightMs)` samples, each observation counting for the **time it covers**.
 *
 * ### ⚠⚠ Why the plain median measured the sampling and not the journey (§37)
 *
 * One leg was one vote regardless of the span it stood for, and on a real device that span varies by
 * more than an order of magnitude — [LegMode.MIN_LEG_MS] is only a floor, and a Doze-starved or
 * batched stretch delivers legs minutes long beside others of seconds. So the "median leg speed" was
 * really a median over *provider delivery events*. That is not the quantity `MODE_BOUNDARIES` makes
 * claims about: "a cyclist does 4–8 m/s" is a statement about time spent travelling.
 *
 * ⚠⚠ **The failure is worst on exactly the journey the tiers exist for.** A stopping train's legs are
 * bimodal — standing at a station, or running at 60–160 km/h — with nothing in between. The median of
 * a bimodal sample lands in the *empty valley*, and the valley is the cycle band. Measured on the
 * reported Mühldorf → München Ost ride (2026-08-31): of 33 legs, 12 sat below 0.6 m/s and 12 above
 * 16 m/s, while **2** were in 4–8 — and the verdict was `cycle` at confidence 9. Weighted by
 * duration the same legs give 17.5 m/s, which is `drive`, which is what
 * [refineWithTransitKinematics] needs to be offered the trace at all.
 *
 * ### ⚠⚠ Why duration is the *right* weight, and not merely a weight that fixes this
 *
 * A leg's speed is a displacement over a span, so its error is the fixes' position error over that
 * span: `sigma_v ~= eps*sqrt(2) / dt`. **Precision therefore scales with duration** — the same 400 m
 * of position error reads as 44 m/s across 9 s and 0.7 m/s across 600 s. Weighting by `dt` is thus
 * approximately inverse-variance weighting, the ordinary way to combine observations of differing
 * precision, and the long legs are preferred because they are the *better measurements* rather than
 * because they are convenient. It is the same fact [LegMode.MIN_LEG_MS] acts on at the bottom end,
 * applied as a gradient instead of a cliff.
 *
 * ⚠ It stays a *median*, so the original reason for choosing one is untouched: a single teleport
 * spike still cannot move it — and a spike is a large displacement between *closely spaced* fixes,
 * which this now discounts twice over.
 *
 * ⚠⚠ **The accepted limit, stated rather than guarded**: a leg holding more than half a move's
 * duration decides the verdict by itself, and no median property protects against that. It is left
 * as intended behaviour by the paragraph above — such a leg is the most precise estimate in the path
 * — but it does mean a move that is essentially one long gap is classified on that gap's
 * straight-line speed. What bounds the damage is upstream, not here: [MovementDetectionConfig.maxResumeGapMs]
 * caps how far a recovered path may be stitched, and a straight line across a gap *under*-reports a
 * real path, so the error runs toward the slower verdict.
 *
 * ⚠ Reduces to [medianOrNull] exactly when every weight is equal — including the even-count average
 * — so a uniformly-sampled path classifies as it always did. That is why the midpoint straddle below
 * is worth the two lines: without it, an even number of equal-weight legs would silently start
 * picking the lower of the two middle values.
 *
 * ⚠ Weights are compared as `Long` (`2 * acc` against `total`) rather than accumulated as fractions,
 * so the straddle is an exact test rather than a float comparison that never quite holds.
 */
private fun timeWeightedMedianOrNull(samples: List<Pair<Double, Long>>): Double? {
    if (samples.isEmpty()) return null
    val sorted = samples.sortedBy { it.first }
    val total = sorted.sumOf { it.second }
    // No usable weights ⇒ every leg speaks for the same unknown span; the unweighted median is then
    // the honest reduction rather than a silent bias toward whichever leg happens to sort first.
    if (total <= 0L) return medianOrNull(sorted.map { it.first })
    var accumulated = 0L
    for (i in sorted.indices) {
        accumulated += sorted[i].second
        if (2 * accumulated > total) return sorted[i].first
        if (2 * accumulated == total) {
            val next = sorted.getOrNull(i + 1) ?: return sorted[i].first
            return (sorted[i].first + next.first) / 2.0
        }
    }
    return sorted.last().first
}

/** Legs beyond this add no further confidence. */
private const val CONFIDENT_LEG_COUNT = 20

/** Mean accuracy at or above this (metres) fully discounts the verdict; 0-accuracy fixes are neutral. */
private const val USELESS_ACCURACY_M = 150.0

private fun confidenceOf(median: Double, legCount: Int, fixes: List<CapturedFix>): Int {
    // Margin: distance to the nearest boundary of the bucket the median landed in, over half its width.
    val lower = MODE_BOUNDARIES.lastOrNull { it <= median } ?: 0.0
    val upper = MODE_BOUNDARIES.firstOrNull { it > median }
    // The open-ended top bucket has no upper edge; treat one boundary-width above as "fully in".
    val halfWidth = ((upper ?: (MODE_BOUNDARIES.last() * 2)) - lower) / 2.0
    val margin = if (halfWidth <= 0.0) 0.0 else {
        // ⚠⚠ §32.4-3: the top bucket has exactly ONE edge, and measuring against the synthetic far
        // side made the margin *fall* again past it — so every median above `MODE_BOUNDARIES.last() *
        // 2` (16 m/s ≈ 58 km/h) scored a negative margin, coerced to 0. **Every motorway drive and
        // every train therefore reported confidence 0**, which is not "near a boundary", it is the
        // most confidently-in-bucket movement there is. Measured on the reported 22.3 km rail leg:
        // median 17.6 m/s → `min(9.6, −1.6)` → 0.
        //
        // ⚠ Two live consumers read this and both silently lost their fast cases: `OngoingMove`'s
        // `MIN_NAMEABLE_CONFIDENCE` (35) meant the live card never named a motorway drive, and §31's
        // `TransitAdjacency.MIN_CONFIDENT` (30) meant a fast leg could never contribute to a
        // `NOT_TRANSIT` bracket. Both failed *safe* — unnamed, and no tightening — which is why this
        // survived: nothing looked broken, things merely never happened.
        val toNearestEdge =
            if (upper == null) median - lower else minOf(median - lower, upper - median)
        (toNearestEdge / halfWidth).coerceIn(0.0, 1.0)
    }

    val sample = (legCount.toDouble() / CONFIDENT_LEG_COUNT).coerceIn(0.0, 1.0)

    val reported = fixes.filter { it.accuracyM > 0 }
    val accuracy = if (reported.isEmpty()) 1.0 else {
        val mean = reported.sumOf { it.accuracyM.toDouble() } / reported.size
        (1.0 - mean / USELESS_ACCURACY_M).coerceIn(0.0, 1.0)
    }

    return (margin * sample * accuracy * 100).roundToInt().coerceIn(0, 100)
}
