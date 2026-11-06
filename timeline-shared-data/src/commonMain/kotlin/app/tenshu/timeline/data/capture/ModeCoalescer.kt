package app.tenshu.timeline.data.capture

/**
 * **S9 — re-join the runs [ModeSplitter] cut that classification then turned out to agree about**
 * (`timeline-movement-capture.md` §32.2).
 *
 * ### The gap this closes
 *
 * The two halves of the flush disagree about what a "mode" is, and nothing reconciled them:
 *
 * | stage | decides on |
 * |---|---|
 * | [ModeSplitter] | [LegMode] — raw per-leg speed + cadence bands, over the fixes as delivered |
 * | [toCapturedMoveOrNull] → [classifyMode] → `promoteIfUnderground` | the **refined** verdict, after `TransitKinematics` and the §27 underground promotion have had their say |
 *
 * So a boundary is chosen on evidence that a later tier then overrules, and the cut stands anyway.
 * Measured on the reported U5 ride (2026-08-12): two runs, cut apart because their raw leg speeds sat
 * in different bands (2.54 m/s and 0.18 m/s), **both promoted to `TRANSIT` by `UndergroundHop`** — one
 * ride, rendered as `Transit · 1.8 km` immediately followed by `Transit · 1.2 km`. The split bought
 * nothing and cost a row.
 *
 * ⚠⚠ **Read-time cannot rescue this.** `TimelineProjection.collapseTransitStationStops` folds
 * `move → stop → move`, and `isPassThroughStop` returns false for anything that is not a `Visit` — so
 * two *adjacent* movement rows with no stay between them are the one shape it structurally cannot
 * reach. Fixing it there would mean teaching the projector to merge captured legs, which is exactly
 * the "one journey, many rows" problem S2 created; fixing it here means the second row is never
 * written.
 *
 * ### The rule, and why it is safe
 *
 * Adjacent runs join **iff their final classifications agree and the joined path still classifies the
 * same way**. Both conjuncts are load-bearing:
 *
 *  - *agree* — if the refined verdicts differ, S2's cut did real work (train → walk) and must stand.
 *    This is the case S2 was written for and it is untouched.
 *  - *and the join still says so* — distance, extent, median speed and confidence are all computed
 *    over the whole path, so a join is a genuinely different measurement, not a relabelling. A pair
 *    whose union classifies as something neither half was is evidence the two really were different
 *    movement, so the split is kept. Fail toward the status quo, the same posture [ModeSplitter]
 *    takes.
 *
 * ⚠ A run that is not recordable on its own ([finalise] returning `null`) **breaks the chain** rather
 * than being skipped over. Joining across a dropped run would silently attribute its distance to a
 * neighbour it was never contiguous with.
 *
 * ⚠ A single run is returned unchanged, so a flush with no mode change is byte-identical to the
 * pre-S9 path — this is additive, never a re-shaping of the simple case.
 */
internal object ModeCoalescer {

    /**
     * Fold [runs] (in time order, sharing boundary fixes as [ModeSplitter.split] emits them) into the
     * moves that should actually be recorded.
     *
     * @param finalise turns a candidate path into the move it would be recorded as — the *whole*
     *   flush-time pipeline (thresholds, classification, underground promotion), not just the mode.
     *   Passed in rather than called directly because the §27 promotion needs the segmenter's
     *   dwell → move → dwell span, which is not derivable from the runs.
     */
    fun coalesce(
        runs: List<List<CapturedFix>>,
        finalise: (List<CapturedFix>) -> CapturedMove?,
    ): List<CapturedMove> {
        val out = ArrayList<CapturedMove>(runs.size)
        var openPath: List<CapturedFix> = emptyList()
        var openMove: CapturedMove? = null

        fun close() {
            openMove?.let { out += it }
            openPath = emptyList()
            openMove = null
        }

        for (run in runs) {
            val move = finalise(run)
            if (move == null) {
                // Unrecordable on its own. ModeSplitter.mergeUnviable normally prevents this; when it
                // does happen the run is dropped exactly as it was before S9 — but it also severs the
                // chain, so nothing joins across the hole it leaves.
                close()
                continue
            }
            val open = openMove
            if (open == null) {
                openPath = run
                openMove = move
                continue
            }
            if (move.classification.mode != open.classification.mode) {
                close()
                openPath = run
                openMove = move
                continue
            }
            // ⚠ `drop(1)`: runs share their boundary fix, so concatenating raw would repeat it and add
            // a zero-length leg — which drags the median speed down and can flip the joined verdict.
            val joinedPath = openPath + run.drop(1)
            val joined = finalise(joinedPath)
            if (joined != null && joined.classification.mode == move.classification.mode) {
                openPath = joinedPath
                openMove = joined
            } else {
                close()
                openPath = run
                openMove = move
            }
        }
        close()
        return out
    }
}
