package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.RoutePoint
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The first-party recorder seam (Ph2, docs/proposals/fit-app.md D6). The live-record
 * screens observe [state]; the pre-start screen observes [sensors]. v1 binds
 * [MockRecordingEngine]; the real sensor pipeline (while-in-use GPS, BLE HR strap,
 * on-device step counter — zero Play Services) implements the same interface later.
 */
interface RecordingEngine {

    fun state(): Flow<RecordingState>

    fun sensors(): Flow<List<SensorStatus>>

    /** Begins sensor acquisition for the pre-start screen (idempotent). */
    fun armSensors()

    /**
     * Opt into a BLE heart-rate strap — the just-in-time "Connect" action on the pre-start HR
     * sensor row (fit-app.md §9.11 / §11.2). Default **no-op**: engines that scan for HR eagerly in
     * [armSensors] (the mock; the Android engine, gated by its permission flow) ignore it. The iOS
     * engine overrides it so creating the `CBCentralManager` — which triggers the Bluetooth
     * permission prompt — happens only on the explicit user tap, never on entering the Record tab.
     */
    fun connectHeartRate() {}

    /** Starts a recording of [activityType]. No-op if one is already active. */
    fun start(activityType: ActivityType)

    fun pause()

    fun resume()

    /**
     * Ends *capture* without saving (RC-2, fit-app.md §13.2): sensors torn down, the raw summary
     * computed, and the session held as an immutable [RecordingDraft] — state moves
     * `Active → Reviewing(draft)`. Nothing is persisted until [commit]; [discard] drops the draft.
     * Returns null unless a recording is Active (stop is not re-entrant from Reviewing).
     */
    fun stop(): RecordingDraft?

    /**
     * Persists the Reviewing draft as a [Source.Recorded] workout via [FitRepository.addWorkout],
     * with the review-screen [edits] applied; the result carries the repository's
     * [WorkoutSaveOutcome] so the UI can distinguish "open the detail" from "saved on this device,
     * syncing later". Once the save is journalled or written the engine resets to Idle; a
     * repository *without* a durable journal throws instead, and the engine stays Reviewing so
     * Save can retry. Null when nothing is under review.
     */
    suspend fun commit(edits: DraftEdits = DraftEdits()): FinishedRecording?

    /**
     * One-shot stop-and-save: [stop] (when still Active) then [commit] with no edits — the
     * pre-RC-2 behaviour, kept for callers without a review surface yet (the iOS RecordView;
     * its review flow is a later slice, §13.6). After a failed commit the draft stays Reviewing,
     * so calling finish again retries just the save.
     */
    suspend fun finish(): FinishedRecording? {
        stop()
        return commit()
    }

    /** Ends the session — Active or Reviewing — without saving. */
    fun discard()
}

/**
 * A stopped-but-unsaved recording under review (fit-app.md §13.2): the raw summary [workout]
 * exactly as the engine computed it at stop time (title/device empty), plus the dense captured
 * [track] (null on engines that synthesise their route — the mock). Immutable; review-screen
 * edits ride [DraftEdits] into [RecordingEngine.commit].
 */
data class RecordingDraft(
    val workout: Workout,
    val track: RecordedTrack? = null,
    /**
     * True when this draft was rebuilt from the RC-5 in-flight journal after a process death
     * (fit-app.md §13.5) rather than produced by a live [RecordingEngine.stop] — the review
     * screen notes the provenance.
     */
    val recovered: Boolean = false,
    /**
     * Wall clock (epoch ms) at which capture stopped, on a [recovered] draft — the last journal
     * flush before the process died. The review screen dates the interruption with it, because
     * "the last few seconds may be missing" is only true when the user comes back promptly: an
     * OEM kill 20 minutes into a run leaves the rest of that run uncaptured, and the draft that
     * says otherwise gets saved as if it were the whole workout. Null on a live-stopped draft.
     */
    val interruptedAtWallMs: Long? = null,
) {
    /**
     * The 0..1 schematic polyline for the review screen: the workout's own abstract route when
     * present (the mock synthesises one), else the captured GPS fixes folded the same way
     * `FitProjection.projectRoute` folds stored samples (equirectangular, aspect-preserving).
     */
    val routeSketch: List<RoutePoint>
        get() = workout.route.ifEmpty { sketchOf(track?.route.orEmpty()) }
}

/**
 * Whether a system-caused process death explains *this* draft's interruption — i.e. whether the
 * review screen may say "your phone stopped the recording" rather than the neutral "was
 * interrupted". Pure so the rule is testable; the platform only supplies [killAtWallMs].
 *
 * The window is deliberately asymmetric. A kill can only land **after** the flush that recorded the
 * last sample, and the flush cadence is 15 s, so anything up to [KILL_AFTER_FLUSH_MS] later is the
 * same event; [KILL_BEFORE_FLUSH_MS] of slack absorbs clock jitter between the two timestamps and
 * nothing more. A kill from *before* the session — a yesterday-evening one still on the OS's 16-deep
 * record — must never be pinned on this draft, and a symmetric window would do exactly that.
 */
fun systemKillExplains(
    killAtWallMs: Long?,
    interruptedAtWallMs: Long?,
): Boolean {
    if (killAtWallMs == null || interruptedAtWallMs == null) return false
    val delta = killAtWallMs - interruptedAtWallMs
    return delta in -KILL_BEFORE_FLUSH_MS..KILL_AFTER_FLUSH_MS
}

/** Jitter slack only: the kill essentially cannot precede the flush it interrupted. */
private const val KILL_BEFORE_FLUSH_MS = 5_000L

/** One flush window (15 s) plus room for a kill that took its time to be recorded. */
private const val KILL_AFTER_FLUSH_MS = 120_000L

/**
 * Review-screen edits applied at [RecordingEngine.commit]; null = keep the draft's value.
 * [trimStartSec]/[trimEndSec] are the applied idle-trim window bounds in track time (RC-3,
 * §13.3 — from [detectIdleTrim], user-applied, never automatic); the commit re-windows the
 * draft via [RecordingDraft.trimmed] before persisting (destructive-at-save v1).
 */
data class DraftEdits(
    val title: String? = null,
    val activityType: ActivityType? = null,
    val trimStartSec: Int? = null,
    val trimEndSec: Int? = null,
)

/** The draft's workout with review edits applied — a blank title keeps the fallback naming. */
fun Workout.applying(edits: DraftEdits): Workout = copy(
    title = edits.title?.trim().takeUnless { it.isNullOrEmpty() } ?: title,
    activityType = edits.activityType ?: activityType,
)

/** [GpsFix]es → the abstract 0..1 polyline (the `FitProjection.projectRoute` fold, pre-store). */
private fun sketchOf(route: List<GpsFix>): List<RoutePoint> {
    if (route.size < 2) return emptyList()
    val meanLatRad = route.map { it.lat }.average() * PI / 180.0
    val cosLat = cos(meanLatRad)
    val xs = route.map { it.lng * cosLat }
    val ys = route.map { it.lat }
    val minX = xs.min()
    val minY = ys.min()
    val span = maxOf(xs.max() - minX, ys.max() - minY).takeIf { it > 0.0 } ?: 1.0
    return route.indices.map { i ->
        RoutePoint(
            x = ((xs[i] - minX) / span).toFloat(),
            y = (1.0 - (ys[i] - minY) / span).toFloat(),
        )
    }
}

/**
 * A finished, persisted-or-journalled recording: the summary [workout] plus what the save
 * actually did ([WorkoutSaveOutcome]).
 */
data class FinishedRecording(
    val workout: Workout,
    val outcome: WorkoutSaveOutcome,
)

/**
 * Builds the platform's [RecordingEngine] over the app's [FitRepository]. The DI seam that lets a
 * platform module swap the real sensor pipeline in for [MockRecordingEngine] without the shared
 * `fitModules()` referencing a platform type — mirrors the optional-`HealthImporter` binding
 * (`FitModules.realDataModule`). Android binds one over `AndroidRecordingEngine`; desktop/iOS
 * fall back to the mock.
 */
fun interface RecordingEngineFactory {
    fun create(repository: FitRepository): RecordingEngine
}

sealed interface RecordingState {
    data object Idle : RecordingState

    /**
     * Capture has ended and the draft awaits review (fit-app.md §13.2) — Save
     * ([RecordingEngine.commit]) or [RecordingEngine.discard] leave it.
     */
    data class Reviewing(val draft: RecordingDraft) : RecordingState

    data class Active(
        val activityType: ActivityType,
        val elapsedSec: Int,
        val distanceM: Int,
        /** Rolling average pace; null while distance is ~zero (or for Strength). */
        val paceSecPerKm: Int?,
        val heartRateBpm: Int?,
        val paused: Boolean,
        /** Steps counted so far (on-device step counter); `0` before the first reading. */
        val steps: Int = 0,
    ) : RecordingState
}

enum class SensorKind { Gps, HeartRate, StepCounter }

enum class SensorState { Searching, Ready, Unavailable }

data class SensorStatus(
    val kind: SensorKind,
    val state: SensorState,
)

/**
 * Deterministic mock recorder: ticks elapsed/distance/HR with the same LCG jitter
 * family as [FitMockData], so a "recorded" session looks believable. Finished workouts
 * are stamped on the dataset's [FitRepository.today] (noon + a counter) — never the
 * system clock — so they sort coherently into the mock week.
 */
class MockRecordingEngine(
    private val repository: FitRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tickMillis: Long = 1000,
) : RecordingEngine {

    private val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val sensors = MutableStateFlow(
        listOf(
            SensorStatus(SensorKind.Gps, SensorState.Searching),
            SensorStatus(SensorKind.HeartRate, SensorState.Searching),
            SensorStatus(SensorKind.StepCounter, SensorState.Ready),
        ),
    )

    private var tickJob: Job? = null
    private var sensorJob: Job? = null
    private var recordingCount = 0

    // Synthesis state for the active recording.
    private var seed = 11L
    private var hr = 0.0
    private var hrSum = 0L
    private var hrMax = 0
    private var distance = 0.0
    private var stepsAccum = 0.0
    private val hrSamples = mutableListOf<HrPoint>()

    override fun state(): Flow<RecordingState> = state.asStateFlow()

    override fun sensors(): Flow<List<SensorStatus>> = sensors.asStateFlow()

    override fun armSensors() {
        if (sensorJob?.isActive == true) return
        if (sensors.value.all { it.state == SensorState.Ready }) return
        sensorJob = scope.launch {
            delay(tickMillis)
            sensors.value = sensors.value.map {
                if (it.kind == SensorKind.HeartRate) it.copy(state = SensorState.Ready) else it
            }
            delay(tickMillis * 2)
            sensors.value = sensors.value.map { it.copy(state = SensorState.Ready) }
        }
    }

    override fun start(activityType: ActivityType) {
        // Idle only: starting over a Reviewing draft would silently clobber the unsaved session.
        if (state.value !is RecordingState.Idle) return
        recordingCount += 1
        seed = 11L + recordingCount * 13
        hr = hrBase(activityType).toDouble()
        hrSum = 0
        hrMax = 0
        distance = 0.0
        stepsAccum = 0.0
        hrSamples.clear()
        state.value = RecordingState.Active(
            activityType = activityType,
            elapsedSec = 0,
            distanceM = 0,
            paceSecPerKm = null,
            heartRateBpm = hr.roundToInt(),
            paused = false,
            steps = 0,
        )
        tickJob = scope.launch {
            while (true) {
                delay(tickMillis)
                val current = state.value as? RecordingState.Active ?: return@launch
                if (current.paused) continue
                state.value = tick(current)
            }
        }
    }

    override fun pause() {
        val current = state.value as? RecordingState.Active ?: return
        state.value = current.copy(paused = true)
    }

    override fun resume() {
        val current = state.value as? RecordingState.Active ?: return
        state.value = current.copy(paused = false)
    }

    override fun stop(): RecordingDraft? {
        val current = state.value as? RecordingState.Active ?: return null
        stopTicking()
        val elapsed = current.elapsedSec
        val startSecondOfDay = 12 * 3600 + recordingCount * 60
        val start = LocalDateTime(repository.today, LocalTime.fromSecondOfDay(startSecondOfDay))
        val end = LocalDateTime(
            repository.today,
            LocalTime.fromSecondOfDay((startSecondOfDay + elapsed).coerceAtMost(24 * 3600 - 1)),
        )
        val distanceM = current.distanceM.takeIf { it > 0 }
        val avgHr = if (elapsed > 0) (hrSum / elapsed).toInt() else null
        val workout = Workout(
            workoutId = "w-rec-$recordingCount",
            activityType = current.activityType,
            source = Source.Recorded,
            // Empty title/device: the UI falls back to the localized activity name and
            // the localized "Recorded in Fit" provenance line.
            title = "",
            device = "",
            start = start,
            end = end,
            durationSec = elapsed,
            distanceM = distanceM,
            activeEnergyKj = (energyKjPerSec(current.activityType) * elapsed).roundToInt().takeIf { it > 0 },
            avgHr = avgHr,
            maxHr = hrMax.takeIf { it > 0 },
            steps = current.steps.takeIf { it > 0 },
            avgPaceSecPerKm = current.paceSecPerKm,
            laps = if (distanceM != null && distanceM >= 1000) {
                FitMockData.evenLaps(distanceM, elapsed, paceVarSec = 9, seed = seed)
            } else {
                emptyList()
            },
            hasRoute = distanceM != null,
            route = if (distanceM != null) FitMockData.route(seed = seed) else emptyList(),
            hrSeries = hrSamples.toList(),
        )
        val draft = RecordingDraft(workout)
        state.value = RecordingState.Reviewing(draft)
        return draft
    }

    override suspend fun commit(edits: DraftEdits): FinishedRecording? {
        val draft = (state.value as? RecordingState.Reviewing)?.draft ?: return null
        val applied = draft.applying(edits)
        val outcome = repository.addWorkout(applied.workout)
        state.value = RecordingState.Idle
        return FinishedRecording(applied.workout, outcome)
    }

    override fun discard() {
        stopTicking()
        state.value = RecordingState.Idle
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    // ── Synthesis ───────────────────────────────────────────────────────────────────

    private fun rnd(): Double {
        seed = (seed * 9301 + 49297) % 233280
        return seed.toDouble() / 233280
    }

    private fun tick(current: RecordingState.Active): RecordingState.Active {
        val elapsed = current.elapsedSec + 1
        val speed = speedMetersPerSec(current.activityType)
        if (speed > 0) distance += speed * (0.9 + rnd() * 0.2)
        stepsAccum += stepsPerSec(current.activityType) * (0.9 + rnd() * 0.2)
        val target = hrPeak(current.activityType)
        hr += (target - hr) * 0.03 + (rnd() - 0.5) * 3
        val bpm = hr.roundToInt()
        hrSum += bpm
        if (bpm > hrMax) hrMax = bpm
        if (elapsed % 5 == 0 || elapsed == 1) hrSamples += HrPoint(elapsed, bpm)
        val distanceM = distance.roundToInt()
        return current.copy(
            elapsedSec = elapsed,
            distanceM = distanceM,
            paceSecPerKm = if (distanceM >= 25) (elapsed * 1000L / distanceM).toInt() else null,
            heartRateBpm = bpm,
            steps = stepsAccum.roundToInt(),
        )
    }

    private fun speedMetersPerSec(type: ActivityType): Double = when (type) {
        ActivityType.Running -> 3.1
        ActivityType.Walking -> 1.4
        ActivityType.Cycling -> 6.6
        ActivityType.Strength -> 0.0
        ActivityType.Other -> 1.0
    }

    /** Cadence in steps/sec — foot-based activities only (cycling/strength ≈ none). */
    private fun stepsPerSec(type: ActivityType): Double = when (type) {
        ActivityType.Running -> 2.8
        ActivityType.Walking -> 1.9
        ActivityType.Cycling -> 0.0
        ActivityType.Strength -> 0.1
        ActivityType.Other -> 1.0
    }

    private fun hrBase(type: ActivityType): Int = when (type) {
        ActivityType.Running -> 96
        ActivityType.Walking -> 78
        ActivityType.Cycling -> 90
        ActivityType.Strength -> 84
        ActivityType.Other -> 80
    }

    private fun hrPeak(type: ActivityType): Int = when (type) {
        ActivityType.Running -> 165
        ActivityType.Walking -> 112
        ActivityType.Cycling -> 150
        ActivityType.Strength -> 138
        ActivityType.Other -> 118
    }

    private fun energyKjPerSec(type: ActivityType): Double = when (type) {
        ActivityType.Running -> 0.9
        ActivityType.Walking -> 0.3
        ActivityType.Cycling -> 0.65
        ActivityType.Strength -> 0.4
        ActivityType.Other -> 0.35
    }
}
