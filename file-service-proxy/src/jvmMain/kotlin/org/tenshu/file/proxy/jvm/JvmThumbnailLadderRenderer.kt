package org.tenshu.file.proxy.jvm

import org.tenshu.core.file.FileCategory
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.isPdf
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.file.proxy.ThumbnailLadderRenderer.Outcome
import java.awt.image.BufferedImage

/**
 * The JVM's [ThumbnailLadderRenderer] (`media-processing-pipeline-sweep.md` §4.1): **one** decode
 * per upload, then the ladder cascaded down from it.
 *
 * - **Decode once, at full resolution** — the same source, and the same peak memory, as each call the
 *   per-rung path makes, so the top rung is that path's render to the pixel.
 * - **Cascade.** Largest rung first; each smaller rung is scaled from the smallest bitmap already
 *   rendered that covers it, never from the original again.
 * - **Step-down re-encodes.** An over-cap JPEG is re-encoded from the bitmap it was scaled to; the
 *   per-rung path re-decodes the original for every quality.
 * - **No hash.** The per-rung path SHA-256s the whole file per rung to key the preview cache; the
 *   upload already computed its content hash once (`UploadFileImplementation`), and nothing here
 *   caches.
 *
 * ⚠⚠ **No `ImageReadParam.setSourceSubsampling`, although §4.1 names it.** It picks pixels and does not
 * filter them, so detail at the subsampling frequency aliases: 1 px stripes read at factor 2 keep one
 * phase and the 2048 px rung comes out **solid black** — under every cap, so nothing downstream can
 * tell, and it would be stored and synced to every peer (pinned by `ThumbnailLadderParityTest`). It would
 * also buy nothing measured: a 12 MP photo needs factor 1, so the whole win is the one decode.
 * A bounded decode belongs here only once it filters.
 *
 * ⚠ Output contract, rung for rung the per-rung `JvmGetPreviewImplementation`'s: dimensions come from
 * the original size through the same [JvmThumbnailRaster.fitWithin], the same encoder, the same
 * qualities, the same cap — and upright like it, because [JvmImageDecoder] turns what it decodes
 * (`ExifUpright`).
 *
 * ⚠ Plain images only. A PDF (first-page raster), a video (no JVM frame decoder) and anything else
 * answer `null`, and the minter renders those rung by rung.
 */
internal class JvmThumbnailLadderRenderer(
    private val decoder: JvmImageDecoder = JvmImageDecoder.Default,
) : ThumbnailLadderRenderer {

    override fun render(fileName: String, bytes: ByteArray, rungs: List<ThumbnailLadderRenderer.Rung>): List<Outcome>? {
        if (isPdf(fileName) || fileCategoryForName(fileName) != FileCategory.Image) return null
        if (rungs.isEmpty()) return emptyList()

        // ⚠ Undecodable is a decline at every rung, as the per-rung path's `return null` is — never
        // `null` here, which would send the same bytes through four more failing decodes.
        val decoded = try {
            decoder.decode(bytes)
        } catch (e: Exception) {
            null
        } ?: return rungs.map { Outcome.Declined }

        val outcomes = arrayOfNulls<Outcome>(rungs.size)
        // Every bitmap rendered so far; a smaller rung scales from the smallest one that covers it.
        val rendered = mutableListOf<BufferedImage>()
        for (index in rungs.indices.sortedByDescending { rungs[it].px }) {
            val rung = rungs[index]
            outcomes[index] = try {
                val size = JvmThumbnailRaster.fitWithin(decoded.width, decoded.height, rung.px, rung.px)
                if (size == null) {
                    Outcome.Declined
                } else {
                    val (outWidth, outHeight) = size
                    val source = rendered.lastOrNull { it.width >= outWidth && it.height >= outHeight }
                        ?: decoded
                    val scaled = JvmThumbnailRaster.scale(source, outWidth, outHeight)
                    rendered += scaled
                    encodeWithinCap(scaled, rung)
                }
            } catch (e: Exception) {
                // The per-rung path's catch-all answers a throwing render with no bytes.
                Outcome.Declined
            }
        }
        return outcomes.map { it ?: Outcome.Declined }
    }

    /**
     * The first of [ThumbnailLadderRenderer.Rung.qualities] whose encode fits the cap. ⚠ An empty
     * encode is the file saying no and stops the walk: a lower quality cannot change that.
     */
    private fun encodeWithinCap(image: BufferedImage, rung: ThumbnailLadderRenderer.Rung): Outcome {
        for (quality in rung.qualities) {
            val encoded = JvmThumbnailRaster.encode(image, rung.format, quality)
            if (encoded == null || encoded.isEmpty()) return Outcome.Declined
            if (encoded.size <= rung.maxBytes) return Outcome.Encoded(encoded)
        }
        return Outcome.Declined
    }
}
