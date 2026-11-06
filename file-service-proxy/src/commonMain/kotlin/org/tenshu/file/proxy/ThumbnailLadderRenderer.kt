package org.tenshu.file.proxy

/**
 * Renders **every still rung of one upload from a single decode** (`media-processing-pipeline-sweep.md`
 * §4.1, P1a).
 *
 * Without it each rung is its own `GetPreview` call, and each of those reads the whole file back,
 * hashes it for the preview cache, decodes it at full size and scales from there: four full decodes
 * per photo, up to twelve when the JPEG quality step-down re-renders from the original. An
 * implementation decodes **once**, bounded to the largest rung asked for, cascades the ladder down
 * from that bitmap, and answers a step-down by re-encoding the bitmap it already scaled.
 *
 * ⚠⚠ **A platform seam, not an operation parameter** (D12: explicit, never a hidden cache). Teaching
 * `GetPreview` a "render several sizes" param would be an op-param change, which is a wire reshape
 * under House Rule 9. This is injected like [MotionThumbnailRenderer], adds nothing to the wire or the
 * `SchemaShapeBaseline`, and a test can hand the minter a fake.
 *
 * ⚠ **Optional, and the fallback is today's path.** `null` from the factory — Android and iOS until
 * P1b/P1c — keeps the per-rung `GetPreview` render, and so does a `null` answer from [render] for a
 * file this implementation does not handle (a PDF, a video, anything that is not a plain image). The
 * output contract is the per-rung path's, rung for rung:
 * - the same edge rule — longest edge at most `px`, aspect from the **original** dimensions, never
 *   upscaled;
 * - the same format, the same qualities tried in the same order, the same byte cap;
 * - **upright**, as this platform's `GetPreview` is ([ExifUpright]). ⚠⚠ Nothing downstream turns a
 *   rung — no display reads `File.Orientation` — so a rung left in sensor order is shown that way on
 *   every device it syncs to (2026-11-01).
 */
fun interface ThumbnailLadderRenderer {

    /**
     * @param fileName the file's name, for the category gate — the implementation decides what it
     *   handles, and answers `null` for the rest.
     * @param bytes the original, in memory (the upload already holds it).
     * @param rungs what to render, in any order; the answer is in the same order.
     * @return one [Outcome] per rung, index for index, or `null` for "not handled here — render it
     *   per rung". ⚠ A file this renderer handles but cannot decode is [Outcome.Declined] at every
     *   rung, never `null`: the per-rung path would decline it too, after decoding it four times.
     *   A throw is a failure (transient, worth a retry), never a decline.
     */
    fun render(fileName: String, bytes: ByteArray, rungs: List<Rung>): List<Outcome>?

    /**
     * One rung to render.
     *
     * @property px the longest edge.
     * @property format `GetPreview.Params.FORMAT_*`.
     * @property qualities tried in order, stopping at the first encode that fits [maxBytes]; `null` is
     *   the codec's own default. ⚠ The minter owns this list (`STEP_DOWN_QUALITIES`), so the step-down
     *   policy has one home whichever path renders.
     * @property maxBytes the cap; nothing over it is ever stored.
     */
    class Rung(
        val px: Int,
        val format: String,
        val qualities: List<Int?>,
        val maxBytes: Int,
    )

    sealed interface Outcome {
        /** Encoded bytes; the minter re-checks them against the cap. */
        class Encoded(val bytes: ByteArray) : Outcome

        /**
         * Nothing to store: undecodable, an empty encode, or over the cap at every quality. Terminal
         * for this file and rung, exactly as the per-rung path's decline is.
         */
        data object Declined : Outcome
    }
}
