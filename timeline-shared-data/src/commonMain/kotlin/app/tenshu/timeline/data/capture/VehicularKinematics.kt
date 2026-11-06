package app.tenshu.timeline.data.capture

/**
 * **Did this path travel at a speed no person can produce under their own power?** (TR-1,
 * `docs/proposals/timeline-movement-capture.md` §38.3.)
 *
 * ### Why this exists: a conclusion was filtering the evidence that produces it
 *
 * `RailRefinement` is the only tier that consults the map, and the map is the closest thing to
 * ground truth this app has — it both promotes *and* demotes. Yet it was reachable only through
 * `mode ∈ {drive, transit}`, i.e. through the **speed-band verdict**, which is a heuristic over a
 * noisy, bimodal, provider-jittered fix stream. Measured twice on a real commute (§37, §38.1): a
 * train's median leg speed landed in the cycle band, and the device log read *"rail sweep: 0
 * candidate(s) of 15 trace(s)"*. The strongest signal available sat behind the weakest, and a trace
 * labelled `cycle` was not merely un-promoted — it was never **checked**.
 *
 * This is the physical test that replaces the modal one: not *"what do we think this was?"* but
 * *"could a body have done this?"*
 *
 * ### ⚠⚠ Speed made good, because it is the one statistic noise can only *understate*
 *
 * Every other candidate is inflatable by exactly the noise this module keeps tripping over:
 *
 * | measure | what fix noise does to it |
 * |---|---|
 * | summed path length (`pathDistanceMeters`) | **inflates** — §22 measured 129.8 km against an ≈85 km route |
 * | per-leg speed | **inflates** — error over a short `dt`; the ping-pong legs read 200 m/s |
 * | 85th-percentile leg speed (`cruiseSpeedMps`) | **inflates** — it is a high percentile *of* those legs |
 * | **net displacement ÷ elapsed time** | **understates** — bounded by the two endpoint errors, and they are metres against kilometres |
 *
 * So the admission test uses the conservative one. A trace can fail this and still be a train (a
 * short hop, a leg that ends where it began); it cannot pass it by being noisy. That asymmetry is
 * the whole design — this gate widens who may be *asked*, so it must never be the thing that lies.
 *
 * ⚠ **Extent is deliberately not used here**, though `toCapturedMoveOrNull` uses it and for a good
 * reason (§18.3: an out-and-back walk finishes where it started, so a displacement gate would delete
 * genuine trips). That argument is about *recording a trip at all*. This asks the opposite question —
 * *did you get a long way from where you started, fast* — and for that, going and staying gone is
 * exactly the evidence wanted. A loop is not a train journey.
 *
 * ### ⚠⚠ This admits; it never concludes
 *
 * Clearing this bar buys a trace one thing: the map is allowed to look at it. Promotion still needs
 * `RailMatcher` coverage, and the coverage tiers still demote. **Both conjuncts are load-bearing**,
 * and the second is what makes the first safe: `RailMatcher.DEFAULT_TOLERANCE_M` is 50 m, so a
 * cycle path running beside a railway *does* match track — proximity alone has never been evidence
 * about a cyclist, which is precisely why `RailRefinement` treats a pedestrian baseline as
 * ineligible. What separates the two here is not where they were but how fast they got there.
 */
internal object VehicularKinematics {

    /**
     * Minimum speed made good (m/s ≈ 36 km/h) for a path to be admitted on kinematics alone.
     *
     * **Reasoned, not fitted.** It is a claim about bodies, in the same spirit as
     * [LegMode.MAX_ON_FOOT_STRIDE_M]. Sustained *point-to-point* 36 km/h is far outside cycling: an
     * EU e-bike cuts assistance at 25 km/h, a commuting cyclist makes good 12–20 km/h once roads
     * bend, and even a racing cyclist's hour record is set on a track rather than between two places
     * kilometres apart. Trains on the measured commute made good 40–53 km/h on every leg.
     *
     * ⚠ The cost, deliberately accepted: slow urban rail — trams, congested light rail — does not
     * clear this and is not admitted on kinematics. That is not a regression; T0 already excludes it
     * for the same reason ([SHAPE_RULE_MIN_CRUISE_MPS] is 20 m/s), and such a trace still reaches
     * the sweep by the ordinary `drive`/`transit` route whenever the speed bands get it right.
     */
    const val MIN_MADE_GOOD_MPS = 10.0

    /**
     * Minimum net displacement (metres) before speed made good means anything.
     *
     * ⚠ Guards the ratio's denominator problem from the other end: over a few hundred metres the two
     * endpoint errors are a large fraction of the displacement, so the quotient is noise. At 3 km
     * they are a rounding error — a ±300 m coarse fix at each end moves the result by ~20 %, not by
     * an order of magnitude — and no accumulation of jitter manufactures 3 km of *separation*.
     *
     * ⚠ It also sets the floor on what this tier can rescue: a 2 km surface hop is not admitted on
     * kinematics. Missing a promotion leaves a speed label; making a wrong one is a confident lie
     * (§13's standing posture), and this bar is on the conservative side of that trade.
     */
    const val MIN_NET_DISPLACEMENT_M = 3_000.0

    /**
     * `true` when [fixes] went far enough, fast enough, that no unaided human could have.
     *
     * ⚠ An empty or single-fix path answers `false`, which matters more than it looks:
     * `RailRefinement.refine` takes `fixes` with an `emptyList()` default, so every caller that does
     * not supply them keeps exactly its previous behaviour rather than silently gaining a new one.
     */
    fun isVehicular(fixes: List<CapturedFix>): Boolean {
        if (fixes.size < 2) return false
        val first = fixes.first()
        val last = fixes.last()
        val elapsedSec = (last.timestampMs - first.timestampMs) / 1000.0
        if (elapsedSec <= 0.0) return false
        val displacementM = capturedDistanceMeters(first, last)
        if (displacementM < MIN_NET_DISPLACEMENT_M) return false
        return displacementM / elapsedSec >= MIN_MADE_GOOD_MPS
    }
}
