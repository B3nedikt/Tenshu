package app.tenshu.timeline.data

import app.tenshu.timeline.data.capture.MOVEMENT_CLASSIFIER_EXTERNAL
import app.tenshu.timeline.data.capture.MovementMode
import app.tenshu.timeline.data.capture.MovementTracePolyline
import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import org.tenshu.timeline.common.MomentSourceTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **Google Timeline bulk-import parser** (I2,
 * `docs/proposals/timeline-ingestion.md` §3): both export vintages normalise into the neutral
 * [ImportedTimeline], visits carry a resolvable place subject, movement segments become the same
 * `MOVEMENT` moment + [ImportedMovementTrace] pair live capture writes (M7), and untrusted/garbage
 * input yields an honest empty result rather than a crash. The end-to-end write path is exercised by
 * `TimelinePipelineIntegrationTest`; this stays parser-only.
 */
class GoogleTimelineParserTest {

    // A single new-format `Timeline.json`: one café visit (with a Google placeId) + a walk that
    // carries a sampled `timelinePath` (the geometry the parser used to drop on the floor).
    private val newExport = """
        {
          "semanticSegments": [
            {
              "startTime": "2024-04-07T11:00:00.000+02:00",
              "endTime": "2024-04-07T12:22:00.000+02:00",
              "visit": {
                "topCandidate": {
                  "placeId": "ChIJcafe",
                  "semanticType": "Searched Address",
                  "placeLocation": { "latLng": "52.5200000°, 13.4050000°" }
                }
              }
            },
            {
              "startTime": "2024-04-07T12:22:00.000+02:00",
              "endTime": "2024-04-07T12:40:00.000+02:00",
              "activity": {
                "topCandidate": { "type": "walking", "probability": 0.92 },
                "distanceMeters": 1200
              },
              "timelinePath": [
                { "point": "52.5200000°, 13.4050000°", "time": "2024-04-07T12:22:00.000+02:00" },
                { "point": "52.5210000°, 13.4050000°", "time": "2024-04-07T12:30:00.000+02:00" },
                { "point": "52.5220000°, 13.4050000°", "time": "2024-04-07T12:40:00.000+02:00" }
              ]
            }
          ],
          "rawSignals": [ { "position": { "ignored": true } } ]
        }
    """.trimIndent()

    // Legacy Semantic Location History: a named place visit (E7 coords) + a driving segment.
    private val legacyExport = """
        {
          "timelineObjects": [
            {
              "placeVisit": {
                "location": {
                  "latitudeE7": 525200000,
                  "longitudeE7": 134050000,
                  "placeId": "ChIJcafe",
                  "name": "Blue Bottle"
                },
                "duration": {
                  "startTimestamp": "2021-04-07T09:00:00Z",
                  "endTimestamp": "2021-04-07T10:00:00Z"
                }
              }
            },
            {
              "activitySegment": {
                "duration": {
                  "startTimestampMs": "1617786000000",
                  "endTimestampMs": "1617787800000"
                },
                "activityType": "IN_PASSENGER_VEHICLE"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheNewOnDeviceExport() {
        val t = GoogleTimelineParser.parse(newExport)

        // The visit becomes a place (foreign-key id from Google's placeId, E7 coords parsed from
        // the degree string) + a Visit moment pointing at it; label is blank until geocoded.
        assertEquals(1, t.places.size)
        val place = t.places.single()
        assertEquals("g:ChIJcafe", place.placeId)
        assertEquals(525_200_000L, place.latE7)
        assertEquals(134_050_000L, place.lngE7)
        assertEquals("", place.label)

        val visit = t.moments.single { it.kind == Moment.MomentKind.VISIT }
        assertEquals(SubjectKind.PLACE, visit.subjectKind)
        assertEquals("g:ChIJcafe", visit.subjectId)
        assertEquals(4_920L, visit.durationSec) // 82 min
        assertEquals(listOf(MomentSourceTag.GOOGLE), visit.sources)

        // The movement segment is now a MOVEMENT moment pointing at a real trace — the same pair
        // live capture writes — instead of the subjectless Activity moment it used to become.
        val move = t.moments.single { it.kind == Moment.MomentKind.MOVEMENT }
        assertEquals(SubjectKind.TRACE, move.subjectKind)
        assertEquals("g-trace:${move.timestampMs}", move.subjectId)
        assertEquals("Walking · 1.2 km", move.title)
        assertEquals(listOf(MomentSourceTag.GOOGLE), move.sources)

        val trace = t.traces.single()
        assertEquals(MovementMode.WALK, trace.mode)
        assertEquals(1_200L, trace.distanceM) // Google's own distanceMeters, not a re-sum
        assertEquals("walking", trace.sourceActivityType) // raw label retained verbatim
        assertEquals(MOVEMENT_CLASSIFIER_EXTERNAL.toLong(), trace.classifierVersion)
        assertEquals(92L, trace.modeConfidence) // probability 0.92 → 0–100
        // The timelinePath became a real polyline, and direction survived (walk heads north).
        assertEquals(3L, trace.rawPointCount)
        assertEquals(525_200_000L, trace.startLatE7)
        assertEquals(525_220_000L, trace.endLatE7)
        assertTrue(trace.polyline.isNotEmpty())

        // Raw signals are deliberately dropped (§8) — nothing beyond the two moments.
        assertEquals(2, t.moments.size)
    }

    @Test
    fun parsesTheLegacySemanticHistory() {
        val t = GoogleTimelineParser.parse(legacyExport)

        val place = t.places.single()
        assertEquals("g:ChIJcafe", place.placeId)
        assertEquals(525_200_000L, place.latE7)
        assertEquals("Blue Bottle", place.label) // legacy exports name the place

        val visit = t.moments.single { it.kind == Moment.MomentKind.VISIT }
        assertEquals("Blue Bottle", visit.title)
        assertEquals("g:ChIJcafe", visit.subjectId)

        // The activity segment used epoch-ms timestamps; type maps to a readable title + a trace.
        val move = t.moments.single { it.kind == Moment.MomentKind.MOVEMENT }
        assertTrue(move.title.startsWith("Driving"), "was ${move.title}")
        assertEquals(1_800L, move.durationSec) // 30 min

        val trace = t.traces.single()
        assertEquals(MovementMode.DRIVE, trace.mode)
        assertEquals("IN_PASSENGER_VEHICLE", trace.sourceActivityType)
    }

    // ── M7: the transit distinction, and the paths that used to be dropped ───────────

    @Test
    fun transitTypesMapToTransitAndKeepTheirRawLabel() {
        // The whole point of retaining activityType: our own classifier cannot tell a train from a
        // car (§13.1), but Google already did — and bus/tram/subway/rail differ from each other too,
        // which `mode` deliberately flattens while sourceActivityType does not.
        fun traceFor(type: String) = GoogleTimelineParser.parse(
            legacyExport.replace("IN_PASSENGER_VEHICLE", type),
        ).traces.single()

        for (type in listOf("IN_TRAIN", "IN_SUBWAY", "IN_TRAM", "IN_BUS", "IN_FERRY")) {
            val trace = traceFor(type)
            assertEquals(MovementMode.TRANSIT, trace.mode, "$type should bucket as transit")
            assertEquals(type, trace.sourceActivityType, "$type must survive verbatim")
        }

        // A motorcycle is not transit, and is distinguishable from a car only via the raw label.
        val moto = traceFor("MOTORCYCLING")
        assertEquals(MovementMode.DRIVE, moto.mode)
        assertEquals("MOTORCYCLING", moto.sourceActivityType)

        // Flying now has a real bucket (capture reaches it via the airborne speed band), and the
        // raw label still rides along.
        val flight = traceFor("FLYING")
        assertEquals(MovementMode.FLIGHT, flight.mode)
        assertEquals("FLYING", flight.sourceActivityType)
    }

    @Test
    fun legacySimplifiedRawPathBecomesTheTraceGeometry() {
        val withPath = """
            {
              "timelineObjects": [
                {
                  "activitySegment": {
                    "duration": { "startTimestampMs": "1617786000000", "endTimestampMs": "1617787800000" },
                    "activityType": "IN_TRAIN",
                    "confidence": "HIGH",
                    "distance": 34200,
                    "simplifiedRawPath": {
                      "points": [
                        { "latE7": 525200000, "lngE7": 134050000, "timestampMs": "1617786000000", "accuracyMeters": 12 },
                        { "latE7": 525900000, "lngE7": 134900000, "timestampMs": "1617786900000", "accuracyMeters": 40 },
                        { "latE7": 526600000, "lngE7": 134050000, "timestampMs": "1617787800000", "accuracyMeters": 9 }
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val trace = GoogleTimelineParser.parse(withPath).traces.single()
        assertEquals(MovementMode.TRANSIT, trace.mode)
        assertEquals(34_200L, trace.distanceM)
        assertEquals(90L, trace.modeConfidence) // HIGH → 90
        assertEquals(3L, trace.rawPointCount)
        assertEquals(525_200_000L, trace.startLatE7)
        assertEquals(526_600_000L, trace.endLatE7)

        // Accuracy rode through the codec (M6 §12.1) — a coarse leg stays identifiable as coarse.
        // (The path bows east at the midpoint so Douglas–Peucker keeps all three; a straight run
        // would legitimately collapse to its endpoints and drop the middle accuracy with it.)
        val decoded = MovementTracePolyline.decode(trace.polyline)
        assertEquals(listOf(12, 40, 9), decoded.map { it.accuracyM })
    }

    @Test
    fun waypointPathIsUsedWhenThereAreNoTimestampedSamples() {
        // Waypoints carry no time at all, so the parser spreads them across the segment span. The
        // shape is real; the implied speeds are an interpolation artefact — which is exactly why an
        // imported trace's mode comes from the label, not from our speed classifier.
        val waypoints = """
            {
              "timelineObjects": [
                {
                  "activitySegment": {
                    "duration": { "startTimestampMs": "1617786000000", "endTimestampMs": "1617787800000" },
                    "activityType": "WALKING",
                    "waypointPath": {
                      "waypoints": [
                        { "latE7": 525200000, "lngE7": 134050000 },
                        { "latE7": 525300000, "lngE7": 134050000 },
                        { "latE7": 525400000, "lngE7": 134050000 }
                      ]
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val trace = GoogleTimelineParser.parse(waypoints).traces.single()
        assertEquals(3L, trace.rawPointCount)
        assertEquals(525_200_000L, trace.startLatE7)
        assertEquals(525_400_000L, trace.endLatE7)
        // Synthesised timestamps span the segment exactly.
        val decoded = MovementTracePolyline.decode(trace.polyline)
        assertEquals(1_617_786_000_000L, decoded.first().timestampMs)
        assertEquals(1_617_787_800_000L, decoded.last().timestampMs)
    }

    @Test
    fun endpointsPreserveDirectionWhenNoPathSurvives() {
        // No sampled path at all — the segment still yields a two-point trace so direction of travel
        // is renderable. The bbox alone could not express it (§12.1).
        val endpointsOnly = """
            {
              "timelineObjects": [
                {
                  "activitySegment": {
                    "duration": { "startTimestampMs": "1617786000000", "endTimestampMs": "1617787800000" },
                    "activityType": "IN_BUS",
                    "startLocation": { "latitudeE7": 525200000, "longitudeE7": 134050000 },
                    "endLocation": { "latitudeE7": 524000000, "longitudeE7": 134050000 }
                  }
                }
              ]
            }
        """.trimIndent()

        val trace = GoogleTimelineParser.parse(endpointsOnly).traces.single()
        assertEquals(2L, trace.rawPointCount)
        assertEquals(525_200_000L, trace.startLatE7)
        assertEquals(524_000_000L, trace.endLatE7) // heading south — order, not just extent
        assertTrue(trace.endLatE7 < trace.startLatE7)
    }

    @Test
    fun activityWithoutAnyGeometryStillBecomesAMovementMoment() {
        // Mode, span and duration are worth keeping even with nothing to draw.
        val trace = GoogleTimelineParser.parse(legacyExport).traces.single()
        assertEquals(0L, trace.rawPointCount)
        assertEquals(0L, trace.startLatE7)
        assertEquals(MovementMode.DRIVE, trace.mode)
        assertEquals(1, GoogleTimelineParser.parse(legacyExport).moments.count { it.kind == Moment.MomentKind.MOVEMENT })
    }

    @Test
    fun importedAndCapturedIdsDoNotCollide() {
        // g-* vs cap-* namespaces: the same journey seen by both sources must stay two rows until
        // MomentReconciler folds them, rather than one silently overwriting the other.
        val t = GoogleTimelineParser.parse(newExport)
        val move = t.moments.single { it.kind == Moment.MomentKind.MOVEMENT }
        assertTrue(move.momentId.startsWith("g-move:"), "was ${move.momentId}")
        assertTrue(t.traces.single().traceId.startsWith("g-trace:"))
    }

    @Test
    fun visitMomentIdIsStableAcrossReimport() {
        val a = GoogleTimelineParser.parse(newExport)
        val b = GoogleTimelineParser.parse(newExport)
        // Content-addressed ids → re-importing the same export folds to identical rows (idempotent).
        assertEquals(a.moments.map { it.momentId }, b.moments.map { it.momentId })
        assertEquals(a.places.map { it.placeId }, b.places.map { it.placeId })
    }

    @Test
    fun mergesAndDedupesAcrossFiles() {
        val merged = GoogleTimelineParser.parseAll(
            listOf(
                TimelineImportFile("a.json", newExport.encodeToByteArray()),
                TimelineImportFile("b.json", newExport.encodeToByteArray()), // same export twice
            ),
        )
        // The duplicate place + moments collapse by content-addressed id.
        assertEquals(1, merged.places.size)
        assertEquals(2, merged.moments.size)
    }

    @Test
    fun placeWithoutPlaceIdFallsBackToCoordinates() {
        val noId = """
            {
              "semanticSegments": [
                {
                  "startTime": "2024-04-07T11:00:00.000Z",
                  "endTime": "2024-04-07T11:30:00.000Z",
                  "visit": { "topCandidate": { "placeLocation": { "latLng": "40.0°, -3.0°" } } }
                }
              ]
            }
        """.trimIndent()
        val t = GoogleTimelineParser.parse(noId)
        assertEquals("g:400000000,-30000000", t.places.single().placeId)
    }

    @Test
    fun malformedInputYieldsEmptyNeverThrows() {
        assertTrue(GoogleTimelineParser.parse("not json at all {{{").isEmpty)
        assertTrue(GoogleTimelineParser.parse("{}").isEmpty)
        assertTrue(GoogleTimelineParser.parse("""{"somethingElse": 1}""").isEmpty)
        assertTrue(GoogleTimelineParser.parse("""{"semanticSegments": []}""").isEmpty)
    }
}
