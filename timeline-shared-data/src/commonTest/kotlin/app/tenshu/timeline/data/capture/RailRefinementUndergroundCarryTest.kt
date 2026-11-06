package app.tenshu.timeline.data.capture

import app.tenshu.vectortile.GeoBounds
import app.tenshu.vectortile.LonLat
import app.tenshu.vectortile.RailMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§32.15 — the sweep may demote an underground verdict, but never silently forget it.**
 *
 * ### ⚠⚠ Why this could not be caught by the existing suite
 *
 * `RailRefinement.refine` recomputes `wentUnderground` from the fixes it is handed, and every test in
 * [RailRefinementTest] hands it a fix stream that still *contains* the blind interval. The sweep does
 * not: it decodes the **stored, decimated polyline** — 6 points for the reported U5 ride — where by
 * §27's own argument the evidence structurally cannot exist. The platform fixes are dropped as bad
 * geometry and Douglas–Peucker removes most of what survives.
 *
 * So the branch that stamps `RAIL_REFINED_UNDERGROUND` could never fire from a sweep, the coverage
 * branch stamped plain `RAIL_REFINED` instead, and the capture-time fact was **erased**. Measured on
 * the 2026-08-13 journey: capture stamped the U5 underground, the sweep re-stamped it `11`, and
 * `TimelineProjection.ranUndergroundOf` then read `false` for a metro hop.
 *
 * Every fixture here therefore passes `fixes = emptyList()` or a decimated stub — *that* is the
 * condition under test, not an omission.
 */
class RailRefinementUndergroundCarryTest {

    private val baseLat = 52.0
    private val baseLon = 13.0

    private fun path(n: Int) = (0 until n).map { LonLat(lon = baseLon, lat = baseLat + it * 0.0001) }

    private fun rail(kind: String, points: List<LonLat>) =
        RailMatcher.RailLine(kind, points, GeoBounds.of(points)!!)

    private fun surveyed(vararg lines: RailMatcher.RailLine) = RailEvidence.Surveyed(lines.toList())

    private fun classification(mode: String, version: Int) =
        ModeClassification(mode = mode, confidence = 60, medianSpeedMmPerS = 4_000, version = version)

    /** Subway track directly under the path — the shape that gives exclusive coverage 1.0. */
    private fun subwayUnderPath(n: Int) = surveyed(rail("subway", path(n)))

    @Test
    fun aCaptureTimeUndergroundVerdictSurvivesASweepThatCannotSeeTheBlackout() {
        // ⭐ The reported U5 ride, as the sweep actually sees it: a decimated polyline, no fixes, and
        // subway track underneath. Before §32.15 this returned RAIL_REFINED and the metro fact was gone.
        val before = classification(MovementMode.TRANSIT, MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND)
        val after = RailRefinement.refine(before, path(6), subwayUnderPath(6), fixes = emptyList())

        assertEquals(MovementMode.TRANSIT, after.mode)
        assertEquals(
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND, after.version,
            "the sweep confirmed the ride and must carry the underground fact forward",
        )
        assertTrue(!RailRefinement.needsRefinement(after), "and it counts as swept")
    }

    @Test
    fun anAlreadySweptUndergroundVerdictIsNotDowngradedByALaterSweep() {
        // The sweep is re-offered whenever its own version moves, so it re-examines its own output.
        // Idempotence matters here more than usual: a downgrade would be invisible and permanent.
        val before = classification(
            MovementMode.TRANSIT,
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND,
        )
        val after = RailRefinement.refine(before, path(6), subwayUnderPath(6), fixes = emptyList())
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED_UNDERGROUND, after.version)
    }

    // ── …and the half that must NOT change: the map tier may still demote ──────

    @Test
    fun aPathTheMapWalksBackToDriveLosesTheUndergroundClaimWithIt() {
        // ⚠⚠ M9-3 intact: "the map tier is the one allowed to demote". Carrying the fact forward must
        // not make a capture-time promotion unfalsifiable — a leg the tier positively puts on no track
        // is a `drive`, and a demoted verdict has no underground claim left to keep.
        val before = classification(MovementMode.TRANSIT, MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND)
        val after = RailRefinement.refine(before, path(20), surveyed(), fixes = emptyList())

        assertEquals(MovementMode.DRIVE, after.mode, "surveyed-and-empty is real negative evidence")
        assertEquals(
            MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version,
            "a walked-back verdict must not keep claiming it ran underground",
        )
    }

    @Test
    fun anOrdinarySurfaceTrainIsStillStampedPlainRailRefined() {
        // The control that stops this becoming "everything is underground": a `drive` baseline the
        // sweep promotes on exclusive track has no underground evidence anywhere and must not gain any.
        val before = classification(MovementMode.DRIVE, MOVEMENT_CLASSIFIER_VERSION)
        val after = RailRefinement.refine(before, path(20), surveyed(rail("rail", path(20))))

        assertEquals(MovementMode.TRANSIT, after.mode)
        assertEquals(MOVEMENT_CLASSIFIER_VERSION_RAIL_REFINED, after.version)
    }

    @Test
    fun unavailableEvidenceLeavesACaptureTimeUndergroundVerdictAloneToRetry() {
        // ⚠ Unchanged behaviour, pinned here because it is the case where "carry it forward" would be
        // actively wrong: we could not look, so the row must stay refinable rather than be stamped.
        val before = classification(MovementMode.TRANSIT, MOVEMENT_CLASSIFIER_VERSION_UNDERGROUND)
        val after = RailRefinement.refine(before, path(6), RailEvidence.Unavailable)

        assertEquals(before, after)
        assertTrue(RailRefinement.needsRefinement(after), "a sweep that could not look must retry")
    }
}
