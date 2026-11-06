package app.tenshu.fit.data

import com.garmin.fit.DateTime
import com.garmin.fit.Fit
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.garmin.fit.File as FitFile
import java.io.File as IoFile

/**
 * Round-trips binary **FIT** through the parser (P5.3c): encode a one-session `.fit` with the FIT
 * SDK's [FileEncoder], then [ActivityFileParser] decodes it back into a workout. Self-contained —
 * no external fixture file needed.
 */
class FitActivityParseTest {

    private val parser = ActivityFileParser(xml = app.tenshu.fit.data.xml.JvmXmlReader(), fit = FitDecoder)

    @Test
    fun decodesAFitSessionIntoAWorkout() {
        val startMs = 1_800_000_000_000L
        val tmp = IoFile.createTempFile("tenshu-fit", ".fit").apply { deleteOnExit() }

        // Two per-second records (position + altitude + HR) inside the session window → the streams.
        val latSemi = (52.5200 * 2147483648.0 / 180.0).toInt()
        val lngSemi = (13.4050 * 2147483648.0 / 180.0).toInt()
        FileEncoder(tmp, Fit.ProtocolVersion.V2_0).apply {
            write(
                FileIdMesg().apply {
                    type = FitFile.ACTIVITY
                    manufacturer = Manufacturer.DEVELOPMENT
                    product = 1
                    serialNumber = 12345L
                    timeCreated = DateTime(Date(startMs))
                },
            )
            write(
                RecordMesg().apply {
                    timestamp = DateTime(Date(startMs))
                    positionLat = latSemi
                    positionLong = lngSemi
                    altitude = 34f
                    heartRate = 130.toShort()
                },
            )
            write(
                RecordMesg().apply {
                    timestamp = DateTime(Date(startMs + 30_000))
                    positionLat = latSemi + 1000
                    positionLong = lngSemi + 1000
                    altitude = 36f
                    heartRate = 150.toShort()
                },
            )
            write(
                SessionMesg().apply {
                    sport = Sport.CYCLING
                    startTime = DateTime(Date(startMs))
                    totalElapsedTime = 3600f
                    totalDistance = 24000f
                    totalCalories = 600
                    avgHeartRate = 140.toShort()
                },
            )
            close()
        }

        val bytes = tmp.readBytes()
        assertEquals(ActivityFileParser.Format.Fit, parser.detectFormat(ActivityFile("ride.fit", bytes)))
        // Also detected by the ".FIT" magic with no/other extension.
        assertEquals(ActivityFileParser.Format.Fit, parser.detectFormat(ActivityFile("ride.dat", bytes)))

        val w = parser.parse(ActivityFile("ride.fit", bytes)).workouts.single()
        assertEquals("Cycling", w.activityType)
        assertEquals("Fit", w.source)
        assertEquals(3600, w.durationSec)
        assertEquals(24000, w.distanceM)
        assertEquals(2510, w.activeEnergyKj)     // 600 kcal * 4.184 ≈ 2510 kJ
        assertEquals(140, w.avgHr)
        assertTrue(w.workoutId.startsWith("fit-"))
        // Dense streams from the RecordMesgs: route (semicircles → E7 ≈ our degrees) + HR.
        assertEquals(2, w.route.size)
        assertTrue(w.route.first().latE7 in 525_100_000..525_300_000, "lat ≈ 52.52° (got ${w.route.first().latE7})")
        assertEquals(30, w.route.last().tSec)
        assertEquals(listOf(130, 150), w.heartRate.map { it.bpm })
    }
}
