package app.tenshu.fit.data

/**
 * Finds sustained walking and running in a stream of timestamped step counts — detection with
 * **no GPS at all** (`fit-automatic-activity-detection.md` §9.2, Tier 1).
 *
 * ⭐ This is the tier that serves the people the feature is actually for. Tier 2 needs Timeline
 * capturing traces; a device pass showed that where Health Connect already logs exercise
 * sessions, every trace-derived detection is correctly superseded and the feature writes nothing.
 * Tier 1's audience is the phone whose health store has **steps but no sessions** — de-Googled,
 * or simply no watch.
 *
 * ⚠⚠ **It is also what Google actually does.** Its auto-detected sessions are accelerometer- and
 * step-derived and frequently carry no route. GNSS is roughly an order of magnitude more
 * expensive than a sensor-hub pedometer, so declining to run it is the whole battery argument —
 * not an optimisation on top of one.
 *
 * ⚠ Pure, and free of any platform type. Health Connect's `StepsRecord` and HealthKit's
 * `HKQuantitySample` both reduce to [Sample]; §1.5 established that both importers already read
 * exactly this and discard it in the same expression that reads it.
 */
object StepBoutDetector {

    /** One step count over a half-open window. Both stores emit this shape. */
    data class Sample(val startMs: Long, val endMs: Long, val steps: Int)

    /**
     * A run of samples that reads as continuous walking or running.
     *
     * ⚠ There is **no distance**, and that is a fact about the input rather than an omission to
     * be filled in later. A stride-length estimate would put a number no sensor produced into a
     * health record — the same rule that keeps a promoted workout's `steps`, `avgHr` and energy
     * empty rather than guessed.
     */
    data class Bout(
        val startMs: Long,
        val endMs: Long,
        val steps: Int,
        /** `run` above [RUN_MIN_SPM], else `walk` — Timeline's vocabulary, so one promoter serves both. */
        val mode: String,
    ) {
        val durationSec: Int get() = ((endMs - startMs) / 1000L).toInt()
        val cadenceSpm: Int
            get() = if (endMs <= startMs) 0 else (steps * 60_000L / (endMs - startMs)).toInt()
    }

    data class Config(
        /**
         * Below this a window is not walking. ⚠ Deliberately low — a real walk is 90–130 spm, but
         * a bucket that straddles the start or end of one reads far lower, and the floor's job is
         * to exclude *pottering about the kitchen*, not to characterise the walk.
         */
        val minCadenceSpm: Int = 50,
        /** Above this a bout reads as running rather than walking (a walk tops out near 130). */
        val runMinSpm: Int = RUN_MIN_SPM,
        /**
         * A gap longer than this closes the bout. Sized to bridge one missing bucket, not a
         * traffic light — standing still for four minutes ends the walk.
         */
        val maxGapMs: Long = 3 * 60_000L,
        /** Shorter bouts are noise; matches the promotion floor so nothing is detected to be dropped. */
        val minDurationSec: Int = 5 * 60,
    )

    const val RUN_MIN_SPM = 140

    /**
     * ⚠ Samples are sorted and non-overlapping by contract; both stores return them per-record and
     * a step bucket cannot overlap another from the same source. Out-of-order input is sorted
     * rather than rejected — a defensive sort is cheap, and dropping data because a store paged
     * oddly would be the worse failure.
     */
    fun detect(samples: List<Sample>, config: Config = Config()): List<Bout> {
        if (samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.startMs }

        val bouts = mutableListOf<Bout>()
        var open: MutableList<Sample>? = null

        fun close() {
            val run = open ?: return
            open = null
            val startMs = run.first().startMs
            val endMs = run.last().endMs
            val steps = run.sumOf { it.steps }
            val durationSec = ((endMs - startMs) / 1000L).toInt()
            if (durationSec < config.minDurationSec) return
            val cadence = if (endMs <= startMs) 0 else (steps * 60_000L / (endMs - startMs)).toInt()
            bouts += Bout(startMs, endMs, steps, if (cadence >= config.runMinSpm) "run" else "walk")
        }

        for (sample in ordered) {
            val active = sample.cadence() >= config.minCadenceSpm
            val current = open
            if (!active) { close(); continue }
            if (current == null) { open = mutableListOf(sample); continue }
            if (sample.startMs - current.last().endMs > config.maxGapMs) {
                close()
                open = mutableListOf(sample)
            } else {
                current += sample
            }
        }
        close()
        return bouts
    }

    /**
     * A bout as the promotion path already understands it.
     *
     * ⚠ `traceId` is `step:<startMs>` — deterministic, like Timeline's `cap-trace:<startMs>`, so
     * D2's convergence holds identically: two devices detecting the same bout mint the same
     * content-addressed workout id. ⚠ The `step:` prefix keeps the two sources from ever
     * colliding on an id.
     *
     * ⚠⚠ `distanceM = null`, never an estimate. And `modeConfidence` measures **how clearly this
     * is sustained pedestrian activity** — margin above the cadence floor — not margin from the
     * walk/run boundary. The distinction is the same trap the Timeline floor fell into: a
     * boundary margin scores a brisk 130 spm walk near zero, yet the promotion decision does not
     * care whether it is a walk or a run, because both promote. A confidence must measure
     * something the decision actually depends on.
     */
    fun Bout.toDetectedMovement(): app.tenshu.fit.model.DetectedMovement =
        app.tenshu.fit.model.DetectedMovement(
            traceId = "step:$startMs",
            mode = mode,
            modeConfidence = confidenceFor(cadenceSpm),
            distanceM = null,
            startAtEpochMs = startMs,
            endAtEpochMs = endMs,
            classifierVersion = CLASSIFIER_VERSION,
        )

    /** Bumped when the bout rules change, so a re-detection can be told from a re-run. */
    const val CLASSIFIER_VERSION = 1

    /**
     * How clearly this is sustained pedestrian activity, 0–100: margin above the cadence floor,
     * saturating at a comfortable walking pace. ⚠ Not margin from the walk/run boundary — see the
     * mapper's KDoc for why that would be the wrong question.
     */
    internal fun confidenceFor(cadenceSpm: Int, floorSpm: Int = Config().minCadenceSpm): Int {
        if (cadenceSpm <= floorSpm) return 0
        val span = (CONFIDENT_SPM - floorSpm).coerceAtLeast(1)
        return ((cadenceSpm - floorSpm) * 100 / span).coerceIn(0, 100)
    }

    /** A comfortable walking pace — at or above this the bout is unambiguous. */
    private const val CONFIDENT_SPM = 100

    private fun Sample.cadence(): Int =
        if (endMs <= startMs) 0 else (steps * 60_000L / (endMs - startMs)).toInt()
}
