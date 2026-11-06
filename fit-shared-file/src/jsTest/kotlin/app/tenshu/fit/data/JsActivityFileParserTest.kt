package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JsXmlReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The web (P5.3e follow-up, docs/proposals/fit-app.md §9.6) twin of the desktop
 * `ActivityFileParserTest`: the same `commonMain` GPX/TCX → [ImportedHealth] parser, run through the
 * browser [JsXmlReader] binding of the `XmlReader` seam (no [FitBinaryDecoder] — binary FIT stays
 * JVM-only, so the FIT branch is verified empty here). Runs on `:fit-shared-data:jsBrowserTest`.
 *
 * The GPX fixture declares `xmlns:gpxtpx` (unlike the JVM test's, which omits it) because the
 * browser `DOMParser` is namespace-aware and rejects an undeclared prefix — see [JsXmlReader]'s
 * namespace note. Real-world GPX always declares it, so this is the honest web fixture.
 */
class JsActivityFileParserTest {

    private val parser = ActivityFileParser(xml = JsXmlReader())

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <trk>
            <name>Morning run</name>
            <type>running</type>
            <trkseg>
              <trkpt lat="52.5200" lon="13.4050"><ele>34</ele><time>2026-07-06T07:14:00Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
              <trkpt lat="52.5230" lon="13.4090"><ele>35</ele><time>2026-07-06T07:24:00Z</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
            </trkseg>
          </trk>
        </gpx>
    """.trimIndent().encodeToByteArray()

    private val tcx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase>
          <Activities>
            <Activity Sport="Biking">
              <Id>2026-07-06T09:30:00Z</Id>
              <Lap StartTime="2026-07-06T09:30:00Z">
                <TotalTimeSeconds>3600</TotalTimeSeconds>
                <DistanceMeters>24000</DistanceMeters>
                <Calories>600</Calories>
                <AverageHeartRateBpm><Value>140</Value></AverageHeartRateBpm>
                <Track>
                  <Trackpoint>
                    <Time>2026-07-06T09:30:00Z</Time>
                    <Position><LatitudeDegrees>52.5200</LatitudeDegrees><LongitudeDegrees>13.4050</LongitudeDegrees></Position>
                    <AltitudeMeters>34</AltitudeMeters>
                    <HeartRateBpm><Value>135</Value></HeartRateBpm>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2026-07-06T09:30:30Z</Time>
                    <Position><LatitudeDegrees>52.5230</LatitudeDegrees><LongitudeDegrees>13.4090</LongitudeDegrees></Position>
                    <AltitudeMeters>35</AltitudeMeters>
                    <HeartRateBpm><Value>145</Value></HeartRateBpm>
                  </Trackpoint>
                </Track>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
    """.trimIndent().encodeToByteArray()

    @Test
    fun parsesGpxTrackIntoAWorkout() {
        val w = parser.parse(ActivityFile("run.gpx", gpx)).workouts.single()
        assertEquals("Running", w.activityType)
        assertEquals("File", w.source)
        assertEquals("Morning run", w.title)
        assertEquals(600, w.durationSec)                 // 07:14 → 07:24
        assertTrue(w.distanceM in 400..600, "distance should be the haversine sum, got ${w.distanceM}")
        assertTrue(w.workoutId.startsWith("gpx-"))
        // Dense streams: a RouteSample per fix (E7 lat/lng + altitude cm) and the HR extension.
        assertEquals(2, w.route.size)
        assertEquals(525_200_000, w.route.first().latE7)
        assertEquals(134_050_000, w.route.first().lngE7)
        assertEquals(3_400, w.route.first().altCm)       // 34 m → cm
        assertEquals(600, w.route.last().tSec)           // 10 min after the first fix
        assertEquals(listOf(120, 150), w.heartRate.map { it.bpm })
        assertEquals(135, w.avgHr)                       // (120 + 150) / 2
    }

    @Test
    fun parsesTcxActivityWithLapSummary() {
        val w = parser.parse(ActivityFile("ride.tcx", tcx)).workouts.single()
        assertEquals("Cycling", w.activityType)
        assertEquals(3600, w.durationSec)
        assertEquals(24000, w.distanceM)
        assertEquals(2510, w.activeEnergyKj)             // 600 kcal * 4.184 ≈ 2510 kJ
        assertEquals(140, w.avgHr)
        assertTrue(w.workoutId.startsWith("tcx-"))
        // Dense streams from the Trackpoints: route (E7 lat/lng + altitude cm) + HR.
        assertEquals(2, w.route.size)
        assertEquals(525_200_000, w.route.first().latE7)
        assertEquals(134_050_000, w.route.first().lngE7)
        assertEquals(3_400, w.route.first().altCm)
        assertEquals(30, w.route.last().tSec)            // 30 s after the first point
        assertEquals(listOf(135, 145), w.heartRate.map { it.bpm })
    }

    @Test
    fun detectsFormatByExtensionAndContent() {
        assertEquals(ActivityFileParser.Format.Gpx, parser.detectFormat(ActivityFile("a.gpx", gpx)))
        assertEquals(ActivityFileParser.Format.Tcx, parser.detectFormat(ActivityFile("a.tcx", tcx)))
        // Wrong/absent extension → sniff the content.
        assertEquals(ActivityFileParser.Format.Gpx, parser.detectFormat(ActivityFile("a.dat", gpx)))
        assertEquals(ActivityFileParser.Format.Tcx, parser.detectFormat(ActivityFile("a.dat", tcx)))
    }

    @Test
    fun garbageParsesToEmptyNeverThrows() {
        assertTrue(parser.parse(ActivityFile("junk.txt", "not xml".encodeToByteArray())).isEmpty)
        assertTrue(parser.parseAll(emptyList()).isEmpty)
    }

    @Test
    fun fitWithoutADecoderYieldsEmpty() {
        // Binary FIT is JVM-only (Garmin SDK); with no FitBinaryDecoder bound on JS the FIT branch
        // must degrade to empty, never fail (ActivityFileParser's `fit?.decode(...).orEmpty()`).
        val fitBytes = ByteArray(16).also {
            it[8] = '.'.code.toByte(); it[9] = 'F'.code.toByte(); it[10] = 'I'.code.toByte(); it[11] = 'T'.code.toByte()
        }
        assertEquals(ActivityFileParser.Format.Fit, parser.detectFormat(ActivityFile("a.fit", fitBytes)))
        assertTrue(parser.parse(ActivityFile("a.fit", fitBytes)).isEmpty)
    }

    @Test
    fun parseAllMergesWorkouts() {
        val merged = parser.parseAll(listOf(ActivityFile("run.gpx", gpx), ActivityFile("ride.tcx", tcx)))
        assertEquals(2, merged.workouts.size)
    }
}
