package app.tenshu.macos

import java.util.concurrent.TimeUnit

/**
 * macOS fallback for image formats the JVM's own decoders can't read.
 *
 * Two decoders on desktop have the same blind spot, from different causes:
 *
 * - **Compose Desktop** renders through Skia, and JetBrains' Skiko builds ship
 *   **without** the HEIF/HEVC codec (HEVC is patent-encumbered; Skia's
 *   `SkHeifDecoder` is effectively Android-only) — so the *viewer* can't open an
 *   iPhone photo (`drive-shared-ui`'s `decodeToImageBitmap`).
 * - **`javax.imageio.ImageIO.read`** has no HEIF plugin either — so the
 *   proxy-side *thumbnail generator* can't render one at upload
 *   (`JvmGetPreviewImplementation`).
 *
 * Nothing about the file is wrong in either case: macOS itself reads HEIC fine
 * through ImageIO (that is what Preview.app uses). Only the JVM-side decoders
 * lack the codec. Serving both consumers is why this lives in a Compose-free
 * module — a service module must not depend on `:ui-core`.
 *
 * This object reaches macOS's own codecs by piping the bytes through the bundled
 * `image-decode-helper` (ImageIO), which hands back PNG or JPEG — formats Skia
 * has always understood. Bytes go over stdin/stdout and never touch the disk:
 * they are decrypted user plaintext, so keeping them in memory leaves the threat
 * model's A5 (transient plaintext) surface unchanged. A `sips`-style
 * file-in/file-out conversion would not.
 *
 * Availability is a *runtime* property, not a compile-time one — the helper is
 * built by swiftc on a Mac build host and is absent on Windows/Linux, where
 * [isAvailable] is `false` and callers are expected to route the file to a
 * "no preview, open it in another app" surface instead.
 */
object MacImageDecoder {

    private val HELPER = HelperBinary("image-decode-helper")

    /** Generous ceiling; a 50 MP HEIC decodes in well under a second in practice. */
    private const val TIMEOUT_SECONDS = 20L

    private val helper: java.io.File? by lazy {
        val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        if (isMac) HELPER.resolve() else null
    }

    /** Whether this machine can decode via ImageIO — macOS with the helper present. */
    fun isAvailable(): Boolean = helper != null

    /**
     * Decodes [data] with ImageIO and returns it re-encoded as PNG (sources with
     * alpha) or JPEG (sources without), capped at 4096 px on the longer edge and
     * with any EXIF orientation already applied. Returns `null` if the helper is
     * unavailable, the input isn't a decodable image, or the helper misbehaves —
     * every failure is a `null`, never a throw, so a bad file can't take down the
     * caller's decode path.
     *
     * Blocking (spawns a process); call it off the UI thread.
     */
    fun decode(data: ByteArray): ByteArray? {
        val bin = helper ?: return null
        if (data.isEmpty()) return null
        return runCatching {
            val process = ProcessBuilder(bin.absolutePath)
                .redirectErrorStream(false)
                .start()
            try {
                // The helper reads stdin to EOF *before* writing anything, so a
                // plain write-then-read can't deadlock on a full pipe buffer.
                process.outputStream.use { it.write(data) }
                val out = process.inputStream.use { it.readBytes() }
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
                if (process.exitValue() != 0 || out.isEmpty()) null else out
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrNull()
    }
}
