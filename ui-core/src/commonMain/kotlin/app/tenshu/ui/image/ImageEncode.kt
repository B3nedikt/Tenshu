package app.tenshu.ui.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The still-image formats [encodeToBytes] writes.
 *
 * ⚠ **WEBP is lossy only.** No lossless variant is exposed: PNG already is the lossless format,
 * and the platforms disagree at the top of the range — Android below API 30 has only the
 * deprecated `WEBP`, which turns lossless at quality 100, while Skia stays lossy. So WebP quality
 * is capped at 99 on every platform ([encodeQualityFor]), and 100 means "best lossy" everywhere. Not every reader in this repo
 * decodes WebP either, so bytes that leave the device — a collage, an edited copy — are JPEG unless there is a
 * reason (`drive-web-parity.md` W2b: "format is a cross-platform contract").
 */
enum class ImageEncodeFormat(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
}

/** The JPEG/WebP quality when a caller names none. */
const val DEFAULT_ENCODE_QUALITY: Int = 90

/** The platform encoder refused or failed. Never swallowed into empty bytes. */
class ImageEncodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encode this bitmap to [format] bytes — the mirror of [decodeToImageBitmap], for anything the
 * client renders and then stores: a collage, an edited copy (`photos-creation-features.md` CR-1,
 * `photos-editing.md` §3.2 A).
 *
 * ⚠⚠ **Orientation: the output is upright, because the pixels are written exactly as they
 * stand and no orientation tag is written.** An `ImageBitmap` has no orientation of its own, so
 * "upright" is a property of the bitmap handed in. [decodeToImageBitmap] returns upright pixels
 * on every platform — Android applies the EXIF tag itself (`BitmapFactory` ignores it) and Skia
 * applies it on desktop and iOS — so decode → edit → encode is upright end to end, and a crop
 * rectangle means the same pixels everywhere. `ImageEncodeTest` pins both halves with a rotated
 * EXIF fixture.
 * ⚠ A synced thumbnail rung is **not** upright: Android/JVM `GetPreview` mint rungs in raw sensor
 * order with no tag, so a decoded rung comes back raw and encoding it writes that raw order out.
 * Build from the original's bytes, not a rung.
 * ⚠ **One exception, on Android:** if the rotation itself cannot allocate (a second full-size
 * buffer, under memory pressure), the Android decode hands back the bitmap in raw sensor order
 * rather than nothing — right for a viewer, wrong for a file. An editor that stores its output
 * should not rely on the decode alone for orientation until that path can report the miss.
 * ⚠ The decode also caps the long edge at [MAX_DECODE_EDGE] (4096), so a copy of a larger original
 * is smaller than it unless the caller passes a larger `maxEdge`.
 *
 * ⚠⚠ **EXIF: nothing is carried.** The output holds pixels only — no capture time, location,
 * camera, lens or orientation. This is deliberate: the source may be HEIC (EXIF inside an
 * ISO-BMFF item), the platforms' encoders have no common way to write a metadata block, and a
 * copied block would carry the source's orientation onto pixels that are already upright, which
 * rotates them a second time on every reader that honours it. A caller that stores the result
 * passes what it needs to `UploadFile` instead — `SOURCE_MODIFIED_AT` for the capture time,
 * `CAPTURE_LAT_E7` / `CAPTURE_LNG_E7` for the place. Camera and lens are not recoverable that way,
 * so an edited copy shows none — nor an EXIF date-taken row, though `SOURCE_MODIFIED_AT` keeps its
 * place in the feed (`photos-creation-features.md` §9.2).
 *
 * ⚠ **JPEG has no alpha.** Transparent pixels come out **black** on every platform (each encoder
 * drops the alpha of premultiplied pixels). A caller with transparency — a collage's gutters —
 * draws an opaque background first, or encodes PNG.
 *
 * ⚠ **Blocking and allocation-heavy**: a 12 MP JPEG encode is tens of milliseconds and up to two
 * full-size buffers (Skia copies a mutable bitmap before encoding). Call it off the UI thread
 * (`withContext(Dispatchers.Default)`).
 *
 * @param quality 0–100, for JPEG and WebP. PNG is lossless and ignores it.
 * @throws ImageEncodeException when the platform encoder fails. There is no `null`: an encode
 *   that silently produced nothing would store an empty photograph.
 */
expect fun ImageBitmap.encodeToBytes(
    format: ImageEncodeFormat,
    quality: Int = DEFAULT_ENCODE_QUALITY,
): ByteArray

/**
 * The quality each actual hands its platform encoder: the caller's, checked, with WebP capped at 99
 * because Android's pre-API-30 `WEBP` turns lossless at 100 (see [ImageEncodeFormat]).
 */
internal fun encodeQualityFor(format: ImageEncodeFormat, quality: Int): Int {
    require(quality in 0..100) { "Encode quality must be 0–100, was $quality" }
    return if (format == ImageEncodeFormat.WEBP) quality.coerceAtMost(MAX_LOSSY_WEBP_QUALITY) else quality
}

private const val MAX_LOSSY_WEBP_QUALITY = 99
