package app.tenshu.photos

import app.tenshu.photos.model.formatDuration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tile badge's clock reading (`photos-video-support.md` M1).
 *
 * ⚠ The boundaries are the point, not the happy path: 59 s → 1 min and 59 min → 1 h are where an
 * off-by-one turns `1:00` into `0:60`, and the hour boundary is where the format itself changes
 * shape.
 */
class FormatDurationTest {

    @Test
    fun `seconds only - zero-padded`() {
        assertEquals("0:06", formatDuration(6_000L))
        assertEquals("0:59", formatDuration(59_000L))
    }

    @Test
    fun `the minute boundary does not render as sixty seconds`() {
        assertEquals("1:00", formatDuration(60_000L))
        assertEquals("1:23", formatDuration(83_000L))
    }

    @Test
    fun `minutes stay unpadded below an hour`() {
        assertEquals("12:05", formatDuration(725_000L))
        assertEquals("59:59", formatDuration(3_599_000L))
    }

    @Test
    fun `the hour boundary changes the shape and pads minutes`() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:02:03", formatDuration(3_723_000L))
    }

    /**
     * ⚠ Truncates rather than rounds. A 6.9 s clip reads `0:06`, which is what a player's own
     * position readout does — rounding up would show a duration the video never reaches.
     */
    @Test
    fun `sub-second remainders truncate`() {
        assertEquals("0:06", formatDuration(6_999L))
        assertEquals("0:00", formatDuration(999L))
    }

    /**
     * ⚠⚠ The value is a stored `Long` written by another device's metadata pass, so a nonsensical
     * one is a shape this can actually be handed. It must clamp, not render `-1:-3`.
     */
    @Test
    fun `zero and negative clamp to zero rather than rendering a minus sign`() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("0:00", formatDuration(-1L))
        assertEquals("0:00", formatDuration(-3_723_000L))
    }
}
