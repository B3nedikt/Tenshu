package app.tenshu.timeline.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import app.tenshu.ui.image.decodePreviewBitmap
import kotlin.math.roundToInt

/**
 * A decoded photo and the colour the rest of the screen should tint itself with
 * (`timeline-photos.md` T-P2).
 *
 * The two travel together because they come from the same decode: `personalise()` has always
 * needed a `dominantColor`, and the only place that colour can honestly come from is the pixels —
 * which the hero is decoding anyway. Computing it here rather than proxy-side is not a shortcut:
 * proxy-side the bytes are often *not local* (`File.Thumbnail` is `OnDemand`), while by the time
 * anything is drawn they are, by construction.
 */
data class PhotoImage(
    val bitmap: ImageBitmap,
    /** `#rrggbb`, in the same shape `Photo.dominantColor` and `parseHexColor` already use. */
    val dominantColor: String,
)

/**
 * Decode [bytes] once, off the composition thread, and derive their tint.
 *
 * Keyed on the byte array's identity, so a recomposition neither re-decodes nor re-averages.
 * `null` while decoding, and for bytes that do not decode at all.
 */
@Composable
fun rememberPhotoImage(bytes: ByteArray?): State<PhotoImage?> =
    produceState<PhotoImage?>(initialValue = null, bytes) {
        val bitmap = bytes?.decodePreviewBitmap()
        value = bitmap?.let { PhotoImage(bitmap = it, dominantColor = it.averageColorHex()) }
    }

/**
 * The mean colour of a sampled grid, as `#rrggbb`.
 *
 * ⚠ **A mean, not a "dominant" colour in the k-means sense** — the field it fills is named
 * `dominantColor`, and the difference is worth knowing: a photo of a red car on grey tarmac
 * averages to a muddy mauve, where clustering would return the red. The mean is what this is for
 * though: `personalise()` shifts the app's accent *toward* it at low strength, so a calm, roughly
 * right hue beats a saturated one that fights the design system. Cluster later if a screen ever
 * needs the actual subject colour.
 *
 * Sampled on a fixed grid rather than every pixel: a 1080-wide hero is ~800k pixels, and the answer
 * from 1024 of them is indistinguishable at the tint strengths in play.
 */
internal fun ImageBitmap.averageColorHex(): String {
    val pixels = toPixelMap()
    val stepX = maxOf(1, pixels.width / GRID)
    val stepY = maxOf(1, pixels.height / GRID)
    var r = 0.0
    var g = 0.0
    var b = 0.0
    var n = 0
    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val colour: Color = pixels[x, y]
            // Weight by alpha so a transparent border does not drag the mean toward black.
            val a = colour.alpha
            r += colour.red * a
            g += colour.green * a
            b += colour.blue * a
            n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0) return FALLBACK
    return hex(r / n, g / n, b / n)
}

private fun hex(r: Double, g: Double, b: Double): String {
    fun channel(v: Double): String =
        (v.coerceIn(0.0, 1.0) * 255).roundToInt().toString(16).padStart(2, '0')
    return "#${channel(r)}${channel(g)}${channel(b)}"
}

private const val GRID = 32

/** Mid-grey — only reachable for a zero-pixel bitmap, which is not a thing we expect to see. */
private const val FALLBACK = "#9e9e9e"

/**
 * How a composable gets pixels for a photo it is about to draw (`timeline-photos.md` T-P3).
 *
 * A `CompositionLocal` rather than a parameter threaded through five composables, because it is a
 * *service* — "given a path and a size, give me bytes" — and every surface that renders a photo
 * needs the same one. Absent (previews, the mock, the not-connected surface) every frame falls back
 * to its gradient, which is exactly right for a screen with no proxy behind it.
 */
val LocalPhotoBytes = androidx.compose.runtime.staticCompositionLocalOf<PhotoByteLoaders?> { null }

/** `(uri, width, height) -> encoded bytes`, or `null` when this device cannot produce a preview. */
typealias PhotoByteLoader = suspend (String, Int, Int) -> ByteArray?

/**
 * The two fidelities a photo can be read at, and the cost that separates them.
 *
 * They are one object rather than two composition locals so that a shell cannot provide the cheap
 * one and forget the expensive one — the pair *is* the policy, and the policy is that only a hero
 * may spend a fetch.
 */
class PhotoByteLoaders(
    /**
     * Whatever this device can answer with already: a stored thumbnail class, or a sharp preview
     * computed from bytes that happen to be local. **Never a full-file fetch.** Every feed frame,
     * carousel page and list row uses this.
     */
    val cached: PhotoByteLoader,
    /**
     * Also pulls the **`XLARGE` rung** (~1024 px, ~139 KB) so a hero is not a `LARGE` upscale — see
     * `TimelineRepository.photoBytes`. Only a **hero** may use this: one photo, on a screen the
     * user deliberately opened.
     */
    val hero: PhotoByteLoader,
)

/**
 * Load and decode the photo behind [uri] through [LocalPhotoBytes], once per uri.
 *
 * ⚠⚠ **This is what keeps the feed affordable.** `File.Thumbnail` is `OnDemand` content-plane data,
 * so each of these can cost a blob fetch — and a day's scroll could ask for dozens. It is called
 * from inside `PhotoFrame`, i.e. only for frames that are actually **composed**: a `LazyColumn` row
 * that has not scrolled into view and a carousel page that is not near the current one never ask.
 * `produceState` cancels the fetch when the frame leaves composition, so a fast scroll abandons
 * what it flew past instead of queueing it.
 *
 * ⚠ [heroFidelity] escalates that cost from the ~15 KB `LARGE` rung to the ~139 KB `XLARGE`
 * one — the hero-only path described on [PhotoByteLoaders.hero]. Passing it from anything that can
 * appear more than once on a screen is a bug, not a tuning choice.
 */
@Composable
internal fun rememberLoadedPhoto(
    uri: String?,
    width: Int,
    height: Int,
    heroFidelity: Boolean = false,
): PhotoImage? {
    val loaders = LocalPhotoBytes.current
    val loader = if (heroFidelity) loaders?.hero else loaders?.cached
    // Keyed on the size too: the request is derived from the frame's measured box, so a window
    // resize that crosses a bucket boundary genuinely should re-read at the new size.
    val state = produceState<PhotoImage?>(initialValue = null, uri, loader, width, height) {
        val path = uri
        value = if (path == null || loader == null) {
            null
        } else {
            loader(path, width, height)
                ?.decodePreviewBitmap()
                ?.let { PhotoImage(bitmap = it, dominantColor = it.averageColorHex()) }
        }
    }
    return state.value
}

/**
 * The preview size to ask for, given the frame's measured longest edge in pixels.
 *
 * ⚠⚠ **Square, and derived from the LONGEST edge — not the frame's own width × height.** A photo
 * frame draws with `ContentScale.Crop`, while `GetPreview` *fits* the source inside the requested
 * box. Ask for the frame's exact shape and the two disagree: a 840×220 hero asking for `(840, 220)`
 * gets a 3:2 photo fitted to **330×220**, which the crop then blows back up to 840 wide — the
 * request that looks most obviously right produces the blurriest possible result. A square of the
 * longest edge is the smallest request that survives the crop for a landscape source.
 *
 * Rounded up to a [PREVIEW_BUCKET_PX] bucket so that dragging a desktop window does not re-request
 * on every frame, and so the proxy's `PreviewCache` (keyed by content **and** requested size) holds
 * a handful of entries per photo rather than one per pixel width the window ever had.
 *
 * Clamped: below [PREVIEW_BUCKET_PX] a request would be smaller than the stored `LARGE` thumbnail
 * class and throw away fidelity that is already here for free; above [MAX_PREVIEW_PX] we would be
 * asking a proxy to encode — and ship over IPC — a preview larger than any screen this runs on.
 */
internal fun previewRequestPx(longestEdgePx: Int): Int {
    if (longestEdgePx <= PREVIEW_BUCKET_PX) return PREVIEW_BUCKET_PX
    val rounded = ((longestEdgePx + PREVIEW_BUCKET_PX - 1) / PREVIEW_BUCKET_PX) * PREVIEW_BUCKET_PX
    return rounded.coerceAtMost(MAX_PREVIEW_PX)
}

/** Request granularity. 256 px is also the stored `LARGE` thumbnail class's size, so it is the floor. */
internal const val PREVIEW_BUCKET_PX = 256

/** Beyond a retina desktop hero there is nothing to gain and a large PNG to ship over IPC. */
internal const val MAX_PREVIEW_PX = 2048
