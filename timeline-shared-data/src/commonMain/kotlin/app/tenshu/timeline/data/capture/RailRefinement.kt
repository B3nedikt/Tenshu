package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.math.roundToInt

/**
 * **T1/T2′ applied to a stored trace** (`docs/proposals/timeline-movement-capture.md` §13.3, M9-3).
 *
 * ### Why this is a separate pass, not part of `classifyMode`
 *
 * `classifyMode` is a **synchronous** function inside the streaming segmenter, while getting rail
 * geometry is **async I/O through Control's proxy** (the Timeline app has no `INTERNET`). T1
 * therefore cannot run at capture time at all. It runs later, over traces already on disk, and
 * re-stamps them — which is exactly the capability `MOVEMENT_CLASSIFIER_VERSION` was added for in
 * M6. The abstract argument for that field became a load-bearing one here.
 *
 * ### T1 may correct T0 — with one hard guard
 *
 * M8 established "promotion only": T0 never demotes, because it *infers* from shape and a false
 * "Train" is worse than a missed one. T1 is different in kind — it is **direct evidence**. A path
 * nowhere near any track is not a train, whatever its shape suggested. So T1 is allowed to demote a
 * T0 `transit` verdict back to `drive`.
 *
 * ⚠⚠ That only holds when we actually looked. **Absence of a match is meaningless if the tiles were
 * never there** — an undownloaded region, a fetch failure, a gap in coverage. Demoting on missing
 * data would silently relabel every train taken outside a downloaded region. Hence [RailEvidence]:
 * "no rail found" and "we could not look" are different values, and only the first is evidence.
 */

/**
 * What the tile layer could tell us about track near a trace.
 *
 * The distinction is the whole point: [Unavailable] makes the refinement a no-op, while
 * [Surveyed] with an empty list is a genuine, actionable "there is no track here".
 */
sealed interface RailEvidence {

    /**
     * Tiles could not be consulted — region not downloaded, fetch failed, coverage incomplete.
     * The trace is returned untouched, still at its old classifier version so a later sweep retries.
     */
    data object Unavailable : RailEvidence

    /** Tiles were read. [lines] may legitimately be empty — that means "no track here". */
    data class Surveyed(val lines: List<RailMatcher.RailLine>) : RailEvidence
}

// ⚠ MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED and MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND live in `:timeline-projection` (ClassifierVersions.kt,
// same package): the feed projection reads them and is compiled for JS (timeline-app.md §Web scope 4a).

/**
 * Coverage of track a road vehicle cannot occupy, at or above which rail is taken as established.
 *
 * ⚠ Calibrated against **real GPS traces, not synthetic ones.** The demo `RailMatchRealArchiveTest`
 * feeds a trace that hugs the rail within 12 m, which clears 0.8 trivially — but real on-device train
 * traces (urban canyons, tunnels, station approaches, ±50–100 m fixes, decimation) only reach
 * ~0.54–0.61 exclusive on heavy rail, so an 0.8 bar was unreachable in the field (two real
 * Munich–Mühldorf trains sat at 0.58 / 0.61). 0.5 sits below the noisiest real train with margin,
 * while staying a strong signal: heavy rail is grade-separated, so a *car* on it for half a long
 * journey is near-impossible — the demo found real roads hit even 0.8-exclusive only 0.83% of the
 * time. See `timeline-recognition-and-place-naming.md` §3.
 */
private const val EXCLUSIVE_PROMOTE_COVERAGE = 0.5

/**
 * The **second** way onto the exclusive-rail branch: not a higher fraction of the points, but a long
 * enough absolute run of track a road vehicle cannot occupy
 * (`timeline-movement-capture.md` §39, measured 2026-09-08).
 *
 * ### ⚠⚠ Why [EXCLUSIVE_PROMOTE_COVERAGE] could not simply be lowered
 *
 * That constant's calibration — *"0.5 sits below the noisiest real train with margin (two real
 * Munich–Mühldorf trains sat at 0.58/0.61)"* — was fitted to **two** samples. Ten runs of that same
 * journey, from the maintainer's raw-fix corpus, score
 * `0.39 0.44 0.46 0.47 0.50 0.52 0.53 0.54 0.60 0.67`: mean **0.512**, sd **0.081**. The bar
 * therefore sits **−0.15 σ from the mean of the class it exists to accept**, and 4 of those 10 runs
 * were demoted to `drive`. A threshold at the centre of the positive distribution is a coin flip by
 * construction, and the same journey scoring 0.44 one day and 0.53 the next is variance in the
 * *instrument*, not a fact about the route.
 *
 * ⚠ Lowering the fraction to fit would drag every **short** trace down with it, and short traces are
 * where the false positive lives — a car crossing a level crossing, or running beside track for a
 * few hundred metres, reaches a high fraction cheaply. So this is a second **disjunct** rather than
 * a loosened conjunct, exactly as §36.8's `isBraking` → `isAbsorbable` was: the strict rule keeps
 * deciding the cases it was right about.
 *
 * **Reasoned, not fitted.** Heavy rail and subway are grade-separated — a road vehicle cannot be on
 * them at all, let alone for kilometres. 8 km is an order of magnitude past the longest incidental
 * contact a road can make with exclusive track (a level crossing is metres; a road running parallel
 * inside the 50 m tolerance does so for hundreds of metres, not kilometres), while sitting below the
 * 9.2 km scored by the weakest genuine train leg in the corpus. ⚠ What it is **not** is measured
 * against road journeys: the corpus contains none, so the false-positive cost is reasoned from the
 * geometry rather than observed. That is the honest limit of this number.
 */
private const val LONG_HAUL_EXCLUSIVE_METERS = 8_000.0

/**
 * Floor on the coverage fraction the long-haul route still has to clear.
 *
 * ⚠⚠ Not decoration, and not a second threshold to tune. Without it, a *very* long trace could reach
 * [LONG_HAUL_EXCLUSIVE_METERS] on a coverage fraction near zero — 8 km of incidental contact spread
 * over a 500 km drive along a rail corridor — which is precisely the shape this branch must not
 * admit. 0.30 sits below the corpus minimum (0.39) with margin while still requiring that rail
 * describes a substantial part of the path rather than a coincidence within it.
 */
private const val LONG_HAUL_MIN_EXCLUSIVE_COVERAGE = 0.30

/**
 * Street-running (tram) coverage needed to promote — higher than the exclusive bar, because a car
 * driving a tram street also achieves it and only corroboration separates the two.
 */
private const val STREET_PROMOTE_COVERAGE = 0.9

object RailRefinement {

    /**
     * Re-classify [baseline] in light of rail evidence.
     *
     * Returns the input unchanged (same version) when nothing can be concluded, so a caller can
     * treat "version did not advance" as "retry later".
     *
     * @param path the trace's decoded polyline, in lon/lat.
     * @param stops positions where the vehicle came to a complete stop (T0 already finds these).
     */
    fun refine(
        baseline: ModeClassification,
        path: List<LonLat>,
        evidence: RailEvidence,
        stops: List<LonLat> = emptyList(),
        /**
         * The trace's **timestamped** fixes, for the S4 dark-leg tier (§20).
         *
         * ⚠ Separate from [path] rather than replacing it: every coverage tier is coordinate-only by
         * design, and a dark leg is a *time* fact that a lon/lat path cannot express. Empty ⇒ the
         * dark-leg tier simply does not run, so every existing caller keeps its exact behaviour.
         */
        fixes: List<CapturedFix> = emptyList(),
    ): ModeClassification {
        // A source that labelled the trace itself (Google's activityType) knows better than we do —
        // it distinguishes bus from tram from rail, which no tier of ours can. Never overwrite it.
        if (baseline.version == MOVEMENT_CLASSIFIER_EXTERNAL) return baseline

        // A flight is settled by a speed band no track can contradict.
        if (baseline.mode == MovementMode.FLIGHT || baseline.mode == MovementMode.UNKNOWN) return baseline

        // TR-1 (§38.3): "pedestrian" is a claim about the *traveller*, and the speed bands are only
        // a guess at it. A path that made good 36 km/h over kilometres was not walked or pedalled,
        // whatever bucket the median landed in — so it reaches the coverage tiers like any vehicle.
        // ⚠⚠ The SAME predicate decides candidacy in [needsRefinement]. Deriving it twice is how a
        // trace gets admitted by one rule and then refused by the other, which is a sweep that
        // fetches tiles and concludes nothing.
        val pedestrian = baseline.mode != MovementMode.DRIVE &&
            baseline.mode != MovementMode.TRANSIT &&
            !VehicularKinematics.isVehicular(fixes)

        val lines = when (evidence) {
            RailEvidence.Unavailable -> return baseline // could not look ⇒ no conclusion, retry later
            is RailEvidence.Surveyed -> evidence.lines
        }
        if (path.isEmpty()) return baseline

        // S8 (§25): the underground tier, generalised from S4's dark leg. Evaluated FIRST because it
        // is the one signal the coverage tiers structurally cannot see — a blind interval has almost
        // no credible points to cover *with*, which is its defining property. See
        // BlindIntervalDetector for why a coverage fraction is the wrong instrument here.
        val blind = BlindIntervalDetector.detect(fixes)
            .filter { BlindIntervalDetector.anchoredToSubway(fixes, it, lines) }
        // §32.15: what THIS pass can see for itself. Kept separate from [cameInUnderground] below,
        // because the two are established by different evidence and only one of them is re-derivable.
        val wentUnderground = BlindIntervalDetector.dominates(fixes, blind)
        // ⚠⚠ …and what an earlier pass already established. The sweep runs on the **decimated** stored
        // polyline, where a covered hop's evidence structurally cannot exist (§27), so recomputing
        // `wentUnderground` there answers `false` for exactly the legs the fact matters for. Capture is
        // the only tier that can ever see it; this tier must therefore be allowed to *demote* the
        // verdict but never to silently *forget* it.
        val cameInUnderground = baseline.version == MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND ||
            baseline.version == MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND
        // The stamp for a branch whose verdict is still `transit`. A demotion to `drive` deliberately
        // does NOT use this — being walked back is the map tier saying the underground claim was wrong.
        val transitVersion = if (wentUnderground || cameInUnderground) {
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND
        } else {
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED
        }

        // ⚠⚠ A pedestrian baseline may be corrected by this tier and by NOTHING else. The coverage
        // tiers rest on proximity, and "a walk beside a railway is still a walk" holds absolutely —
        // being near track is not evidence about a walker.
        // ⚠ TR-1 narrowed who counts as pedestrian, and did NOT weaken this rule: a trace only
        // escapes it by having made good 36 km/h over kilometres, which is a fact about speed and
        // not about proximity. The walker beside the track is exactly as ineligible as before. A blind interval is different in kind: it
        // carries its own kinematic evidence (2.5 km in 5 minutes), which no pedestrian can produce
        // whatever they are standing next to. Measured: three short trips in the corpus were labelled
        // `walk`/`run` purely because a covered metro ride sat in their middle (§25.2).
        if (pedestrian) {
            // ⚠ Unstamped when it does not fire: `needsRefinement` admits a pedestrian trace only
            // while it has a dominating blind interval, so stamping here would confirm a verdict this
            // tier never actually examined.
            if (!wentUnderground) return baseline
            return baseline.copy(
                mode = MovementMode.TRANSIT,
                confidence = confidenceFrom(0.85, 1.0),
                version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
            )
        }

        val match = RailMatcher.match(path, lines)
        val stopsOnRail = RailMatcher.stopsOnRail(stops, lines)

        return when {
            wentUnderground ->
                baseline.copy(
                    mode = MovementMode.TRANSIT,
                    confidence = confidenceFrom(0.85, 1.0),
                    version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
                )

            // Direct evidence: the path follows track a car physically cannot use.
            // ⚠ [transitVersion], not a bare `RAIL_REFINED`: this is the branch the reported U5 ride
            // actually takes (exclusive coverage 1.0 on `[rail, subway]`), and stamping the plain
            // constant here is what erased the capture-time underground verdict (§32.15).
            match.exclusiveCoverage >= EXCLUSIVE_PROMOTE_COVERAGE ->
                baseline.copy(
                    mode = MovementMode.TRANSIT,
                    confidence = confidenceFrom(0.95, match.exclusiveCoverage),
                    version = transitVersion,
                )

            // The same evidence measured in metres rather than as a fraction of the points
            // ([LONG_HAUL_EXCLUSIVE_METERS]). ⚠ Deliberately below the branch above and not merged
            // into it: that one is the rule this tier was calibrated on, and it keeps deciding every
            // case it already decided. This one only reaches traces it would have demoted.
            match.exclusiveCoverage >= LONG_HAUL_MIN_EXCLUSIVE_COVERAGE &&
                match.exclusiveMatchedMeters >= LONG_HAUL_EXCLUSIVE_METERS ->
                baseline.copy(
                    mode = MovementMode.TRANSIT,
                    // ⚠ The weaker evidence reports itself as weaker: the coverage term is lower
                    // here by construction, so a long-haul promotion cannot present as confidently
                    // as one that cleared the strict bar.
                    confidence = confidenceFrom(0.95, match.exclusiveCoverage),
                    version = transitVersion,
                )

            // Street-running track only. A car on a tram street matches this too, so it needs
            // corroboration: either T0 independently read the shape as transit, or the vehicle
            // stopped *on* the track (T2′ — a car does not stop on tram rails repeatedly).
            match.coverage >= STREET_PROMOTE_COVERAGE &&
                (baseline.mode == MovementMode.TRANSIT || stopsOnRail >= 2) ->
                baseline.copy(
                    mode = MovementMode.TRANSIT,
                    confidence = confidenceFrom(0.7, match.coverage),
                    version = transitVersion,
                )

            // We looked, and the path does not follow track. T0's shape-based guess is overruled —
            // this is the one place a verdict is walked back, and it is safe precisely because
            // `Unavailable` was handled above.
            baseline.mode == MovementMode.TRANSIT ->
                baseline.copy(
                    mode = MovementMode.DRIVE,
                    confidence = confidenceFrom(0.8, 1.0 - match.coverage),
                    version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED,
                )

            // A drive that is not on rail: unchanged verdict, but now *confirmed* rather than
            // merely un-promoted — so it is stamped, and a later sweep will not revisit it.
            else -> baseline.copy(version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED)
        }
    }

    /**
     * Does this trace still need the rail pass, judged on the classification **alone**?
     *
     * ⚠ The modal test, kept for callers that hold no geometry. Since TR-1 (§38.3) the sweep uses the
     * two-argument overload instead, because a mode is a conclusion and gating the map on it is what
     * hid a train from it twice — see [VehicularKinematics].
     */
    fun needsRefinement(classification: ModeClassification): Boolean =
        classification.version != MOVEMENT_CLASSIFIER_EXTERNAL &&
            classification.version < MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED &&
            (classification.mode == MovementMode.DRIVE || classification.mode == MovementMode.TRANSIT)

    /**
     * As [needsRefinement], but decided on **kinematics** rather than on the mode alone — admitting a
     * trace that travelled faster than a body can ([VehicularKinematics], TR-1 §38.3) or whose middle
     * is blind (S8, §25).
     *
     * ⚠⚠ The extra conjuncts are what keep this affordable. Most traces are walks, and each candidate
     * costs a tile-fetching op; offering all of them would multiply the sweep's egress for tiers that
     * fire on a handful. Both tests need **no tiles** — they are arithmetic over fixes already
     * decoded — so the cheap half decides candidacy and only the survivors spend a fetch. That is the
     * whole reason [BlindIntervalDetector]'s map half is a separate call, and TR-1 was written to the
     * same rule rather than around it.
     *
     * ⚠⚠ **This overload is the one the sweep uses, and the fixes-free [needsRefinement] cannot
     * answer the same question.** That is not an oversight: without fixes there is nothing to measure,
     * so the single-argument form necessarily falls back to the modal test TR-1 exists to replace.
     * A caller holding fixes must use this one, or it will refuse exactly the traces §38.1 measured.
     */
    fun needsRefinement(classification: ModeClassification, fixes: List<CapturedFix>): Boolean {
        if (classification.version == MOVEMENT_CLASSIFIER_EXTERNAL) return false
        if (classification.version >= MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED) return false
        if (classification.mode == MovementMode.DRIVE || classification.mode == MovementMode.TRANSIT) return true
        if (classification.mode == MovementMode.FLIGHT || classification.mode == MovementMode.UNKNOWN) return false
        // TR-1 (§38.3): a mode is a conclusion, and gating the map on it let a bad median hide a
        // train from the one tier that could have named it. Both remaining admissions are cheap,
        // tile-free kinematics over fixes already in hand, so the affordability argument above is
        // untouched — what changed is that neither of them asks what we *think* the trace was.
        return VehicularKinematics.isVehicular(fixes) ||
            BlindIntervalDetector.dominates(fixes, BlindIntervalDetector.detect(fixes))
    }

    private fun confidenceFrom(strength: Double, evidenceStrength: Double): Int =
        (strength * evidenceStrength.coerceIn(0.0, 1.0) * 100).roundToInt().coerceIn(0, 100)
}
