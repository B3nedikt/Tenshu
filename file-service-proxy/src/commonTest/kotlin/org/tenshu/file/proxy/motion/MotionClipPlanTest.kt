package org.tenshu.file.proxy.motion

import org.tenshu.core.file.GetStoredThumbnail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins `photos-video-support.md` §4.2 / §12.5 / §12.6 / §13.4 on the JVM — everything about the
 * `MOTION` mint that is not a codec call. The host lane cannot run `MediaCodec`, so this is the
 * whole of what a green gate says about M4; the encoder itself is the device pass's business.
 */
class MotionClipPlanTest {

    private val second = 1_000_000L

    // ---- the window (§4.2, §12.6) ----

    @Test
    fun thePosterOffsetIsOneSecondOrTheMidpointOfAShorterClip() {
        assertEquals(second, MotionClipPlan.posterOffsetUs(6 * second))
        assertEquals(second, MotionClipPlan.posterOffsetUs(2 * second))
        assertEquals(750_000L, MotionClipPlan.posterOffsetUs(1_500_000L))
        // §12.6's real clip: 1.0 s long, so the poster is its midpoint.
        assertEquals(500_000L, MotionClipPlan.posterOffsetUs(second))
        assertEquals(0L, MotionClipPlan.posterOffsetUs(0L))
        assertEquals(0L, MotionClipPlan.posterOffsetUs(-5L))
    }

    @Test
    fun aLongClipSeeksThePosterOffsetAndTakesThreeSecondsFromTheSyncFrame() {
        assertEquals(second, MotionClipPlan.seekTargetUs(6 * second))
        val window = assertNotNull(MotionClipPlan.windowFrom(second, 6 * second))
        assertEquals(MotionWindow(startUs = second, endUs = 4 * second), window)
        assertEquals(MotionClipPlan.NOMINAL_DURATION_US, window.durationUs)

        // A twenty-minute recording costs the same window as a six-second one (§3).
        assertEquals(window, MotionClipPlan.windowFrom(second, 1_200 * second))
    }

    @Test
    fun aClipShorterThanTheWindowRunsFromThePosterOffsetToItsEnd() {
        // §12.5's 2.7 s source.
        assertEquals(
            MotionWindow(startUs = second, endUs = 2_700_000L),
            MotionClipPlan.windowFrom(MotionClipPlan.seekTargetUs(2_700_000L), 2_700_000L),
        )
        assertEquals(
            MotionWindow(startUs = second, endUs = 3_500_000L),
            MotionClipPlan.windowFrom(MotionClipPlan.seekTargetUs(3_500_000L), 3_500_000L),
        )
    }

    /**
     * ⚠⚠ §12.6 / §14.9 — the regression the 2026-09-09 device pass found. This clip is real, and
     * its **video track** runs 970 744 us while the container says 1 002 700 (its audio track is
     * longer). The floor used to be applied to the nominal window — 970 744 / 2 = 485 372 us, short
     * of [MotionClipPlan.MIN_WINDOW_US] by 14.6 ms — so the clip was refused before the extractor was
     * ever consulted, and it minted nothing on the phone. Its only keyframe is at 0, so the sync-frame
     * re-derivation §14.6 relies on gives it the whole clip with the poster as frame one.
     */
    @Test
    fun theOneSecondClipTakesItsWholeSelfFromTheSyncFrameAtZero() {
        val trackDurationUs = 970_744L
        // The nominal second half is under the floor — and that is no longer a refusal.
        assertEquals(485_372L, MotionClipPlan.seekTargetUs(trackDurationUs))

        val window = assertNotNull(
            MotionClipPlan.windowFrom(startUs = 0L, durationUs = trackDurationUs),
            "the sync frame at 0 must widen the window, not be refused before it is read",
        )
        assertEquals(MotionWindow(startUs = 0L, endUs = trackDurationUs), window)
        assertTrue(MotionClipPlan.storesAsLoop(frameCount = 12, spanUs = window.durationUs))
    }

    /**
     * ⚠⚠ §14.9: a fragmented MP4 (`mvex` + `moof` — Telegram, WhatsApp, anything DASH-derived, and
     * §12.5's own seventh source) declares **0** in `mvhd` and in every track's `mdhd`, and decodes
     * perfectly. An undeclared duration leaves the end open at the nominal length; the decoder's EOS
     * and [MotionClipPlan.storesAsLoop] bound it.
     */
    @Test
    fun anUndeclaredDurationLeavesTheWindowOpenRatherThanRefusingTheClip() {
        assertEquals(MotionClipPlan.POSTER_OFFSET_US, MotionClipPlan.seekTargetUs(0L))
        assertEquals(MotionClipPlan.POSTER_OFFSET_US, MotionClipPlan.seekTargetUs(-1L))

        val window = assertNotNull(MotionClipPlan.windowFrom(startUs = second, durationUs = 0L))
        assertEquals(MotionWindow(startUs = second, endUs = 4 * second), window)
    }

    /** ⚠ The floor is on what DECODED, so a source that decodes to a flicker is still refused. */
    @Test
    fun theFloorRefusesAFlickerAndAnUnderLengthClip() {
        assertTrue(MotionClipPlan.storesAsLoop(frameCount = 2, spanUs = MotionClipPlan.MIN_WINDOW_US))
        assertFalse(MotionClipPlan.storesAsLoop(frameCount = 1, spanUs = 3 * second), "one frame is not a loop")
        assertFalse(MotionClipPlan.storesAsLoop(frameCount = 12, spanUs = 499_999L), "under the floor")
        assertFalse(MotionClipPlan.storesAsLoop(frameCount = 0, spanUs = 0L))
    }

    @Test
    fun theWholeClipIsTakenWhenItIsNoLongerThanThePosterOffset() {
        // Unreachable with today's midpoint rule (the offset is at most half the clip), but §4.2
        // specifies the branch and a fixed 1 s offset would reach it — so it is pinned rather than
        // left to whoever changes `posterOffsetUs`.
        val window = MotionClipPlan.windowFrom(startUs = 0L, durationUs = 900_000L)
        assertEquals(MotionWindow(0L, 900_000L), window)
    }

    // ---- the window re-derived from the real sync frame (D4 exactly) ----

    @Test
    fun theWindowStartsOnTheSyncFrameTheExtractorLandedOn() {
        // Nearest keyframe 200 ms before the nominal offset: the clip still runs 3 s, from there.
        assertEquals(
            MotionWindow(startUs = 800_000L, endUs = 3_800_000L),
            MotionClipPlan.windowFrom(startUs = 800_000L, durationUs = 6 * second),
        )
        // The 1.0 s clip's only keyframe is at 0 — the whole clip, first frame the poster.
        assertEquals(
            MotionWindow(startUs = 0L, endUs = second),
            MotionClipPlan.windowFrom(startUs = 0L, durationUs = second),
        )
    }

    @Test
    fun aSyncFrameThatLeavesNoWindowMeansNoClass() {
        // Keyframe-sparse source whose closest sync sits 200 ms before the end.
        assertNull(MotionClipPlan.windowFrom(startUs = 5_800_000L, durationUs = 6 * second))
        assertNull(MotionClipPlan.windowFrom(startUs = 6 * second, durationUs = 6 * second))
        assertNull(MotionClipPlan.windowFrom(startUs = -1L, durationUs = 6 * second))
    }

    // ---- bytes (§12.1, §12.2, §12.5) ----

    @Test
    fun theBitrateIsDerivedFromTheCapAndLandsUnderItByConstruction() {
        val cap = 96 * 1024
        val bitrate = MotionClipPlan.targetBitrateBps(cap)
        // §14.9: ~218 kbps for the locked 96 KB / 3 s once the headroom covers the encoder's spread.
        assertTrue(bitrate in 214_000..222_000, "expected ~218 kbps, got $bitrate")
        // The model bytes ≈ bitrate × duration × headroom; the derivation inverts it.
        val predictedBytes = bitrate * 3.0 * MotionClipPlan.BYTE_HEADROOM / 8.0
        assertTrue(predictedBytes <= cap, "predicted $predictedBytes B must not exceed the cap $cap")
        assertTrue(predictedBytes > cap * 0.99, "the cap is the input — spend it, not a rounded fraction of it")
    }

    /**
     * ⭐ [MotionClipPlan.BYTE_HEADROOM] against what real encoders actually delivered (§14.9, device
     * pass 2026-09-09). Each row is `delivered file bitrate ÷ the bitrate the encoder was asked for`
     * at the clip's real length — the spread a single bitrate target has to survive, which at the
     * old `1.07` it did not: two of these overshot the cap and burned D13's only retry.
     *
     * ⚠ The retry stays the backstop. This pins that it is not the ordinary path.
     */
    @Test
    fun theHeadroomCoversEveryEncoderSpreadMeasuredOnDevice() {
        val cap = 96 * 1024
        val bitrate = MotionClipPlan.targetBitrateBps(cap)
        // name to (delivered ÷ requested, clip seconds)
        val measured = listOf(
            Triple("rot0 1080p hevc", 0.964, 2.999),
            Triple("rot90 1080p hevc", 0.954, 2.999),
            Triple("rot180 1080p hevc", 1.133, 3.036),
            Triple("rot90 1080p hevc, 1.5 s", 1.188, 1.466),
            Triple("720p avc, software encoder", 1.107, 3.067),
        )
        for ((name, ratio, seconds) in measured) {
            val predicted = ratio * bitrate * seconds / 8.0
            assertTrue(
                predicted <= cap,
                "$name would land at ${predicted.toInt()} B on the FIRST encode, over the $cap B cap",
            )
        }
    }

    @Test
    fun theRetryIsProportionalWithHeadroom() {
        val cap = 96 * 1024
        val first = MotionClipPlan.targetBitrateBps(cap)
        val overshoot = 110_000
        val retry = MotionClipPlan.retryBitrateBps(first, actualBytes = overshoot, maxBytes = cap)
        assertTrue(retry < first)
        // Under the linear model the retry lands at RETRY_HEADROOM of the cap.
        val predicted = overshoot.toDouble() * retry / first
        assertEquals(cap * MotionClipPlan.RETRY_HEADROOM, predicted, absoluteTolerance = 2.0)
    }

    // ---- size (§12.5, rotation baked in) ----

    @Test
    fun theLongEdgeIsTheLockedOneAndAliasesTheSharedConstant() {
        assertEquals(256, MotionClipPlan.LONG_EDGE_PX)
        assertEquals(GetStoredThumbnail.MOTION_PX, MotionClipPlan.LONG_EDGE_PX)
    }

    @Test
    fun landscapeAndPortraitScaleToTheLongEdge() {
        assertEquals(MotionSize(256, 144), MotionClipPlan.outputSize(1920, 1080, 0))
        assertEquals(MotionSize(144, 256), MotionClipPlan.outputSize(1080, 1920, 0))
        assertEquals(MotionSize(256, 192), MotionClipPlan.outputSize(640, 480, 0))
        assertEquals(MotionSize(256, 192), MotionClipPlan.outputSize(4000, 3000, 180))
    }

    /** A phone's portrait clip is a landscape track plus `rotation-degrees` 90: the output is upright. */
    @Test
    fun rotationTransposesTheOutput() {
        assertEquals(MotionSize(144, 256), MotionClipPlan.outputSize(1920, 1080, 90))
        assertEquals(MotionSize(144, 256), MotionClipPlan.outputSize(1920, 1080, 270))
        assertEquals(MotionSize(144, 256), MotionClipPlan.outputSize(1920, 1080, -90))
        assertEquals(MotionSize(256, 144), MotionClipPlan.outputSize(1920, 1080, 360))
    }

    @Test
    fun aSmallSourceIsNeverUpscaledAndOddEdgesRoundDownToEven() {
        assertEquals(MotionSize(200, 100), MotionClipPlan.outputSize(200, 100, 0))
        assertEquals(MotionSize(200, 100), MotionClipPlan.outputSize(201, 101, 0))
        assertEquals(MotionSize(2, 2), MotionClipPlan.outputSize(1, 1, 0))
        assertNull(MotionClipPlan.outputSize(0, 100, 0))
        assertNull(MotionClipPlan.outputSize(100, -1, 0))
    }

    @Test
    fun rotationNormalisesToAQuarterTurn() {
        assertEquals(0, MotionClipPlan.normaliseRotation(0))
        assertEquals(270, MotionClipPlan.normaliseRotation(-90))
        assertEquals(90, MotionClipPlan.normaliseRotation(450))
        assertEquals(180, MotionClipPlan.normaliseRotation(-180))
    }

    // ---- decimation (§12.1: 12 fps) ----

    @Test
    fun aThirtyFpsSourceDecimatesToAboutTwelveFrames() {
        val window = MotionWindow(startUs = second, endUs = 4 * second)
        val decimator = FrameDecimator(window)
        val kept = (0..200).map { it * 33_333L }.filter { decimator.keeps(it) }
        assertTrue(kept.size in 35..37, "expected ~36 frames over 3 s at 12 fps, got ${kept.size}")
        assertEquals(kept.size, decimator.keptCount)
        assertTrue(kept.all { it in window.startUs..window.endUs })
        // Decimation, not passthrough: one frame per tick, so two kept frames are never closer than
        // the pitch minus one source frame (the first frame *after* a tick can sit up to a source
        // interval past it, and the next tick's frame up to a source interval short of that).
        val sourceIntervalUs = 33_333L
        val closest = kept.zipWithNext().minOf { (a, b) -> b - a }
        assertTrue(
            closest >= MotionClipPlan.FRAME_INTERVAL_US - sourceIntervalUs,
            "kept frames only $closest us apart — that is passthrough, not 12 fps",
        )
    }

    @Test
    fun aTwelveFpsSourceKeepsEveryFrameInTheWindow() {
        val window = MotionWindow(startUs = 0L, endUs = second)
        val decimator = FrameDecimator(window)
        val frames = (0..12).map { it * MotionClipPlan.FRAME_INTERVAL_US }
        val kept = frames.filter { decimator.keeps(it) }
        assertEquals(frames.filter { it <= window.endUs }, kept)
    }

    @Test
    fun framesOutsideTheWindowAreNeverKept() {
        val window = MotionWindow(startUs = second, endUs = 2 * second)
        val decimator = FrameDecimator(window)
        assertFalse(decimator.keeps(0L))
        assertFalse(decimator.keeps(999_999L))
        assertTrue(decimator.keeps(second))
        assertFalse(decimator.keeps(2 * second + 1))
        assertTrue(decimator.isPast(2 * second + 1))
        assertFalse(decimator.isPast(2 * second))
    }

    @Test
    fun aBurstAtOneTickKeepsOneFrame() {
        val decimator = FrameDecimator(MotionWindow(0L, second))
        assertTrue(decimator.keeps(0L))
        assertFalse(decimator.keeps(0L))
        assertFalse(decimator.keeps(1L))
        assertTrue(decimator.keeps(MotionClipPlan.FRAME_INTERVAL_US))
    }

    /** Kept frames keep their source spacing (a 10 fps source plays at 10 fps, not 1.2× fast). */
    @Test
    fun keptFramesCarrySourceTimingFromZero() {
        val decimator = FrameDecimator(MotionWindow(startUs = second, endUs = 4 * second))
        val tenFps = (0..40).map { it * 100_000L }
        val kept = tenFps.filter { decimator.keeps(it) }
        assertEquals(second, kept.first())
        assertEquals(0L, decimator.relativeUs(kept.first()))
        assertEquals(100_000L, decimator.relativeUs(kept[1]))
        assertEquals(kept.map { it - second }, kept.map { decimator.relativeUs(it) })
    }
}
