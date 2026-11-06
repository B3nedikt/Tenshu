@file:Suppress("ClassName")

package app.tenshu.sdk.internal

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise

// Browser-side preview generation: decodes the input bytes via
// `createImageBitmap`, draws scaled into an `OffscreenCanvas`, and re-
// encodes via `convertToBlob` (default `image/webp`, quality 0.85 — same
// shape `:file-service-proxy/.../androidMain/AndroidGetPreviewImplementation.kt`
// produces). Aspect ratio is preserved; if the input is already smaller
// than the requested bounds the output keeps the original dimensions.
//
// All three browser APIs are stable Promise-returning calls; we bridge
// to suspend with `kotlinx.coroutines.await`. Used by `FileSystem.getPreview`
// directly (no protocol round-trip) — the in-process runtime IS the byte
// source on this device, so dispatching the protocol-level `GetPreview`
// op for a local-only fetch is needless indirection. Same call as slice
// 10's `getData`; will revisit when a sync HTTP transport lands and
// previews can be served by another device.
internal suspend fun generatePreview(
    bytes: ByteArray,
    width: Int,
    height: Int,
    mimeType: String = MIME_WEBP,
    /** 0–1, for the lossy encodings; a browser ignores it for PNG. */
    quality: Double = DEFAULT_QUALITY,
): ByteArray? {
    if (bytes.isEmpty()) return null

    // Wrap the bytes in a Blob and decode via `createImageBitmap`. A decode failure
    // (corrupted bytes, unsupported format) rejects through the await and is **absorbed
    // as null** by the catch below — deliberately: `FileSystem.previewBytes` treats null as
    // "nothing previewable here", a real answer, and `getPreview` reports it as
    // `{ ok: true, bytes: null }`.
    //
    // ⚠ So a decode failure never reaches `{ ok: false, error }`. That path is for a *throw* out
    // of `previewBytes`, and only the DECODE is guarded here: everything after this catch —
    // `JsOffscreenCanvas`, `drawImage`, `convertToBlob`, `arrayBuffer` — runs unguarded, so a
    // render or encode failure escapes and does surface as `{ ok: false, error }`, as does a read
    // that broke. The one thing that never does is a blob this browser cannot decode.
    val blob = JsBlob(arrayOf(bytes.unsafeCast<Int8Array>()))
    val bitmap = try {
        createImageBitmap(blob).await()
    } catch (t: Throwable) {
        return null
    }

    val (outW, outH) = scaleDimensions(bitmap.width, bitmap.height, width, height)
    if (outW <= 0 || outH <= 0) return null

    val canvas = JsOffscreenCanvas(outW, outH)
    val ctx = canvas.getContext("2d")
        ?: return null
    ctx.drawImage(bitmap, 0, 0, outW, outH)

    // ⚠ The format is the CALLER's, not this function's. A stored thumbnail class syncs to
    // every other device, so a WebP minted here has to decode on Android, iOS and desktop —
    // and the JVM's ImageIO does not read WebP out of the box. `UploadFileImplementation` asks
    // for JPEG at **all three** rungs precisely so the bytes are universally decodable; only
    // `FileSystem.getPreview`, whose output never leaves this browser, keeps the WebP default.
    //
    // ⚠ `FORMAT_PNG` is `GetPreview`'s *default* when a caller names no format — it is not what
    // any rung is minted as. LIST was PNG until 2026-08-31, when it moved to JPEG: lossless is
    // ~9× larger on photographic content. `drive-web-parity.md`, `tech-debt.md`.
    val encodeOptions: dynamic = js("({})")
    encodeOptions.type = mimeType
    encodeOptions.quality = quality
    val outBlob = canvas.convertToBlob(encodeOptions).await()
    val arrayBuffer = outBlob.arrayBuffer().await()
    return Int8Array(arrayBuffer).unsafeCast<ByteArray>()
}

/** The encode quality when a caller names none — `GetPreview.Params.QUALITY` absent. */
internal const val DEFAULT_QUALITY = 0.85

// Aspect-ratio-preserving fit. If the input fits within the requested
// box the box is honored; otherwise the longer side caps. Mirrors
// `AndroidGetPreviewImplementation.getPreview`'s sizing.
private fun scaleDimensions(
    originalWidth: Int,
    originalHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
): Pair<Int, Int> {
    if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
        return Pair(originalWidth, originalHeight)
    }
    val aspectRatio = originalWidth.toDouble() / originalHeight.toDouble()
    return if (maxWidth / aspectRatio <= maxHeight) {
        Pair(maxWidth, (maxWidth / aspectRatio).toInt())
    } else {
        Pair((maxHeight * aspectRatio).toInt(), maxHeight)
    }
}

// External declarations for the browser APIs we use. Kotlin/JS doesn't
// ship them in `org.w3c.*` for OffscreenCanvas / convertToBlob (the
// stdlib bindings are HTMLCanvasElement-flavored). Declaring directly
// keeps us free of `kotlinx-browser`-style dep churn. `internal` so the
// jsTest sibling can reuse them as fixtures (e.g. generating a real PNG
// via canvas instead of hand-crafting zlib-compressed IDAT chunks).

@JsName("Blob")
internal external class JsBlob(parts: Array<dynamic>) {
    fun arrayBuffer(): Promise<ArrayBuffer>
}

internal external interface JsImageBitmap {
    val width: Int
    val height: Int
}

internal external fun createImageBitmap(image: JsBlob): Promise<JsImageBitmap>

internal external interface JsOffscreenCanvasContext {
    var fillStyle: String
    fun fillRect(x: Int, y: Int, w: Int, h: Int)
    fun drawImage(image: JsImageBitmap, dx: Int, dy: Int, dw: Int, dh: Int)
}

@JsName("OffscreenCanvas")
internal external class JsOffscreenCanvas(width: Int, height: Int) {
    fun getContext(type: String): JsOffscreenCanvasContext?
    fun convertToBlob(options: dynamic = definedExternally): Promise<JsBlob>
}

// Encode targets. WebP is the browser-local default (smallest, and these bytes never
// leave the device); PNG/JPEG are what the synced thumbnail ladder stores.
internal const val MIME_WEBP = "image/webp"
internal const val MIME_PNG = "image/png"
internal const val MIME_JPEG = "image/jpeg"
