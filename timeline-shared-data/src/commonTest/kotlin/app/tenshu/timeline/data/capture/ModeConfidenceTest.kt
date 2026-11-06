package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **§32.4-3 — the open-ended top bucket has one edge, not two.**
 *
 * `confidenceOf`'s margin term asks how far the median sits from the nearest boundary of its bucket.
 * Every bucket but the last has two; `drive` is open above 8 m/s, and the code measured it against a
 * *synthesised* far edge at `MODE_BOUNDARIES.last() * 2` = 16 m/s. Past that the margin fell again and
 * coerced to zero, so **the faster the travel, the less confident the verdict** — inverted exactly
 * where the bucket is least ambiguous.
 *
 * ⚠⚠ It failed *safe* in both consumers, which is why it lived so long: `OngoingMove` simply never
 * named a motorway drive, and §31's `TransitAdjacency` simply never tightened. Nothing looked wrong;
 * things merely never happened. The monotonicity case below is the one that would have caught it
 * without anybody having to guess which consumer to look at.
 */
class ModeConfidenceTest {

    private val t0 = 1_800_000_000_000L
    private val lng = 134_000_000L

    /** [legs] + 1 fixes stepping north every 10 s at [speedMps] — a steady, unambiguous median. */
    private fun steady(speedMps: Double, legs: Int = 30, accuracyM: Int = 5): List<CapturedFix> =
        (0..legs).map {
            CapturedFix(
                latE7 = 525_000_000L + (it * speedMps * 10.0 / 0.011132).toLong(),
                lngE7 = lng,
                timestampMs = t0 + it * 10_000L,
                accuracyM = accuracyM,
            )
        }

    private fun confidenceAt(speedMps: Double): Int = classifyMode(steady(speedMps)).confidence

    @Test
    fun theReportedRailLegIsNoLongerZeroConfidence() {
        // The real 22.3 km leg: median 17.6 m/s, drive, **confidence 0** in the store.
        val classified = classifyMode(steady(17.6))
        assertEquals(MovementMode.DRIVE, classified.mode, "the verdict itself was never in doubt")
        assertTrue(classified.confidence > 0, "was ${classified.confidence}")
    }

    @Test
    fun confidenceNeverFallsAsTheTopBucketGetsLessAmbiguous() {
        // ⭐ The invariant, stated once instead of pinning constants: inside the open-ended bucket,
        // getting further from the only edge cannot make the verdict *less* certain. This is the
        // shape of the bug — a peak at 12 m/s and a collapse to 0 past 16 — not a specific number.
        val samples = listOf(9.0, 12.0, 16.0, 17.6, 25.0, 40.0).map { it to confidenceAt(it) }
        samples.zipWithNext { (slowSpeed, slow), (fastSpeed, fast) ->
            assertTrue(
                fast >= slow,
                "confidence fell from $slow at $slowSpeed m/s to $fast at $fastSpeed m/s: $samples",
            )
        }
    }

    @Test
    fun deepInTheTopBucketIsFullyConfident() {
        // Saturates rather than merely not-falling: 30 legs, fine fixes, nothing near a boundary.
        assertTrue(confidenceAt(40.0) >= 90, "was ${confidenceAt(40.0)}")
    }

    @Test
    fun aMedianOnTheDriveBoundaryIsStillUnconfident() {
        // ⚠⚠ The twin. The margin term must keep *doing its job* at the one edge the bucket has —
        // a fix for "always 0" that returned "always 100" would pass every case above.
        // ⚠ Near-zero, not exactly zero: the fixture builds fixes through an E7 integer conversion, so
        // a nominal 8.0 m/s median lands a hair off the boundary. Pinning `0` would be pinning the
        // rounding, not the rule.
        assertTrue(confidenceAt(8.0) < 5, "8 m/s sits on the cycle/drive boundary, was ${confidenceAt(8.0)}")
    }

    @Test
    fun theBoundedBucketsAreUnchanged() {
        // Only the open-ended bucket had a synthetic edge, so every closed bucket must be untouched:
        // confident mid-bucket, unconfident at both rims.
        assertTrue(confidenceAt(6.0) >= 90, "mid-cycle should be confident, was ${confidenceAt(6.0)}")
        assertTrue(confidenceAt(4.0) < 5, "the run/cycle boundary, was ${confidenceAt(4.0)}")
        assertTrue(confidenceAt(2.5) < 5, "the walk/run boundary, was ${confidenceAt(2.5)}")
        assertTrue(confidenceAt(3.25) >= 90, "mid-run should be confident, was ${confidenceAt(3.25)}")
    }

    @Test
    fun aNearStationaryMedianIsStillUnconfident() {
        // The bottom bucket's lower edge is 0, and that is deliberate: a median of a few cm/s is not
        // a confident walk, it is noise. Unchanged by this fix, pinned so it stays that way.
        assertTrue(confidenceAt(0.05) < 10, "was ${confidenceAt(0.05)}")
    }

    @Test
    fun sampleAndAccuracyStillDiscountAConfidentBucket() {
        // Margin is one of three factors; a fix that is deep in-bucket but poorly sampled or coarsely
        // measured must still be discounted, or "confidence" would just mean "fast".
        val fewLegs = classifyMode(steady(40.0, legs = 4)).confidence
        val coarse = classifyMode(steady(40.0, accuracyM = 140)).confidence
        assertTrue(fewLegs < 40, "four legs is a guess, was $fewLegs")
        assertTrue(coarse < 40, "±140 m fixes cannot support a confident speed, was $coarse")
    }
}
