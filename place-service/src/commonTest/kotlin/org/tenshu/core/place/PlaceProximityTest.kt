package org.tenshu.core.place

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **§32.17 — a vague place must not swallow a precisely-located one.**
 *
 * `PlaceProximity` had no test of its own. The rule it encodes is load-bearing for both layers that
 * ask "are these the same venue?", and the numbers below are the real ones from the reported store —
 * a U-Bahn entrance that had **two** `Place` rows 244 m apart, with the user's office 256 m from the
 * same station.
 *
 * ⚠⚠ That 12 m gap is why the obvious fix — a wider ceiling on [PlaceProximity.radiusFor] — was
 * rejected: it folds the duplicate pair at 250 m and folds the **office into the station** at 300 m.
 * Distance alone cannot separate them, and [aWiderCeilingCannotSeparateTheseAtAll] pins that so
 * nobody re-derives the tempting one-constant "fix".
 */
class PlaceProximityTest {

    // Munich, near Odeonsplatz. ~89.9 E7 of latitude ≈ 1 m; ~148 E7 of longitude ≈ 1 m at 48°N.
    private val lat0 = 481_420_000L
    private val lng0 = 115_740_000L

    private fun northOf(metres: Double) = lat0 + (metres * 89.9).toLong()

    private fun apart(metres: Double, accA: Int, accB: Int): Boolean =
        PlaceProximity.isSamePlace(lat0, lng0, accA, northOf(metres), lng0, accB)

    // ── the reported cluster ──────────────────────────────────────────────────

    @Test
    fun twoRowsForOneStationEntranceFold() {
        // Both ±300 because §32.10 made accuracy the worse of fix quality and cluster spread, and a
        // stay the user walks through genuinely spans that much. 244 m apart, and neither row can
        // tell the difference — that is what makes them one venue.
        assertTrue(apart(244.0, accA = 300, accB = 300))
    }

    @Test
    fun theOfficeIsNotSwallowedByTheStationBesideIt() {
        // ⚠⚠ The adversary, and the whole reason for the second rule. The office is known to ±100 m;
        // the station admits ±300. Under the "worse accuracy" rule the station reaches out and takes
        // it. Under "both must be uncertain", the office's own confidence refuses.
        assertFalse(apart(256.0, accA = 100, accB = 300))
    }

    @Test
    fun theOfficeIsRefusedByAMarginRatherThanByAHair() {
        // ⭐ Where the office actually stops folding, measured rather than asserted from memory: the
        // binding constraint is `radiusFor`'s **unchanged** 150 m ceiling (worse accuracy = 300), not
        // the new rule's 100 m — the union means the looser of the two decides. So the office folds
        // out to 150 m and is refused beyond it, and at its real 256 m that is **106 m** of margin.
        //
        // ⚠ Not the 156 m an earlier reading of this claimed: the new rule alone would refuse at
        // 101 m, but the old rule is still there and still says yes. A union's margin is set by its
        // most permissive arm, which is exactly the sort of thing that is wrong until it is run.
        assertTrue(apart(149.0, accA = 100, accB = 300), "still inside the unchanged 150 m ceiling")
        assertFalse(apart(151.0, accA = 100, accB = 300), "…and refused immediately beyond it")
        assertFalse(apart(256.0, accA = 100, accB = 300), "the real separation, refused by 106 m")
    }

    @Test
    fun aWiderCeilingCannotSeparateTheseAtAll() {
        // ⚠⚠ The measurement that rejected the one-constant fix, kept as a test so it is not
        // re-derived. Any rule keyed on the WORSE accuracy sees both pairs identically (300), so the
        // only knob is the ceiling — and the two distances are 12 m apart. There is no value that
        // folds the duplicate pair and refuses the office.
        assertEquals(300, maxOf(300, 300), "duplicate pair: worse accuracy")
        assertEquals(300, maxOf(100, 300), "office pair: the SAME worse accuracy")
        assertTrue(256.0 - 244.0 < 15.0, "…and only 12 m of daylight between the two distances")
    }

    // ── the rule the union must not break ─────────────────────────────────────

    @Test
    fun aPoorIndoorFixStillFoldsOntoAPreciselyKnownHome() {
        // ⚠⚠ The §8.2 regression this must not cause. A ±100 m indoor dwell 80 m from a ±16 m home is
        // exactly what F1 exists to fold; the mutual-uncertainty rule alone would refuse it (better
        // accuracy = 16 → 60 m floor), which is why the two rules are a UNION and not a replacement.
        assertTrue(apart(80.0, accA = 100, accB = 16))
        assertFalse(
            80.0 <= PlaceProximity.mutualUncertaintyRadiusFor(100, 16),
            "precondition: the new rule alone would have refused this — the union is what saves it",
        )
    }

    @Test
    fun theUnionOnlyEverAddsFolds() {
        // The monotonicity claim stated directly: anything the old rule folded still folds.
        val cases = listOf(0 to 0, 0 to 50, 5 to 5, 16 to 100, 100 to 300, 300 to 300, 40 to 40)
        for ((a, b) in cases) {
            val old = PlaceProximity.radiusFor(a, b)
            assertTrue(
                apart(old - 1.0, a, b),
                "a pair inside the old radius ($a/$b, ${old - 1} m) must still fold",
            )
        }
    }

    // ── the abstention ────────────────────────────────────────────────────────

    @Test
    fun anUnreportedAccuracyMakesTheNewRuleAbstainRatherThanGuess() {
        // ⚠ 0 = NOT REPORTED. Treating it as "0 m, therefore mutually certain" would silently refuse,
        // and treating it as "unbounded" would fold across a district. It abstains; `radiusFor`'s
        // default still answers, so an import with no accuracies behaves exactly as before.
        assertEquals(0.0, PlaceProximity.mutualUncertaintyRadiusFor(0, 300))
        assertEquals(0.0, PlaceProximity.mutualUncertaintyRadiusFor(300, 0))
        assertEquals(PlaceProximity.SAME_PLACE_DEFAULT_RADIUS_M, PlaceProximity.radiusFor(0, 0))
        assertTrue(apart(90.0, accA = 0, accB = 0), "the default rule still decides for an import")
    }

    @Test
    fun theFloorAndCeilingBothApply() {
        assertEquals(PlaceProximity.SAME_PLACE_MIN_RADIUS_M, PlaceProximity.mutualUncertaintyRadiusFor(5, 5))
        assertEquals(
            PlaceProximity.MUTUAL_UNCERTAINTY_MAX_RADIUS_M,
            PlaceProximity.mutualUncertaintyRadiusFor(5_000, 4_000),
        )
    }
}
