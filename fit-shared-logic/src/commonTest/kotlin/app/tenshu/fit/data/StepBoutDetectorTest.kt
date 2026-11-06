package app.tenshu.fit.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepBoutDetectorTest {

    private val base = 1_700_000_000_000L
    private val minute = 60_000L

    /** One minute-long bucket at [spm] steps per minute, [atMinute] minutes after [base]. */
    private fun bucket(atMinute: Int, spm: Int) = StepBoutDetector.Sample(
        startMs = base + atMinute * minute,
        endMs = base + (atMinute + 1) * minute,
        steps = spm,
    )

    private fun walkBuckets(from: Int, count: Int, spm: Int = 110) =
        (from until from + count).map { bucket(it, spm) }

    @Test
    fun aSustainedWalkBecomesOneBout() {
        val bouts = StepBoutDetector.detect(walkBuckets(0, 12))
        assertEquals(1, bouts.size)
        val b = bouts.single()
        assertEquals("walk", b.mode)
        assertEquals(12 * 60, b.durationSec)
        assertEquals(110, b.cadenceSpm)
    }

    @Test
    fun aFastCadenceReadsAsRunning() {
        val bouts = StepBoutDetector.detect(walkBuckets(0, 10, spm = 170))
        assertEquals("run", bouts.single().mode)
    }

    /** ⚠ Pottering about a kitchen is not a walk, however long it goes on. */
    @Test
    fun lowCadenceNeverOpensABout() {
        assertTrue(StepBoutDetector.detect(walkBuckets(0, 30, spm = 20)).isEmpty())
    }

    @Test
    fun aShortBurstIsNoise() {
        assertTrue(StepBoutDetector.detect(walkBuckets(0, 3)).isEmpty())
    }

    /** ⚠ One missing bucket is bridged; standing still for four minutes ends the walk. */
    @Test
    fun aBriefGapBridgesButALongOneSplits() {
        val bridged = walkBuckets(0, 6) + walkBuckets(8, 6)      // 2-minute gap
        assertEquals(1, StepBoutDetector.detect(bridged).size)

        val split = walkBuckets(0, 6) + walkBuckets(12, 6)       // 6-minute gap
        assertEquals(2, StepBoutDetector.detect(split).size)
    }

    /** A pause below the cadence floor closes the bout even with no time gap. */
    @Test
    fun goingStillClosesTheBout() {
        val samples = walkBuckets(0, 6) + listOf(bucket(6, 5)) + walkBuckets(7, 6)
        val bouts = StepBoutDetector.detect(samples)
        assertEquals(2, bouts.size)
    }

    @Test
    fun outOfOrderInputIsSortedRatherThanDropped() {
        val shuffled = walkBuckets(0, 12).reversed()
        assertEquals(1, StepBoutDetector.detect(shuffled).size)
    }

    @Test
    fun emptyInputYieldsNothing() {
        assertTrue(StepBoutDetector.detect(emptyList()).isEmpty())
    }

    /**
     * ⚠⚠ Confidence measures "is this sustained pedestrian activity", not "walk or run". A brisk
     * 130 spm walk sits right on the run boundary, and a boundary-margin score would rate it near
     * zero — refusing a perfectly good walk over a distinction the promotion decision does not
     * even use, since walks and runs both promote.
     */
    @Test
    fun aBriskWalkIsConfidentEvenThoughItIsNearTheRunBoundary() {
        assertTrue(StepBoutDetector.confidenceFor(130) >= 90, "a brisk walk is unambiguous")
        assertTrue(StepBoutDetector.confidenceFor(110) >= 90)
        // And a shuffle barely above the floor is not.
        assertTrue(StepBoutDetector.confidenceFor(55) < 20)
        assertEquals(0, StepBoutDetector.confidenceFor(50))
    }

    /**
     * ⚠⚠ A bout carries **no distance**, and nothing here invents one. A stride estimate would
     * put a number no sensor produced into a health record — the rule that already keeps a
     * promoted workout's steps, HR and energy empty rather than guessed.
     */
    @Test
    fun aBoutCarriesNoDistanceAtAll() {
        val b = StepBoutDetector.detect(walkBuckets(0, 12)).single()
        // The type has no distance field to read — this asserts the shape, so adding one is a
        // deliberate act with a test to change rather than a quiet default.
        assertEquals(12 * 110, b.steps)
    }
}
