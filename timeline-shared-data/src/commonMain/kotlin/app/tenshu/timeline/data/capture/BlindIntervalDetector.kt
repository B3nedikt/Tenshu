package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher

/**
 * **The underground ride, recognised by what the fix stream stops telling us**
 * (`docs/proposals/timeline-movement-capture.md` §17.5/§20/§21/§25).
 *
 * ### ⚠⚠ The reframe that makes this possible at all
 *
 * The tempting reading is *"GPS is the wrong sensor underground, so we lose the leg"*. That inverts
 * the evidence. A metro ride leaves a **positive signature**: the stream stops locating the user,
 * resumes kilometres away, at a speed no walk explains, with **both ends on subway alignment** —
 * which the production basemap already ships (`kind_detail=subway`, §24). Every input already
 * exists; `RailRefinementSweep` has the geometry in hand at the moment it judges the trace.
 *
 * ### ⚠⚠ Two ways to stop locating someone, and S4 only implemented one
 *
 * S4 (§20) assumed the stream goes **silent**. Measured against the reported journey (§21) that
 * premise failed: Munich's U-Bahn has mobile coverage, so `NETWORK` kept delivering a fix every
 * 60–133 s and the largest gap never reached the 3-minute gate. What actually happens is that
 * **GPS stops and NETWORK carries on** — accuracy goes from 4–11 m to 100–600 m and stays there for
 * the whole ride.
 *
 * Both are the same fact — *an interval in which we do not know where the user is* — so this is one
 * detector with two recognisers, not two tiers:
 *
 *  - [Kind.SILENT] — no fixes at all. The genuinely dark tunnel: regional rail, older systems,
 *    airplane mode. This is S4b unchanged.
 *  - [Kind.COARSE] — fixes arrive but none of them are GPS. This is what a **covered** metro looks
 *    like, and it is the case that occurs eleven times in eight days of the real corpus (§25.2).
 *
 * ### ⚠⚠ "GPS went dark" is only evidence if GPS was ever alight
 *
 * [MIN_FINE_FIX_SHARE] is the load-bearing guard, and it is the same distinction [RailEvidence]
 * draws one layer up: *"we could not look"* is not *"we looked and found nothing"*. A trace recorded
 * entirely on NETWORK — phone in a bag, GPS disabled, poor sky view — is blind from end to end, and
 * reading that as an underground ride would promote every such **drive** to transit. Measured, the
 * separation is not marginal: metro traces run 42–57 % fine fixes (60–82 % after §22 hygiene) while
 * the GPS-absent drives run 0–23 % (0–33 %). One bar at 0.35 sits inside both gaps.
 *
 * ⚠ A device that never reports accuracy at all scores 0 and is therefore never promoted — correct
 * by the same rule: without a quality claim there is no provider signal to read.
 *
 * ### The remaining guards, all biased toward the status quo
 *
 *  - **Subway specifically**, not any exclusive rail. Heavy rail is above ground and covered by the
 *    ordinary coverage tiers; admitting it would let any GPS outage beside a mainline promote a drive.
 *  - **Both** endpoints must anchor. One end near a station and the other anywhere is a phone that
 *    lost signal, not a journey between two stations.
 *  - A **speed floor** no pedestrian reaches, and a **speed ceiling** — see [MAX_METRO_SPEED_MPS]
 *    for why the two kinds do not share one.
 *  - Promotion only when the blind intervals are a **material share of distance** ([dominates]).
 *
 * Pure `commonMain`, no I/O. [detect] needs no tiles — the kinematic half stands alone, which is
 * what lets the sweep decide candidacy before spending a fetch.
 */
object BlindIntervalDetector {

    /** How the stream stopped locating the user. */
    enum class Kind {
        /** No fixes at all across the interval — a genuinely dark tunnel (S4b). */
        SILENT,

        /** Fixes arrived, none of them from GPS — a covered metro (§25). */
        COARSE,
    }

    /**
     * Reported accuracy at or below which a fix is taken to be **GPS** (metres).
     *
     * ⚠ This reads the *provider* through the one property that crosses the seam. `activeProviders()`
     * registers GPS and NETWORK as two independent listeners (§21.3) and `CapturedFix` does not
     * record which one produced it — but the two populations do not overlap in the measured corpus:
     * GPS reports 4–28 m, NETWORK 44–800 m. 30 m sits in that gap.
     *
     * ⚠ `accuracyM <= 0` means **not reported** and is deliberately *not* fine: absence of a quality
     * claim cannot establish that GPS was working.
     */
    const val FINE_ACCURACY_M: Int = 30

    /**
     * Fraction of a trace's fixes that must be GPS before a blind interval inside it means anything.
     *
     * See the type KDoc: this is the *"we could not look"* guard, and it is what stops a NETWORK-only
     * motorway drive from reading as an underground ride. ⚠ It is measured, not tuned to taste — and
     * it holds at the same value on both sides of the §22 hygiene change, which is the reason to
     * trust it rather than the corpus it came from.
     */
    const val MIN_FINE_FIX_SHARE: Double = 0.35

    /** Minimum silence to count as a [Kind.SILENT] interval (millis). Below this it is GPS jitter. */
    const val MIN_DARK_GAP_MS: Long = 3 * 60_000L

    /**
     * Minimum duration of a [Kind.COARSE] interval (millis).
     *
     * ⚠ Longer than a single missed GPS fix by a wide margin: the two providers interleave, so a
     * fine fix normally reappears within ~1–2 minutes. Real metro rides in the corpus run 4.8–16.2
     * minutes end to end, including the platform wait.
     */
    const val MIN_COARSE_INTERVAL_MS: Long = 4 * 60_000L

    /** Minimum coarse fixes inside a [Kind.COARSE] interval — one bad fix is not a blackout. */
    const val MIN_COARSE_FIXES: Int = 3

    /** Minimum straight-line jump across the interval (metres) — below this nobody went anywhere. */
    const val MIN_BLIND_DISTANCE_M: Double = 800.0

    /**
     * Implied speed (m/s ≈ 14 km/h) below which the interval is walkable and proves nothing.
     *
     * A person crosses 800 m in 3 minutes only by running; a metro covers its inter-station hop far
     * faster. Set above a brisk run so an outage on foot cannot fire this.
     */
    const val MIN_IMPLIED_SPEED_MPS: Double = 4.0

    /**
     * Implied speed above which a [Kind.SILENT] measurement is not believable (m/s ≈ 360 km/h).
     *
     * ⚠ A coarse network fix or a clock adjustment can imply an arbitrary speed. Above this the
     * honest answer is "we do not know", not "the metro was very fast" — the same ceiling posture
     * `TransitKinematics` takes for its flight band.
     */
    const val MAX_IMPLIED_SPEED_MPS: Double = 100.0

    /**
     * Metro plausibility ceiling for a [Kind.COARSE] interval (m/s ≈ 54 km/h).
     *
     * ⚠⚠ **Deliberately far tighter than [MAX_IMPLIED_SPEED_MPS], because the two kinds measure
     * different things.** A coarse interval still contains position samples, so the speed here is
     * taken over the *travelling sub-span* with waiting trimmed out — a tight estimate that can
     * carry a tight bound. A silent interval has no interior at all, so its figure is diluted by
     * any wait inside it and is only a **lower bound** on the true speed; a tight ceiling there
     * would reject long journeys for having paused.
     *
     * ⚠ Measured: real metro rides imply 4.2–11.3 m/s including station stops (no metro system
     * averages more), while the motorway drives that survive every other gate imply 12.3–39.5.
     *
     * ⚠⚠ **Known softness, stated rather than papered over:** because the trimming search takes the
     * *longest* in-band sub-span, a fast blackout that happens to end in a stop can still offer one
     * that averages into the band. Tightening it further would reject the real metro rides, whose own
     * fixes contain a 23.7 m/s leg produced by a single 600 m-accuracy sample. The tier does not rest
     * on this bound alone: the subway anchor, [MIN_FINE_FIX_SHARE] and [dominates] each independently
     * reject every drive in the measured corpus.
     */
    const val MAX_METRO_SPEED_MPS: Double = 15.0

    /**
     * How near a subway line an endpoint must sit to anchor the interval (metres).
     *
     * ⚠⚠ **Deliberately far wider than `RailMatcher.DEFAULT_TOLERANCE_M` (50 m), and that gap is
     * where this whole tier earns its keep.** A metro rider's surviving fixes are taken at street
     * level above a tunnel, so with ordinary GPS error they land *outside* the matcher's tolerance
     * and the coverage tiers cannot see them at all.
     *
     * ⚠⚠ **200, not S4's 150, and the 50 metres are measured rather than argued.** Against
     * production Munich tiles the real metro brackets sit 1, 1, 2, 3, 4, 7, 8, 9, 81, 116, 157, 167,
     * 191 m from subway alignment — so 150 rejected 3 of 11, *including the very journey that
     * prompted the tier*. The nearest thing that must stay rejected is a drive bracket at 905 m, so
     * widening to 200 keeps a 4.7× margin and costs no measured precision.
     */
    const val ENDPOINT_TOLERANCE_M: Double = 200.0

    /** The `kind_detail` value the production basemap uses for underground track. */
    private const val SUBWAY_KIND = "subway"

    /**
     * One interval in which the stream stopped locating the user.
     *
     * [fromIndex]/[toIndex] bracket the interval — for [Kind.COARSE] they are the *fine* fixes on
     * either side, which is what gets anchored. [distanceM]/[impliedSpeedMps] describe the
     * travelling span inside it, which for [Kind.COARSE] is not the same thing (see [detect]).
     */
    data class BlindInterval(
        val kind: Kind,
        val fromIndex: Int,
        val toIndex: Int,
        val durationMs: Long,
        val distanceM: Double,
        val impliedSpeedMps: Double,
    )

    /**
     * Every blind interval in [fixes], judged on kinematics alone.
     *
     * ⚠ Takes the **timestamped fixes**, not the lon/lat path the coverage tiers use: both a silence
     * and an accuracy blackout are *time* facts that a coordinate-only path cannot express.
     *
     * ⚠ Deliberately **tile-free**, so the sweep can ask "is this trace even a candidate?" without
     * spending a fetch — which is what makes it affordable to offer walks and runs to this tier at
     * all. The map half is [anchoredToSubway].
     */
    fun detect(
        fixes: List<CapturedFix>,
        /**
         * The *"was GPS ever alight?"* bar. Defaults to [MIN_FINE_FIX_SHARE] — the §25 rule, unchanged
         * for every existing caller.
         *
         * ⚠⚠ **Overridable only because the guard is anti-correlated with how underground a ride is,
         * and a caller may substitute a strictly better test.** Measured on a purely underground U5
         * hop the ride scores **0.25** — inside the drive band — precisely *because* it is a tunnel;
         * §25 calibrated 42–57 % on rides that were partly above ground. A caller passing `0.0` here
         * is asserting it has its own discriminator; [UndergroundHop] is the one that does, and says
         * which.
         *
         * ⚠ Widening it to "measure the share over surrounding context instead" was tried and
         * **falsified**: with only the *past* visible (all capture ever has), the second real metro
         * hop scores 0.16–0.20 at every window from 15 to 45 minutes — below the trains it must beat
         * — because it followed a train ride and inherited that ride's coarse stream. No trailing
         * window separates the two populations; do not re-derive that idea.
         */
        minFineFixShare: Double = MIN_FINE_FIX_SHARE,
    ): List<BlindInterval> {
        if (fixes.size < 2) return emptyList()
        if (fineFixShare(fixes) < minFineFixShare) return emptyList()

        val coarse = coarseIntervals(fixes)
        // ⚠ A long gap *inside* a coarse blackout is part of that blackout, not a second interval —
        // counting both would let one metro ride pay twice into `dominates`.
        val silent = silentIntervals(fixes).filterNot { gap ->
            coarse.any { gap.fromIndex >= it.fromIndex && gap.toIndex <= it.toIndex }
        }
        return coarse + silent
    }

    /** Do both of [interval]'s bracketing fixes sit within [ENDPOINT_TOLERANCE_M] of subway track? */
    fun anchoredToSubway(
        fixes: List<CapturedFix>,
        interval: BlindInterval,
        lines: List<RailMatcher.RailLine>,
    ): Boolean {
        val subway = lines.filter { it.kind == SUBWAY_KIND }
        if (subway.isEmpty()) return false
        return anchored(fixes[interval.fromIndex], subway) && anchored(fixes[interval.toIndex], subway)
    }

    /**
     * Do [intervals] characterise the whole trace?
     *
     * ⚠ The trace is promoted only when blind travel accounts for a **material share** of its
     * distance — a 400 m underground hop inside a 40 km drive says nothing about the drive.
     * Deliberately a share of *distance* and not of *time*: the blind interval's time is exactly the
     * thing that is missing.
     *
     * ⚠⚠ This is also what routes the evidence to the traces that need it, which the corpus shows
     * sharply: the U-Bahn hop inside a 120 km train journey scores 0.01–0.10 (no promotion needed —
     * heavy rail already settled those), while the short trips mislabelled `walk`/`run` because their
     * middle was blind score 0.60–0.80.
     */
    fun dominates(fixes: List<CapturedFix>, intervals: List<BlindInterval>, minShare: Double = 0.5): Boolean {
        if (intervals.isEmpty()) return false
        val total = fixes.pathDistanceMeters()
        if (total <= 0.0) return false
        return intervals.sumOf { it.distanceM } / total >= minShare
    }

    /** Share of [fixes] that carry a GPS-grade accuracy claim. */
    internal fun fineFixShare(fixes: List<CapturedFix>): Double {
        if (fixes.isEmpty()) return 0.0
        return fixes.count { isFine(it) }.toDouble() / fixes.size
    }

    private fun isFine(fix: CapturedFix): Boolean = fix.accuracyM in 1..FINE_ACCURACY_M

    /** Maximal runs of non-fine fixes, bracketed by the fixes either side. */
    private fun coarseIntervals(fixes: List<CapturedFix>): List<BlindInterval> {
        val out = ArrayList<BlindInterval>()
        var i = 0
        while (i < fixes.size) {
            if (isFine(fixes[i])) { i++; continue }
            var j = i
            while (j + 1 < fixes.size && !isFine(fixes[j + 1])) j++
            val count = j - i + 1
            // ⚠ Clamped at the trace ends rather than requiring a fine fix on both sides: a ride that
            // begins or ends the trace is still a ride, and the measured corpus contains several.
            val a = if (i > 0) i - 1 else 0
            val b = if (j < fixes.size - 1) j + 1 else fixes.size - 1
            val duration = fixes[b].timestampMs - fixes[a].timestampMs
            if (count >= MIN_COARSE_FIXES && duration >= MIN_COARSE_INTERVAL_MS) {
                travellingSpan(fixes, a, b)?.let { (distance, speed) ->
                    out += BlindInterval(Kind.COARSE, a, b, duration, distance, speed)
                }
            }
            i = j + 1
        }
        return out
    }

    /**
     * The longest credible movement inside `[a, b]`.
     *
     * ⚠⚠ Not the bracket-to-bracket displacement, and that difference decides whether the tier fires
     * at all: an 8-minute wait on the platform is *inside* the blackout, and measuring across it
     * put the reported journey at 2.9 m/s — under the walk floor. Trimming to the sub-span that
     * actually moved puts the same ride at 7.5 m/s. The coarse fixes are poor positions but they are
     * positions, which is precisely what a [Kind.SILENT] interval does not have.
     */
    private fun travellingSpan(fixes: List<CapturedFix>, a: Int, b: Int): Pair<Double, Double>? {
        var bestDistance = 0.0
        var bestSpeed = 0.0
        for (i in a until b) {
            for (j in i + 1..b) {
                val seconds = (fixes[j].timestampMs - fixes[i].timestampMs) / 1000.0
                if (seconds <= 0) continue
                val distance = capturedDistanceMeters(fixes[i], fixes[j])
                val speed = distance / seconds
                if (speed < MIN_IMPLIED_SPEED_MPS || speed > MAX_METRO_SPEED_MPS) continue
                if (distance > bestDistance) { bestDistance = distance; bestSpeed = speed }
            }
        }
        return if (bestDistance >= MIN_BLIND_DISTANCE_M) bestDistance to bestSpeed else null
    }

    /** Gaps with no fixes at all — S4b, unchanged apart from the shared guards. */
    private fun silentIntervals(fixes: List<CapturedFix>): List<BlindInterval> {
        val out = ArrayList<BlindInterval>()
        for (i in 1 until fixes.size) {
            val a = fixes[i - 1]
            val b = fixes[i]
            val gap = b.timestampMs - a.timestampMs
            if (gap < MIN_DARK_GAP_MS) continue
            val distance = capturedDistanceMeters(a, b)
            if (distance < MIN_BLIND_DISTANCE_M) continue
            val implied = distance / (gap / 1000.0)
            if (implied < MIN_IMPLIED_SPEED_MPS || implied > MAX_IMPLIED_SPEED_MPS) continue
            out += BlindInterval(Kind.SILENT, i - 1, i, gap, distance, implied)
        }
        return out
    }

    /** Is this fix within [ENDPOINT_TOLERANCE_M] of any subway line? */
    private fun anchored(fix: CapturedFix, subway: List<RailMatcher.RailLine>): Boolean {
        val point = LonLat(lon = fix.lngE7 / 1e7, lat = fix.latE7 / 1e7)
        // Reuse the matcher's own point-to-polyline measure so "near track" means the same thing here
        // as it does in every other tier.
        return RailMatcher.match(listOf(point), subway, toleranceM = ENDPOINT_TOLERANCE_M).matchedPointCount > 0
    }
}
