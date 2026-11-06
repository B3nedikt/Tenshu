package app.tenshu.fit.data

import app.tenshu.fit.model.ActivityType
import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.HrPoint
import app.tenshu.fit.model.RecordedTrack
import app.tenshu.fit.model.Source
import app.tenshu.fit.model.Workout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The RC-5 in-flight capture journal (fit-app.md §13.5): the line-oriented, append-only format
 * an engine flushes during capture so a process death mid-workout loses at most one flush
 * window, not the whole recording. Pure codec — platform engines own the file I/O; on relaunch
 * a leftover journal decodes into a [RecordingDraft] marked `recovered`, parked as
 * `Reviewing` for the ordinary review→save flow.
 *
 * Format (one event per line, `|`-separated; append-friendly, torn-tail-tolerant — malformed
 * lines are skipped on decode):
 *
 * ```
 * H|1|Running|1753440000000        header: version, activity, startWallMs (must come first)
 * F|t|lat|lng|alt                  GPS fix (alt empty when absent); t = active seconds
 * R|t|bpm                          HR sample
 * S|steps                         cumulative step count (last one wins)
 * P|                             the session was **stopped** by the user — a draft is parked for
 *                                  review, so a later relaunch must not resume capture. Its absence
 *                                  is what distinguishes "the process died mid-capture" from "the
 *                                  user pressed Stop", and nothing else in the file can: the journal
 *                                  deliberately outlives stop() until commit/discard (§13.5).
 * T|activeSec|wallMs              clock tick, flushed periodically (recovers duration for
 *                                  sample-less strength workouts). `wallMs` is optional and
 *                                  purely additive — a v1 decoder reads the line without it, and
 *                                  this decoder reads a journal written without it — so it costs
 *                                  no version bump. It is what lets the review screen say *when*
 *                                  capture stopped rather than only that it did.
 * ```
 */
object RecordingJournalCodec {

    private const val VERSION = 1

    fun header(activityType: ActivityType, startWallMs: Long): String =
        "H|$VERSION|${activityType.name}|$startWallMs"

    fun fix(f: GpsFix): String = "F|${f.tSec}|${f.lat}|${f.lng}|${f.altM ?: ""}"

    fun hr(p: HrPoint): String = "R|${p.t}|${p.bpm}"

    fun steps(count: Int): String = "S|$count"

    /** Written at [RecordingEngine.stop]; see the `P` line in the format table above. */
    fun stopped(): String = "P|"

    /**
     * [wallMs] is the engine's wall clock at flush time. Optional so a platform without one still
     * writes a valid tick; when present it dates the interruption to the last flush rather than
     * inferring it from `start + activeSec`, which a paused span would understate.
     */
    fun tick(activeSec: Int, wallMs: Long? = null): String =
        if (wallMs == null) "T|$activeSec" else "T|$activeSec|$wallMs"

    /** Null when the journal is empty, headerless, or an unknown version — nothing to recover. */
    fun decode(lines: List<String>): RecoveredRecording? {
        val header = lines.firstOrNull { it.startsWith("H|") }?.split('|') ?: return null
        if (header.size < 4 || header[1].toIntOrNull() != VERSION) return null
        val activity = runCatching { ActivityType.valueOf(header[2]) }.getOrNull() ?: return null
        val startWallMs = header[3].toLongOrNull() ?: return null

        val route = mutableListOf<GpsFix>()
        val hr = mutableListOf<HrPoint>()
        var steps = 0
        var lastActiveSec = 0
        var lastWallMs: Long? = null
        var stopped = false
        for (line in lines) {
            val p = line.split('|')
            when (p.firstOrNull()) {
                "F" -> {
                    val t = p.getOrNull(1)?.toIntOrNull() ?: continue
                    val lat = p.getOrNull(2)?.toDoubleOrNull() ?: continue
                    val lng = p.getOrNull(3)?.toDoubleOrNull() ?: continue
                    route += GpsFix(tSec = t, lat = lat, lng = lng, altM = p.getOrNull(4)?.toDoubleOrNull())
                    if (t > lastActiveSec) lastActiveSec = t
                }
                "R" -> {
                    val t = p.getOrNull(1)?.toIntOrNull() ?: continue
                    val bpm = p.getOrNull(2)?.toIntOrNull() ?: continue
                    hr += HrPoint(t = t, bpm = bpm)
                    if (t > lastActiveSec) lastActiveSec = t
                }
                "S" -> p.getOrNull(1)?.toIntOrNull()?.let { steps = it }
                "P" -> stopped = true
                "T" -> {
                    p.getOrNull(1)?.toIntOrNull()?.let { if (it > lastActiveSec) lastActiveSec = it }
                    p.getOrNull(2)?.toLongOrNull()?.let { if (it > (lastWallMs ?: 0L)) lastWallMs = it }
                }
            }
        }
        return RecoveredRecording(
            activityType = activity,
            startWallMs = startWallMs,
            route = route,
            hr = hr,
            steps = steps,
            activeSec = lastActiveSec,
            lastFlushWallMs = lastWallMs,
            stoppedByUser = stopped,
        )
    }
}

/** A decoded journal — what survived the crash; [toDraft] shapes it for the review screen. */
data class RecoveredRecording(
    val activityType: ActivityType,
    val startWallMs: Long,
    val route: List<GpsFix>,
    val hr: List<HrPoint>,
    val steps: Int,
    val activeSec: Int,
    /** Wall clock of the last flushed tick — when capture actually stopped. Null in a v1 journal. */
    val lastFlushWallMs: Long? = null,
    /**
     * True when the journal carries a `P` line, i.e. the user pressed Stop and this is a draft
     * parked for review. False means the process died with capture still running — the only case
     * where resuming is right (§13.5e).
     */
    val stoppedByUser: Boolean = false,
)

/**
 * Whether this journal describes a session the OS interrupted that should be **picked back up**
 * rather than parked for review (§13.5e).
 *
 * Two guards, and both are load-bearing:
 *  - [stoppedByUser] — a journal outlives `stop()` until the draft is saved or discarded, so
 *    without its `P` marker every parked draft would resume capture on the next launch.
 *  - freshness — resuming a run the user gave up on hours ago (phone in a drawer, app reopened
 *    the next morning) would attach a second leg to a workout that ended long before. A session
 *    whose last flush is older than [RESUME_WINDOW_MS] is history, not an interruption.
 *
 * ⚠ A journal with no wall clock (written before §13.5b) cannot be dated, and an undatable session
 * is never resumed — the review path is the safe answer.
 */
fun RecoveredRecording.isResumable(nowWallMs: Long): Boolean {
    if (stoppedByUser) return false
    val lastFlush = lastFlushWallMs ?: return false
    return nowWallMs - lastFlush in 0..RESUME_WINDOW_MS
}

/** How long after the last flush a killed session is still "the run I am on". */
const val RESUME_WINDOW_MS: Long = 15 * 60 * 1000

/**
 * The recovered session as a review-ready draft — the same summary shape the engines build at
 * stop, computed from the journalled samples (distance re-summed from the fixes; energy from
 * the shared per-activity rate). Marked [RecordingDraft.recovered] so the review screen can say
 * where it came from.
 */
fun RecoveredRecording.toDraft(zone: TimeZone = TimeZone.currentSystemDefault()): RecordingDraft {
    val start = Instant.fromEpochMilliseconds(startWallMs).toLocalDateTime(zone)
    val end = Instant.fromEpochMilliseconds(startWallMs + activeSec * 1000L).toLocalDateTime(zone)
    val distance = routeDistanceM(route)
    val workout = Workout(
        workoutId = "rec-$startWallMs",
        activityType = activityType,
        source = Source.Recorded,
        title = "",
        device = "",
        start = start,
        end = end,
        durationSec = activeSec,
        distanceM = distance,
        activeEnergyKj = (recordedEnergyKjPerSec(activityType) * activeSec).roundToInt().takeIf { it > 0 },
        avgHr = hr.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        maxHr = hr.maxOfOrNull { it.bpm },
        steps = steps.takeIf { it > 0 },
        avgPaceSecPerKm = distance?.takeIf { it >= 25 }?.let { (activeSec * 1000L / it).toInt() },
        hasRoute = route.isNotEmpty(),
        hrSeries = hr,
    )
    return RecordingDraft(
        workout = workout,
        track = RecordedTrack(route = route, hr = hr),
        recovered = true,
        // Prefer the journalled wall clock; fall back to the derived end so a v1 journal still
        // dates itself (only understated if the session was paused).
        interruptedAtWallMs = lastFlushWallMs ?: (startWallMs + activeSec * 1000L),
    )
}

/**
 * The recorder's coarse per-activity energy rate (kJ/s) — the constants every engine uses
 * (duration-based, no body-weight/HR input yet; `fit-app.md` §9.8), shared here so a recovered
 * draft prices energy identically.
 */
internal fun recordedEnergyKjPerSec(type: ActivityType): Double = when (type) {
    ActivityType.Running -> 0.9
    ActivityType.Walking -> 0.3
    ActivityType.Cycling -> 0.65
    ActivityType.Strength -> 0.4
    ActivityType.Other -> 0.35
}
