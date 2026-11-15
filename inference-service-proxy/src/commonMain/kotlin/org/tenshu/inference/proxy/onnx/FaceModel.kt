package org.tenshu.inference.proxy.onnx

import org.tenshu.inference.ModelProvenance
import org.tenshu.inference.proxy.FaceAlignmentTemplate
import org.tenshu.inference.proxy.FacePoint

/**
 * Everything about a face checkpoint pair a backend has to get right, in one place — the face twin
 * of [VisionModel], and for its reason: every field is silently wrong rather than loudly
 * wrong. A crop aligned to another model's template, or fed another model's normalisation, embeds
 * without error and lands in the wrong region of the space.
 *
 * ⚠⚠ **The template belongs to the EMBEDDER**, not to the detector: it is the constellation the
 * embedder was trained on, and `alignFace` maps every detector's landmarks onto it. That is why it
 * lives here and why [FaceAlignmentTemplate] refuses a default.
 */
data class FaceModel(
    /** The catalog entry's id — also the provenance stamp every stored template carries. */
    val modelId: String,
    val modelVersion: Int,
    val template: FaceAlignmentTemplate,
    val profile: ImagePreprocessing.Profile,
    /** The embedding width, used to reject an embedder artifact that is not this model. */
    val dimensions: Int,
    /**
     * The smallest raw (pre-normalisation) L2 norm a HUMAN face produces; below it the crop is not a
     * face (`photos-people-and-pets.md` §12.15). ⚠ Model-specific — the norm's scale belongs to the
     * weights — and `null` means no gate. ArcFace-family norms track how face-like the input is, which
     * is the signal; below ~30 px they track image quality instead, which is its limit.
     */
    val minFaceNorm: Float? = null,
) {
    /**
     * ⚠⚠ What `FaceTemplateStore` adopts on its first write and refuses to mix with afterwards. It
     * must move when the weights do — a backend-wide constant here would let a model swap append
     * vectors from a new space onto the old one (`MediaIndexer.currentProvenance`).
     */
    val provenance: ModelProvenance get() = ModelProvenance(modelId, modelVersion)

    companion object {
        /**
         * ⚠⚠ The catalog runtime of the face pair: a MediaPipe `.task` detector beside an ONNX
         * embedder. One entry carries one `runtime`, and neither `"onnx"` nor a new `"mediapipe"`
         * alone describes the pair — so a platform contributes this only when it can execute BOTH,
         * and a desktop build (no MediaPipe) is offered nothing it could not run (IN-D13).
         */
        const val RUNTIME = "onnx+mediapipe"

        /**
         * ⚠⚠ The catalog runtime of the iOS face pair (`ios-face-grouping.md` §12.3): Apple Vision
         * detects — a system framework, so nothing to download — and a Core ML archive embeds. Never
         * bare `"coreml"`: Photos on iOS contributes that for SigLIP alone, and would be offered a
         * ~130 MB face archive nothing in it can run.
         */
        const val COREML_VISION_RUNTIME = "coreml+vision"

        /**
         * ⚠⚠ The iOS detection pipeline's stamp — the third field of every iOS face template's
         * provenance, as `FaceDetectionStrategy.pipelineVersion` is on Android.
         *
         * ⚠ Drawn from **≥ 100**, a range Android never uses (it has used 0, 1–4, 11–14 and 21–24),
         * so an iOS stamp can never read as an Android pipeline. Like Android's, a new Vision
         * revision, mapping or confidence floor takes a NEW number, which is what re-indexes; never
         * reuse one.
         */
        const val VISION_PIPELINE_VERSION = 100

        /**
         * ⚠⚠ The `VNDetectFaceLandmarksRequest` revision [VISION_PIPELINE_VERSION] was measured at
         * (F1a, F1b: `VNDetectFaceLandmarksRequestRevision3`, the 76-point constellation), pinned on
         * every request (FD9). ⚠ The pair moves together: another revision is another pipeline, so
         * a new [VISION_PIPELINE_VERSION] — `FaceModelTest` pins the two side by side. It is also in
         * the face preflight key (`RuntimeFingerprint.detectorRevision`).
         */
        const val VISION_LANDMARKS_REVISION = 3

        /**
         * The published ArcFace 112×112 constellation, `models.md` §5.6 — verified three ways and
         * shared by the whole ArcFace family, AuraFace included.
         */
        val ArcFaceTemplate = FaceAlignmentTemplate(
            leftEye = FacePoint(38.2946f, 51.6963f),
            rightEye = FacePoint(73.5318f, 51.5014f),
            noseTip = FacePoint(56.0252f, 71.7366f),
            mouthLeft = FacePoint(41.5493f, 92.3655f),
            mouthRight = FacePoint(70.7299f, 92.2041f),
            side = 112,
        )

        /**
         * ⭐ **The pinned pair** — `photos-people-and-pets.md` §10 Q1: AuraFace
         * (`fal/AuraFace-v1` `glintr100.onnx`), static QDQ int8 (§12.8b), beside MediaPipe Face
         * Landmarker.
         *
         * ⚠⚠ **Changing this is a re-index of every library on every device**, not an upgrade: the
         * template store is stamped with [provenance] and refuses foreign vectors. Names survive —
         * they are per-photograph `MediaLabel`s — so the cost is scaffolding, never a user's words.
         *
         * ⚠ The profile is SigLIP's by value, not by accident: RGB, (x/255 − 0.5)/0.5, CHW is
         * exactly ArcFace's (x − 127.5)/127.5, and the graph carries no normalisation of its own
         * (its first op is `Conv`, §12.8a).
         */
        val PinnedAuraFace = FaceModel(
            modelId = "auraface-v1-int8",
            modelVersion = 1,
            template = ArcFaceTemplate,
            profile = ImagePreprocessing.Profile.SIGLIP,
            dimensions = 512,
            // ⚠⚠ OFF (§12.15): 15.0 was measured on full-resolution originals, and on the
            // maintainer's library — indexed mostly from 1024 px previews — it rejected 26 real faces
            // alongside 22 non-faces. Re-enable only with a threshold measured on PREVIEWS.
            minFaceNorm = null,
        )

        /**
         * ⭐ The iOS twin of [PinnedAuraFace]: the SAME checkpoint (`glintr100.onnx`) converted to a
         * Core ML fp16 archive (`ios-face-grouping.md` §10.2, §12), run under
         * [COREML_VISION_RUNTIME] beside Apple Vision.
         *
         * ⚠⚠ **Same id and version on purpose** (FD4): one checkpoint is one catalog id across
         * runtimes, so the catalog entry is `(auraface-v1-int8, 1)` under its own runtime. What
         * tells an iOS stamp from an Android one is the pipeline field, [VISION_PIPELINE_VERSION].
         * The `-int8` is wrong for an fp16 file and is a recorded wart (§12.3).
         *
         * Same template, profile and width as Android: the archive's I/O is `input` Float32
         * `[1, 3, 112, 112]` → `embedding` 512 wide (§12.1). No norm gate, for [PinnedAuraFace]'s
         * reason.
         */
        val AuraFaceCoreMl = FaceModel(
            modelId = "auraface-v1-int8",
            modelVersion = 1,
            template = ArcFaceTemplate,
            profile = ImagePreprocessing.Profile.SIGLIP,
            dimensions = 512,
            minFaceNorm = null,
        )
    }
}
