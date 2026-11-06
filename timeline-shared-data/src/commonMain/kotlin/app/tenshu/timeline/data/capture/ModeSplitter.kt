package app.tenshu.timeline.data.capture

/**
 * **S2 — split a leg where the mode changes, not only where the user stops**
 * (`timeline-movement-capture.md` §17.3/§19).
 *
 * The report that opened this: *train → U-Bahn → walk* recorded as **one row**. `MovementSegmenter`
 * closes a leg only on a ≥5 min dwell, on `maxOpenMove`, or at `onEnd` — and a cross-platform change
 * is under two minutes — so three modes were averaged into a single median-speed verdict. That is
 * also why §16's snapper could never fire on the journey it was written for: `MIN_EXCLUSIVE_COVERAGE`
 * is measured over the *whole* path, and a path that is half rail and a quarter pavement cannot clear
 * it. **Leg splitting is the precondition for the per-leg substitution §16.5 already built.**
 *
 * ### ⚠⚠ A pure post-pass at the flush boundary, never in the online state machine
 *
 * A mode change is only visible in retrospect — you cannot classify a leg that has not finished — and
 * `MovementSegmenter`'s streaming state (journal recovery, dwell confirmation, content addressing) is
 * the hardest-won correctness in this file. So the segmenter's job is unchanged: buffer the path,
 * decide *when* to flush. This decides *how many moves* that flush produces, as a pure function of an
 * ordered path, unit-testable without a `Flow`, a clock, or a device.
 *
 * Everything downstream composes for free: each run is content-addressed by its own `startMs`
 * (`cap-move:$startMs`), so recovery and idempotence are untouched, and each becomes a
 * `MovementTrace` the rail sweep can refine, promote and snap **independently**.
 *
 * ### The guards, all of them vetoes that fail toward the status quo
 *
 * Today's behaviour is a coarse row; the failure mode of over-splitting is a shredded journey, which
 * is worse and far less recoverable. So every rule here is biased against cutting:
 *
 *  - **Hysteresis** ([MovementDetectionConfig.modeChangeHysteresisLegs]) — a change must persist for N
 *    consecutive voting legs. A train slowing into a station is not a cycle; a bus in traffic is not
 *    a walk.
 *  - **Slow legs do not vote** ([LegMode.of] returns `null` below its floor) — a red light cannot
 *    manufacture a boundary.
 *  - **Minimum leg** — a run that fails the ordinary §3 thresholds is merged back into its neighbour
 *    rather than dropped. The whole path stays accounted for; splitting never *loses* distance.
 *  - **One run means no split** — the fall-through is byte-identical to pre-S2 behaviour.
 *
 * ### ⚠⚠ Since §34.2 there are two detectors, not one rule with an extra case
 *
 * A leg that abstains on speed is exactly what an underground ride looks like, so [pinnedBoundaries]
 * finds the boundary [speedBoundaries] cannot see. They run **side by side** and their boundaries are
 * unioned; neither can move or suppress the other's. See [boundaryIndices] for why that separation is
 * load-bearing rather than tidiness.
 */
internal object ModeSplitter {

    /**
     * Cut [path] into contiguous runs of one travel mode.
     *
     * ⚠ Runs **share their boundary fix** — the last fix of one run is the first of the next — so
     * summed distance across the runs equals the distance of the whole path. Cutting between fixes
     * instead would silently delete the boundary leg from the journey.
     *
     * Returns a single-element list (the input unchanged) whenever splitting is off, the path is too
     * short to hold two viable runs, or no boundary survives the guards.
     */
    fun split(
        path: List<CapturedFix>,
        config: MovementDetectionConfig = MovementDetectionConfig(),
    ): List<List<CapturedFix>> {
        if (!config.splitOnModeChange) return listOf(path)
        // Two viable runs need at least 2×3 fixes, and they share a boundary ⇒ 5.
        if (path.size < 5) return listOf(path)

        val boundaries = boundaryIndices(path, config)
        if (boundaries.isEmpty()) return listOf(path)

        val runs = absorbBrakingRuns(sliceAt(path, boundaries))
        val merged = mergeUnviable(runs, config)
        return if (merged.size <= 1) listOf(path) else merged
    }

    /**
     * Every fix index at which this path should be cut, from **both** detectors, in order.
     *
     * ⚠⚠ The two are separate passes over one shared reading of the path, and are never merged into a
     * single vote sequence. Folding "pinned" in as a third **mode** was tried and measured worse: a
     * pinned leg is one that *abstains* on speed, so a pinned vote puts an entry into the hysteresis
     * sequence where there was none, breaking the runs of `drive` and `walk` votes the speed rule
     * counts. On this corpus that cost an 08-09 drive 1.5 km to the walk before it and merged a run
     * and a cycle leg into one row — boundaries §34 has no business touching.
     *
     * What a pinned leg does instead is **abstain**, exactly as a too-slow leg does: the sequence is
     * *filtered*, never extended, and an abstention removed from a sequence cannot break a run the way
     * a foreign vote inserted into one can.
     */
    private fun boundaryIndices(path: List<CapturedFix>, config: MovementDetectionConfig): List<Int> {
        val legs = LegMode.legsOf(path)
        val pinned = if (config.splitOnPinnedAnchor) pinnedFlags(path, legs) else legs.map { false }
        return (speedBoundaries(legs, pinned, config) + pinnedBoundaries(legs, pinned, config))
            .distinct()
            .sorted()
    }

    /**
     * Is each leg part of a stretch that is **blind, pinned, and going somewhere**? Computed once;
     * both detectors read it, and that sharing is deliberate — see below.
     *
     * ### ⚠⚠ Standing still is also pinned, so the anchor test alone is not the predicate
     *
     * "Pinned" means *the anchor is not moving*, which a tunnel and a coffee break satisfy equally.
     * [UndergroundHop.promotes] separates them with [BlindIntervalDetector] rather than with the anchor
     * test, and so must this: the boundary must land inside a [BlindIntervalDetector.Kind.COARSE]
     * interval — a blackout that demonstrably *travelled*, ≥ 800 m at a metro's pace.
     *
     * ⚠⚠ Without that conjunct the failure is not a spare row, it is a **deleted** one. Measured on an
     * 08-09 road trip, a stop with no sky view opened a boundary 2.7 minutes into a real 594 m walk;
     * both halves then fell under the §3 thresholds, [mergeUnviable] folded them into the drives either
     * side, and the walk vanished from the day. A leg that was never going to be promoted can only lose
     * by being cut.
     *
     * ⚠ Both consumers read the *same* flag on purpose. The abstention in [speedBoundaries] exists
     * because inside a blackout a leg's speed is a cell-handoff artefact; outside one it is an ordinary
     * reading and must keep its vote. Gating only the boundary and not the abstention was measured too
     * — the 08-09 stop then silenced the drive votes that ended that walk, and it grew from 594 m to
     * 2 149 m by swallowing 1.5 km of motorway.
     *
     * ⚠ The third conjunct, [hasSurfaceTravelBefore], is there for a different failure — a flush with
     * nothing but blackout in it — and carries its own reasoning.
     */
    private fun pinnedFlags(path: List<CapturedFix>, legs: List<LegMode.Leg>): List<Boolean> {
        val blind = BlindIntervalDetector.detect(path, minFineFixShare = 0.0)
            .filter { it.kind == BlindIntervalDetector.Kind.COARSE && isMetroPaced(path, it) }
        var from = 0
        return legs.map { leg ->
            val pinned = hasSurfaceTravelBefore(path, leg.index) &&
                blind.any { leg.index >= it.fromIndex && leg.index <= it.toIndex } &&
                UndergroundHop.legIsPinned(
                    legFixes = path.subList(from, leg.index + 1),
                    window = pinnedWindow(path, leg.index),
                )
            from = leg.index
            pinned
        }
    }

    /**
     * Could a metro have covered this blackout **end to end**, or is it too fast to be one? (§34.5)
     *
     * ### ⚠⚠ Why the interval's own `impliedSpeedMps` is the wrong number to ask
     *
     * [BlindIntervalDetector.BlindInterval.impliedSpeedMps] describes the *travelling sub-span* —
     * `travellingSpan` searches for the **longest stretch inside 4–15 m/s** and reports that. For
     * [UndergroundHop.promotes] that trimming is right and load-bearing: an 8-minute platform wait
     * sits inside the blackout, and measuring across it put a real ride under the walk floor (§27).
     *
     * ⚠⚠ But the same trimming makes the reading useless as a *veto*, and its own KDoc says so —
     * *"a fast blackout that happens to end in a stop can still offer one that averages into the
     * band"*. Measured on the 2026-08-15 Mühldorf → München Ost train, the blackout at 07:39:15:
     *
     * | reading | value | verdict |
     * |---|---|---|
     * | the sub-span `travellingSpan` selected | 1 082 m @ **12.9 m/s** | in band ⚠ |
     * | the interval **end to end** | 7 528 m in 339 s = **22.2 m/s** | 80 km/h — no metro |
     *
     * So a train held on one cell mast for four minutes read *blind and pinned*, §34.2 cut the ride,
     * and both halves — too short to show `TransitKinematics` a train — classified `drive`. The
     * journey rendered as `Drive · 9.4 km` + `Transit · 69.6 km`, and the read-time fold cannot join
     * across a `drive`.
     *
     * ⚠ End-to-end is the honest test **here** and only here, because a boundary is a claim about the
     * *whole* stretch. It is deliberately the same [BlindIntervalDetector.MAX_METRO_SPEED_MPS] the
     * promotion tier uses, so the splitter cannot cut where that tier would refuse — asked of a span
     * a wait can only make *slower*, never faster, so no wait can smuggle a train past it.
     */
    private fun isMetroPaced(path: List<CapturedFix>, interval: BlindIntervalDetector.BlindInterval): Boolean {
        val seconds = (path[interval.toIndex].timestampMs - path[interval.fromIndex].timestampMs) / 1000.0
        if (seconds <= 0) return false
        val metres = capturedDistanceMeters(path[interval.fromIndex], path[interval.toIndex])
        return metres / seconds <= BlindIntervalDetector.MAX_METRO_SPEED_MPS
    }

    /**
     * Is there anything on this path **before** [index] that a boundary there would separate — that
     * is, a fix taken where GPS could still see the sky?
     *
     * ⚠⚠ The boundary [pinnedBoundaries] opens means *"the surface travel before the ride ends here"*.
     * When the whole path up to this point is already blind there is no surface travel in it: the near
     * side is the ride's own first half, and cutting it off subtracts rather than separates. Measured
     * on an 08-11 hop that began the moment the platform dwell closed — every fix coarse from the
     * first — the only pinned run started four minutes in, and cutting there turned a correctly
     * promoted `Transit · 2.8 km` into `Drive · 3.0 km` + `Walk · 645 m`.
     *
     * ⚠ Refusing leaves pre-§34 behaviour exactly: §27 still promotes the leg whole, as it did before.
     * That is the right failure — §27's posture throughout is that a missed split leaves an honest
     * coarse row while a wrong one is a confident lie.
     */
    private fun hasSurfaceTravelBefore(path: List<CapturedFix>, index: Int): Boolean =
        (0 until index).any { path[it].accuracyM in 1..BlindIntervalDetector.FINE_ACCURACY_M }

    /**
     * Fix indices at which the mode changes for good — i.e. where a *new* mode has been voted for by
     * [MovementDetectionConfig.modeChangeHysteresisLegs] consecutive voting legs.
     *
     * ⚠ The boundary is placed at the **first** leg of the confirming run, not the last: the change
     * happened when the new behaviour started, not when we became sure of it. Placing it at the point
     * of certainty would attribute the first N legs of every new mode to the old one.
     *
     * ### ⚠⚠ A pinned leg abstains here, and that is not a courtesy to the other detector (§34.2)
     *
     * A pinned leg's speed is not a weak reading, it is a meaningless one — the fixes are one repeated
     * coordinate, and the only numbers in it come from cell handoffs. Measured mid-U5, those handoffs
     * vote `drive` at 33–91 m/s, and three in a row cut the ride four minutes in. What made that fatal
     * rather than merely untidy is what the far half then contained: **zero** displacement, every fix
     * the same pinned coordinate. It failed the §3 thresholds, [mergeUnviable] folded it back into the
     * walk before it, and the hop reassembled as exactly the one mislabelled row §34 exists to split.
     */
    private fun speedBoundaries(
        legs: List<LegMode.Leg>,
        pinned: List<Boolean>,
        config: MovementDetectionConfig,
    ): List<Int> {
        val voting = legs.filterIndexed { i, leg -> leg.mode != null && !pinned[i] }
        if (voting.size <= config.modeChangeHysteresisLegs) return emptyList()

        val boundaries = ArrayList<Int>()
        var established = voting.first().mode
        var candidate: String? = null
        var candidateRun = 0
        var candidateStartIndex = 0

        for (leg in voting) {
            val mode = leg.mode
            if (mode == established) {
                candidate = null
                candidateRun = 0
                continue
            }
            if (mode == candidate) {
                candidateRun++
            } else {
                candidate = mode
                candidateRun = 1
                candidateStartIndex = leg.index
            }
            if (candidateRun >= config.modeChangeHysteresisLegs) {
                boundaries += candidateStartIndex
                established = candidate
                candidate = null
                candidateRun = 0
            }
        }
        return boundaries
    }

    /**
     * **The second detector: where the anchor goes blind and stops moving** (§34.2).
     *
     * ### ⚠⚠ Why speed cannot open this boundary by itself
     *
     * [LegMode] votes on displacement, and underground there is none to read: the phone is pinned to
     * one cell anchor, consecutive fixes are the identical coordinate, and the leg measures 0.00 m/s —
     * which is *below* [LegMode.INDETERMINATE_SPEED_MPS], so the leg is filtered out of
     * [speedBoundaries] before its hysteresis loop ever sees it. Measured on the 2026-08-14 U5, the
     * 7½-minute ride offers the splitter **eleven consecutive legs that do not vote at all**,
     * punctuated by cell-handoff jumps that vote `drive` at 87 and 91 m/s. So the two things the speed
     * rule could see were silence and nonsense, and the walk to the platform stayed glued to the ride:
     *
     * | | boundary | what shipped |
     * |---|---|---|
     * | speed only | none inside the leg | one row, `Walk · 4.9 km` at 27 km/h |
     * | with this detector | 11:05:18, where the pinning starts | `Walk · 0.3 km` + `Transit · 4.5 km` |
     *
     * ⚠ It opens an **entry** boundary only. Leaving the pinned state is a return to ordinary
     * travel, which produces ordinary speed votes, so [speedBoundaries] already cuts there — adding a
     * second exit rule would be two detectors racing to cut the same fix.
     *
     * ⚠ The run must hold for [MovementDetectionConfig.modeChangeHysteresisLegs] legs, the same
     * hysteresis the speed rule uses. The *duration* evidence is already carried by
     * [UndergroundHop.PINNED_WINDOW_MS] — the window trails four minutes, so the first pinned leg is
     * one that has been dark and still for that long, and a 44-second station stop cannot reach it.
     *
     * ⚠ That a pinned stretch is also one that *travelled* is [pinnedFlags]' conjunct, not this
     * function's — both detectors need it, so it belongs to the shared predicate.
     *
     * ⚠ There is deliberately **no** "one boundary per blackout" rule. A cell handoff can unpin a leg
     * mid-ride and restart the run, and an 08-11 hop did lose its promotion that way — but the cause
     * was the truncated window described in [pinnedFlags], and once that interval is skipped the
     * once-per-blackout guard changed nothing anywhere in the corpus. An inert guard is one nobody can
     * test, so it is not here.
     */
    private fun pinnedBoundaries(
        legs: List<LegMode.Leg>,
        pinned: List<Boolean>,
        config: MovementDetectionConfig,
    ): List<Int> {
        val boundaries = ArrayList<Int>()
        var runStart = -1
        var runLegs = 0
        for (i in legs.indices) {
            if (!pinned[i]) {
                runStart = -1
                runLegs = 0
                continue
            }
            if (runStart < 0) {
                runStart = legs[i].index
                runLegs = 0
            }
            runLegs++
            if (runLegs == config.modeChangeHysteresisLegs) boundaries += runStart
        }
        return boundaries
    }

    /**
     * The fixes [UndergroundHop.legIsPinned] reads the anchor over — the
     * [UndergroundHop.PINNED_WINDOW_MS] of path ending at [index], and **trailing**, never centred.
     *
     * ⚠⚠ Capture has no view of the future (§27), so a window that looked forward could not run here
     * at all; this is the same constraint that makes the frozen-anchor discriminator usable online in
     * the first place. The cost is that the state is recognised a window late — which is exactly why
     * the blindness half of the test is asked of the *leg* instead, and why the boundary is placed at
     * the run's first leg rather than at the point of certainty.
     */
    private fun pinnedWindow(path: List<CapturedFix>, index: Int): List<CapturedFix> {
        val floor = path[index].timestampMs - UndergroundHop.PINNED_WINDOW_MS
        var from = index
        while (from > 0 && path[from - 1].timestampMs >= floor) from--
        return path.subList(from, index + 1)
    }

    /** Slice into runs that share their boundary fixes. */
    /**
     * Fold away a run that is a **vehicle braking**, not a leg (§35.7.3).
     *
     * ### The shape
     *
     * A train decelerating into an intermediate station spends two or three minutes below the run
     * floor. [LegMode.of]'s honest answer for "walking pace, no steps" is `WALK`, so a boundary
     * opens either side of it; [ModeCoalescer] then **correctly** refuses to re-join, because its
     * rule is that differing refined verdicts mean the cut did real work. One ride becomes four
     * rows, and every stage behaved as designed. Measured on a Mühldorf → München Ost train:
     * `drive 31 km | transit 26 km | walk 0.44 km @ 8.4 km/h steps=0 | drive 23 km`.
     *
     * ### ⚠⚠ Why this is a **boundary** rule and not a vote rule
     *
     * The obvious fix — make a zero-step sub-walking-pace leg abstain in [LegMode.of] — was
     * implemented, replayed over six real days, and **reverted** (§35.7.2). Abstention is not
     * neutral: it re-groups the hysteresis runs rather than suppressing one boundary, so removing a
     * vote can *open* a boundary elsewhere. It improved three days and split the one day the
     * pipeline already read perfectly, turning a single clean 95 km leg into three.
     *
     * ⭐ Suppressing a boundary is **monotone** — it can only ever reduce the run count — so a day
     * with nothing to absorb is byte-identical, which is the property the vote change could not
     * offer.
     *
     * ### The predicate, and why each conjunct is load-bearing
     *
     *  - **Zero steps, from a pedometer that reported.** `null` is not `0`: no pedometer means the
     *    speed-only fallback ran and this evidence does not exist. A hard zero over minutes is the
     *    positive claim that nobody walked.
     *  - **Interior only, with both neighbours vehicle-paced.** The information that resolves the
     *    leg is entirely in what surrounds it — 80 km/h either side is a train slowing down; the
     *    same leg at the *start* of a flush is someone leaving a building and must stand.
     *  - **Short.** A genuine walk between two drives — a car park, a platform change — is real and
     *    must survive. [MAX_BRAKING_MS] is deliberately below the time any of those take.
     */
    private fun absorbBrakingRuns(runs: List<List<CapturedFix>>): List<List<CapturedFix>> {
        if (runs.size < 3) return runs
        val out = ArrayList<List<CapturedFix>>(runs.size)
        var index = 0
        while (index < runs.size) {
            val previous = out.lastOrNull()
            val middle = runs[index]
            val next = runs.getOrNull(index + 1)
            if (previous != null && next != null && isAbsorbable(middle) &&
                isVehiclePaced(previous) && isVehiclePaced(next)
            ) {
                // ⚠ Runs share their boundary fix, so a join drops the duplicate — otherwise every
                // absorbed leg would add a zero-length step to the polyline it lands in.
                out[out.lastIndex] = previous + middle.drop(1) + next.drop(1)
                index += 2
            } else {
                out += middle
                index += 1
            }
        }
        return out
    }

    /**
     * A short, step-less interior run that is **part of the ride around it** — in one of two shapes.
     *
     * Both share every conjunct except the last, and the shared ones are where the safety lives: a
     * pedometer that *reported* and counted exactly zero, a span under [MAX_BRAKING_MS], and (at the
     * call site) vehicle-paced runs on both sides.
     *
     *  - **A brake** — the run is itself below the run floor (§35.7.4). A train easing into a station.
     *  - **A station stop** — the run is *fast on average* and yet [holdsAStandstill] (§36.8). This is
     *    the shape the brake rule structurally could not see: a run that contains the approach **and**
     *    the stand still, so its mean is vehicle-paced while the middle of it is a stationary train.
     *
     * ⚠⚠ The second shape is why the first one's `mean < RUN_MIN` conjunct could not simply be
     * loosened. That conjunct is what makes a *brake* a brake, and it is still doing its job; a
     * station stop is a different claim resting on different evidence, so it is a separate disjunct
     * rather than a widened threshold. Measured on the 2026-08-16 commute: a 3-minute run at a mean
     * of 8.2 m/s holding a 59 s standstill, between an 75 km/h run and an 80 km/h run.
     *
     * ⭐⭐ Still monotone, exactly as §35.7.4 argued: absorbing an interior run can only **reduce** the
     * run count, never re-group the survivors into a shape they did not already have.
     */
    private fun isAbsorbable(run: List<CapturedFix>): Boolean {
        if (run.size < 3) return false
        val durationMs = run.last().timestampMs - run.first().timestampMs
        if (durationMs !in 1..MAX_BRAKING_MS) return false
        // ⚠⚠ The INTERIOR only. [sliceAt] emits runs that share their boundary fixes with the
        // neighbours, so reading steps off the whole run lets a neighbour's reading count as this
        // run's evidence — and a cruise fix carrying `0` made a no-pedometer stretch look like a
        // hard zero. Caught by `aStretchWithNoPedometerAtAllIsUntouched`, which passed before this
        // rule existed and failed after it: the shared fix, not the run, was answering.
        val interior = run.subList(1, run.size - 1)
        val reported = interior.mapNotNull { it.stepsSinceLastFix }
        if (reported.isEmpty() || reported.sum() != 0) return false
        return meanSpeedMps(run) < LegMode.RUN_MIN_SPEED_MPS || holdsAStandstill(interior)
    }

    /**
     * Does this span hold a **standstill** — a window of at least [MIN_STANDSTILL_MS] that went
     * nowhere?
     *
     * ⚠ Measured on **displacement**, not path distance, for the reason that runs through §36: a
     * stationary phone's fixes jitter, so the summed path of a standstill is not zero and can be
     * hundreds of metres on coarse fixes. Straight-line displacement between the window's ends is the
     * one figure jitter cannot inflate.
     *
     * ⚠ [STOP_SPEED_MPS] is `TransitKinematics`' standstill bar, borrowed rather than re-spelled —
     * two different numbers here would be two different claims about the same physical fact.
     *
     * ⚠ Windows are tested **longest first** and the loop stops once they fall under the minimum,
     * which is safe because the duration is monotone in `to`.
     *
     * ⚠ `internal` so the predicate is testable against a synthetic span without reverse-engineering
     * a path that makes the splitter cut in exactly the right place — the same reason
     * [TransitEvidence] and `StopTileReader.best` are exposed.
     */
    internal fun holdsAStandstill(fixes: List<CapturedFix>): Boolean {
        for (from in fixes.indices) {
            for (to in fixes.lastIndex downTo from + 1) {
                val ms = fixes[to].timestampMs - fixes[from].timestampMs
                if (ms < MIN_STANDSTILL_MS) break
                val allowed = STOP_SPEED_MPS * (ms / 1000.0)
                if (capturedDistanceMeters(fixes[from], fixes[to]) <= allowed) return true
            }
        }
        return false
    }

    /**
     * The shortest window that counts as a standstill, in millis.
     *
     * ⚠ 45 s sits below a train's station dwell (typically 30–120 s) and above anything a moving
     * vehicle produces by accident. ⚠ It **will** also absorb a long red light between two stretches
     * of driving, and that is wanted: a traffic light splitting one drive into two rows is the same
     * defect wearing different clothes. What it cannot absorb is anyone who got *out* — that takes
     * steps, and the exactly-zero conjunct above has already refused.
     */
    private const val MIN_STANDSTILL_MS = 45_000L

    private fun isVehiclePaced(run: List<CapturedFix>): Boolean =
        run.size >= 2 && meanSpeedMps(run) >= LegMode.DRIVE_MIN_SPEED_MPS

    private fun meanSpeedMps(run: List<CapturedFix>): Double {
        val seconds = (run.last().timestampMs - run.first().timestampMs) / 1000.0
        if (seconds <= 0) return 0.0
        var metres = 0.0
        for (i in 1 until run.size) metres += capturedDistanceMeters(run[i - 1], run[i])
        return metres / seconds
    }

    /**
     * The longest a *braking* run may be, in millis.
     *
     * ⚠ Four minutes: long enough for a train easing into a station and waiting a moment, short
     * enough that a real walk between two drives — a car park, a platform change — is never eaten.
     */
    private const val MAX_BRAKING_MS = 4 * 60 * 1000L

    private fun sliceAt(path: List<CapturedFix>, boundaries: List<Int>): List<List<CapturedFix>> {
        val runs = ArrayList<List<CapturedFix>>(boundaries.size + 1)
        var start = 0
        for (b in boundaries) {
            // `b` is the fix that ENDS the first leg of the new mode, so the previous run runs up to
            // and including `b - 1`, and the new one starts at `b - 1` to keep the boundary shared.
            val cut = (b - 1).coerceIn(start + 1, path.lastIndex)
            if (cut <= start) continue
            runs += path.subList(start, cut + 1)
            start = cut
        }
        runs += path.subList(start, path.size)
        return runs
    }

    /**
     * Fold runs that could never be recorded on their own back into a neighbour.
     *
     * ⚠ Merged **forward into the previous run** where one exists, so the journey stays contiguous and
     * no fix is dropped. A leading unviable run merges into the one after it instead — there is no
     * previous run to hold it, and discarding it would silently shorten the journey's start.
     */
    private fun mergeUnviable(
        runs: List<List<CapturedFix>>,
        config: MovementDetectionConfig,
    ): List<List<CapturedFix>> {
        if (runs.size <= 1) return runs
        val out = ArrayList<List<CapturedFix>>(runs.size)
        for (run in runs) {
            val viable = run.toCapturedMoveOrNull(config) != null
            if (viable || out.isEmpty()) {
                out += run
            } else {
                // Drop the shared boundary fix when concatenating, or it would appear twice and add a
                // zero-length leg to the merged path.
                out[out.lastIndex] = out.last() + run.drop(1)
            }
        }
        // A leading unviable run survives the loop above (out was empty when it arrived); fold it
        // forward now, for the same reason its trailing counterpart folds backward.
        if (out.size > 1 && out.first().toCapturedMoveOrNull(config) == null) {
            val head = out.removeAt(0)
            out[0] = head + out[0].drop(1)
        }
        return out
    }
}
