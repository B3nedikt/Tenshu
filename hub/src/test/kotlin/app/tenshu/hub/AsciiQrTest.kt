package app.tenshu.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsciiQrTest {

    @Test
    fun rendersARectangularBlockGridWithQuietZone() {
        val rendered = AsciiQr.render("tenshu://offer?k=abc&s=def")
        val lines = rendered.trimEnd('\n').lines()

        assertTrue(lines.isNotEmpty())
        // Rectangular: every line the same width (QR size + 2×2-module quiet zone).
        assertEquals(1, lines.map { it.length }.toSet().size, "all lines must be equal width")
        // Half-block rendering actually draws something.
        assertTrue(rendered.any { it == '█' || it == '▀' || it == '▄' })
        // Default polarity: the quiet zone is bright (light modules as blocks).
        assertTrue(lines.first().all { it == '█' }, "top quiet-zone line must be solid bright")
        // Inverted polarity flips the quiet zone dark.
        val inverted = AsciiQr.render("tenshu://offer?k=abc&s=def", invert = true)
        assertTrue(inverted.lines().first().all { it == ' ' })
    }
}
