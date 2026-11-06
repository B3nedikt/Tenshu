package app.tenshu.timeline.data.capture

import org.tenshu.core.timeline.Moment
import org.tenshu.core.timeline.SubjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure, device-free coverage of the **visit-detection engine** (I3a,
 * `docs/proposals/timeline-ingestion.md` §4): a run of raw fixes that dwells becomes one visit;
 * pure movement and sub-threshold stops become none; two dwells split into two; and the mapping to
 * the neutral `ImportedTimeline` is content-addressed so repeat visits fold to one place and
 * re-detection is idempotent. The Android sensor + foreground service that *feed* this are I3b
 * (device-gated); this stays hardware-free.
 */
class VisitDetectorTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    // A café near (52.5200°, 13.4050°); jitter within a few tens of metres of the anchor.
    private val cafeLat = 525_200_000L
    private val cafeLng = 134_050_000L

    @Test
    fun aDwellBecomesOneVisit() {
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 1_000, cafeLng + 1_000, t0 + 2 * min),
            CapturedFix(cafeLat - 1_000, cafeLng - 1_000, t0 + 4 * min),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 6 * min),
        )
        val visits = VisitDetector.detect(fixes)

        assertEquals(1, visits.size)
        val v = visits.single()
        assertEquals(t0, v.arrivalMs)
        assertEquals(t0 + 6 * min, v.departureMs)
        assertEquals(6 * min, v.durationMs)
        // Centroid is the run's mean — near the café.
        assertTrue(v.latE7 in (cafeLat - 1_000)..(cafeLat + 1_000))
    }

    @Test
    fun pureMovementYieldsNoVisit() {
        // Each fix ~1.3 km from the last — never dwells.
        val moving = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 100_000, cafeLng + 100_000, t0 + 2 * min),
            CapturedFix(cafeLat + 200_000, cafeLng + 200_000, t0 + 4 * min),
        )
        assertEquals(emptyList(), VisitDetector.detect(moving))
    }

    @Test
    fun aStopBelowMinDwellIsRejected() {
        val brief = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 2 * min), // only 2 min < 5 min default
        )
        assertEquals(emptyList(), VisitDetector.detect(brief))
    }

    @Test
    fun twoDwellsWithTravelBetweenSplitIntoTwoVisits() {
        val homeLat = 525_300_000L
        val homeLng = 134_200_000L
        val fixes = listOf(
            // dwell A — café
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 3 * min),
            CapturedFix(cafeLat, cafeLng, t0 + 6 * min),
            // travel
            CapturedFix(cafeLat + 60_000, cafeLng + 70_000, t0 + 15 * min),
            // dwell B — home
            CapturedFix(homeLat, homeLng, t0 + 30 * min),
            CapturedFix(homeLat + 500, homeLng + 500, t0 + 33 * min),
            CapturedFix(homeLat, homeLng, t0 + 36 * min),
        )
        val visits = VisitDetector.detect(fixes)

        assertEquals(2, visits.size)
        assertEquals(t0, visits[0].arrivalMs)
        assertEquals(t0 + 30 * min, visits[1].arrivalMs)
    }

    @Test
    fun repeatVisitsToOnePlaceFoldToOnePlaceTwoMoments() {
        val day = 24 * 60 * min
        val visits = listOf(
            CapturedVisit(cafeLat, cafeLng, t0, t0 + 6 * min),
            CapturedVisit(cafeLat + 300, cafeLng + 300, t0 + day, t0 + day + 5 * min), // ~few m away
        )
        val timeline = VisitDetector.capturedVisitsToTimeline(visits)

        assertEquals(1, timeline.places.size)
        assertEquals(2, timeline.moments.size)

        val moment = timeline.moments.first()
        assertEquals(Moment.MomentKind.VISIT, moment.kind)
        assertEquals(SubjectKind.PLACE, moment.subjectKind)
        assertEquals(timeline.places.single().placeId, moment.subjectId)
        assertEquals(6 * 60L, moment.durationSec) // 6 min
    }

    @Test
    fun detectionAndMappingAreIdempotent() {
        val fixes = listOf(
            CapturedFix(cafeLat, cafeLng, t0),
            CapturedFix(cafeLat + 500, cafeLng + 500, t0 + 3 * min),
            CapturedFix(cafeLat, cafeLng, t0 + 6 * min),
        )
        val a = VisitDetector.detectTimeline(fixes)
        val b = VisitDetector.detectTimeline(fixes)
        assertEquals(a.moments.map { it.momentId }, b.moments.map { it.momentId })
        assertEquals(a.places.map { it.placeId }, b.places.map { it.placeId })
        assertEquals(1, a.moments.size)
    }

    @Test
    fun tooFewFixesYieldNothing() {
        assertEquals(emptyList(), VisitDetector.detect(emptyList()))
        assertEquals(emptyList(), VisitDetector.detect(listOf(CapturedFix(cafeLat, cafeLng, t0))))
    }
}
