package app.tenshu.hub

import io.nayuki.qrcodegen.QrCode

/**
 * Renders a QR code as terminal text using half-height block characters (two module
 * rows per text line), with the standard quiet zone.
 *
 * Polarity: by default **light modules are drawn as bright blocks** and dark modules as
 * terminal background — on the common dark terminal that yields correct optical polarity
 * (dark modules dark, light modules bright). On a light-themed terminal pass
 * [invert] = true (surfaced as the CLI's `--invert-qr`).
 */
object AsciiQr {

    fun render(text: String, invert: Boolean = false): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val size = qr.size

        // Bright-on-screen for a module: light modules by default, dark when inverted.
        fun bright(x: Int, y: Int): Boolean {
            val inBounds = x in 0 until size && y in 0 until size
            val dark = inBounds && qr.getModule(x, y)
            // Outside the symbol (quiet zone) counts as a light module.
            return if (invert) dark else !dark
        }

        val sb = StringBuilder()
        var y = -QUIET_ZONE
        while (y < size + QUIET_ZONE) {
            for (x in -QUIET_ZONE until size + QUIET_ZONE) {
                val top = bright(x, y)
                val bottom = bright(x, y + 1)
                sb.append(
                    when {
                        top && bottom -> '█' // full block
                        top -> '▀' // upper half block
                        bottom -> '▄' // lower half block
                        else -> ' '
                    },
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString()
    }

    private const val QUIET_ZONE = 2
}
