package org.tenshu.file.proxy

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every orientation against the EXIF definition of where the stored 0th row and 0th column sit
 * visually. The shown picture is always
 *
 * ```
 * A B C
 * D E F
 * ```
 *
 * and each case is the raster a camera writes for it, derived from the spec's row/column wording
 * rather than from [ExifUpright]'s own table — a test built from the table would agree with any
 * mistake in it. The [ExifUpright.Turn] is applied by [applyTurn], a plain reading of "mirror, then
 * turn clockwise"; each platform's native transform is pinned against real images elsewhere
 * (`JvmUprightRenderTest`; Android on a device).
 */
class ExifUprightTest {

    private val upright = intArrayOf(A, B, C, D, E, F)

    private fun assertUpright(orientation: Int, stored: IntArray, storedWidth: Int, storedHeight: Int) {
        val shown = applyTurn(stored, storedWidth, storedHeight, ExifUpright.turnFor(orientation))
        assertContentEquals(upright.toList(), shown.toList(), "orientation $orientation")
    }

    @Test
    fun `1 asks for nothing and so does anything it does not know`() {
        for (o in listOf(1, null, 0, 9)) {
            assertNull(ExifUpright.turnFor(o), "$o")
            assertTrue(ExifUpright.isIdentity(o), "$o")
        }
    }

    // Row 0 = top, column 0 = right.
    @Test
    fun `2 mirrors`() = assertUpright(2, intArrayOf(C, B, A, F, E, D), 3, 2)

    // Row 0 = bottom, column 0 = right — the upside-down iPhone selfie.
    @Test
    fun `3 turns half way`() = assertUpright(3, intArrayOf(F, E, D, C, B, A), 3, 2)

    // Row 0 = bottom, column 0 = left.
    @Test
    fun `4 flips top to bottom`() = assertUpright(4, intArrayOf(D, E, F, A, B, C), 3, 2)

    // Row 0 = left, column 0 = top.
    @Test
    fun `5 transposes`() = assertUpright(5, intArrayOf(A, D, B, E, C, F), 2, 3)

    // Row 0 = right, column 0 = top — a portrait phone photo.
    @Test
    fun `6 turns a quarter clockwise`() = assertUpright(6, intArrayOf(C, F, B, E, A, D), 2, 3)

    // Row 0 = right, column 0 = bottom.
    @Test
    fun `7 transverses`() = assertUpright(7, intArrayOf(F, C, E, B, D, A), 2, 3)

    // Row 0 = left, column 0 = bottom.
    @Test
    fun `8 turns a quarter counter-clockwise`() = assertUpright(8, intArrayOf(D, A, E, B, F, C), 2, 3)

    @Test
    fun `only the quarter turns swap the axes`() {
        for (o in 1..4) assertFalse(ExifUpright.swapsAxes(o), "$o")
        for (o in 5..8) assertTrue(ExifUpright.swapsAxes(o), "$o")
        assertFalse(ExifUpright.swapsAxes(null))
    }

    private companion object {
        const val A = 1
        const val B = 2
        const val C = 3
        const val D = 4
        const val E = 5
        const val F = 6
    }
}

/**
 * [pixels] (row-major, [width] × [height]) with [turn] applied: mirrored left-to-right first, then
 * turned clockwise. The answer is `height × width` for a quarter turn. A reference, for tests only.
 */
internal fun applyTurn(pixels: IntArray, width: Int, height: Int, turn: ExifUpright.Turn?): IntArray {
    if (turn == null) return pixels
    val mirrored = if (!turn.mirror) pixels else IntArray(pixels.size) { i ->
        val x = i % width
        val y = i / width
        pixels[y * width + (width - 1 - x)]
    }
    var current = mirrored
    var w = width
    var h = height
    repeat(turn.clockwiseDegrees / 90) {
        // One quarter clockwise: source (x, y) lands at (h - 1 - y, x) in an h-wide raster.
        val next = IntArray(current.size)
        for (y in 0 until h) for (x in 0 until w) next[x * h + (h - 1 - y)] = current[y * w + x]
        current = next
        val t = w; w = h; h = t
    }
    return current
}
