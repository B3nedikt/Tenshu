package app.tenshu.timeline.data.capture

/*
 * The movement classifier's version stamps (`ModeClassification.version` / `MovementTrace.classifierVersion`).
 *
 * ⚠ Moved verbatim from `CaptureModels.kt` and `RailRefinement.kt` in `:timeline-shared-data` — same
 * package, so no reader's import changed — because the feed projection reads them (the §32.12
 * underground carry, the §32.8 rail-refined evidence) and the projection now lives here, compiled
 * for JS too (`timeline-app.md` §Web scope 4a). Capture still writes them; this module only declares.
 */

// ── from CaptureModels.kt ──

/**
 * The version stamped onto every classified trace. **Bump this whenever [classifyMode]'s thresholds
 * or algorithm change** — it is what makes a later re-classification pass able to tell which rows
 * still carry an old verdict (§12.1, §13.3).
 *
 * - `1` — median-leg-speed buckets (walk/run/cycle/drive), the M1 heuristic.
 * - `3` — adds the airborne band: a cruise above `IMPLAUSIBLE_GROUND_SPEED_MPS` now classifies as
 *   [MovementMode.FLIGHT] instead of being left as the baseline `drive`. Only affects traces above
 *   that speed, so a v2 row's ground verdict stands under v3.
 * - `2` — adds the T0 kinematic transit tier (`TransitKinematics`, §13.3): a `drive` verdict may be
 *   promoted to `transit` on turn density + station-like stop spacing, or a rail-only cruise speed.
 *   Buckets 1–3 are unchanged, so a v1 row's walk/run/cycle label is still correct under v2 — only
 *   its `drive` rows are candidates for re-labelling by a sweep.
 * - `4` — **the sampling regime changed, not the rule** (S1, §17.2). `MODE_BOUNDARIES` are applied to
 *   the median of *per-leg* speeds, and a leg's speed is straight-line **displacement** over the
 *   interval, which under-reports on any non-straight path in proportion to that interval. Adaptive
 *   sampling shortens the interval (to 30 s on foot, 10 s in a vehicle where the platform batches),
 *   so **every measured median shifts upward**, most on the twistiest paths. Nothing about
 *   [classifyMode] itself moved; what moved is the population its thresholds are compared against,
 *   and a version is the only thing that lets a later pass tell the two regimes apart.
 *   ⚠⚠ **`MODE_BOUNDARIES` were deliberately NOT re-tuned here.** Fitting them honestly needs the T3
 *   corpus (a real Google Takeout import against known modes), which does not exist yet; guessing new
 *   numbers would be strictly worse than a documented, distinguishable status quo.
 * - `6` — **`confidenceOf`'s top bucket was one-sided all along** (§32.4-3). `MODE_BOUNDARIES` is
 *   open-ended above 8 m/s, but the margin term measured the median against a *synthesised* far edge
 *   at `last * 2`, so past 16 m/s (≈ 58 km/h) the margin fell again and coerced to **0** — every
 *   motorway drive and every train reported no confidence at all. The **mode verdict is unchanged**,
 *   so a v4 row's bucket is still correct under v6; what moved is the confidence beside it. ⚠⚠ That
 *   is precisely why this needs a version: v4's `confidence = 0` on a fast leg means *"the formula
 *   broke"*, v6's means *"genuinely on a boundary"*, and `TransitAdjacency` (§31) gates on exactly
 *   that number. ⚠ It cannot be swept retroactively — confidence is derived from the raw fixes and
 *   only the decimated polyline is stored — so old rows keep their 0 and the version is the only
 *   thing that says not to trust it.
 * - `8` — **the path's two terminal fixes are now kept regardless of accuracy** (§32.6,
 *   `FixHygiene.geometryPath`). Like `4`, this moves the *population* the thresholds are compared
 *   against rather than the thresholds themselves: a leg that ended on a coarse fix now reaches where
 *   it actually ended, so `distanceM` grows and the median gains a leg — a v8 verdict is therefore
 *   not comparable with a v6 one on any journey whose ends were coarse. ⚠ Under v6 whole underground
 *   hops were missing from the distance entirely (the reported U5 ride recorded 1223 m of 4.2 km),
 *   so this is a correction rather than a drift.
 * - `9` — **the T0 stop detector stopped depending on sampling phase** (§32.11). A station stop was
 *   only counted when two consecutive fixes both landed inside it, so at the delivered ~60 s cadence
 *   an identical ride promoted at one sampling offset in six. The bar is now the looser of a
 *   standstill and half the trace's own cruise. ⚠ Promotion-only, as the whole tier is, so a v8
 *   `transit` stays `transit`; what moves is which `drive` rows become `transit`.
 * - `13` — **turn density's bearing bar now scales with the trace's own fix accuracy** (§32.15,
 *   [bearingLegBarFor]). A fixed 30 m bar sat *below* the noise floor of a real train capture (median
 *   accuracy 64 m), and because turn density sums **absolute** heading changes, noise can only inflate
 *   it — so the estimator was one-sided and every poor-fix journey drifted toward "road-like".
 *   Measured on a 79 km regional train: 54.7 °/km against a ≤ 25 bar, with every other conjunct
 *   passing (105 km/h cruise, 10 well-spaced stops). ⚠ Promotion-only like `9`: a v9 `transit` stays
 *   `transit`, and what moves is which `drive` rows become `transit`.
 * - `14` — **the per-leg speeds are reduced with a time-weighted median** (§37,
 *   `timeWeightedMedianOrNull`). One leg was one vote whatever span it stood for, so the statistic
 *   described the providers' delivery pattern rather than the journey — and on the bimodal profile of
 *   a stopping train (standing, or 60–160 km/h, nothing between) the median landed in the *empty
 *   valley*, which is the cycle band. ⚠⚠ **The first bump on this line that is not promotion-only**:
 *   `9` and `13` moved which `drive` rows become `transit`, this one moves which *bucket* a trace
 *   lands in at all, so a v14 verdict is not comparable with a v13 one on any journey whose legs were
 *   unevenly spaced. Measured on a 44.3 km regional train (2026-08-31): 12 of 33 legs below 0.6 m/s,
 *   12 above 16, **2** inside 4–8 — verdict `cycle` at confidence 9, and because `cycle` is neither
 *   `drive` nor `transit` the trace was refused by `refineWithTransitKinematics`, by
 *   `RailRefinement.needsRefinement` (the device log reads *"rail sweep: 0 candidate(s) of 15
 *   trace(s)"*) and by `collapseTransitStationStops` alike. One statistic, three closed doors.
 *
 * ### ⚠⚠ Why this jumped 9 → 13 rather than 9 → 10
 *
 * The tier stamps below used to sit at 10 / 11 / 12, immediately above this line, so there was **no
 * free slot** — 9 → 10 would have collided with `MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND` and silently
 * reinterpreted every stored row carrying it. The tier block moved up to 20 / 21 / 22 and this line
 * moved to 13, leaving 13–19 free for the next bump — of which `14` is now taken and 15–19 remain.
 *
 * ⚠ The retired stamps **10 / 11 / 12 / 22** are never reused, so the numbering history in the docs
 * stays unambiguous. No stored row carries them: the 1.0.1 re-cut wiped every store, so nothing reads
 * them either.
 */
const val MOVEMENT_CLASSIFIER_VERSION: Int = 14

// ── from CaptureModels.kt ──

/**
 * [ModeClassification.version] for a leg promoted to transit at **capture** by [UndergroundHop] (§27)
 * — a metro hop, recognised from the fix stream alone before any map tile is available.
 *
 * ⚠ Distinct from [MOVEMENT_CLASSIFIER_VERSION] so `RailRefinement` can tell "the speed bands said
 * this" from "the blind-interval evidence said this", and revisit only the latter with tiles. It sits
 * **below** `MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED` deliberately: a capture-time promotion is a
 * candidate the tile tier has not seen yet, so the sweep must still pick it up and may still demote
 * it (the M9-3 rule that the map tier is the one allowed to demote).
 *
 * ⚠ Moved 5 → 7 with the v6 confidence fix and 7 → 9 with the v8 terminal-fix fix, to keep the tiers
 * in the order they actually apply in. Moved 9 → 10 for §32.7, which changed **this tier's own rule**
 * (a leg must now be pinned itself, not merely sit inside a pinned span). Moved again 10 → **20** for
 * §32.15 — a pure renumber that changes nothing about this tier, made because the classifier line
 * below had run out of slots (see [MOVEMENT_CLASSIFIER_VERSION]). The block now starts at 20 with the
 * whole 13–19 range free beneath it, so the next classifier bump does not force this one to move.
 *
 * The invariant, pinned by [MovementClassifierVersionTest]:
 * `EXTERNAL < capture < underground-promotion < rail-refined < rail-refined-underground`.
 *
 * ⚠ Retired values are deliberately **not reused** by any current constant (see
 * [MOVEMENT_CLASSIFIER_VERSION]).
 */
const val MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND: Int = 20

// ── from RailRefinement.kt ──

/**
 * The classifier version stamped by a trace that has been through rail refinement.
 *
 * Distinct from [MOVEMENT_CLASSIFIER_VERSION] (what the capture-time classifier stamps) so a sweep
 * can find un-refined traces by version alone: anything below this and not [MOVEMENT_CLASSIFIER_EXTERNAL]
 * is a candidate.
 *
 * **Bump this when the rail-refinement logic materially changes** — every already-refined trace then
 * becomes a candidate again and is re-evaluated with the new logic. This value re-runs everything the
 * older readers had judged, across the whole §3 fix chain: the `transit`-only reader that found no
 * rail in the production basemap (real trains left un-promoted), the whole-route-bbox read that
 * clipped long journeys and *falsely demoted* them, and the segment-by-segment survey that keeps a
 * long route at full z14 precision. See `timeline-recognition-and-place-naming.md` §3.
 *
 * `11` — **S8** (§25): the dark-leg tier generalises to [BlindIntervalDetector], which recognises the
 * *covered* metro (GPS stops, NETWORK continues) that S4 could not see, and the tier may now speak
 * about a `walk`/`run`/`cycle` baseline. Both widen what an already-refined trace would conclude.
 *
 * `10` — **S4** (§20): rail fragments split by tile boundaries are stitched before matching (so the
 * snapper's same-line walk crosses a tile edge), and the underground **dark-leg** tier lands. Both
 * change what an already-refined trace would now conclude, so every one has to be re-offered — which
 * is exactly why this constant is a version and not a boolean.
 *
 * `9` — **B4 route snapping** (`timeline-movement-capture.md` §14). The mode verdict is unchanged;
 * what changed is that the sweep now also writes a `SnappedRoute` for a trace it promotes on
 * exclusive track. Every already-refined trace has to be re-offered, or the correction would only
 * ever reach journeys recorded *after* this shipped — which is the whole reason this constant is a
 * version and not a boolean.
 *
 * `23` — **§39**: the long-haul promote route ([LONG_HAUL_EXCLUSIVE_METERS]). It changes what an
 * already-refined trace would conclude *and* specifically targets traces this tier previously
 * **demoted**, so re-offering every one of them is the whole point of the bump — a `drive` verdict
 * written under the old rule is exactly what needs re-deciding.
 *
 * ⚠ Moved 11 → **21** for §32.15 — a pure renumber to give the classifier line beneath it room; the
 * tier's own rule is unchanged.
 */
const val MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED: Int = 23

// ── from RailRefinement.kt ──

/**
 * As [MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED], but the tier positively established the leg ran
 * **underground** — its blind interval's endpoints sit on subway alignment (§32.12).
 *
 * ⚠⚠ Both verdicts are `transit`, and until now both were stamped `11`, so the sweep *erased* the one
 * fact that separates a metro hop from the surface train it connects to. `wentUnderground` is computed
 * from [BlindIntervalDetector.anchoredToSubway] — the tier already knows; it simply did not write it
 * down. That is what `collapseTransitStationStops` needs to tell a **transfer** (train → U-Bahn, two
 * rows) from a **pass-through station stop** (train → same train, one row).
 *
 * ⚠ Above [MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED] so `needsRefinement` still reads it as swept and
 * never re-offers it; the two sort together as "the map tier has spoken".
 *
 * ⚠ Deliberately **not** a new `SnappedRoute` field. The matched kinds are richer there
 * (`kinds=[subway, rail]`), but persisting them reshapes a stored composite — a service-version bump,
 * a migration, and a second read joined onto the feed path. `classifierVersion` is already on the
 * trace row the projector reads, and carries exactly the one bit needed.
 *
 * ### ⚠⚠ §32.15: this was unreachable from a sweep, which is where it was needed
 *
 * `wentUnderground` is recomputed from the fixes handed to [RailRefinement.refine], and the sweep
 * hands it the **decimated stored polyline** — 6 points for the reported U5 ride. By §27's own
 * argument the blind-interval evidence *cannot exist there*: the platform fixes are dropped as bad
 * geometry and Douglas–Peucker removes most of what survives. So the branch that stamps this constant
 * could never fire from a sweep, and the sweep fell through to the coverage branch and stamped plain
 * `RAIL_REFINED` — **erasing the capture-time verdict it could not re-derive**.
 *
 * Measured on the 2026-08-13 journey: capture stamped the U5 correctly (underground), the sweep
 * re-stamped it `11`, and `TimelineProjection.ranUndergroundOf` — which accepts only the underground
 * stamps — then read `false`. The interchange survived only because the connecting train was
 * misclassified `drive`; once §32.15's turn-density fix makes that leg `transit` too, both legs read
 * the same and `isVehicleChange` folds the München Ost row away again. The two fixes are coupled and
 * have to land together.
 *
 * ⚠ The tier may still **demote**: [RailRefinement.refine] only carries the fact forward on branches
 * whose verdict is still `transit`. A leg the map positively walks back to `drive` is stamped plain
 * `RAIL_REFINED`, which is M9-3 ("the map tier is the one allowed to demote") intact.
 *
 * ⚠ Moved 12 → **22** for §32.15, and 22 → **24** with §39's bump of the constant above — it has to
 * stay over [MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED] or a swept metro leg reads as un-swept.
 */
const val MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND: Int = 24
