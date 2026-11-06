package app.tenshu.takeout.zip

import java.util.zip.Inflater

/**
 * The JVM/Android half of [Inflate] (TB-D1) — the shape `JvmTileDecompressor` already established
 * for gzip, for the same reason: `commonMain` has no compression, and an injected lambda beats an
 * `expect`/`actual` whose JS side would be awkward.
 *
 * ⚠⚠ **`nowrap = true`.** A ZIP entry holds a *raw* DEFLATE stream with no zlib header or Adler
 * checksum around it. An `Inflater()` built without this flag fails on the very first byte of every
 * entry — the classic mistake, and it looks like a corrupt archive rather than a wrong flag.
 */
val jvmInflate: Inflate = { deflated, uncompressedSize ->
    val inflater = Inflater(true)
    try {
        inflater.setInput(deflated)
        val out = ByteArray(uncompressedSize)
        var written = 0
        while (written < uncompressedSize && !inflater.finished()) {
            val n = inflater.inflate(out, written, uncompressedSize - written)
            // ⚠ `inflate` returning 0 without finishing means it wants more input that will never
            // come (a truncated entry). Breaking beats spinning forever on a malformed file.
            if (n == 0) break
            written += n
        }
        if (written == uncompressedSize) out else out.copyOf(written)
    } finally {
        inflater.end()
    }
}
