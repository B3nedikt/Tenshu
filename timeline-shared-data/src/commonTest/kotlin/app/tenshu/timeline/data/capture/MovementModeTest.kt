package app.tenshu.timeline.data.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure coverage of the de-Googled mode heuristic (M1, §5): median per-leg speed bucketed into
 * walk / run / cycle / drive, with degenerate input → unknown. ~89.83 E7 lat ≈ 1 metre.
 *
 * M6 (§12.1) added the *evidence* half — the classifier now returns a [ModeClassification] carrying
 * confidence, median speed and version, so a later rule can re-label old traces. The confidence
 * cases below are the ones that would silently regress if someone reduced it back to a bare bucket.
 */
class MovementModeTest {

    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    private val lng = 134_000_000L

    /** 5 fixes stepping [dLatE7] north each minute → 4 legs at a steady speed. */
    private fun leg(dLatE7: Long, accuracyM: Int = 0) =
        (0 until 5).map { CapturedFix(525_000_000L + it * dLatE7, lng, t0 + it * min, accuracyM) }

    @Test fun walkSpeed() = assertEquals(MovementMode.WALK, classifyMode(leg(8_000)).mode)    // ~1.5 m/s
    @Test fun runSpeed() = assertEquals(MovementMode.RUN, classifyMode(leg(18_000)).mode)      // ~3.3 m/s
    @Test fun cycleSpeed() = assertEquals(MovementMode.CYCLE, classifyMode(leg(32_000)).mode)  // ~5.9 m/s
    @Test fun driveSpeed() = assertEquals(MovementMode.DRIVE, classifyMode(leg(90_000)).mode)  // ~16.7 m/s

    @Test fun singleFixIsUnknown() =
        assertEquals(MovementMode.UNKNOWN, classifyMode(listOf(CapturedFix(525_000_000L, lng, t0))).mode)

    @Test fun zeroElapsedTimeIsUnknown() = assertEquals(
        MovementMode.UNKNOWN,
        classifyMode(listOf(CapturedFix(525_000_000L, lng, t0), CapturedFix(525_100_000L, lng, t0))).mode,
    )

    @Test fun medianResistsASingleGpsSpike() {
        // Four walking legs plus one teleport spike — the median stays walk.
        val walk = (0 until 5).map { CapturedFix(525_000_000L + it * 8_000, lng, t0 + it * min) }
        val spiked = walk + CapturedFix(525_000_000L + 9_000_000, lng, t0 + 5 * min)
        assertEquals(MovementMode.WALK, classifyMode(spiked).mode)
    }

    // ── M6 §12.1: the evidence behind the verdict ────────────────────────────────

    @Test fun stampsTheClassifierVersion() =
        assertEquals(MOVEMENT_CLASSIFIER_VERSION, classifyMode(leg(8_000)).version)

    @Test fun reportsTheMedianSpeedItBucketed() {
        // ~1.49 m/s: 8_000 E7 ≈ 89.1 m per 60 s.
        val median = classifyMode(leg(8_000)).medianSpeedMmPerS
        assertTrue(median in 1_400L..1_600L, "median was $median mm/s")
    }

    @Test fun degenerateInputHasZeroConfidence() =
        assertEquals(0, classifyMode(listOf(CapturedFix(525_000_000L, lng, t0))).confidence)

    @Test fun aMedianNearABucketBoundaryIsLessConfidentThanOneMidBucket() {
        // 13_400 E7/min ≈ 2.49 m/s — a hair under the 2.5 walk|run boundary.
        val boundary = classifyMode(leg(13_400))
        // 4_000 E7/min ≈ 0.74 m/s — squarely inside walk.
        val midBucket = classifyMode(leg(4_000))
        assertEquals(MovementMode.WALK, boundary.mode)
        assertEquals(MovementMode.WALK, midBucket.mode)
        assertTrue(
            boundary.confidence < midBucket.confidence,
            "boundary ${boundary.confidence} should be < mid-bucket ${midBucket.confidence}",
        )
    }

    @Test fun coarseFixesDiscountConfidence() {
        val precise = classifyMode(leg(4_000, accuracyM = 5))
        val coarse = classifyMode(leg(4_000, accuracyM = 120))
        assertEquals(precise.mode, coarse.mode)
        assertTrue(
            coarse.confidence < precise.confidence,
            "coarse ${coarse.confidence} should be < precise ${precise.confidence}",
        )
    }

    @Test fun moreLegsRaiseConfidence() {
        val few = classifyMode(leg(4_000)) // 4 legs
        val many = classifyMode((0 until 25).map { CapturedFix(525_000_000L + it * 4_000, lng, t0 + it * min) })
        assertEquals(few.mode, many.mode)
        assertTrue(many.confidence > few.confidence, "many ${many.confidence} vs few ${few.confidence}")
    }
}
