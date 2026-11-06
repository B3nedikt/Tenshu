package org.tenshu.file.proxy.motion

import org.tenshu.core.file.GetStoredThumbnail
import kotlin.math.roundToInt

/**
 * The platform-independent half of minting the `MOTION` class (`photos-video-support.md` §4, §12):
 * which window of the source becomes the clip, how many bits it may spend, what size it comes out
 * at, and which decoded frames survive decimation. Every number here is one §12.5 locked, and every
 * rule is one the Android encoder (`AndroidMotionThumbnailRenderer`) and any later platform mint
 * (M6 iOS, M7 macOS) must share — which is why none of it lives in `androidMain`.
 *
 * ⚠ Pure by design so that it is testable on the JVM: the host test lane cannot run `MediaCodec`
 * (no Robolectric — `AndroidFileUploadOperationTest` is `@Ignore`d for exactly that reason), so
 * everything that *can* be pinned by a test is kept out of the platform code.
 */
internal object MotionClipPlan {

    /** Longest edge of the clip, in px — `GetStoredThumbnail.MOTION_PX` (§12.5: byte-free vs 192, visibly sharper). */
    const val LONG_EDGE_PX: Int = GetStoredThumbnail.MOTION_PX

    /** The window length (§12.5: "3 s reads as a clip, 2 s as a stutter"). */
    const val NOMINAL_DURATION_US: Long = 3_000_000L

    /** Frames per second after decimation (§12.1: byte-neutral, so the reading-an-action floor). */
    const val FRAME_RATE: Int = 12

    /** `1 s / FRAME_RATE`, the decimation pitch. */
    const val FRAME_INTERVAL_US: Long = 1_000_000L / FRAME_RATE

    /**
     * Where the poster frame is taken from, and therefore where the clip starts (§4.2 / D4): ~1 s in,
     * or the midpoint of a shorter clip, so the opening frame is neither black nor lens-capped.
     */
    const val POSTER_OFFSET_US: Long = 1_000_000L

    /**
     * ⭐ How much more than `bitrate × duration` a muxed clip actually costs, so the bitrate
     * derivation lands under the cap on the **first** encode (§14.9, measured 2026-09-09 on a
     * Snapdragon 8 Gen 3 and on the emulator's software encoder). Two parts, and the second is the
     * one that matters:
     *
     * - the MP4 container: **3.6–3.8 %** at 3 s (six real clips; 6.5 % at 1.5 s, where the moov is a
     *   bigger fraction). §12.1's 7 % came from a 2 s AVFoundation clip and over-states it here.
     * - the encoder's spread **around** its bitrate target: `MediaCodec` VBR delivered 0.95×–1.13× of
     *   what it was asked for across the same clips. A bitrate target is a target, not a ceiling.
     *
     * ⚠⚠ This was `1.07`, which is *below* the observed spread: it left ~3.3 % of slack over a 3.7 %
     * container against a ±10 % encoder, so **2 of 5 real phone clips overshot the cap and burned
     * D13's single retry on the ordinary path** — leaving nothing for a device that overshoots for a
     * second reason. 1.20 covers every clip measured (worst was 1.146) with the retry back to being
     * the exception it was designed as. Cost: ~218 kbps instead of 245, which §12.5's +3.8 dB per
     * doubling makes **−0.6 dB** — and *higher* than the 215 kbps the retry was actually delivering.
     */
    const val BYTE_HEADROOM: Double = 1.20

    /**
     * ⚠ The shortest clip worth storing — anything shorter reads as a flicker in the rotation's
     * "play it through once" turn, and the static poster is the better tile. §13.4: never store an
     * empty or single-frame blob.
     *
     * ⚠⚠ Applied to the window that was actually **decoded** ([storesAsLoop]), never to the nominal
     * one. Applying it early is what made §12.6's real 1.0 s clip mint nothing on the device: its
     * *video track* runs 970 744 us (the container says 1 002 700 — the audio track is longer), so
     * the nominal second half is 485 372 us and missed this floor by 14.6 ms, and the sync-frame
     * re-derivation that §14.6 relies on to widen it to the whole clip never ran. See §14.9.
     */
    const val MIN_WINDOW_US: Long = 500_000L

    /** ⚠ The second half of §13.4's rule: fewer decoded frames than this is not a loop. */
    const val MIN_FRAMES: Int = 2

    /**
     * Headroom on the one proportional retry (see [retryBitrateBps]). §12.1's model is linear in
     * bitrate to within a few percent; 10 % covers that spread so a second overshoot is a real
     * failure rather than rounding.
     */
    const val RETRY_HEADROOM: Double = 0.9

    /**
     * The poster frame's offset for a clip of [durationUs] — the rule `extractVideoPoster` uses on
     * both generating platforms, lifted here so the clip *cannot* start anywhere else (D4). ⚠ Change
     * this and the poster moves with it, which is the point.
     */
    fun posterOffsetUs(durationUs: Long): Long =
        if (durationUs > 0L) minOf(POSTER_OFFSET_US, durationUs / 2) else 0L

    /**
     * Where to seek for the clip's first frame (§4.2 / D4) — the poster's offset, or, when the
     * container declares no duration, [POSTER_OFFSET_US] flat.
     *
     * ⚠⚠ A **seek target, not a window**. Nothing is refused here: the real window is
     * [windowFrom] the sync frame the extractor lands on, and the floor is [storesAsLoop] on what
     * decoded. The gate used to live on the nominal window and refused §12.6's 1.0 s clip by
     * 14.6 ms — see [MIN_WINDOW_US] and §14.9.
     */
    fun seekTargetUs(durationUs: Long): Long =
        if (durationUs > 0L) posterOffsetUs(durationUs) else POSTER_OFFSET_US

    /**
     * The window that actually starts at [startUs] — the sync frame the extractor landed on, which
     * may sit a few hundred ms either side of [seekTargetUs]. Re-deriving the end from the real
     * start keeps the clip [NOMINAL_DURATION_US] long and the first frame *exactly* the poster's
     * keyframe rather than nearly.
     *
     * ⚠⚠ [durationUs] `<= 0` means the container declared **no** duration, which is not the same as
     * "empty": a fragmented MP4 (`mvex` + `moof`, what Telegram, WhatsApp and every DASH-derived
     * clip produce — §12.5's own seventh source is one) carries `0` in `mvhd` *and* in every track's
     * `mdhd`, and decodes perfectly. The end is then left open at the nominal length and the
     * decoder's EOS bounds it; [storesAsLoop] judges the result. Refusing on the declared duration
     * cost that whole family its motion class, silently (§14.9).
     */
    fun windowFrom(startUs: Long, durationUs: Long): MotionWindow? {
        if (startUs < 0L) return null
        if (durationUs > 0L && startUs >= durationUs) return null
        val end = if (durationUs > 0L) {
            minOf(startUs + NOMINAL_DURATION_US, durationUs)
        } else {
            startUs + NOMINAL_DURATION_US
        }
        if (end - startUs < MIN_WINDOW_US) return null
        return MotionWindow(startUs = startUs, endUs = end)
    }

    /**
     * Whether what actually decoded is a loop worth storing: [MIN_FRAMES] frames spanning
     * [MIN_WINDOW_US] (§13.4). [spanUs] is the last kept frame's presentation time inside the clip,
     * i.e. `FrameDecimator.relativeUs` of the final frame.
     *
     * ⚠ This — not the nominal window — is the floor. It is the only check that can see a source
     * whose declared duration was wrong, absent, or measured on a different track.
     */
    fun storesAsLoop(frameCount: Int, spanUs: Long): Boolean =
        frameCount >= MIN_FRAMES && spanUs >= MIN_WINDOW_US

    /**
     * The encoder's target for a clip that must land under [maxBytes] (§12.2, §12.5):
     * `cap × 8 / (duration × overhead)` ≈ 245 kbps for the locked 96 KB / 3 s.
     *
     * ⚠ Derived from the **nominal** duration, not the actual window: a shorter clip therefore comes
     * out proportionally *smaller*, not sharper. The alternative — spend the whole cap on 1.5 s —
     * would buy bitrate §12.3 measured as invisible at tile size, for bytes every device then
     * fetches. Constant quality, variable size, cap never exceeded.
     */
    fun targetBitrateBps(maxBytes: Int): Int {
        val seconds = NOMINAL_DURATION_US / 1_000_000.0
        return (maxBytes * 8.0 / (seconds * BYTE_HEADROOM)).toInt()
    }

    /**
     * The bitrate for the one retry after an encode of [actualBytes] overshot [maxBytes] at
     * [bitrateBps]. Proportional, because bytes are linear in bitrate (§12.1), with [RETRY_HEADROOM].
     *
     * ⚠ This exists for a hardware encoder that ignores the target on its opening I-frame — the one
     * overshoot a bitrate-targeted encoder *can* produce, and one that would otherwise make the
     * class silently inert on that device. It is not §4.5's shorten-the-window ladder, which §12.2
     * retired: the window is the product, the bitrate is the knob.
     */
    fun retryBitrateBps(bitrateBps: Int, actualBytes: Int, maxBytes: Int): Int {
        require(actualBytes > 0) { "retry needs the overshooting size" }
        return (bitrateBps.toDouble() * maxBytes / actualBytes * RETRY_HEADROOM).toInt()
    }

    /**
     * The clip's frame size for a source of [sourceWidth] × [sourceHeight] that a player is told to
     * rotate by [rotationDegrees] (clockwise; the container's `rotation-degrees`): the long edge
     * capped at [LONG_EDGE_PX], never upscaled, both dimensions even (H.264 4:2:0 needs it), in
     * *displayed* orientation.
     *
     * ⚠ Rotation is baked into the pixels rather than carried as a container hint: every player
     * the class will meet (Android, AVPlayer, a browser `<video>`) honours the hint *differently*,
     * and a portrait phone clip stored sideways is the kind of thing only a device pass finds. The
     * scaler rotates for free.
     */
    fun outputSize(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): MotionSize? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val transposed = normaliseRotation(rotationDegrees) % 180 != 0
        val width = if (transposed) sourceHeight else sourceWidth
        val height = if (transposed) sourceWidth else sourceHeight
        val longEdge = maxOf(width, height)
        val scale = if (longEdge > LONG_EDGE_PX) LONG_EDGE_PX.toDouble() / longEdge else 1.0
        return MotionSize(
            width = even((width * scale).roundToInt()),
            height = even((height * scale).roundToInt()),
        )
    }

    /** `rotation-degrees` as one of 0/90/180/270 (a container may say −90 or 450). */
    fun normaliseRotation(rotationDegrees: Int): Int = ((rotationDegrees % 360) + 360) % 360

    private fun even(value: Int): Int = maxOf(2, value and 1.inv())
}

/** A half-open-ish slice of the source, in presentation time: `[startUs, endUs]`. */
internal data class MotionWindow(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
}

/** The clip's frame size, in displayed orientation, both even. */
internal data class MotionSize(val width: Int, val height: Int)

/**
 * Picks which decoded frames of a [window] become clip frames: the first frame at or after each
 * [MotionClipPlan.FRAME_INTERVAL_US] tick from the window's start, so a 30 fps source yields ~36
 * frames over 3 s and a 12 fps one yields every frame.
 *
 * ⚠ Kept frames carry their **source** timing, offset to start at zero ([relativeUs]) — not a
 * synthetic `index × interval`. Re-stamping would speed up a 10 fps source by 1.2× and desync a
 * variable-rate one; MP4 is happy with variable frame timing and the turn length stays honest.
 */
internal class FrameDecimator(
    private val window: MotionWindow,
    private val intervalUs: Long = MotionClipPlan.FRAME_INTERVAL_US,
) {
    private var nextTargetUs: Long = window.startUs
    private var firstKeptUs: Long = -1L

    /** How many frames [keeps] has said yes to. */
    var keptCount: Int = 0
        private set

    /** Whether the frame at [ptsUs] belongs in the clip. Frames arrive in presentation order. */
    fun keeps(ptsUs: Long): Boolean {
        if (ptsUs < window.startUs || ptsUs > window.endUs) return false
        if (ptsUs < nextTargetUs) return false
        if (firstKeptUs < 0L) firstKeptUs = ptsUs
        keptCount++
        // Past this frame, never merely to it — a burst of frames at one tick keeps one.
        while (nextTargetUs <= ptsUs) nextTargetUs += intervalUs
        return true
    }

    /** True once a frame past the window has been seen: the decoder can stop. */
    fun isPast(ptsUs: Long): Boolean = ptsUs > window.endUs

    /** A kept frame's presentation time inside the clip, starting at zero. */
    fun relativeUs(ptsUs: Long): Long = ptsUs - firstKeptUs
}
