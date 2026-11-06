package app.tenshu.fit.data

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import com.garmin.fit.Sport
import org.tenshu.health.common.HrSample
import org.tenshu.health.common.RouteSample
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Decodes a **binary FIT** activity (Garmin FIT Java SDK) into neutral [ImportedWorkout]s — one per
 * `SessionMesg` (the FIT session = a workout), which carries the summary the `Workout` schema wants
 * (sport / start / elapsed time / distance / calories / avg HR). Isolated from
 * [ActivityFileParser] so the FIT SDK import stays in one place.
 *
 * Also extracts the **dense streams** (`docs/proposals/fit-app.md` §9.7): each per-second
 * `RecordMesg` carries position (semicircles → decimal degrees), altitude, and heart rate; the
 * records inside a session's time window become its route + HR ([ImportedWorkout.route]/`heartRate`),
 * persisted to `Workout.Streams`.
 */
internal object FitDecoder : FitBinaryDecoder {

    override fun decode(bytes: ByteArray): List<ImportedWorkout> {
        val sessions = mutableListOf<SessionMesg>()
        val records = mutableListOf<RecordMesg>()
        val decode = Decode()
        val broadcaster = MesgBroadcaster(decode)
        broadcaster.addListener(SessionMesgListener { mesg -> sessions += mesg })
        broadcaster.addListener(RecordMesgListener { mesg -> records += mesg })
        ByteArrayInputStream(bytes).use { input ->
            decode.read(input, broadcaster, broadcaster)
        }
        return sessions.mapNotNull { mapSession(it, records) }
    }

    private fun mapSession(mesg: SessionMesg, records: List<RecordMesg>): ImportedWorkout? {
        val startMs = mesg.startTime?.date?.time ?: return null
        val durationSec = (mesg.totalElapsedTime ?: 0f).toLong()
        val endMs = startMs + durationSec * 1000
        val calories = mesg.totalCalories ?: 0

        // Records whose timestamp falls in this session's window carry its streams; tSec is whole
        // seconds from the session start. (Records arrive before the summary session mesg in a FIT
        // stream, so by the time we map they are all collected.)
        val route = mutableListOf<RouteSample>()
        val hr = mutableListOf<HrSample>()
        for (r in records) {
            val tMs = r.timestamp?.date?.time ?: continue
            if (tMs < startMs || tMs > endMs) continue
            val tSec = ((tMs - startMs) / 1000).toInt()
            val lat = r.positionLat?.let(::semicirclesToDegrees)
            val lng = r.positionLong?.let(::semicirclesToDegrees)
            if (lat != null && lng != null) {
                val altM = r.enhancedAltitude ?: r.altitude
                route += RouteSample(
                    tSec = tSec,
                    latE7 = (lat * 1e7).roundToInt(),
                    lngE7 = (lng * 1e7).roundToInt(),
                    altCm = altM?.let { (it * 100).roundToInt() } ?: RouteSample.NO_ALT,
                )
            }
            r.heartRate?.let { hr += HrSample(tSec = tSec, bpm = it.toInt() and 0xFF) }
        }

        return ImportedWorkout(
            workoutId = "fit-$startMs",
            activityType = sportName(mesg.sport),
            source = SOURCE_FIT,
            startMs = startMs,
            endMs = endMs,
            durationSec = durationSec,
            title = "",
            distanceM = (mesg.totalDistance ?: 0f).toLong(),
            activeEnergyKj = (calories * KCAL_TO_KJ).toLong(),
            avgHr = mesg.avgHeartRate?.toLong() ?: 0,
            route = route,
            heartRate = hr,
        )
    }

    /** FIT stores lat/long as **semicircles** — 2^31 semicircles per 180°. */
    private fun semicirclesToDegrees(semicircles: Int): Double = semicircles * (180.0 / 2147483648.0)

    private fun sportName(sport: Sport?): String = when (sport) {
        Sport.RUNNING -> "Running"
        Sport.WALKING, Sport.HIKING -> "Walking"
        Sport.CYCLING -> "Cycling"
        Sport.TRAINING -> "Strength"
        else -> "Other"
    }

    private const val SOURCE_FIT = "Fit"
    private const val KCAL_TO_KJ = 4.184
}
