package app.tenshu.timeline.data.capture

/**
 * **Per-leg travel mode from speed *and* cadence** (S3, `timeline-movement-capture.md` §17.4/§19) —
 * the one rule shared by the whole-leg classifier ([classifyMode]) and the mode-change splitter
 * ([ModeSplitter]), so a journey cannot be cut on a boundary the classifier then disagrees with.
 *
 * ### Why cadence, and why not a gyroscope
 *
 * Speed alone is 1-D and the bands genuinely overlap: a fast walk against a slow run, a runner
 * against a slow cyclist. The gyroscope is the reflexive answer and the wrong instrument — it needs
 * continuous sampling plus frequency analysis at milliamps. The pedometer already runs in the sensor
 * hub, is batchable, and costs sub-milliamp.
 *
 * **The decisive fact: cycling produces no steps.** That single conjunct separates the pair speed
 * cannot — a cyclist at 5 m/s and a runner at 5 m/s are indistinguishable by GPS and unmistakable by
 * cadence.
 *
 * ### The rule
 *
 * Cadence decides **pedestrian vs wheeled**; speed then decides *within* each. That ordering is the
 * point: it puts the reliable discriminator first and lets the ambiguous one settle a question it can
 * actually answer.
 *
 * ⚠ **A slow leg votes for nothing.** Below [INDETERMINATE_SPEED_MPS] a leg carries no information —
 * a red light has zero speed and zero steps and is neither a stop nor a bike ride. Such legs return
 * `null` and *inherit* their neighbours rather than voting, which is also what stops a traffic light
 * from manufacturing a mode change in [ModeSplitter].
 *
 * ⚠ **`stepsSinceLastFix == null` is not `0`.** No pedometer ⇒ fall back to the speed-only bands,
 * i.e. exactly today's behaviour. Nothing about this rule degrades a device without the sensor.
 */
internal object LegMode {

    /** Below this (m/s) a leg is too slow to classify — it inherits rather than votes. */
    const val INDETERMINATE_SPEED_MPS = 0.6

    /**
     * At or above this cadence (steps/min) the traveller is on their own feet.
     *
     * **Reasoned, not fitted.** Walking cadence is ~100–120 spm and running ~150–190; a cyclist and a
     * passenger sit at 0 with only incidental jostle. 60 is set low deliberately — well under the
     * slowest real walk — because the failure it guards against is asymmetric: reading a walk as a
     * bike ride (steps missed, e.g. phone in a bag on a bumpy path) is a visible lie about how
     * someone travelled, while reading a bike ride as a walk is caught by the speed band above.
     */
    const val PEDESTRIAN_MIN_SPM = 60.0

    /** Pedestrian split: at or above this (m/s ≈ 9 km/h) it is a run, below it a walk. */
    const val RUN_MIN_SPEED_MPS = 2.5

    /** Wheeled split: at or above this (m/s ≈ 29 km/h) it is a vehicle, below it a bicycle. */
    const val DRIVE_MIN_SPEED_MPS = 8.0

    /**
     * The mode this leg argues for, or `null` when it argues for nothing.
     *
     * @param speedMps straight-line speed over the leg.
     * @param cadenceSpm steps/min over the leg, or `null` when no pedometer reported.
     */
    fun of(speedMps: Double, cadenceSpm: Double?): String? {
        if (speedMps < INDETERMINATE_SPEED_MPS) return null
        if (cadenceSpm == null) return speedOnly(speedMps)
        return if (cadenceSpm >= PEDESTRIAN_MIN_SPM) {
            if (speedMps >= RUN_MIN_SPEED_MPS) MovementMode.RUN else MovementMode.WALK
        } else {
            // ⚠ No steps at walking pace is NOT evidence of a vehicle — it is a phone in a bag, a
            // pedometer that missed, or a slow roll. Only the speed band can promote it, and below
            // the cycle floor the honest answer is still "walk".
            if (speedMps >= DRIVE_MIN_SPEED_MPS) MovementMode.DRIVE
            else if (speedMps >= RUN_MIN_SPEED_MPS) MovementMode.CYCLE
            else MovementMode.WALK
        }
    }

    /**
     * Downgrade a `RUN` verdict whose implied **stride** no person has (§35.8).
     *
     * ### ⚠⚠ Cadence alone calls a bicycle a run
     *
     * Measured over six commuting days: the same ride came back `run` on **6 of 6 mornings** and
     * `cycle` on **5 of 5 evenings**, at overlapping speeds — 13.8–16.6 km/h against 13.6–26.0. So
     * speed was not the discriminator; cadence was, and it was *working correctly on an input that
     * is wrong*: the pedometer counts pedalling and road vibration, ~90 steps/min outbound against
     * ~30 homebound, so only the mornings cleared [PEDESTRIAN_MIN_SPM].
     *
     * ⚠ Why the same bicycle yields three times the step rate in one direction is not answerable
     * from a fix stream — phone placement is the obvious candidate and is not recorded. This does
     * not depend on knowing.
     *
     * ### Stride is a fact about bodies, not a threshold to tune
     *
     * 90 steps/min at 15 km/h is a **2.9 m** stride; a real run at that speed is ~170 spm and
     * ~1.5 m. Over the same corpus the separation is total — genuine walks 0.61–1.47 m, genuine
     * runs 1.25 and 1.80 m, **bike rides labelled `run` 2.45–3.25 m**.
     *
     * ### ⚠⚠ Why this is applied HERE and not inside [of]
     *
     * [of] is shared with `ModeSplitter`, deliberately, so a journey is never cut on a boundary the
     * classifier then disagrees with. Putting the veto there was implemented and replayed: it fixed
     * the bike on four days and **regressed two**, absorbing an evening ride's first kilometre into
     * an 85 km drive and merging a `drive` + `walk` into a bogus 19 km/h "run". A vote change
     * re-groups the hysteresis runs — the same trap §35.7.2 recorded.
     *
     * Applied at classification time the boundaries do not move at all; only the label does, and
     * `ModeCoalescer` then joins neighbours that now agree — which is precisely the reconciliation
     * path §32.2 built for splitter-vs-classifier disagreement.
     */
    fun withoutImplausibleRun(mode: String, speedMps: Double, cadenceSpm: Double?): String {
        if (mode != MovementMode.RUN || cadenceSpm == null || cadenceSpm <= 0.0) return mode
        val strideM = speedMps / (cadenceSpm / 60.0)
        if (strideM <= MAX_ON_FOOT_STRIDE_M) return mode
        // ⚠ Withdrawing the pedestrian claim, not asserting a vehicle: the speed bands answer, and
        // they are what already label the evening ride `cycle`.
        return speedOnly(speedMps)
    }

    /**
     * The longest stride a person can be travelling on their own feet, in metres.
     *
     * ⚠ **Reasoned, not fitted**, like [PEDESTRIAN_MIN_SPM]: sustained running strides run ~1.2–2.0 m
     * and even a sprinter's peak is ~2.5 m, so 2.2 m is generous to every real runner while every
     * measured bicycle impostor sat at 2.45 m or above.
     */
    const val MAX_ON_FOOT_STRIDE_M = 2.2

    /** The pre-S3 bands, used verbatim when no pedometer reported. */
    fun speedOnly(speedMps: Double): String = when {
        speedMps < RUN_MIN_SPEED_MPS -> MovementMode.WALK
        speedMps < 4.0 -> MovementMode.RUN
        speedMps < DRIVE_MIN_SPEED_MPS -> MovementMode.CYCLE
        else -> MovementMode.DRIVE
    }

    /**
     * Minimum time base for one leg (millis) — below this, a fix pair cannot measure travel.
     *
     * ⚠⚠ **The two providers report the same moment twice.** `activeProviders()` registers GPS and
     * NETWORK independently, so a position is commonly delivered twice a second or two apart. Measured
     * over a real 2 587-fix corpus: **33 % of consecutive pairs are ≤ 4 s apart with a median
     * displacement of 0.0 m** — a leg carrying no information about travel, which nevertheless voted
     * in the median exactly as loudly as a real one. Legs are the *median* of the per-leg speeds, so a
     * near-duplicate for every genuine leg drags that median toward zero: measured on three real
     * transit legs, **60–93 % of legs came in under 0.05 m/s** and the median landed at 0.00–0.14 m/s
     * for journeys covering kilometres.
     *
     * ⚠ 8 s is read off that distribution rather than chosen: the duplicate cluster ends at ~6 s
     * (34.6 % of pairs, 0.0 m median displacement) and real motion appears from 10 s (median
     * displacement 109.6 m). 8 s sits in the empty band between them.
     *
     * ⚠⚠ This does **not** invalidate `MODE_BOUNDARIES` the way a *sampling* change would. Those
     * bounds are physical (a walk is ~1.4 m/s, a cyclist 4–8), not fitted to our medians, and the
     * duplicates were biasing measurement *away* from them. Measured against the corpus the corrected
     * figures move toward physical truth in every case: a walk 0.66 → 1.09 m/s, a 12–13 km/h bike ride
     * 0.11 → 3.41, a train 12.7 → 17.7. No trace in the corpus changes bucket in the wrong direction.
     */
    const val MIN_LEG_MS: Long = 8_000L

    /**
     * The legs of a path as `(speed, cadence)` pairs. Shared so the classifier and the splitter
     * measure identically.
     *
     * ⚠ A leg spans from its anchor to the first fix at least [MIN_LEG_MS] later — the intervening
     * fixes are **absorbed, not dropped**: the leg's displacement is measured across all of them, so
     * no distance is lost, and only the useless sub-second sub-divisions disappear.
     */
    fun legsOf(fixes: List<CapturedFix>): List<Leg> {
        val legs = ArrayList<Leg>(maxOf(0, fixes.size - 1))
        var anchor = 0
        for (i in 1 until fixes.size) {
            val dtMs = fixes[i].timestampMs - fixes[anchor].timestampMs
            if (dtMs < MIN_LEG_MS) continue
            legs += legOf(fixes, anchor, i, dtMs)
            anchor = i
        }
        // ⚠ A path shorter end-to-end than one leg's time base still has to answer *something* — the
        // honest answer is one leg measured over the longest base there is, not silence. Without this
        // a brief input would classify as UNKNOWN, which is a worse claim than an uncertain one.
        if (legs.isEmpty() && fixes.size >= 2) {
            val dtMs = fixes.last().timestampMs - fixes.first().timestampMs
            if (dtMs > 0) legs += legOf(fixes, 0, fixes.lastIndex, dtMs)
        }
        return legs
    }

    private fun legOf(fixes: List<CapturedFix>, from: Int, to: Int, dtMs: Long) = Leg(
        index = to,
        speedMps = capturedDistanceMeters(fixes[from], fixes[to]) / (dtMs / 1000.0),
        cadenceSpm = cadenceOver(fixes, from, to, dtMs),
        durationMs = dtMs,
    )

    /**
     * Steps per minute across an absorbed span, or `null` if any fix in it did not count.
     *
     * ⚠ `null` propagates rather than being read as zero. `stepsSinceLastFix` is per-fix, so a span
     * has a cadence only if every fix in it contributed one; treating a missing count as `0` would
     * manufacture the "no steps" reading that is [LegMode]'s evidence for *wheels*, and turn a walk
     * whose pedometer missed a sample into a bike ride.
     */
    private fun cadenceOver(fixes: List<CapturedFix>, from: Int, to: Int, dtMs: Long): Double? {
        if (dtMs <= 0) return null
        var steps = 0
        for (j in from + 1..to) steps += fixes[j].stepsSinceLastFix ?: return null
        return steps * 60_000.0 / dtMs
    }

    /**
     * One leg's measurements, plus the index of the fix that ends it.
     *
     * ⚠ [durationMs] is not decoration: [classifyMode] reduces the legs with a **time-weighted**
     * median (§37), so a leg has to carry the span it speaks for. It is always > 0 — [legsOf] admits
     * a leg only once [MIN_LEG_MS] has elapsed, and its short-path fallback only when `dtMs > 0`.
     */
    data class Leg(
        val index: Int,
        val speedMps: Double,
        val cadenceSpm: Double?,
        val durationMs: Long,
    ) {
        val mode: String? get() = of(speedMps, cadenceSpm)
    }
}
