package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure coverage for the GPX/TCX → [ImportedHealth] parser (P5.3) — no proxy, sample files inline.
 * The parse logic is `commonMain`; here it runs through the JVM [JvmXmlReader] binding of the
 * `XmlReader` seam.
 *
 * ⚠ No `fit =` decoder, deliberately. Every case here is GPX or TCX, and the Garmin SDK binding
 * (`FitDecoder`, the only `FitBinaryDecoder` implementation) stays in `:fit-shared-data`'s desktop
 * source set with its own round-trip coverage in `FitActivityParseTest`. Passing it here would
 * have pulled a JVM-only SDK into this module for a constructor argument no assertion reads.
 */
class ActivityFileParserTest {

    private val parser = ActivityFileParser(xml = JvmXmlReader())

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test">
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

    /**
     * A **Google Fit** export's real shape, which the schema-ordered [tcx] fixture above does not
     * exercise: one walk arrives as a run of `<Activity>` segments, each lap writes its `<Track>`
     * *before* its summary fields, and every trackpoint carries a `<DistanceMeters>` of its own —
     * the first of which is always `0.0`.
     */
    private val googleFitTcx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase>
          <Activities>
            <Activity Sport="Walking">
              <Id>2026-07-06T09:00:00Z</Id>
              <Lap StartTime="2026-07-06T09:00:00Z">
                <Track>
                  <Trackpoint>
                    <DistanceMeters>0.0</DistanceMeters>
                    <Time>2026-07-06T09:00:00Z</Time>
                    <Position><LatitudeDegrees>52.5200</LatitudeDegrees><LongitudeDegrees>13.4050</LongitudeDegrees></Position>
                  </Trackpoint>
                  <Trackpoint>
                    <DistanceMeters>100.0</DistanceMeters>
                    <Time>2026-07-06T09:00:30Z</Time>
                    <Position><LatitudeDegrees>52.5210</LatitudeDegrees><LongitudeDegrees>13.4060</LongitudeDegrees></Position>
                  </Trackpoint>
                </Track>
                <DistanceMeters>100.0</DistanceMeters>
                <TotalTimeSeconds>60</TotalTimeSeconds>
                <Calories>10</Calories>
              </Lap>
            </Activity>
            <Activity Sport="Walking">
              <Id>2026-07-06T09:05:00Z</Id>
              <Lap StartTime="2026-07-06T09:05:00Z">
                <Track>
                  <Trackpoint>
                    <DistanceMeters>0.0</DistanceMeters>
                    <Time>2026-07-06T09:05:00Z</Time>
                    <Position><LatitudeDegrees>52.5220</LatitudeDegrees><LongitudeDegrees>13.4070</LongitudeDegrees></Position>
                  </Trackpoint>
                  <Trackpoint>
                    <DistanceMeters>150.0</DistanceMeters>
                    <Time>2026-07-06T09:05:30Z</Time>
                    <Position><LatitudeDegrees>52.5230</LatitudeDegrees><LongitudeDegrees>13.4080</LongitudeDegrees></Position>
                  </Trackpoint>
                </Track>
                <DistanceMeters>150.0</DistanceMeters>
                <TotalTimeSeconds>90</TotalTimeSeconds>
                <Calories>15</Calories>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
    """.trimIndent().encodeToByteArray()

    @Test
    fun mergesGoogleFitActivitySegmentsIntoTheOneSessionTheyCameFrom() {
        val w = parser.parse(ActivityFile("walk.tcx", googleFitTcx)).workouts.single()
        assertEquals("Walking", w.activityType)
        assertEquals(150, w.durationSec)                  // 60 + 90 active seconds, gap excluded
        // The route is the point: each segment alone carries two fixes, and a real export's segments
        // often carry one or none — split, none of them clears the two-point floor the map needs.
        assertEquals(4, w.route.size)
        assertEquals(525_200_000, w.route.first().latE7)
        assertEquals(0, w.route.first().tSec)
        // Re-based on the session start (09:00:00), not on the second segment's own start.
        assertEquals(330, w.route.last().tSec)            // 09:05:30 − 09:00:00
        // Content-addressed on the *session* start, so a re-import folds onto the same row.
        assertEquals("tcx-${w.startMs}", w.workoutId)
        assertEquals(kotlin.time.Instant.parse("2026-07-06T09:00:00Z").toEpochMilliseconds(), w.startMs)
    }

    @Test
    fun readsLapSummaryFieldsThatFollowTheTrackRatherThanTheFirstTrackpointsDistance() {
        val w = parser.parse(ActivityFile("walk.tcx", googleFitTcx)).workouts.single()
        // A descendant read would return the first trackpoint's running total — 0.0 — for every lap.
        assertEquals(250, w.distanceM)                    // 100 + 150
        assertEquals(105, w.activeEnergyKj)               // (10 + 15) kcal * 4.184 ≈ 105 kJ
    }

    @Test
    fun keepsDistinctSportsInOneFileAsDistinctWorkouts() {
        val multiSport = googleFitTcx.decodeToString()
            .replaceFirst("""<Activity Sport="Walking">""", """<Activity Sport="Biking">""")
            .encodeToByteArray()
        val workouts = parser.parse(ActivityFile("multi.tcx", multiSport)).workouts
        assertEquals(setOf("Cycling", "Walking"), workouts.map { it.activityType }.toSet())
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
    fun parseAllMergesWorkouts() {
        val merged = parser.parseAll(listOf(ActivityFile("run.gpx", gpx), ActivityFile("ride.tcx", tcx)))
        assertEquals(2, merged.workouts.size)
    }
}
