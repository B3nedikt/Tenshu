package app.tenshu.macos

import java.util.concurrent.TimeUnit

/**
 * A **map hero as an image**, drawn by macOS itself — AM-5 of
 * `docs/proposals/ios-apple-maps-option.md`.
 *
 * Compose Desktop renders through Skia and cannot host an `MKMapView`, and §6.5's design does not
 * need it to: our heroes are **static frames** (gestures are disabled by design), and a static
 * frame is an image. `MKMapSnapshotter` returns exactly that, so the JVM asks for pixels and draws
 * them itself — the `MacCameraSession` pattern, one plane over, and the reason desktop needs no
 * window-embedding bridge, no JAWT and no `CALayer` attach.
 *
 * ⚠⚠ **Availability is not permission.** [isAvailable] answers "this machine can draw an Apple
 * map"; it says nothing about whether the user asked for one. Every caller must gate on the
 * renderer election first — reaching this object at all means tiles are fetched from Apple, which
 * discloses which place or trip was opened, and when (threat model **A35(f)** / **T-MR-05**). The
 * default renderer is MapLibre and a user who never opens Settings must never arrive here.
 *
 * ⚠ Like [MacImageDecoder], availability is a *runtime* property: the helper is swiftc-built on a
 * Mac build host and simply absent on Windows and Linux, where [isAvailable] is false and the
 * caller keeps the schematic basemap it draws today.
 *
 * ⚠ No entitlement is involved, and that is a finding rather than an omission (§8.1): a snapshot
 * renders from a bundle carrying none, while *claiming* `com.apple.developer.maps` without a
 * provisioning profile is an instant SIGKILL. Do not add the Maps capability to anything that
 * embeds this helper.
 */
object MacMapSnapshotter {

    private val HELPER = HelperBinary("map-snapshot-helper")

    /** A snapshot is a network fetch against Apple's tile servers; this is a ceiling, not a target. */
    private const val TIMEOUT_SECONDS = 25L

    private val helper: java.io.File? by lazy {
        val isMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
        if (isMac) HELPER.resolve() else null
    }

    /** Whether this machine can draw an Apple map — macOS with the helper present. */
    fun isAvailable(): Boolean = helper != null

    /**
     * Renders [route] (or, when it has fewer than two points, the [centerLatitude]/[centerLongitude]
     * point at [zoom]) into a PNG of [width] × [height] points, or null on any failure.
     *
     * [zoom] is `:basemap-core`'s slippy-map zoom in its **512 dp-tile** convention — the helper
     * converts, using the same constant the iOS path uses, so the two engines cannot drift.
     * [colorHex] is `#RRGGBB` (D8). [dark] picks Apple's dark basemap rather than dropping a light
     * map into a dark page.
     *
     * Every failure is a null, never a throw: a hero that cannot be drawn must fall back to the
     * schematic, not take the screen down with it.
     *
     * Blocking (spawns a process, fetches tiles); call it off the UI thread.
     */
    fun snapshot(
        centerLatitude: Double,
        centerLongitude: Double,
        zoom: Double,
        width: Int,
        height: Int,
        dark: Boolean,
        route: List<Pair<Double, Double>>,
        colorHex: String,
    ): ByteArray? {
        val bin = helper ?: return null
        if (width <= 0 || height <= 0) return null
        val request = buildString {
            append("""{"centerLat":$centerLatitude,"centerLon":$centerLongitude,"zoom":$zoom,""")
            append(""""width":$width,"height":$height,"dark":$dark,"route":[""")
            route.forEachIndexed { index, (latitude, longitude) ->
                if (index > 0) append(',')
                append("[$latitude,$longitude]")
            }
            // ⚠ `colorHex` is the only string that crosses, and it is composed here rather than
            // interpolated from user input: it comes from a theme colour via `#RRGGBB`, so there is
            // nothing to escape. If a caller-supplied string ever joins it, this needs a real
            // encoder.
            append("""],"colorHex":"$colorHex"}""")
        }
        return runCatching {
            val process = ProcessBuilder(bin.absolutePath).redirectErrorStream(false).start()
            try {
                // Same contract as the image decoder: the helper reads stdin to EOF before writing
                // anything, so write-then-read cannot deadlock on a full pipe buffer.
                process.outputStream.use { it.write(request.toByteArray()) }
                val png = process.inputStream.use { it.readBytes() }
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return null
                if (process.exitValue() != 0 || png.isEmpty()) null else png
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrNull()
    }
}
