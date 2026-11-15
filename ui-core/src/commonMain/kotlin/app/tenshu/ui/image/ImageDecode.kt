package app.tenshu.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decode preview/thumbnail bytes into a Compose [ImageBitmap].
 *
 * This is the **UI-side** decode: the logic layer (models, view models) carries raw `ByteArray`
 * preview bytes so it stays Compose-free and compiles on iOS, and the Compose screens decode
 * here. SwiftUI shells use `UIImage(data:)` instead and never call this.
 *
 * ⚠ Lives in `:ui-core` because it has three consumers and counting — Drive's file previews,
 * Contacts' avatars, and (as of `timeline-photos.md` T-P1) Timeline's moment hero. Drive's copy
 * was the original and its Android downsampling logic is what moved here; `:contacts-shared-ui`
 * and Control's Home screen still carry their own forks and should fold onto this one when they
 * are next touched.
 */
expect fun decodeToImageBitmap(data: ByteArray, maxEdge: Int = MAX_DECODE_EDGE): ImageBitmap?

/**
 * [decodeToImageBitmap], but **upright or nothing** — for a bitmap that will be stored (an edit, a
 * collage), never only shown.
 *
 * ⚠⚠ Android's decode turns the bitmap itself (`BitmapFactory` ignores EXIF) and, when the turned
 * copy cannot be allocated, falls back to the stored-order bitmap — right for a viewer, a permanent
 * sideways copy for an export (`photos-creation-features.md` §9.1). This one answers `null` there.
 * On desktop and iOS Skia applies the tag during the decode, so the two are the same function.
 */
expect fun decodeToUprightImageBitmap(data: ByteArray, maxEdge: Int = MAX_DECODE_EDGE): ImageBitmap?

/**
 * The default ceiling on a decoded bitmap's longer edge: ≈ 64 MB at `ARGB_8888`, comfortably under
 * Android's ~100 MB `Canvas.MAX_BITMAP_SIZE`. It is a **safety** ceiling — a caller that knows the
 * size it draws should pass that instead, and the viewer does.
 */
const val MAX_DECODE_EDGE: Int = 4096

/**
 * How much to divide a [sourceEdge] by while decoding, given a [maxEdge] the caller wants to draw at
 * and a [ceiling] no decode may exceed whatever it wants.
 *
 * ⚠⚠ **Two rules, and they are not the same rule.** [maxEdge] is a *quality target*: sampling is
 * powers of two, so the naive "divide until under it" loop overshoots — a 2048 px rung drawn on a
 * 1440 px viewport would sample to 1024 and the viewer would upscale a picture it had at full size,
 * the blur this whole ladder exists to remove (`drive-xxlarge-thumbnail-rung.md` §4.5). So sampling
 * stops one step **above** it and the caller scales the remainder exactly.
 *
 * ⚠⚠ [ceiling] is a *memory bound*, and it wins. Without it the first rule is a trap at large
 * targets: a caller that passes the default (a bound, not a size it draws) would decode a 6000 px
 * original at **full resolution** to land "just above" 4096, then hold a scaled copy beside it —
 * ~141 MB peak where the ladder-free code managed 24 MB, on a `minSdk` 24 heap with no `largeHeap`
 * anywhere in this repo. Undershooting is the right answer for a bound and the wrong answer for a
 * target; this function is the only place that distinction lives.
 *
 * ⚠ When the two disagree the ceiling is applied second and wins, so a caller asking for more than
 * [ceiling] gets **at most** it — sampling is powers of two, so the result then lands anywhere in
 * `(ceiling/2, ceiling]` rather than exactly on it.
 *
 * ⚠ The platforms differ where the ceiling forces an undershoot: Android lands as low as
 * `ceiling/2 + 1` because sampling is all it does, while the Skia actuals decode whole and scale to
 * exactly [maxEdge]. That is master's Android behaviour, and it is unreachable from the Photos
 * viewer — the proxy already renders a byte holder down to the request, so viewer bytes are never
 * above it — but Drive hands whole originals to the default and does see it.
 *
 * ⚠⚠ A non-positive [maxEdge] means "no target", **not** "no bound": the ceiling still applies.
 * Returning 1 there was a hole big enough to drive the original defect through — a 20000 px source
 * would have decoded whole — and it is the kind of hole a degenerate-input test blesses rather than
 * catches.
 */
fun decodeSampleSize(sourceEdge: Int, maxEdge: Int, ceiling: Int = MAX_DECODE_EDGE): Int {
    if (sourceEdge <= 0) return 1
    var sample = 1
    // The quality half: never land below what the caller draws at.
    if (maxEdge > 0) while (sourceEdge / (sample * 2) >= maxEdge) sample *= 2
    // The memory half: never decode above the hard ceiling, even if that undershoots the target.
    while (ceiling > 0 && sourceEdge / sample > ceiling) sample *= 2
    return sample
}

/**
 * Decode preview bytes **off the UI thread**. Per-row `previewLoader` lambdas and one-shot hero
 * loads both call this on the bytes their view model returned; a decode on the composition
 * thread janks the scroll it is trying to fill.
 */
suspend fun ByteArray.decodePreviewBitmap(maxEdge: Int = MAX_DECODE_EDGE): ImageBitmap? =
    withContext(Dispatchers.Default) { decodeToImageBitmap(this@decodePreviewBitmap, maxEdge) }
