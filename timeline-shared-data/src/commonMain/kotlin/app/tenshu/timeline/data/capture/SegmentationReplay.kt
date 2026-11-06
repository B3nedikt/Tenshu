package app.tenshu.timeline.data.capture

/**
 * **Replay segmentation over data already on the device** (`timeline-movement-capture.md` §18.9) —
 * the offline half of verifying S6, and the standing instrument for every later tuning question.
 *
 * ### ⚠⚠ The two corpora are not equally powerful, and the difference is the whole design
 *
 * | corpus | available | answers |
 * |---|---|---|
 * | **stored `MovementTrace`s** | today, for every trace ever recorded | S6b only — *would the extent gate now reject this?* |
 * | **[RawFixJournal]** | after recording is switched on for a while | S6a **and** S6b — the full segmenter, dwells included |
 *
 * The asymmetry is not an oversight in this file; it is a property of what capture persists. A move
 * keeps a (decimated) path, so its geometry survives. **A dwell keeps only its centroid** — the
 * cluster is discarded — so the jitter that split §18.2's stay into three rows was never written
 * down, and no amount of cleverness here can reconstruct it. That is precisely why [RawFixJournal]
 * exists, and why S6a's radius could not be validated against the report that motivated it.
 *
 * ⚠ **S1 is not replayable at all, by either corpus.** It changes *which fixes exist*; a 10-second
 * trace cannot be synthesised from 60-second samples. Wakeup cadence, battery and significant-motion
 * wake are forward-only, on a live device — see §18.9.
 *
 * Pure `commonMain`, no I/O: it takes decoded inputs and returns a report. `SegmentationReplaySweep`
 * owns reading the store.
 */
object SegmentationReplay {

    /** The clustering rule as it stood before S6a — expressed in config, so there is no dead branch. */
    val preS6VisitConfig: VisitDetectionConfig =
        VisitDetectionConfig().let { it.copy(maxAnchorRadiusM = it.radiusM) }

    /** The move rule as it stood before S6b: no extent gate at all. */
    val preS6MoveConfig: MovementDetectionConfig =
        MovementDetectionConfig(minExtentM = 0.0, extentAccuracyFactor = 0.0)

    // ── corpus 1: stored traces (S6b) ──────────────────────────────────────────

    /**
     * One stored trace, decoded far enough to judge.
     *
     * ⚠⚠ [storedDistanceM] is the **raw** path length recorded at capture, while [path] is the
     * **decimated** polyline. Both are needed and neither substitutes for the other: recomputing
     * distance from the decimated path would under-report it (Douglas–Peucker removes length), while
     * extent is computed *from* the path because DP preserves the extremes it is measured over. Using
     * the stored number for the length arm and the decoded geometry for the extent arm is what makes
     * this replay honest rather than approximate.
     */
    data class StoredTracePath(
        val traceId: String,
        val startMs: Long,
        val mode: String,
        val storedDistanceM: Long,
        val rawPointCount: Long,
        val path: List<CapturedFix>,
    )

    /** What the current extent gate would say about a trace that is already in the store. */
    data class TraceVerdict(
        val trace: StoredTracePath,
        val extentM: Double,
        val extentFloorM: Double,
        val meanAccuracyM: Double,
    ) {
        /** `true` when today's rule would have refused to record this trace at all. */
        val wouldBeRejected: Boolean get() = extentM <= extentFloorM
    }

    data class TraceReport(
        val examined: Int,
        val rejected: List<TraceVerdict>,
        /**
         * Traces whose decoded path is too short to judge (< 2 points after decimation).
         *
         * ⚠ Reported rather than silently skipped or counted as passing. A replay that quietly
         * dropped the cases it could not decide would overstate its own coverage, which for a
         * measuring instrument is the one unacceptable failure.
         */
        val undecidable: Int,
    ) {
        val rejectedCount: Int get() = rejected.size
    }

    fun replayStoredTraces(
        traces: List<StoredTracePath>,
        config: MovementDetectionConfig = MovementDetectionConfig(),
    ): TraceReport {
        var undecidable = 0
        val rejected = mutableListOf<TraceVerdict>()
        for (trace in traces) {
            if (trace.path.size < 2) {
                undecidable++
                continue
            }
            val verdict = TraceVerdict(
                trace = trace,
                extentM = trace.path.pathExtentMeters(),
                extentFloorM = config.extentFloorFor(trace.path),
                meanAccuracyM = trace.path.meanReportedAccuracyM(),
            )
            if (verdict.wouldBeRejected) rejected += verdict
        }
        return TraceReport(examined = traces.size, rejected = rejected, undecidable = undecidable)
    }

    // ── corpus 2: raw fixes (S6a + S6b) ────────────────────────────────────────

    /** What one config produced over a fix stream. */
    data class Outcome(
        /** Distinct stays: a dwell is emitted twice (confirm + depart), so arrivals are de-duplicated. */
        val dwellCount: Int,
        val moveCount: Int,
        val moveDistancesM: List<Long>,
    )

    data class FixReport(
        val fixCount: Int,
        val spanMs: Long,
        val before: Outcome,
        val after: Outcome,
    ) {
        /** Stays the new rule keeps whole that the old one split. Negative would be a regression. */
        val dwellsMerged: Int get() = before.dwellCount - after.dwellCount

        /** Moves the extent gate removed — the phantom walks of §18.3. */
        val movesSuppressed: Int get() = before.moveCount - after.moveCount
    }

    /**
     * Run the **whole segmenter** over a recorded fix stream under both rules.
     *
     * ⚠ `journal = OpenMoveJournal.None` deliberately: replay must not read or write the live
     * capture journal, or a diagnostic run would corrupt the in-progress journey it is diagnosing.
     */
    fun replayRawFixes(
        fixes: List<CapturedFix>,
        beforeVisit: VisitDetectionConfig = preS6VisitConfig,
        beforeMove: MovementDetectionConfig = preS6MoveConfig,
        afterVisit: VisitDetectionConfig = VisitDetectionConfig(),
        afterMove: MovementDetectionConfig = MovementDetectionConfig(),
    ): FixReport {
        val ordered = fixes.sortedBy { it.timestampMs }
        return FixReport(
            fixCount = ordered.size,
            spanMs = if (ordered.size < 2) 0 else ordered.last().timestampMs - ordered.first().timestampMs,
            before = ordered.outcomeUnder(beforeVisit, beforeMove),
            after = ordered.outcomeUnder(afterVisit, afterMove),
        )
    }

    private fun List<CapturedFix>.outcomeUnder(
        visitConfig: VisitDetectionConfig,
        moveConfig: MovementDetectionConfig,
    ): Outcome {
        val segmenter = MovementSegmenter(visitConfig, moveConfig, OpenMoveJournal.None)
        val events = buildList {
            this@outcomeUnder.forEach { addAll(segmenter.onFix(it)) }
            addAll(segmenter.onEnd())
        }
        val moves = events.filterIsInstance<CaptureEvent.Move>().map { it.move }
        return Outcome(
            dwellCount = events.filterIsInstance<CaptureEvent.Dwell>()
                .map { it.visit.arrivalMs }.distinct().size,
            moveCount = moves.size,
            moveDistancesM = moves.map { it.distanceM.toLong() },
        )
    }
}
