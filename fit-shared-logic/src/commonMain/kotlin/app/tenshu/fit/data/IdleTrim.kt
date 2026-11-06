package app.tenshu.fit.data

import app.tenshu.fit.model.GpsFix
import app.tenshu.fit.model.RecordedTrack
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/**
 * The RC-3 idle-trim suggestion (fit-app.md §13.3): the fumbling-with-the-phone span before the
 * user actually moves, and the standing-at-the-crosswalk span after they stop, detected from the
 * captured GPS fixes so the review screen can offer "Remove 2:14 of idle time at the start?".
 *
 * Window bounds in track time: [trimStartSec] = the suggested new window start (the first
 * sustained movement; null when the leading idle is under [MIN_IDLE_SEC]); [trimEndSec] = the
 * suggested new window end (null likewise). A **suggestion, never automatic** — the user applies
 * each end explicitly; silent auto-trimming eats real warm-up data.
 */
data class TrimSuggestion(
    val trimStartSec: Int?,
    val trimEndSec: Int?,
)

/** A prefix/suffix only counts as trimmable idle from this length — never nag over seconds. */
private const val MIN_IDLE_SEC = 45

/** Fixes within this radius of the dwell centroid still count as standing still (GPS jitter). */
private const val IDLE_RADIUS_M = 30.0

/**
 * The boundary anchor: the last fix this close to the centroid. Departure is *detected* at
 * [IDLE_RADIUS_M] (jitter-proof), but anchoring the trim there would eat the first
 * radius-worth of real movement (~20 s of walking); the tight radius keeps the ramp.
 */
private const val TIGHT_RADIUS_M = 10.0

/** Never suggest a trim that leaves less than this much workout. */
private const val MIN_REMAINING_SEC = 30

/**
 * Detects trimmable leading/trailing idle in a captured route. Walks the fix list from each end,
 * folding fixes into a rolling dwell centroid while they stay within [IDLE_RADIUS_M]; the first
 * **sustained** departure (two consecutive out-of-radius fixes — a lone spike is jitter, not
 * movement) marks where activity starts/ends. Null when neither end has ≥ [MIN_IDLE_SEC] of
 * idle, when the route is too short to judge, when the whole recording is one dwell (trimming
 * everything is nonsense), or when the suggested window would keep under [MIN_REMAINING_SEC].
 */
fun detectIdleTrim(route: List<GpsFix>, durationSec: Int): TrimSuggestion? {
    if (route.size < 3) return null
    val trimStart = dwellBoundaryTSec(route)?.takeIf { it >= MIN_IDLE_SEC }
    val trimEnd = dwellBoundaryTSec(route.reversed())?.takeIf { durationSec - it >= MIN_IDLE_SEC }
    if (trimStart == null && trimEnd == null) return null
    if ((trimEnd ?: durationSec) - (trimStart ?: 0) < MIN_REMAINING_SEC) return null
    return TrimSuggestion(trimStart, trimEnd)
}

/**
 * The tSec of the last fix still inside the initial dwell (walking the list front-to-back), or
 * null when the walk never sustainably leaves the dwell radius. Passing a reversed list yields
 * the trailing-dwell boundary.
 */
private fun dwellBoundaryTSec(route: List<GpsFix>): Int? {
    var cLat = route[0].lat
    var cLng = route[0].lng
    var folded = 1
    var lastTight = route[0]
    var i = 1
    while (i < route.size) {
        val fix = route[i]
        val d = haversineMeters(cLat, cLng, fix.lat, fix.lng)
        if (d <= IDLE_RADIUS_M) {
            folded += 1
            cLat += (fix.lat - cLat) / folded
            cLng += (fix.lng - cLng) / folded
            if (d <= TIGHT_RADIUS_M) lastTight = fix
            i += 1
            continue
        }
        val next = route.getOrNull(i + 1)
        if (next == null || haversineMeters(cLat, cLng, next.lat, next.lng) > IDLE_RADIUS_M) {
            // Sustained departure — the last tight-radius fix anchors the window so the
            // dwell→movement ramp survives the trim.
            return lastTight.tSec
        }
        // A lone out-of-radius spike with an in-radius successor: GPS jitter, skip unfolded.
        i += 1
    }
    return null
}

/**
 * The draft re-windowed to `[trimStartSec, trimEndSec]` in track time (null = keep that end) —
 * the §13.3 recompute both the review screen's live preview and the commit share. Fixes/HR are
 * filtered and re-based; duration, start/end stamps, distance (re-summed haversine), avg/max HR,
 * pace and energy (linear per-second rate, matching the engines' duration-based model) are
 * recomputed from the window. Steps stay the session total — the counter is cumulative-only and
 * idle spans contribute ~none. Identity when there is no dense track or nothing is trimmed.
 */
fun RecordingDraft.trimmed(trimStartSec: Int?, trimEndSec: Int?): RecordingDraft {
    val dense = track ?: return this
    val from = trimStartSec ?: 0
    val to = trimEndSec ?: workout.durationSec
    if (from <= 0 && to >= workout.durationSec) return this
    val newDuration = (to - from).coerceAtLeast(0)
    val route = dense.route.filter { it.tSec in from..to }.map { it.copy(tSec = it.tSec - from) }
    val hr = dense.hr.filter { it.t in from..to }.map { it.copy(t = it.t - from) }
    val distance = routeDistanceM(route)
    val zone = TimeZone.currentSystemDefault()
    val newStart = workout.start.toInstant(zone).plus(from.seconds).toLocalDateTime(zone)
    val newEnd = workout.start.toInstant(zone).plus(to.seconds).toLocalDateTime(zone)
    val trimmedWorkout = workout.copy(
        start = newStart,
        end = newEnd,
        durationSec = newDuration,
        distanceM = distance,
        activeEnergyKj = workout.activeEnergyKj
            ?.takeIf { workout.durationSec > 0 }
            ?.let { (it.toDouble() * newDuration / workout.durationSec).roundToInt() }
            ?.takeIf { it > 0 },
        avgHr = hr.map { it.bpm }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        maxHr = hr.maxOfOrNull { it.bpm },
        avgPaceSecPerKm = distance?.takeIf { it >= 25 }?.let { (newDuration * 1000L / it).toInt() },
        hasRoute = route.isNotEmpty(),
        hrSeries = hr,
    )
    return copy(workout = trimmedWorkout, track = RecordedTrack(route = route, hr = hr))
}

/**
 * All review edits applied in one place — trim window first, then title/activity — so every
 * engine's `commit` persists exactly what the review screen previewed.
 */
fun RecordingDraft.applying(edits: DraftEdits): RecordingDraft {
    val trimmed = trimmed(edits.trimStartSec, edits.trimEndSec)
    return trimmed.copy(workout = trimmed.workout.applying(edits))
}

fun routeDistanceM(route: List<GpsFix>): Int? =
    route.zipWithNext { a, b -> haversineMeters(a.lat, a.lng, b.lat, b.lng) }
        .sum()
        .roundToInt()
        .takeIf { it > 0 }

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private fun Double.toRadians(): Double = this * PI / 180.0
