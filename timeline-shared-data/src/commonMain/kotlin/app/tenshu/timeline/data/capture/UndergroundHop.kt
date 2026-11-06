package app.tenshu.timeline.data.capture

/**
 * **The metro hop, recognised by a network fix that stops moving** (§27).
 *
 * ### ⚠⚠ Why this cannot live in the sweep, where the other transit tiers live
 *
 * `RailRefinementSweep` re-derives everything from the **stored** polyline, and for an underground
 * hop that polyline is empty of evidence by the time it is written. Measured on a real U5 ride:
 *
 * | span | coarse run | duration | travelling span |
 * |---|---|---|---|
 * | the stored trace (184 s) | 12 | 153 s | **0 m** |
 * | dwell → move → dwell (709 s) | 33 | 709 s | **2761 m @ 4.7 m/s** |
 *
 * Two losses put it there: `FixHygiene.geometryPath` drops the ≥ 200 m platform fixes as bad
 * *geometry* (they are — for distance; they are also the only *evidence*, which is §25's
 * "presence vs geometry needs a third role" arriving from the other side), and Douglas–Peucker
 * decimates the survivors — 8 fixes to 2 on this ride. Nothing downstream can reconstruct either.
 *
 * ⚠ **Partly resolved since §32.6, and only partly** — `geometryPath` now keeps a path's two
 * *terminal* fixes whatever their accuracy, so a hop's arrival is no longer deleted and the ride's
 * distance reaches where it ended. The *interior* platform fixes are still dropped, which is correct
 * for distance and is why this tier still exists: the discriminator below reads the raw span, not the
 * stored path, and nothing about that changes.
 *
 * ### ⚠⚠ And why the span is the dwell triple, not the move
 *
 * **The move alone contains no travel at all.** Its position is frozen end to end; its recorded 924 m
 * is one cell-change jump at the boundary. The evidence spans the two *platform dwells* — which
 * capture detects perfectly well and which no transit tier had ever looked at. On a metro hop the
 * platform wait is not noise around the journey, it is most of the journey's duration.
 *
 * ### The discriminator: the anchor stops moving
 *
 * ⭐ Underground the phone is pinned to one cell (or Wi-Fi) anchor, so consecutive NETWORK fixes are
 * **the identical coordinate repeated**; a train moving above ground hands off between cells
 * continuously, so its coarse fixes march along. Measured, median displacement between consecutive
 * coarse fixes:
 *
 * | | metro U5 | metro (2nd) | train 22 km | train 59 km | train 55 km |
 * |---|---|---|---|---|---|
 * | median coarse step | **0 m** | **0 m** | 482 m | 311 m | 360 m |
 *
 * That is categorical, not marginal, and — the property that matters for capture — it needs **no view
 * of the future and no context window**.
 *
 * ⚠⚠ It replaces [BlindIntervalDetector.MIN_FINE_FIX_SHARE] for this tier, which is why `0.0` is
 * passed there. That guard exists to refuse a NETWORK-only drive, and it is *anti-correlated with
 * depth*: the clearest metro hop scores lowest (0.25, inside the drive band). The frozen-anchor test
 * refuses the same drives for a stronger reason — a driven phone's coarse fixes move.
 * ⚠ Widening the guard to a trailing context window was tried and **falsified** — see
 * [BlindIntervalDetector.detect].
 *
 * Deliberately **kinematic only**: capture cannot reach map tiles, so a promotion here is a
 * *candidate* that `RailRefinement` may still demote once it can check the endpoints against subway
 * alignment (the M9-3 rule that the map tier is the one allowed to demote). Promote-then-verify beats
 * wait-for-tiles because the evidence is perishable and the tiles are not.
 */
internal object UndergroundHop {

    /**
     * Median displacement between consecutive coarse fixes, at or below which the device is taken to
     * be pinned to one anchor (metres).
     *
     * ⚠ Measured 0 m for both real metro hops and 311–482 m for every above-ground train, so this sits
     * in a gap two orders of magnitude wide. It is set at the low end anyway: a bicycle's coarse
     * fixes already step 61 m, and nothing is gained by admitting more.
     */
    const val MAX_ANCHOR_STEP_M = 50.0

    /** Consecutive coarse fixes needed before "the anchor is frozen" means anything. */
    const val MIN_ANCHOR_FIXES = 4

    /**
     * How far back [legIsPinned] reads when asking whether the anchor is moving (millis, §34.2).
     *
     * ⚠⚠ **A time window, not a count of fixes, and it has to be this long.** A short window cannot
     * survive a cell handoff: measured on the 2026-08-14 U5, two consecutive handoffs at 11:09:14 and
     * 11:09:36 step 1767 m and 944 m, and in an 8-fix window those are two of only three steps — the
     * median becomes 944 m and the ride reads *unpinned in its middle*. Over four minutes the same two
     * jumps are two steps out of eight and the median stays 0.
     *
     * ⚠ It is [BlindIntervalDetector.MIN_COARSE_INTERVAL_MS] reused rather than a second number fitted
     * to the same corpus: §25 already measured how long a coarse blackout must last to mean anything,
     * and a splitter that cut on a shorter window than the promotion tier accepts would only ever buy
     * a row that stays mislabelled.
     */
    const val PINNED_WINDOW_MS: Long = BlindIntervalDetector.MIN_COARSE_INTERVAL_MS

    /**
     * Does [move] sit inside a blind interval that only a metro ride explains?
     *
     * @param move the fixes of the leg being classified.
     * @param span the dwell → move → dwell evidence window, platform waits included.
     *
     * ### ⚠⚠ Why the leg must be pinned too, and not merely sit inside a pinned span (§32.7)
     *
     * Both of the first two guards are about the *span*, and a span is shared: when one flush produces
     * several legs they are all judged against the same window, and `coarseIntervals` builds **maximal
     * runs of non-fine fixes**, so an all-coarse flush is *one* interval covering everything —
     * departure dwell included. [movedInside] then asks "did this leg happen during the blackout?",
     * which every leg in the flush answers yes to.
     *
     * Measured on the reported 2026-08-12 journey, one flush holding the train's terminal approach
     * into Ostbahnhof followed by the U5:
     *
     * | | anchor frozen? | promoted before | after |
     * |---|---|---|---|
     * | the span (incl. a 40-fix dwell tail) | **true** | — | — |
     * | the train's tail — fixes *marching* | false | **true** ⚠ | false |
     * | the U5 — fixes pinned | true | true | true |
     *
     * ⚠ The span test is close to unconditionally true, which is the part worth remembering:
     * `previousDwellTail` contributes up to `DWELL_TAIL_FIXES` fixes at one location, so it dominates
     * the median and the span reads frozen however the ride actually moved. It is not useless — it is
     * simply not *per leg*, and it was carrying the whole decision.
     *
     * So the same measure is applied to the leg's own fixes. Nothing new is introduced: it is
     * [anchorIsFrozen] against [MAX_ANCHOR_STEP_M], the constant whose two-orders-of-magnitude gap
     * (0 m metro vs 311–482 m train) was measured *per ride* in the first place — this simply asks it
     * of the ride again rather than only of the window around it.
     *
     * ⚠ A leg with fewer than [MIN_ANCHOR_FIXES] coarse fixes is now **not promoted**. That is a real
     * cost and it is accepted deliberately: with two steps the median is the mean, and the one
     * cell-change jump *is* one of them — the exact averaging failure [anchorIsFrozen]'s KDoc refuses.
     * A leg that thin cannot distinguish pinned from marching, and §27's posture is that a missed
     * promotion leaves the speed label while a wrong one is a confident lie.
     *
     * ### ⚠⚠ The span is asked about the blackout, not about the whole flush (§34.1)
     *
     * §32.7 above reasoned that the *span* test "is close to unconditionally true", because
     * `previousDwellTail` contributes up to `DWELL_TAIL_FIXES` fixes at one location and dominates the
     * median. **That holds only while the flush is short.** Measured on the 2026-08-14 commute, one
     * flush carrying a 57 km surface train ride followed by the U5:
     *
     * | window | fixes | median coarse step | frozen? |
     * |---|---|---|---|
     * | the whole span (dwell tail + train + hop) | 327 | **200.9 m** | false ⚠ |
     * | the COARSE interval the hop sits in | 51 | **0.0 m** | true |
     * | the U5 leg itself | 21 | **0.0 m** | true |
     *
     * A 40-fix tail cannot dominate a 287-fix path, so the train's marching coarse fixes set the
     * median and the span test **vetoed the very ride this tier exists for** — the U5 shipped as
     * `Walk · 4.9 km`. So the same measure is asked of the **interval** instead: the blackout the leg
     * sits inside, which is the evidence window §27 meant all along and is bounded by the platform
     * dwells rather than by however much unrelated travel the flush happened to buffer.
     *
     * ⚠ Nothing is weakened by the move: §32.7's train-tail case is refused by [anchorIsFrozen] on the
     * leg (its fixes march), and a NETWORK-only drive is refused by that same test *and* now by the
     * interval's — a driven phone's coarse fixes move whichever window you read them over. What is
     * dropped is only the whole-flush reading, which measured the wrong thing whenever the flush was
     * longer than the hop.
     *
     * ⚠⚠ **Stated rather than papered over: this helps only when the ride *has* its own interval.**
     * `coarseIntervals` builds maximal runs of non-fine fixes, so a flush that never sees GPS from end
     * to end is **one** interval and the reading is the old whole-flush one again. Every measured
     * surface ride carries some GPS (§25: trains run 0–33 % fine), which is what separates the train's
     * blackout from the hop's on the real journey; a phone that never gets a fix at all still loses the
     * promotion. That is the pre-§34 outcome — a missed promotion, never a wrong one — and it is the
     * failure §27 chooses everywhere else.
     */
    fun promotes(move: List<CapturedFix>, span: List<CapturedFix>): Boolean {
        if (move.isEmpty() || span.size < 2) return false
        if (!anchorIsFrozen(move)) return false
        return BlindIntervalDetector.detect(span, minFineFixShare = 0.0)
            .filter { it.kind == BlindIntervalDetector.Kind.COARSE }
            .any { movedInside(move, span, it) && anchorIsFrozen(span.subList(it.fromIndex, it.toIndex + 1)) }
    }

    /**
     * Is the leg made of [legFixes] **blind**, in a stretch whose anchor is **pinned** (§34.2)?
     *
     * The discriminator [anchorIsFrozen] reads is *"the coarse anchor is not moving"*, and on its own
     * that is **not** evidence of a tunnel: it fires wherever the traveller stays inside one cell,
     * GPS working or not. Measured on this corpus a real 4.8 km **run** reads `anchorIsFrozen = true`
     * over its own fixes, because its NETWORK samples repeat while its GPS samples march.
     *
     * What makes the reading mean *underground* is the conjunction §25 already draws — **GPS stopped
     * reporting at all**. So this asks for both, and it is the blindness conjunct that carries the
     * separation: a run, a walk at a red light and a phone in a pocket all keep delivering fine fixes.
     *
     * ### ⚠⚠ The two conjuncts are asked of deliberately different spans
     *
     * | conjunct | asked of | why not the other span |
     * |---|---|---|
     * | **blind** — no fine fix at all | the leg's own fixes | a window is *entered* from daylight, so requiring the whole window to be dark cannot recognise a blackout until a whole window has passed, and the boundary then lands minutes into the ride |
     * | **pinned** — [anchorIsFrozen] | [PINNED_WINDOW_MS] of trailing fixes | a leg is two or three fixes, and one cell handoff among three steps *is* the median |
     *
     * ⚠ **Blind means every fix in the leg, not most of them.** A leg with one fine fix in it is a leg
     * in which we *could* look, and §25's whole posture is that "we could not look" and "we looked and
     * found nothing" are different claims. A share-based bar would also re-open the trailing-context
     * idea [BlindIntervalDetector.detect] records as falsified.
     */
    internal fun legIsPinned(legFixes: List<CapturedFix>, window: List<CapturedFix>): Boolean {
        if (legFixes.isEmpty()) return false
        if (legFixes.any { it.accuracyM in 1..BlindIntervalDetector.FINE_ACCURACY_M }) return false
        return anchorIsFrozen(window)
    }

    /**
     * Are the coarse fixes in [span] pinned to one place?
     *
     * ⚠ Median, not mean: the one cell-change jump that reveals the ride is a legitimate large step,
     * and a mean would let that single value mask a stream that is otherwise marching. Median asks
     * the right question — is the *typical* coarse fix a repeat of the last one?
     *
     * ### ⚠⚠ Steps are measured between *independent* samples, or the median measures the providers
     *
     * `activeProviders()` registers GPS and NETWORK separately (§21.3), so a position is routinely
     * delivered **twice, seconds apart, at zero displacement** — and when both providers are coarse,
     * as they are underground and in a covered stairwell, every second step in the raw list is a 0 by
     * construction. A stream can then be marching and still read frozen, because half its steps are
     * duplicates. Measured on the 2026-08-14 walk from the platform to the U5:
     *
     * | coarse steps | median | verdict |
     * |---|---|---|
     * | as delivered — `0, 3, 0, 76, 0, 111, 0, 48, 0` | **0 m** | frozen ⚠ a walk |
     * | between samples ≥ [LegMode.MIN_LEG_MS] apart — `13, 76, 111, 48` | **62 m** | not frozen |
     *
     * ⚠⚠ That false reading is not academic: it promoted the walk to the platform to `TRANSIT`
     * alongside the ride, and the two then coalesced (§32.2) back into the single 4.9 km row §34 set
     * out to split. **The same trap one layer up is what [LegMode.MIN_LEG_MS] was measured for**, so
     * this reuses that constant rather than fitting a second one — same duplication, same corpus, same
     * empty band between 6 s and 10 s.
     */
    internal fun anchorIsFrozen(span: List<CapturedFix>): Boolean {
        val anchors = independentCoarseAnchors(span)
        if (anchors.size < MIN_ANCHOR_FIXES) return false
        val steps = (1 until anchors.size).map { capturedDistanceMeters(anchors[it - 1], anchors[it]) }
        return median(steps) <= MAX_ANCHOR_STEP_M
    }

    /**
     * The coarse fixes of [span], thinned to one per [LegMode.MIN_LEG_MS] — the same absorb rule
     * [LegMode.legsOf] applies, for the same reason.
     *
     * ⚠ The *first* fix of each cluster is kept rather than the last, so a step is measured from where
     * the anchor was first seen. Keeping the last would attribute a duplicate's timestamp to a
     * position that has not been re-measured.
     */
    private fun independentCoarseAnchors(span: List<CapturedFix>): List<CapturedFix> {
        val out = ArrayList<CapturedFix>()
        for (fix in span) {
            if (fix.accuracyM in 1..BlindIntervalDetector.FINE_ACCURACY_M) continue
            val last = out.lastOrNull()
            if (last == null || fix.timestampMs - last.timestampMs >= LegMode.MIN_LEG_MS) out += fix
        }
        return out
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.MAX_VALUE
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * Is the leg being promoted actually the travel this interval describes?
     *
     * ⚠ Time overlap, not containment: the move is a *sub-range* of the interval (which also covers
     * the platform waits), so requiring containment either way round would reject every real case.
     * Overlap suffices because the interval is already bounded by the dwells either side.
     */
    private fun movedInside(
        move: List<CapturedFix>,
        span: List<CapturedFix>,
        interval: BlindIntervalDetector.BlindInterval,
    ): Boolean {
        val from = span[interval.fromIndex].timestampMs
        val to = span[interval.toIndex].timestampMs
        return move.first().timestampMs <= to && move.last().timestampMs >= from
    }
}
