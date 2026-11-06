package org.tenshu.place.proxy

import org.tenshu.core.place.LabelSource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The [PlaceMerger] field rules, which had **no test at all** despite two of them being marked
 * "visible for tests".
 *
 * ⚠⚠ The accuracy rule is the one worth the most here, and it is the one that had none. Its old
 * `minOf` was correct for what the field used to mean (*fix quality* — tighter is better evidence) and
 * wrong for what §32.10 made it mean (*how well we know where this place is*). The reported bar row
 * is what that costs: a row minted at ±14 m under the pre-§32.10 rule could not be corrected by any
 * later honest measurement, on any device, ever — so the enricher kept naming a venue from a figure
 * nothing could reach.
 */
class PlaceMergerTest {

    private val merger = PlaceMerger()

    // ── accuracy: a measurement, not a claim ──────────────────────────────────

    @Test
    fun aLaterHonestMeasurementReplacesAnEarlierOptimisticOne() {
        // ⭐ The reported case, in one line: ±14 m minted under the old rule, re-measured at 128 m.
        assertEquals(128, merger.mergedAccuracy(existing = 14, incoming = 128))
    }

    @Test
    fun aLaterTighterMeasurementIsAlsoTaken() {
        // The symmetric direction, and the reason this is not `maxOf`: an accuracy must be able to
        // move BOTH ways, or one bad indoor visit coarsens a well-known venue forever.
        assertEquals(9, merger.mergedAccuracy(existing = 128, incoming = 9))
    }

    @Test
    fun neitherDirectionIsAnAbsorbingState() {
        // ⚠⚠ The property the two tests above are instances of, stated directly: `minOf` and `maxOf`
        // are both ratchets — once entered, a place can never leave the extreme. Applying the rule
        // repeatedly must track the readings, not accumulate toward one end.
        val readings = listOf(14, 128, 40, 200, 25)
        var merged = 0
        readings.forEach { merged = merger.mergedAccuracy(existing = merged, incoming = it) }
        assertEquals(25, merged, "the sequence must end at its last reading, not its min or max")
    }

    @Test
    fun unknownNeverOverwritesARealReading() {
        // 0 = NOT REPORTED, never "0 m" — the convention the whole capture path follows. A peer that
        // cannot measure must not erase a peer that can.
        assertEquals(64, merger.mergedAccuracy(existing = 64, incoming = 0))
    }

    @Test
    fun unknownOnBothSidesStaysUnknown() {
        assertEquals(0, merger.mergedAccuracy(existing = 0, incoming = 0))
    }

    @Test
    fun aFirstRealReadingFillsAnUnknown() {
        assertEquals(90, merger.mergedAccuracy(existing = 0, incoming = 90))
    }

    // ── labels: the rule this class was built for, pinned alongside ───────────

    @Test
    fun aUserLabelWinsWhicheverSideItIsOn() {
        // ⚠ Side-independence is the whole point — the outcome must not depend on which device pulled
        // first, so the same conflict is asserted both ways round.
        assertEquals(
            "Office" to LabelSource.USER,
            merger.mergeLabel("Office", LabelSource.USER, "München", LabelSource.GEOCODED),
        )
        assertEquals(
            "Office" to LabelSource.USER,
            merger.mergeLabel("München", LabelSource.GEOCODED, "Office", LabelSource.USER),
        )
    }

    @Test
    fun betweenTwoUserLabelsTheNewerWins() {
        // The precedent the accuracy rule now follows: the user changed their mind.
        assertEquals(
            "The Office" to LabelSource.USER,
            merger.mergeLabel("Office", LabelSource.USER, "The Office", LabelSource.USER),
        )
    }

    @Test
    fun aBlankCaptureNeverErasesAName() {
        assertEquals(
            "Bar" to LabelSource.GEOCODED,
            merger.mergeLabel("Bar", LabelSource.GEOCODED, "", LabelSource.GEOCODED),
        )
    }
}
