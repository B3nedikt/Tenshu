package org.tenshu.inference.proxy

import org.tenshu.inference.proxy.onnx.VisionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correcting `VisionModel.PinnedSigLip`'s constants changed **no search result** (2026-10-11).
 *
 * `photosLike` admits a photograph when `calibration.probability(cosine) >= floor`. Until that day
 * the calibration was the `-224` checkpoint's and the floor was `0.10`; now the calibration is the
 * pinned checkpoint's and the floor is the probability *it* gives the same cosine. This proves the
 * two pairs admit the same cosines, on whichever platform runs it.
 *
 * ⚠ The first pair is **history**, kept here as literals and nowhere else. It is not a second
 * calibration and nothing may read it.
 *
 * ⚠⚠ What this does not say: that the floor is *right*. Its cosine was carried over from a number
 * measured on another checkpoint, and re-measuring it is owed (`inference-service.md` §35.1).
 */
class SemanticFloorCarryOverTest {

    /** History: what `PinnedSigLip.calibration` carried until 2026-10-11, the `-224` checkpoint's. */
    private val formerCalibration = ScoreCalibration(scale = 117.330765f, bias = -12.932437f)

    /** History: `SEMANTIC_RELEVANCE_FLOOR` while it was read against [formerCalibration]. */
    private val formerFloor = 0.10f

    private val calibration = VisionModel.PinnedSigLip.calibration!!
    private val floor = PhotosLikeImplementation.SEMANTIC_RELEVANCE_FLOOR

    private fun admittedBefore(cosine: Float): Boolean = formerCalibration.probability(cosine) >= formerFloor

    private fun admittedNow(cosine: Float): Boolean = calibration.probability(cosine) >= floor

    /** Every millionth of the whole range a cosine can take: 2,000,001 values. */
    @Test
    fun theTwoPairsAdmitTheSameCosinesAcrossTheWholeRange() {
        var admitted = 0
        for (step in -1_000_000..1_000_000) {
            val cosine = step / 1_000_000f
            val now = admittedNow(cosine)
            assertEquals(admittedBefore(cosine), now, "cosine $cosine (bits ${cosine.toBits()})")
            if (now) admitted++
        }
        // ⚠ Both predicates being constant would pass the loop above.
        assertEquals(908_505, admitted, "cosines of the sweep at or above the cut")
    }

    /**
     * ⚠⚠ The sweep above steps over ~130 `Float`s at a time near the cut, and the cut is where the
     * two pairs could differ. This is **every** `Float` for 65,536 either side of it.
     *
     * ⚠⚠ **One `Float` is asserted for the former pair only in single precision**: the cut itself.
     * In `Float` arithmetic the former pair scored that cosine at exactly its floor, to the bit, so
     * whether it was admitted hung on the last bit of `exp`. Kotlin/JS computes a `Float` as a
     * double and puts it just under (0.0999999994). No backend on JS states a calibration, so that
     * is a statement about this test and not about a search anyone ran.
     */
    @Test
    fun theTwoPairsAdmitTheSameCosinesAtEveryFloatAroundTheCut() {
        for (bits in CARRIED_OVER_COSINE_BITS - NEIGHBOURHOOD..CARRIED_OVER_COSINE_BITS + NEIGHBOURHOOD) {
            val cosine = Float.fromBits(bits)
            assertEquals(bits >= CARRIED_OVER_COSINE_BITS, admittedNow(cosine), "now: cosine $cosine (bits $bits)")
            if (bits != CARRIED_OVER_COSINE_BITS || floatIsSinglePrecision) {
                assertEquals(admittedNow(cosine), admittedBefore(cosine), "before: cosine $cosine (bits $bits)")
            }
        }
    }

    /** ⚠ The exemption above must not swallow the platforms that ship: there, the cut is compared. */
    @Test
    fun onAPlatformWithRealFloatsTheCutItselfIsCompared() {
        if (!floatIsSinglePrecision) return
        assertTrue(admittedBefore(Float.fromBits(CARRIED_OVER_COSINE_BITS)))
        assertEquals(formerFloor, formerCalibration.probability(Float.fromBits(CARRIED_OVER_COSINE_BITS)))
    }

    /** `false` on Kotlin/JS, where `0.10f` is the double 0.1 and not the nearest `Float` to it. */
    private val floatIsSinglePrecision: Boolean get() = formerFloor.toDouble() != 0.1

    /**
     * ⚠⚠ Why the floor is this `Float` and not the one the arithmetic first suggests. `exp` is the
     * one step here that platforms may round differently, by an ulp. The floor therefore sits
     * mid-way between the model's score for the first admitted cosine and its score for the
     * `Float` below: an ulp of difference in `exp` moves either score by about one ulp, not eight.
     *
     * ⚠ The former pair had no such margin: its score at the cut was the floor itself, to the bit.
     */
    @Test
    fun theFloorSitsWellInsideTheGapBetweenTheTwoScoresItSeparates() {
        val below = calibration.probability(Float.fromBits(CARRIED_OVER_COSINE_BITS - 1))
        val at = calibration.probability(Float.fromBits(CARRIED_OVER_COSINE_BITS))

        assertTrue(floor.toBits() - below.toBits() >= MARGIN_ULPS, "floor $floor, the score below the cut $below")
        assertTrue(at.toBits() - floor.toBits() >= MARGIN_ULPS, "floor $floor, the score at the cut $at")
    }

    companion object {
        /** `0.09149529f`: the smallest cosine either pair admits. */
        const val CARRIED_OVER_COSINE_BITS: Int = 0x3DBB61E2

        private const val NEIGHBOURHOOD = 65_536
        private const val MARGIN_ULPS = 4
    }
}
