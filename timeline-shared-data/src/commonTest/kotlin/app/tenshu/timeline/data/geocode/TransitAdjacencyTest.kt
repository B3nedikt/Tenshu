package app.tenshu.timeline.data.geocode

import app.tenshu.timeline.data.geocode.TransitAdjacency.Leg
import app.tenshu.timeline.data.geocode.TransitAdjacency.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **§31 — the trajectory half of the naming decision, at the real legs.**
 *
 * Every fixture below is one of the four coarse dwells this rule can actually touch, with the modes
 * and confidences the store holds for them. The rule may only ever *tighten*, and the case that pins
 * that is Odeonsplatz: its outbound leg is the U5 ride classified `walk` with **confidence 0**, so a
 * gate that trusted `walk` would undo the label §28 fixed.
 */
class TransitAdjacencyTest {

    @Test
    fun theOdeonsplatzShapeIsUnknownNotNonTransit() {
        // ⚠⚠ The load-bearing case. walk/35 in, walk/conf=0 out — and that zero-confidence walk IS the
        // U5 ride. Reading it as "not transit" would tighten the radius past the 80 m station and put
        // "München" back on a U-Bahn platform.
        assertEquals(
            Verdict.UNKNOWN,
            TransitAdjacency.verdict(Leg("walk", 35), Leg("walk", 0)),
        )
    }

    @Test
    fun theOdeonsplatzRadiusIsThereforeUnchanged() {
        val v = TransitAdjacency.verdict(Leg("walk", 35), Leg("walk", 0))
        assertEquals(300, TransitAdjacency.searchRadiusM(v, accuracyBounded = 300))
    }

    @Test
    fun schwindeggIsTransitOnBothSides() {
        val v = TransitAdjacency.verdict(Leg("transit", 57), Leg("transit", 70))
        assertEquals(Verdict.TRANSIT, v)
        assertEquals(100, TransitAdjacency.searchRadiusM(v, accuracyBounded = 100))
    }

    @Test
    fun muehldorfsWeakRunAndDriveAreNotEvidenceAgainstTransit() {
        // run/conf=18 in, drive/conf=48 out — the train read as a drive. The weak run alone keeps this
        // UNKNOWN, which is what preserves the station label the maintainer's data already carries.
        val v = TransitAdjacency.verdict(Leg("run", 18), Leg("drive", 48))
        assertEquals(Verdict.UNKNOWN, v)
        assertEquals(100, TransitAdjacency.searchRadiusM(v, accuracyBounded = 100))
    }

    @Test
    fun aConfidentWalkOnBothSidesTightens() {
        // The case this exists for: a poor-fix dwell at a café, walked to and walked from. Measured,
        // 38 % of Paris cafés have a named stop within 150 m — without this they would be renamed.
        val v = TransitAdjacency.verdict(Leg("walk", 41), Leg("walk", 46))
        assertEquals(Verdict.NOT_TRANSIT, v)
        assertEquals(40, TransitAdjacency.searchRadiusM(v, accuracyBounded = 300))
    }

    @Test
    fun aStopUnderfootStillWinsAConfidentWalk() {
        // ⚠ Tightening is not a veto: a stop 6 m away (the maintainer's Schwindegg figure) is where you
        // were standing, whatever the surrounding legs say.
        val v = TransitAdjacency.verdict(Leg("walk", 41), Leg("walk", 46))
        assertEquals(40, TransitAdjacency.searchRadiusM(v, accuracyBounded = 40))
    }

    @Test
    fun oneMissingLegIsNeverEvidenceAgainstTransit() {
        // ⚠⚠ Both sides must exist. One confident walk beside an unclassified leg is the Odeonsplatz
        // shape again, and the first dwell of a day has no inbound leg at all.
        assertEquals(Verdict.UNKNOWN, TransitAdjacency.verdict(Leg("walk", 90), null))
        assertEquals(Verdict.UNKNOWN, TransitAdjacency.verdict(null, Leg("walk", 90)))
        assertEquals(Verdict.UNKNOWN, TransitAdjacency.verdict(null, null))
    }

    @Test
    fun eitherSideBeingTransitIsEnough() {
        // A metro hop's arrival leg can be classified before its departure is.
        assertEquals(Verdict.TRANSIT, TransitAdjacency.verdict(Leg("transit", 50), Leg("walk", 90)))
        assertEquals(Verdict.TRANSIT, TransitAdjacency.verdict(Leg("walk", 90), Leg("transit", 50)))
        assertEquals(Verdict.TRANSIT, TransitAdjacency.verdict(null, Leg("transit", 12)))
    }

    @Test
    fun theRuleIsMonotone() {
        // ⚠ It can only ever make the label more conservative. No verdict widens past what §28 chose.
        for (bound in listOf(40, 94, 100, 300)) {
            for (v in Verdict.entries) {
                val r = TransitAdjacency.searchRadiusM(v, bound)
                kotlin.test.assertTrue(r <= bound, "$v widened $bound to $r")
            }
        }
    }
}
