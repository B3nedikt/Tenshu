package org.tenshu.inference.proxy

import kotlin.math.sqrt
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.tenshu.inference.proxy.onnx.ImagePreprocessing
import org.tenshu.inference.proxy.onnx.SiglipTextTokenizer
import org.tenshu.inference.proxy.onnx.TowerGuards
import org.tenshu.inference.proxy.onnx.UnigramVocabulary
import org.tenshu.inference.proxy.onnx.VisionModel

/** One compiled tower the preflight opened for itself. Not thread-safe; closed by its opener. */
interface PreflightTower {
    fun predict(floats: FloatArray): FloatArray

    fun predict(ids: IntArray): FloatArray

    fun close()
}

/** Opens a compiled tower **outside the backend registry**, so it works while nothing is claimed. */
interface PreflightTowers {
    /** @throws Throwable when the model will not load, or loads and is not the vision tower. */
    fun openVision(compiledPath: String): PreflightTower

    /** @throws Throwable when the model will not load, or loads and is not the text tower. */
    fun openText(compiledPath: String): PreflightTower
}

/** What the preflight reads off the phone around a probe. */
interface PreflightDevice {
    /** `ProcessInfo.thermalState`: 0 nominal, 1 fair, 2 serious, 3 critical. */
    fun thermalState(): Int

    /** How many memory warnings this process has received so far. Only ever grows. */
    fun memoryWarnings(): Long

    /** `phys_footprint`, or `null` when it cannot be read. */
    fun footprintBytes(): Long?

    /** What this process may still allocate before the system's limit, or `null` when unknown. */
    fun availableBytes(): Long?

    fun nowEpochMs(): Long
}

/**
 * What a passing probe may cost (`ios-on-device-inference.md` §7 D12 ②).
 *
 * ⚠ Changing any of these changes what a pass means: bump [PREFLIGHT_REVISION].
 */
object PreflightBudget {

    /**
     * §4.5's latency row, applied to each tower as D12 says: the stop condition is a photo
     * above 500 ms. ⚠ That row is decode **and** predict on a photograph; a probe predicts on a
     * tensor it already has, so this is the row at its most lenient. The measured phone predicts
     * in 13 ms (§9.7), and no slower phone has been measured to say where the line belongs.
     */
    const val MAX_MEDIAN_PREDICT_MS: Double = 500.0

    /** Predictions per tower: "a short fixed batch" (D12). The median of them is what is judged. */
    const val PREDICTIONS_PER_TOWER: Int = 8

    /**
     * ⚠⚠ **A PLACEHOLDER, not a measurement.** D12 wants the probe to fail when the footprint with
     * both sessions open "leaves less than the measured headroom", and no headroom was measured:
     * the footprint of a pass through the backend with both towers open is owed (D10, §9.7).
     *
     * Until then: what the process may still allocate with both towers open must cover the
     * largest pass anyone has seen, 190 MB at the peak in the harness (§9.7, a path that reuses
     * no buffer), rounded up. **Replace it** with that owed footprint plus a stated margin, taken
     * on a phone at the intended floor.
     */
    const val PLACEHOLDER_MIN_AVAILABLE_BYTES: Long = 256L * 1024 * 1024

    /** From here up a probe's failure says what the phone was doing, not what it is (D12). */
    const val THERMAL_SERIOUS: Int = 2
}

/**
 * When a probe that did not pass is tried again, and when the trying stops (D12).
 *
 * ⚠ Changing any of these changes what `Unsuitable` means: bump [PREFLIGHT_REVISION].
 */
object PreflightPolicy {

    /** D12: `Unsuitable` needs three slow or over-budget probes, on separate foregrounds. */
    const val SLOW_PROBES_FOR_UNSUITABLE: Int = 3

    /**
     * D12 gives a load error "its bound" and no number. ⚠ A choice: the same three. Past it the
     * install is reported as failed and the preflight stops until the model is installed again.
     */
    const val FAILED_PROBES_FOR_FAILED_INSTALL: Int = 3

    /**
     * Loads of one key's towers (a probe, or a prepare's first open) that the process did not
     * survive, for `Unsuitable` (D12: "one wasted download, never a slow or killed Photos";
     * §14.5). ⚠⚠ A choice, and deliberately fewer than the three above: each one is Photos
     * killed in front of the user. ⚠ Not one: a user who swipes Photos away in the seconds of a
     * load, or a crash anywhere else in the app while it runs, leaves the same record as jetsam.
     * ⚠ The phone's, like a slow probe's: an OS update is a new key and starts again at zero.
     */
    const val INTERRUPTED_LOADS_FOR_UNSUITABLE: Int = 2

    /**
     * How many times a user may press *Try again* on one key's `StoppedLoading`
     * ([AiReadiness.loadAgain], §14.5 item 0, §14.18.16 item 10). ⚠⚠ Each press is one more load
     * that may kill Photos in front of the user, so it is counted on disk, per key, and a press
     * spent is not given back by a relaunch or a removal: past it the fail is a plain `Unsuitable`
     * (D12), with no *Try again* and no download offered. ⚠ Not per foreground: every kill is a new
     * process, so a per-foreground cap bounds nothing. What starts the count again is a new key —
     * an app update (each archived build carries its own `CFBundleVersion`), an OS update, or a
     * new model.
     */
    const val LOAD_AGAIN_PRESSES: Int = 1

    /**
     * The backoff: one minute after the first probe without a verdict, doubling, an hour at most.
     * ⚠ Choices, not measurements. A retry happens only on a later foreground anyway; this keeps
     * a user who switches apps three times in ten seconds from spending all three slow probes on
     * one busy moment, and keeps a model that will not load from being loaded at every return.
     */
    const val RETRY_BASE_MS: Long = 60_000L
    const val RETRY_CAP_MS: Long = 3_600_000L

    /** How long after [PreflightAttempts.lastAtEpochMs] the next probe may start. */
    fun delayMs(attempts: PreflightAttempts): Long {
        val misses: Int = attempts.slow + attempts.failed + attempts.transient + attempts.interrupted
        if (misses <= 0) return 0
        // ⚠ The shift is capped before it is taken: 60 000 shl 40 overflows into a short wait.
        val doublings: Int = minOf(misses - 1, 16)
        return minOf(RETRY_BASE_MS shl doublings, RETRY_CAP_MS)
    }

    /** ⚠ A clock that went backwards waits no longer than one full delay from where it is now. */
    fun mayProbe(attempts: PreflightAttempts, nowEpochMs: Long): Boolean =
        nowEpochMs < attempts.lastAtEpochMs || nowEpochMs - attempts.lastAtEpochMs >= delayMs(attempts)
}

/**
 * Everything one probe observed. The judge reads nothing else.
 *
 * @param failure a tower or the vocabulary that would not load, a row that is not the recorded
 *   one, or a prediction that threw: what, and why. Nothing after it was measured.
 * @param vision the vision tower's raw pooled vectors for the probe image, one per prediction.
 * @param describes the text tower's raw vectors for the caption that describes it.
 */
open class PreflightMeasurement(
    val thermalAtStart: Int,
    val memoryWarned: Boolean,
    val failure: String? = null,
    /** With [failure]: it was met before a tower was opened. */
    val failedBeforeAnyTower: Boolean = false,
    val vision: List<FloatArray> = emptyList(),
    val describes: List<FloatArray> = emptyList(),
    val doesNot: List<FloatArray> = emptyList(),
    val visionPredictMs: List<Double> = emptyList(),
    val textPredictMs: List<Double> = emptyList(),
    val availableBytes: Long? = null,
    val footprintBytes: Long? = null,
    val footprintBeforeBytes: Long? = null,
    val thermalAtEnd: Int = thermalAtStart,
    val vocabularyMs: Double = 0.0,
    val tokeniseMs: Double = 0.0,
    val visionOpenMs: Double = 0.0,
    val textOpenMs: Double = 0.0,
) {
    /** One line for the device log: every timing D12 asks for, and both memory readings. */
    open fun summary(): String =
        "thermal $thermalAtStart->$thermalAtEnd warned=$memoryWarned vocabulary=${vocabularyMs}ms tokenise=${tokeniseMs}ms " +
            "open=${visionOpenMs}/${textOpenMs}ms predict=${PreflightJudge.median(visionPredictMs)}/${PreflightJudge.median(textPredictMs)}ms " +
            "footprint=$footprintBeforeBytes->$footprintBytes available=$availableBytes"
}

/** What one probe amounts to. */
sealed interface PreflightFinding {
    /** Fast enough, inside the budget, and the output is the model's. */
    data class Passed(val margin: Double) : PreflightFinding

    /** The output is right and the phone is too slow or too full. Counts towards `Unsuitable`. */
    data class Slow(val why: String) : PreflightFinding

    /**
     * Something would not load, or the output is wrong. Counts towards a failed install.
     *
     * @param beforeAnyTower the vocabulary did not load, or did not tokenise as the export
     *   recorded: no tower was opened. ⚠ What the prepare step reads before it compiles the
     *   towers a second time, which cures nothing of that.
     */
    data class Failed(val why: String, val beforeAnyTower: Boolean = false) : PreflightFinding

    /** Not a pass, on a phone that was hot or short of memory. Decides nothing. */
    data class Transient(val cause: Cause, val why: String) : PreflightFinding {
        /** What a screen says, as a value (§14.18, M8); [why] is for a log. */
        enum class Cause { HOT, MEMORY_PRESSURE }
    }
}

/** The rule (D12 ②), as arithmetic over a [PreflightMeasurement]. */
internal object PreflightJudge {

    fun judge(measurement: PreflightMeasurement, probe: PreflightProbe, width: Int = VisionModel.PinnedSigLip.dimensions): PreflightFinding =
        conditioned(measurement, unconditioned(measurement, probe, width))

    /**
     * [finding] as the phone's state when the probe ran lets it stand: a probe that did not pass
     * on a hot or pressured phone is transient. ⚠ Every model's judge ends here (FD7).
     */
    fun conditioned(measurement: PreflightMeasurement, finding: PreflightFinding): PreflightFinding {
        if (finding is PreflightFinding.Passed) return finding
        // ⚠⚠ What the phone was doing, not what it is: such a probe may pass, and may not fail.
        if (measurement.thermalAtStart >= PreflightBudget.THERMAL_SERIOUS) {
            return PreflightFinding.Transient(PreflightFinding.Transient.Cause.HOT, "started at thermal state ${measurement.thermalAtStart}: $finding")
        }
        if (measurement.memoryWarned) return PreflightFinding.Transient(PreflightFinding.Transient.Cause.MEMORY_PRESSURE, "ran under a memory warning: $finding")
        return finding
    }

    private fun unconditioned(measurement: PreflightMeasurement, probe: PreflightProbe, width: Int): PreflightFinding {
        measurement.failure?.let { return PreflightFinding.Failed(it, measurement.failedBeforeAnyTower) }

        // ⚠⚠ The output before the speed: a tower that answers quickly with NaN is not slow.
        val towers: List<Triple<String, List<FloatArray>, Double>> = listOf(
            Triple("vision", measurement.vision, probe.hostVisionNorm),
            Triple("text (describes)", measurement.describes, probe.hostDescribesNorm),
            Triple("text (does not)", measurement.doesNot, probe.hostDoesNotNorm),
        )
        for ((name, vectors, hostNorm) in towers) {
            if (vectors.isEmpty()) return PreflightFinding.Failed("$name: no vector")
            for (vector in vectors) {
                TowerGuards.unusableReason(vector, width)?.let { return PreflightFinding.Failed("$name: $it") }
                val norm: Double = norm(vector)
                if (norm < hostNorm / probe.normBand || norm > hostNorm * probe.normBand) {
                    return PreflightFinding.Failed("$name: a norm of $norm, the export's is $hostNorm")
                }
            }
        }
        // ⚠⚠ Every prediction's vector, not the first: a tower that answers once and then drifts
        // is ranked on its worst pairing.
        var margin: Double = Double.POSITIVE_INFINITY
        for (image in measurement.vision) {
            val describes: Double = measurement.describes.minOf { cosine(image, it) }
            val doesNot: Double = measurement.doesNot.maxOf { cosine(image, it) }
            margin = minOf(margin, describes - doesNot)
        }
        if (margin < probe.minMargin) {
            return PreflightFinding.Failed(
                "the probe pair is ranked by $margin, the export's margin is ${probe.hostMargin} and at least ${probe.minMargin} is required",
            )
        }

        val vision: Double = median(measurement.visionPredictMs)
        if (vision > PreflightBudget.MAX_MEDIAN_PREDICT_MS) return PreflightFinding.Slow("the vision tower predicts in $vision ms")
        val text: Double = median(measurement.textPredictMs)
        if (text > PreflightBudget.MAX_MEDIAN_PREDICT_MS) return PreflightFinding.Slow("the text tower predicts in $text ms")
        // ⚠ Unknown is not judged: a reading that could not be taken is not the phone's fault.
        val available: Long? = measurement.availableBytes
        if (available != null && available < PreflightBudget.PLACEHOLDER_MIN_AVAILABLE_BYTES) {
            return PreflightFinding.Slow("$available bytes left with both towers open")
        }
        return PreflightFinding.Passed(margin)
    }

    fun norm(vector: FloatArray): Double = sqrt(vector.fold(0.0) { sum, value -> sum + value.toDouble() * value })

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot / (norm(a) * norm(b))
    }

    /** ⚠ The upper median of an even count, and no reading at all is the slowest reading. */
    fun median(values: List<Double>): Double =
        if (values.isEmpty()) Double.POSITIVE_INFINITY else values.sorted()[values.size / 2]
}

/**
 * How a call to [Preflight] ended, and which model it is about.
 *
 * @param key what was probed, or looked at. `null` when there was nothing to look at.
 */
class PreflightReport(val key: PreflightKey?, val outcome: PreflightOutcome)

/**
 * How one call to [Preflight] ended.
 *
 * ⚠⚠ **Every case that says something about a model was handed to the preflight by
 * [AiReadiness]**, under its lock: what there is to probe ([AiReadiness.awaitingProbe]) and
 * what became of a probe's result ([AiReadiness.recordProbe]). None is concluded from the
 * preflight's own arithmetic on a count it read earlier.
 */
sealed interface PreflightOutcome {
    /** Nothing to probe: no prepared model without a verdict, or the readiness is retired. */
    data object NotAwaiting : PreflightOutcome

    /**
     * The store, the markers or the verdict file could not be **read**, or the probe's
     * [AttemptBegun] could not be written before it. Nothing was probed and nothing is known:
     * not *nothing to do*, and tried again at the next trigger.
     */
    data object Unreadable : PreflightOutcome

    /** This build carries no probe for the installed artifacts. No verdict, nothing counted. */
    data object NoProbe : PreflightOutcome

    /** Another probe is running. */
    data object Busy : PreflightOutcome

    /** Not yet: the backoff, or this foreground has already spent its slow probe. */
    data class Waiting(val cause: Cause, val why: String) : PreflightOutcome {
        /** Why not yet, as a value (§14.18, M8); [why] is for a log. */
        enum class Cause { NOT_IN_FOREGROUND, FOREGROUND_SPENT, BACKING_OFF }
    }

    /** The runtime was torn down under the probe. Nothing recorded. */
    data object Abandoned : PreflightOutcome

    /** Readiness applied the pass: the probed model is `Ready`. */
    data class Passed(val measurement: PreflightMeasurement) : PreflightOutcome

    /** Not a pass and not a verdict, and counted: tried again on a later foreground. */
    data class Retry(val finding: PreflightFinding, val attempts: PreflightAttempts) : PreflightOutcome

    /**
     * The probe ended and readiness took nothing from it: the model was replaced or removed
     * under it, the runtime was retired, or the result could not be written. ⚠ It says nothing
     * about the model that is installed now.
     */
    data class NotRecorded(val finding: PreflightFinding, val state: AiReadinessState) : PreflightOutcome

    /** The third slow probe: persisted, and the phone keeps its weights deletable. */
    data class Unsuitable(val finding: PreflightFinding) : PreflightOutcome

    /**
     * The model would not load, or answered wrongly, [PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL]
     * times. ⚠ Not `Unsuitable`: the state stays `AwaitingVerdict` and nothing more is probed.
     * ⚠⚠ The way out is a **removal**, and then a download. This comment promised that the
     * Models screen "offers the download again": a download of an entry that is installed whole
     * fetches nothing and commits nothing (§14.11, S3), so readiness's writer 3 never runs and
     * the count that stops the probe is never cleared (§14.18.3, row 10c).
     */
    data object InstallFailed : PreflightOutcome
}

/**
 * The one foreground that has had its slow probe: at most one slow probe is counted for each
 * foreground of the scene, however many checks run in it and whichever graph runs them.
 * ⚠ Memory. A new process is a new foreground, and its slow probe is counted: the count
 * itself is on disk, in the verdict file.
 */
class SlowProbes {
    @kotlin.concurrent.Volatile
    private var last: Long? = null

    fun spentIn(foreground: Long): Boolean = last == foreground

    fun spend(foreground: Long) {
        last = foreground
    }
}

/**
 * The runtime preflight (`ios-on-device-inference.md` §7 D12 ②, §14): what turns
 * [AiReadinessState.AwaitingVerdict] into `Ready` or `Unsuitable`, and readiness's writer 2.
 *
 * One probe covers the whole search path: the vocabulary loaded and both fixed captions
 * tokenised, both towers opened, a short batch predicted on each and timed, the vectors
 * validated and ranked against the export's recorded probe, the phone's heat and memory read.
 *
 * ⚠⚠ **It opens its own sessions, and never leaves them open.** They are opened through [towers],
 * outside the backend registry, so this runs while the backend claims nothing; and they are
 * closed before the verdict is recorded, so the backend can only open its pair after this one
 * is gone. They are not handed over: the backend keys a session by path under its own lock and
 * opens lazily, a warm reopen is 24–88 ms (§2.2), and a hand-over would be a second way into
 * that state for the sake of it. [awaitSessionsClosed] is what the backend, and a teardown,
 * wait on.
 *
 * ⚠⚠ **A failure is persisted only for what the phone is** ([PreflightJudge], [PreflightPolicy]):
 * `Unsuitable` after three slow probes on three foregrounds, each started cool; never for a hot
 * or pressured phone, a model that would not load or a wrong output.
 *
 * @param beforeLoad awaited before a tower is opened: an earlier graph of this process may still
 *   be unloading its own pair.
 */
class Preflight private constructor(
    private val readiness: AiReadiness,
    /** The model's half: what is probed, measured and judged (FD7). The rest is this class's. */
    private val prober: PreflightProber,
    private val device: PreflightDevice,
    private val beforeLoad: suspend () -> Unit,
    /** ⚠ Never the main thread: opening a tower and predicting are blocking calls into Core ML. */
    private val dispatcher: CoroutineDispatcher,
    /**
     * Which foreground has had its slow probe. ⚠ A graph binds **the process's**: a graph that
     * replaces another inside one foreground must not get that foreground's slow probe again.
     */
    private val slowProbes: SlowProbes,
    /**
     * Which foreground of the scene it is **now**, or `null` in none: asked once the towers have
     * answered, before anything is recorded. `null` here (no way to ask) records without that
     * check.
     */
    private val foregroundNow: (() -> Long?)?,
) {

    /** SigLIP's preflight, as every graph before the face pair bound it. */
    constructor(
        readiness: AiReadiness,
        towers: PreflightTowers,
        readVocabulary: (String) -> ByteArray?,
        device: PreflightDevice,
        beforeLoad: suspend () -> Unit,
        /** The probes this build carries. A test passes its own; nothing else does. */
        probes: PreflightProbeLookup = PreflightProbeLookup { key -> PreflightProbes.forKey(key) },
        time: TimeSource = TimeSource.Monotonic,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        model: VisionModel = VisionModel.PinnedSigLip,
        slowProbes: SlowProbes = SlowProbes(),
        foregroundNow: (() -> Long?)? = null,
    ) : this(
        readiness = readiness,
        prober = SiglipPreflightProber(towers, readVocabulary, device, probes, time, model),
        device = device,
        beforeLoad = beforeLoad,
        dispatcher = dispatcher,
        slowProbes = slowProbes,
        foregroundNow = foregroundNow,
    )

    companion object {
        /**
         * Another model's preflight over its own [readiness] (FD7): the same protocol, backoff,
         * attempt record and foreground rule as SigLIP's, with [prober]'s probe.
         * ⚠ [slowProbes] is per model: a face probe's slow foreground must not spend search's.
         */
        fun over(
            readiness: AiReadiness,
            prober: PreflightProber,
            device: PreflightDevice,
            beforeLoad: suspend () -> Unit,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            slowProbes: SlowProbes = SlowProbes(),
            foregroundNow: (() -> Long?)? = null,
        ): Preflight = Preflight(readiness, prober, device, beforeLoad, dispatcher, slowProbes, foregroundNow)
    }

    private val running = Mutex()
    private val holding = MutableStateFlow(false)

    /**
     * Whether a probe of this graph holds its towers now: it is **running**. For a screen
     * (§14.18): while it is, [lastReport] is the report of the attempt *before* it (a report is
     * written when an attempt ends, and never cleared when the next begins), so a screen that
     * read the report alone would say "paused" over a running probe.
     * ⚠ The value the unload and the prepare already wait on; no second copy.
     */
    val probing: StateFlow<PreflightKey?> get() = probed.asStateFlow()

    /**
     * The key of the install the running probe is of, from the moment it is known until the
     * probe lets go ([holding]). ⚠ With its key, like everything a screen joins: *a probe is
     * running* said of another install is not said of this one.
     */
    private val probed = MutableStateFlow<PreflightKey?>(null)
    private val last = MutableStateFlow<PreflightReport?>(null)

    // Under [running].
    // ⚠ No count of calls here: "a foreground" is the scene's, and arrives with each call.

    /**
     * How the last call that looked at a model ended, **and which model**: what the Models screen
     * says while a model is not `Ready`.
     *
     * ⚠⚠ Keyed. A report is about [PreflightReport.key] and nothing else; after a replacement it
     * still names the model it was about until the next call, and [outcomeFor] is how to ask.
     */
    val lastReport: StateFlow<PreflightReport?> = last.asStateFlow()

    /** The last outcome, if it is about [key]; `null` when it is about another model or none. */
    fun outcomeFor(key: PreflightKey): PreflightOutcome? = last.value?.takeIf { it.key == key }?.outcome

    /** Suspends while this preflight holds a tower open. */
    suspend fun awaitSessionsClosed() {
        holding.first { !it }
    }

    /**
     * At launch and on every return to the foreground, after readiness's own check (writer 4).
     *
     * @param foreground **which** foreground of the scene this is: a number that moves when the
     *   scene becomes active and at no other time; `null` when Photos is not in the foreground.
     *   ⚠⚠ D12 counts three slow probes on three *separate foregrounds*. Counted by calls, two
     *   checks overlapping in one foreground (the launch's and a rehydration's) would be two
     *   foregrounds, and three slow probes inside one real one would reach `Unsuitable`, which
     *   is durable. The caller says which
     *   foreground it is; N calls in one foreground are one, whatever their order.
     *   ⚠ `null` probes nothing: a probe opens both towers, and nothing that heavy runs for a
     *   Photos that is not on screen.
     */
    suspend fun foregrounded(foreground: Long?): PreflightOutcome = attemptReport(foreground, afterRecompile = false).outcome

    /** After the prepare step wrote its marker (writer 1). ⚠ No caller outside the tests. */
    suspend fun prepared(foreground: Long?): PreflightOutcome = attemptReport(foreground, afterRecompile = false).outcome

    /**
     * After the prepare step compiled the towers **a second time** because a probe could not
     * load them, or they answered wrongly.
     *
     * ⚠⚠ Not held back by the backoff: that is for a probe of the same artifact on a phone
     * that may only need time, and what failed a moment ago is no longer what is on the disk.
     * Held back, this probe would not run while the user stayed in the app (nothing else starts
     * one), and the recompile would be for nothing until the next foreground.
     * ⚠ The count is not reset: a second failure is this install's second.
     */
    suspend fun recompiled(foreground: Long?): PreflightOutcome = attemptReport(foreground, afterRecompile = true).outcome

    /**
     * [foregrounded], with **what the answer is about**: the key that was probed, or `null`.
     *
     * ⚠⚠ For a caller that will act on the answer later (the prepare step compiles towers again
     * on a failure): an answer that crosses a wait must carry the identity of what it is
     * about, or it is acted on for whatever is installed by then.
     */
    suspend fun foregroundedReport(foreground: Long?): PreflightReport = attemptReport(foreground, afterRecompile = false)

    /** [recompiled], with what the answer is about. */
    suspend fun recompiledReport(foreground: Long?): PreflightReport = attemptReport(foreground, afterRecompile = true)

    private suspend fun attemptReport(foreground: Long?, afterRecompile: Boolean): PreflightReport {
        // ⚠ Not reported, and nothing is looked at: there is no foreground to count this in.
        val scene: Long = foreground ?: return PreflightReport(null, PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND, "Photos is not in the foreground"))
        // ⚠ Not reported: the probe that is running will say what it found.
        if (!running.tryLock()) return PreflightReport(null, PreflightOutcome.Busy)
        try {
            return withContext(dispatcher) {
                try {
                    probeOnce(scene, afterRecompile)
                } finally {
                    // ⚠ Claimed under readiness's lock when the probe was let through; given
                    // back on every way out, also the ones that never opened a tower.
                    // ⚠⚠ And only HERE, after the answer was recorded: released when the towers
                    // closed, the prepare step could discard and recompile them between the
                    // measurement and its recording, and a pass was then recorded for towers
                    // that had never been probed.
                    probed.value = null
                    holding.value = false
                }
            }.also { last.value = it }
        } finally {
            running.unlock()
        }
    }

    private suspend fun probeOnce(foreground: Long, afterRecompile: Boolean): PreflightReport {
        // ⚠ With nothing to probe the last report is replaced by one about no model: whatever
        // was said about a model that is gone, or decided since, is not said any longer.
        // ⚠⚠ [holding] is claimed INSIDE readiness's lock, in the same breath as the probe is
        // let through: the prepare step either sees it and waits, or had marked the cache as
        // changing first and no probe is let through. Claimed only when the first tower is
        // opened, after the vocabulary has been read and tokenised, a prepare arriving in between
        // would go straight past.
        val target: ProbeTarget = when (val awaiting: Awaiting = readiness.awaitingProbe { holding.value = true }) {
            is Awaiting.Probe -> awaiting.target
            Awaiting.NothingToProbe -> return PreflightReport(null, PreflightOutcome.NotAwaiting)
            // ⚠⚠ Not probed: its result would have to be dropped, and three towers' worth of
            // loading would have been spent to learn nothing.
            Awaiting.Unreadable -> return PreflightReport(null, PreflightOutcome.Unreadable)
        }
        probed.value = target.key
        fun about(outcome: PreflightOutcome) = PreflightReport(target.key, outcome)
        // ⚠⚠ By all three digests: an export this build has no record for is never probed against
        // another export's captions and margin.
        val probe: PreflightRun = prober.runFor(target.key) ?: return about(PreflightOutcome.NoProbe)
        if (target.attempts.failed >= PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL) return about(PreflightOutcome.InstallFailed)
        // ⚠ D12: three slow probes on SEPARATE foregrounds.
        if (slowProbes.spentIn(foreground)) return about(PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.FOREGROUND_SPENT, "this foreground has had its slow probe"))
        if (!afterRecompile && !PreflightPolicy.mayProbe(target.attempts, device.nowEpochMs())) return about(PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.BACKING_OFF, "backing off"))

        beforeLoad()
        // ⚠⚠ On disk before the first byte is loaded, and cleared on every way out this process
        // lives through: a phone on which the probe gets Photos killed is counted at the next
        // launch, and stops being probed (§14.5). Not begun when it cannot be written.
        return try {
            readiness.attempting(AttemptKind.PROBE, role = null, key = target.key, atEpochMs = device.nowEpochMs(), foreground = foreground) {
                measuredAndRecorded(target, probe, foreground)
            }
        } catch (notRecorded: AttemptNotRecordedException) {
            when (notRecorded.why) {
                AttemptNotRecordedException.Why.RETIRED -> PreflightReport(null, PreflightOutcome.Abandoned)
                AttemptNotRecordedException.Why.UNREADABLE -> about(PreflightOutcome.Unreadable)
                // ⚠ About no model: what would run now is not what was to be probed, or is answered.
                AttemptNotRecordedException.Why.MOVED -> PreflightReport(null, PreflightOutcome.NotAwaiting)
            }
        }
    }

    /** The probe and its recording, with its [AttemptBegun] on disk. */
    private suspend fun measuredAndRecorded(target: ProbeTarget, probe: PreflightRun, foreground: Long): PreflightReport {
        fun about(outcome: PreflightOutcome) = PreflightReport(target.key, outcome)
        // ⚠ A retirement is about no model of this graph any more.
        val measurement: PreflightMeasurement = probe.measure(target.model, context) ?: return PreflightReport(null, PreflightOutcome.Abandoned)
        // ⚠⚠ A probe the scene left under leaves NO trace: no count, no backoff's stamp, no
        // foreground's slow probe spent. What it measured was measured on a phone doing
        // something else by the end (a backgrounded app is throttled), and a count is durable.
        if (foregroundNow != null && foregroundNow.invoke() != foreground) {
            return PreflightReport(null, PreflightOutcome.Waiting(PreflightOutcome.Waiting.Cause.NOT_IN_FOREGROUND, "Photos left the foreground under the probe"))
        }
        val finding: PreflightFinding = probe.judge(measurement)

        // ⚠⚠ Both towers are closed by here. Recording a pass is what lets the backend open its own.
        val result: ProbeResult = when (finding) {
            is PreflightFinding.Passed -> ProbeResult.PASSED
            is PreflightFinding.Slow -> ProbeResult.SLOW
            is PreflightFinding.Failed -> ProbeResult.FAILED
            is PreflightFinding.Transient -> ProbeResult.TRANSIENT
        }
        // ⚠⚠ What is reported is what readiness did, never what the finding would have meant:
        // the store may have moved, or the runtime been retired, while the towers ran.
        val recorded: ProbeRecorded = readiness.recordProbe(result, target.key, device.nowEpochMs())
        println("Preflight -> $finding, $recorded (${measurement.summary()})")
        return when (recorded) {
            // ⚠ About no model: the one it probed is not the one installed, or nothing is.
            is ProbeRecorded.Dropped -> PreflightReport(null, PreflightOutcome.NotRecorded(finding, recorded.state))
            is ProbeRecorded.Decided -> {
                if (result == ProbeResult.SLOW) slowProbes.spend(foreground)
                about(if (recorded.passed) PreflightOutcome.Passed(measurement) else PreflightOutcome.Unsuitable(finding))
            }
            is ProbeRecorded.Counted -> {
                if (result == ProbeResult.SLOW) slowProbes.spend(foreground)
                val failedInstall: Boolean = recorded.attempts.failed >= PreflightPolicy.FAILED_PROBES_FOR_FAILED_INSTALL
                about(if (failedInstall) PreflightOutcome.InstallFailed else PreflightOutcome.Retry(finding, recorded.attempts))
            }
        }
    }

    /** What a model's probe asks of this preflight while it measures. */
    private val context = object : PreflightRunContext {
        override fun isRetired(): Boolean = readiness.isRetired()

        override fun holdingSessions() {
            holding.value = true
        }
    }
}

/**
 * One model's half of a preflight (`ios-face-grouping.md` FD7): which probe this build carries for
 * a key, how a probe is measured, how it is judged. [Preflight] owns everything else, once for
 * every model: readiness's protocol, the attempt record, the backoff, the foreground rule.
 */
fun interface PreflightProber {
    /** The probe this build carries for [key], ready to run; `null` when it carries none. */
    fun runFor(key: PreflightKey): PreflightRun?
}

/** One probe of one install. */
interface PreflightRun {
    /**
     * Measures [target] on sessions it opens itself, outside the backend, and closes every one
     * before it returns.
     * @return `null` when the runtime was retired under it.
     */
    fun measure(target: PreparedModel, context: PreflightRunContext): PreflightMeasurement?

    /** The rule, over what [measure] returned. */
    fun judge(measurement: PreflightMeasurement): PreflightFinding
}

/** What a [PreflightRun] asks of the [Preflight] it runs in. */
interface PreflightRunContext {
    /** Whether the readiness was retired: asked between steps. */
    fun isRetired(): Boolean

    /** Said right before the first session is opened: the preflight holds sessions from here. */
    fun holdingSessions()
}

/** SigLIP's probe (D12 ②): the vocabulary, both captions, both towers. ⚠ Moved, not changed. */
internal class SiglipPreflightProber(
    private val towers: PreflightTowers,
    private val readVocabulary: (String) -> ByteArray?,
    private val device: PreflightDevice,
    private val probes: PreflightProbeLookup,
    private val time: TimeSource,
    private val model: VisionModel,
) : PreflightProber {

    override fun runFor(key: PreflightKey): PreflightRun? = probes.forKey(key)?.let { Run(it) }

    private inner class Run(private val probe: PreflightProbe) : PreflightRun {

        override fun judge(measurement: PreflightMeasurement): PreflightFinding = PreflightJudge.judge(measurement, probe, model.dimensions)

        /** One probe, or `null` when the runtime was retired under it. Both towers are closed on return. */
        override fun measure(target: PreparedModel, context: PreflightRunContext): PreflightMeasurement? {
            val thermalAtStart: Int = device.thermalState()
            val warningsAtStart: Long = device.memoryWarnings()
            val footprintBefore: Long? = device.footprintBytes()
            fun failed(why: String, beforeAnyTower: Boolean = false) = PreflightMeasurement(
                thermalAtStart = thermalAtStart,
                memoryWarned = device.memoryWarnings() != warningsAtStart,
                failure = why,
                failedBeforeAnyTower = beforeAnyTower,
                thermalAtEnd = device.thermalState(),
            )

            // ⚠ The vocabulary first, as the backend does: it is cheap, and without it there is
            // nothing to feed a text tower that costs seconds to open.
            var mark: TimeMark = time.markNow()
            val tokenizer: SiglipTextTokenizer = runCatching {
                readVocabulary(target.vocabularyPath)?.let(UnigramVocabulary::read)?.let { SiglipTextTokenizer(it, model.textSequenceLength) }
            }.getOrNull() ?: return failed("the vocabulary did not load", beforeAnyTower = true)
            val vocabularyMs: Double = mark.elapsedMs()
            mark = time.markNow()
            val rows: List<IntArray> = runCatching { listOf(tokenizer.encode(probe.captionDescribes), tokenizer.encode(probe.captionDoesNot)) }
                .getOrElse { return failed("the probe captions did not tokenise: $it", beforeAnyTower = true) }
            val tokeniseMs: Double = mark.elapsedMs()
            // ⚠⚠ Against the rows the checkpoint's own tokenizer made: this is the check of the
            // downloaded vocabulary and of the app's tokenizer, before a tower is opened for them.
            if (!rows[0].contentEquals(probe.tokensDescribes) || !rows[1].contentEquals(probe.tokensDoesNot)) {
                return failed("the probe captions tokenised to other rows than the export recorded", beforeAnyTower = true)
            }

            var vision: PreflightTower? = null
            var text: PreflightTower? = null
            context.holdingSessions()
            try {
                if (context.isRetired()) return null
                mark = time.markNow()
                vision = runCatching { towers.openVision(target.visionPath) }.getOrElse { return failed("the vision tower did not load: $it") }
                val visionOpenMs: Double = mark.elapsedMs()
                if (context.isRetired()) return null
                mark = time.markNow()
                text = runCatching { towers.openText(target.textPath) }.getOrElse { return failed("the text tower did not load: $it") }
                val textOpenMs: Double = mark.elapsedMs()

                val tensor: FloatArray = ImagePreprocessing.preprocessPixels(PreflightProbeImage.argb(), model.side, model.profile)
                val images = ArrayList<FloatArray>()
                val describes = ArrayList<FloatArray>()
                val doesNot = ArrayList<FloatArray>()
                val visionMs = ArrayList<Double>()
                val textMs = ArrayList<Double>()
                try {
                    repeat(PreflightBudget.PREDICTIONS_PER_TOWER) { round ->
                        if (context.isRetired()) return null
                        mark = time.markNow()
                        images += vision.predict(tensor)
                        visionMs += mark.elapsedMs()
                        mark = time.markNow()
                        (if (round % 2 == 0) describes else doesNot) += text.predict(rows[round % 2])
                        textMs += mark.elapsedMs()
                    }
                } catch (failure: Throwable) {
                    return failed("a prediction failed: $failure")
                }
                return PreflightMeasurement(
                    thermalAtStart = thermalAtStart,
                    memoryWarned = device.memoryWarnings() != warningsAtStart,
                    vision = images,
                    describes = describes,
                    doesNot = doesNot,
                    visionPredictMs = visionMs,
                    textPredictMs = textMs,
                    // ⚠ With both sessions still open: D12 asks for exactly that reading.
                    availableBytes = device.availableBytes(),
                    footprintBytes = device.footprintBytes(),
                    footprintBeforeBytes = footprintBefore,
                    thermalAtEnd = device.thermalState(),
                    vocabularyMs = vocabularyMs,
                    tokeniseMs = tokeniseMs,
                    visionOpenMs = visionOpenMs,
                    textOpenMs = textOpenMs,
                )
            } finally {
                runCatching { vision?.close() }
                runCatching { text?.close() }
            }
        }

    }

    private fun TimeMark.elapsedMs(): Double = elapsedNow().toDouble(DurationUnit.MILLISECONDS)
}
