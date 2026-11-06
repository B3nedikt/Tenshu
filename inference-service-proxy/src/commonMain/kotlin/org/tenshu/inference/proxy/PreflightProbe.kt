package org.tenshu.inference.proxy

/**
 * The fixed cross-tower check of **one export** (`ios-on-device-inference.md` §7 D12 ②, §14): an
 * image, a caption that describes it and one that does not, and how far apart the two must score.
 *
 * ⚠⚠ **Bound to the bytes it was measured on.** The three digests are the archives' and the
 * vocabulary's. [PreflightProbes.forKey] hands a probe out only for a key that names all three,
 * so another export, or these towers with another vocabulary, is never checked against it. It
 * has no probe, and a model with no probe gets no verdict.
 *
 * ⚠⚠ **Host figures.** `scripts/inference/preflight-probe.py` measured [hostMargin] and the norms
 * through coremltools on the build Mac. [minMargin] is half the narrowest host margin, a stated
 * allowance for what a phone's Neural Engine does differently. No phone has run it.
 *
 * @param id what the record is filed under: the head of the export manifest's sha256.
 * @param tokensDescribes the row the checkpoint's own tokenizer makes of [captionDescribes]. The
 *   preflight requires the app's tokenizer to make the same row from the downloaded vocabulary.
 * @param normBand a raw pooled vector's norm must be within this factor of the host's, either way.
 */
class PreflightProbe(
    val id: String,
    val visionArchiveSha256: String,
    val textArchiveSha256: String,
    val vocabularySha256: String,
    val imageRgbSha256: String,
    val imageTensorSha256: String,
    val captionDescribes: String,
    val captionDoesNot: String,
    val tokensDescribes: IntArray,
    val tokensDoesNot: IntArray,
    val hostMargin: Double,
    val minMargin: Double,
    val hostVisionNorm: Double,
    val hostDescribesNorm: Double,
    val hostDoesNotNorm: Double,
    val normBand: Double,
) {
    fun isFor(key: PreflightKey): Boolean =
        key.runtime.visionArchiveSha256 == visionArchiveSha256 &&
            key.runtime.textArchiveSha256 == textArchiveSha256 &&
            key.vocabularySha256 == vocabularySha256
}

/** Which probe, if any, was measured on the artifacts a key names. */
fun interface PreflightProbeLookup {
    fun forKey(key: PreflightKey): PreflightProbe?
}

/**
 * A set of probes, one per export, looked up by the three digests.
 *
 * ⚠⚠ **One per export a phone may still hold, not one for the newest.** A phone keeps the export
 * it downloaded until it has downloaded the next, and an app update can void its verdict
 * ([PREFLIGHT_REVISION], a new OS build). A build that carried only the newest export's probe
 * would then leave that phone with nothing to renew the verdict by: `NoProbe`, and no search by
 * description until ~709 MB had come down again.
 *
 * @throws IllegalArgumentException for two records with one id, or two for the same artifacts:
 *   which of them a phone was checked against would then depend on their order.
 */
internal class PreflightProbeSet(private val records: List<PreflightProbe>) {
    init {
        require(records.map { it.id }.toSet().size == records.size) { "two probe records share an id: ${records.map { it.id }}" }
        val exports = records.map { Triple(it.visionArchiveSha256, it.textArchiveSha256, it.vocabularySha256) }
        require(exports.toSet().size == exports.size) { "two probe records are for the same three artifacts" }
    }

    val ids: List<String> get() = records.map { it.id }

    /** The probe measured on exactly the artifacts [key] names, or `null`. */
    fun forKey(key: PreflightKey): PreflightProbe? = records.firstOrNull { it.isFor(key) }

    fun byId(id: String): PreflightProbe? = records.firstOrNull { it.id == id }
}

/**
 * The probes this build carries: every record committed under
 * `scripts/inference/preflight-probe-records/`, through the generated [PreflightProbeRegistry].
 */
internal val PreflightProbes = PreflightProbeSet(PreflightProbeRegistry.records)

/**
 * The probe image, drawn: a disc on a plain background, [SIDE] pixels square.
 *
 * ⚠⚠ **`preflight-probe.py`'s `probe_pixels`, twin for twin.** Integer arithmetic in doubled
 * coordinates, so the rim falls on the same pixels on every platform. The phone feeds its vision
 * tower this array through `ImagePreprocessing`, with no file, decoder or resampler in between:
 * what differs from the host is then the Core ML runtime alone. `PreflightProbeImageTest` pins
 * both digests the record holds.
 */
internal object PreflightProbeImage {
    const val SIDE: Int = 256

    private const val BACKGROUND: Int = 0xFFFFFFFF.toInt()
    private const val DISC: Int = 0xFFBC002D.toInt() // (188, 0, 45)
    private const val DISC_RADIUS: Int = 77

    /** Row-major packed ARGB, [SIDE]² entries. */
    fun argb(): IntArray {
        val limit: Int = (2 * DISC_RADIUS) * (2 * DISC_RADIUS)
        return IntArray(SIDE * SIDE) { index ->
            val dx: Int = 2 * (index % SIDE) + 1 - SIDE
            val dy: Int = 2 * (index / SIDE) + 1 - SIDE
            if (dx * dx + dy * dy <= limit) DISC else BACKGROUND
        }
    }
}
