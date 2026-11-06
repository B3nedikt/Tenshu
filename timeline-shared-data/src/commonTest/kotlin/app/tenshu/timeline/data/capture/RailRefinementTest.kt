package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rail-refinement rule (M9-3): T1/T2′ evidence applied to a trace already classified at capture
 * time.
 *
 * The cases worth the most here are the **negative-evidence** ones. T1 is the only tier permitted to
 * walk a verdict back, and the entire safety of that rests on distinguishing "we looked and there is
 * no track" from "we could not look" — get that wrong and every train taken outside a downloaded
 * region silently becomes a drive.
 */
class RailRefinementTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    /** A straight north-running path of [n] points ~11 m apart. */
    private fun path(n: Int, lonOffsetDeg: Double = 0.0): List<LonLat> =
        (0 until n).map { LonLat(lon = baseLon + lonOffsetDeg, lat = baseLat + it * 0.0001) }

    /**
     * The same shape at a chosen scale — [n] points [stepDeg]° of latitude apart (0.01° ≈ 1.11 km).
     *
     * ⚠ A separate helper rather than a parameter on [path] because the §39 long-haul rule is about
     * **metres**, and every existing case here is 11 m per step: reusing [path] would test the new
     * branch on a 200 m "journey" and pass for the wrong reason.
     *
     * ⚠⚠ Keep the step well above `RailMatcher.DEFAULT_TOLERANCE_M` (50 m) whenever a test puts track
     * under only *part* of the path. At 11 m per step the four points after the track's end are still
     * within tolerance of it and match anyway — so "track under 9 of 20 points" silently becomes 0.65
     * coverage, not 0.45, and a case written to sit below the strict bar lands above it.
     */
    private fun longPath(n: Int, stepDeg: Double = 0.01): List<LonLat> =
        (0 until n).map { LonLat(lon = baseLon, lat = baseLat + it * stepDeg) }

    private fun rail(kind: String, points: List<LonLat>) =
        RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)

    private fun classification(mode: String, version: Int = MOVEMENT_CLASSIFIER_VERSION) =
        ModeClassification(mode = mode, confidence = 60, medianSpeedMmPerS = 20_000, version = version)

    private fun surveyed(vararg lines: RailMatcher.RailLine) = RailEvidence.Surveyed(lines.toList())

    // ── the guard that makes demotion safe ───────────────────────────────────

    @Test
    fun unavailableEvidenceChangesNothingAndLeavesTheTraceUnstamped() {
        // ⚠⚠ The most important case. A region that was never downloaded must not be read as
        // "no track here" — otherwise every train outside downloaded coverage becomes a drive.
        val before = classification(MovementMode.TRANSIT)
        val after = RailRefinement.refine(before, path(20), RailEvidence.Unavailable)
        assertEquals(before, after)
        // Version did not advance ⇒ a later sweep retries once tiles exist.
        assertTrue(RailRefinement.needsRefinement(after))
    }

    @Test
    fun surveyedButEmptyIsRealEvidenceUnlikeUnavailable() {
        // The same absence, but this time we actually looked. T0's shape guess is overruled.
        val after = RailRefinement.refine(classification(MovementMode.TRANSIT), path(20), surveyed())
        assertEquals(MovementMode.DRIVE, after.mode)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
        assertTrue(!RailRefinement.needsRefinement(after))
    }

    // ── promotion ────────────────────────────────────────────────────────────

    @Test
    fun aDriveOnHeavyRailBecomesTransit() {
        val track = path(20)
        val after = RailRefinement.refine(classification(MovementMode.DRIVE), track, surveyed(rail("rail", track)))
        assertEquals(MovementMode.TRANSIT, after.mode)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
        assertTrue(after.confidence >= 70, "direct evidence should be confident, was ${after.confidence}")
    }

    @Test
    fun tramCoverageAloneDoesNotPromoteADrive() {
        // A car driving down a tram street: full coverage, but of track it can legally occupy, and
        // T0 saw nothing rail-like. Must stay a drive.
        val track = path(20)
        val after = RailRefinement.refine(classification(MovementMode.DRIVE), track, surveyed(rail("tram", track)))
        assertEquals(MovementMode.DRIVE, after.mode)
        // Still stamped — we looked and concluded; no need to revisit.
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
    }

    @Test
    fun tramCoverageWithStopsOnTrackDoesPromote() {
        // T2′: a car does not repeatedly come to a full stop *on* the rails. Two such stops are the
        // corroboration street-running coverage needs.
        val track = path(20)
        val after = RailRefinement.refine(
            classification(MovementMode.DRIVE),
            track,
            surveyed(rail("tram", track)),
            stops = listOf(track[5], track[15]),
        )
        assertEquals(MovementMode.TRANSIT, after.mode)
    }

    @Test
    fun tramCoverageCorroboratedByT0Promotes() {
        // T0 independently read the shape as transit; tram geometry then confirms it.
        val track = path(20)
        val after = RailRefinement.refine(classification(MovementMode.TRANSIT), track, surveyed(rail("tram", track)))
        assertEquals(MovementMode.TRANSIT, after.mode)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
    }

    // ── what refinement must not touch ───────────────────────────────────────

    @Test
    fun anExternallyLabelledTraceIsNeverOverwritten() {
        // Google's activityType distinguishes bus from tram from rail — finer than any tier of ours.
        val imported = classification(MovementMode.DRIVE, version = MOVEMENT_CLASSIFIER_EXTERNAL)
        val track = path(20)
        assertEquals(imported, RailRefinement.refine(imported, track, surveyed(rail("rail", track))))
        assertTrue(!RailRefinement.needsRefinement(imported))
    }

    @Test
    fun nonVehicleModesAreLeftAlone() {
        // A walk along a railway path is still a walk; a flight is settled by a speed band that no
        // track can contradict.
        val track = path(20)
        for (mode in listOf(MovementMode.WALK, MovementMode.RUN, MovementMode.CYCLE, MovementMode.FLIGHT)) {
            val before = classification(mode)
            assertEquals(before, RailRefinement.refine(before, track, surveyed(rail("rail", track))))
            assertTrue(!RailRefinement.needsRefinement(before))
        }
    }

    @Test
    fun aPathAwayFromTrackLeavesADriveAsADriveButStampsIt() {
        val after = RailRefinement.refine(
            classification(MovementMode.DRIVE),
            path(20),
            surveyed(rail("rail", path(20, lonOffsetDeg = 0.02))), // ~1.4 km away
        )
        assertEquals(MovementMode.DRIVE, after.mode)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
    }

    @Test
    fun anEmptyPathConcludesNothing() {
        val before = classification(MovementMode.DRIVE)
        assertEquals(before, RailRefinement.refine(before, emptyList(), surveyed(rail("rail", path(20)))))
    }

    // ── the sweep's candidate query ──────────────────────────────────────────

    @Test
    fun needsRefinementSelectsExactlyTheGroundVehicleTracesNotYetSwept() {
        assertTrue(RailRefinement.needsRefinement(classification(MovementMode.DRIVE)))
        assertTrue(RailRefinement.needsRefinement(classification(MovementMode.TRANSIT)))
        // Already swept.
        assertTrue(
            !RailRefinement.needsRefinement(
                classification(MovementMode.DRIVE, version = MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED),
            ),
        )
        // Not ours to touch.
        assertTrue(
            !RailRefinement.needsRefinement(
                classification(MovementMode.DRIVE, version = MOVEMENT_CLASSIFIER_EXTERNAL),
            ),
        )
        // Not a ground vehicle.
        assertTrue(!RailRefinement.needsRefinement(classification(MovementMode.WALK)))
    }

    @Test
    fun aTraceSweptByAnEarlierRefinementIsOfferedAgain() {
        // ⚠ This is the whole mechanism behind bumping MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED: a
        // trace stamped by *any* earlier refinement must come back as a candidate, or a new tier (B4
        // route snapping) would only ever reach journeys recorded after it shipped. Pinned as a
        // property of "below current", not of one literal, so the next bump inherits it.
        for (previous in 1 until MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED) {
            assertTrue(
                RailRefinement.needsRefinement(classification(MovementMode.TRANSIT, version = previous)),
                "a trace stamped at v$previous must be re-offered",
            )
        }
        // And concretely: v8 is what the pre-B4 sweep stamped, so forgetting to bump the constant
        // when adding a tier is caught here rather than discovered as "snapping never happens".
        // ⚠ Raise this literal at the next bump — it names the previous shipped stamp, not the current one.
        assertTrue(RailRefinement.needsRefinement(classification(MovementMode.TRANSIT, version = 8)))
    }

    // ── §39: the long-haul promote route ─────────────────────────────────────

    @Test
    fun aLongRunOnExclusiveTrackPromotesBelowTheFractionBar() {
        // 20 points ~1.11 km apart; track under the first 9 ⇒ coverage 0.45, i.e. UNDER the strict
        // bar and inside the 0.39–0.67 band ten real runs of one train actually scored (§39).
        // What carries it is the ~9.4 km demonstrably on rail, which no road vehicle can be.
        val track = longPath(20)
        val after = RailRefinement.refine(
            classification(MovementMode.DRIVE),
            track,
            surveyed(rail("rail", track.take(9))),
        )
        assertEquals(MovementMode.TRANSIT, after.mode, "a long run on exclusive track is a train")
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
    }

    @Test
    fun theSameFractionOverAShortPathIsStillDemoted() {
        // ⚠⚠ The twin of the case above, and the one that proves the new branch is a *disjunct*
        // rather than a loosened bar: identical 0.45 coverage, identical geometry, only the scale
        // differs — 111 m per step instead of 1.11 km, so ~0.9 km on rail against the 8 km bar.
        // Delete LONG_HAUL_EXCLUSIVE_METERS's metre test and this is the assertion that goes red.
        val track = longPath(20, stepDeg = 0.001)
        val after = RailRefinement.refine(
            classification(MovementMode.TRANSIT),
            track,
            surveyed(rail("rail", track.take(9))),
        )
        assertEquals(MovementMode.DRIVE, after.mode, "0.9 km beside track is not a train")
    }

    @Test
    fun eightKilometresSpreadThroughALongDriveDoesNotPromote() {
        // The shape LONG_HAUL_MIN_EXCLUSIVE_COVERAGE exists for: the metre bar is cleared (~8.3 km)
        // while rail describes almost none of the journey (0.08). Without the floor this branch
        // would turn a long drive along a rail corridor into a train.
        val track = longPath(100)
        val after = RailRefinement.refine(
            classification(MovementMode.TRANSIT),
            track,
            surveyed(rail("rail", track.take(8))),
        )
        assertEquals(MovementMode.DRIVE, after.mode, "incidental contact within a long drive is not a train")
    }

    @Test
    fun theLongHaulRouteStillWillNotPromoteTramTrack() {
        // ⚠ Street-running track is excluded from exclusiveMatchedMeters exactly as it is from
        // exclusiveCoverage, so length cannot buy what the kind refuses: a car on a tram street for
        // 10 km must not become a train.
        val track = longPath(20)
        val after = RailRefinement.refine(
            classification(MovementMode.DRIVE),
            track,
            surveyed(rail("tram", track.take(9))),
        )
        assertEquals(MovementMode.DRIVE, after.mode, "a tram street is not exclusive track at any length")
    }
}
